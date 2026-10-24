package com.speechnotes.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import com.speechnotes.app.data.DownloadState
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.speech.EngineKind

/**
 * Speech engine subpage: fallback banner, engine radio picker and the three
 * model download rows. Engine/download state flows are collected only while
 * this page is open.
 */
@Composable
fun SpeechSettingsScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    SettingsSubpage(title = "Speech engine", onBack = onBack) {
        SettingsCard("Speech engine") {
            val engine by vm.engineKind.collectAsState()
            // The readiness flags must be OBSERVED or the radio subtitles
            // froze on "Model not downloaded yet" after a download
            // finished — isReadyCached() reads plain state, which told
            // Compose nothing.
            val kokoroState by vm.models.kokoroState.collectAsState()
            val kokoroSmallState by vm.models.kokoroSmallState.collectAsState()
            val supertonicState by vm.models.supertonicState.collectAsState()
            val readyNow = { kind: EngineKind ->
                when (kind) {
                    EngineKind.KOKORO -> kokoroState is DownloadState.Ready
                    EngineKind.KOKORO_SMALL -> kokoroSmallState is DownloadState.Ready
                    EngineKind.SUPERTONIC -> supertonicState is DownloadState.Ready
                    EngineKind.SYSTEM -> true
                }
            }
            val anyFallback = engine.isNeural && !readyNow(engine)
            if (anyFallback) {
                FallbackBanner(engine)
            }
            EngineKind.entries.forEach { kind ->
                val ready = readyNow(kind)
                RadioRow(
                    title = kind.label,
                    subtitle = when {
                        kind == EngineKind.SYSTEM -> "Instant — no download"
                        ready -> "Ready · fully offline"
                        else -> "Model not downloaded yet"
                    },
                    selected = engine == kind,
                    onClick = { vm.setEngineKind(kind) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            EngineKind.entries.filter { it.isNeural }.forEach { kind ->
                ModelDownloadRow(vm, kind)
            }
        }
    }
}

@Composable
internal fun FallbackBanner(engine: EngineKind) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            // Same disambiguation as the download rows: "Kokoro — small · …"
            // truncates to just "Kokoro" before the dash.
            "${if (engine == EngineKind.KOKORO_SMALL) "Kokoro (small)" else engine.label.substringBefore(" —")} " +
                "is selected but its model isn't downloaded — " +
                "playback uses the Android system voice until then.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun ModelDownloadRow(vm: SpeechnotesViewModel, engine: EngineKind) {
    val stateFlow = when (engine) {
        EngineKind.KOKORO -> vm.models.kokoroState
        EngineKind.KOKORO_SMALL -> vm.models.kokoroSmallState
        EngineKind.SUPERTONIC -> vm.models.supertonicState
        else -> return
    }
    val state by stateFlow.collectAsState()

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    // "Kokoro" and "Kokoro — small · uint8" both truncate to
                    // "Kokoro" before the dash — the two download rows were
                    // indistinguishable.
                    when (engine) {
                        EngineKind.KOKORO_SMALL -> "Kokoro (small)"
                        else -> engine.label.substringBefore(" —")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        supportingContent = {
            when (val s = state) {
                is DownloadState.Downloading -> "Downloading… ${(s.progress * 100).toInt()}%"
                is DownloadState.Failed -> s.message
                is DownloadState.Ready -> "Ready — fully offline"
                DownloadState.NotDownloaded -> "Not downloaded"
            }
        },
        trailingContent = {
            when (val s = state) {
                is DownloadState.NotDownloaded -> {
                    Button(onClick = { vm.downloadModel(engine) }) { Text("Download") }
                }
                is DownloadState.Downloading -> {
                    LinearProgressIndicator(progress = { s.progress.toFloat() }, modifier = Modifier.size(56.dp, 8.dp))
                }
                is DownloadState.Failed -> {
                    OutlinedButton(onClick = { vm.downloadModel(engine) }) { Text("Retry") }
                }
                is DownloadState.Ready -> {
                    OutlinedButton(onClick = { vm.models.deleteModel(engine) }) { Text("Delete") }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
