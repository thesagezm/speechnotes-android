package com.speechnotes.app.logic

/**
 * RTF (Rich Text Format) — the format every word processor since Word 2.0
 * can still write. Ported from speechnotes-linux-rust
 * `crates/sn-doc/src/rtf.rs`.
 *
 * Scope: the text-bearing parts of an RTF document, which is all a reader
 * needs. Handled:
 *
 * - Group nesting (`{`/`}`) with an explicit stack, so a `\par` inside a
 *   nested group cannot leak into the paragraph buffer that opened it —
 *   the classic bug that turns a whole document into one enormous line.
 * - `\uN?` Unicode escapes (with the ANSI fallback the control's optional
 *   replacement character carries), `\'hh` byte escapes, and `\ansicpg`
 *   codepages.
 * - Destination groups that carry content: `\line`, `\par`, `\pard`,
 *   `\sect`, `\page`, `\tab`, `\cell`, `\row`.
 * - Destinations that must be SKIPPED: the well-known metadata groups
 *   (`\fonttbl`, `\colortbl`, `\stylesheet`, `\info`, `\listtable`, …)
 *   and every `\*\…` destination the reader does not understand.
 * - Emphasis (`\b`, `\i`, `\ul`) so a paragraph that is entirely
 *   emphasised, short and unpunctuated can open a chapter — the RTF
 *   convention for a title standing alone on its line.
 * - `\info`'s `\title` and `\author` for the shelf card.
 *
 * Not handled, and said so rather than half-done: `\pict` image payloads
 * (hex blobs this reader skips rather than mis-speaks) and table borders
 * beyond splitting rows at `\cell`/`\row`.
 */
object BookRtfParser {

    /** Bound on one chapter's paragraphs — a document with no headings at
     *  all still chunks into units the reader can open one at a time. */
    private const val CHAPTER_PARAGRAPH_LIMIT = 150

    /** Parse a `.rtf` file. */
    fun parse(data: ByteArray): BookDoc {
        val reader = Reader.run(data)
        if (reader.items.isEmpty()) {
            throw BookDocError.Malformed("no readable text in the RTF document")
        }
        val (chapters, toc) = chapterize(reader.items)
        return BookDoc(
            title = reader.infoTitle,
            author = reader.infoAuthor,
            chapters = chapters,
            toc = toc,
        )
    }

    /** One output paragraph in progress. */
    private class Builder {
        var text = StringBuilder()
        /** Any emphasis formatting was ever on in this paragraph. */
        var emphasized = false
        var isBold = false
        var isItalic = false
        var isUnderlined = false
        var isMarked = false

        fun isEmpty(): Boolean = text.trim().isEmpty()

        fun copyFrom(other: Builder) {
            text = StringBuilder(other.text)
            emphasized = other.emphasized
            isBold = other.isBold
            isItalic = other.isItalic
            isUnderlined = other.isUnderlined
            isMarked = other.isMarked
        }
    }

    /** A group on the stack: the state to restore on `}`. */
    private class GroupState(val builder: Builder, val skipDepth: Int, val infoKey: String?)

    /** One paragraph or heading the reader emitted. */
    private sealed class Item {
        class Heading(val text: String) : Item()
        class Paragraph(val text: String) : Item()
    }

    /**
     * The streaming reader. RTF is a 7-bit syntax with `\'hh` escapes, so it
     * reads BYTES, not characters — decoding first would have already turned
     * `\'e9` into a replacement character before the codepage was known.
     */
    private class Reader private constructor(private val bytes: ByteArray) {
        val items = ArrayList<Item>()

        private var builder = Builder()
        private val stack = ArrayList<GroupState>()
        /** Nesting depth of a destination being skipped. > 0 means "read nothing". */
        private var skipDepth = 0
        /** Which `\info` field is open, if any. */
        private var infoKey: String? = null
        /** Raw bytes seen inside an `\info` field. */
        private val infoBytes = ArrayList<Byte>()
        var infoTitle: String? = null
            private set
        var infoAuthor: String? = null
            private set

