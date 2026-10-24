package com.speechnotes.app.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for the LocalSend protocol v2.2 receiver (see the iOS repo's
 * Docs/BOOKDROP.md for the spec map). Field names ARE the wire format — the
 * serial names below are deliberate; don't "tidy" them.
 */
@Serializable
data class LocalSendDevice(
    val alias: String,
    val version: String,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String,
    val port: Int,
    @SerialName("protocol") val protocolField: String,
    val download: Boolean? = false,
)

/** One file offered in a prepare-upload request. */
@Serializable
data class LocalSendFileMeta(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String? = null,
    val sha256: String? = null,
    val preview: String? = null,
    val metadata: FileMetadata? = null,
) {
    @Serializable
    data class FileMetadata(
        val modified: String? = null,
        val accessed: String? = null,
    )
}

/** POST /api/localsend/v2/prepare-upload request body. */
@Serializable
data class LocalSendPrepareUpload(
    val info: LocalSendDevice,
    val pin: String? = null,
    val files: Map<String, LocalSendFileMeta>,
)

/** prepare-upload's 200 response — sessionId plus a per-file token. */
@Serializable
data class LocalSendPrepareResponse(
    val sessionId: String,
    val files: Map<String, String>,
)

/** One completed (or routed) transfer, for the BookDrop history list. */
data class BookDropRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val size: Long,
    val outcome: Outcome,
    val at: Long = System.currentTimeMillis(),
) {
    sealed class Outcome {
        data class Imported(val detail: String) : Outcome()
        data class Failed(val detail: String) : Outcome()
        data object Rejected : Outcome()
    }
}

/**
 * The BookDrop protocol state machine — no sockets, no Android, no clock of
 * its own ([now] is injected so the session timeout is testable).
 *
 * Ported from LocalSendReceiver.swift (speechnotes-ios), whose endpoint logic
 * is MainActor-isolated. Here every entry point is guarded by [lock], so the
 * observable behavior — the validation order and the status codes — is the
 * same while concurrent uploads into one session serialize.
 *
 * Status codes (per spec + iOS behavior):
 *  register/info always 200 · prepare-upload 403 (rejected / auto-accept off),
 *  409 (busy) · upload 400 (bad params), 403 (wrong sender or token),
 *  409 (duplicate fileId), 422 (size or sha256 mismatch) · cancel 400 (unknown).
 */
