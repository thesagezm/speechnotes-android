package com.speechnotes.app.data


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The meeting point between the application-scoped BookDrop receiver and the
 * activity-scoped import pipelines (the same bridge pattern PlaybackRegistry
 * uses for the players): the receiver singleton is created in Application,
 * while SpeechnotesViewModel — which owns the stores — registers its import
 * entry points here at creation.
 */
object BookDropRegistry {

    @Volatile
    var receiver: LocalSendReceiver? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var importFile: ((File) -> Unit)? = null

    /**
     * Called by SpeechnotesViewModel at creation: a landed file routes by
     * extension exactly like the share-intent switch in SpeechnotesApp —
     * books (epub/pdf/audio/office) → importBook, .jex → the JEX importer.
     * The ViewModel hands over Uri-based entry points, so the file is wrapped
     * in a file:// Uri through the FileProvider-backed path the importers
     * already read.
     */
    fun registerImporter(route: (File) -> Unit) {
        importFile = route
    }

    fun routeLanded(file: File) {
        val route = importFile
        if (route == null) {
            LogStore.warn("BookDrop: no importer registered yet — dropping ${file.name}")
            return
        }
        scope.launch {
            runCatching { route(file) }.onFailure { error ->
                LogStore.error("BookDrop: import of ${file.name} failed — ${error.message}")
                receiver?.reportImport(file.name, file.length(), com.speechnotes.app.logic.BookDropRecord.Outcome.Failed(error.message ?: "Import failed"))
            }
        }
    }
}
