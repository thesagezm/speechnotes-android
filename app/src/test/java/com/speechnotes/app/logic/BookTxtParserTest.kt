package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The plain-text book pins, ported from sn-doc plainbook.rs's tests. */
class BookTxtParserTest {

    private fun utf16le(text: String): ByteArray =
        text.toByteArray(Charsets.UTF_16LE).let { bom + it }

    private val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    @Test
    fun utf8IsTheFirstAnswer() {
        assertEquals("plain é text", BookTxtParser.decode("plain é text".toByteArray(Charsets.UTF_8)))
        // A BOM-less UTF-16 file decoded as UTF-8 would show NULs.
        assertEquals("Hello", BookTxtParser.decode(bom + "Hello".toByteArray(Charsets.UTF_16LE)))
        assertEquals(
            "Hello",
            BookTxtParser.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Hello".toByteArray(Charsets.UTF_16BE))
        )
    }

    @Test
    fun bomlessUtf16DecodesByItsNullPattern() {
        assertEquals("Hi there", BookTxtParser.decode("Hi there".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Hi there", BookTxtParser.decode("Hi there".toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun latin1IsTheFloor() {
        // é = 0xE9 in latin-1; as UTF-8 this byte run is invalid, so the
        // floor reads it.
        assertEquals("c" + Char(0xE9) + "st", BookTxtParser.decode(byteArrayOf(0x63, 0xE9.toByte(), 0x73, 0x74)))
    }

    @Test
    fun emptyTextIsRefused() {
        try {
            BookTxtParser.parse("   \n\t\n".toByteArray())
            fail("expected refusal")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("no readable text"))
        }
    }

    @Test
    fun gutenbergRunningHeadingsOpenChapters() {
        val text = "Chapter One\n\nProse one. More prose.\n\nChapter Two\n\nProse two.\n"
        val doc = BookTxtParser.parse(text.toByteArray())
        assertEquals(2, doc.chapters.size)
        assertEquals("Chapter One", doc.chapters[0].title)
        assertEquals("Chapter Two", doc.chapters[1].title)
        assertTrue(doc.chapters[0].html.contains("<p>Prose one. More prose.</p>"))
    }

    @Test
    fun markdownHeadingsOpenChaptersAndLoseTheirMarks() {
        val text = "# Part One\n\nText.\n\n## Deeper\n\nMore.\n"
        val doc = BookTxtParser.parse(text.toByteArray())
        assertEquals(2, doc.chapters.size)
        assertEquals("Part One", doc.chapters[0].title)
        assertEquals("Deeper", doc.chapters[1].title)
    }

    @Test
    fun numberedShortLinesOpenChapters() {
        val text = "I. The Letter\n\nBody one.\n\n12. The Garden\n\nBody two.\n"
        val doc = BookTxtParser.parse(text.toByteArray())
        assertEquals(2, doc.chapters.size)
        assertEquals("I. The Letter", doc.chapters[0].title)
        assertEquals("12. The Garden", doc.chapters[1].title)
    }

    @Test
    fun aHeadinglessFileChunksByParagraphCount() {
        val paragraph = "Some prose sentence that stands alone."
        val text = List(400) { paragraph }.joinToString("\n\n")
        val doc = BookTxtParser.parse(text.toByteArray())
        assertTrue("got ${doc.chapters.size} chapters", doc.chapters.size >= 3)
        assertTrue(doc.chapters.all { it.html.isNotEmpty() })
    }

    @Test
    fun aHardWrappedParagraphStaysOneBlock() {
        val text = "the lamp post stood\nquiet under the grey\nrain all evening.\n"
        val blocks = stripMarkdownLines(text)
        assertEquals(listOf("the lamp post stood quiet under the grey rain all evening."), blocks)
    }

    /** The text_blocks view is internal to the parser; the same result is
     *  observable through a chapter's first paragraph. */
    private fun stripMarkdownLines(text: String): List<String> {
        val doc = BookTxtParser.parse(text.toByteArray())
        // One paragraph, escaped inside <p>…</p>.
        return doc.chapters.map { chapter ->
            chapter.html.removePrefix("<p>").removeSuffix("</p>").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        }
    }

    @Test
    fun sentenceEndedLinesSplitIntoParagraphs() {
        val text = "Alpha prose.\nBeta prose.\n"
        val html = BookTxtParser.parse(text.toByteArray()).chapters.single().html
        assertEquals(2, Regex("<p>").findAll(html).count())
    }

    @Test
    fun aRunningHeadingIsNeverGluedToItsProse() {
        // The regression the sentence-end rule exists for: "Chapter One" +
        // "Prose one." must not become one block, or the chapter title
        // swallows the first sentence.
        val text = "Chapter One\nProse one. More prose.\n"
        val doc = BookTxtParser.parse(text.toByteArray())
        assertEquals("Chapter One", doc.chapters[0].title)
        assertTrue(doc.chapters[0].html.contains("Prose one."))
    }

    @Test
    fun theTitleCarriesOntoTheContinuation() {
        val paragraph = "A paragraph of prose that fills space."
        val text = "Chapter One\n\n" + List(300) { paragraph }.joinToString("\n\n") + "\n"
        val doc = BookTxtParser.parse(text.toByteArray())
        assertTrue("got ${doc.chapters.size} chapters", doc.chapters.size >= 2)
        for (chapter in doc.chapters) {
            assertEquals("Chapter One", chapter.title)
        }
    }

    @Test
    fun wordLikeNearMissesAreNotHeadings() {
        assertEquals(null, BookTxtParser.headingLevel("chapterhouse rules apply here"))
        // Prose that merely begins with a numeral or a pronoun stays prose —
        // the no-separator guard.
        assertEquals(null, BookTxtParser.headingLevel("I read the book tonight by the fire"))
        assertEquals(null, BookTxtParser.headingLevel("12 to 15 people were there"))
        assertEquals(null, BookTxtParser.headingLevel("12345 numbers do not make a heading"))
        // The real forms: vocabulary heads, and separated or title-case
        // numbered heads.
        assertEquals(2, BookTxtParser.headingLevel("Chapter"))
        assertEquals(2, BookTxtParser.headingLevel("Chapter One"))
        assertEquals(2, BookTxtParser.headingLevel("I. The Letter"))
        assertEquals(2, BookTxtParser.headingLevel("12. The Garden"))
        assertEquals(2, BookTxtParser.headingLevel("VII — The Letter"))
        assertEquals(2, BookTxtParser.headingLevel("I Was Here"))
        // A bare head with nothing after it heads nothing — the reference
        // regex demands whitespace and a title after the numeral.
        assertEquals(null, BookTxtParser.headingLevel("VIII"))
        assertEquals(null, BookTxtParser.headingLevel("I."))
    }

    @Test
    fun markdownInlineSyntaxIsStripped() {
        assertEquals("bold and em", BookTxtParser.stripMarkdownInline("**bold** and *em*"))
        assertEquals("see the docs now", BookTxtParser.stripMarkdownInline("see [the docs](http://x.y/z) now"))
        assertEquals(" rest", BookTxtParser.stripMarkdownInline("![pic](img.png) rest"))
        assertEquals("visit today", BookTxtParser.stripMarkdownInline("visit https://example.com/x today"))
        assertEquals("code here", BookTxtParser.stripMarkdownInline("`code` here"))
        // snake_case keeps its underscore.
        assertEquals("a snake_case word", BookTxtParser.stripMarkdownInline("a snake_case word"))
    }

    @Test
    fun chapterHtmlIsEscapedParagraphs() {
        val doc = BookTxtParser.parse("Chapter One\n\nA <b> tag & an \"entity\".\n".toByteArray())
        assertTrue(doc.chapters[0].html.contains("A &lt;b&gt; tag &amp; an &quot;entity&quot;."))
        assertTrue(!doc.chapters[0].html.contains("<b>"))
    }
}
