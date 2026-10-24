package com.speechnotes.app.logic

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * One parsed HTTP/1.1 request. The body arrives either in memory (small JSON
 * endpoints) or spilled to a temp file (uploads — book files reach hundreds of
 * MB and must never fully materialize).
 */
data class LocalSendRequest(
    val method: String,
    val path: String,
    /** Query items, percent-decoded. */
    val query: Map<String, String>,
    val headers: Map<String, String>,
    /** Sender IP, IPv6-mapped prefixes stripped ("::ffff:a.b.c.d" → a.b.c.d). */
    val remoteHost: String?,
    val bodySource: Body,
) {
    sealed class Body {
        /** Fully materialized bytes — JSON endpoints only. */
        data class Memory(val data: ByteArray) : Body() {
            override fun equals(other: Any?): Boolean =
                other is Memory && other.data.contentEquals(data)
            override fun hashCode(): Int = data.contentHashCode()
        }
        /** A temp file plus its byte count — uploads. */
        data class File(val file: java.io.File, val byteCount: Long) : Body()
    }

    fun queryValue(name: String): String? = query[name]

    val contentLength: Long
        get() = headers.entries.firstOrNull { it.key.equals("content-length", true) }
            ?.value?.toLongOrNull() ?: 0L
}

data class LocalSendResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "application/json",
) {
    companion object {
        fun json(value: String): LocalSendResponse = LocalSendResponse(200, value.toByteArray())
        fun status(code: Int): LocalSendResponse = LocalSendResponse(code)
        fun badRequest(): LocalSendResponse = status(400)
        fun forbidden(): LocalSendResponse = status(403)
        fun notFound(): LocalSendResponse = status(404)
        fun conflict(): LocalSendResponse = status(409)
        fun unsupported(): LocalSendResponse = status(422)
    }

    override fun equals(other: Any?): Boolean =
        other is LocalSendResponse && other.status == status && other.body.contentEquals(body) &&
            other.contentType == contentType
    override fun hashCode(): Int = (31 * status + body.contentHashCode()) * 31 + contentType.hashCode()
}

/**
 * A minimal HTTP/1.1 server on plain sockets — enough for the LocalSend
 * endpoints: request-line + headers + Content-Length body, one request per
 * connection (Connection: close; LocalSend senders tolerate this), no chunked
 * encoding, no TLS.
 *
 * Threading: every connection gets a thread from a small pool; the handler
 * runs on that thread and is expected to keep its critical section short.
 *
 * Ported from LocalSendHTTPServer.swift (speechnotes-ios). Readest's
 * zombie-listener lesson applies on Android too: a suspended process can lose
 * its listening socket, so [verifyAlive] probes the port with a loopback TCP
 * connect rather than trusting the flag.
 */
class LocalSendHTTPServer {

    /** Called on a connection thread for every complete request. */
    var handler: ((LocalSendRequest) -> LocalSendResponse)? = null

    @Volatile private var listener: ServerSocket? = null

    @Volatile var port: Int = 0
        private set

    /** Body bytes spill to disk past this threshold (uploads). */
    private val spillThreshold = 8L shl 20
    /** Request-head cap — a hostile peer cannot OOM the parser with an endless header block. */
    private val maxHeadBytes = 64 shl 10
    /** Session bodies live here; deleted when a response is written. */
    private val bodies = ConcurrentHashMap<String, File>()

    private val pool = Executors.newCachedThreadPool { runnable ->
        thread(start = false, isDaemon = true, name = "bookdrop-http") { runnable.run() }
    }

