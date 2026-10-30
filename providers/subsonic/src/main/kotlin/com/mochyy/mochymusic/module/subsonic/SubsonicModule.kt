package com.mochyy.mochymusic.module.subsonic

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleChoice
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SignInSpec
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyy.mochymusic.module.TrackQuery

/**
 * The user's own Subsonic server (Navidrome, Gonic...) directly, or an
 * Octo-Fiesta proxy in front of it, which additionally merges Deezer and
 * Qobuz results as ext-* tracks that stream through the same endpoints.
 *
 * Login is server + username + password (stored in this module's own
 * storage by the Subsonic account screen); auth on the wire is the
 * standard Subsonic token scheme, so no password ever goes in a URL.
 */
class SubsonicModule : SourceModule {

    override val info = ModuleInfo(
        id = Id,
        name = "Subsonic",
        version = "1.0",
        description = "Your own music server (Navidrome, Octo-Fiesta…), including its Deezer catalogue",
    )

    private lateinit var host: ModuleHost

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val signIn: SignInSpec? = null

    override val choices: List<ModuleChoice> = listOf(
        ModuleChoice(
            id = QualityChoice,
            label = "Stream quality",
            options = listOf(
                StreamOption("lossless", "Original", "No transcoding, as the server has it"),
                StreamOption("high", "High", "Up to 320 kbps"),
                StreamOption("balanced", "Balanced", "Up to 128 kbps, kind to mobile data"),
            ),
        ),
    )

    private fun api(): SubsonicApi? {
        val base = host.storage.get("server")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val user = host.storage.get("user")?.takeIf { it.isNotBlank() } ?: return null
        val pass = host.storage.get("pass")?.takeIf { it.isNotBlank() } ?: return null
        return SubsonicApi(base, user, pass, HttpExecutor { url, _ ->
            val r: com.mochyy.mochymusic.module.HttpResponse = host.http(HttpRequest(url))
            r.code to r.text
        })
    }

    override fun accountState(): AccountState {
        val base = host.storage.get("server")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: return AccountState.SignedOut
        val user = host.storage.get("user")?.takeIf { it.isNotBlank() } ?: return AccountState.SignedOut
        return AccountState.SignedIn("$user · $base")
    }

    override fun onSignInHeader(name: String, value: String, url: String): Boolean = false

    override suspend fun verifyAccount(): AccountState {
        val api = api() ?: return AccountState.SignedOut
        return if (runCatching { api.ping() }.getOrDefault(false)) accountState()
        else AccountState.Expired("Server didn't answer")
    }

    override fun signOut() {
        host.storage.set("server", null)
        host.storage.set("user", null)
        host.storage.set("pass", null)
    }

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult {
        if (api() == null) {
            host.log("resolve skipped: no server configured")
            return ResolveResult.Unavailable("No Subsonic server configured")
        }
        return diagnose(track, picks) {}
    }

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        val api = api() ?: return ResolveResult.Unavailable("No Subsonic server configured")
        host.log("resolve '${track.title}' by ${track.artists.joinToString()}")
        report(DiagnosticStep("Find the song", null, "Searching the server…"))
        val found = runCatching {
            val q = "${track.artists.firstOrNull().orEmpty()} ${TrackMatcher.core(track.title)}".trim()
            val songs = api.searchSongs(q).map {
                CatalogTrack(it.id, it.title, it.artist, it.album, it.durationSec * 1000L, null)
            }
            TrackMatcher.ranked(track, songs).firstOrNull()?.track
        }.getOrNull()
        if (found == null) {
            report(DiagnosticStep("Find the song", false, "No match for “${track.title}”"))
            return ResolveResult.NotFound("Not on the Subsonic server")
        }
        report(DiagnosticStep("Find the song", true, "${found.title} · ${found.artist}"))
        val quality = picks[QualityChoice] ?: "lossless"
        val url = when (quality) {
            "high" -> api.streamUrl(found.id, maxBitRate = 320)
            "balanced" -> api.streamUrl(found.id, maxBitRate = 128, format = "mp3")
            else -> api.streamUrl(found.id)
        }
        report(DiagnosticStep("Stream", true, url.substringBefore("?")))
        return ResolveResult.Found(
            SourceStream(
                url = url,
                format = StreamFormat.Progressive,
                sourceLabel = "Subsonic",
                qualityLabel = when (quality) {
                    "high" -> "≤320 kbps"
                    "balanced" -> "≤128 kbps"
                    else -> "Original"
                },
                codec = null,
                matched = MatchedTrack(
                    id = found.id,
                    title = found.title,
                    artist = found.artist,
                    album = found.album,
                    durationMs = found.durationMs,
                    confidence = TrackMatcher.score(track, found),
                ),
            )
        )
    }

    companion object {
        const val Id = "subsonic"
        const val QualityChoice = "quality"
    }
}
