package com.speechnotes.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.logic.DocumentBlock
import com.speechnotes.app.logic.DocumentBook
import com.speechnotes.app.model.Book
import com.speechnotes.app.model.Note
import com.speechnotes.app.model.Notebook
import com.speechnotes.app.speech.EngineKind
import com.speechnotes.app.speech.ModelFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A model download in progress (or its result). */
sealed class DownloadState {
    data object NotDownloaded : DownloadState()
    data class Downloading(val progress: Double) : DownloadState()
    data class Failed(val message: String) : DownloadState()
    data object Ready : DownloadState()
}

/**
 * One-time model downloads for the neural engines, ported from ModelManager
 * (speechnotes-ios). Downloads into filesDir/KokoroOnnx and
 * filesDir/Supertonic with size validation; nothing else in the app touches
 * the network (the offline rule).
 */
class ModelManager(private val context: Context) {

    // Model files are hundreds of MB. Two failure modes drove these numbers:
    //  - the platform's 10 s read timeout kills long transfers outright;
    //  - huggingface's CDN resets the socket mid-stream on multi-hundred-MB
    //    files (the first device round died with a bare "Connection reset").
    // A long callTimeout plus per-file retry/resume is the fix; without it
    // every engine reported "download failed" and looked like a dead button.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)   // no read timeout
        .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)   // no overall timeout
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Per-file attempts before the engine is declared failed. */
    private val maxAttempts = 3

    private val _kokoroState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val kokoroState: StateFlow<DownloadState> = _kokoroState.asStateFlow()

    private val _kokoroSmallState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val kokoroSmallState: StateFlow<DownloadState> = _kokoroSmallState.asStateFlow()

    private val _supertonicState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val supertonicState: StateFlow<DownloadState> = _supertonicState.asStateFlow()

    /** Called on the main thread when any model becomes ready. */
    var onReady: (() -> Unit)? = null

    fun refreshStates() {
        // Reclaim the superseded ~177 MB uint8 small-tier build before the
        // validity windows run (iOS removeQuantizedDownloads parity).
        ModelFiles.removeLegacySmallModel()
        _kokoroState.value = stateFor(EngineKind.KOKORO)
        _kokoroSmallState.value = stateFor(EngineKind.KOKORO_SMALL)
        _supertonicState.value = stateFor(EngineKind.SUPERTONIC)
    }

    fun stateFor(engine: EngineKind): DownloadState {
        val set = ModelFiles.setFor(engine) ?: return DownloadState.NotDownloaded
        return if (ModelFiles.isValid(engine)) DownloadState.Ready else DownloadState.NotDownloaded
    }

    fun isReady(engine: EngineKind): Boolean = stateFor(engine) == DownloadState.Ready

    /**
     * Whether every file in the engine's set exists at min size — cached in
     * the same StateFlow the download rows observe. Settings used to call
     * [isReady] (live disk stats) during composition, on every row on every
     * recomposition — a big part of why toggles felt sluggish there.
     */
    fun isReadyCached(engine: EngineKind): Boolean = when (engine) {
        EngineKind.KOKORO -> _kokoroState.value is DownloadState.Ready
        EngineKind.KOKORO_SMALL -> _kokoroSmallState.value is DownloadState.Ready
        EngineKind.SUPERTONIC -> _supertonicState.value is DownloadState.Ready
        EngineKind.SYSTEM -> true
    }

    fun startDownload(engine: EngineKind, scope: kotlinx.coroutines.CoroutineScope) {
        val set = ModelFiles.setFor(engine) ?: return
        val state = stateFlowFor(engine) ?: return
        if (state.value is DownloadState.Downloading) return
        state.value = DownloadState.Downloading(0.0)

        scope.launch {
            val result = withContext(Dispatchers.IO) { downloadSet(set, state) }
            when (result) {
                is DownloadResult.Ok -> {
                    state.value = DownloadState.Ready
                    onReady?.invoke()
                }
                is DownloadResult.Error -> {
                    LogStore.error("ModelManager: ${engine.key} download failed — ${result.message}")
                    state.value = DownloadState.Failed(result.message)
                }
            }
        }
    }

    fun deleteModel(engine: EngineKind) {
        val set = ModelFiles.setFor(engine) ?: return
        // The Kokoro tiers share a directory; deleting one tier removes only
        // its own model file, never the shared tokenizer/voices. The other
        // engines own their directories outright.
        set.files.forEach { entry ->
            val file = File(set.directory, entry.name)
            if (!file.isFile) return@forEach
            val sharedKokoroFile = engine != EngineKind.SUPERTONIC &&
                (entry.name.contains("tokenizer") || entry.name.contains("voices"))
            if (!sharedKokoroFile) runCatching { file.delete() }
        }
        refreshStates()
    }

    private sealed class DownloadResult {
        data object Ok : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    private fun downloadSet(set: ModelFiles.ModelSet, state: MutableStateFlow<DownloadState>): DownloadResult {
        val total = set.files.sumOf { it.minBytes }
        var done = 0L
        for (entry in set.files) {
            val target = File(set.directory, entry.name)
            if (target.isFile && target.length() >= entry.minBytes) {
                done += entry.minBytes
                continue
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            var lastError: String? = null
            var attempt = 0
            // Per-file retry with RESUME: a CDN reset on a 256 MB file used to
            // throw away the whole transfer. The .part file keeps the bytes we
            // already have and each attempt sends a Range header, so a flaky
            // link costs one chunk instead of the entire model.
            while (attempt < maxAttempts) {
                attempt++
                val already = if (tmp.isFile) tmp.length() else 0L
                if (already >= entry.minBytes) break
                try {
                    val builder = Request.Builder().url(entry.url)
                    if (already > 0) builder.header("Range", "bytes=$already-")
                    client.newCall(builder.build()).execute().use { response ->
                        val code = response.code
                        // 206 = resumed, 200 = server ignored the Range and is
                        // sending the whole file, so start over rather than
                        // concatenating two copies of the same bytes.
                        val append = code == 206 && already > 0
                        if (code != 200 && code != 206) {
                            if (code in 400..499 && code != 429) {
                                // A client error is not going to improve.
                                return DownloadResult.Error("HTTP $code for ${entry.name}")
                            }
                            lastError = "HTTP $code for ${entry.name}"
                            return@use
                        }
                        val body = response.body
                            ?: return DownloadResult.Error("Empty body for ${entry.name}")
                        body.byteStream().use { input ->
                            java.io.FileOutputStream(tmp, append).use { output ->
                                val buffer = ByteArray(256 * 1024)
                                var bytesInFile = already
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    bytesInFile += read
                                    val overall = ((done + bytesInFile).coerceAtMost(total)).toDouble() / total
                                    state.value = DownloadState.Downloading(overall.coerceIn(0.0, 0.99))
                                }
                                output.flush()
                            }
                        }
                    }
                } catch (e: IOException) {
                    // Partial bytes stay on disk for the next attempt.
                    lastError = e.message ?: "network error"
                    LogStore.warn(
                        "ModelManager: ${entry.name} attempt $attempt/$maxAttempts failed at " +
                            "${tmp.length()} bytes — ${e.javaClass.simpleName}: $lastError"
                    )
                    if (attempt < maxAttempts) {
                        // Brief backoff; the CDN needs a moment before it will
                        // serve the same range again.
                        runCatching { Thread.sleep(1500L * attempt) }
                    }
                    continue
                }
                if (tmp.length() >= entry.minBytes) {
                    lastError = null
                    break
                }
                lastError = "${entry.name} is incomplete (${tmp.length()} bytes)"
                if (attempt < maxAttempts) runCatching { Thread.sleep(1200L * attempt) }
            }
            if (lastError != null || tmp.length() < entry.minBytes) {
                tmp.delete()
                return DownloadResult.Error(
                    lastError ?: "${entry.name} is incomplete (${tmp.length()} bytes)"
                )
            }
            if (!tmp.renameTo(target)) {
                target.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            done += entry.minBytes
            state.value = DownloadState.Downloading(done.toDouble() / total)
        }
        return DownloadResult.Ok
    }

    private fun stateFlowFor(engine: EngineKind): MutableStateFlow<DownloadState>? = when (engine) {
        EngineKind.KOKORO -> _kokoroState
        EngineKind.KOKORO_SMALL -> _kokoroSmallState
        EngineKind.SUPERTONIC -> _supertonicState
        EngineKind.SYSTEM -> null
    }

    // MARK: - Joplin .jex export + share

    fun exportJex(notes: List<Note>, notebooks: List<Notebook>): File? {
        val payloads = com.speechnotes.app.logic.JexExport.buildArchive(
            notes = notes.filter { it.deletedAt == null }.map { note ->
                com.speechnotes.app.logic.JexExport.NotePayload(
                    id = com.speechnotes.app.logic.JexExport.joplinId(note.id),
                    title = note.title,
                    markdownBody = note.text,
                    notebookId = note.notebookId?.let { com.speechnotes.app.logic.JexExport.joplinId(it) },
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                    // E5: images ride along as Joplin resources — the
                    // importer on the other side rewrites the links.
                    images = jexImages(note),
                )
            },
            notebooks = notebooks.map { nb ->
                com.speechnotes.app.logic.JexExport.NotebookPayload(
                    id = com.speechnotes.app.logic.JexExport.joplinId(nb.id),
                    title = nb.name,
                    parentId = null,
                    createdAt = nb.createdAt,
                )
            },
        )
        val out = File(AppFiles.exportsDir, "speechnotes-backup.jex")
        return runCatching {
            out.parentFile?.mkdirs()
            out.writeBytes(payloads)
            out
        }.getOrNull()
    }

    /**
     * Materializes every `speechnotes://note-image/<hash>.<ext>` reference
     * in the note's markdown as a Joplin resource payload. Joplin wants
     * 32-hex resource ids; NoteImageStore's content hashes are longer, so
     * the leading 32 are used (collision-safe at this scale, iOS parity).
     */
    private fun jexImages(note: Note): List<com.speechnotes.app.logic.JexExport.ImagePayload> {
        val targets = com.speechnotes.app.logic.NoteImageStore.allTargets(note.id)
            .filter { note.text.contains(it) }
        return targets.mapNotNull { target ->
            val parsed = com.speechnotes.app.logic.NoteImageStore.parseLocalTarget(target) ?: return@mapNotNull null
            val file = com.speechnotes.app.logic.NoteImageStore.resolveLocalFile(target, note.id) ?: return@mapNotNull null
            val data = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
            com.speechnotes.app.logic.JexExport.ImagePayload(
                id = parsed.first.take(32).lowercase(),
                fileExtension = parsed.second,
                mimeType = when (parsed.second.lowercase()) {
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    "heic", "heif" -> "image/heic"
                    else -> "application/octet-stream"
                },
                data = data,
            )
        }
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension) {
                "wav" -> "audio/wav"
                "jex" -> "application/octet-stream"
                else -> "text/plain"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // ModelManager holds the application context, and createChooser()
        // builds a NEW intent that does NOT inherit flags from the wrapped
        // one — NEW_TASK must go on the CHOOSER or startActivity throws.
        context.startActivity(
            Intent.createChooser(intent, "Share")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // MARK: - Import (file → note text)

    fun importTextFrom(uri: android.net.Uri): Pair<String, String>? {
        return runCatching {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else "imported"
            } ?: "imported"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val ext = name.substringAfterLast('.', "").lowercase()
            val text = when {
                // Office documents as notes (iOS ImportService.officeDocumentText
                // parity): the same normalize parsers the books shelf uses,
                // reduced to text with tables as comma-joined lines.
                DocumentBook.canParse(ext) -> documentText(bytes, ext)
                ext == "pdf" -> com.speechnotes.app.logic.XhtmlText.plainText(String(bytes, Charsets.ISO_8859_1)) // PDFs go through PdfText on the books side; a plain import falls back to raw decode
                else -> decodeText(bytes)
            }
            val cleaned = com.speechnotes.app.logic.SpeechSanitizer.clean(text)
            if (cleaned.isBlank()) null else name to cleaned
        }.getOrNull()
    }

    /** A parsed office document as plain text: one line per block, table rows comma-joined. */
    private fun documentText(bytes: ByteArray, ext: String): String {
        val parsed = DocumentBook.parse(ext, bytes)
        return parsed.chapters.joinToString("\n\n") { chapter ->
            val heading = chapter.title?.takeIf { it.isNotEmpty() }
            val body = chapter.blocks.joinToString("\n") { block ->
                when (block) {
                    is DocumentBlock.Paragraph -> block.text
                    is DocumentBlock.Table ->
                        block.rows.joinToString("\n") { row ->
                            row.joinToString(", ") { it.text }
                        }
                    // Blocks are stripped in note text: a note has no images.
                    else -> ""
                }
            }.trim()
            listOfNotNull(heading, body.takeIf { it.isNotEmpty() }).joinToString("\n")
        }.trim()
    }

    private fun decodeText(bytes: ByteArray): String {
        // UTF-8 first, then UTF-16 BOMs, then latin-1 — mirrors the iOS fallbacks.
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, Charsets.UTF_16BE)
        }
        val decoded = String(bytes, Charsets.UTF_8)
        if (decoded.contains('\uFFFD')) return String(bytes, Charsets.ISO_8859_1)
        return decoded
    }

    fun storageUsageFiles(): List<Pair<String, Long>> =
        listOf(
            "Notes" to AppFiles.notesImagesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            "Exports" to AppFiles.exportsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            "Kokoro model" to AppFiles.kokoroDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            "Supertonic model" to AppFiles.supertonicDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            "Books" to AppFiles.booksDirectorySize(),
        )

    // The Storage card used to call storageUsageFiles() DURING composition —
    // a synchronous walk of every model/note/book directory on the main
    // thread on every Settings recomposition (i.e. every toggle flip). It
    // now runs once, off-thread, into this flow.
    private val _storageUsage = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val storageUsage: StateFlow<List<Pair<String, Long>>> = _storageUsage.asStateFlow()

    fun refreshStorageUsage(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            _storageUsage.value = withContext(Dispatchers.IO) { storageUsageFiles() }
        }
    }
}
