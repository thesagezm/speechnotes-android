package com.speechnotes.app.data

import kotlinx.serialization.Serializable

import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.logic.TextHash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LRU-capped per-item playback bookmarks in bookmarks.json. Ported from
 * BookmarkStore.swift (speechnotes-ios).
 */
class BookmarkStore {

    @Serializable
    data class PlaybackBookmark(
        /** Notes carry noteId; books carry bookId + chapterIndex instead. */
        val noteId: String? = null,
        val bookId: String? = null,
        val chapterIndex: Int? = null,
        val charsDone: Int,
        val textLength: Int,
        val textHash: Long,
        val savedAt: Long = System.currentTimeMillis(),
    ) {
        val key: String
            get() = when {
                noteId != null -> "note:$noteId"
                bookId != null && chapterIndex != null -> "book:$bookId:$chapterIndex"
                else -> "?"
            }
    }

    private val _bookmarks = MutableStateFlow<List<PlaybackBookmark>>(emptyList())
    val bookmarks: StateFlow<List<PlaybackBookmark>> = _bookmarks.asStateFlow()

    private val maxEntries = 40
    private val validityWindowMs = 30L * 24 * 60 * 60 * 1000

    fun load() {
        val list = Persistence.load(
            file = AppFiles.bookmarksFile(),
            backup = null,
            empty = emptyList(),
            decode = { raw -> runCatching { Persistence.json.decodeFromString<List<PlaybackBookmark>>(raw) }.getOrNull() },
            onQuarantine = { LogStore.warn("BookmarkStore: quarantined corrupt bookmarks.json") },
        )
        _bookmarks.value = list
        pruneExpired()
    }

    /** The bookmark for a note, valid for 30 days and only when the text still matches. */
    fun forNote(noteId: String, textHash: Long, textLength: Int): PlaybackBookmark? {
        val now = System.currentTimeMillis()
        return _bookmarks.value.firstOrNull {
            it.noteId == noteId &&
                it.savedAt > now - validityWindowMs &&
                it.textHash == textHash &&
                it.textLength == textLength
        }
    }

    /** The bookmark for a note regardless of hash — presence checks only. */
    fun forNoteRaw(noteId: String): PlaybackBookmark? =
        _bookmarks.value.firstOrNull { it.noteId == noteId }

    fun forBookChapter(bookId: String, chapterIndex: Int, textHash: Long): PlaybackBookmark? {
        val now = System.currentTimeMillis()
        return _bookmarks.value.firstOrNull {
            it.bookId == bookId && it.chapterIndex == chapterIndex &&
                it.savedAt > now - validityWindowMs &&
                it.textHash == textHash
        }
    }

    fun save(noteId: String? = null, bookId: String? = null, chapterIndex: Int? = null,
             charsDone: Int, textLength: Int, textHash: Long) {
        val entry = PlaybackBookmark(noteId, bookId, chapterIndex, charsDone, textLength, textHash)
        val others = _bookmarks.value.filterNot { it.key == entry.key }
        _bookmarks.value = (listOf(entry) + others).take(maxEntries)
        persist()
    }

    fun remove(key: String) {
        _bookmarks.value = _bookmarks.value.filterNot { it.key == key }
        persist()
    }

    fun clear() {
        _bookmarks.value = emptyList()
        persist()
    }

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - validityWindowMs
        val kept = _bookmarks.value.filter { it.savedAt > cutoff }
        if (kept.size != _bookmarks.value.size) {
            _bookmarks.value = kept
            persist()
        }
    }

    private fun persist() {
        val content = runCatching { Persistence.json.encodeToString(_bookmarks.value) }.getOrNull() ?: return
        // The payload is one small JSON array of ≤ 40 entries — writing it
        // inline is sub-millisecond, and an EXECUTOR here could drop the last
        // pause's position when the process is killed immediately after.
        Persistence.save(AppFiles.bookmarksFile(), null, content)
    }

    companion object {
        fun hashOf(text: String): Long = TextHash.of(text)
    }
}