        private var index = 0
        /** Codepage from `\ansicpg`. CP1252 is the spec's floor. */
        private var codepage = 1252
        /** Single-byte text accumulated in the current group, decoded at flush. */
        private val byteBuffer = ArrayList<Byte>()
        /** A `\*` was just read: the next control word decides whether this
         *  group is a destination the reader skips. */
        private var starPending = false
        /** The `\ucN` state: how many fallback bytes each `\uN` is followed by. */
        private var unicodeFallback = 1

        companion object {
            fun run(data: ByteArray): Reader {
                // The signature is `{\rtfN` — the `{` plus a backslash plus `rtf`.
                // Checking the two bytes `{r` (which a naive reader does) rejects
                // every real RTF file.
                if (data.size <= 6 ||
                    data[0] != '{'.code.toByte() || data[1] != '\\'.code.toByte() ||
                    data[2] != 'r'.code.toByte() || data[3] != 't'.code.toByte() ||
                    data[4] != 'f'.code.toByte()
                ) {
                    throw BookDocError.Malformed("not an RTF document")
                }
                val reader = Reader(data)
                reader.scan()
                reader.flushParagraph()
                return reader
            }
        }

        private fun scan() {
            while (index < bytes.size) {
                val byte = bytes[index]
                when ((byte.toInt() and 0xFF).toChar()) {
                    '{' -> {
                        // Text already read belongs to the paragraph that was
                        // open — flush it into the builder before the state
                        // snapshot.
                        if (byteBuffer.isNotEmpty()) decodeByteBufferIntoBuilder()
                        stack.add(GroupState(Builder().apply { copyFrom(builder) }, skipDepth, infoKey))
                        starPending = false
                        index++
                    }
                    '}' -> closeGroup()
                    '\\' -> {
                        index++
                        // `\'hh` is a byte escape and `\*` a destination mark,
                        // not control words.
                        val next = bytes.getOrNull(index)
                        when {
                            next == '\''.code.toByte() -> {
                                index++
                                hexEscape()
                            }
                            next == '*'.code.toByte() -> {
                                index++
                                starPending = true
                            }
                            else -> controlWord()
                        }
                    }
                    '\r', '\n' -> index++
                    else -> {
                        // Literal text, but only when not inside a destination.
                        if (skipDepth > 0) {
                            if (infoKey != null) infoBytes.add(byte)
                            index++
                            continue
                        }
                        byteBuffer.add(byte)
                        index++
                    }
                }
            }
        }

        /** Flushes the group the `}` closes: its paragraph text becomes
         *  content and the enclosing group's state comes back. */
        private fun closeGroup() {
            if (byteBuffer.isNotEmpty()) decodeByteBufferIntoBuilder()
            val parentHasText = stack.lastOrNull()?.let { !it.builder.isEmpty() } ?: false
            if (!builder.isEmpty()) {
                val finished = builder
                builder = Builder()
                if (parentHasText) {
                    emit(finished)
                } else {
                    // Keep accumulating into the parent: this group was a
                    // formatting scope, not a paragraph. The emphasis flags
                    // travel too — a bold word alone must still be able to
                    // make its paragraph a title.
                    stack.lastOrNull()?.let { parent ->
                        parent.builder.text.append(finished.text)
                        parent.builder.emphasized = parent.builder.emphasized || finished.emphasized
                        parent.builder.isMarked = parent.builder.isMarked || finished.isMarked
                    }
                }
            }
            // An `\info` field's text arrives as raw bytes inside the group
            // that held it — captured at the group's close.
            infoKey?.let { key ->
                if (infoBytes.isNotEmpty()) {
                    val raw = ByteArray(infoBytes.size) { infoBytes[it] }
                    infoBytes.clear()
                    captureInfo(key, raw)
                }
            }
            val state = stack.removeLastOrNull()
            if (state != null) {
                builder = state.builder
                skipDepth = state.skipDepth
                infoKey = state.infoKey
            } else {
                builder = Builder()
            }
            index++
        }

