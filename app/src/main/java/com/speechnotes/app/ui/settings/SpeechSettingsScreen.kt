package com.speechnotes.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import com.speechnotes.app.data.DownloadState
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.model.VoiceCatalog
import com.speechnotes.app.speech.EngineKind
import com.speechnotes.app.speech.SupertonicEngine
import kotlinx.coroutines.delay

/**
 * Speech engine subpage: fallback banner, engine radio picker, voice pickers
 * (neural + system, iOS SettingsView parity), the Supertonic quality dial and
 * the model download rows. Engine/download state flows are collected only
 * while this page is open.
 */
@Composable
fun SpeechSettingsScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    SettingsSubpage(title = "Speech engine", onBack = onBack) {
        val engine by vm.player.engineKind.collectAsState()
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

        SettingsCard("Speech engine") {
            // The readiness flags must be OBSERVED or the radio subtitles
            // froze on "Model not downloaded yet" after a download
            // finished — isReadyCached() reads plain state, which told
            // Compose nothing.
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
        }

        if (engine != EngineKind.SYSTEM) {
            VoiceRow(vm, engine)
        }
        SystemVoiceRow(vm, engine, neuralActive = engine.isNeural && readyNow(engine))
        if (engine == EngineKind.SUPERTONIC) {
            SupertonicQualityCard(vm)
        }

        SettingsCard("Models") {
            EngineKind.entries.filter { it.isNeural }.forEachIndexed { index, kind ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ModelDownloadRow(vm, kind)
            }
        }
    }
}

/** The neural voice row — opens the shared VoicePickerSheet (iOS SettingsView parity). */
@Composable
private fun VoiceRow(vm: SpeechnotesViewModel, engine: EngineKind) {
    var showPicker by remember { mutableStateOf(false) }
    val kokoroVoice by vm.player.voice.collectAsState()
    val supertonicVoice by vm.player.supertonicVoice.collectAsState()
    val activeVoice = if (engine == EngineKind.SUPERTONIC) supertonicVoice else kokoroVoice
    ListItem(
        headlineContent = { Text("Voice") },
        supportingContent = {
            Text(
                VoiceCatalog.subtitle(activeVoice, engine.key),
                maxLines = 1,
            )
        },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable { showPicker = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    if (showPicker) {
        com.speechnotes.app.ui.voice.VoicePickerSheet(
            vm = vm,
            onDismiss = { showPicker = false },
        )
    }
}

/** The system-voice row — always visible, usable only when system TTS is the active engine. */
@Composable
private fun SystemVoiceRow(vm: SpeechnotesViewModel, engine: EngineKind, neuralActive: Boolean) {
    var showSheet by remember { mutableStateOf(false) }
    val systemVoice by vm.player.systemVoice.collectAsState()
    // The engine connects async: poll briefly so the row can show the
    // voice's display name instead of the raw identifier.
    var installed by remember { mutableStateOf(vm.player.systemVoiceList()) }
    LaunchedEffect(Unit) {
        var tries = 0
        while (installed.isEmpty() && tries < 20) {
            delay(300)
            installed = vm.player.systemVoiceList()
            tries++
        }
    }
    ListItem(
        headlineContent = { Text("System voice") },
        supportingContent = {
            Text(
                when {
                    systemVoice == null -> "Default (English)"
                    else -> installed.firstOrNull { it.name == systemVoice }
                        ?.let { "${it.name} (${it.locale.toLanguageTag()})" }
                        ?: systemVoice!!
                },
                maxLines = 1,
            )
        },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(enabled = !neuralActive) { showSheet = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    if (neuralActive) {
        Text(
            "A neural engine is active — the system voice only applies when Android (system) is selected, or while a neural model is missing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
    if (showSheet) {
        SystemVoiceSheet(
            vm = vm,
            onDismiss = { showSheet = false },
        )
    }
}

/** Supertonic's quality-vs-speed dial (iOS SpeechSettingsView.swift:205-219). */
@Composable
private fun SupertonicQualityCard(vm: SpeechnotesViewModel) {
    val quality by vm.player.supertonicQuality.collectAsState()
    SettingsCard("Supertonic quality") {
        SupertonicEngine.RenderQuality.entries.forEach { option ->
            RadioRow(
                title = option.label.substringBefore(" —"),
                subtitle = when (option) {
                    SupertonicEngine.RenderQuality.HIGH -> "Every chunk with the full 8 denoising steps — the best sound."
                    SupertonicEngine.RenderQuality.BALANCED -> "6 steps — most of High's quality at about 3/4 of its time."
                    SupertonicEngine.RenderQuality.AUTOMATIC -> "Drops to 4 steps only when the device runs critically hot."
                    SupertonicEngine.RenderQuality.FAST -> "Always 4 steps — quickest, with audible artifacts."
                },
                selected = quality == option.raw,
                onClick = { vm.player.setSupertonicQuality(option.raw) },
            )
        }
        Text(
            "Applies from the next chunk — no restart needed. Exports always render full quality.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * System-voice picker sheet: Default (English) first, then every installed
 * TextToSpeech voice sorted by language tag then name (iOS systemVoiceRow
 * parity). The engine connects asynchronously, so the list polls briefly
 * when the sheet opens empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemVoiceSheet(vm: SpeechnotesViewModel, onDismiss: () -> Unit) {
    val selected by vm.player.systemVoice.collectAsState()
    var voices by remember { mutableStateOf(vm.player.systemVoiceList()) }
    LaunchedEffect(Unit) {
        var tries = 0
        while (voices.isEmpty() && tries < 20) {
            delay(300)
            voices = vm.player.systemVoiceList()
            tries++
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp)) {
                Text("System voice", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Used by Android's built-in speech. Voices installed system-wide (including quality tiers from Settings → Accessibility → Text-to-speech) appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item(key = "default") {
                    ListItem(
                        headlineContent = { Text("Default (English)") },
                        leadingContent = {
                            if (selected == null) {
                                Icon(Icons.Default.Check, contentDescription = "Selected")
                            }
                        },
                        modifier = Modifier.clickable {
                            vm.player.setSystemVoice(null)
                            onDismiss()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                items(voices, key = { it.name }) { voice ->
                    val title = "${voice.name} (${voice.locale.toLanguageTag()})"
                    ListItem(
                        headlineContent = { Text(title, maxLines = 1) },
                        leadingContent = {
                            if (selected == voice.name) {
                                Icon(Icons.Default.Check, contentDescription = "Selected")
                            }
                        },
                        modifier = Modifier.clickable {
                            vm.player.setSystemVoice(voice.name)
                            onDismiss()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
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
