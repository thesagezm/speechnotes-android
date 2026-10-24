package com.speechnotes.app.logic

/**
 * Markdown → speech text + reading-view blocks.
 *
 * One scanner ([blocks]) drives both outputs:
 *  - [plainText] — syntax-stripped text for TTS and the read-along view
 *    (what the engine hears == what the read-along shows, so SentenceChunker
 *    offsets stay in sync).
 *  - [blocks] — structured blocks for the preview; inline syntax stays
 *    intact for the display layer to style.
 *
 * Ported from MarkdownText.swift (speechnotes-ios, SpeechLogic). Handles
 * ATX/setext headings, fenced code (matched fence char/length, language
 * captured), lists with nesting ranks + task markers + lazy continuation,
 * blockquotes, tables, reference links/images, autolinks, footnote-lite,
 * thematic breaks, images, HTML comments/tags, backslash escapes.
 * Unrecognized syntax passes through untouched.
 */
object MarkdownText {

    sealed class MarkdownBlock {
        data class Heading(val level: Int, val text: String) : MarkdownBlock()
        /** Single line breaks are CONTENT here. */
        data class Paragraph(val text: String) : MarkdownBlock()
        data class BulletList(val items: List<ListItem>) : MarkdownBlock()
        data class OrderedList(val items: List<ListItem>) : MarkdownBlock()
        data class Quote(val text: String) : MarkdownBlock()
        data class Code(val language: String?, val text: String) : MarkdownBlock()
        data object Divider : MarkdownBlock()
        data class Image(val alt: String, val url: String) : MarkdownBlock()
        data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    }

    /**
     * A list item. [level] is the nesting rank (0 = top level), computed
     * relative to the other indents in the same list — so 2-space and
     * 4-space nesting both work.
     */
    data class ListItem(
        val level: Int = 0,
        val text: String,
        val isTask: Boolean = false,
        val isDone: Boolean = false,
    )

    sealed class InlineRun {
        data class Text(val text: String) : InlineRun()
        data class Image(val alt: String, val url: String) : InlineRun()
        data class Link(val label: String, val url: String) : InlineRun()
    }

    data class LinkReference(val label: String, val url: String, val title: String?)

    // MARK: - Regex cache (compiled once, reused)

    private val regexCache = HashMap<String, Regex>()
    private val cacheLock = Any()

    private fun rx(pattern: String, options: Set<RegexOption> = emptySet()): Regex {
        val key = "$options|$pattern"
        synchronized(cacheLock) {
            regexCache[key]?.let { return it }
        }
        val compiled = try {
            Regex(pattern, options)
        } catch (e: IllegalArgumentException) {
            Regex(Regex.escape(pattern))
        }
        synchronized(cacheLock) { regexCache[key] = compiled }
        return compiled
    }

    private val INT_REPLACE = Regex("\\$(\\d+)")

    private fun replace(input: String, pattern: String, template: String, options: Set<RegexOption> = emptySet()): String =
        rx(pattern, options).replace(input) { m ->
            INT_REPLACE.replace(template) { rm ->
                val idx = rm.groupValues[1].toIntOrNull() ?: 0
                if (idx in m.groupValues.indices) m.groupValues[idx] else ""
            }
        }

    /** Like [replace] but the replacement is computed per match. */
    private fun replaceMatches(input: String, pattern: String, handler: (MatchResult) -> String): String {
        val regex = rx(pattern)
        val out = StringBuilder(input.length)
        var cursor = 0
        for (m in regex.findAll(input)) {
            if (m.range.first < cursor) continue
            if (m.range.first > cursor) out.append(input, cursor, m.range.first)
            out.append(handler(m))
            cursor = m.range.last + 1
        }
        if (cursor < input.length) out.append(input, cursor, input.length)
        return out.toString()
    }

    private fun normalize(markdown: String): String =
        markdown.replace("\r\n", "\n").replace("\r", "\n")

    // MARK: - Speech text

    /**
     * Markdown → plain text for speech + read-along. Task items read
     * "To do: …" / "Done: …", tables read row-wise, code is kept verbatim.
     * The result passes through [SpeechSanitizer.clean] last.
     */
    fun plainText(markdown: String): String {
        val refs = linkReferences(markdown)
        val parsed = blocks(markdown, refs)
        val spoken = parsed.mapNotNull { block ->
            val text = speechText(block, refs)
            if (text.isEmpty()) null else text
        }.joinToString("\n\n")
        return SpeechSanitizer.clean(spoken)
    }

