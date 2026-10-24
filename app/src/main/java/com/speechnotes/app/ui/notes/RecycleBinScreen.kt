package com.speechnotes.app.ui.notes

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.model.Note

/**
 * Recently Deleted — 30-day retention with recover, purge and empty-all.
 * Ported from RecycleBinView.swift (speechnotes-ios).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    val deleted by vm.notes.deletedNotes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recently Deleted") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (deleted.isNotEmpty()) {
                        IconButton(onClick = { vm.notes.emptyRecycleBin() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Empty")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                "Notes are kept for ${com.speechnotes.app.model.Note.RECYCLE_RETENTION_DAYS} days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            if (deleted.isEmpty()) {
                Text(
                    "No deleted notes.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn {
                    items(deleted, key = { it.id }) { note ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    val days = note.recycleDaysRemaining ?: 0
                                    Text("Deleted ${relative(note.deletedAt ?: 0)} · $days days left")
                                },
                                trailingContent = {
                                    Row {
                                        OutlinedButton(onClick = { vm.notes.recover(note.id) }) { Text("Recover") }
                                        Spacer(Modifier.height(4.dp))
                                        Button(onClick = { vm.notes.purge(note.id) }) { Text("Delete now") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Notebooks manager — create, rename, delete flat notebooks (Joplin-style).
 * Ported from NotebookListView.swift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebooksScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    val notebooks by vm.notebooks.notebooks.collectAsState()
    var newName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notebooks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("New notebook") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.notebooks.create(newName)?.let { newName = "" }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Add") }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(notebooks, key = { it.id }) { notebook ->
                    var renaming by remember { mutableStateOf(false) }
                    var renameDraft by remember(notebook.id) { mutableStateOf(notebook.name) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            if (renaming) {
                                OutlinedTextField(
                                    value = renameDraft,
                                    onValueChange = { renameDraft = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        vm.notebooks.rename(notebook, renameDraft.trim())
                                        renaming = false
                                    }) { Text("Save") }
                                    TextButton(onClick = { renaming = false }) { Text("Cancel") }
                                }
                            } else {
                                ListItem(
                                    headlineContent = { Text(notebook.name) },
                                    trailingContent = {
                                        Row {
                                            TextButton(onClick = {
                                                renameDraft = notebook.name
                                                renaming = true
                                            }) { Text("Rename") }
                                            Button(onClick = { vm.notebooks.delete(notebook) }) { Text("Delete") }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun relative(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = maxOf(0L, now - millis)
    val days = diff / 86_400_000
    return when {
        days == 0L -> "today"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}