        // -- control words -------------------------------------------------

        private fun controlWord() {
            val word = StringBuilder()
            while (index < bytes.size) {
                val byte = bytes[index]
                if (!((byte.toInt() and 0xFF).toChar().isAsciiLetter())) break
                word.append((byte.toInt() and 0xFF).toChar())
                index++
            }
            // Optional signed numeric parameter.
            var parameter: Int = 0
            var hasParameter = false
            if (index < bytes.size && bytes[index] == '-'.code.toByte()) {
                hasParameter = true
                index++
                while (index < bytes.size && ((bytes[index].toInt() and 0xFF).toChar().isAsciiDigit())) {
                    parameter = parameter * 10 + (bytes[index] - '0'.code.toByte())
                    index++
                }
                parameter = -parameter
            } else if (index < bytes.size && ((bytes[index].toInt() and 0xFF).toChar().isAsciiDigit())) {
                hasParameter = true
                while (index < bytes.size && ((bytes[index].toInt() and 0xFF).toChar().isAsciiDigit())) {
                    parameter = parameter * 10 + (bytes[index] - '0'.code.toByte())
                    index++
                }
            }
            // A single space after a control word is a delimiter, not content.
            if (index < bytes.size && bytes[index] == ' '.code.toByte()) index++
            val known = apply(word.toString(), parameter, hasParameter)
            if (starPending) {
                // `\*` + unknown word: the group is a destination this reader
                // does not understand — its whole body is skipped.
                starPending = false
                if (!known) skipDepth++
            }
        }

        /** One control word applied. Returns whether the reader knows the
         *  word — an unknown one under `\*` turns the group into a skipped
         *  destination. */
        private fun apply(word: String, parameter: Int, hasParameter: Boolean): Boolean {
            when {
                word == "ansicpg" && hasParameter -> {
                    codepage = parameter.coerceIn(0, 65535)
                    return true
                }
                // Destinations to skip entirely.
                word in setOf(
                    "fonttbl", "colortbl", "stylesheet", "listtable", "listoverridetable",
                    "generator", "pict", "object", "themedata", "colorschememapping",
                    "datastore", "xmlnstbl", "latentstyles", "rsidtbl", "mmathPr", "upr",
                    "header", "footer", "headerl", "headerr", "footerl", "footerr",
                    "footnote", "ftnsep", "ftnsepc", "ftncn", "aftnsep", "aftnsepc",
                    "aftncn",
                ) -> {
                    skipDepth++
                    return true
                }
                word == "info" -> {
                    skipDepth++
                    infoKey = null
                    return true
                }
                word in setOf("title", "author", "subject", "keywords", "operator", "company", "category", "doccomm") -> {
                    // An `\info` child: capture its text, but never read it
                    // aloud.
                    if (skipDepth > 0) {
                        infoKey = word
                        infoBytes.clear()
                    }
                    return true
                }
                // A bare `\b`/`\i`/`\ul` means the property is ON — the
                // absence of a parameter is not a parameter of zero.
                word == "b" -> {
                    val on = !hasParameter || parameter != 0
                    builder.isBold = on
                    if (on) builder.isMarked = true
                    return true
                }
                word == "i" -> {
                    val on = !hasParameter || parameter != 0
                    builder.isItalic = on
                    if (on) builder.isMarked = true
                    return true
                }
                word in setOf("ul", "ulw", "uld", "uldb") -> {
                    val on = !hasParameter || parameter != 0
                    builder.isUnderlined = on
                    if (on) builder.isMarked = true
                    return true
                }
                word == "ulnone" -> {
                    builder.isUnderlined = false
                    return true
                }
                word == "plain" -> {
                    builder.isBold = false
                    builder.isItalic = false
                    builder.isUnderlined = false
                    builder.isMarked = false
                    return true
                }
                word in setOf("par", "sect", "page", "line") -> {
                    flushParagraph()
                    return true
                }
                word == "pard" -> {
                    // A destination change that starts a new paragraph for
                    // many writers; flushing is what makes RTF's "paragraph =
                    // group" and "paragraph = \par" styles both work.
                    flushParagraph()
                    return true
                }
                word == "tab" -> {
                    appendText(" ")
                    return true
                }
                word == "cell" -> {
                    appendText(", ")
                    return true
                }
                word == "row" -> {
                    flushParagraph()
                    return true
                }
                word == "u" && hasParameter -> {
                    // RTF writes a 16-bit value; values over 32767 arrive as
                    // negative numbers, which is the same bits re-read as i16.
                    // A surrogate half has no character of its own and is
                    // dropped.
                    val scalar = (parameter.toShort()).toInt() and 0xFFFF
                    codePointOrNull(scalar)?.let { appendText(it) }
                    // The escape is followed by up to `\ucN` bytes of the ANSI
                    // fallback (usually the `?` a writer emits) — skipped. The
                    // skip stops at syntax: a fallback that never ends must
                    // not eat the rest of the document.
                    var skipped = 0
                    while (skipped < unicodeFallback && index < bytes.size) {
                        val byte = bytes[index]
                        if (byte == '\\'.code.toByte() || byte == '{'.code.toByte() ||
                            byte == '}'.code.toByte() || byte == '\r'.code.toByte() ||
                            byte == '\n'.code.toByte()
                        ) break
                        index++
                        skipped++
                    }
                    return true
                }
                word == "uc" && hasParameter -> {
                    // `\ucN` SETS the fallback count the `\uN` escapes use.
                    unicodeFallback = maxOf(parameter, 0)
                    return true
                }
                word == "emdash" -> {
                    appendText("—")
                    return true
                }
                word == "endash" -> {
                    appendText("–")
                    return true
                }
                word == "lquote" || word == "rquote" -> {
                    appendText("‘")
                    return true
                }
                word == "ldblquote" || word == "rdblquote" -> {
                    appendText("”")
                    return true
                }
                word == "bullet" -> {
                    appendText("•")
                    return true
                }
                word == "nbsp" -> {
                    appendText(" ")
                    return true
                }
                else -> return false
            }
        }