    private fun speechText(block: MarkdownBlock, refs: Map<String, LinkReference>): String = when (block) {
        is MarkdownBlock.Heading -> speechInline(block.text, refs)
        is MarkdownBlock.Paragraph -> speechInline(block.text, refs)
        is MarkdownBlock.BulletList -> block.items.joinToString("\n") { item ->
            val body = speechInline(item.text, refs)
            if (item.isTask) (if (item.isDone) "Done: " else "To do: ") + body else body
        }
        is MarkdownBlock.OrderedList -> block.items.joinToString("\n") { item ->
            val body = speechInline(item.text, refs)
            if (item.isTask) (if (item.isDone) "Done: " else "To do: ") + body else body
        }
        is MarkdownBlock.Quote -> speechInline(block.text, refs)
        is MarkdownBlock.Code -> block.text
        is MarkdownBlock.Divider -> ""
        is MarkdownBlock.Image -> speechInline(block.alt, refs)
        is MarkdownBlock.Table -> {
            val lines = ArrayList<String>(block.rows.size + 1)
            lines.add(block.headers.joinToString(", ") { speechInline(it, refs) })
            for (row in block.rows) lines.add(row.joinToString(", ") { speechInline(it, refs) })
            lines.joinToString("\n")
        }
    }

    /** Strips inline syntax from one chunk of text. Escape-protected. */
    fun speechInline(text: String, references: Map<String, LinkReference> = emptyMap()): String {
        if (text.isEmpty()) return text

        // 1. Protect backslash escapes so `\*` never looks like emphasis.
        val (line0, stash) = protectEscapes(text)
        var line = line0

        // 2. Footnote markers are silent.
        line = replace(line, """\[\^[^\]]+\]""", "")

        // 3. Images → alt text (inline and reference forms).
        line = replaceMatches(line, """!\[([^\]]*)\](?:\([^)]*\)|\[([^\]]*)\])""") { m -> m.groupValues[1] }

        // 4. Links → label (inline and reference forms, including unresolved
        // reference links — the raw `[label][key]` form reads as bracket soup).
        line = replaceMatches(line, """(?<!!)\[([^\]]+)\](?:\([^)]*\)|\[([^\]]*)\])""") { m -> m.groupValues[1] }

        // 5. Autolinks → bare URL.
        line = replace(line, """<((?:https?://|mailto:)[^>\s]+)>""", "$1")
        line = replace(line, """<([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,})>""", "$1")

        // 6. Code spans → content.
        line = replace(line, """`([^`]+)`""", "$1")

        // 7. Emphasis markers (loop until stable, bounded).
        for (i in 0 until 6) {
            var next = line
            for ((pattern, template) in emphasisRules) next = replace(next, pattern, template)
            if (next == line) break
            line = next
        }

        // 8. HTML: <br> becomes a line break; other tags vanish.
        line = replace(line, """<br\s*/?>""", "\n", setOf(RegexOption.IGNORE_CASE))
        line = replace(line, """</?[A-Za-z][^>]*>""", "")

        // 9. Restore escapes.
        return restoreEscapes(line, stash)
    }

    private val emphasisRules: List<Pair<String, String>> = listOf(
        """\*\*\*([^*]+)\*\*\*""" to "$1",
        """\*\*([^*]+)\*\*""" to "$1",
        """__([^_]+)__""" to "$1",
        """(?<![*\w])\*([^*\s][^*]*)\*(?!\*)""" to "$1",
        """(?<![\w_])_([^_\s][^_]*)_(?![\w_])""" to "$1",
        """~~([^~]+)~~""" to "$1",
    )

    private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private const val ESCAPE_SENTINEL = '\u0001'

    /** `\X` → invisible sentinel + index, so escaped markers can't be mistaken for syntax. */
    private fun protectEscapes(line: String): Pair<String, List<String>> {
        if (!line.contains('\\')) return line to emptyList()
        val stash = ArrayList<String>()
        val out = StringBuilder(line.length)
        var index = 0
        while (index < line.length) {
            val character = line[index]
            if (character == '\\') {
                val next = index + 1
                if (next < line.length && line[next] in ASCII_PUNCTUATION) {
                    stash.add(line[next].toString())
                    out.append(ESCAPE_SENTINEL).append(stash.size - 1)
                    index += 2
                    continue
                }
            }
            out.append(character)
            index++
        }
        return out.toString() to stash
    }

