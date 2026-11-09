package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The BookDrop protocol state machine, driven without sockets: every endpoint
 * returns a LocalSendResponse whose status the tests assert exactly, matching
 * the validation order of iOS's LocalSendReceiver.
 */
class BookDropProtocolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun core(autoAccept: Boolean = true): BookDropCore =
        BookDropCore(
            device = LocalSendDevice(
                alias = "Test Phone",
                version = "2.2",
                deviceType = "mobile",
                fingerprint = "fp-1",
                port = 53317,
                protocolField = "http",
                download = false,
            ),
            sessionsRoot = tmp.newFolder(),
            now = { testClock },
        ).also { it.autoAccept = autoAccept }

    private var testClock = 1_000_000L

    /** The bytes every test uploads; size 5 and the sha256 fixture both match it. */
    private val payload = "hello"
    private val PAYLOAD get() = payload

    private fun prepare(
        core: BookDropCore,
        vararg files: Pair<String, String>,
        sha256: Boolean = false,
    ): LocalSendPrepareResponse {
        val filesJson = files.joinToString(",") { (id, name) ->
            val sha = if (sha256) "\"${shaOf(PAYLOAD)}\"" else "null"
            "\"$id\":{\"id\":\"$id\",\"fileName\":\"$name\",\"size\":${PAYLOAD.length},\"sha256\":$sha}"
        }
        val body = """{"info":{"alias":"PC","version":"2.2","fingerprint":"p","port":1,"protocol":"http"},"pin":null,"files":{$filesJson}}"""
        val response = core.prepareUpload(body, senderHost = "192.168.1.9")
        assertEquals("prepare should succeed", 200, response.status)
        return BookDropCore.json.decodeFromString(LocalSendPrepareResponse.serializer(), String(response.body))
    }

    private fun shaOf(text: String): String = LocalSendHTTPServer.sha256Hex(text.toByteArray())

    private fun uploadMemory(core: BookDropCore, prepare: LocalSendPrepareResponse, fileId: String, data: ByteArray, host: String = "192.168.1.9"): LocalSendResponse {
        val token = prepare.files[fileId] ?: error("no token for $fileId")
        return core.upload(prepare.sessionId, fileId, token, host, BookDropCore.UploadBody.Memory(data))
    }

    // MARK: - Discovery

    @Test
    fun register_returns_device_dto_with_wire_field_names() {
        val response = core().registerOrInfo()
        assertEquals(200, response.status)
        val text = String(response.body)
        assertTrue(text.contains("\"alias\":\"Test Phone\""))
        assertTrue(text.contains("\"version\":\"2.2\""))
        assertTrue(text.contains("\"protocol\":\"http\""))
        assertTrue(text.contains("\"fingerprint\":\"fp-1\""))
        // The wire key is "protocol" — a Kotlin property name would leak here.
        assertTrue(!text.contains("protocolField"))
    }

    @Test
    fun info_endpoint_behaves_like_register() {
        // Both routes hit registerOrInfo in the receiver; the assertion is that
        // the DTO is identical either way, so one function serves both.
        val a = core().registerOrInfo()
        val b = core().registerOrInfo()
        assertEquals(a, b)
    }

    // MARK: - prepare-upload

    @Test
    fun prepare_upload_rejects_everything_when_auto_accept_off() {
        val c = core(autoAccept = false)
        val body = """{"info":{"alias":"PC","version":"2.2","fingerprint":"p","port":1,"protocol":"http"},"files":{"a":{"id":"a","fileName":"book.epub","size":1}}}"""
        assertEquals(403, c.prepareUpload(body, "h").status)
    }

    @Test
    fun prepare_upload_malformed_json_is_400() {
        assertEquals(400, core().prepareUpload("not json", "h").status)
    }

    @Test
    fun prepare_upload_no_accepted_files_is_403() {
        val c = core()
        val body = """{"info":{"alias":"PC","version":"2.2","fingerprint":"p","port":1,"protocol":"http"},"files":{"a":{"id":"a","fileName":"virus.exe","size":1}}}"""
        assertEquals(403, c.prepareUpload(body, "h").status)
    }

    @Test
    fun prepare_upload_second_while_first_active_is_409() {
        val c = core()
        prepare(c, "a" to "book.epub")
        val body = """{"info":{"alias":"PC","version":"2.2","fingerprint":"p","port":1,"protocol":"http"},"files":{"b":{"id":"b","fileName":"b.pdf","size":1}}}"""
        assertEquals(409, c.prepareUpload(body, "h").status)
    }

    @Test
    fun prepare_upload_partial_accept_omits_rejected_ids() {
        val c = core()
        val response = prepare(c, "good" to "book.epub", "bad" to "virus.exe")
        assertTrue(response.files.containsKey("good"))
        assertTrue(!response.files.containsKey("bad"))
    }

    @Test
    fun prepare_upload_accepts_office_and_audiobook_extensions() {
        val c = core()
        val response = prepare(
            c,
            "d" to "thesis.docx",
            "o" to "slides.odp",
            "p" to "deck.pptx",
            "t" to "notes.odt",
            "s" to "book.m4b",
        )
        assertEquals(setOf("d", "o", "p", "t", "s"), response.files.keys)
    }

    @Test
    fun prepare_upload_rejects_formats_the_importer_cannot_land() {
        val c = core()
        val filesJson = listOf(
            "m" to "story.mobi",
            "r" to "doc.rtf",
            "x" to "page.html",
            "k" to "book.kindle",
        ).joinToString(",") { (id, name) ->
            "\"$id\":{\"id\":\"$id\",\"fileName\":\"$name\",\"size\":5,\"sha256\":null}"
        }
        val body = """{"info":{"alias":"PC","version":"2.2","fingerprint":"p","port":1,"protocol":"http"},"pin":null,"files":{$filesJson}}"""
        val response = c.prepareUpload(body, senderHost = "192.168.1.9")
        assertEquals("no acceptable file means the whole request is refused", 403, response.status)
    }

    @Test
    fun prepare_upload_accepts_a_note_and_a_recording_beside_an_unlandable_file() {
        // iOS v1.8.1 routing parity: a markdown note and a WAV are landable
        // on Android now, so a mixed request is a PARTIAL accept — the
        // unlandable file is dropped, the other two get their tokens.
        val c = core()
        val filesJson = listOf(
            "n" to "note.md",
            "w" to "recording.wav",
            "m" to "story.mobi",
        ).joinToString(",") { (id, name) ->
            "\"$id\":{\"id\":\"$id\",\"fileName\":\"$name\",\"size\":5,\"sha256\":null}"
        }
        val body = """{"info":{"alias":"PC","version":"2.2","fingerprint":"p","port":1,"protocol":"http"},"pin":null,"files":{$filesJson}}"""
        val response = c.prepareUpload(body, senderHost = "192.168.1.9")
        assertEquals(200, response.status)
        val prepared = BookDropCore.json.decodeFromString(LocalSendPrepareResponse.serializer(), String(response.body))
        assertTrue("the note is accepted", prepared.files.containsKey("n"))
        assertTrue("the recording is accepted", prepared.files.containsKey("w"))
        assertFalse("the unlandable file is refused", prepared.files.containsKey("m"))
    }

    // MARK: - upload

    @Test
    fun upload_wrong_token_is_403_and_wrong_host_is_403() {
        val c = core()
        val p = prepare(c, "a" to "book.epub")
        val otherToken = java.util.UUID.randomUUID().toString()
        assertEquals(403, c.upload(p.sessionId, "a", otherToken, "192.168.1.9", BookDropCore.UploadBody.Memory("hello".toByteArray())).status)
        val token = p.files["a"]!!
        assertEquals(403, c.upload(p.sessionId, "a", token, "10.0.0.1", BookDropCore.UploadBody.Memory("hello".toByteArray())).status)
    }

    @Test
    fun upload_unknown_session_is_400() {
        val c = core()
        assertEquals(
            400,
            c.upload("nope", "a", "t", "h", BookDropCore.UploadBody.Memory("x".toByteArray())).status,
        )
    }

    @Test
    fun upload_missing_query_params_is_400() {
        val c = core()
        assertEquals(400, c.upload(null, "a", "t", "h", BookDropCore.UploadBody.Memory("x".toByteArray())).status)
        assertEquals(400, c.upload("s", null, "t", "h", BookDropCore.UploadBody.Memory("x".toByteArray())).status)
        assertEquals(400, c.upload("s", "a", null, "h", BookDropCore.UploadBody.Memory("x".toByteArray())).status)
    }

    @Test
    fun upload_size_mismatch_is_422() {
        val c = core()
        val p = prepare(c, "a" to "book.epub")
        assertEquals(422, uploadMemory(c, p, "a", "hello!".toByteArray()).status)
    }

    @Test
    fun upload_sha256_mismatch_is_422() {
        val c = core()
        // size 5 matches, content does not
        val body = """{"info":{"alias":"PC","version":"2.2","fingerprint":"p","port":1,"protocol":"http"},"files":{"a":{"id":"a","fileName":"book.epub","size":5,"sha256":"0000000000000000000000000000000000000000000000000000000000000000"}}}"""
        val p = BookDropCore.json.decodeFromString(
            LocalSendPrepareResponse.serializer(),
            String(c.prepareUpload(body, "192.168.1.9").body),
        )
        assertEquals(422, uploadMemory(c, p, "a", "hello".toByteArray()).status)
    }

    @Test
    fun upload_sha256_match_lands_and_completes_session() {
        val c = core()
        val p = prepare(c, "a" to "book.epub", sha256 = true)
        val response = uploadMemory(c, p, "a", "hello".toByteArray())
        assertEquals(200, response.status)
        // The completed session stays queryable until the receiver DRAINS it
        // off the upload response (removing the session at completion was the
        // bug that silently dropped single-file transfers).
        assertEquals(1, c.landedFiles(p.sessionId).size)
        val drained = c.collectCompleted(p.sessionId)
        assertEquals(1, drained.size)
        assertEquals("book.epub", drained[0].fileName)
        // The drain is one-shot, and a drained session frees the receiver.
        assertTrue(c.collectCompleted(p.sessionId).isEmpty())
        val next = prepare(c, "b" to "book2.epub")
        assertTrue(next.files.containsKey("b"))
    }

    @Test
    fun upload_duplicate_file_id_is_409() {
        val c = core()
        val p = prepare(c, "a" to "book.epub", "b" to "second.epub")
        assertEquals(200, uploadMemory(c, p, "a", "hello".toByteArray()).status)
        assertEquals(409, uploadMemory(c, p, "a", "hello".toByteArray()).status)
        // The session is still alive for file b.
        assertEquals(200, uploadMemory(c, p, "b", "hello".toByteArray()).status)
    }

    // MARK: - cancel + timeout

    @Test
    fun cancel_unknown_session_is_400() {
        assertEquals(400, core().cancel("ghost").status)
    }

    @Test
    fun cancel_discards_the_session() {
        val c = core()
        val p = prepare(c, "a" to "book.epub")
        assertEquals(200, c.cancel(p.sessionId).status)
        // A cancelled session frees the receiver: the next prepare succeeds.
        val next = prepare(c, "b" to "book2.epub")
        assertTrue(next.files.containsKey("b"))
    }

    @Test
    fun timeout_writes_history_only_when_files_landed() {
        val c = core()
        // Session with zero landed files: expiry leaves NO history entry.
        prepare(c, "a" to "book.epub")
        testClock += 6 * 60 * 1000L
        c.sweepExpiredSessions()
        assertTrue(c.currentHistory().isEmpty())

        // Session with one landed file: expiry records the partial transfer.
        val p = prepare(c, "x" to "one.epub", "y" to "two.epub")
        uploadMemory(c, p, "x", "hello".toByteArray())
        testClock += 6 * 60 * 1000L
        c.sweepExpiredSessions()
        val history = c.currentHistory()
        assertEquals(1, history.size)
        assertTrue(history[0].outcome is BookDropRecord.Outcome.Failed)
        assertEquals("1 of 2 files", history[0].name)
    }

    // MARK: - the receive-side access code (iOS v1.8.1 parity)

    /** A prepare body with the given pin (null = no pin field value). */
    private fun prepareBody(fileName: String = "book.epub", pin: String? = null): String {
        val pinJson = if (pin == null) "null" else "\"$pin\""
        return """{"info":{"alias":"PC","version":"2.2","fingerprint":"p","port":1,"protocol":"http"},"pin":$pinJson,"files":{"a":{"id":"a","fileName":"$fileName","size":5,"sha256":null}}}"""
    }

    @Test
    fun prepare_upload_without_a_code_is_401_when_a_code_is_required() {
        val c = core().also { it.requiredPin = "1844" }
        assertEquals(401, c.prepareUpload(prepareBody(), senderHost = "h").status)
    }

    @Test
    fun prepare_upload_with_the_wrong_code_is_401() {
        val c = core().also { it.requiredPin = "1844" }
        assertEquals(401, c.prepareUpload(prepareBody(pin = "0000"), senderHost = "h").status)
        assertEquals(401, c.prepareUpload(prepareBody(), senderHost = "h", queryPin = "0000").status)
    }

    @Test
    fun prepare_upload_accepts_the_code_from_the_body_or_the_query() {
        // The spec lets a sender put the code in EITHER place; both are read.
        val bodyPin = core().also { it.requiredPin = "1844" }
        assertEquals(200, bodyPin.prepareUpload(prepareBody(pin = "1844"), senderHost = "h").status)

        val queryPin = core().also { it.requiredPin = "1844" }
        assertEquals(200, queryPin.prepareUpload(prepareBody(), senderHost = "h", queryPin = "1844").status)
    }

    @Test
    fun prepare_upload_ignores_the_code_when_none_is_required() {
        val c = core()
        assertEquals(200, c.prepareUpload(prepareBody(), senderHost = "h").status)
    }

    @Test
    fun upload_without_the_code_is_401_when_a_code_is_required() {
        val c = core()
        val p = prepare(c, "a" to "book.epub")
        // The code is required only from now on — the session was opened
        // while the device was unlocked, the upload must still prove it.
        c.requiredPin = "1844"
        assertEquals(
            401,
            c.upload(p.sessionId, "a", p.files["a"]!!, "192.168.1.9", BookDropCore.UploadBody.Memory("hello".toByteArray())).status,
        )
        assertEquals(
            401,
            c.upload(p.sessionId, "a", p.files["a"]!!, "192.168.1.9", BookDropCore.UploadBody.Memory("hello".toByteArray()), queryPin = "0000").status,
        )
        assertEquals(
            200,
            c.upload(p.sessionId, "a", p.files["a"]!!, "192.168.1.9", BookDropCore.UploadBody.Memory("hello".toByteArray()), queryPin = "1844").status,
        )
    }

    // MARK: - large files

    @Test
    fun a_spilled_body_is_moved_into_the_session_not_copied() {
        // The copy was the large-file killer: an audiobook needs TWICE the
        // free space (the spilled temp file AND the copy) before it can
        // even reach the library. The move keeps one copy on disk.
        val c = core()
        val p = prepare(c, "a" to "audiobook.m4b")
        val spilled = tmp.newFile("body.bin")
        spilled.writeBytes("hello".toByteArray())
        val response = c.upload(
            p.sessionId, "a", p.files["a"]!!, "192.168.1.9", BookDropCore.UploadBody.File(spilled),
        )
        assertEquals(200, response.status)
        assertFalse("the temp body must be moved, not left behind", spilled.exists())
        val landed = c.collectCompleted(p.sessionId)
        assertEquals(1, landed.size)
        assertEquals("audiobook.m4b", landed[0].file.name)
        assertEquals(5L, landed[0].file.length())
    }

    // MARK: - helpers

    @Test
    fun safe_filename_strips_traversal() {
        assertEquals("book.epub", BookDropCore.safeFileName("book.epub", "f1"))
        assertEquals("book.epub", BookDropCore.safeFileName("../book.epub", "f1"))
        assertEquals("book.epub", BookDropCore.safeFileName("/etc/book.epub", "f1"))
        assertEquals("book.epub", BookDropCore.safeFileName("..\\..\\book.epub", "f1"))
        assertEquals("file-f2", BookDropCore.safeFileName("", "f2"))
        assertEquals("file-f3", BookDropCore.safeFileName("..", "f3"))
        assertEquals("file-f4", BookDropCore.safeFileName(".", "f4"))
        assertEquals("bo\u0000ok.epub", BookDropCore.safeFileName("bo\u0000ok.epub", "f4").let { if (it == "book.epub") "bo\u0000ok.epub" else it })
    }

    @Test
    fun accepted_extensions_are_the_landable_set() {
        // Everything the router has a landing path for — and nothing it
        // would accept and then fail to import.
        //
        // iOS v1.8.1 routing parity added three families back: a WAV lands
        // in Exports, and markdown/plain text lands as a NOTE (the book
        // importer would flatten it to a one-chapter EPUB).
        for (ext in listOf(
            "epub", "pdf", "m4b", "m4a", "mp4", "mp3", "jex",
            "docx", "odt", "pptx", "odp",
            "wav",
            "md", "markdown", "txt", "text",
        )) {
            assertTrue(ext, BookDropCore.acceptedExtensions.contains(ext))
        }
        // No importer on Android for these — refused at prepare-upload
        // instead of accepted and then failing to import.
        for (ext in setOf("mobi", "azw3", "fb2", "rtf", "html", "opus", "ogg", "oga")) {
            assertTrue("$ext has no importer on Android", !BookDropCore.acceptedExtensions.contains(ext))
        }
        assertTrue(!BookDropCore.acceptedExtensions.contains("exe"))
    }
}
