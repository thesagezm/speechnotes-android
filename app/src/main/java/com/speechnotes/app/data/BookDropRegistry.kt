package com.speechnotes.app.data

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

    @Volatile
    private var importFile: (suspend (File) -> Unit)? = null

    /**
     * Called by SpeechnotesViewModel at creation: a landed file routes by
     * extension exactly like the share-intent switch in SpeechnotesApp —
     * books (epub/pdf/audio/office) → importBook, .jex → the JEX importer.
     * The ViewModel hands over Uri-based entry points, so the file is wrapped
     * in a file:// Uri through the FileProvider-backed path the importers
     * already read.
     *
     * SUSPENDING on purpose: the receiver awaits the route so its grace
     * sweep cannot delete a file that is still being imported (a large
     * audiobook takes minutes to land in the library).
     */
    fun registerImporter(route: suspend (File) -> Unit) {
        importFile = route
    }

    /** Routes one landed file and reports the outcome in the history. */
    suspend fun routeLanded(file: File) {
        val route = importFile
        if (route == null) {
            LogStore.warn("BookDrop: no importer registered yet — dropping ${file.name}")
            return
        }
        // The name and the size are read BEFORE the route: a book import
        // MOVES the file into the library, so afterwards there is nothing
        // left to measure.
        val name = file.name
        val size = file.length()
        val ext = file.extension.lowercase()
        runCatching { route(file) }
            .onSuccess {
                // The accept gate (BookDropCore.acceptedExtensions) only
                // lets landable formats through, so a handed-off file is
                // on its way into the library — record it (iOS reports
                // the awaited outcome; the importers here report their own
                // failures as an importError on the shelf card).
                //
                // The WAV and markdown routes report their own outcome
                // (they save to Exports / create a note and say so), so
                // they are not reported twice.
                if (ext in SELF_REPORTED_ROUTES) return@onSuccess
                val detail = if (ext == "jex") "Importing into notes" else "Importing into the shelf"
                receiver?.reportImport(name, size, com.speechnotes.app.logic.BookDropRecord.Outcome.Imported(detail))
            }
            .onFailure { error ->
                LogStore.error("BookDrop: import of $name failed — ${error.message}")
                receiver?.reportImport(name, size, com.speechnotes.app.logic.BookDropRecord.Outcome.Failed(error.message ?: "Import failed"))
            }
    }

    /**
     * Routes that report their own BookDrop history entry (the WAV route
     * and the markdown→note route), so the generic hand-off report above
     * would double up.
     */
    private val SELF_REPORTED_ROUTES = setOf("wav", "md", "markdown", "txt", "text")
}
