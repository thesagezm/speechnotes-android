package com.speechnotes.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.BookDropRegistry
import com.speechnotes.app.data.LocalSendSender
import com.speechnotes.app.data.Peer
import com.speechnotes.app.data.Prefs
import com.speechnotes.app.data.SendItem
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.model.Book
import com.speechnotes.app.ui.theme.Haptics
import java.io.File

/**
 * The send half of BookDrop: pick books, notes, exported audio or a .jex
 * archive, find a peer on this network, push. Ported from iOS
 * BookDropSendView.swift — the items are the same ones the share sheet
 * carries; this is a second route to them, with NO file picker (the payload
 * is staged straight from app storage).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDropSendSheet(
    vm: SpeechnotesViewModel,
    prefillNoteId: String? = null,
    prefillBookId: String? = null,
    onDismiss: () -> Unit,
) {
    val sender = BookDropRegistry.sender
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (sender == null) {
            Text(
                "BookDrop is unavailable.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
            return@ModalBottomSheet
        }
        BookDropSendContent(vm = vm, sender = sender, prefillNoteId = prefillNoteId, prefillBookId = prefillBookId)
    }
}

private enum class Payload(val label: String) {
    BOOK("Books"), NOTE("Notes"), EXPORT("Exports"), JEX(".jex archive")
}

@Composable
private fun BookDropSendContent(
    vm: SpeechnotesViewModel,
    sender: com.speechnotes.app.data.LocalSendSender,
    prefillNoteId: String?,
    prefillBookId: String?,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val books by vm.books.books.collectAsState()
    val notes by vm.notes.notes.collectAsState()
    val notebooks by vm.notebooks.notebooks.collectAsState()
    val phase by sender.phase.collectAsState()
    val peers by sender.peers.collectAsState()
    val lastError by sender.lastError.collectAsState()
    val needsAccessCode by sender.needsAccessCode.collectAsState()
    val progress by sender.progress.collectAsState()
    val transfer by sender.transferProgress.collectAsState()

    var payload by remember { mutableStateOf(Payload.BOOK) }
    var selectedBooks by remember { mutableStateOf(setOf<String>()) }
    var selectedNotes by remember { mutableStateOf(setOf<String>()) }
    var selectedExports by remember { mutableStateOf(setOf<String>()) }
    var peer by remember { mutableStateOf<Peer?>(null) }
    var showPeerPicker by remember { mutableStateOf(false) }
    var pinText by remember {
        mutableStateOf(
            context.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE)
                .getString(Prefs.BOOK_DROP_SEND_PIN, "").orEmpty(),
        )
    }
    var stagingDirs by remember { mutableStateOf(listOf<File>()) }
    var buildError by remember { mutableStateOf<String?>(null) }

    // Fresh sheet, clean slate (iOS reset() onAppear parity).
    LaunchedEffect(Unit) {
        sender.reset()
        if (prefillBookId != null) {
            payload = Payload.BOOK
            selectedBooks = setOf(prefillBookId)
        }
        if (prefillNoteId != null) {
            payload = Payload.NOTE
            selectedNotes = setOf(prefillNoteId)
        }
    }
    // Whichever way the transfer ends, staged payload copies leave with it.
    LaunchedEffect(phase) {
        if (phase is LocalSendSender.Phase.Completed || phase is LocalSendSender.Phase.Failed) {
            kotlinx.coroutines.delay(5_000)
            cleanupStaging(stagingDirs)
        }
    }
    // The destination wants a code and the field is empty: prefill with
    // THIS device's RECEIVE code first (iOS v1.8.1 parity — the
    // same-code-everywhere user gets a one-tap retry), then with the send
    // code. A per-peer code can still be typed over the prefill.
    LaunchedEffect(needsAccessCode) {
        if (needsAccessCode && pinText.isEmpty()) {
            val prefs = context.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE)
            pinText = prefs.getString(Prefs.BOOK_DROP_PIN, "").orEmpty()
                .ifEmpty { prefs.getString(Prefs.BOOK_DROP_SEND_PIN, "").orEmpty() }
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("Send with BookDrop", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Payload.entries.forEach { option ->
                FilterChip(
                    selected = payload == option,
                    onClick = {
                        Haptics.tap(context)
                        payload = option
                        buildError = null
                    },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (payload) {
                Payload.BOOK -> "The original file as stored — an EPUB, PDF or audiobook lands with its real name."
                Payload.NOTE -> "Each note becomes its own markdown file, ready for Joplin or any editor."
                Payload.EXPORT -> "WAV recordings from the Exports folder — the same files Storage lists."
                Payload.JEX -> "Every note and notebook in one Joplin-compatible archive."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // Payload list (scroll-bounded so the sheet stays a sheet).
        when (payload) {
            Payload.BOOK -> {
                if (books.isEmpty()) {
                    SheetHint("No books on the shelf yet.")
                } else {
                    SelectionList(books.map { b -> SelectableRowData(b.id, b.title, b.author ?: b.format) }, selectedBooks) { id ->
                        selectedBooks = toggle(selectedBooks, id)
                    }
                }
            }
            Payload.NOTE -> {
                if (notes.isEmpty()) {
                    SheetHint("No notes yet.")
                } else {
                    SelectionList(
                        notes.filter { it.deletedAt == null }.map { n -> SelectableRowData(n.id, n.title.ifBlank { "Untitled" }, null) },
                        selectedNotes,
                    ) { id -> selectedNotes = toggle(selectedNotes, id) }
                }
            }
            Payload.EXPORT -> {
                val exports = remember { exportFiles() }
                if (exports.isEmpty()) {
                    SheetHint("No exported audio yet — use Export WAV in a note's menu.")
                } else {
                    SelectionList(
                        exports.map { f -> SelectableRowData(f.name, f.name, labelForBytes(f.length())) },
                        selectedExports,
                    ) { name -> selectedExports = toggle(selectedExports, name) }
                }
            }
            Payload.JEX -> {
                SheetHint("Contents: ${notes.count { it.deletedAt == null }} notes, ${notebooks.size} notebooks. The receiving device imports it from Settings → BookDrop.")
            }
        }

        Spacer(Modifier.height(8.dp))
        // Peer + access code.
        val currentPeer = peer
        if (currentPeer != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(currentPeer.alias, style = MaterialTheme.typography.bodyMedium)
                    Text(currentPeer.displayKind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showPeerPicker = true }) { Text("Change") }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        Haptics.tap(context)
                        sender.scan()
                        showPeerPicker = true
                    }
                    .padding(vertical = 8.dp),
            ) {
                Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text("Choose a device", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = pinText,
            onValueChange = {
                pinText = it
                context.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE)
                    .edit().putString(Prefs.BOOK_DROP_SEND_PIN, it).apply()
            },
            label = { Text("Access code (if the receiver asks for one)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        buildError?.let { error -> SheetError(error) }
        if (lastError != null && phase !is LocalSendSender.Phase.Sending) {
            SheetError(lastError.orEmpty())
        }

        when (val p = phase) {
            is LocalSendSender.Phase.Sending -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "Sending… file ${minOf(progress.done + 1, progress.total)} of ${progress.total}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { sender.cancelSend() }) { Text("Cancel") }
                }
                val t = transfer
                if (t != null) {
                    LinearProgressIndicator(progress = { t.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Row {
                        Text(t.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        Text("${(t.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            is LocalSendSender.Phase.Completed -> SheetOk(p.message)
            else -> {
                Button(
                    onClick = {
                        Haptics.tap(context)
                        val target = peer
                        if (target == null) {
                            showPeerPicker = true
                            return@Button
                        }
                        scope.launchSend(vm, sender, payload, selectedBooks, selectedNotes, selectedExports, target, pinText) { error, staged ->
                            buildError = error
                            if (staged != null) stagingDirs = staged
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (needsAccessCode) "Send again" else "Send") }
            }
        }

        if (needsAccessCode && phase !is LocalSendSender.Phase.Sending) {
            Text(
                "The receiving device has an access code set — type the code that device shows under Nearby BookDrop, then send again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPeerPicker) {
        PeerPickerSheet(
            sender = sender,
            onDismiss = { showPeerPicker = false },
            onPick = { candidate ->
                peer = candidate
                showPeerPicker = false
            },
        )
    }
}

// MARK: - Item building (staging)

/**
 * Resolves the picked payload to files on disk. Books are staged into
 * cache/BookDrop-send-* (so the transfer never races a re-import or delete
 * of the original); note markdown files are written fresh; exports and the
 * .jex archive stream straight from app storage.
 */
