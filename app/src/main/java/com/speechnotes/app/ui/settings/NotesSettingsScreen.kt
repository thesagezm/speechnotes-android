package com.speechnotes.app.ui.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.SpeechnotesViewModel

/** Notes subpage: markdown, read-along, haptics, image caching. */
@Composable
fun NotesSettingsScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    val renderMarkdown by vm.renderMarkdownFlow.collectAsState()
    // Haptics + image cache are plain prefs toggles; read once (this page is
    // short-lived, and these booleans must not trigger full recomposition).
    val hapticsState = remember {
        mutableStateOf(
            vm.context.getSharedPreferences("speechnotes", android.content.Context.MODE_PRIVATE)
                .getBoolean("hapticsEnabled", true),
        )
    }
    var hapticsEnabled by hapticsState
    val imageCacheState = remember {
        mutableStateOf(
            vm.context.getSharedPreferences("speechnotes", android.content.Context.MODE_PRIVATE)
                .getBoolean("imageCacheAutomatic", true),
        )
    }
    var imageCacheAutomatic by imageCacheState
    // Persist both through the VM's setter so the prefix of the file stays stable.
    fun persistBool(key: String, value: Boolean) {
        vm.context.getSharedPreferences("speechnotes", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
    }
    LaunchedEffect(hapticsEnabled) {
        com.speechnotes.app.ui.theme.Haptics.enabled = hapticsEnabled
        persistBool("hapticsEnabled", hapticsEnabled)
    }
    LaunchedEffect(imageCacheAutomatic) {
        persistBool("imageCacheAutomatic", imageCacheAutomatic)
    }

    SettingsSubpage(title = "Notes", onBack = onBack) {
        SettingsCard("Notes") {
            ToggleRow(
                title = "Render markdown",
                subtitle = "Preview rendered markdown; speech reads the plain text.",
                checked = renderMarkdown,
                onCheckedChange = { vm.renderMarkdown = it },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val readAlong by vm.readAlongEnabledFlow.collectAsState()
            ToggleRow(
                title = "Read-along",
                subtitle = "Highlight the text while a note is being read.",
                checked = readAlong,
                onCheckedChange = { vm.readAlongEnabled = it },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ToggleRow(
                title = "Haptic feedback",
                subtitle = "The short vibration on taps and presses.",
                checked = hapticsEnabled,
                onCheckedChange = {
                    hapticsEnabled = it
                    com.speechnotes.app.ui.theme.Haptics.enabled = it
                },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ToggleRow(
                title = "Image caching",
                subtitle = "Automatic downloads every web image when a note opens; Manual only when an image is displayed.",
                checked = imageCacheAutomatic,
                onCheckedChange = { imageCacheAutomatic = it },
            )
        }
    }
}
