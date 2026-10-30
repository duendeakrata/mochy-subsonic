// ModulesViewModel.kt

package com.mochyy.mochymusic.viewmodels

import android.media.MediaDrm
import android.media.NotProvisionedException
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.StreamDrm
import com.mochyy.mochymusic.module.TrackQuery
import com.mochyy.mochymusic.modules.SourceModules
import com.mochyy.mochymusic.playback.custom.WidevineDrm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The Modules settings: each module's account, switch, quality, and its playback test. */
@HiltViewModel
class ModulesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val modules: SourceModules,
) : ViewModel() {

    val entries = modules.entries

    /** the provider order: module ids and "youtube" */
    val order = modules.order

    fun setOrder(ids: List<String>) = modules.setOrder(ids)

    /** YouTube Music's switch; it's not a module, so it isn't in [entries] */
    val youTubeEnabled = modules.youTubeEnabled

    fun isUsable(id: String): Boolean = modules.isUsable(id)

    fun nameOf(id: String): String = modules.nameOf(id)

    private val _steps = MutableStateFlow<List<DiagnosticStep>>(emptyList())
    val steps: StateFlow<List<DiagnosticStep>> = _steps.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private var testJob: Job? = null
    private var signedIn = false

    init {
        modules.refresh()
    }

    fun setEnabled(id: String, on: Boolean) = modules.setEnabled(id, on)

    fun setPick(id: String, choiceId: String, optionId: String) = modules.setPick(id, choiceId, optionId)

    fun signOut(id: String) {
        modules.module(id)?.signOut()
        modules.setEnabled(id, false)
        _steps.value = emptyList()
    }

    fun verify(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { modules.module(id)?.verifyAccount() } }
            modules.refresh()
        }
    }

    /** enables the Subsonic module once the user has saved server credentials */
    fun enableSubsonicIfConfigured(id: String) {
        if (id != "subsonic") return
        val prefs = context.getSharedPreferences("module_subsonic", android.content.Context.MODE_PRIVATE)
        if (prefs.getString("server", null)?.isNotBlank() == true &&
            prefs.getString("user", null)?.isNotBlank() == true &&
            prefs.getString("pass", null)?.isNotBlank() == true
        ) {
            modules.setEnabled(id, true)
        }
    }

    /** a header from the sign-in page; [onDone] runs once, when the account is in */
    fun onSignInHeader(id: String, name: String, value: String, url: String, onDone: (AccountState) -> Unit) {
        val module = modules.module(id) ?: return
        if (signedIn || !module.onSignInHeader(name, value, url)) return
        signedIn = true
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { runCatching { module.verifyAccount() }.getOrNull() } ?: module.accountState()
            // signing in is asking for it: the module plays from now on
            if (state is AccountState.SignedIn) modules.setEnabled(id, true) else modules.refresh()
            onDone(state)
        }
    }

    fun resetSignIn() {
        signedIn = false
    }

    /** the module's own steps, then whether this phone's Widevine actually gets a license */
    fun runTest(id: String, track: TrackQuery) {
        val module = modules.module(id) ?: return
        testJob?.cancel()
        _steps.value = emptyList()
        _testing.value = true
        testJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { module.diagnose(track, modules.picks(id)) { report(it) } }
                    .getOrElse { e ->
                        report(DiagnosticStep("Error", false, e.message ?: e.javaClass.simpleName))
                        null
                    }
            }
            val stream = (result as? ResolveResult.Found)?.stream
            val drm = stream?.drm as? StreamDrm.Widevine
            var licensed = stream != null && drm == null
            if (drm != null) {
                report(DiagnosticStep("License", null, "Asking for a license on this phone…"))
                val outcome = withContext(Dispatchers.IO) { runCatching { checkWidevine(drm) } }
                licensed = outcome.isSuccess
                report(
                    outcome.fold(
                        onSuccess = { DiagnosticStep("License", true, it) },
                        onFailure = { DiagnosticStep("License", false, it.message ?: it.javaClass.simpleName) },
                    )
                )
            }
            // a license alone isn't proof: Apple's enhanced streams licensed fine and then
            // decrypted nothing, so the test ends by really decoding the start of the song
            if (stream != null && licensed) {
                report(DiagnosticStep("Play", null, "Decoding the start of the song, muted…"))
                val outcome = runCatching { checkPlayback(stream) }
                report(
                    outcome.fold(
                        onSuccess = { DiagnosticStep("Play", true, it) },
                        onFailure = { DiagnosticStep("Play", false, it.message ?: it.javaClass.simpleName) },
                    )
                )
            }
            modules.refresh()
            _testing.value = false
        }
    }

    private fun report(step: DiagnosticStep) {
        // release builds drop Timber; this shows in `adb logcat -s ModuleTest`
        if (step.ok != null) android.util.Log.i("ModuleTest", "${if (step.ok == true) "OK" else "FAIL"} ${step.name}: ${step.detail}")
        val list = _steps.value.toMutableList()
        val at = list.indexOfFirst { it.name == step.name }
        if (at >= 0) list[at] = step else list += step
        _steps.value = list
    }

    /**
     * Plays the stream in a throwaway, muted player until two seconds have been decoded: the
     * whole path playback takes (playlist, segments, DRM sessions, the decoder), nothing faked.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun checkPlayback(stream: com.mochyy.mochymusic.module.SourceStream): String {
        val drm = stream.drm as? StreamDrm.Widevine
        val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            androidx.media3.datasource.DefaultDataSource.Factory(context)
        )
        if (drm != null) factory.setDrmSessionManagerProvider { WidevineDrm.sessionManager(drm.license) }
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).setMediaSourceFactory(factory).build()
        var error: androidx.media3.common.PlaybackException? = null
        try {
            player.volume = 0f
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    error = e
                }
            })
            val item = androidx.media3.common.MediaItem.Builder()
                .setUri(stream.url)
                .apply {
                    if (stream.format == com.mochyy.mochymusic.module.StreamFormat.Hls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    if (drm != null) setDrmConfiguration(
                        androidx.media3.common.MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setMultiSession(true).build()
                    )
                }
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.play()
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                error?.let { e ->
                    android.util.Log.w("ModuleTest", "Test playback failed", e)
                    throw java.io.IOException(generateSequence<Throwable>(e) { it.cause }.last().message ?: e.errorCodeName)
                }
                if (player.currentPosition >= 2_000) return "Decoded ${player.currentPosition / 1000} s here (${stream.qualityLabel})"
                kotlinx.coroutines.delay(200)
            }
            throw java.io.IOException("Didn't start playing within 20 s")
        } finally {
            player.release()
        }
    }

    /**
     * Opens a Widevine session on the device's own CDM and has every key of the stream licensed,
     * the same exchange playback makes, without playing anything.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun checkWidevine(drm: StreamDrm.Widevine): String {
        val mediaDrm = MediaDrm(C.WIDEVINE_UUID)
        var session: ByteArray? = null
        try {
            val level = runCatching { mediaDrm.getPropertyString("securityLevel") }.getOrNull()
            session = try {
                mediaDrm.openSession()
            } catch (e: NotProvisionedException) {
                val request = mediaDrm.provisionRequest
                mediaDrm.provideProvisionResponse(WidevineDrm.provision(request.defaultUrl, request.data))
                mediaDrm.openSession()
            }
            drm.initData.forEach { pssh ->
                val request = mediaDrm.getKeyRequest(session, pssh, "audio/mp4", MediaDrm.KEY_TYPE_STREAMING, null)
                mediaDrm.provideKeyResponse(session, drm.license.acquire(request.data))
            }
            return "Licensed on this phone (Widevine ${level ?: "?"}, ${drm.initData.size} keys)"
        } finally {
            session?.let { runCatching { mediaDrm.closeSession(it) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mediaDrm.close() else @Suppress("DEPRECATION") mediaDrm.release()
        }
    }
}