private suspend fun buildItems(
    vm: SpeechnotesViewModel,
    payload: Payload,
    selectedBooks: Set<String>,
    selectedNotes: Set<String>,
    selectedExports: Set<String>,
): Pair<List<SendItem>, List<File>> {
    val staging = File(vm.context.cacheDir, "BookDrop-send-${java.util.UUID.randomUUID()}")
    staging.mkdirs()
    val items = ArrayList<SendItem>()
    when (payload) {
        Payload.BOOK -> {
            val chosen = vm.books.books.value.filter { selectedBooks.contains(it.id) && !it.isDeleted }
            if (chosen.isEmpty()) throw SendBuildError("Tick at least one book.")
            for (book in chosen) {
                val original = AppFiles.bookDirectory(book.id).listFiles()
                    ?.firstOrNull { it.name.startsWith("original.") }
                    ?: throw SendBuildError("Could not read that book's file: ${book.title}")
                val ext = original.name.removePrefix("original.")
                val fileName = "${sanitizeTitle(book.title)}.$ext"
                val target = File(staging, fileName)
                // HARD LINK, not a copy: an audiobook can be a gigabyte, and
                // a staged copy needs that much free space AGAIN before the
                // transfer can start (the "big books never send" bug). A
                // link costs nothing and still pins the content, so a
                // re-import of the book cannot change the bytes on the
                // wire. The copy is the fallback for the one case a link
                // cannot cover: another filesystem.
                if (!stageFile(original, target)) {
                    throw SendBuildError("Could not read that book's file: ${book.title}")
                }
                items.add(SendItem(target, fileName, contentTypeFor(ext)))
            }
        }
        Payload.NOTE -> {
            val chosen = vm.notes.notes.value.filter { selectedNotes.contains(it.id) && it.deletedAt == null }
            if (chosen.isEmpty()) throw SendBuildError("Tick at least one note.")
            for (note in chosen) {
                val text = note.title.ifBlank { null }?.let { "# $it\n\n${note.text}" } ?: note.text
                val fileName = "${sanitizeTitle(note.title.ifBlank { "Untitled" })}.md"
                val target = File(staging, fileName)
                target.writeText(text)
                items.add(SendItem(target, fileName, "text/markdown"))
            }
        }
        Payload.EXPORT -> {
            val exports = exportFiles().filter { selectedExports.contains(it.name) }
            if (exports.isEmpty()) throw SendBuildError("Tick at least one export.")
            for (file in exports) {
                // Exports is our own folder: stream from the file as-is.
                // Not only WAVs — a note PDF, a markdown export or the .jex
                // archive the user just made is an export too.
                items.add(SendItem(file, file.name, contentTypeFor(file.extension)))
            }
        }
        Payload.JEX -> {
            val archive = vm.models.exportJex(
                vm.notes.notes.value.filter { it.deletedAt == null },
                vm.notebooks.notebooks.value,
            ) ?: throw SendBuildError("Could not build the archive.")
            items.add(SendItem(archive, archive.name, "application/joplin-archive"))
        }
    }
    return items to listOf(staging)
}

    private class SendBuildError(message: String) : Exception(message)

    /**
     * Stages [source] at [target] as cheaply as the filesystem allows: a
     * hard link first (no bytes copied at all), a full copy only when the
     * link cannot be made — a different filesystem, or a filesystem that
     * has no hard links.
     */
    private fun stageFile(source: File, target: File): Boolean {
        val linked = runCatching {
            java.nio.file.Files.createLink(target.toPath(), source.toPath())
            true
        }.getOrDefault(false)
        if (linked) return true
        return runCatching {
            source.copyTo(target, overwrite = true)
            true
        }.getOrDefault(false)
    }

