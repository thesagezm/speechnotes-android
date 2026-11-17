package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The HTML/HTMLZ pins, ported from sn-doc htmlbook.rs's tests. The htmlz
 *  fixtures build a STORED-entry zip the same way the Rust test helper
 *  does. */
class BookHtmlParserTest {

    /** A zip of STORED entries (no compression), read back by ZipReader. */
    private fun storedZipFiles(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.crc = CRC32().apply { update(data) }.value
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun aGutenbergStylePageChaptersOnH2() {
        val html = "<html><head><title>An Essay</title></head><body>" +
            "<h1>An Essay</h1><p>Front matter prose.</p>" +
            "<h2>Chapter One</h2><p>Prose one.</p>" +
            "<h2>Chapter Two</h2><p>Prose two.</p></body></html>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        assertEquals("An Essay", doc.title)
        assertEquals(3, doc.chapters.size)
        assertEquals("An Essay", doc.chapters[0].title)
        assertEquals("Chapter One", doc.chapters[1].title)
        assertTrue(doc.chapters[1].html.contains("<p>Prose one.</p>"))
        assertEquals("Chapter One", doc.toc[1].label)
        assertEquals(1, doc.toc[1].chapterIndex)
    }

    @Test
    fun voidElementsAndUnclosedParagraphsDoNotTruncate() {
        val html = "<body><p>one<br>line<p>two<img src=\"x.png\"><p>three"
        val doc = BookHtmlParser.parse(html.toByteArray())
        val text = doc.chapters.joinToString("") { it.html }
        assertTrue(text.contains("one"))
        assertTrue(text.contains("two"))
        assertTrue(text.contains("three"))
        // The external image loses its tag — nothing to serve it from.
        assertTrue(!text.contains("<img"))
    }

    @Test
    fun attributesWithGtInsideQuotesSurvive() {
        val html = "<p title=\"a > b\">after the alt bug</p>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        assertTrue(doc.chapters[0].html.contains("after the alt bug"))
    }

    @Test
    fun scriptAndStyleBodiesAreSkippedWhole() {
        val html = "<head><style>p { color: red; }</style><script>if (a<b) x();</script>" +
            "<title>Kept</title></head><body><p>prose</p></body>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        assertEquals("Kept", doc.title)
        val text = doc.chapters[0].html
        assertTrue(text.contains("prose"))
        assertTrue(!text.contains("color"))
        assertTrue(!text.contains("x()"))
    }

    @Test
    fun commentsAndDoctypeAreSkipped() {
        val html = "<!DOCTYPE html><!-- a note <p>hidden</p> --><p>shown</p>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        val text = doc.chapters.joinToString("") { it.html }
        assertTrue(text.contains("shown"))
        assertTrue(!text.contains("hidden"))
    }

    @Test
    fun entitiesDecodeAndUnknownNamesSurvive() {
        val html = "<p>Caf&eacute; &mdash; na&iuml;ve & &weirdname; &#x41;&#66;</p>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        val text = doc.chapters[0].html
        assertTrue(text.contains("Café"))
        assertTrue(text.contains("—"))
        assertTrue(text.contains("naïve"))
        // The chapter XHTML is real markup: a decoded '&' is written back
        // escaped, and an unknown name survives as its own token.
        assertTrue(text.contains("&"))
        assertTrue(text.contains("weirdname;"))
        assertTrue(text.contains("AB"))
        // The speech pass says the same.
        val spoken = XhtmlText.plainText(doc.chapters[0].html)
        assertTrue(spoken.contains("&weirdname;"))
    }

    @Test
    fun anEntityWithoutItsTerminatorStaysText() {
        val html = "<p>AT&T and a bare &amp word</p>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        val text = doc.chapters[0].html
        // The bare '&amp' (no ';') is literal text, not a name the reader
        // may swallow.
        assertTrue(text.contains("AT"))
        assertTrue(text.contains("T and a bare"))
        assertTrue(text.contains("amp word"))
    }

    @Test
    fun tablesBecomeTableMarkupWithHeaders() {
        val html = "<table><tr><th>Name</th><th>Qty</th></tr><tr><td>Apples</td><td>12</td></tr></table>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        val text = doc.chapters[0].html
        assertTrue(text.contains("<table>"))
        assertTrue(text.contains("<th>Name</th>"))
        assertTrue(text.contains("<td>Apples</td>"))
        assertTrue(text.contains("<td>12</td>"))
    }

    @Test
    fun metaAuthorIsFound() {
        val html = "<head><meta name=\"author\" content=\"Jane A. Doe\"></head><body><p>x</p></body>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        assertEquals("Jane A. Doe", doc.author)
    }

    @Test
    fun deepHeadingsStayInsideTheirChapter() {
        val html = "<h1>Part</h1><p>a.</p><h3>Section</h3><p>b.</p>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        assertEquals(1, doc.chapters.size)
        assertTrue(doc.chapters[0].html.contains("<p>Section</p>"))
    }

    @Test
    fun emptyInputIsRefused() {
        try {
            BookHtmlParser.parse("   ".toByteArray())
            fail("expected refusal")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("no readable text"))
        }
        try {
            BookHtmlParser.parse("<html><body></body></html>".toByteArray())
            fail("expected refusal")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("no readable text"))
        }
    }

    /** The Gutenberg front-matter shape: the header sits in a `<pre>` block
     *  whose newlines are the layout. Without the pre handling, tidy()
     *  collapsed every line into ONE paragraph. */
    @Test
    fun aPreBlockReadsOneLinePerParagraph() {
        val html = "<html><body><pre>Title:      The Great Gatsby\n" +
            "Author:     F. Scott Fitzgerald\n\n" +
            "* A Project Gutenberg of Australia eBook *</pre>" +
            "<p>prose after the pre</p></body></html>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        val itemsText = doc.chapters.flatMap { c ->
            c.html.split("</p>").map { s -> s.removePrefix("<p>").trim() }.filter { it.isNotEmpty() }
        }
        assertEquals(
            listOf(
                "Title: The Great Gatsby",
                "Author: F. Scott Fitzgerald",
                "* A Project Gutenberg of Australia eBook *",
                "prose after the pre",
            ),
            itemsText,
        )
    }

    @Test
    fun aPreBlockSurvivesAStrayBoundary() {
        val html = "<pre>line one\nline two</pre><p>after</p>"
        val doc = BookHtmlParser.parse(html.toByteArray())
        val text = doc.chapters.joinToString("") { it.html }
        assertTrue(text.contains("<p>line one</p>"))
        assertTrue(text.contains("<p>line two</p>"))
        assertTrue(text.contains("<p>after</p>"))
    }

    @Test
    fun utf8ThenCp1252Decoding() {
        assertEquals("é—", BookHtmlParser.decode("é—".toByteArray(Charsets.UTF_8)))
        // 0x92 = ’ in cp1252; as UTF-8 a lone 0x92 is invalid.
        assertEquals("’", BookHtmlParser.decode(byteArrayOf(0x92.toByte())))
        assertEquals("“”", BookHtmlParser.decode(byteArrayOf(0x93.toByte(), 0x94.toByte())))
    }

    @Test
    fun aHtmlzCarriesIndexOpfCoverAndImages() {
        val zipBytes = storedZipFiles(
            listOf(
                "index.html" to ("<html><head><title>T</title></head><body>" +
                    "<p>before <img src=\"images/pic.png\" alt=\"a pic\"> after</p></body></html>").toByteArray(),
                "metadata.opf" to ("<?xml version=\"1.0\"?>" +
                    "<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><metadata>" +
                    "<dc:title>Htmlz Title</dc:title><dc:creator>Htmlz Author</dc:creator>" +
                    "</metadata></package>").toByteArray(),
                "images/pic.png" to "\\x89PNG fake".let { byteArrayOf(0x89.toByte()) + "PNG fake".toByteArray() },
                "cover.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + " fake jpeg".toByteArray(),
            )
        )
        val doc = BookHtmlParser.parseHtmlz(zipBytes)
        assertEquals("Htmlz Title", doc.title)
        assertEquals("Htmlz Author", doc.author)
        val cover = doc.cover
        assertNotNull(cover)
        assertEquals("img/cover", cover!!.name)
        assertEquals("image/jpeg", cover.mime)
        val image = doc.image("pic.png")
        assertNotNull(image)
        assertEquals("image/png", image!!.mime)
        assertTrue(doc.chapters[0].html.contains("<img src=\"img/pic.png\" alt=\"a pic\">"))
    }

    @Test
    fun aHtmlzWithoutAnyHtmlIsRefused() {
        val zip = storedZipFiles(listOf("notes.txt" to "x".toByteArray()))
        try {
            BookHtmlParser.parseHtmlz(zip)
            fail("expected refusal")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("index.html"))
        }
    }

    @Test
    fun aHtmlzMirrorWithoutAnIndexStillReads() {
        // Mirrors vary: some keep the page under its own name. The reader
        // takes the first HTML entry when there is no index.html.
        val zip = storedZipFiles(listOf("chapter01.html" to "<p>x</p>".toByteArray()))
        val doc = BookHtmlParser.parseHtmlz(zip)
        assertEquals(1, doc.chapters.size)
    }
}
