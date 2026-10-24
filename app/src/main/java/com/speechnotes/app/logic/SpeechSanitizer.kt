package com.speechnotes.app.logic

/**
 * Text hygiene for TTS. Ported from SpeechSanitizer.swift (speechnotes-ios):
 * removes only code points a reader would never pronounce, leaving every one
 * they would. No punctuation is touched — the chunker's sentence rules and
 * the read-along offsets both depend on it.
 */
object SpeechSanitizer {

    private val zeroWidth = charArrayOf(
        '\u00AD', '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
        '\u2060', '\u2061', '\u2062', '\u2063', '\u2064', '\uFEFF',
    )

    private val bidiControls = charArrayOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )

    /** C0/C1 control ranges that are never pronouncable (tab/LF/CR kept). */
    fun isUnspeakable(codePoint: Int): Boolean {
        return when (codePoint) {
            0x09, 0x0A, 0x0D -> false // tab, LF, CR are real whitespace
            0x00 -> true             // NUL
            in 0x01..0x08, in 0x0B..0x0C, in 0x0E..0x1F, in 0x7F..0x9F -> true
            else -> {
                val c = codePoint.toChar()
                c in zeroWidth || c in bidiControls ||
                    codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF ||
                    codePoint in 0xE000..0xF8FF || codePoint in 0xF0000..0xFFFFD ||
                    codePoint in 0x100000..0x10FFFD
            }
        }
    }

    /**
     * Strips what a reader cannot pronounce and tidies whitespace, preserving
     * paragraph structure. Line breaks between non-empty lines are kept;
     * three or more consecutive newlines collapse to one blank line.
     */
    fun clean(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        text.codePoints().forEach { cp ->
            if (isUnspeakable(cp)) out.append(' ') else out.appendCodePoint(cp)
        }
        return normalizeWhitespace(out.toString())
    }

    /**
     * Offset-preserving variant for paths that index the cleaned string with
     * offsets from the ORIGINAL text (the read-along highlight is exactly
     * that). Unspeakable scalars become a single space, so length is
     * preserved exactly: result.length == text.length.
     */
    fun cleanedPreservingOffsets(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        text.codePoints().forEach { cp ->
            if (isUnspeakable(cp)) {
                out.append(if (cp == 0x0D || cp == 0x2028 || cp == 0x2029) '\n' else ' ')
            } else {
                out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /**
     * Collapses whitespace runs while preserving paragraph structure:
     * CRLF/CR become LF, trailing spaces go, 3+ newlines become one blank
     * line. Idempotent.
     */
    fun normalizeWhitespace(text: String): String {
        if (text.isEmpty()) return text
        val lines = text.split("\n")
            .map { line -> if (line.endsWith("\r")) line.dropLast(1) else line }
            .map { collapseSpaces(it) }

        var start = 0
        while (start < lines.size && lines[start].isEmpty()) start++
        var end = lines.size
        while (end > start && lines[end - 1].isEmpty()) end--
        if (start >= end) return ""

        val out = ArrayList<String>(end - start)
        var blankRun = 0
        for (index in start until end) {
            val line = lines[index]
            if (line.isEmpty()) {
                blankRun++
                if (blankRun < 2) out.add(line)
            } else {
                blankRun = 0
                out.add(line)
            }
        }
        return out.joinToString("\n")
    }

    /**
     * The substring around [offset] that a speech engine is handed, grown to
     * word boundaries so a paragraph boundary landing mid-word does not cut
     * one. Used by the audiobook sidecar builder.
     */
    fun snappedSpan(text: String, offset: Int, length: Int, slack: Int = 200): Triple<Int, Int, String> {
        val total = text.length
        val start = offset.coerceIn(0, total)
        val end = (start + length).coerceIn(start, total)
        val snappedStart = precedingWordBoundary(text, start, maxOf(0, start - slack))
        val snappedEnd = followingWordBoundary(text, end, minOf(total, end + slack))
        return Triple(snappedStart, snappedEnd, text.substring(snappedStart, snappedEnd))
    }

    // MARK: - Private

    private fun isSpaceUnit(c: Char): Boolean = when (c) {
        ' ', '\t', '\n', '\r', '\u000C', '\u000B', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000' -> true
        in '\u2000'..'\u200A' -> true
        else -> false
    }

    private fun collapseSpaces(line: String): String {
        if (line.isEmpty()) return ""
        val out = StringBuilder(line.length)
        var pendingSpace = false
        var wroteAny = false
        for (c in line) {
            if (c == '\t' || c == '\u00A0' || c.isWhitespace()) {
                pendingSpace = true
                continue
            }
            if (pendingSpace && wroteAny) out.append(' ')
            pendingSpace = false
            wroteAny = true
            out.append(c)
        }
        return out.toString()
    }

    private fun precedingWordBoundary(text: String, from: Int, limit: Int): Int {
        if (from <= 0) return 0
        var index = from
        while (index > limit && index > 0) {
            if (isSpaceUnit(text[index - 1])) return index
            index--
        }
        return from
    }

    private fun followingWordBoundary(text: String, from: Int, limit: Int): Int {
        if (from >= text.length) return text.length
        var index = from
        while (index < limit && index < text.length) {
            if (isSpaceUnit(text[index])) return index
            index++
        }
        return from
    }
}
