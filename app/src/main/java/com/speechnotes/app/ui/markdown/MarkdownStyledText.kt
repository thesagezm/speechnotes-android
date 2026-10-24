package com.speechnotes.app.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.Modifier

/**
 * Inline markdown styling for the reading view.
 *
 * The preview used to hand every block straight to `Text(block.text)`, so
 * `**bold**`, `*italic*`, `` `code` ``, `~~strike~~` and `[links](url)` all
 * rendered as their literal source — the note read like a wall of asterisks.
 * [MarkdownText.speechInline] already knows how to strip all of that for the
 * TTS path; this does the visual counterpart, using the same marker rules so
 * what the editor shows and what the engine hears cannot drift.
 *
 * The tokenizer is a single pass with the alternation ordered exactly like the
 * iOS renderer, so `***x***` reads as bold-italic rather than bold-then-*.
 */
object MarkdownStyledText {

    /** One styled fragment of a text run. */
    data class Span(val text: String, val style: SpanStyle)

    /**
     * The marker grammar, shared with the iOS MarkdownPreviewView:
     * code, bold-italic, bold, strong-underscore, strike, italic-star,
     * italic-underscore. The underscore rules carry word-boundary guards so
     * snake_case identifiers and 5_file_names are left alone.
     */
    private val PATTERN = Regex(
        "`([^`]+)`" +
            """|\*\*\*([^*]+)\*\*\*""" +
            """|\*\*([^*]+)\*\*""" +
            """|__([^_]+)__""" +
            """|~~([^~]+)~~""" +
            """|(?<![*\w])\*([^*\s][^*]*)\*(?!\*)""" +
            """|(?<![\w_])_([^_\s][^_]*)_(?![\w_])"""
    )

    private val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace)
    private val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    private val boldItalicStyle = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    private val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    private val strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)

    /**
     * Splits [text] into styled fragments. Unmatched markers stay literal, so
     * a stray `*` in prose survives as an asterisk instead of eating the rest
     * of the paragraph.
     */
    fun spans(text: String): List<Span> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Span>()
        var cursor = 0
        for (m in PATTERN.findAll(text)) {
            if (m.range.first > cursor) {
                out.add(Span(text.substring(cursor, m.range.first), SpanStyle()))
            }
            val body = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
            val style = when {
                m.groupValues[1].isNotEmpty() -> codeStyle
                m.groupValues[2].isNotEmpty() -> boldItalicStyle
                m.groupValues[3].isNotEmpty() -> boldStyle
                m.groupValues[4].isNotEmpty() -> boldStyle
                m.groupValues[5].isNotEmpty() -> strikeStyle
                m.groupValues[6].isNotEmpty() -> italicStyle
                m.groupValues[7].isNotEmpty() -> italicStyle
                else -> null
            }
            if (body == null || style == null) {
                out.add(Span(m.value, SpanStyle()))
            } else {
                out.add(Span(body, style))
            }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) out.add(Span(text.substring(cursor), SpanStyle()))
        return out
    }

    /** [text] as an [AnnotatedString] with inline markdown styling applied. */
    fun annotated(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString =
        buildAnnotatedString {
            for (span in spans(text)) {
                if (span.style == SpanStyle()) {
                    append(span.text)
                } else {
                    pushStyle(span.style)
                    append(span.text)
                    pop()
                }
            }
        }

    /** [text] with inline styling, plus a clickable handler for bare links. */
    @Composable
    fun Text(
        text: String,
        style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
        modifier: Modifier = Modifier,
    ) {
        val linkColor = MaterialTheme.colorScheme.primary
        val annotated = remember(text, linkColor) { annotated(text, linkColor) }
        androidx.compose.material3.Text(annotated, style = style, modifier = modifier)
    }
}
