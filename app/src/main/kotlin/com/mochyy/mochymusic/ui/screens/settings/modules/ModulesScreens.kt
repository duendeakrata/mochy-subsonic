// settings → modules: the sources mochy plays from besides youtube, each with its account, switch, quality and a playback test

package com.mochyy.mochymusic.ui.screens.settings.modules

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.TrackQuery
import com.mochyy.mochymusic.modules.ModuleEntry
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.screens.audiolab.LabCard
import com.mochyy.mochymusic.ui.screens.audiolab.LabChip
import com.mochyy.mochymusic.ui.screens.audiolab.LabChips
import com.mochyy.mochymusic.ui.screens.audiolab.LabColors
import com.mochyy.mochymusic.ui.screens.audiolab.LabSectionLabel
import com.mochyy.mochymusic.ui.screens.audiolab.LabTextAction
import com.mochyy.mochymusic.ui.screens.audiolab.LabToggleRow
import com.mochyy.mochymusic.ui.screens.settings.importsync.CaptureWebView
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.viewmodels.ModulesViewModel

const val ModulesRoute = "modules"
const val ModuleRoutePattern = "module/{id}"
const val ModuleSignInRoutePattern = "module_signin/{id}"
fun moduleRoute(id: String) = "module/$id"
fun moduleSignInRoute(id: String) = "module_signin/$id"

private fun logoFor(id: String): Int? = when (id) {
    "applemusic" -> R.drawable.provider_apple_music
    "monochrome" -> R.drawable.provider_monochrome
    else -> null
}

private fun statusOf(entry: ModuleEntry): String = when (val account = entry.account) {
    AccountState.SignedOut -> "Not signed in"
    is AccountState.Expired -> "Sign in again · ${account.reason}"
    is AccountState.SignedIn -> if (entry.enabled) "On · ${account.label}" else "Off · ${account.label}"
}

@Composable
fun ModulesScreen(navController: NavController, viewModel: ModulesViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsState()
    SubSettingsScaffold(title = "Modules", onBack = { navController.navigateUp() }) {
        Text(
            "Sources Mochy can play from besides YouTube. Each one is its own module: built in for now, installable on its own later.",
            fontFamily = InterFontFamily, fontSize = 14.sp, lineHeight = 19.sp, color = LabColors.Secondary,
        )
        Spacer(Modifier.height(16.dp))
        entries.forEach { entry ->
            val id = entry.module.info.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(LabColors.Card)
                    .clickable { navController.navigate(moduleRoute(id)) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModuleLogo(id)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.module.info.name, fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
                    Text(statusOf(entry), fontFamily = InterFontFamily, fontSize = 12.sp, color = LabColors.Secondary)
                    Text(
                        (if (entry.packageName == null) "Built in" else "Installed") + " · v${entry.module.info.version}",
                        fontFamily = InterFontFamily, fontSize = 11.sp, color = LabColors.Faint,
                    )
                }
                Icon(painterResource(R.drawable.navigate_next), null, tint = LabColors.Secondary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun ModuleLogo(id: String, size: Int = 46) {
    val logo = logoFor(id)
    Box(
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.26f).dp)).background(LabColors.Row),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Image(painterResource(logo), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp))
        } else {
            Icon(painterResource(R.drawable.music_note), null, tint = Color.White, modifier = Modifier.size((size / 2).dp))
        }
    }
}

