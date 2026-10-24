package com.speechnotes.app.ui.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.model.VoiceCatalog
import com.speechnotes.app.speech.EngineKind

/**
 * Searchable, sectioned voice picker with tap-to-audition, recents and —
 * for Supertonic — the language selector. Ported from VoicePickerSheet.swift
 * (speechnotes-ios).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(vm: SpeechnotesViewModel, onDismiss: () -> Unit) {
    val engine by vm.player.engineKind.collectAsState()
    val activeVoice by (if (engine == EngineKind.SUPERTONIC) vm.player.supertonicVoice else vm.player.voice).collectAsState()
    val supertonicLang by vm.player.supertonicLang.collectAsState()
    val auditioning by vm.player.auditioningVoice.collectAsState()
    var query by remember { mutableStateOf("") }

    val descriptors = remember(engine) { VoiceCatalog.descriptors(engine.key) }
    val filtered = remember(query, descriptors) {
        if (query.isBlank()) descriptors
        else descriptors.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.id.contains(query, ignoreCase = true) ||
                it.subtitle.contains(query, ignoreCase = true)
        }
    }
    val sections = remember(filtered) {
        val groups = LinkedHashMap<String, MutableList<com.speechnotes.app.model.VoiceDescriptor>>()
        for (d in filtered) {
            val key = listOfNotNull(d.accent, d.gender).joinToString(" ").ifEmpty { "Voices" }
            groups.getOrPut(key) { mutableListOf() }.add(d)
        }
        groups.map { it.key to it.value.toList() }
    }
    // Recents (iOS parity): the last five picked voices for this engine,
    // shown first so a returning user is one tap from their usual voice.
    val recents = remember(engine) {
        val ids = vm.player.recentVoices(engine).toSet()
        descriptors.filter { it.id in ids }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // The system engine cannot use the Kokoro/Supertonic catalogs —
        // descriptors() falls through to Kokoro, which listed 28 voices the
        // engine can't speak with. System voices get their own list
        // (iOS routes the system engine to a dedicated menu too).
        if (engine == EngineKind.SYSTEM) {
            SystemVoiceList(vm, onDismiss)
            return@ModalBottomSheet
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = when (engine) {
                    EngineKind.SUPERTONIC -> "Supertonic voice"
                    EngineKind.KOKORO_SMALL -> "Kokoro voice (small model)"
                    else -> "Kokoro voice"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))

            if (engine == EngineKind.SUPERTONIC) {
                LanguageBar(
                    current = supertonicLang,
                    onSelect = { vm.player.setSupertonicLang(it) },
                )
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search voices") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))

            if (!vm.isModelReady(engine)) {
                Text(
                    "Download the model (Settings) to hear these voices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            LazyColumn(Modifier.weight(1f)) {
                if (recents.isNotEmpty() && query.isBlank()) {
                    item {
                        Text(
                            "Recent",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(recents, key = { "recent-" + it.id }) { descriptor ->
                        val modelReady = vm.isModelReady(engine)
                        ListItem(
                            headlineContent = { Text(descriptor.displayName) },
                            supportingContent = { Text(descriptor.subtitle + " · " + descriptor.id) },
                            leadingContent = {
                                if (descriptor.id == activeVoice) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                }
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        if (auditioning == descriptor.id) {
                                            vm.player.stop()
                                            vm.player.stopAudition()
                                        } else if (modelReady) {
                                            vm.player.audition(descriptor.id)
                                        }
                                    },
                                    enabled = modelReady || auditioning == descriptor.id,
                                ) {
                                    Icon(
                                        if (auditioning == descriptor.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = if (auditioning == descriptor.id) "Stop audition" else "Audition",
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                vm.player.setVoice(descriptor.id, engine)
                                vm.player.cancelAuditionRestore()
                                onDismiss()
                            },
                        )
                    }
                }
                for (section in sections) {
                    item {
                        Text(
                            section.first,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(section.second, key = { it.id }) { descriptor ->
                        val modelReady = vm.isModelReady(engine)
                        ListItem(
                            headlineContent = { Text(descriptor.displayName) },
                            supportingContent = { Text(descriptor.subtitle + " · " + descriptor.id) },
                            leadingContent = {
                                if (descriptor.id == activeVoice) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                }
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        if (auditioning == descriptor.id) {
                                            vm.player.stop()
                                            vm.player.stopAudition()
                                        } else if (modelReady) {
                                            vm.player.audition(descriptor.id)
                                        }
                                    },
                                    enabled = modelReady || auditioning == descriptor.id,
                                ) {
                                    Icon(
                                        if (auditioning == descriptor.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = if (auditioning == descriptor.id) "Stop audition" else "Audition",
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                vm.player.setVoice(descriptor.id, engine)
                                vm.player.cancelAuditionRestore()
                                onDismiss()
                            },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.padding(bottom = 16.dp)) {
                Text("Done")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemVoiceList(vm: SpeechnotesViewModel, onDismiss: () -> Unit) {
    val selected by vm.player.systemVoice.collectAsState()
    var voices by remember { mutableStateOf(vm.player.systemVoiceList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        var tries = 0
        while (voices.isEmpty() && tries < 20) {
            kotlinx.coroutines.delay(300)
            voices = vm.player.systemVoiceList()
            tries++
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("System voice", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search voices") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
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
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
            }
            val shown = voices.filter {
                query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                    it.locale.toLanguageTag().contains(query, ignoreCase = true)
            }
            items(shown, key = { it.name }) { voice ->
                ListItem(
                    headlineContent = { Text("${voice.name} (${voice.locale.toLanguageTag()})", maxLines = 1) },
                    leadingContent = {
                        if (selected == voice.name) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        }
                    },
                    modifier = Modifier.clickable {
                        vm.player.setSystemVoice(voice.name)
                        onDismiss()
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.padding(bottom = 16.dp)) {
            Text("Done")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageBar(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        FilterChip(
            selected = true,
            onClick = { expanded = !expanded },
            label = { Text(if (expanded) "Language: ${VoiceCatalog.languageName(current)}  ▲" else "Language: ${VoiceCatalog.languageName(current)}  ▼") },
        )
        if (expanded) {
            // 31 languages — a horizontal scrolling chip row keeps every
            // choice reachable (a plain Row overflows off-screen).
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                items(VoiceCatalog.supertonicLanguages.entries.toList(), key = { it.key }) { (code, name) ->
                    FilterChip(
                        selected = code == current,
                        onClick = { onSelect(code) },
                        label = { Text(name) },
                    )
                }
            }
        }
    }
}
