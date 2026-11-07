package com.speechnotes.app.logic

/**
 * Pure decision + merge layer for the PDF OCR pipeline — the Kotlin port of
 * the iOS note-import extraction (PdfTextExtractor.builtin + PdfOCR
 .shouldOCR, v1.8.1 15208d8). The Android caller (the note importer in
 * SpeechnotesViewModel, the book chapter path) supplies per-page native
 * text and ML Kit OCR results; THIS layer owns the decisions so they are
 * assertable on the JVM:
 *
 *  - which pages need OCR (no text layer, a watermark-thin layer, or forced),
 *  - how the merged note text is assembled (pages joined with blank lines,
 *    OCR pages never duplicated with native text, blanks dropped),
 *  - the note importer's caps (a note is not a book: 150 pages / 1M chars /
 *    25 OCR'd pages, the iOS numbers verbatim).
 *
 * Pure Kotlin — no android imports (the logic/ contract).
 */
object PdfOcrMerge {

    /**
     * Pages whose native text layer carries at most this many characters
     * read as text-layer-less for OCR purposes: absent, or a scanner
     * watermark (the CamScanner shape — a date/QR footer the extractor
     * returns as a handful of characters). iOS detects the watermark by
     * page geometry; a length probe approximates it without PDF internals —
     * a real page of prose never lands under the threshold.
     */
    const val THIN_TEXT_CHARS: Int = 24

    /**
     * The note importer's caps (iOS ImportService verbatim): a 500 MB
     * textbook through the note path was a freeze/jetsam pattern — big
     * documents belong on the Books tab, which reads lazily by chapter.
     */
    const val MAX_NOTE_PAGES: Int = 150
    const val MAX_NOTE_TEXT_CHARS: Int = 1_000_000
    const val MAX_NOTE_OCR_PAGES: Int = 25

    /**
     * Whether [nativeText] should be replaced by OCR: forced, absent, or
     * watermark-thin. A page with real prose is NEVER re-OCRed — the merge
     * must not duplicate native text (the iOS "never duplicate" rule).
     */
    fun pageNeedsOcr(nativeText: String?, forceOCR: Boolean): Boolean {
        if (forceOCR) return true
        val trimmed = nativeText?.trim()
        if (trimmed.isNullOrEmpty()) return true
        return trimmed.length <= THIN_TEXT_CHARS
    }

    /**
     * The extraction for one page, as the caller contributed it.
     * [text] is the page's final content (native or OCRed), null when the
     * page contributed nothing (dropped — a watermark-only page past the
     * OCR cap contributes nothing rather than its watermark).
     */
    data class Page(val text: String?, val wasOcr: Boolean = false)

    /** The merged document. */
    data class Merged(val text: String, val ocrPages: Int, val truncated: Boolean)

    /**
     * Joins the pages in reading order with blank lines, counting OCR'd
     * pages, and applies the character cap with the iOS truncation marker.
     * Returns null when nothing survived — information, not an error: the
     * caller surfaces that to the user instead of importing an empty note.
     */
    fun merge(pages: List<Page>, maxChars: Int, truncatedNote: String): Merged? {
        val out = StringBuilder()
        var ocrPages = 0
        var truncated = false
        for (page in pages) {
            val t = page.text?.trim().orEmpty()
            if (t.isEmpty()) continue
            if (page.wasOcr) ocrPages++
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(t)
            if (out.length > maxChars) {
                out.append("\n\n").append(truncatedNote)
                truncated = true
                break
            }
        }
        if (out.isEmpty()) return null
        return Merged(out.toString(), ocrPages, truncated)
    }

    /**
     * How many of the document's pages the note import may still OCR: the
     * cap minus what a scan has already spent. Past the cap a scanned page
     * is DROPPED (its text layer, if any, is the watermark — appending that
     * is worse than dropping it), never merged from both sources.
     */
    fun remainingOcrPages(used: Int): Int = (MAX_NOTE_OCR_PAGES - used).coerceAtLeast(0)
}
