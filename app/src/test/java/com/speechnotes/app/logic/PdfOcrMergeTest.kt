package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests for the note-import PDF OCR merge (iOS PdfTextExtractor port). */
class PdfOcrMergeTest {

    // MARK: - pageNeedsOcr

    @Test
    fun pageNeedsOcr_forcedAlwaysOcrs() {
        assertTrue(PdfOcrMerge.pageNeedsOcr("A full page of real prose text.", forceOCR = true))
        assertTrue(PdfOcrMerge.pageNeedsOcr(null, forceOCR = true))
    }

    @Test
    fun pageNeedsOcr_absentOrThinLayerOcrs() {
        assertTrue(PdfOcrMerge.pageNeedsOcr(null, forceOCR = false))
        assertTrue(PdfOcrMerge.pageNeedsOcr("", forceOCR = false))
        assertTrue(PdfOcrMerge.pageNeedsOcr("   \n  ", forceOCR = false))
        // The CamScanner watermark shape: a date/QR footer — a handful of chars.
        assertTrue(PdfOcrMerge.pageNeedsOcr("2026-10-23 14:02", forceOCR = false))
    }

    @Test
    fun pageNeedsOcr_realProseNeverReOcrs() {
        // The never-duplicate rule: a page with real prose keeps its native
        // text; OCR on top of it would double every sentence.
        val prose = "The quick brown fox jumps over the lazy dog. ".repeat(3)
        assertFalse(PdfOcrMerge.pageNeedsOcr(prose, forceOCR = false))
        assertFalse(PdfOcrMerge.pageNeedsOcr(prose, forceOCR = false))
    }

    // MARK: - merge

    @Test
    fun merge_joinsPagesWithBlankLines_andCountsOcr() {
        val merged = PdfOcrMerge.merge(
            listOf(
                PdfOcrMerge.Page("First page native text."),
                PdfOcrMerge.Page("Second page, recognized by OCR.", wasOcr = true),
                PdfOcrMerge.Page(null), // dropped (nothing survived)
                PdfOcrMerge.Page("Third page native."),
            ),
            maxChars = PdfOcrMerge.MAX_NOTE_TEXT_CHARS,
            truncatedNote = "[truncated]",
        )
        assertNotNull(merged)
        assertEquals(1, merged!!.ocrPages)
        assertFalse(merged.truncated)
        assertEquals(
            "First page native text.\n\nSecond page, recognized by OCR.\n\nThird page native.",
            merged.text,
        )
    }

    @Test
    fun merge_truncatesAtTheCharacterCap_withMarker() {
        val page = "x".repeat(60)
        val merged = PdfOcrMerge.merge(
            listOf(PdfOcrMerge.Page(page), PdfOcrMerge.Page(page)),
            maxChars = 100,
            truncatedNote = "[Text truncated — import large PDFs on the Books tab instead.]",
        )
        assertNotNull(merged)
        assertTrue(merged!!.truncated)
        assertTrue(merged.text.endsWith("[Text truncated — import large PDFs on the Books tab instead.]"))
        // The overflowing page is included, THEN the marker, then stop.
        assertEquals(186, merged.text.length)
    }

    @Test
    fun merge_nothingSurvived_reportsNull() {
        assertNull(
            PdfOcrMerge.merge(
                listOf(PdfOcrMerge.Page(null), PdfOcrMerge.Page("  ")),
                maxChars = 100,
                truncatedNote = "[truncated]",
            )
        )
    }

    @Test
    fun merge_blankPagesAreNotCountedAsOcr() {
        val merged = PdfOcrMerge.merge(
            listOf(PdfOcrMerge.Page("", wasOcr = true), PdfOcrMerge.Page("real text")),
            maxChars = 100,
            truncatedNote = "[truncated]",
        )
        assertEquals(0, merged!!.ocrPages)
    }

    // MARK: - Caps

    @Test
    fun caps_matchTheIosNoteImporter() {
        assertEquals(150, PdfOcrMerge.MAX_NOTE_PAGES)
        assertEquals(1_000_000, PdfOcrMerge.MAX_NOTE_TEXT_CHARS)
        assertEquals(25, PdfOcrMerge.MAX_NOTE_OCR_PAGES)
    }

    @Test
    fun remainingOcrPages_neverGoesNegative() {
        assertEquals(25, PdfOcrMerge.remainingOcrPages(0))
        assertEquals(0, PdfOcrMerge.remainingOcrPages(25))
        assertEquals(0, PdfOcrMerge.remainingOcrPages(40))
    }
}
