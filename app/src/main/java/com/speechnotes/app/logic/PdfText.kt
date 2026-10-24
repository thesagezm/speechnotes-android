package com.speechnotes.app.logic

/**
 * PDF → speech-text chapter logic. Ported from PdfText.swift
 * (speechnotes-ios). The platform's text extraction (PdfBox-Android on
 * Android, PDFKit on iOS) supplies the raw per-page strings and outlines;
 * this file decides how pages become chapters and normalizes the text.
 */
object PdfText {

    /** One speech unit of a PDF: [startPage]..[endPage] (inclusive, 0-based). */
    data class PdfChapter(val label: String, val startPage: Int, val endPage: Int)

    /** Where a page's text starts inside its chapter's speech text. */
    data class PdfPageOffset(val page: Int, val utf16Offset: Int)

    /** What the resolver needs from the platform PDF layer. */
    interface PageSource {
        val pageCount: Int
        /** Outline rows as (label, pageIndex); empty when the PDF has none. */
        fun outline(): List<Pair<String, Int>>
        /** Per-page lines: text + geometry (y center, height, x, width). */
        fun lines(page: Int): List<Line>
        fun pageText(page: Int): String
    }

    data class Line(
        val text: String,
        val y: Double,
        val height: Double,
        val x: Double,
        val width: Double,
    )

    /**
     * The one entry the app calls: chapter list + where it came from
     * ("outline" / "headings" / "pages").
     */
    fun resolveChapters(source: PageSource): Pair<List<PdfChapter>, String> {
        chaptersFromOutline(source)?.let { return it to "outline" }
        chaptersFromHeadings(source)?.let { return it to "headings" }
        return fallbackChapters(source.pageCount) to "pages"
    }

    /**
     * Top-level outline nodes as chapter starts. A node with a resolvable
     * destination contributes ONE start and its children are swallowed.
     */
    fun chaptersFromOutline(source: PageSource): List<PdfChapter>? {
        val pageCount = source.pageCount
        if (pageCount <= 0) return null
        val outline = source.outline()
        if (outline.isEmpty()) return null
        val starts = ArrayList<Pair<String, Int>>()
        for ((label, page) in outline) {
            if (page in 0 until pageCount && label.isNotBlank()) starts.add(label.trim() to page)
        }
        if (starts.isEmpty()) return null
        return fixUpStarts(starts, pageCount)
    }

    /**
     * Headings from line geometry: a heading line is significantly taller
     * than the modal body line. The geometric sibling of the classic
     * font-size heuristic.
     */
    fun chaptersFromHeadings(source: PageSource, maxScanPages: Int = 400): List<PdfChapter>? {
        val pageCount = source.pageCount
        if (pageCount <= 0) return null
        val scan = minOf(pageCount, maxScanPages)
        val starts = ArrayList<Pair<String, Int>>()
        for (page in 0 until scan) {
            val lines = source.lines(page)
            if (lines.isEmpty()) continue
            val modalHeight = modalValue(lines.map { it.height }) ?: continue
            for (line in lines) {
                if (line.height > modalHeight * 1.35 && line.text.isNotBlank()) {
                    starts.add(line.text.trim().take(80) to page)
                }
            }
        }
        if (starts.size < 2) return null
        return fixUpStarts(starts, pageCount)
    }

    /** Labeled page ranges are the floor so playback always has units. */
    fun fallbackChapters(pageCount: Int, groupSize: Int = 10): List<PdfChapter> {
        if (pageCount <= 0) return emptyList()
        val chapters = ArrayList<PdfChapter>()
        var start = 0
        while (start < pageCount) {
            val end = minOf(start + groupSize - 1, pageCount - 1)
            chapters.add(PdfChapter("Pages ${start + 1}–${end + 1}", start, end))
            start = end + 1
        }
        return chapters
    }

