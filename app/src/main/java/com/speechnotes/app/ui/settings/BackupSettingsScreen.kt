package com.speechnotes.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.SpeechnotesViewModel

/** Backup subpage: .jex export (with notebook scope) + import. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    val jexImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importJex(it) } }

    SettingsSubpage(title = "Backup", onBack = onBack) {
        SettingsCard("Backup") {
            // Multi-notebook export scope (iOS BackupExportView parity):
            // pick notebooks before writing the .jex. Default = all.
            var showExportScope by remember { mutableStateOf(false) }
            var exportScope by remember {
                mutableStateOf<Set<String>>(emptySet())
            }
            ActionRow(
                icon = Icons.Default.FileUpload,
                title = "Export notes to Joplin (.jex)",
                subtitle = "A tar archive Joplin can import.",
                actionLabel = "Export",
            ) { showExportScope = true }
            if (showExportScope) {
                val allNotebooks = vm.notebooks.notebooks.value
                ModalBottomSheet(onDismissRequest = { showExportScope = false }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Export notebooks", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Pick notebooks to include, or export everything.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn {
                            items(allNotebooks, key = { it.id }) { notebook ->
                                ListItem(
                                    headlineContent = { Text(notebook.name) },
                                    leadingContent = {
                                        Checkbox(
                                            checked = notebook.id in exportScope,
                                            onCheckedChange = { checked ->
                                                exportScope = if (checked) exportScope + notebook.id else exportScope - notebook.id
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                showExportScope = false
                                val selectedNotebooks = vm.notebooks.notebooks.value.filter { it.id in exportScope }
                                val scopedNotes = vm.notes.notes.value.filter { note ->
                                    note.notebookId in exportScope || exportScope.isEmpty()
                                }
                                val file = vm.models.exportJex(scopedNotes, selectedNotebooks)
                                if (file != null) vm.models.share(file) else vm.showToast("Export failed")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export ${if (exportScope.isEmpty()) "all notes" else "${exportScope.size} notebook(s)"}") }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ActionRow(
                icon = Icons.Default.FileDownload,
                title = "Import from Joplin (.jex)",
                subtitle = "Notes, notebooks and images from a Joplin export.",
                actionLabel = "Import",
            ) {
                jexImportLauncher.launch(arrayOf("*/*"))
            }
        }
    }
}
