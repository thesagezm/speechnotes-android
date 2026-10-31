package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round-trip + parser tests for the Joplin JEX path (iOS v1.8.1 semantics). */
class JexImportTest {

    private fun hex(prefix: String): String = (prefix + "0".repeat(32)).take(32)

    // MARK: - Fixtures

    /** Minimal ustar writer, independent of JexExport so a bug there can't mask a bug here. */
    private fun tar(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun append(name: String, data: ByteArray) {
            val header = ByteArray(512)
            System.arraycopy(name.toByteArray(), 0, header, 0, name.length)
            val size = java.lang.Long.toOctalString(data.size.toLong())
            val padded = "0".repeat(11 - size.length) + size
            System.arraycopy(padded.toByteArray(), 0, header, 124, padded.length)
            header[156] = '0'.code.toByte()
            System.arraycopy("ustar\u0000".toByteArray(), 0, header, 257, 6)
            var checksum = 0
            for (b in header) checksum += (b.toInt() and 0xFF)
            val chk = java.lang.Long.toOctalString(checksum.toLong())
            val chkPadded = "0".repeat(7 - chk.length) + chk + " "
            System.arraycopy(chkPadded.toByteArray(), 0, header, 148, chkPadded.length)
            out.write(header)
            out.write(data)
            val pad = (512 - (data.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
        entries.forEach { (name, data) -> append(name, data) }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    /** A full JexExport.buildArchive fixture (port of iOS exportFixture). */
    private fun exportFixture(): ByteArray = JexExport.buildArchive(
        notes = listOf(
            JexExport.NotePayload(
                id = hex("bb22"),
                title = "First note",
                markdownBody = "# Hello\n\nFirst paragraph.\n\nSome body text.",
                notebookId = hex("aa11"),
                createdAt = 1_700_000_100L,
                updatedAt = 1_700_000_200L,
                images = listOf(
                    JexExport.ImagePayload(
                        id = hex("cc33"),
                        fileExtension = "png",
                        mimeType = "image/png",
                        data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3),
                    )
                ),
            ),
            JexExport.NotePayload(
                id = hex("dd44"),
                title = "Unfiled note",
                markdownBody = "No notebook here.",
                notebookId = null,
                createdAt = 1_700_000_300L,
                updatedAt = 1_700_000_400L,
            ),
        ),
        notebooks = listOf(
            JexExport.NotebookPayload(id = hex("aa11"), title = "Inbox", parentId = null, createdAt = 1_700_000_000L),
        ),
    )

    // MARK: - Round trip

    @Test
    fun `round trip notes notebooks and resources`() {
        val archive = JexImport.parse(exportFixture())
        assertEquals(1, archive.notebooks.size)
        assertEquals(2, archive.notes.size)
        assertEquals(1, archive.resources.size)

        val notebook = archive.notebooks[0]
        assertEquals(hex("aa11"), notebook.id)
        assertEquals("Inbox", notebook.title)

        val note = archive.notes.first { it.id == hex("bb22") }
        assertEquals("First note", note.title)
        assertEquals(hex("aa11"), note.notebookId)
        assertTrue(note.body.contains("Some body text."))
        assertEquals(1_700_000_100L, note.createdAt)
        assertEquals(1_700_000_200L, note.updatedAt)

        val unfiled = archive.notes.first { it.id == hex("dd44") }
        assertNull(unfiled.notebookId)

        val resource = archive.resources.first { it.id == hex("cc33") }
        assertEquals("png", resource.fileExtension)
        assertEquals(11, resource.data.size)
    }

    @Test
    fun `round trip uuid mapping`() {
        val original = "01234567-89ab-cdef-0123-456789abcdef"
        assertEquals(original, JexImport.uuid(JexExport.joplinId(original)))
        assertNull(JexImport.uuid("zz" + "9".repeat(30)))
    }

    @Test
    fun `export layout is joplin canonical`() {
        // No trailing newline after the props block — one and Joplin's
        // parser reads zero props and aborts the import.
        val bytes = exportFixture()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("type_: 1\n\n"))
        assertTrue(text.contains("user_created_time"))
        assertTrue(text.contains("file_extension: png"))
    }

    // MARK: - Hand-built archives

    @Test
    fun `parses a joplin written entry with title splice and resource links`() {
        val id = hex("ee55")
        val resourceId = hex("ff66")
        val entry = "The note title\n" +
            "\n" +
            "The note body.\n" +
            "\n" +
            "![cover](../resources/$resourceId.png)\n" +
            "\n" +
            "id: $id\n" +
            "parent_id: ${hex("aa11")}\n" +
            "created_time: 2026-09-08T16:00:43.123Z\n" +
            "updated_time: 2026-09-08T16:00:43.123Z\n" +
            "markup_language: 1\n" +
            "type_: 1"
        val archive = JexImport.parse(
            tar(
                listOf(
                    "$id.md" to entry.toByteArray(),
                    "resources/$resourceId.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2),
                )
            )
        )
        assertEquals(1, archive.notes.size)
        val note = archive.notes[0]
        assertEquals(id, note.id)
        assertEquals("The note title", note.title)
        assertEquals("The note body.\n\n![cover](../resources/$resourceId.png)", note.body)
        assertEquals("../resources/$resourceId.png", note.resources[resourceId])
        assertEquals(1, archive.resources.size)
        assertEquals("png", archive.resources[0].fileExtension)
    }

    @Test
    fun `parses legacy app entry with title prop and trailing newline`() {
        val id = hex("ee55")
        val entry = "The body, verbatim.\nSecond line.\n" +
            "\n" +
            "id: $id\n" +
            "title: The old title\n" +
            "created_time: 2026-09-08T16:00:43.123Z\n" +
            "updated_time: 2026-09-08T16:00:43.123Z\n" +
            "type_: 1\n\n"
        val archive = JexImport.parse(tar(listOf("$id.md" to entry.toByteArray())))
        assertEquals(1, archive.notes.size)
        assertEquals("The old title", archive.notes[0].title)
        assertEquals("The body, verbatim.\nSecond line.", archive.notes[0].body)
    }

    @Test
    fun `parses folder entry with title as first line`() {
        val id = hex("aa11")
        val entry = "Inbox\n\nid: $id\ncreated_time: 2026-09-08T16:00:43.000Z\ntype_: 2"
        val archive = JexImport.parse(tar(listOf("$id.md" to entry.toByteArray())))
        assertEquals(1, archive.notebooks.size)
        assertEquals(id, archive.notebooks[0].id)
        assertEquals("Inbox", archive.notebooks[0].title)
    }

    @Test
    fun `resource metadata supplies mime when file name has no extension`() {
        val id = hex("ff66")
        val meta = "id: $id\ntitle: $id.png\nmime: image/png\nfilename:\n" +
            "created_time: 2026-09-08T16:00:43.123Z\ntype_: 4"
        val archive = JexImport.parse(
            tar(
                listOf(
                    "$id.md" to meta.toByteArray(),
                    "resources/$id" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9),
                )
            )
        )
        assertEquals(1, archive.resources.size)
        assertEquals("png", archive.resources[0].fileExtension)
        assertEquals("image/png", archive.resources[0].mime)
    }

    @Test
    fun `joplin ids reconstitute to uuids`() {
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", JexImport.uuid("0123456789abcdef0123456789abcdef"))
        assertNull(JexImport.uuid("not-a-uuid"))
        assertNull(JexImport.uuid("zz23456789abcdef0123456789abcdef"))
    }

    // MARK: - speechnotes image links

    @Test
    fun `speechnotes note-image links map to their resource id prefix`() {
        val noteId = hex("ee55")
        val fullHash = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        assertEquals(64, fullHash.length)
        val body = "text ![img](speechnotes://note-image/$fullHash.png) more"
        val entry = "Title\n\n$body\n\nid: $noteId\ntype_: 1"
        val archive = JexImport.parse(tar(listOf("$noteId.md" to entry.toByteArray())))
        val resources = archive.notes[0].resources
        assertEquals(fullHash.take(32), resources.keys.single())
        assertEquals("speechnotes://note-image/$fullHash.png", resources.values.single())
    }

    @Test
    fun `legacy short-hash speechnotes links map by their full hash`() {
        val noteId = hex("ee55")
        val shortHash = "1a2b3c4d5e"
        val body = "![img](speechnotes://note-image/$shortHash.png)"
        val entry = "$body\n\nid: $noteId\ntitle: Legacy\ncreated_time: 2026-09-08T16:00:43.123Z\ntype_: 1"
        val archive = JexImport.parse(tar(listOf("$noteId.md" to entry.toByteArray())))
        val resources = archive.notes[0].resources
        assertEquals(shortHash, resources.keys.single())
    }

    // MARK: - Rejections + safety

    @Test
    fun `not a tar throws`() {
        try {
            JexImport.parse("this is not a tar archive at all......".toByteArray())
            throw AssertionError("expected ImportError")
        } catch (e: JexImport.ImportError) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `empty archive with valid framing throws`() {
        try {
            JexImport.parse(ByteArray(1024))
            throw AssertionError("expected ImportError")
        } catch (e: JexImport.ImportError) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `traversal resource entries are skipped`() {
        val noteId = hex("ee55")
        val good = hex("ff66")
        val meta = "id: $good\ntitle: $good.png\nmime: image/png\ntype_: 4"
        val archive = JexImport.parse(
            tar(
                listOf(
                    "$noteId.md" to "T\n\nbody\n\nid: $noteId\ntype_: 1".toByteArray(),
                    "$good.md" to meta.toByteArray(),
                    "resources/$good.png" to byteArrayOf(1, 2, 3),
                    // Escapes the resources/ namespace: must not be accepted.
                    "resources/../../../evil.png" to byteArrayOf(9, 9),
                    "resources/nested/dir.png" to byteArrayOf(9, 9),
                )
            )
        )
        assertEquals(1, archive.resources.size)
        assertEquals(good, archive.resources[0].id)
        assertEquals(3, archive.resources[0].data.size)
    }
}

/** SHA-256 hashing + extension allowlist behind the .jex image round-trip. */
class NoteImageStoreTest {

    private fun initRoot(): File {
        val root = createTempDirectory("note-images-test").toFile()
        AppFiles.init(root)
        return root
    }

    @Test
    fun `store hashes content with sha256`() {
        val root = initRoot()
        val bytes = "abc".toByteArray()
        val target = NoteImageStore.store(bytes, "png", "note-1")
        assertNotNull(target)
        // SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            NoteImageStore.parseLocalTarget(target!!)!!.first,
        )
        assertEquals(64, NoteImageStore.parseLocalTarget(target)!!.first.length)
        assertTrue(File(File(AppFiles.notesImagesDir, "note-1"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.png").exists())
        root.deleteRecursively()
    }

    @Test
    fun `store dedupes identical content`() {
        val root = initRoot()
        val first = NoteImageStore.store("same".toByteArray(), "png", "note-1")
        val second = NoteImageStore.store("same".toByteArray(), "png", "note-1")
        assertEquals(first, second)
        assertEquals(1, File(AppFiles.notesImagesDir, "note-1").listFiles()!!.size)
        root.deleteRecursively()
    }

    @Test
    fun `sanitiseExtension allowlist`() {
        assertEquals("png", NoteImageStore.sanitiseExtension("png"))
        assertEquals("png", NoteImageStore.sanitiseExtension("PNG"))
        assertEquals("jpg", NoteImageStore.sanitiseExtension("jpeg"))
        assertEquals("jpg", NoteImageStore.sanitiseExtension(".jpg"))
        assertEquals("png", NoteImageStore.sanitiseExtension("png/../../evil"))
        assertEquals("png", NoteImageStore.sanitiseExtension("png?query"))
        assertEquals("bin", NoteImageStore.sanitiseExtension("txt"))
        assertEquals("bin", NoteImageStore.sanitiseExtension(".."))
        assertNull(NoteImageStore.sanitiseExtension(""))
        assertNull(NoteImageStore.sanitiseExtension(null))
    }
}
