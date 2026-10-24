package com.speechnotes.app.data

import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.logic.JoplinCrypto
import com.speechnotes.app.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds all notes in memory and persists them as one JSON file. Deleted
 * notes are kept (flagged `deletedAt`) for Note.RECYCLE_RETENTION_DAYS so the
 * user can recover them from Recently Deleted. Ported from NotesStore.swift
 * (speechnotes-ios).
 *
 * With the vault enabled (Unit 10, iOS `NotesStore` + `JoplinNotesFile`
 * parity) the file is one Joplin StringV1 envelope whose plaintext is the
 * same JSON: writes seal when the vault is unlocked and stay plain when it
 * is off. A sealed file with the vault locked is NEVER rewritten as plain —
 * the write is skipped, stricter than iOS, so an encrypted library can never
 * silently become readable on disk.
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

    /**
     * True while the store holds notes it CANNOT read — a sealed notes.json
     * with no unlocked key. The list screens show the unlock prompt instead
     * of an empty library (an empty library is a lie; the notes are there,
     * just sealed).
     */
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /** The shape of the last file image read/written — the settings page reports it. */
    private val _fileIsSealed = MutableStateFlow(false)
    val fileIsSealed: StateFlow<Boolean> = _fileIsSealed.asStateFlow()

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
        // The shape is detected from the FIRST BYTES (JoplinNotesFile.read
        // parity): a sealed file must never reach the JSON decoder, and must
        // never be quarantined — it is a key problem, not a corruption one.
        val raw = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
        if (raw != null && JoplinCrypto.isEncrypted(raw)) {
            _fileIsSealed.value = true
            val plain = if (EncryptionVault.keyMaterial != null) {
                runCatching { EncryptionVault.decryptText(raw) }.getOrNull()
            } else {
                null
            }
            val decoded = plain?.let { decodeNotesOrNull(it) }
            if (decoded == null) {
                // Sealed and unreadable with the current key state: the
                // library is intact on disk but unreadable until the
                // passphrase arrives — the unlock path re-reads from disk.
                if (plain != null) {
                    LogStore.warn("NotesStore: decrypted notes.json did not decode as a note library")
                }
                _isLocked.value = true
                publish()
                return
            }
            _isLocked.value = false
            _allNotes.value = decoded
            publish()
            pruneExpiredDeleted()
            return
        }
        _fileIsSealed.value = false
        _isLocked.value = false
        val list = Persistence.load(
            file = file,
            backup = backup,
            empty = emptyList(),
            decode = ::decodeNotesOrNull,
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

    /** null signals a corrupt library (the plain path quarantines on it). */
    private fun decodeNotesOrNull(raw: String): List<Note>? =
        runCatching { Persistence.json.decodeFromString<List<Note>>(raw) }.getOrNull()

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
            val key = EncryptionVault.keyMaterial
            if (key == null && _fileIsSealed.value) {
                // The vault locked over a sealed file: writing plaintext
                // would downgrade a sealed library, and there is no key to
                // re-seal with. Deliberately stricter than iOS (which falls
                // back to plain) — the edits stay in memory until unlock.
                return@execute
            }
            val json = runCatching { Persistence.json.encodeToString(snapshot) }.getOrNull() ?: return@execute
            val content = if (key != null) {
                runCatching { EncryptionVault.encryptText(json) }.getOrNull() ?: return@execute
            } else {
                json
            }
            Persistence.save(AppFiles.notesFile(), AppFiles.notesBackupFile(), content)
            _fileIsSealed.value = key != null
        }
    }

    // MARK: - Encryption reseal (iOS NotesStore.resealWithCurrentVault parity)

    /**
     * Raises the locked state after `EncryptionVault.lock()`: the notes stay
     * in memory but the list must show the unlock prompt, not the library
     * (iOS EncryptionSettingsView: "the app shows nothing until unlocked").
     * Unlock re-reads the file from disk, so nothing is lost.
     */
    fun markLocked() {
        if (_fileIsSealed.value) _isLocked.value = true
    }

    /**
     * Rewrites the notes file in the vault's CURRENT state: sealed when the
     * vault is unlocked, plain with `forcePlain` (the turn-off path — the
     * caller must delete the key only after this returns, so a failure here
     * leaves the sealed file and its key intact). Writes the in-memory
     * snapshot, which is strictly fresher than the disk file (every mutation
     * lands in `_allNotes` before its save is scheduled). Runs on the writer
     * thread, ordered against the debounced flushes; the pending one is
     * cancelled first. Returns the note count now on disk.
     */
    suspend fun resealWithCurrentVault(forcePlain: Boolean): Int = withContext(Dispatchers.IO) {
        saveJob?.cancel()
        writeExecutor.submit<Int> { resealBlocking(forcePlain) }.get()
    }

    private fun resealBlocking(forcePlain: Boolean): Int {
        val snapshot = _allNotes.value
        val key = EncryptionVault.keyMaterial
        if (key == null && !forcePlain) {
            // Cannot seal, and the file must not silently become plain.
            LogStore.warn("NotesStore: reseal requested with a locked vault — file left as it is")
            return snapshot.size
        }
        if (_isLocked.value) {
            // The in-memory list is a locked placeholder, not the library:
            // writing it would replace sealed notes. Unlock first.
            LogStore.warn("NotesStore: reseal refused while the sealed file is unreadable")
            throw EncryptionVault.VaultError.Locked
        }
        val json = runCatching { Persistence.json.encodeToString(snapshot) }.getOrNull()
            ?: throw IllegalStateException("notes.json encode failed")
        val content = if (!forcePlain && key != null) {
            EncryptionVault.encryptText(json)
        } else {
            json
        }
        Persistence.save(AppFiles.notesFile(), AppFiles.notesBackupFile(), content)
        _fileIsSealed.value = !forcePlain && key != null
        _isLocked.value = false
        return snapshot.size
    }

    /**
     * Re-reads the notes file after an unlock. Returns the note count that
     * came back, or null when the file could not be opened with the new key
     * (corrupt content, foreign envelope) — the caller then leaves the store
     * empty rather than half-loaded. A wrong passphrase never reaches here:
     * the KeyV1 unwrap in EncryptionVault.unlock gates it.
     */
    suspend fun reloadAfterUnlock(): Int? = withContext(Dispatchers.IO) {
        writeExecutor.submit<Int?> { reloadBlocking() }.get()
    }

    private fun reloadBlocking(): Int? {
        val raw = runCatching { AppFiles.notesFile().takeIf { it.exists() }?.readText() }.getOrNull()
            ?: return if (_allNotes.value.isEmpty()) 0 else _allNotes.value.size
        val plain = if (JoplinCrypto.isEncrypted(raw)) {
            _fileIsSealed.value = true
            runCatching { EncryptionVault.decryptText(raw) }.getOrNull() ?: return null
        } else {
            _fileIsSealed.value = false
            raw
        }
        val decoded = decodeNotesOrNull(plain) ?: run {
            LogStore.warn("NotesStore: unlocked content did not decode — file left untouched")
            return null
        }
        _allNotes.value = decoded
        _isLocked.value = false
        rowMetadata.clear()
        publish()
        pruneExpiredDeleted()
        LogStore.info("NotesStore unlocked: ${decoded.size} note(s)")
        return decoded.size
    }

    /** Serialized disk-write lane; the JVM exits don't need it flushed (writes are small and frequent). */
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "NotesStore-writer").apply { isDaemon = true }
    }
}
