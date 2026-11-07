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

    /** The send half (v1.8.1 port) — created next to the receiver in Application. */
    @Volatile
    var sender: LocalSendSender? = null

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
            runCatching { route(file) }
                .onSuccess {
                    // The accept gate (BookDropCore.acceptedExtensions) only
                    // lets landable formats through, so a handed-off file is
                    // on its way into the library — record it (iOS reports
                    // the awaited outcome; the importers here are
                    // fire-and-forget, and their failures toast).
                    //
                    // The WAV and markdown routes report their own outcome
                    // (they save to Exports / create a note and say so), so
                    // they are not reported twice.
                    val ext = file.extension.lowercase()
                    if (ext in SELF_REPORTED_ROUTES) return@onSuccess
                    val detail = if (ext == "jex") "Importing into notes" else "Importing into the shelf"
                    receiver?.reportImport(
                        file.name,
                        file.length(),
                        com.speechnotes.app.logic.BookDropRecord.Outcome.Imported(detail),
                    )
                }
                .onFailure { error ->
                    LogStore.error("BookDrop: import of ${file.name} failed — ${error.message}")
                    receiver?.reportImport(file.name, file.length(), com.speechnotes.app.logic.BookDropRecord.Outcome.Failed(error.message ?: "Import failed"))
                }
        }
    }

    /**
     * Routes that report their own BookDrop history entry (the WAV route
     * and the markdown→note route), so the generic hand-off report above
     * would double up.
     */
    private val SELF_REPORTED_ROUTES = setOf("wav", "md", "markdown", "txt", "text")
}