    /**
     * Starts on the first free port among 53317–53327 (the LocalSend default
     * first). Returns the port, or -1 when every candidate is taken.
     */
    fun start(): Int {
        stop()
        for (candidate in PORT_CANDIDATES) {
            val socket = try {
                ServerSocket()
            } catch (e: Exception) {
                null
            } ?: break
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), candidate))
            } catch (e: Exception) {
                runCatching { socket.close() }
                continue
            }
            listener = socket
            port = candidate
            thread(isDaemon = true, name = "bookdrop-accept") { acceptLoop(socket) }
            return candidate
        }
        return -1
    }

    fun stop() {
        runCatching { listener?.close() }
        listener = null
        port = 0
        // Any in-flight bodies belong to aborted transfers.
        for (file in bodies.values) runCatching { file.delete() }
        bodies.clear()
    }

    /** True when a loopback connection to the listener actually succeeds. */
    fun verifyAlive(timeoutMs: Int = 500): Boolean {
        val current = port
        if (current == 0 || listener == null) return false
        return try {
            Socket().use { probe ->
                probe.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), current), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val connection = try {
                server.accept()
            } catch (e: Exception) {
                return
            }
            pool.execute { runCatching { handleConnection(connection) } }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            // A slow peer must not hold a connection thread forever.
            it.soTimeout = 60_000
            val buffer = ByteArrayOutputStream()
            val head = ByteArray(64 * 1024)
            var headData: ByteArray? = null
            // The bytes that arrived after the request head (usually none) —
            // per-CONNECTION state. This used to be an instance field, which
            // let two simultaneous uploads hand each other's first body chunk
            // to accumulateBody and silently corrupt both.
            var bodyStart: ByteArray = ByteArray(0)
            while (headData == null) {
                val read = it.getInputStream().read(head)
                if (read < 0) return
                buffer.write(head, 0, read)
                if (buffer.size() > maxHeadBytes) {
                    writeResponse(it, LocalSendResponse.status(431))
                    return
                }
                val bytes = buffer.toByteArray()
                val split = indexOf(bytes, "\r\n\r\n".toByteArray())
                if (split >= 0) {
                    headData = bytes.copyOfRange(0, split)
                    // Whatever arrived with the head belongs to the body.
                    bodyStart = bytes.copyOfRange(split + 4, bytes.size)
                }
            }
            val parsed = parseHead(String(headData, Charsets.UTF_8))
            if (parsed == null) {
                writeResponse(it, LocalSendResponse.badRequest())
                return
            }
            val (method, target, headers) = parsed
            val body = accumulateBody(it, headers, bodyStart)
            val request = LocalSendRequest(
                method = method,
                path = pathOf(target),
                query = queryOf(target),
                headers = headers,
                remoteHost = remoteHostOf(it),
                bodySource = body,
            )
            val response = handler?.invoke(request) ?: LocalSendResponse.notFound()
            writeResponse(it, response)
            // The body is consumed: a spilled upload temp file is ours to
            // delete once the response is away.
            if (body is LocalSendRequest.Body.File) discardBody(body.file)
        }
    }

    private fun pathOf(target: String): String {
        val q = target.indexOf('?')
        return if (q >= 0) target.substring(0, q) else target
    }

    /** Percent-decodes query pairs; a repeated key keeps its last value. */
    private fun queryOf(target: String): Map<String, String> {
        val q = target.indexOf('?')
        if (q < 0 || q + 1 >= target.length) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in target.substring(q + 1).split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            out[decode(key)] = decode(value)
        }
        return out
    }

    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    /** Accumulates the body, spilling to a temp file past the threshold. */
    private fun accumulateBody(socket: Socket, headers: Map<String, String>, initial: ByteArray): LocalSendRequest.Body {
        val remaining = headers.entries
            .firstOrNull { it.key.equals("content-length", true) }
            ?.value?.toLongOrNull() ?: 0L
        if (remaining <= 0) return LocalSendRequest.Body.Memory(ByteArray(0))

        val memory = ByteArrayOutputStream()
        var spilled: File? = null
        var trailing: BufferedOutputStream? = null
        var total = 0L

        fun append(chunk: ByteArray) {
            total += chunk.size
            val file = spilled
            if (file != null) {
                trailing!!.write(chunk)
                return
            }
            memory.write(chunk)
            if (memory.size() > spillThreshold) {
                val spill = createBodyFile()
                spilled = spill
                val stream = BufferedOutputStream(FileOutputStream(spill))
                trailing = stream
                stream.write(memory.toByteArray())
                memory.reset()
                bodies[spill.name] = spill
            }
        }

        if (initial.isNotEmpty()) append(initial)
        if (total < remaining) {
            val still = remaining - total
            val stream = socket.getInputStream()
            val buffer = ByteArray(64 * 1024)
            var left = still
            while (left > 0) {
                val want = if (left < buffer.size) left.toInt() else buffer.size
                val read = stream.read(buffer, 0, want)
                if (read < 0) break
                append(buffer.copyOf(read))
                left -= read
            }
        }

        // A peer that sends FEWER bytes than Content-Length promised (or hung
        // up mid-body) is a protocol error, not a short body: the transfer
        // would land truncated and only fail sha256 far downstream. Drop the
        // partial bytes — an empty body makes every upload endpoint's size
        // check (422) fail naturally, and prepare/cancel don't read bodies.
        if (total != remaining) {
            spilled?.let { discardBody(it) }
            return LocalSendRequest.Body.Memory(ByteArray(0))
        }

        val file = spilled
        return if (file != null) {
            trailing!!.flush()
            trailing!!.close()
            LocalSendRequest.Body.File(file, total)
        } else {
            LocalSendRequest.Body.Memory(memory.toByteArray())
        }
    }

    /** Spill dir override — the receiver points this at the app's BookDrop dir. */
    @Volatile var bodySpillDir: File? = null

    private fun createBodyFile(): File {
        val dir = bodySpillDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", "bookdrop")
        dir.mkdirs()
        return File.createTempFile("body-", ".bin", dir)
    }

    private fun parseHead(head: String): Triple<String, String, Map<String, String>>? {
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return Triple(parts[0].uppercase(), parts[1], headers)
    }

    private fun remoteHostOf(socket: Socket): String? {
        val address: InetAddress = socket.inetAddress ?: return null
        if (address is Inet4Address) return address.hostAddress
        return address.hostAddress?.removePrefix("::ffff:")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun writeResponse(socket: Socket, response: LocalSendResponse) {
        val reason = reason(response.status)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(response.body)
            flush()
        }
    }

    /** Deletes a spilled body after its transfer is complete or rejected. */
    fun discardBody(file: File) {
        bodies.remove(file.name)
        runCatching { file.delete() }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        else -> "Status $status"
    }

    companion object {
        val PORT_CANDIDATES = (53317..53327).toList()

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
