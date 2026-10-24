package com.speechnotes.app.data

import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.model.Notebook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notebook list persisted to notebooks.json. Ported from NotebooksStore.swift
 * (speechnotes-ios): trimmed-name validation, case-insensitive duplicate
 * rejection, rolling mirror + corrupt-file quarantine + backup recovery.
 */
class NotebooksStore {

    private val _notebooks = MutableStateFlow<List<Notebook>>(emptyList())
    val notebooks: StateFlow<List<Notebook>> = _notebooks.asStateFlow()

    fun load() {
        val file = AppFiles.notebooksFile()
        val list = Persistence.load(
            file = file,
            backup = AppFiles.notebooksBackupFile(),
            empty = emptyList(),
            decode = { raw -> runCatching { Persistence.json.decodeFromString<List<Notebook>>(raw) }.getOrNull() },
            onQuarantine = { LogStore.warn("NotebooksStore: quarantined corrupt notebooks.json") },
        )
        _notebooks.value = list.sortedBy { it.createdAt }
    }

    fun name(id: String?): String? = _notebooks.value.firstOrNull { it.id == id }?.name

    fun create(rawName: String): Notebook? {
        val name = rawName.trim()
        if (name.isEmpty()) return null
        if (_notebooks.value.any { it.name.equals(name, ignoreCase = true) }) return null
        val notebook = Notebook(name = name)
        _notebooks.value = (_notebooks.value + notebook).sortedBy { it.createdAt }
        save()
        return notebook
    }

    fun rename(notebook: Notebook, toName: String) {
        val rawName = toName
        val name = rawName.trim()
        if (name.isEmpty()) return
        if (_notebooks.value.any { it.id != notebook.id && it.name.equals(name, ignoreCase = true) }) return
        _notebooks.value = _notebooks.value.map { if (it.id == notebook.id) it.copy(name = name) else it }
        save()
    }

    /** Inserts a notebook with a known id (jex import); null when it exists. */
    fun addExisting(id: String, name: String): Notebook? {
        if (_notebooks.value.any { it.id == id }) return null
        val notebook = Notebook(id = id, name = name)
        _notebooks.value = (_notebooks.value + notebook).sortedBy { it.createdAt }
        save()
        return notebook
    }

    fun delete(notebook: Notebook) {
        _notebooks.value = _notebooks.value.filterNot { it.id == notebook.id }
        save()
    }

    private fun save() {
        val content = runCatching { Persistence.json.encodeToString(_notebooks.value) }.getOrNull() ?: return
        writeExecutor.execute { Persistence.save(AppFiles.notebooksFile(), AppFiles.notebooksBackupFile(), content) }
    }

    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "NotebooksStore-writer").apply { isDaemon = true }
    }
}