class BookDropCore(
    /** Reassigned once at start(), after the server picks its port. */
    var device: LocalSendDevice,
    /** Directory rooted session dirs are created under (per-session subdir). */
    val sessionsRoot: java.io.File? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionTimeoutMs: Long = 5 * 60 * 1000L,
) {
    private val lock = Any()
    private val sessions = HashMap<String, BookDropSession>()
    private val history = ArrayList<BookDropRecord>()

    /** @Volatile so the Settings UI observes the user's toggle immediately. */
    @Volatile
    var autoAccept: Boolean = true

    fun currentHistory(): List<BookDropRecord> = synchronized(history) { ArrayList(history) }

    private fun insertHistory(record: BookDropRecord) {
        synchronized(history) {
            history.add(0, record)
            if (history.size > 20) history.subList(20, history.size).clear()
        }
    }

    // MARK: - Discovery

    fun registerOrInfo(): LocalSendResponse =
        LocalSendResponse.json(deviceJson(device.copy(port = device.port)))

    private fun deviceJson(value: LocalSendDevice): String = json.encodeToString(LocalSendDevice.serializer(), value)

    // MARK: - prepare-upload

    fun prepareUpload(bodyJson: String, senderHost: String?): LocalSendResponse {
        val payload = runCatching {
            json.decodeFromString(LocalSendPrepareUpload.serializer(), bodyJson)
        }.getOrNull() ?: return LocalSendResponse.badRequest()

        synchronized(lock) {
            if (!autoAccept) return LocalSendResponse.forbidden()
            if (sessions.isNotEmpty()) return LocalSendResponse.conflict()

            val accepted = payload.files.filter { (fileId, meta) ->
                fileId.isNotEmpty() && meta.fileName.isNotEmpty() &&
                    acceptedExtensions.contains(extension(meta.fileName))
            }
            // Partial accept: omitted file ids count as rejected by the sender,
            // and rejecting EVERY file is a 403 for the whole request.
            if (accepted.isEmpty()) return LocalSendResponse.forbidden()

            val root = sessionsRoot ?: java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp").apply { mkdirs() }
            val session = BookDropSession(
                id = java.util.UUID.randomUUID().toString(),
                senderHost = senderHost,
                files = accepted,
                dir = java.io.File(root, "BookDrop-" + java.util.UUID.randomUUID()).apply { mkdirs() },
                createdAt = now(),
            )
            sessions[session.id] = session
            return LocalSendResponse.json(
                json.encodeToString(
                    LocalSendPrepareResponse.serializer(),
                    LocalSendPrepareResponse(session.id, session.tokens),
                ),
            )
        }
    }

    // MARK: - upload

    /**
     * Validates and lands one file body. [body] is either landed bytes or a
     * spilled file we hash in place — book files reach hundreds of MB and must
     * never fully materialize in RAM (the iOS 8 MB spill rule).
     */
    fun upload(
        sessionId: String?,
        fileId: String?,
        token: String?,
        remoteHost: String?,
        body: UploadBody,
    ): LocalSendResponse {
        if (sessionId == null || fileId == null || token == null) return LocalSendResponse.badRequest()
        synchronized(lock) {
            val session = sessions[sessionId] ?: return LocalSendResponse.badRequest()
            // A third party must not inject files into someone's session.
            if (session.senderHost != null && session.senderHost != remoteHost) {
                return LocalSendResponse.forbidden()
            }
            if (session.tokens[fileId] != token) return LocalSendResponse.forbidden()
            if (session.receivedIds.contains(fileId)) return LocalSendResponse.conflict()
            val meta = session.files[fileId] ?: return LocalSendResponse.badRequest()

            val byteCount = body.byteCount()
            if (byteCount != meta.size) return LocalSendResponse.unsupported()
            val expected = meta.sha256?.lowercase()
            if (!expected.isNullOrEmpty()) {
                val actual = when (body) {
                    is UploadBody.Memory -> LocalSendHTTPServer.sha256Hex(body.data)
                    is UploadBody.File -> LocalSendHTTPServer.sha256Hex(body.file)
                }
                if (actual != expected) return LocalSendResponse.unsupported()
            }

            val target = java.io.File(session.dir, safeFileName(meta.fileName, fileId))
            val landed = when (body) {
                is UploadBody.Memory -> runCatching { target.writeBytes(body.data) }.isSuccess
                is UploadBody.File -> runCatching {
                    target.parentFile?.mkdirs()
                    body.file.copyTo(target, overwrite = true)
                    body.file.delete()
                }.isSuccess
            }
            if (!landed) return LocalSendResponse.status(500)

            session.receivedIds.add(fileId)
            if (session.receivedIds.size >= session.files.size) complete(session)
            return LocalSendResponse.status(200)
        }
    }

    sealed class UploadBody {
        abstract fun byteCount(): Long

        data class Memory(val data: ByteArray) : UploadBody() {
            override fun byteCount(): Long = data.size.toLong()
            override fun equals(other: Any?): Boolean = other is Memory && other.data.contentEquals(data)
            override fun hashCode(): Int = data.contentHashCode()
        }

        data class File(val file: java.io.File) : UploadBody() {
            override fun byteCount(): Long = file.length()
        }
    }

    /** One body of a finished transfer, landed in its session dir. */
    data class LandedFile(val fileId: String, val fileName: String, val file: java.io.File)

    // MARK: - cancel

    fun cancel(sessionId: String?): LocalSendResponse {
        if (sessionId == null) return LocalSendResponse.badRequest()
        synchronized(lock) {
            val session = sessions.remove(sessionId) ?: return LocalSendResponse.badRequest()
            runCatching { session.dir.deleteRecursively() }
            return LocalSendResponse.status(200)
        }
    }

    /** Called by the app on returning to foreground (the iOS didBecomeActive rule). */
    fun sweepExpiredSessions() {
        synchronized(lock) {
            val cutoff = now() - sessionTimeoutMs
            val expired = sessions.values.filter { it.createdAt < cutoff }
            for (session in expired) {
                sessions.remove(session.id)
                val received = session.receivedIds.size
                val offered = session.files.size
                runCatching { session.dir.deleteRecursively() }
                if (received > 0) {
                    insertHistory(
                        BookDropRecord(
                            name = "$received of $offered file${if (offered == 1) "" else "s"}",
                            size = 0L,
                            outcome = BookDropRecord.Outcome.Failed("Transfer timed out"),
                        ),
                    )
                }
            }
        }
    }

    // MARK: - Completion

    /** The files that landed, in the order they were received. */
    fun landedFiles(sessionId: String): List<LandedFile> {
        synchronized(lock) {
            val session = sessions[sessionId] ?: return emptyList()
            return session.receivedIds.mapNotNull { fileId ->
                val meta = session.files[fileId] ?: return@mapNotNull null
                val file = java.io.File(session.dir, safeFileName(meta.fileName, fileId))
                if (!file.isFile) null else LandedFile(fileId, meta.fileName, file)
            }
        }
    }

    private fun complete(session: BookDropSession) {
        sessions.remove(session.id)
    }

    /** Called by the router after a file reached its destination. */
    fun reportImport(name: String, size: Long, outcome: BookDropRecord.Outcome) {
        insertHistory(BookDropRecord(name = name, size = size, outcome = outcome))
    }

    companion object {
        val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Extensions accepted — the base set the receiver has always taken,
         * plus every format the normalize-to-EPUB pipeline handles. iOS's
         * LocalSendReceiver builds the same union from
         * SpeechLogic.DocumentBook.supportedExtensions.
         */
        val acceptedExtensions: Set<String> = setOf(
            "epub", "pdf", "m4b", "m4a", "mp4", "mp3", "jex",
            // Opus/Ogg audiobook containers — refusing them read as
            // "BookDrop skipped my book".
            "opus", "ogg", "oga",
        ) + com.speechnotes.app.logic.DocumentBook.supportedExtensions

        fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

        /** Strips directory components and NUL, so a sender can't escape the session dir. */
        fun safeFileName(name: String, fallback: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
                .replace("\u0000", "")
                .trim()
            return when {
                base.isEmpty() || base == "." || base == ".." -> "file-$fallback"
                else -> base
            }
        }
    }



}

/**
 * One in-flight transfer. File-private (not nested) so the outer class can
 * hold and inspect it without "outer receiver" restrictions.
 */
private class BookDropSession(
    val id: String,
    val senderHost: String?,
    val files: Map<String, LocalSendFileMeta>,
    val dir: java.io.File,
    val createdAt: Long,
) {
    val receivedIds = LinkedHashSet<String>()
    val tokens: Map<String, String> = files.keys.associateWith { java.util.UUID.randomUUID().toString() }
}
