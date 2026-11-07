package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Byte-progress on the receive side: the server's throttled reporter, the
 * connection-died marker, and BookDropCore's fileId lookup — exercised over
 * real sockets so the plumbing (not just the math) is what's tested.
 */
class LocalSendProgressTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class ProgressSink {
        val reports = ArrayList<Pair<Long, Long>>()
        fun fire(received: Long, expected: Long) {
            reports.add(received to expected)
        }
    }

    @Test
    fun `reporter throttles to one percent steps and always reports the final byte`() {
        val sink = ProgressSink()
        val reporter = BodyProgressReporter(expected = 10_000_000, start = 0)
        reporter.fire = sink::fire
        val step = maxOf(10_000_000 / 100, 1L shl 18)

        var sent = 0L
        while (sent < 10_000_000) {
            sent += step / 3  // sub-step writes must not each fire
            reporter.report(sent)
        }
        reporter.report(10_000_000)  // the final byte always reports
        val received = sink.reports.map { it.first }
        // No INTERMEDIATE report within one step of its predecessor (the
        // final byte always reports and may sit closer than a step).
        for (i in 0 until received.size - 2) {
            assertTrue(
                "intermediate reports must be at least a step apart",
                received[i + 1] - received[i] >= step,
            )
        }
        assertEquals(10_000_000L, received.last())
        assertTrue(sink.reports.all { it.second == 10_000_000L })
    }

    @Test
    fun `connectionDied reports negative one`() {
        val sink = ProgressSink()
        val reporter = BodyProgressReporter(expected = 4096, start = 0)
        reporter.fire = sink::fire
        reporter.connectionDied()
        assertEquals(listOf(-1L to 4096L), sink.reports)
    }

    @Test
    fun `upload over real socket reports final byte with expected size`() {
        val sink = ProgressSink()
        val server = LocalSendHTTPServer()
        server.bodyProgress = { path, _, received, expected ->
            if (path == "/api/localsend/v2/upload") sink.fire(received, expected)
        }
        server.handler = { LocalSendResponse.status(200) }
        val port = server.start()
        try {
            assertTrue("server must bind a test port", port > 0)
            val body = ByteArray(512 * 1024) { (it % 251).toByte() }
            val connection = URL("http://127.0.0.1:$port/api/localsend/v2/upload?sessionId=s&fileId=f&token=t")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            assertEquals(200, connection.responseCode)
            val received = sink.reports.map { it.first }
            assertTrue("at least the final byte must report", received.isNotEmpty())
            assertEquals(body.size.toLong(), received.last())
            assertTrue("expected must ride every report", sink.reports.all { it.second == body.size.toLong() })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `peer hanging up mid-body fires the connection-died marker`() {
        val sink = ProgressSink()
        val server = LocalSendHTTPServer()
        server.bodyProgress = { _, _, received, _ -> sink.fire(received, 0) }
        server.handler = { LocalSendResponse.status(200) }
        val port = server.start()
        try {
            // Announce more than we send, then close: the short-body branch.
            val socket = java.net.Socket("127.0.0.1", port)
            val head = "POST /api/localsend/v2/upload HTTP/1.1\r\n" +
                "Content-Length: ${2 * 1024 * 1024}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray())
                write(ByteArray(64 * 1024))
                flush()
                close()
            }
            socket.close()
            // The server thread needs its moment to reach the short-body branch.
            val deadline = System.currentTimeMillis() + 5_000
            while (sink.reports.none { it.first == -1L } && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            assertTrue("the -1 marker must fire", sink.reports.any { it.first == -1L })
        } finally {
            server.stop()
        }
    }

    // MARK: - fileNameFor

    private fun core(): BookDropCore = BookDropCore(
        device = LocalSendDevice(
            alias = "Test Phone", version = "2.2", deviceType = "mobile",
            fingerprint = "fp-1", port = 53317, protocolField = "http", download = false,
        ),
        sessionsRoot = tmp.newFolder(),
    )

    private fun prepare(core: BookDropCore, vararg files: Pair<String, String>): LocalSendPrepareResponse {
        val filesJson = files.joinToString(",") { (id, name) ->
            "\"$id\":{\"id\":\"$id\",\"fileName\":\"$name\",\"size\":5,\"sha256\":null}"
        }
        val body = """{"info":{"alias":"PC","version":"2.2","fingerprint":"p","port":1,"protocol":"http"},"pin":null,"files":{$filesJson}}"""
        val response = core.prepareUpload(body, senderHost = "192.168.1.9")
        assertEquals(200, response.status)
        return BookDropCore.json.decodeFromString(LocalSendPrepareResponse.serializer(), String(response.body))
    }

    @Test
    fun `fileNameFor resolves an in-flight file and ignores strangers`() {
        val core = core()
        assertNull(core.fileNameFor("nope"))
        val prepared = prepare(core, "a" to "Book.epub", "b" to "Notes.jex")
        assertEquals("Book.epub", core.fileNameFor("a"))
        assertEquals("Notes.jex", core.fileNameFor("b"))
        assertNull(core.fileNameFor("c"))
        assertNull(core.fileNameFor(null))
        // Landed or cancelled sessions drop out of the lookup.
        core.upload(prepared.sessionId, "a", prepared.files["a"], "192.168.1.9", BookDropCore.UploadBody.Memory("hello".toByteArray()))
        core.cancel(prepared.sessionId)
        assertNull(core.fileNameFor("b"))
    }

    @Test
    fun `accepted extensions are exactly the formats the router can land`() {
        // iOS v1.8.1 routing parity: the shelf formats, plus the three
        // families Android now lands the way iOS does — .jex into the
        // notes, .wav into Exports, .md/.txt as a NOTE.
        val expected = setOf(
            "epub", "pdf", "m4b", "m4a", "mp4", "mp3", "jex",
            "docx", "odt", "pptx", "odp",
            "wav",
            "md", "markdown", "txt", "text",
        )
        assertEquals(expected, BookDropCore.acceptedExtensions)
        // The old set advertised formats the import pipeline cannot parse.
        for (unparseable in setOf("mobi", "azw3", "fb2", "rtf", "html", "opus", "ogg", "oga")) {
            assertTrue(
                "$unparseable must not be accepted",
                !BookDropCore.acceptedExtensions.contains(unparseable),
            )
        }
    }
}
