package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression cover for the central-directory field widths that made every
 * EPUB read as zero entries (the "could not import" report).
 */
class ZipReaderRegressionTest {

    private fun buildZip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun readsEntriesWithNarrowFields() {
        val bytes = buildZip(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to "<container/>",
                "OEBPS/ch1.xhtml" to "<html><body>Chapter one</body></html>",
            ),
        )
        val reader = ZipReader.parse(bytes)
        assertNotNull("archive must parse", reader)
        assertEquals(3, reader!!.names.size)
        assertTrue(reader.text("OEBPS/ch1.xhtml")!!.contains("Chapter one"))
    }

    @Test
    fun manyEntriesDoNotDrift() {
        // Enough entries that a 2-byte field-width mistake accumulates.
        val many = linkedMapOf<String, String>()
        for (i in 0 until 400) many["OEBPS/img$i.bin"] = "payload-$i"
        many["META-INF/container.xml"] = "<container/>"
        val reader = ZipReader.parse(buildZip(many))
        assertNotNull(reader)
        assertEquals(401, reader!!.names.size)
        assertEquals("payload-399", reader.text("OEBPS/img399.bin"))
    }
}
