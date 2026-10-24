package com.speechnotes.app.ui.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.speechnotes.app.model.Note
import com.speechnotes.app.model.Notebook

/**
 * The notes list — the Speechnotes home screen. Mirrors the iOS NotesListView:
 * pinned section, date-era sections, notebook scope chips, search, sort,
 * swipe-free action sheet (long-press menu), and a Material 3 Expressive FAB
 * menu for new note / import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    vm: com.speechnotes.app.data.SpeechnotesViewModel,
    onOpenNote: (String) -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenNotebooks: () -> Unit,
    onOpenEncryptionSettings: () -> Unit = {},
) {
    val notes by vm.notes.notes.collectAsState()
    val notebooks by vm.notebooks.notebooks.collectAsState()
    val toast by vm.toast.collectAsState()
    // A sealed notes.json with a locked vault: the library is intact but
    // unreadable — show the unlock prompt, not an empty list (iOS
    // NotesStore.isLocked parity).
    val notesLocked by vm.notes.isLocked.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // rememberSaveable: the search box and scroll position survive tab
    // switches (the nav host keeps per-tab state; plain remember{} didn't).
    var searchText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    val sort by vm.notesSortOrderFlow.collectAsState()
    val scope by vm.activeNotebookScopeFlow.collectAsState()
    val visible = remember(notes, notebooks, searchText, sort, scope) {
        vm.searchNotes(searchText, scope, sort)
    }

    // Toast bridge (ViewModel → Snackbar).
    androidx.compose.runtime.LaunchedEffect(toast?.id) {
        val t = toast ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = t.message,
            actionLabel = t.actionTitle,
            duration = androidx.compose.material3.SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) t.action?.invoke()
        if (toast?.id == t.id) vm.dismissToast()
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Speechnotes", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Sort and more")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Search notes") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                )
                NotebookChipRow(
                    notebooks = notebooks,
                    allCount = notes.size,
                    // One pass per recomposition, not one per chip per pass.
                    countsByNotebook = remember(notebooks, notes) {
                        val counts = HashMap<String, Int>(notebooks.size)
                        for (note in notes) note.notebookId?.let { counts[it] = (counts[it] ?: 0) + 1 }
                        counts
                    },
                    scope = scope,
                    onSelect = { vm.activeNotebookScope = it },
                    onManage = onOpenNotebooks,
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                androidx.compose.animation.AnimatedVisibility(visible = fabExpanded) {
                    Column(horizontalAlignment = Alignment.End) {
                        FabAction(
                            label = "New note from clipboard",
                            icon = Icons.Default.Add,
                        ) {
                            fabExpanded = false
                            vm.importClipboard()
                        }
                        Spacer(Modifier.height(8.dp))
                        FabAction(
                            label = "New note",
                            icon = Icons.Default.Edit,
                        ) {
                            fabExpanded = false
                            val note = vm.createNote(currentScopeNotebookId(vm))
                            onOpenNote(note.id)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                    Icon(
                        if (fabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (fabExpanded) "Close menu" else "New note",
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                notesLocked -> LockedNotesState(onUnlock = onOpenEncryptionSettings)
                notes.isEmpty() -> EmptyNotesState(onCreate = { val n = vm.createNote(null); onOpenNote(n.id) })
                visible.isEmpty() -> EmptySearchState(searchText)
                else -> {
                    val pinned = visible.filter { it.isPinned }
                    val rest = visible.filter { !it.isPinned }
                    val sections = buildSections(rest, sort)

                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (pinned.isNotEmpty()) {
                            item { SectionHeader("Pinned") }
                            items(pinned, key = { it.id }) { note ->
                                NoteCard(
                                    note = note,
                                    meta = vm.notes.metadata(note),
                                    onClick = { onOpenNote(note.id) },
                                    onPin = { vm.notes.setPinned(!note.isPinned, note.id) },
                                    onFavorite = { vm.notes.setFavorite(!note.isFavorite, note.id) },
                                    onDelete = { vm.deleteNoteWithUndo(note.id) },
                                    onShare = { vm.models.share(noteToFile(vm, note)) },
                                    onMove = { notebookId -> vm.notes.move(note.id, notebookId) },
                                    notebooks = notebooks,
                                )
                            }
                        }
                        for (section in sections) {
                            item { SectionHeader(section.first) }
                            items(section.second, key = { it.id }) { note ->
                                NoteCard(
                                    note = note,
                                    meta = vm.notes.metadata(note),
                                    onClick = { onOpenNote(note.id) },
                                    onPin = { vm.notes.setPinned(!note.isPinned, note.id) },
                                    onFavorite = { vm.notes.setFavorite(!note.isFavorite, note.id) },
                                    onDelete = { vm.deleteNoteWithUndo(note.id) },
                                    onShare = { vm.models.share(noteToFile(vm, note)) },
                                    onMove = { notebookId -> vm.notes.move(note.id, notebookId) },
                                    notebooks = notebooks,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Import text files into notes (iOS ImportService parity): txt / md / pdf
    // pick a note text from the system file picker.
    val fileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.appScope.launch {
                val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.models.importTextFrom(uri) }
                if (imported != null) {
                    val added = vm.addNoteFromText(imported.second)
                    if (!added) Unit
                } else {
                    vm.showToast("Couldn't read that file.")
                }
            }
        }
    }

    if (showSortMenu) {
        ModalBottomSheet(onDismissRequest = { showSortMenu = false }) {
            Column(Modifier.padding(16.dp)) {
                Text("Sort by", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SortOption("Last edited", sort == "edited") { vm.notesSortOrder = "edited"; showSortMenu = false }
                SortOption("Date created", sort == "created") { vm.notesSortOrder = "created"; showSortMenu = false }
                SortOption("Title", sort == "title") { vm.notesSortOrder = "title"; showSortMenu = false }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("Import text file") },
                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showSortMenu = false
                        fileImportLauncher.launch(arrayOf("text/plain", "text/markdown", "application/pdf"))
                    },
                )
                ListItem(
                    headlineContent = { Text("Recently deleted") },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                    modifier = Modifier.clickable { onOpenRecycleBin() },
                )
            }
        }
    }
}

private fun currentScopeNotebookId(vm: com.speechnotes.app.data.SpeechnotesViewModel): String? {
    val scope = vm.activeNotebookScope
    return if (scope.startsWith("nb-")) scope.removePrefix("nb-") else null
}

private fun noteToFile(vm: com.speechnotes.app.data.SpeechnotesViewModel, note: Note): java.io.File {
    // Name the file after the note — a fixed name made every shared note
    // arrive as the same file (and a share to Files overwrote the last one).
    val safe = note.title.replace(Regex("[^\\w\\- ]"), "").trim()
        .take(40).ifBlank { "note" }
    val file = java.io.File(com.speechnotes.app.logic.AppFiles.exportsDir, "$safe.txt")
    return runCatching {
        file.parentFile?.mkdirs()
        file.writeText(note.text)
        file
    }.getOrDefault(file)
}

private fun buildSections(rest: List<Note>, sort: String): List<Pair<String, List<Note>>> {
    if (sort != "edited") return listOf("" to rest)
    val groups = LinkedHashMap<String, MutableList<Note>>()
    for (note in rest) {
        val key = eraFor(note.updatedAt)
        groups.getOrPut(key) { mutableListOf() }.add(note)
    }
    return groups.map { it.key to it.value.toList() }
}

private fun eraFor(millis: Long): String {
    val now = System.currentTimeMillis()
    val dayMs = 86_400_000L
    val days = ((now - millOfDayStart(millis)) / dayMs).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> "Previous 7 days"
        else -> "Older"
    }
}

private fun millOfDayStart(millis: Long): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = millis
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** One labeled action in the FAB's expandable menu. */
@Composable
private fun FabAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    if (title.isEmpty()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun NotebookChipRow(
    notebooks: List<Notebook>,
    allCount: Int,
    countsByNotebook: Map<String, Int>,
    scope: String,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = scope == "all",
                onClick = { onSelect("all") },
                label = { Text("All notes · $allCount") },
            )
        }
        items(notebooks, key = { it.id }) { notebook ->
            FilterChip(
                selected = scope == "nb-${notebook.id}",
                onClick = { onSelect("nb-${notebook.id}") },
                label = { Text("${notebook.name} · ${countsByNotebook[notebook.id] ?: 0}") },
            )
        }
        item {
            FilterChip(
                selected = scope == "unfiled",
                onClick = { onSelect("unfiled") },
                label = { Text("Unfiled") },
            )
        }
        item {
            AssistChip(onClick = onManage, label = { Text("Manage") })
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    meta: com.speechnotes.app.data.NotesStore.RowMetadata,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onMove: (String?) -> Unit = {},
    notebooks: List<com.speechnotes.app.model.Notebook> = emptyList(),
) {
    // Long-press context menu: pin / favorite / move-to-notebook / share /
    // delete — the same actions as iOS's row context menu + swipe actions.
    var showMenu by remember { mutableStateOf(false) }
    Box {
        // ONE click pipeline: combinedClickable handles tap + long-press.
        // The old Card(onClick=…) ALSO carried its own click handling — two
        // parallel pipelines on every row for no behavior gain.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true },
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = meta.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.preview.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (note.isFavorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Favorite",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = relativeTime(note.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(" · ", color = MaterialTheme.colorScheme.outline)
                Text(
                    text = "${meta.wordCount} words",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                meta.listenMinutes?.let {
                    Text(" · ", color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = "~$it min listen",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPin) {
                    Icon(
                        if (note.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (note.isPinned) "Unpin" else "Pin",
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (note.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (note.isFavorite) "Unfavorite" else "Favorite",
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(if (note.isPinned) "Unpin" else "Pin") },
                onClick = { showMenu = false; onPin() },
            )
            DropdownMenuItem(
                text = { Text(if (note.isFavorite) "Unfavorite" else "Favorite") },
                onClick = { showMenu = false; onFavorite() },
            )
            DropdownMenuItem(
                text = { Text("Unfiled") },
                onClick = { showMenu = false; onMove(null) },
            )
            notebooks.forEach { notebook ->
                DropdownMenuItem(
                    text = { Text("Move to ${notebook.name}") },
                    onClick = { showMenu = false; onMove(notebook.id) },
                )
            }
        }
    }
}
}

@Composable
private fun SortOption(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun EmptyNotesState(onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No notes yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Write something and hear it read aloud — fully offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onCreate) { Text("New note") }
    }
}

@Composable
private fun EmptySearchState(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No results for \"$query\"",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * The library is sealed and the vault is locked (Unit 10): the notes are
 * there, just unreadable — never render that as an empty list.
 */
@Composable
private fun LockedNotesState(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Your notes are encrypted",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Unlock with your passphrase to read and edit them. They stay sealed on disk until then.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onUnlock) { Text("Unlock in Settings") }
    }
}

private fun relativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 30 -> "${days}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
            sdf.format(java.util.Date(millis))
        }
    }
}