private fun sanitizeTitle(title: String): String {
    val slug = title.replace("/", "-")
        .filter { it.isLetterOrDigit() || it == '-' || it == ' ' }
        .trim()
        .take(60)
    return slug.ifBlank { "Untitled" }
}

/**
 * Everything the app has exported — WAVs, note PDFs, markdown/text exports
 * and the .jex archives. All of them are sendable: the sheet sends files
 * straight from app storage, no picker involved.
 */
private fun exportFiles(): List<File> =
    AppFiles.exportsDir.listFiles { f -> f.isFile }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

private fun contentTypeFor(ext: String): String = when (ext.lowercase()) {
    "epub" -> "application/epub+zip"
    "pdf" -> "application/pdf"
    "m4b", "m4a", "mp4" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "jex" -> "application/joplin-archive"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "odp" -> "application/vnd.oasis.opendocument.presentation"
    else -> "application/octet-stream"
}

private fun labelForBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "${"%.1f".format(bytes / 1048576.0)} MB"
    bytes >= 1 shl 10 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun cleanupStaging(dirs: List<File>) {
    for (dir in dirs) runCatching { dir.deleteRecursively() }
}

private fun toggle(set: Set<String>, value: String): Set<String> =
    if (set.contains(value)) set - value else set + value

private fun kotlinx.coroutines.CoroutineScope.launchSend(
    vm: SpeechnotesViewModel,
    sender: com.speechnotes.app.data.LocalSendSender,
    payload: Payload,
    selectedBooks: Set<String>,
    selectedNotes: Set<String>,
    selectedExports: Set<String>,
    target: Peer,
    pin: String,
    onError: (String?, List<File>?) -> Unit,
) {
    launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val (items, staged) = buildItems(vm, payload, selectedBooks, selectedNotes, selectedExports)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onError(null, staged) }
            sender.send(items, target, pin.ifBlank { null })
        } catch (e: SendBuildError) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onError(e.message, null) }
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onError("Could not prepare the payload: ${e.message}", null) }
        }
    }
}