    /** Strictly increasing, non-empty chapter starts. */
    private fun fixUpStarts(starts: List<Pair<String, Int>>, pageCount: Int): List<PdfChapter> {
        val sorted = starts.sortedBy { it.second }
        val result = ArrayList<PdfChapter>(sorted.size)
        for (i in sorted.indices) {
            val start = sorted[i].second.coerceIn(0, pageCount - 1)
            val end = (sorted.getOrNull(i + 1)?.second?.minus(1) ?: (pageCount - 1)).coerceIn(start, pageCount - 1)
            if (result.isEmpty() || start > result.last().endPage) {
                result.add(PdfChapter(sorted[i].first, start, end))
            }
        }
        return result.ifEmpty { fallbackChapters(pageCount) }
    }

    // MARK: - Chapter text

    /** Concatenated chapter text + per-page offsets for page-synced read-along. */
    fun chapterText(source: PageSource, chapters: List<PdfChapter>, index: Int): Pair<String, List<PdfPageOffset>>? {
        if (index < 0 || index >= chapters.size) return null
        val chapter = chapters[index]
        val text = StringBuilder()
        val offsets = ArrayList<PdfPageOffset>()
        for (page in chapter.startPage..chapter.endPage) {
            if (page < 0 || page >= source.pageCount) continue
            // Route through the gutter-aware reader (not the platform's raw
            // page string) so two-column chapters read left→right like the
            // reader view does.
            val pageString = pageText(source, page)
            if (pageString.isEmpty()) continue
            offsets.add(PdfPageOffset(page, text.length))
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(pageString)
        }
        if (text.isEmpty()) return null
        return text.toString() to offsets
    }

    /**
     * Two-column layouts are split at a detected gutter and read left→right
     * (the platform's default order interleaves the columns); everything
     * else uses the page's default string.
     */
    fun pageText(source: PageSource, page: Int): String {
        val lines = source.lines(page)
        val split = twoColumnSplit(lines)
        if (split != null) {
            val left = lines.filter { it.x + it.width <= split.first }.joinToString("\n") { it.text }
            val right = lines.filter { it.x >= split.second }.joinToString("\n") { it.text }
            return normalize("$left\n\n$right")
        }
        return normalize(source.pageText(page))
    }

    /**
     * Finds a vertical gutter that cleanly separates two text columns: a
     * candidate band IS the gutter when no line crosses it.
     */
    fun twoColumnSplit(lines: List<Line>): Pair<Double, Double>? {
        if (lines.size < 6) return null
        val xs = lines.map { it.x }.sorted()
        val maxX = lines.maxOf { it.x + it.width }
        var bestBand: Pair<Double, Double>? = null
        var bestScore = -1
        // Candidate bands: between successive line-start x positions.
        for (i in 0 until xs.size - 1) {
            val lo = xs[i]
            val hi = xs[i + 1]
            if (hi - lo < 8) continue
            val band = lo..hi
            val crossing = lines.count { it.x < band.endInclusive && (it.x + it.width) > band.start }
            val left = lines.count { it.x + it.width <= band.start }
            val right = lines.count { it.x >= band.endInclusive }
            if (left >= 3 && right >= 3 && crossing == 0) {
                val score = left + right
                if (score > bestScore) {
                    bestScore = score
                    bestBand = lo to hi
                }
            }
        }
        return bestBand
    }

    /**
     * NFKC maps ligatures (ﬁ → fi, ﬂ → fl); hyphen-newline joins words split
     * across lines; CR becomes \n; blank runs collapse; then
     * SpeechSanitizer.clean.
     */
    fun normalize(raw: String): String {
        var text = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
        for (hyphenBreak in listOf("-\r\n", "-\r", "-\n")) {
            while (text.contains(hyphenBreak)) text = text.replace(hyphenBreak, "")
        }
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        while (text.contains("\n\n\n")) text = text.replace("\n\n\n", "\n\n")
        return SpeechSanitizer.clean(text)
    }

    /** Modal value via 1-D clustering: line heights jitter, so exact-equality histograms never peak. */
    fun modalValue(values: List<Double>, bucket: Double = 1.5): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        var bestCount = 0
        var bestValue = sorted[0]
        var index = 0
        while (index < sorted.size) {
            var end = index
            while (end + 1 < sorted.size && sorted[end + 1] - sorted[index] <= bucket) end++
            val count = end - index + 1
            if (count > bestCount) {
                bestCount = count
                bestValue = (sorted[index] + sorted[end]) / 2.0
            }
            index = end + 1
        }
        return bestValue
    }
}