        /** `\'hh` — one byte in the document's codepage. */
        private fun hexEscape() {
            if (index + 1 >= bytes.size) {
                index = bytes.size
                return
            }
            val high = hexValue(bytes[index])
            val low = hexValue(bytes[index + 1])
            index += 2
            if (high == null || low == null) return
            val byte = (high * 16 + low).toByte()
            if (skipDepth > 0) {
                // Inside `\info`, byte escapes are metadata text too.
                if (infoKey != null) infoBytes.add(byte)
                return
            }
            byteBuffer.add(byte)
        }

        // -- text accumulation ---------------------------------------------

        private fun appendText(value: String) {
            if (skipDepth > 0) return
            if (byteBuffer.isNotEmpty()) decodeByteBufferIntoBuilder()
            builder.text.append(value)
            if (builder.isBold || builder.isItalic || builder.isUnderlined) builder.emphasized = true
        }

        /** The single-byte buffer accumulates `\'hh` and other raw 8-bit
         *  bytes; it is decoded once, in the document's codepage, when text
         *  is needed. */
        private fun decodeByteBufferIntoBuilder() {
            if (byteBuffer.isEmpty()) return
            val buffer = ByteArray(byteBuffer.size) { byteBuffer[it] }
            byteBuffer.clear()
            val decoded = if (codepage == 65001) {
                String(buffer, Charsets.UTF_8)
            } else {
                BookCodepages.decodeNamed(codepage.toString(), buffer)
                    ?: String(buffer, Charsets.UTF_8)
            }
            builder.text.append(decoded)
            if (builder.isBold || builder.isItalic || builder.isUnderlined) builder.emphasized = true
        }

        // -- paragraphs ----------------------------------------------------

