package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The RTF pins, ported from sn-doc rtf.rs's tests. */
class BookRtfParserTest {

    @Test
    fun paragraphsAndHeadingGroupsComeOut() {
        val rtf = "{\\rtf1\\ansi{\\b Title Line}\\par First paragraph.\\par Second.\\par}".toByteArray()
        val doc = BookRtfParser.parse(rtf)
        // One heading opens one chapter; everything until the next one
        // belongs to it.
        assertEquals(1, doc.chapters.size)
        assertEquals("Title Line", doc.chapters[0].title)
        assertTrue(doc.chapters[0].html.contains("First paragraph."))
        assertTrue(doc.chapters[0].html.contains("Second."))
        assertEquals("Title Line", doc.toc[0].label)
    }

    @Test
    fun hexEscapesAndTheDefaultCodepageDecode() {
        // \'e9 is é in cp1252 — the floor an RTF without \ansicpg uses.
        val doc = BookRtfParser.parse("{\\rtf1 Caf\\'e9.}".toByteArray())
        assertTrue(doc.chapters[0].html.contains("Café."))
    }

    @Test
    fun anAnsicpgNamesItsCodepage() {
        // "Привет" in windows-1251.
        val rtf = "{\\rtf1\\ansi\\ansicpg1251 ".toByteArray() +
            byteArrayOf(0xCF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte()) +
            ".}".toByteArray()
        val doc = BookRtfParser.parse(rtf)
        assertTrue(doc.chapters[0].html.contains("Привет"))
    }

    @Test
    fun unicodeEscapesDecodeAndSwallowTheirFallback() {
        // \u8212? — an em dash with the `?` fallback consumed; \uc1 tells
        // the reader to skip the one ANSI byte after each escape.
        val doc = BookRtfParser.parse("{\\rtf1\\uc1 A\\u8212?B}".toByteArray())
        val text = doc.chapters[0].html
        assertTrue(text.contains("A—B"))
        assertTrue(!text.contains("?"))
    }

    @Test
    fun metadataDestinationsAreSkippedAndInfoIsCaptured() {
        val rtf = "{\\rtf1{\\fonttbl{\\f0 Times;}}{\\colortbl;\\red0\\green0\\blue0;}" +
            "{\\info{\\title Info Title}{\\author Info Author}}\\par Body text.}"
        val doc = BookRtfParser.parse(rtf.toByteArray())
        assertEquals("Info Title", doc.title)
        assertEquals("Info Author", doc.author)
        val text = doc.chapters.joinToString("") { it.html }
        assertTrue(text.contains("Body text."))
        assertTrue(!text.contains("Times"))
        assertTrue(!text.contains("red0"))
        assertTrue(!text.contains("Info Title"))
    }

    @Test
    fun anUnknownStarDestinationIsSkipped() {
        // \*\shpinst is a drawing destination no reader needs; its body
        // must not be read aloud.
        val rtf = "{\\rtf1\\par Keep me.\\par{\\*\\shpinst secret drawing words}\\par After.}"
        val text = BookRtfParser.parse(rtf.toByteArray()).chapters.joinToString("") { it.html }
        assertTrue(text.contains("Keep me."))
        assertTrue(text.contains("After."))
        assertTrue(!text.contains("secret"))
    }

    @Test
    fun aNestedGroupCannotLeakItsParagraph() {
        // The classic bug: a \par inside a nested group starting a new
        // paragraph in the OUTER buffer, welding the document into one line.
        val rtf = "{\\rtf1\\par Outer one.{\\b nested\\par Inner}\\par Outer two.}"
        val text = BookRtfParser.parse(rtf.toByteArray()).chapters.joinToString("") { it.html }
        assertTrue(text.contains("Outer one."))
        assertTrue(text.contains("Inner"))
        assertTrue(text.contains("Outer two."))
    }

    @Test
    fun cellsJoinAndRowsBreak() {
        val rtf = "{\\rtf1\\trowd Apples\\cell 12\\cell\\row Pears\\cell 4\\cell\\row}"
        val text = BookRtfParser.parse(rtf.toByteArray()).chapters[0].html
        assertTrue(text.contains("Apples, 12"))
        assertTrue(text.contains("Pears, 4"))
    }

    @Test
    fun emphasisAndEntityWordsDecode() {
        val rtf = "{\\rtf1 an em\\emdash dash and a\\bullet dot.}"
        val text = BookRtfParser.parse(rtf.toByteArray()).chapters[0].html
        assertTrue(text.contains("em—dash"))
        assertTrue(text.contains("•dot"))
    }

    @Test
    fun notAnRtfIsRefusedAndEmptyIsRefused() {
        try {
            BookRtfParser.parse("{\\rtf ".toByteArray()) // too short to be one
            fail("expected refusal")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("not an RTF document"))
        }
        try {
            BookRtfParser.parse("hello world".toByteArray())
            fail("expected refusal")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("not an RTF document"))
        }
        // A valid RTF with no text at all.
        try {
            BookRtfParser.parse("{\\rtf1\\ansi{\\fonttbl{\\f0 Times;}}}".toByteArray())
            fail("expected refusal")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("no readable text"))
        }
    }

    @Test
    fun aHeadinglessDocumentChunksByParagraphCount() {
        val paragraph = "Some prose sentence."
        val rtf = StringBuilder("{\\rtf1 ")
        repeat(400) {
            rtf.append(paragraph)
            rtf.append("\\par ")
        }
        rtf.append("}")
        val doc = BookRtfParser.parse(rtf.toString().toByteArray())
        assertTrue("got ${doc.chapters.size} chapters", doc.chapters.size >= 3)
        assertTrue(doc.chapters.all { it.html.isNotEmpty() })
    }

    @Test
    fun tidyCollapsesWhitespaceRuns() {
        assertEquals("a b c", BookRtfParser.tidy("a \t\n b  c "))
    }
}