// MARK: - Peer picker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerPickerSheet(
    sender: com.speechnotes.app.data.LocalSendSender,
    onDismiss: () -> Unit,
    onPick: (Peer) -> Unit,
) {
    val phase by sender.phase.collectAsState()
    val peers by sender.peers.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Send to", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (phase == LocalSendSender.Phase.Scanning) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Looking for devices on this network…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn {
                items(peers, key = { it.id }) { candidate ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(candidate) }
                            .padding(vertical = 10.dp),
                    ) {
                        Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(candidate.alias)
                            Text(candidate.displayKind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (peers.isEmpty() && phase != LocalSendSender.Phase.Scanning) {
                Text(
                    "Nothing found. Both devices must be on the same Wi-Fi (or one phone's hotspot), and the recipient's Nearby BookDrop must be on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            TextButton(onClick = { sender.scan() }) { Text("Scan again") }
        }
    }
}

// MARK: - Small rows

private data class SelectableRowData(val id: String, val title: String, val subtitle: String?)

@Composable
private fun SelectionList(
    rows: List<SelectableRowData>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.height(180.dp)) {
        items(rows, key = { it.id }) { row ->
            val isPicked = selected.contains(row.id)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(row.id) }
                    .padding(vertical = 6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.title, maxLines = 1)
                    row.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Icon(
                    if (isPicked) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (isPicked) "Selected" else "Not selected",
                    tint = if (isPicked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SheetHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SheetError(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SheetOk(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}