    private fun restoreEscapes(line: String, stash: List<String>): String {
        if (stash.isEmpty() || !line.contains(ESCAPE_SENTINEL)) return line
        val out = StringBuilder(line.length)
        var index = 0
        while (index < line.length) {
            if (line[index] == ESCAPE_SENTINEL) {
                var digits = ""
                var scan = index + 1
                while (scan < line.length && line[scan].isDigit() && line[scan].code < 128) {
                    digits += line[scan]; scan++
                }
                val n = digits.toIntOrNull()
                if (n != null && n < stash.size) {
                    out.append(stash[n]); index = scan; continue
                }
            }
            out.append(line[index]); index++
        }
        return out.toString()
    }

    // MARK: - Blocks (reading view)

    fun blocks(markdown: String): List<MarkdownBlock> = blocks(markdown, linkReferences(markdown))

    fun blocks(markdown: String, refs: Map<String, LinkReference>): List<MarkdownBlock> {
        val lines = normalize(markdown).split("\n")
        val result = ArrayList<MarkdownBlock>()
        val paragraph = ArrayList<String>()
        val quote = ArrayList<String>()

        class PendingItem(val indent: Int, var text: String, val isTask: Boolean = false, val isDone: Boolean = false)
        val bullets = ArrayList<PendingItem>()
        val ordered = ArrayList<PendingItem>()
        val codeLines = ArrayList<String>()
        var codeLanguage: String? = null
        var fenceChar: Char? = null
        var fenceLength = 0
        var inHTMLComment = false

        fun finalized(item: PendingItem, siblings: List<PendingItem>): ListItem {
            val distinct = siblings.map { it.indent }.toSortedSet()
            return ListItem(level = distinct.indexOf(item.indent).coerceAtLeast(0), text = item.text, isTask = item.isTask, isDone = item.isDone)
        }
        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val joined = paragraph.joinToString("\n")
            val img = standaloneImage(joined, refs)
            if (img != null) result.add(MarkdownBlock.Image(img.first, img.second))
            else result.add(MarkdownBlock.Paragraph(joined))
            paragraph.clear()
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) result.add(MarkdownBlock.Quote(quote.joinToString("\n")))
            quote.clear()
        }
        fun flushLists() {
            if (bullets.isNotEmpty()) result.add(MarkdownBlock.BulletList(bullets.map { finalized(it, bullets) }))
            bullets.clear()
            if (ordered.isNotEmpty()) result.add(MarkdownBlock.OrderedList(ordered.map { finalized(it, ordered) }))
            ordered.clear()
        }
        fun flushCode() {
            result.add(MarkdownBlock.Code(codeLanguage, codeLines.joinToString("\n")))
            codeLines.clear(); codeLanguage = null
        }
        fun flushAll() { flushParagraph(); flushQuote(); flushLists() }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            i++
            val trimmedRaw = raw.trim()

            // ---- Fences (matched char + length) ----
            if (fenceChar != null) {
                val info = fenceInfo(trimmedRaw)
                if (info != null && info.char == fenceChar && info.length >= fenceLength && info.canClose) {
                    fenceChar = null
                    flushCode()
                } else {
                    codeLines.add(raw)
                }
                continue
            }
            val openInfo = fenceInfo(trimmedRaw)
            if (openInfo != null) {
                flushAll()
                fenceChar = openInfo.char
                fenceLength = openInfo.length
                codeLanguage = languageFrom(openInfo.info)
                continue
            }

            // ---- HTML comments (single and multi-line) ----
            var line = raw
            if (inHTMLComment) {
                val close = line.indexOf("-->")
                if (close >= 0) { inHTMLComment = false; line = line.substring(close + 3) }
                else continue
            }
            val open = line.indexOf("<!--")
            if (open >= 0) {
                val close = line.indexOf("-->", open + 4)
                line = if (close >= 0) line.substring(0, open) + line.substring(close + 3)
                else { inHTMLComment = true; line.substring(0, open) }
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { flushAll(); continue }

            // ---- Setext underline ----
            if (paragraph.isNotEmpty() && isSetextUnderline(trimmed)) {
                flushQuote(); flushLists()
                result.add(MarkdownBlock.Heading(if (trimmed.startsWith("=")) 1 else 2, paragraph.joinToString("\n")))
                paragraph.clear()
                continue
            }

            // ---- Thematic break ----
            if (isThematicBreak(trimmed)) { flushAll(); result.add(MarkdownBlock.Divider); continue }

            // ---- Footnote definition (reads as a paragraph) / link refs (dropped) ----
            val footnote = footnoteBody(trimmed)
            if (footnote != null) { flushAll(); paragraph.add(footnote); continue }
            if (isLinkReferenceDefinition(trimmed)) { flushAll(); continue }

            // ---- ATX heading (closing #'s stripped) ----
            val heading = headingInfo(trimmed)
            if (heading != null) { flushAll(); result.add(MarkdownBlock.Heading(heading.first, heading.second)); continue }

            // ---- Table: this line is the header, next line the delimiter row ----
            if (trimmed.contains("|") && i < lines.size && isTableDelimiterRow(lines[i].trim())) {
                flushParagraph(); flushQuote(); flushLists()
                val headers = tableCells(trimmed)
                i++
                val rows = ArrayList<List<String>>()
                while (i < lines.size) {
                    val row = lines[i].trim()
                    if (row.isEmpty() || !row.contains("|")) break
                    rows.add(tableCells(row))
                    i++
                }
                result.add(MarkdownBlock.Table(headers, rows))
                continue
            }

            // ---- Blockquote ----
            if (trimmed.startsWith(">")) {
                flushParagraph(); flushLists()
                var content = trimmed
                while (content.startsWith(">")) content = content.substring(1).trim()
                quote.add(content)
                continue
            }

            // ---- Lists ----
            val indent = leadingSpaces(raw)
            val bullet = bulletItem(trimmed)
            if (bullet != null) {
                flushParagraph(); flushQuote()
                if (ordered.isNotEmpty()) flushLists()
                bullets.add(PendingItem(indent, bullet.text, bullet.isTask, bullet.isDone))
                continue
            }
            val ord = orderedItem(trimmed)
            if (ord != null) {
                flushParagraph(); flushQuote()
                if (bullets.isNotEmpty()) flushLists()
                ordered.add(PendingItem(indent, ord.text, ord.isTask, ord.isDone))
                continue
            }

            // ---- Lazy continuation ----
            if (paragraph.isEmpty()) {
                if (bullets.isNotEmpty() && indent > bullets.last().indent) {
                    bullets.last().text += "\n" + trimmed
                    continue
                }
                if (ordered.isNotEmpty() && indent > ordered.last().indent) {
                    ordered.last().text += "\n" + trimmed
                    continue
                }
                if (quote.isNotEmpty()) { quote.add(trimmed); continue }
            }

            // ---- Paragraph ----
            flushQuote(); flushLists()
            paragraph.add(trimmed)
        }

        if (fenceChar != null) flushCode()
        flushAll()
        return result
    }

    // MARK: - Inline runs (preview)

    fun inlineRuns(line: String, references: Map<String, LinkReference> = emptyMap()): List<InlineRun> {
        val imageRx = rx("""!\[([^\]]*)\](?:\(([^)]*)\)|\[([^\]]*)\])""")
        val matches = imageRx.findAll(line).toList()
        if (matches.isEmpty()) return linkRuns(line, references)
        return splitRuns(line, matches, make = { m ->
            val alt = m.groupValues[1]
            val url = if (m.groupValues[2].isNotEmpty()) cleanURL(m.groupValues[2])
            else if (m.groupValues[3].isNotEmpty()) references[(m.groupValues[3].ifEmpty { alt }).lowercase()]?.url ?: ""
            else ""
            if (url.isEmpty()) listOf(InlineRun.Text(m.value))
            else listOf(InlineRun.Image(alt, url))
        }, textSegment = { linkRuns(it, references) }) ?: linkRuns(line, references)
    }

    private fun linkRuns(text: String, references: Map<String, LinkReference>): List<InlineRun> {
        val autos = rx("""<((?:https?://|mailto:)[^>\s]+)>""").findAll(text).toList()
        if (autos.isNotEmpty()) {
            val runs = splitRuns(text, autos, make = { m ->
                val url = m.groupValues[1]
                listOf(InlineRun.Link(url, url))
            }, textSegment = { bracketLinkRuns(it, references) })
            if (runs != null) return runs
        }
        return bracketLinkRuns(text, references)
    }

    private fun bracketLinkRuns(text: String, references: Map<String, LinkReference>): List<InlineRun> {
        val bracketRx = rx("""\[([^\]]+)\](?:\(([^)]*)\)|\[([^\]]*)\])""")
        val matches = bracketRx.findAll(text).toList()
        if (matches.isEmpty()) return listOf(InlineRun.Text(text))
        return splitRuns(text, matches, make = { m ->
            val label = m.groupValues[1]
            val url = if (m.groupValues[2].isNotEmpty()) cleanURL(m.groupValues[2])
            else if (m.groupValues[3].isNotEmpty()) references[(m.groupValues[3].ifEmpty { label }).lowercase()]?.url ?: ""
            else ""
            if (url.isEmpty()) listOf(InlineRun.Text(m.value))
            else listOf(InlineRun.Link(label, url))
        }) ?: listOf(InlineRun.Text(text))
    }

    /** Shared splitter: emits [textSegment] between matches, [make] for each match. */
    private fun splitRuns(
        text: String,
        matches: List<MatchResult>,
        make: (MatchResult) -> List<InlineRun>,
        textSegment: (String) -> List<InlineRun> = { listOf(InlineRun.Text(it)) },
    ): List<InlineRun>? {
        if (matches.isEmpty()) return null
        val runs = ArrayList<InlineRun>()
        var cursor = 0
        for (m in matches) {
            if (m.range.first > cursor) {
                val before = text.substring(cursor, m.range.first)
                if (before.isNotEmpty()) runs.addAll(textSegment(before))
            }
            if (m.value.isNotEmpty()) runs.addAll(make(m))
            cursor = m.range.last + 1
        }
        if (cursor < text.length) {
            val tail = text.substring(cursor)
            if (tail.isNotEmpty()) runs.addAll(textSegment(tail))
        }
        return runs
    }

    private fun cleanURL(raw: String): String {
        var url = raw.trim()
        val quote = url.indexOf('"')
        if (quote >= 0) url = url.substring(0, quote).trim()
        if (url.length >= 2 && url.startsWith("<") && url.endsWith(">")) url = url.substring(1, url.length - 1)
        return url
    }

    private fun standaloneImage(joined: String, refs: Map<String, LinkReference>): Pair<String, String>? {
        val trimmed = joined.trim()
        val runs = inlineRuns(trimmed, refs)
        if (runs.size == 1 && runs[0] is InlineRun.Image) return (runs[0] as InlineRun.Image).alt to (runs[0] as InlineRun.Image).url
        return null
    }

    // MARK: - Reference definitions

    fun linkReferences(markdown: String): Map<String, LinkReference> {
        val map = HashMap<String, LinkReference>()
        val rx = rx("""^[ \t]{0,3}\[([^\]^][^\]]*)\]:[ \t]*(?:<([^<>]+)>|(\S+))(?:[ \t]+(?:"([^"]*)"|'([^']*)'|\(([^)]*)\)))?[ \t]*$""")
        var fenceChar: Char? = null
        for (rawLine in normalize(markdown).split("\n")) {
            val line = rawLine.trim()
            val info = fenceInfo(line)
            if (info != null) {
                if (fenceChar == null) fenceChar = info.char
                else if (info.char == fenceChar && info.canClose) fenceChar = null
                continue
            }
            if (fenceChar != null) continue
            val m = rx.find(line) ?: continue
            val label = m.groupValues[1]
            val url = m.groupValues[2].ifEmpty { m.groupValues[3] }
            if (url.isEmpty()) continue
            val title = m.groupValues[4].ifEmpty { m.groupValues[5] }.ifEmpty { m.groupValues[6] }.ifEmpty { null }
            map[label.lowercase()] = LinkReference(label, url, title)
        }
        return map
    }

    // MARK: - Token scanners (editing layer)

    /** Every link (inline, reference, resolved) as (label, url, range). */
    fun linkTargets(markdown: String): List<Triple<String, String, IntRange>> {
        val results = ArrayList<Triple<String, String, IntRange>>()
        val rx = rx("""(?<!!)\[([^\]]+)\](?:\(([^)]*)\)|\[([^\]]*)\])""")
        val refs = linkReferences(markdown)
        for (m in rx.findAll(markdown)) {
            val label = m.groupValues[1]
            val url = if (m.groupValues[2].isNotEmpty()) cleanURL(m.groupValues[2])
            else if (m.groupValues[3].isNotEmpty()) refs[(m.groupValues[3].ifEmpty { label }).lowercase()]?.url ?: ""
            else ""
            if (url.isEmpty()) continue
            results.add(Triple(label, url, m.range))
        }
        return results
    }

    /** Every image token (inline and reference, resolved) as (alt, url, range). */
    fun imageTokens(markdown: String): List<Triple<String, String, IntRange>> {
        val results = ArrayList<Triple<String, String, IntRange>>()
        val rx = rx("""!\[([^\]]*)\](?:\(([^)]*)\)|\[([^\]]*)\])""")
        val refs = linkReferences(markdown)
        for (m in rx.findAll(markdown)) {
            val alt = m.groupValues[1]
            val url = if (m.groupValues[2].isNotEmpty()) cleanURL(m.groupValues[2])
            else if (m.groupValues[3].isNotEmpty()) refs[(m.groupValues[3].ifEmpty { alt }).lowercase()]?.url ?: ""
            else ""
            if (url.isEmpty()) continue
            results.add(Triple(alt, url, m.range))
        }
        return results
    }

    // MARK: - Line-level helpers

    private class FenceInfo(val char: Char, val length: Int, val info: String, val canClose: Boolean)

    private fun fenceInfo(line: String): FenceInfo? {
        val first = line.firstOrNull() ?: return null
        if (first != '`' && first != '~') return null
        var count = 0
        var idx = 0
        while (idx < line.length && line[idx] == first) { count++; idx++ }
        if (count < 3) return null
        val info = line.substring(idx).trim()
        return FenceInfo(first, count, info, info.isEmpty())
    }

    private fun languageFrom(info: String): String? {
        val trimmed = info.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.split(" ").first()
    }

    private fun headingInfo(line: String): Pair<Int, String>? {
        if (!line.startsWith("#")) return null
        var marks = 0
        var idx = 0
        while (idx < line.length && line[idx] == '#') { marks++; idx++ }
        if (marks !in 1..6) return null
        if (idx >= line.length || (line[idx] != ' ' && line[idx] != '\t')) return null
        var text = line.substring(idx).trim()
        val closing = rx("""[ \t]+#+[ \t]*$""").find(text)
        if (closing != null) text = text.substring(0, closing.range.first).trim()
        return marks to text
    }

    private fun isSetextUnderline(line: String): Boolean {
        val first = line.firstOrNull() ?: return false
        if (first != '=' && first != '-') return false
        return line.all { it == first }
    }

    private fun isThematicBreak(line: String): Boolean {
        if (line.length < 3) return false
        val first = line[0]
        if (first != '-' && first != '*' && first != '_') return false
        var runs = 0
        for (c in line) {
            if (c == first) runs++
            else if (c != ' ' && c != '\t') return false
        }
        return runs >= 3
    }

    private fun footnoteBody(line: String): String? {
        val m = rx("""^\[\^[^\]]+\]:[ \t]*(.*)$""").find(line) ?: return null
        return m.groupValues[1]
    }

    private fun isLinkReferenceDefinition(line: String): Boolean =
        rx("""^\[[^\]^][^\]]*\]\:""").find(line) != null

    private fun tableCells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split("|").map { it.trim() }
    }

    private fun isTableDelimiterRow(line: String): Boolean {
        if (!line.contains("|")) return false
        val cells = tableCells(line)
        if (cells.isEmpty()) return false
        return cells.all { cell ->
            cell.isNotEmpty() && cell.contains("-") && cell.all { it == '-' || it == ':' }
        }
    }

    private data class TaskMarker(val isTask: Boolean, val isDone: Boolean, val remainder: String)

    private fun taskMarker(text: String): TaskMarker {
        if (text.length < 3 || text[0] != '[' || text[2] != ']') return TaskMarker(false, false, text)
        return when (text[1]) {
            ' ' -> TaskMarker(true, false, text.substring(3).trim())
            'x', 'X' -> TaskMarker(true, true, text.substring(3).trim())
            else -> TaskMarker(false, false, text)
        }
    }

    private data class ListItemMatch(val text: String, val isTask: Boolean, val isDone: Boolean)

    private fun bulletItem(line: String): ListItemMatch? {
        val first = line.firstOrNull() ?: return null
        if (first != '-' && first != '*' && first != '+') return null
        if (line.length < 2 || (line[1] != ' ' && line[1] != '\t')) return null
        val text = line.substring(1).trim()
        val task = taskMarker(text)
        return ListItemMatch(task.remainder, task.isTask, task.isDone)
    }

    private fun orderedItem(line: String): ListItemMatch? {
        val m = rx("""^\d{1,9}[.)][ \t]+""").find(line) ?: return null
        val text = line.substring(m.value.length).trim()
        val task = taskMarker(text)
        return ListItemMatch(task.remainder, task.isTask, task.isDone)
    }

    private fun leadingSpaces(line: String): Int {
        var count = 0
        for (c in line) {
            if (c == ' ') count++
            else if (c == '\t') count += 4
            else break
        }
        return count
    }
}
