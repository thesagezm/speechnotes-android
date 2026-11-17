package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parsers against REAL calibre output — the fixtures elsewhere are
 * byte-wise hand-builds, but the books users actually import come from
 * calibre's converter. These files are the smallest calibre corpus that
 * exercises each parser's real branch: the hybrid MOBI6+KF8 pick, the KF8
 * flow splitter, the EPUB3 nav with ANCHORED hrefs (the fragment the TOC
 * fix must strip), htmlz's STORED zip.
 */
class CalibreRealFileTest {

    private fun read(name: String): ByteArray =
        javaClass.getResourceAsStream("/calibre/$name")!!.readBytes()

    @Test
    fun aRealCalibreHybridMobiParses() {
        val doc = BookMobiParser.parse(read("the-road.mobi"))
        // VERIFIED IDENTICAL to the Rust probe on this exact file: calibre's
        // publisher TOC (the CNCX offsets are the <a filepos=…> values) points
        // each anchor at the chapter's BODY — one block past the styled
        // heading paragraph — so every publisher-cut chapter carries the NEXT
        // chapter's heading text at its end, and the styled heading of chapter
        // one rides the title-page flow. That shape is the reference's own
        // behavior on calibre files, not an Android defect; the pins hold it
        // so a port drift cannot sneak in.
        assertEquals(3, doc.chapters.size)
        assertEquals(listOf("Chapter One — The Gate", "Chapter Two — The River", "Chapter Three — The Letter"), doc.chapters.mapNotNull { it.title })
        assertTrue("text decoded", doc.chapters[0].html.contains("farmer watched"))
        assertTrue("the reference's filepos shape: the next heading leaks as text", doc.chapters[0].html.contains("Chapter Two — The River"))
        assertEquals(listOf("Chapter One — The Gate", "Chapter Two — The River", "Chapter Three — The Letter"), doc.toc.map { it.label })
    }

    @Test
    fun aRealCalibreAzw3Parses() {
        val doc = BookMobiParser.parse(read("the-road.azw3"))
        // VERIFIED IDENTICAL to the Rust probe on this exact file: two
        // publisher-cut chapters (the third entry's cut lands inside the
        // trailing inline-TOC flow), titles from the publisher's words.
        assertEquals(2, doc.chapters.size)
        assertEquals(listOf("Chapter One — The Gate", "Chapter Two — The River"), doc.chapters.mapNotNull { it.title })
        assertTrue("text decoded", doc.chapters[0].html.contains("river ran backwards"))
    }

    @Test
    fun aRealCalibreEpubTocResolvesThroughTheSpineGate() {
        val zip = ZipReader.parse(read("the-road.epub"))!!
        val info = EpubParser.parse(zip)
        assertTrue("spine exists", info.spine.isNotEmpty())
        assertTrue("toc exists", info.toc.isNotEmpty())
        // Calibre's EPUB3 nav anchors deep into split files — the import
        // path matches the spine with the fragment stripped (the TOC fix).
        for (entry in info.toc) {
            val stem = entry.href.substringBefore('#')
            assertTrue(
                "toc href '$entry.href' has a spine entry for '$stem'",
                info.spine.contains(stem),
            )
        }
    }

    @Test
    fun aRealGutenbergTxtChapterizes() {
        val doc = BookTxtParser.parse(read("gutenberg-style.txt"))
        // The reference keeps the Gutenberg preamble as chapter 0 (verified
        // byte-identical against the Rust probe on this file) and chapterizes
        // the three "Chapter I/II/III." running headings after it.
        assertNull(doc.chapters[0].title)
        assertEquals("Chapter I. The Gate", doc.chapters[1].title)
        assertEquals(4, doc.chapters.size)
        assertTrue(doc.chapters[3].html.contains("Dear stranger"))
    }

    @Test
    fun aRealRtfParses() {
        val doc = BookRtfParser.parse(read("notes.rtf"))
        // No \info group in this file, so doc.title stays null (the Rust's
        // behavior — verified) — but the emphasis-heading heuristic made the
        // bold larger-font opening paragraph the chapter's title.
        assertNull(doc.title)
        assertEquals("Road Diary", doc.chapters[0].title)
        assertTrue(doc.chapters[0].html.contains("postman"))
    }

    @Test
    fun aRealFb2Parses() {
        val doc = BookFb2Parser.parse(read("story.fb2"))
        assertEquals("The Signal Lamp", doc.title)
        assertEquals("Ada", doc.author?.substringBefore(' ') ?: doc.author?.let { it.split(" ").first() })
        assertEquals(2, doc.chapters.size)
        assertEquals("One: The Tower", doc.chapters[0].title)
    }

    @Test
    fun aRealHtmlParses() {
        val doc = BookHtmlParser.parse(read("plain.html"))
        assertEquals("Field Notes", doc.title)
        assertTrue(doc.chapters.isNotEmpty())
    }

    @Test
    fun aRealHtmlzParses() {
        val doc = BookHtmlParser.parseHtmlz(read("field-notes.htmlz"))
        assertEquals("Field Notes", doc.title)
        assertEquals("W. Marsh", doc.author)
    }

    @Test
    fun theServedDocAnswersTheWholeCorpus() {
        for (pair in listOf(
            "the-road.mobi" to "mobi",
            "the-road.azw3" to "azw3",
            "gutenberg-style.txt" to "txt",
            "notes.rtf" to "rtf",
            "story.fb2" to "fb2",
            "plain.html" to "html",
            "field-notes.htmlz" to "htmlz",
        )) {
            val served = ServedBookDoc.parse(pair.second, read(pair.first))
            assertNotNull(served.read("chapter-0"))
            assertEquals("text/html", served.mimeFor("chapter-0"))
        }
    }
}
