package com.speechnotes.app.data

import com.speechnotes.app.logic.AppFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The exports directory contract: what the user exported is USER content,
 * and the Storage maintenance sweep must not eat it.
 */
class ExportsStoreTest {

    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sn-exports-test").toFile()
        AppFiles.init(root)
    }

    @Test
    fun `clear temporary files keeps the user's exports`() {
        val exports = AppFiles.exportsDir.apply { mkdirs() }
        val wav = java.io.File(exports, "my-note.wav").apply { writeBytes(ByteArray(64)) }
        val pdf = java.io.File(exports, "my-note.pdf").apply { writeBytes(ByteArray(64)) }
        val md = java.io.File(exports, "my-note.md").apply { writeText("# hi") }
        val txt = java.io.File(exports, "my-note.txt").apply { writeText("hi") }
        val jex = java.io.File(exports, "speechnotes-2026-11-07.jex").apply { writeBytes(ByteArray(64)) }

        val freed = ExportsStore.clearTemporaryFiles()

        assertEquals(0L, freed)
        for (file in listOf(wav, pdf, md, txt, jex)) {
            assertTrue("${file.name} must survive the sweep", file.exists())
        }
    }

    @Test
    fun `clear temporary files drops partial downloads and temp writes`() {
        val exports = AppFiles.exportsDir.apply { mkdirs() }
        val part = java.io.File(exports, "model.onnx.part").apply { writeBytes(ByteArray(128)) }
        val tmp = java.io.File(exports, "notes.json.tmp").apply { writeText("half") }
        val kept = java.io.File(exports, "recording.wav").apply { writeBytes(ByteArray(32)) }

        val freed = ExportsStore.clearTemporaryFiles()

        assertEquals(128L + 4L, freed)
        assertFalse(part.exists())
        assertFalse(tmp.exists())
        assertTrue(kept.exists())
    }

    @Test
    fun `the store lists non-wav exports separately`() {
        val exports = AppFiles.exportsDir.apply { mkdirs() }
        java.io.File(exports, "recording.wav").writeBytes(ByteArray(16))
        java.io.File(exports, "speechnotes-2026-11-07.jex").writeBytes(ByteArray(16))
        java.io.File(exports, "notes.md").writeText("x")

        val store = ExportsStore()
        store.refresh()

        assertEquals(listOf("recording"), store.exports.value.map { it.name })
        assertEquals(
            listOf("notes.md", "speechnotes-2026-11-07.jex"),
            store.files.value.map { it.name }.sorted(),
        )
    }
}
