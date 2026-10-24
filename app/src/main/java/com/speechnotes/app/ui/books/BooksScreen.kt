package com.speechnotes.app.ui.books

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.model.Book

/**
 * Books tab — import library with a cover grid, reader navigation and a
 * chapter browser. Mirrors BooksView.swift (speechnotes-ios).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(
    vm: SpeechnotesViewModel,
    onOpenReader: (String) -> Unit,
    onOpenRecycleBin: () -> Unit = {},
) {
    val books by vm.books.books.collectAsState()
    val deletedBooks by vm.books.deletedBooks.collectAsState()
    val isImporting by vm.books.isImporting.collectAsState()
    val importError by vm.books.importError.collectAsState()

    // Covers are rendered by a quiet IO pass; re-enter the shelf while it's
    // still working (or re-import a book) and this picks the new files up
    // without waiting for a relaunch.
    LaunchedEffect(books.size) { vm.ensureBookCovers() }
    val coverRevision by vm.bookCoverRevision.collectAsState()
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var openBook by remember { mutableStateOf<Book?>(null) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importBook(it) } }

    val visible = remember(books, query, coverRevision) { vm.books.visibleBooks(query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Books") },
                actions = {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(4.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(12.dp))
                    }
                    if (deletedBooks.isNotEmpty()) {
                        IconButton(onClick = onOpenRecycleBin) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Recently deleted books")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                filePicker.launch(
                    // Office documents join through the normalize-to-EPUB
                    // pipeline; the mime names are what the system picker
                    // filters on, so each format's real type is listed
                    // alongside a generic catch-all for zip containers.
                    arrayOf(
                        "application/epub+zip",
                        "application/pdf",
                        "audio/mp4",
                        "audio/x-m4b",
                        "audio/mpeg",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/vnd.oasis.opendocument.text",
                        "application/vnd.oasis.opendocument.presentation",
                        "application/msword",
                        "application/octet-stream",
                    )
                )
            }) {
                Icon(Icons.Default.Add, contentDescription = "Import book")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (books.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Title or author") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
            importError?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            when {
                books.isEmpty() -> EmptyBooks()
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No books match \"$query\".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = { it.id }) { book ->
                        BookCoverCard(
                            book = book,
                            onOpen = { openBook = book },
                            onDelete = { pendingDelete = book },
                        )
                    }
                }
            }
        }
    }

    openBook?.let { book ->
        BookDetailSheet(
            book = book,
            onDismiss = { openBook = null },
            onRead = {
                openBook = null
                onOpenReader(book.id)
            },
            // Row tap = OPEN the reader AT that chapter; the play icon still
            // narrates just that chapter.
            onOpenChapter = { index ->
                openBook = null
                when (book.format) {
                    Book.FORMAT_PDF -> {
                        val startPage = book.chapters.getOrNull(index)?.startPage ?: 0
                        vm.saveBookPosition(book.id, startPage + 1, 0.0, null)
                    }
                    else -> vm.saveBookPosition(book.id, index + 1, 0.0, null)
                }
                onOpenReader(book.id)
            },
            onPlayChapter = { index ->
                vm.toggleBookChapter(book, index)
                openBook = null
            },
            onPlayAudioChapter = { index ->
                openBook = null
                vm.audioBooks.play(book, index, 0L)
            },
            onDelete = {
                openBook = null
                pendingDelete = book
            },
        )
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete \"${book.title.take(40)}\"?") },
            text = { Text("The book moves to Recently Deleted for ${Book.RECYCLE_RETENTION_DAYS} days, then is removed for good.") },
            confirmButton = {
                Button(onClick = {
                    vm.deleteBook(book)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Recently Deleted — the books recycle bin (iOS BooksRecycleBinView): recover,
 * delete-permanently, empty-all. A mis-tap on a 3 GB audiobook is recoverable
 * for Book.RECYCLE_RETENTION_DAYS before the file is really removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksRecycleBinScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    val deleted by vm.books.deletedBooks.collectAsState()
    var pendingPurge by remember { mutableStateOf<Book?>(null) }
    var pendingEmpty by remember { mutableStateOf(false) }

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
                        TextButton(onClick = { pendingEmpty = true }) { Text("Empty") }
                    }
                },
            )
        },
    ) { padding ->
        if (deleted.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Recycle bin is empty.\nDeleted books stay here for ${Book.RECYCLE_RETENTION_DAYS} days.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(padding)) {
                lazyListItems(deleted, key = { it.id }) { book ->
                    ListItem(
                        headlineContent = {
                            Text(book.title.ifBlank { book.formatLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            val days = ((System.currentTimeMillis() - (book.deletedAt ?: 0)) / 86_400_000L).toInt()
                            Text(
                                "${book.formatLabel} · removed " + when {
                                    days <= 0 -> "today"
                                    days == 1 -> "yesterday"
                                    else -> "$days days ago"
                                } + " · ${book.recycleDaysRemaining ?: 0} days left",
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.MenuBook, contentDescription = null)
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { vm.recoverBook(book.id) }) {
                                    Icon(Icons.Default.Restore, contentDescription = "Recover ${book.title}")
                                }
                                IconButton(onClick = { pendingPurge = book }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete ${book.title} now")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    pendingPurge?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text("Delete \"${book.title.take(40)}\" for good?") },
            text = { Text("This removes the book's files from the device. No undo.") },
            confirmButton = {
                Button(onClick = {
                    vm.purgeBook(book)
                    pendingPurge = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingPurge = null }) { Text("Cancel") } },
        )
    }

    if (pendingEmpty) {
        AlertDialog(
            onDismissRequest = { pendingEmpty = false },
            title = { Text("Permanently delete all ${deleted.size} books?") },
            text = { Text("This removes their files from the device. No undo.") },
            confirmButton = {
                Button(onClick = {
                    vm.emptyBookRecycleBin()
                    pendingEmpty = false
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { pendingEmpty = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyBooks() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(96.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        MaterialTheme.shapes.extraLarge,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("No books yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Import an EPUB, PDF or audiobook with + — then read it or hear it narrated, chapter by chapter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}

/**
 * One shelf card. The cover image is looked for on disk rather than trusted
 * from the manifest: a backfill that wrote cover.jpg after the manifest was
 * saved (or a manifest from an older build) left the card showing a bare
 * gradient block even though the artwork was right there.
 */
