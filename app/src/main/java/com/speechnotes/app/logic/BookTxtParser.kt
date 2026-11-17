package com.speechnotes.app.logic

/**
 * Plain-text and Markdown book reader — the format Project Gutenberg still
 * ships more of than any other. Ported from speechnotes-linux-rust
 * `crates/sn-doc/src/plainbook.rs`.
 *
 * Two jobs, in order of how much they matter:
 *
 * 1. **Chapters.** A 900 kB `.txt` imported as one chapter is unusable —
 *    the contents list shows one row and resume always lands at the top.
 *    Real text books carry one of four structures, and each gets its own
 *    detection: Markdown headings (`#`…`######`), Gutenberg's running
 *    headings (`Chapter 7`, `PART TWO`), numbered short lines (`I.`, `12.`),
 *    and, when the file has no structure at all, chunks by paragraph count.
 * 2. **Encoding.** Gutenberg mixes UTF-8 with UTF-16 (the BOM tells you),
 *    latin-1 and CP1252; a wrong guess turns every é into two mojibake
 *    glyphs. The battery below decodes UTF-8 strictly, UTF-16/32 only on
 *    their BOM (or on an unambiguous null-byte pattern), and falls back to
 *    latin-1, which every byte survives.
 *
 * Markdown inline syntax (`**bold**`, `[text](url)`) is stripped rather
 * than spoken — the reader reads the words, not the markup.
 */
object BookTxtParser {

    /** Ceiling on one document's characters — a 40 MB text dump must fail
     *  with a message rather than take the import down. */
    const val MAX_CHARACTERS: Int = 4_000_000

    /** Bound on one chapter's blocks — a heading-less 900 kB text file still
     *  chunks into units the reader can open one at a time. */
    private const val CHAPTER_BLOCK_LIMIT = 150

    /** Parse a `.txt`/`.text` file into chapters of plain XHTML. */
    fun parse(data: ByteArray): BookDoc {
        val text = decode(data)
        if (text.isBlank()) {
            throw BookDocError.Malformed("the file has no readable text")
        }
        val chars = text.codePointCount(0, text.length)
        if (chars > MAX_CHARACTERS) {
            throw BookDocError.TooLarge(chars / 1000, MAX_CHARACTERS / 1_000_000)
        }
        val chapters = chapterize(text).map { (title, paragraphs) ->
            BookChapter(title, paragraphsParagraphs(paragraphs))
        }
        return BookDoc(chapters = chapters)
    }

    /** The paragraphs of one chapter as one `<p>` XHTML body. The text is
     *  escaped once, here — the reader serves this body as markup. */
    private fun paragraphsParagraphs(paragraphs: List<String>): String {
        val html = StringBuilder()
        for (paragraph in paragraphs) {
            html.append("<p>").append(xmlEscape(paragraph)).append("</p>")
        }
        return html.toString()
    }

    /**
     * The encoding battery, in the order it must be tried. UTF-16/32 are
     * only decoded on their BOM (or an unambiguous null pattern) because a
     * BOM-less even-length byte run decodes "successfully" in either parity
     * — into CJK garbage — and would swallow every latin-1 file before its
     * turn.
     */
    fun decode(data: ByteArray): String {
        // 1. UTF-8 — validates strictly, so a Latin-1 file never decodes
        //    here. The NUL test keeps UTF-16 out: NUL is valid UTF-8, and
        //    without the test every UTF-16 file "decoded" here as the right
        //    -looking text with a NUL between each character.
        decodeStrictUtf8(data)?.let { text ->
            if (!text.contains('\u0000') && !text.isBlank()) return text
        }
        // 2. UTF-16, BOM required.
        if (data.size >= 2) {
            if (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
                unitsToString(utf16Units(data, 2, bigEndian = true))?.let { return it }
            }
            if (data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
                unitsToString(utf16Units(data, 2, bigEndian = false))?.let { return it }
            }
        }
        // 3. UTF-32, BOM required (rare, but Gutenberg has shipped them).
        if (data.size >= 4 &&
            (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0xFE.toByte() && data[3] == 0xFF.toByte() ||
                data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() && data[2] == 0.toByte() && data[3] == 0.toByte())
        ) {
            val bigEndian = data[0] == 0.toByte()
            val text = StringBuilder()
            var valid = true
            var at = 4
            while (at + 4 <= data.size) {
                val value = if (bigEndian) {
                    ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
                        ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
                } else {
                    ((data[at + 3].toInt() and 0xFF) shl 24) or ((data[at + 2].toInt() and 0xFF) shl 16) or
                        ((data[at + 1].toInt() and 0xFF) shl 8) or (data[at].toInt() and 0xFF)
                }
                val c = codePointOrNull(value)
                if (c != null) text.append(c) else {
                    valid = false
                    break
                }
                at += 4
            }
            if (valid && !text.isBlank()) return text.toString()
        }
        // 4. UTF-16 with NO BOM — Gutenberg and Windows editors both ship
        //    these; the null-byte pattern is the tell.
        bomlessUtf16(data)?.let { return it }
        // 5. Latin-1 — total (every byte is a glyph), and correct for the
        //    Western corpus.
        val latin1 = decodeLatin1(data)
        if (!latin1.isBlank()) return latin1
        return String(data, Charsets.UTF_8)
    }