@Composable
fun ModuleScreen(navController: NavController, id: String, viewModel: ModulesViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val entry = entries.firstOrNull { it.module.info.id == id }
    val playerConnection = LocalPlayerConnection.current
    val playing = playerConnection?.mediaMetadata?.collectAsState()?.value

    SubSettingsScaffold(
        title = entry?.module?.info?.name ?: "Module",
        backLabel = "Modules",
        onBack = { navController.navigateUp() },
    ) {
        if (entry == null) return@SubSettingsScaffold
        val module = entry.module
        val signedIn = entry.account is AccountState.SignedIn

        LabCard(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModuleLogo(id, size = 56)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(module.info.name, fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                    Text(statusOf(entry), fontFamily = InterFontFamily, fontSize = 13.sp, color = LabColors.Secondary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(module.info.description, fontFamily = InterFontFamily, fontSize = 13.sp, lineHeight = 18.sp, color = LabColors.Secondary)
            Spacer(Modifier.height(14.dp))
            if (!signedIn && module.signIn != null) {
                PrimaryButton(
                    text = if (entry.account is AccountState.Expired) "Sign in again" else "Sign in to ${module.info.name}",
                    onClick = { navController.navigate(moduleSignInRoute(id)) },
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabTextAction(if (module.signIn != null) "Check account" else "Check server", R.drawable.refresh) { viewModel.verify(id) }
                    Spacer(Modifier.weight(1f))
                    if (module.signIn != null) LabTextAction("Sign out", R.drawable.close) { viewModel.signOut(id) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        LabCard(padding = 10.dp) {
            LabToggleRow(
                title = "Play from ${module.info.name}",
                subtitle = "Songs play from ${module.info.name} when it has them, in the order set under Audio Quality → Provider order",
                checked = entry.enabled && signedIn,
                enabled = signedIn,
                onCheckedChange = { viewModel.setEnabled(id, it) },
            )
        }
        Spacer(Modifier.height(16.dp))

        module.choices.forEach { choice ->
            if (choice.options.isEmpty()) return@forEach
            // a pick the module no longer offers (spatial, before it went) reads as the default
            val picked = entry.picks[choice.id]?.takeIf { p -> choice.options.any { it.id == p } } ?: choice.options.first().id
            LabSectionLabel(choice.label)
            LabChips(
                chips = choice.options.map { LabChip(it.id, it.label) },
                selectedKey = picked,
                onSelect = { viewModel.setPick(id, choice.id, it) },
            )
            choice.options.firstOrNull { it.id == picked }?.let { option ->
                Text(option.description, fontFamily = InterFontFamily, fontSize = 12.sp, color = LabColors.Secondary,
                    modifier = Modifier.padding(top = 8.dp, start = 6.dp))
            }
            Spacer(Modifier.height(16.dp))
        }

        if (id == "subsonic") {
            LabSectionLabel("Server account")
            SubsonicAccountCard { viewModel.verify(id) }
            Spacer(Modifier.height(16.dp))
        }

        LabSectionLabel("Test playback")
        LabCard(padding = 14.dp) {
            val track = playing?.let {
                TrackQuery(
                    title = it.title,
                    artists = it.artists.map { a -> a.name },
                    album = it.album?.title,
                    durationMs = it.duration.takeIf { d -> d > 0 }?.times(1000L),
                )
            } ?: TrackQuery("Espresso", listOf("Sabrina Carpenter"), durationMs = 175_000)
            Text(
                "Runs every step on this phone with “${track.title}”: finding the song, the stream${if (module.signIn != null) ", a license from this phone's Widevine" else ""}, and decoding the start of the song, muted.",
                fontFamily = InterFontFamily, fontSize = 13.sp, lineHeight = 18.sp, color = LabColors.Secondary,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(text = if (testing) "Testing…" else "Run the test", enabled = signedIn && !testing) {
                viewModel.runTest(id, track)
            }
            Column(Modifier.animateContentSize()) {
                steps.forEach { StepRow(it) }
            }
            if (!testing && steps.isNotEmpty() && steps.all { it.ok == true }) {
                Text(
                    "Everything works. Turn on “Play from ${module.info.name}” and play anything: a toast says where each song comes from.",
                    fontFamily = InterFontFamily, fontSize = 13.sp, color = Color.White,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (id == "applemusic") Text(
            "Not available: lossless, Dolby Atmos and Apple's Spatial mix (Apple only encodes those for its own DRM, FairPlay) and downloads (web players get no offline licenses).",
            fontFamily = InterFontFamily, fontSize = 12.sp, lineHeight = 17.sp, color = LabColors.Faint,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun StepRow(step: DiagnosticStep) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (step.ok) {
                null -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
                true -> Icon(painterResource(R.drawable.check), null, tint = Color(0xFF8FD98B), modifier = Modifier.size(18.dp))
                false -> Icon(painterResource(R.drawable.close), null, tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(step.name, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
            Text(step.detail, fontFamily = InterFontFamily, fontSize = 12.sp, lineHeight = 16.sp, color = LabColors.Secondary)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) Color.White else LabColors.Row)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            color = if (enabled) Color.Black else LabColors.Faint)
    }
}

/** the service's own sign-in page; it closes itself once the module has its account */
@Composable
fun ModuleSignInScreen(navController: NavController, id: String, viewModel: ModulesViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsState()
    val module = entries.firstOrNull { it.module.info.id == id }?.module ?: return
    val spec = module.signIn ?: return
    DisposableEffect(Unit) {
        viewModel.resetSignIn()
        onDispose { }
    }
    CaptureWebView(
        title = spec.title,
        subtitle = spec.subtitle,
        startUrl = spec.startUrl,
        playerOrigins = spec.sessionOrigins,
        desktopSite = spec.desktopSite,
        onHeader = { name, value, url ->
            viewModel.onSignInHeader(id, name, value, url) { navController.navigateUp() }
        },
        onTick = {},
        onExit = { navController.navigateUp() },
    )
}
