package com.speechnotes.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.SpeechnotesViewModel

/**
 * Settings root — a plain navigation list into one subpage per area, mirroring
 * the iOS app's grouped settings stacks. The old single flat screen composed
 * all nine cards (stats heatmap + goals poller included) whenever the tab was
 * open; each area's work now runs only while its own page is showing.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    vm: SpeechnotesViewModel,
    onOpen: (String) -> Unit,
    onOpenLogs: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsLinkRow(
                icon = Icons.Default.RecordVoiceOver,
                title = "Speech engine",
                subtitle = "Engine, voices' models, downloads",
            ) { onOpen("settings/speech") }
            SettingsLinkRow(
                icon = Icons.Default.Notes,
                title = "Notes",
                subtitle = "Markdown, read-along, haptics, image caching",
            ) { onOpen("settings/notes") }
            SettingsLinkRow(
                icon = Icons.Default.Palette,
                title = "Appearance",
                subtitle = "Accent, theme, reader, spacing",
            ) { onOpen("settings/appearance") }
            SettingsLinkRow(
                icon = Icons.Default.GraphicEq,
                title = "Stats & goals",
                subtitle = "Reading time, streak, heatmap, goals",
            ) { onOpen("settings/stats") }
            SettingsLinkRow(
                icon = Icons.Default.Wifi,
                title = "BookDrop",
                subtitle = "Receive books from nearby devices",
            ) { onOpen("settings/bookdrop") }
            SettingsLinkRow(
                icon = Icons.Default.Backup,
                title = "Backup",
                subtitle = "Joplin .jex export and import",
            ) { onOpen("settings/backup") }
            SettingsLinkRow(
                icon = Icons.Default.Storage,
                title = "Storage",
                subtitle = "Usage breakdown, exported audio",
            ) { onOpen("settings/storage") }
            SettingsLinkRow(
                icon = Icons.Default.Terminal,
                title = "Diagnostics",
                subtitle = "Engine, store and playback logs",
                onClick = onOpenLogs,
            )
            SettingsLinkRow(
                icon = Icons.Default.Info,
                title = "About",
                subtitle = "Version and credits",
            ) { onOpen("settings/about") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Version / credits page. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    SettingsSubpage(title = "About", onBack = onBack) {
        SettingsCard("About") {
            ListItem(
                headlineContent = { Text("Speechnotes for Android") },
                supportingContent = {
                    Text("Offline TTS notes — the Android port of speechnotes-ios. v1.1.4")
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("Developer: TheSageZM · MIT") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}