    /** Strict UTF-8: `null` when the bytes are not valid UTF-8. */
    private fun decodeStrictUtf8(data: ByteArray): String? = runCatching {
        java.nio.charset.Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(data)).toString()
    }.getOrNull()

    private fun utf16Units(data: ByteArray, from: Int, bigEndian: Boolean): CharArray {
        val units = CharArray((data.size - from) / 2)
        var at = from
        for (i in units.indices) {
            units[i] = if (bigEndian) {
                (((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)).toChar()
            } else {
                (((data[at + 1].toInt() and 0xFF) shl 8) or (data[at].toInt() and 0xFF)).toChar()
            }
            at += 2
        }
        return units
    }

    /** UTF-16 units → String, rejecting a decode that is mostly control
     *  characters (a 1% allowance with a floor, so a short clean string is
     *  not rejected by rounding). */
    private fun unitsToString(units: CharArray): String? {
        val text = String(units)
        if (text.isBlank()) return null
        val controls = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        if (controls * 100 >= maxOf(text.length, 100)) return null
        return text
    }

    /** Decodes BOM-less UTF-16 by its null-byte pattern, or `null` when
     *  [data] is not that shape. */
    private fun bomlessUtf16(data: ByteArray): String? {
        if (data.size < 4) return null
        val sampleLength = minOf(data.size, 4096)
        val pairs = sampleLength / 2
        if (pairs < 2) return null
        var evenNulls = 0
        var oddNulls = 0
        for (index in 0 until sampleLength) {
            if (data[index] == 0.toByte()) {
                if (index % 2 == 0) evenNulls++ else oddNulls++
            }
        }
        val evenShare = evenNulls.toDouble() / pairs
        val oddShare = oddNulls.toDouble() / pairs
        val bigEndian = evenShare > 0.3 && oddShare < 0.1
        val littleEndian = oddShare > 0.3 && evenShare < 0.1
        if (bigEndian == littleEndian) return null
        // The parity that is null-heavy is the HIGH byte's.
        return unitsToString(utf16Units(data, 0, bigEndian))
    }

    private fun codePointOrNull(value: Int): String? {
        if (value < 0 || value > 0x10FFFF) return null
        if (value in 0xD800..0xDFFF) return null
        return String(Character.toChars(value))
    }

    // -- chapters -------------------------------------------------------------

    /** Splits the text into chapters: a `(title, paragraphs)` pair each. A
     *  chapter that fills up carries its title onto the continuation so the
     *  contents list never shows "Chapter N" mid-section. */
    private fun chapterize(text: String): List<Pair<String?, List<String>>> {
        val blocks = textBlocks(text)
        if (blocks.isEmpty()) return listOf(null to emptyList())

        val chapters = ArrayList<Pair<String?, List<String>>>()
        var title: String? = null
        var paragraphs = ArrayList<String>()

        fun flush() {
            if (paragraphs.isNotEmpty() || title != null) {
                chapters.add(title to paragraphs)
                paragraphs = ArrayList()
            }
            // The title carries onto the continuation chapter on purpose —
            // clearing it here is what split one Gutenberg chapter into two.
        }

        for (block in blocks) {
            val level = headingLevel(block)
            if (level != null) {
                val headingText = tidyHeading(block)
                if (headingText.isEmpty()) continue
                // A heading closes whatever is open. Level 1–2 always opens a
                // chapter; deeper ones only when nothing is open (documents
                // whose only structure is h3+).
                if (level <= 2 || title == null) {
                    flush()
                    title = headingText
                } else {
                    paragraphs.add(headingText)
                    if (paragraphs.size >= CHAPTER_BLOCK_LIMIT) flush()
                }
                continue
            }
            paragraphs.add(block)
            if (paragraphs.size >= CHAPTER_BLOCK_LIMIT) flush()
        }
        flush()

        if (chapters.isEmpty()) return listOf(null to emptyList())
        return chapters
    }

    /** A heading's spoken text: the `#` markers go, the words stay. */
    private fun tidyHeading(block: String): String {
        if (!block.startsWith("#")) return block
        return stripMarkdownInline(block.trimStart('#')).trim()
    }

    /** Paragraph blocks: blank-line separated, hard-wrapped lines joined.
     *  Three things end a block: a blank line, a Markdown heading, and a
     *  line that ENDS A SENTENCE — a hard-wrapped paragraph only continues
     *  onto the next line while its sentence is unfinished. Joining
     *  unconditionally glued a Gutenberg running heading to the prose under
     *  it. */
    private fun textBlocks(text: String): List<String> {
        val out = ArrayList<String>()
        val paragraph = ArrayList<String>()

        fun flush() {
            if (paragraph.isEmpty()) return
            val joined = paragraph.joinToString(" ")
            paragraph.clear()
            val cleaned = stripMarkdownInline(joined).trim()
            if (cleaned.isNotEmpty()) out.add(cleaned)
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                flush()
                continue
            }
            // A Markdown heading is its own block, never joined to the
            // paragraph above it — and so is a short running/numbered
            // heading ("Chapter 7", "PART TWO"), even when prose follows it
            // on the very next line.
            if (line.startsWith("#") || headingLevel(line) != null) {
                flush()
                out.add(line)
                continue
            }
            val previous = paragraph.lastOrNull()
            if (previous != null && endsSentence(previous)) {
                flush()
            }
            paragraph.add(line)
        }
        flush()
        return out
    }

    /** True when a line ends a sentence, so the next line opens a new
     *  paragraph rather than continuing this one. Closing punctuation after
     *  the full stop still counts — `"Go," she said.` ends the sentence. */
    private fun endsSentence(line: String): Boolean {
        for (c in line.reversed()) {
            if (c.isWhitespace()) continue
            // ! ? . ” " ) ]  — closing punctuation after the full stop still
            // counts ("Go," she said.).
            return c == '!' || c == '?' || c == '.' || c == '”' || c == '"' || c == ')' || c == ']'
        }
        return false
    }

    /** The heading level a block opens, or `null` when it is prose. `#`/`##`
     *  win; a short line matching the Gutenberg running-heading vocabulary,
     *  or a numbered short line, comes next. */
    internal fun headingLevel(block: String): Int? {
        if (block.startsWith("#")) {
            val level = block.takeWhile { it == '#' }.length
            return level.coerceIn(1, 6)
        }
        val len = block.length
        if (len !in 2..80) return null
        if (isRunningHeading(block) || isNumberedHeading(block)) return 2
        return null
    }

    /** "Chapter 7", "CHAPTER VII.", "Part Two", "BOOK THE SECOND", "Act I",
     *  "Letter 3", "Canto XXIV" — the running headings of the Gutenberg
     *  corpus, matched as a whole short line. */
    private fun isRunningHeading(line: String): Boolean {
        val words = arrayOf(
            "chapter", "part", "book", "section", "act", "scene", "letter", "canto",
            "prologue", "epilogue", "preface", "introduction", "afterword", "foreword",
        )
        val lower = line.lowercase()
        for (word in words) {
            if (!lower.startsWith(word)) continue
            val rest = lower.substring(word.length)
            // Word boundary: the character after the word must not continue
            // it ("chapterhouse" is a noun, not a heading).
            val first = rest.firstOrNull()
            if (first != null && (first.isLetterOrDigit() || first == '_')) continue
            if (rest.length <= 60) return true
        }
        return false
    }

    /** "I.", "12.", "VII — The Letter", "3. The Garden" — a roman-numeral or
     *  1–3-digit head, an optional one of `. : ) — -`, whitespace, then at
     *  most 60 characters of title.
     *
     *  One guard the reference regex did not carry: on the *no-separator*
     *  path the first word after the head must start an uppercase letter or
     *  a symbol — otherwise every prose line that begins "I was three
     *  months…" or "12 to 15 people…" parses as a heading and splits the
     *  chapter mid-sentence. */
    private fun isNumberedHeading(line: String): Boolean {
        val headLen = line.takeWhile {
            it in "IVXLCivxlc" || it in '0'..'9'
        }.length
        if (headLen == 0) return false
        val head = line.substring(0, headLen)
        val digitsOnly = head.all { it in '0'..'9' }
        // Digit heads are exactly 1–3 digits; roman heads any length the
        // line's own 80-char bound allows.
        if (digitsOnly && headLen > 3) return false
        var rest = line.substring(headLen)
        // One optional separator from the set, then required whitespace.
        var separated = false
        if (rest.isNotEmpty() && rest[0] in ".:)-—" && rest[0] != ' ') {
            val c = rest[0]
            if (c == '.' || c == ':' || c == ')' || c == '—' || c == '-') {
                rest = rest.substring(1)
                separated = true
            }
        }
        if (rest.isEmpty() || (rest[0] != ' ' && rest[0] != '\t')) return false
        rest = rest.trimStart(' ', '\t')
        if (rest.isEmpty() || rest.length > 60) return false
        // The guard: a bare head ("I", "12") with no separator only heads a
        // title-case line.
        val first = rest.first()
        return separated || !first.isLowerCase()
    }

    /** `**bold**`, `*em*`, backticks, `[text](url)` and bare URLs — the
     *  reader speaks the words. Links first, so the label survives and the
     *  target goes. Underscore runs only disappear at word boundaries, so
     *  `snake_case` survives. */
    fun stripMarkdownInline(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            // Markdown links and images: `[label](target)` keeps the label;
            // `![alt](target)` goes entirely.
            if (c == '[' || (c == '!' && text.getOrNull(index + 1) == '[')) {
                val labelStart = if (c == '[') index else index + 1
                val link = matchLink(text, labelStart)
                if (link != null) {
                    val (labelEnd, targetEnd) = link
                    if (c == '!') {
                        // An image is one gap: the space it becomes must not
                        // double the whitespace around it.
                        index = pushSpaceGap(out, text, targetEnd)
                    } else {
                        out.append(text, labelStart + 1, labelEnd)
                        index = targetEnd
                    }
                    continue
                }
            }
            // Runs of 1–3 emphasis markers disappear — except an underscore,
            // which must sit at a word boundary on both sides.
            if (c == '*' || c == '`' || c == '_') {
                val run = text.substring(index).takeWhile { it == c }.length
                val boundaryBefore = out.isEmpty() || !out.last().isLetterOrDigit()
                val afterRun = text.getOrNull(index + run)
                val boundaryAfter = afterRun == null || !afterRun.isLetterOrDigit()
                if (c != '_' || (boundaryBefore && boundaryAfter)) {
                    index += minOf(run, 3)
                    continue
                }
            }
            // A bare URL goes, to the next whitespace — one gap, not three.
            if (text.startsWith("http://", index) || text.startsWith("https://", index)) {
                var end = index
                while (end < text.length && !text[end].isWhitespace()) end++
                index = pushSpaceGap(out, text, end)
                continue
            }
            out.append(c)
            index++
        }
        return out.toString()
    }

    /** Push the one-space gap a removal leaves behind, without doubling the
     *  whitespace that already surrounds the removed span. Returns where
     *  scanning resumes. */
    private fun pushSpaceGap(out: StringBuilder, text: String, at: Int): Int {
        var index = at
        while (index < text.length && text[index].isWhitespace()) index++
        if (out.isEmpty() || !out.last().isWhitespace()) out.append(' ')
        return index
    }

    /** `(labelEnd, targetEnd)` for the `[label](target)` starting at [open]
     *  (the `[`'s own offset), when the brackets and parens all close. */
    private fun matchLink(text: String, open: Int): Pair<Int, Int>? {
        var labelEnd = -1
        var depth = 0
        for (offset in open until text.length) {
            when (text[offset]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        labelEnd = offset
                        break
                    }
                }
            }
        }
        if (labelEnd < 0) return null
        if (text.getOrNull(labelEnd + 1) != '(') return null
        val targetOpen = labelEnd + 1
        for (offset in targetOpen + 1 until text.length) {
            val c = text[offset]
            if (c == ')') return labelEnd to (offset + 1)
            // A `(` inside the target would mean we matched the wrong pair;
            // real links do not nest their targets.
            if (c == '(') return null
        }
        return null
    }
}
