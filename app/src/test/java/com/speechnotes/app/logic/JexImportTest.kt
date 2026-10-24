package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip + parser tests for the Joplin JEX path. */
class JexImportTest {

    private fun noteEntry(id: String, body: String, parentId: String?, type: Int = 1): ByteArray {
        val fields = buildString {
            append("id: ").append(id).append('\n')
            if (parentId != null) append("parent_id: ").append(parentId).append('\n')
            append("created_time: 2026-09-01T10:00:00.000Z\n")
            append("updated_time: 2026-09-02T10:00:00.000Z\n")
            append("type_: ").append(type)
        }
        return (body + "\n\n" + fields).toByteArray(Charsets.UTF_8)
    }

    private fun tar(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun append(name: String, data: ByteArray) {
            val header = ByteArray(512)
            System.arraycopy(name.toByteArray(), 0, header, 0, name.length)
            val size = java.lang.Long.toOctalString(data.size.toLong())
            val padded = "0".repeat(11 - size.length) + size
            System.arraycopy(padded.toByteArray(), 0, header, 124, padded.length)
            header[156] = '0'.code.toByte()
            System.arraycopy("ustar".toByteArray(), 0, header, 257, 5)
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

    @Test
    fun `parses the app's own export with type_ last`() {
        val nbId = "a".repeat(32)
        val noteId = "b".repeat(32)
        val archive = JexImport.parse(
            tar(
                listOf(
                    "$nbId.md" to noteEntry(nbId, "My notebook", null, type = 2),
                    "$noteId.md" to noteEntry(noteId, "Hello world body", nbId),
                )
            )
        )
        assertEquals(1, archive.notebooks.size)
        assertEquals("My notebook", archive.notebooks[0].title)
        assertEquals(1, archive.notes.size)
        assertEquals("Hello world body", archive.notes[0].body)
        assertEquals(nbId, archive.notes[0].notebookId)
    }

    @Test
    fun `parses a Joplin-style export with type_ first`() {
        val noteId = "c".repeat(32)
        val body = "body text"
        val entry = ("type_: 1\nid: $noteId\nparent_id: \n" +
            "created_time: 2026-09-01T10:00:00.000Z\nupdated_time: 2026-09-01T10:00:00.000Z").let {
            body + "\n\n" + it
        }.toByteArray()
        val archive = JexImport.parse(tar(listOf("$noteId.md" to entry)))
        assertEquals(1, archive.notes.size)
        assertEquals("body text", archive.notes[0].body)
    }

    @Test
    fun `resource metadata with no body and resource binaries import`() {
        val resourceId = "d".repeat(32)
        val meta = "id: $resourceId\ntitle: $resourceId.png\nmime: image/png\ntype_: 4".toByteArray()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val archive = JexImport.parse(
            tar(
                listOf(
                    "$resourceId.md" to meta,
                    "resources/$resourceId.png" to png,
                )
            )
        )
        assertEquals(1, archive.resources.size)
        assertEquals("png", archive.resources[0].fileExtension)
        assertEquals("image/png", archive.resources[0].mime)
        assertTrue(archive.resources[0].data.contentEquals(png))
    }

    @Test
    fun `resource links in note bodies are collected`() {
        val noteId = "e".repeat(32)
        val resourceId = "f".repeat(32)
        val body = "look ![img](../resources/$resourceId.png) here"
        val entry = (body + "\n\ntype_: 1\nid: $noteId").toByteArray()
        val archive = JexImport.parse(tar(listOf("$noteId.md" to entry)))
        assertEquals(resourceId, archive.notes[0].resources.keys.single())
    }

    @Test
    fun `joplin ids reconstitute to uuids`() {
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", JexImport.uuid("0123456789abcdef0123456789abcdef"))
        assertNull(JexImport.uuid("not-a-uuid"))
        assertNull(JexImport.uuid("zz23456789abcdef0123456789abcdef"))
    }

    @Test
    fun `not a tar throws`() {
        try {
            JexImport.parse("this is not a tar archive at all......".toByteArray())
            throw AssertionError("expected ImportError")
        } catch (e: JexImport.ImportError) {
            assertNotNull(e.message)
        }
    }
}
