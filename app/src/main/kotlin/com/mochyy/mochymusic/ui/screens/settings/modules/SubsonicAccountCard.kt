// Subsonic account card: server + username + password for the user's own
// Subsonic server (Navidrome, Octo-Fiesta...). Stored in this module's own
// preferences, the same place the SubsonicModule reads them from.

package com.mochyy.mochymusic.ui.screens.settings.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.hilt.navigation.compose.hiltViewModel
import com.mochyy.mochymusic.subsonic.SubsonicViewModel
import com.mochyy.mochymusic.ui.screens.audiolab.LabCard
import com.mochyy.mochymusic.ui.screens.audiolab.LabColors
import com.mochyy.mochymusic.ui.screens.audiolab.LabTextAction
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.R

@Composable
fun SubsonicAccountCard(onChanged: (hasCreds: Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("module_subsonic", android.content.Context.MODE_PRIVATE) }
    var server by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var user by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var savedTick by remember { mutableStateOf(0) }
    val syncViewModel: SubsonicViewModel = hiltViewModel()
    val syncProgress by syncViewModel.progress.collectAsState()

    fun save() {
        prefs.edit {
            putString("server", server.trim().trimEnd('/'))
            putString("user", user.trim())
            putString("pass", pass)
        }
        savedTick++
        onChanged(server.isNotBlank() && user.isNotBlank() && pass.isNotBlank())
    }

    // refresh the toggle/account line above once saved
    androidx.compose.runtime.LaunchedEffect(savedTick) {
        if (savedTick > 0) onChanged(server.isNotBlank() && user.isNotBlank() && pass.isNotBlank())
    }

    LabCard(padding = 16.dp) {
        SubsonicField("Server", server, "https://music.example.com", KeyboardType.Uri, VisualTransformation.None) {
            server = it; save()
        }
        Spacer(Modifier.height(10.dp))
        SubsonicField("Username", user, "you", KeyboardType.Text, VisualTransformation.None) {
            user = it; save()
        }
        Spacer(Modifier.height(10.dp))
        SubsonicField("Password", pass, "••••••••", KeyboardType.Password, PasswordVisualTransformation()) {
            pass = it; save()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Uses the standard Subsonic login. Nothing leaves your phone except to your own server.",
            fontFamily = InterFontFamily, fontSize = 12.sp, color = LabColors.Faint,
        )
        if (server.isNotBlank() || user.isNotBlank() || pass.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            LabTextAction("Forget this server", R.drawable.close) {
                prefs.edit { clear() }
                server = ""; user = ""; pass = ""
                savedTick++
                onChanged(false)
            }
        }
        Spacer(Modifier.height(10.dp))
        val p = syncProgress
        if (p != null && !p.done) {
            Text(
                "Syncing… ${p.artists} artists · ${p.albums} albums · ${p.songs} songs",
                fontFamily = InterFontFamily, fontSize = 13.sp, color = LabColors.Secondary,
            )
        } else {
            SubsonicButton(
                text = if (p?.error != null) "Sync again" else "Sync library now",
                onClick = { syncViewModel.startSync() },
            )
            if (p != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    p.error ?: "Library synced: ${p.artists} artists · ${p.albums} albums · ${p.songs} songs",
                    fontFamily = InterFontFamily, fontSize = 12.sp,
                    color = if (p.error != null) Color(0xFFFF8A80) else LabColors.Faint,
                )
            }
        }
    }
}

@Composable
private fun SubsonicButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (enabled) Color.White else LabColors.Row)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            color = if (enabled) Color.Black else LabColors.Faint)
    }
}

@Composable
private fun SubsonicField(
    label: String,
    value: String,
    hint: String,
    keyboard: KeyboardType,
    visual: VisualTransformation,
    onValueChange: (String) -> Unit,
) {
    Text(label, fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = LabColors.Secondary,
        modifier = Modifier.padding(bottom = 6.dp))
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = visual,
        textStyle = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White),
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp))
                    .background(LabColors.Row).padding(horizontal = 16.dp),
            ) {
                if (value.isEmpty()) Text(hint, color = LabColors.Faint, fontSize = 14.sp, maxLines = 1)
                inner()
            }
        },
    )
}
