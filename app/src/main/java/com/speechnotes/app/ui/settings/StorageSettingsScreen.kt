package com.speechnotes.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.ExportsStore
import com.speechnotes.app.data.SpeechnotesViewModel

/**
 * Storage subpage: usage breakdown, exported-audio list with inline transport,
 * delete-all and temp cleanup. The directory listing refreshes on entry (not
 * for the lifetime of the Settings tab).
 */
@Composable
fun StorageSettingsScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    SettingsSubpage(title = "Storage", onBack = onBack) {
        SettingsCard("Storage") {
            var confirmExportDelete by remember { mutableStateOf(false) }
            val usage by vm.models.storageUsage.collectAsState()
            LaunchedEffect(Unit) { vm.refreshStorageUsage() }
            usage.forEachIndexed { index, (label, bytes) ->
                if (index > 0) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(label) },
                    trailingContent = { Text(formatBytes(bytes), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            // Exported audio: list + per-file transport (StorageSettings
            // parity — the old Storage tab's whole point). Tapping a row
            // plays it through the shared WavPlayer; the playing row
            // expands the full transport in place.
            val exports by vm.exports.exports.collectAsState()
            val playingURL by vm.wavPlayer.playingURL.collectAsState()
            val wavPaused by vm.wavPlayer.isPaused.collectAsState()
            val wavProgress by vm.wavPlayer.progress.collectAsState()
            val wavTime by vm.wavPlayer.currentTime.collectAsState()
            val wavDuration by vm.wavPlayer.duration.collectAsState()
            val wavRate by vm.wavPlayer.rate.collectAsState()

            LaunchedEffect(Unit) { vm.exports.refresh() }

            if (exports.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Exported audio",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
                )
                exports.forEach { item ->
                    val isThis = playingURL == item.url
                    ListItem(
                        headlineContent = { Text(item.name, maxLines = 1) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(formatBytes(item.bytes))
                                    if (isThis) {
                                        append(" · ")
                                        append(formatClock((wavTime * 1000).toLong()))
                                        if (wavDuration > 0) append(" / " + formatClock((wavDuration * 1000).toLong()))
                                    } else {
                                        append(" · ")
                                        append(java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.createdAt)))
                                    }
                                },
                            )
                        },
                        leadingContent = {
                            IconButton(onClick = { vm.wavPlayer.play(item.url) }) {
                                Icon(
                                    if (isThis && !wavPaused) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isThis && !wavPaused) "Pause" else "Play",
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.wavPlayer.stop(); vm.exports.delete(item) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete export")
                            }
                        },
                        modifier = Modifier.clickable { vm.wavPlayer.play(item.url) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    if (isThis) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Slider(
                                value = (wavProgress ?: 0.0).toFloat(),
                                onValueChange = { vm.wavPlayer.seek(it.toDouble()) },
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                IconButton(onClick = { vm.wavPlayer.skip(-15.0) }) {
                                    Icon(Icons.Default.Replay10, contentDescription = "Back 15 seconds")
                                }
                                IconButton(onClick = { vm.wavPlayer.togglePlay() }) {
                                    Icon(
                                        if (wavPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = if (wavPaused) "Play" else "Pause",
                                    )
                                }
                                IconButton(onClick = { vm.wavPlayer.skip(15.0) }) {
                                    Icon(Icons.Default.Forward10, contentDescription = "Forward 15 seconds")
                                }
                                Text(
                                    "%.2f×".format(wavRate),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        val next = when (wavRate) {
                                            0.5 -> 1.0
                                            1.0 -> 1.5
                                            1.5 -> 2.0
                                            else -> 0.5
                                        }
                                        vm.wavPlayer.setRate(next)
                                    },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                if (confirmExportDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmExportDelete = false },
                        title = { Text("Delete all exports?") },
                        text = { Text("This removes the ${exports.size} exported WAV file(s). Notes are not affected.") },
                        confirmButton = {
                            Button(onClick = {
                                vm.wavPlayer.stop(); vm.exports.deleteAll(); confirmExportDelete = false
                            }) { Text("Delete") }
                        },
                        dismissButton = {
                            Button(onClick = { confirmExportDelete = false }) { Text("Cancel") }
                        },
                    )
                } else {
                    TextButton(
                        onClick = { confirmExportDelete = true },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Delete all exports (${exports.size})") }
                }
            } else {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "No exported audio yet — use Share → Audio (WAV) in a note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Maintenance: clear partial downloads and share temp files
            // (never user content — ExportsStore.clearTemporaryFiles).
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            TextButton(
                onClick = {
                    val freed = ExportsStore.clearTemporaryFiles()
                    vm.showToast("Cleared ${formatBytes(freed)} of temporary files")
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Clear temporary files") }
        }
    }
}