        private fun flushParagraph() {
            if (byteBuffer.isNotEmpty()) decodeByteBufferIntoBuilder()
            val finished = builder
            builder = Builder()
            if (finished.isEmpty()) return
            emit(finished)
        }

        private fun emit(value: Builder) {
            // While a destination is being skipped there is nothing to emit.
            if (skipDepth > 0) return
            val text = tidy(value.text.toString())
            if (text.isEmpty()) return
            // An emphasised, short, unpunctuated line standing on its own is
            // a heading — the same convention the FB2 and HTML readers use.
            val looksLikeHeading = value.isMarked &&
                value.emphasized &&
                text.length <= 120 &&
                !text.endsWithSentence()
            if (looksLikeHeading) {
                items.add(Item.Heading(text))
            } else {
                items.add(Item.Paragraph(text))
            }
        }

        private fun captureInfo(key: String, raw: ByteArray) {
            val decoded = if (codepage == 65001) {
                String(raw, Charsets.UTF_8)
            } else {
                BookCodepages.decodeNamed(codepage.toString(), raw)
                    ?: String(raw, Charsets.UTF_8)
            }
            val value = tidy(decoded)
            if (value.isEmpty()) return
            when (key) {
                "title" -> if (infoTitle == null) infoTitle = value
                "author" -> if (infoAuthor == null) infoAuthor = value
            }
        }
    }

    private fun hexValue(byte: Byte): Int? = when ((byte.toInt() and 0xFF).toChar()) {
        in '0'..'9' -> byte - '0'.code.toByte()
        in 'A'..'F' -> byte - 'A'.code.toByte() + 10
        in 'a'..'f' -> byte - 'a'.code.toByte() + 10
        else -> null
    }

    /** Whitespace and NBSP tidy — collapse the indentation and line wraps an
     *  RTF writer scatters through a paragraph. */
    internal fun tidy(text: String): String {
        val out = StringBuilder(text.length)
        var inSpace = false
        for (c in text) {
            if (c.isWhitespace() || c.code == 0xA0) {
                if (!inSpace) {
                    out.append(' ')
                    inSpace = true
                }
            } else {
                out.append(c)
                inSpace = false
            }
        }
        return out.toString().trim()
    }

    private fun String.endsWithSentence(): Boolean {
        val last = lastOrNull() ?: return false
        return last == '.' || last == ',' || last == ';' || last == '!' || last == '?'
    }

    private fun codePointOrNull(value: Int): String? {
        if (value < 0) return null
        if (value in 0xD800..0xDFFF) return null
        return String(Character.toChars(value))
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    // -- chapterization --------------------------------------------------------

    /** RTF carries no outline structure, so chapters are the emphasised
     *  standalone lines; everything between them is one chapter. A document
     *  with none chunks into ~150-paragraph chapters. */
    private fun chapterize(items: List<Item>): Pair<List<BookChapter>, List<BookToc>> {
        val chapters = ArrayList<BookChapter>()
        val toc = ArrayList<BookToc>()
        var title: String? = null
        val html = StringBuilder()
        var paragraphCount = 0

        fun flush() {
            if (html.isNotEmpty() || title != null) {
                val label = title ?: "Chapter ${chapters.size + 1}"
                toc.add(BookToc(label, chapters.size, 0))
                chapters.add(BookChapter(title, html.toString()))
                html.clear()
            }
            paragraphCount = 0
        }

        for (item in items) {
            when (item) {
                is Item.Heading -> {
                    flush()
                    title = item.text
                }
                is Item.Paragraph -> {
                    html.append("<p>").append(xmlEscape(item.text)).append("</p>")
                    paragraphCount++
                }
            }
            if (paragraphCount >= CHAPTER_PARAGRAPH_LIMIT) {
                // The bound: the title carries onto the continuation chapter
                // so the contents list never shows "Chapter N" mid-section.
                val carried = title
                title = null
                flush()
                title = carried
            }
        }
        flush()

        return chapters to toc
    }
}
