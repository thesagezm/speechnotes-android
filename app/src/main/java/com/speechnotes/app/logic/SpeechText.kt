package com.speechnotes.app.logic

/**
 * The one boundary every speak path goes through. Ported from the Swift
 * `SpeechText` derivation enum (BeepPlayer.swift, speechnotes-ios): raw text
 * → sanitized, whitespace-normalized, offset-preserving so read-along and
 * engine coordinates agree.
 */
object SpeechText {
    /**
     * The text handed to an engine: sanitized (unspeakable code points
     * replaced by spaces) and whitespace-normalized, offsets preserved so
     * highlighting stays aligned with the source string.
     *
     * EXCEPTION, iOS 1fa3482 parity: when the raw draft carries maths, the
     * formulas are expanded to their SPOKEN words first — an engine handed
     * `$\beta$` reads "backslash beta" or nothing. Expansion changes
     * lengths, so read-along highlighting can drift ON NOTES WITH FORMULAS;
     * a formula spoken as words beats an offset that points at a backslash.
     */
    fun forText(text: String): String {
        if (text.isEmpty()) return text
        val expanded =
            if (text.contains('$') || text.contains('\\')) MathNotation.spokenText(text) else text
        return SpeechSanitizer.cleanedPreservingOffsets(expanded)
    }

    /** Plain speech text for markdown: strip syntax, keep content. */
    fun forMarkdown(markdown: String): String =
        SpeechSanitizer.clean(MarkdownText.plainText(markdown))

    /** True when the string has nothing a reader would say. */
    fun isSpeakable(text: String): Boolean = text.any { !it.isWhitespace() }
}
