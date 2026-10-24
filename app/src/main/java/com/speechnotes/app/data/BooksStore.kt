package com.speechnotes.app.data

import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.model.Book
import com.speechnotes.app.model.BookChapter
import com.speechnotes.app.model.BookTocEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Books library: one manifest.json per book directory under
 * filesDir/Books/<uuid>/. Ported from BooksStore.swift (speechnotes-ios).
 */
class BooksStore {

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    /** Binned books, most recently deleted first — the books recycle bin. */
    private val _deletedBooks = MutableStateFlow<List<Book>>(emptyList())
    val deletedBooks: StateFlow<List<Book>> = _deletedBooks.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun refresh() {
        val list = AppFiles.bookIds().mapNotNull { id ->
            val file = AppFiles.bookManifestFile(id)
            if (!file.exists()) return@mapNotNull null
            runCatching { Persistence.json.decodeFromString<Book>(file.readText()) }.getOrNull()
        }
        _books.value = list.filter { !it.isDeleted }.sortedBy { it.title.lowercase() }
        _deletedBooks.value = list.filter { it.isDeleted }.sortedByDescending { it.deletedAt }
        pruneExpiredDeleted()
    }

    fun book(id: String): Book? = _books.value.firstOrNull { it.id == id }

    /** Serialized disk-write lane, so manifest writes never touch the main thread. */
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "BooksStore-writer").apply { isDaemon = true }
    }

    fun save(book: Book) {
        // In-memory upsert + async manifest write. This used to re-read and
        // re-decode EVERY book's manifest (refresh()) after each save — and
        // save fires on every EPUB page turn and PDF page change, all on the
        // main thread.
        val content = runCatching { Persistence.json.encodeToString(book) }.getOrNull() ?: return
        val target = AppFiles.bookManifestFile(book.id)
        writeExecutor.execute { AppFiles.atomicWrite(target, content) }
        upsertInMemory(book)
    }

    /** Replaces/adds one book in the published lists without touching disk. */
    private fun upsertInMemory(book: Book) {
        val known = _books.value.any { it.id == book.id } || _deletedBooks.value.any { it.id == book.id }
        if (book.isDeleted) {
            _books.value = _books.value.filterNot { it.id == book.id }
            _deletedBooks.value = (_deletedBooks.value.map { if (it.id == book.id) book else it })
                .let { if (known) it else listOf(book) + it }
                .sortedByDescending { it.deletedAt }
        } else {
            _books.value = (_books.value.map { if (it.id == book.id) book else it })
                .let { if (known) it else it + book }
                .sortedBy { it.title.lowercase() }
            _deletedBooks.value = _deletedBooks.value.filterNot { it.id == book.id }
        }
    }

    /** Soft-delete into the recycle bin; the directory stays for recovery. */
    fun delete(book: Book) {
        if (book.isDeleted) return
        save(book.copy(deletedAt = System.currentTimeMillis()))
    }

    /** Moves a binned book back onto the shelf, timestamp removed. */
    fun recover(bookId: String) {
        val book = _deletedBooks.value.firstOrNull { it.id == bookId } ?: return
        save(book.copy(deletedAt = null))
    }

    /** Really deletes one binned book's directory. No undo. */
    fun purge(book: Book) {
        writeExecutor.execute { AppFiles.bookDirectory(book.id).deleteRecursively() }
        _deletedBooks.value = _deletedBooks.value.filterNot { it.id == book.id }
    }

    /** Really deletes every binned book. */
    fun emptyRecycleBin() {
        val doomed = _deletedBooks.value
        writeExecutor.execute {
            for (book in doomed) AppFiles.bookDirectory(book.id).deleteRecursively()
        }
        _deletedBooks.value = emptyList()
    }

    /** Books binned longer than the retention window are purged on refresh. */
    private fun pruneExpiredDeleted() {
        val cutoff = System.currentTimeMillis() - Book.RECYCLE_RETENTION_DAYS * 86_400_000L
        for (book in _deletedBooks.value) {
            val binned = book.deletedAt ?: continue
            if (binned < cutoff) AppFiles.bookDirectory(book.id).deleteRecursively()
        }
    }

    /** Import result — the caller (import pipeline) fills in chapters/cover. */
    fun beginImport(): Book {
        _isImporting.value = true
        _importError.value = null
        return Book()
    }

    fun endImport(book: Book?, error: String? = null) {
        _isImporting.value = false
        if (book != null) {
            val content = runCatching { Persistence.json.encodeToString(book) }.getOrNull()
            if (content != null) {
                val target = AppFiles.bookManifestFile(book.id)
                writeExecutor.execute { AppFiles.atomicWrite(target, content) }
            }
            upsertInMemory(book)
        }
        _importError.value = error
    }

    fun visibleBooks(query: String): List<Book> {
        val q = query.trim()
        if (q.isEmpty()) return _books.value
        return _books.value.filter {
            it.title.contains(q, ignoreCase = true) || (it.author ?: "").contains(q, ignoreCase = true)
        }
    }

    /** Opening a binned book un-bins it (iOS markOpened semantics). */
    fun unbinIfDeleted(bookId: String) {
        if (_deletedBooks.value.any { it.id == bookId }) recover(bookId)
    }

    /** Chapter label helper — the manifest stores either spine hrefs or page ranges. */
    fun chapterLabel(book: Book, index: Int): String? =
        book.chapters.getOrNull(index)?.label ?: book.toc.getOrNull(index)?.label

    fun chapterCount(book: Book): Int = book.chapters.size

    fun isAudioBook(book: Book): Boolean = book.format == Book.FORMAT_AUDIO
}
