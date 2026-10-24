package com.speechnotes.app.data

import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds all notes in memory and persists them as one JSON file. Deleted
 * notes are kept (flagged `deletedAt`) for Note.RECYCLE_RETENTION_DAYS so the
 * user can recover them from Recently Deleted. Ported from NotesStore.swift
 * (speechnotes-ios).
 */
class NotesStore(private val scope: CoroutineScope?) {

    /** Everything, including notes sitting in the recycle bin. */
    private val _allNotes = MutableStateFlow<List<Note>>(emptyList())

    /** Active notes only — what the whole UI reads. */
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    /** Binned notes, most recently deleted first. */
    private val _deletedNotes = MutableStateFlow<List<Note>>(emptyList())
    val deletedNotes: StateFlow<List<Note>> = _deletedNotes.asStateFlow()

    private val rowMetadata = HashMap<String, RowMetadata>()
    private var saveJob: Job? = null

    data class RowMetadata(
        val title: String,
        val preview: String,
        val wordCount: Int,
        val listenMinutes: Int?,
    )

    fun load() {
        val file = AppFiles.notesFile()
        val backup = AppFiles.notesBackupFile()
        val list = Persistence.load(
            file = file,
            backup = backup,
            empty = emptyList(),
            decode = { raw -> runCatching { Persistence.json.decodeFromString<List<Note>>(raw) }.getOrNull() },
            onQuarantine = { LogStore.warn("NotesStore: quarantined corrupt notes.json") },
        )
        // Loading runs on IO after the UI is up; a share-intent note created
        // in that window must survive the disk list replacing the in-memory
        // one (its own save is already scheduled).
        val createdDuringLoad = _allNotes.value.filter { fresh -> list.none { it.id == fresh.id } }
        _allNotes.value = if (createdDuringLoad.isEmpty()) list else createdDuringLoad + list
        publish()
        pruneExpiredDeleted()
    }

    // MARK: - Queries

    fun note(id: String): Note? = _allNotes.value.firstOrNull { it.id == id }

    fun notes(inNotebook: String?): List<Note> = _notes.value.filter { it.notebookId == inNotebook }

    fun metadata(note: Note): RowMetadata = rowMetadata.getOrPut(note.id) {
        RowMetadata(
            title = note.title,
            preview = note.preview,
            wordCount = note.wordCount,
            listenMinutes = note.estimatedListenMinutes,
        )
    }

    private fun publish() {
        _notes.value = _allNotes.value.filter { it.deletedAt == null }
        _deletedNotes.value = _allNotes.value
            .filter { it.deletedAt != null }
            .sortedByDescending { it.deletedAt }
    }

    // MARK: - Mutations

    fun createNote(notebookId: String? = null): Note {
        val note = Note(notebookId = notebookId)
        _allNotes.value = listOf(note) + _allNotes.value
        publish()
        scheduleSave()
        return note
    }

    fun setPinned(pinned: Boolean, noteId: String) = mutate(noteId) { it.copy(isPinned = pinned) }

    fun setFavorite(favorite: Boolean, noteId: String) = mutate(noteId) { it.copy(isFavorite = favorite) }

    fun move(noteId: String, toNotebookId: String?) = mutate(noteId) {
        val notebookId = toNotebookId
        if (it.notebookId == notebookId) it
        else it.copy(notebookId = notebookId, updatedAt = System.currentTimeMillis())
    }

    /** After a notebook is deleted: its notes fall back to Unfiled. */
    fun clearNotebook(notebookId: String) {
        var changed = false
        _allNotes.value = _allNotes.value.map {
            if (it.notebookId == notebookId) { changed = true; it.copy(notebookId = null) } else it
        }
        if (changed) { publish(); scheduleSave() }
    }

    fun update(note: Note) = mutate(note.id) {
        it.copy(
            text = note.text,
            explicitTitle = note.explicitTitle,
            isPinned = note.isPinned,
            isFavorite = note.isFavorite,
            notebookId = note.notebookId,
        )
    }

    private fun mutate(noteId: String, transform: (Note) -> Note) {
        _allNotes.value = _allNotes.value.map { if (it.id == noteId) transform(it) else it }
        rowMetadata.remove(noteId)
        publish()
        scheduleSave()
    }

    /** Inserts a note with a known id (jex import), restoring its metadata. */
    fun importNote(
        id: String,
        text: String,
        notebookId: String?,
        explicitTitle: String? = null,
        createdAt: Long? = null,
        updatedAt: Long? = null,
    ) {
        if (_allNotes.value.any { it.id == id }) return
        val note = Note(
            id = id,
            text = text,
            notebookId = notebookId,
            explicitTitle = explicitTitle?.takeIf { it.isNotBlank() },
            createdAt = createdAt ?: System.currentTimeMillis(),
            updatedAt = updatedAt ?: createdAt ?: System.currentTimeMillis(),
        )
        _allNotes.value = listOf(note) + _allNotes.value
        publish()
        scheduleSave()
    }

    /** Soft-delete by identity — moves the note to Recently Deleted. */
    fun delete(noteId: String) {
        _allNotes.value = _allNotes.value.map {
            if (it.id == noteId && it.deletedAt == null) it.copy(deletedAt = System.currentTimeMillis()) else it
        }
        publish()
        scheduleSave()
    }

    fun recover(noteId: String) = mutate(noteId) { it.copy(deletedAt = null) }

    fun purge(noteId: String) {
        _allNotes.value = _allNotes.value.filterNot { it.id == noteId }
        rowMetadata.remove(noteId)
        publish()
        scheduleSave()
    }

    fun emptyRecycleBin() {
        _allNotes.value = _allNotes.value.filter { it.deletedAt == null }
        publish()
        scheduleSave()
    }

    /** Notes binned longer than the retention window are purged on load. */
    private fun pruneExpiredDeleted() {
        val cutoff = System.currentTimeMillis() - Note.RECYCLE_RETENTION_DAYS * 86_400_000L
        val kept = _allNotes.value.filterNot { it.deletedAt != null && it.deletedAt < cutoff }
        if (kept.size != _allNotes.value.size) {
            _allNotes.value = kept
            publish()
            scheduleSave()
        }
    }

    // MARK: - Persistence (debounced)

    private fun scheduleSave() {
        saveJob?.cancel()
        if (scope != null) {
            saveJob = scope.launch {
                delay(400)
                flushNow()
            }
        } else {
            flushNow()
        }
    }

    fun flushNow() {
        // Serialize + write OFF the main thread. This used to encode the
        // whole library on the CALLER (main, mid-typing) and only offload the
        // file write. The snapshot is taken synchronously so a later edit
        // can't interleave with the encode; the single-thread executor keeps
        // writes ordered.
        val snapshot = _allNotes.value
        writeExecutor.execute {
            val content = runCatching { Persistence.json.encodeToString(snapshot) }.getOrNull() ?: return@execute
            Persistence.save(AppFiles.notesFile(), AppFiles.notesBackupFile(), content)
        }
    }

    /** Serialized disk-write lane; the JVM exits don't need it flushed (writes are small and frequent). */
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "NotesStore-writer").apply { isDaemon = true }
    }
}