@Composable
private fun BookCoverCard(book: Book, onOpen: () -> Unit, onDelete: () -> Unit) {
    val cover = remember(book.id) { AppFiles.bookCoverFile(book.id) }
    val coverExists = remember(book.id, book.hasCover, cover.lastModified()) {
        book.hasCover && cover.isFile && cover.length() > 0
    }
    // An unreadable book explains itself on the card instead of looking
    // normal (iOS BookGridCard.metaLine parity).
    val errorText = book.importError
    Box {
        Card(
            onClick = onOpen,
            modifier = Modifier.aspectRatio(0.68f),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (errorText != null) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                errorText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else if (coverExists) {
                    AsyncImage(
                        // The file's mtime is part of the model: Coil keys its
                        // memory cache on the request, so a cover regenerated
                        // over an old one would otherwise keep serving the
                        // stale bitmap.
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(cover)
                            .memoryCacheKey("${cover.path}#${cover.lastModified()}")
                            .build(),
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(10.dp),
                        ) {
                            Text(
                                text = book.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = book.author ?: book.formatLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // Format chip on the shelf.
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                ) {
                    Text(
                        book.formatLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                // Spine/chapter count under the chip (iOS metaLine "N ch").
                val chapterMeta = when (book.format) {
                    Book.FORMAT_EPUB -> book.spineCount?.takeIf { it > 0 }?.let { "$it ch" }
                    Book.FORMAT_PDF -> book.chapters.size.takeIf { it > 0 }?.let { "$it ch" }
                    else -> book.audioChapters.size.takeIf { it > 0 }?.let { "$it ch" }
                }
                if (chapterMeta != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                    ) {
                        Text(
                            chapterMeta,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd)) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${book.title}",
                    modifier = Modifier
                        .padding(5.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The book's browser: Read (the reader surface), per-chapter narration, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDetailSheet(
    book: Book,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onOpenChapter: (Int) -> Unit,
    onPlayChapter: (Int) -> Unit,
    onPlayAudioChapter: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (!book.author.isNullOrBlank()) {
                Text(
                    book.author!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            // A per-book import failure states itself here too — the sheet is
            // where the user goes next after seeing the badge on the card.
            if (book.importError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text(
                        book.importError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Text(
                "${book.formatLabel} · " + when {
                    book.audioChapters.isNotEmpty() -> "${book.audioChapters.size} chapters"
                    book.chapters.isNotEmpty() -> "${book.chapters.size} chapters"
                    book.format == Book.FORMAT_PDF -> "pages"
                    else -> "no chapters found"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))

            Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.MenuBook, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (book.format == Book.FORMAT_AUDIO) "Open audiobook player" else "Read")
            }
            Spacer(Modifier.height(4.dp))

            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(8.dp))
                Text("Delete book", color = MaterialTheme.colorScheme.error)
            }

            when {
                // Audiobooks: the chapter TRACK is the table of contents. It
                // was only listed inside the player — the shelf sheet showed
                // no chapters at all for audio books.
                book.format == Book.FORMAT_AUDIO && book.audioChapters.isNotEmpty() -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Chapters",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(count = book.audioChapters.size, key = { book.audioChapters[it].hashCode() }) { index ->
                            val chapter = book.audioChapters[index]
                            ListItem(
                                headlineContent = { Text(chapter.title.ifBlank { "Chapter ${index + 1}" }) },
                                supportingContent = { Text(formatAudioClock(chapter.endMs - chapter.startMs)) },
                                leadingContent = {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { onPlayAudioChapter(index) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play chapter ${index + 1}")
                                    }
                                },
                                modifier = Modifier.clickable { onPlayAudioChapter(index) },
                            )
                        }
                    }
                }

                book.chapters.isNotEmpty() && book.format != Book.FORMAT_AUDIO -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Chapters",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(book.chapters) { index, chapter ->
                            val tocLabel = book.toc.firstOrNull { it.chapterIndex == index }?.label
                            ListItem(
                                headlineContent = { Text(tocLabel ?: chapter.label) },
                                supportingContent = {
                                    if (book.format == Book.FORMAT_PDF) {
                                        Text("Pages ${chapter.startPage + 1}–${chapter.endPage + 1}")
                                    }
                                },
                                leadingContent = {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { onPlayChapter(index) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Narrate chapter ${index + 1}")
                                    }
                                },
                                modifier = Modifier.clickable { onOpenChapter(index) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.padding(bottom = 16.dp)) {
                Text("Close")
            }
        }
    }
}

private fun formatAudioClock(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** itemsIndexed for LazyColumn with stable keys. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    list: List<com.speechnotes.app.model.BookChapter>,
    itemContent: @Composable (Int, com.speechnotes.app.model.BookChapter) -> Unit,
) {
    items(count = list.size, key = { index -> list[index].hashCode() }) { index ->
        itemContent(index, list[index])
    }
}
