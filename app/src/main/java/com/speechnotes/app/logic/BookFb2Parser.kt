package com.speechnotes.app.logic

/**
 * FictionBook (`.fb2`) — the format the largest free Russian-language ebook
 * libraries ship in, and the friendliest non-EPUB format to read because it
 * is plain XML. Ported from speechnotes-linux-rust
 * `crates/sn-doc/src/fb2.rs`.
 *
 * What the parser takes:
 *
 * - `<description><title-info>` for title, author and language, so the
 *   shelf shows the book's own metadata rather than the file name. FB2
 *   names an author twice — `<title-info>` (the book) and
 *   `<document-info>` (the file's provenance) — and only the first is the
 *   author the shelf shows.
 * - `<body>` sections. A `<title>` carrying `<level>1`–`2` opens a
 *   chapter; deeper titles stay inside as prose. `<subtitle>` joins its
 *   title after an em dash, the way the books print it.
 * - `<table>` becomes a real `<table>` the chapter XHTML carries.
 * - `<binary>` payloads (base64) referenced by `<image l:href="#id">`
 *   become the book's images; the one named in `<coverpage>` is the shelf
 *   cover. A payload nothing references, or one the reader cannot show
 *   (SVG), ships no bytes.
 * - Verse (`<v>`) reads as paragraphs, one per line.
 *
 * ## The de-facto heading fallback
 *
 * Plenty of FB2 files carry no `<title>` elements at all — the body is a
 * flat run of `<section>`s that begin with a bold line. The first
 * paragraph of a section is treated as a heading when it is *entirely*
 * inside `<strong>`/`<emphasis>`, short and not sentence-punctuated: the
 * convention in every such file, and the document's own words either way.
 */
object BookFb2Parser {

    /** Bound on one chapter's items — a section-less FB2 chunks the same
     *  way a heading-less text file does. */
    private const val CHAPTER_ITEM_LIMIT = 150

    /** Parse a `.fb2` file.
     *
     *  A parse that aborted with something collected keeps what it
     *  collected — half a chapter beats none, and FB2 in the wild is often
     *  slightly malformed. An empty parse is a refused import. */
    fun parse(data: ByteArray): BookDoc {
        val text = decode(data)
        val reader = Fb2Reader()
        val parser = BookXml.Parser(text)
        while (true) {
            val event = try {
                parser.nextEvent()
            } catch (e: BookXml.XmlError) {
                break // half a chapter beats none
            } ?: break
            reader.handle(event)
        }
        return reader.finish()
    }

    /** The encoding the XML declaration names, decoded with the shared
     *  codepage tables. FB2 files are UTF-8 or windows-1251 in practice. */
    fun decode(data: ByteArray): String {
        val head = String(data, 0, minOf(data.size, 1024), Charsets.UTF_8)
        val name = declaredEncoding(head)
        if (name != null) {
            BookCodepages.decodeNamed(name, data)?.let { return it }
        }
        return String(data, Charsets.UTF_8)
    }

    /** The value of `encoding="…"` in an XML declaration, when the head of
     *  the document carries one. */
    private fun declaredEncoding(head: String): String? {
        val at = head.indexOf("encoding")
        if (at < 0) return null
        val after = head.substring(at + "encoding".length)
        val equals = after.indexOf('=')
        if (equals < 0) return null
        val rest = after.substring(equals + 1).trimStart()
        val quote = rest.firstOrNull() ?: return null
        if (quote != '"' && quote != '\'') return null
        val end = rest.substring(1).indexOf(quote)
        if (end < 0) return null
        return rest.substring(1, end + 1)
    }

    /** One thing the reader collected, in document order. */
    private sealed class Item {
        class Heading(val text: String, val level: Int) : Item()
        /** A paragraph's XHTML (already escaped). */
        class Paragraph(val html: String) : Item()
        class Table(val rows: List<List<Cell>>) : Item()
        /** An `<image>` reference by its FB2 id; resolved against the decoded
         *  binaries when the parse ends, dropped when nothing answers. */
        class Image(val id: String) : Item()
    }

    private class Cell(val text: String, val header: Boolean)

    /** Where the next paragraph's words belong. */
    private enum class Pending {
        /** A `<p>` in the body: the words are a paragraph. */
        Body,
        /** A `<p>` directly inside a `<title>`: the words ARE its title. */
        Title,
        /** A `<subtitle>`: joins the title it follows. */
        Subtitle,
    }

    /** The streaming reader. FB2 files run to tens of megabytes, so nothing
     *  is held but the item stream and the decoded images. */
    private class Fb2Reader {
        val items = ArrayList<Item>()

        // Title state.
        private var titleDepth = 0
        private var titleLevel = 1
        private var pending = Pending.Body

        // The paragraph being built: its XHTML, its plain text, and whether
        // an emphasis element wrapped the whole thing.
        private val paragraphHtml = StringBuilder()
        private val paragraphPlain = StringBuilder()
        private var inParagraph = false
        private var emphasisDepth = 0
        /** Text sat outside every emphasis element — the paragraph is not a
         *  title candidate. */
        private var paragraphLeftEmphasis = false
        /** The paragraph carried any emphasis at all. */
        private var paragraphHadEmphasis = false
        /** The first paragraph of a section, while it is still a candidate. */
        private var paragraphIsCandidate = false
        /** Text accumulated since the last inline boundary. */
        private val textBuffer = StringBuilder()
        /** Image references made by the paragraph being built. */
        private val pendingImageIds = ArrayList<String>()

        // Section state: the emphasis fallback only looks at the FIRST
        // paragraph of a section.
        private var sectionDepth = 0
        private var paragraphsInSection = 0

        // Table state. FB2 wraps each cell's text in `<p>`, so the paragraph
        // reader is what fills the cells; `inCell` is where its output goes.
        private var tableDepth = 0
        private var inCell = false
        private val tableRows = ArrayList<List<Cell>>()
        private val rowCells = ArrayList<Cell>()
        private var inHeaderCell = false
        private val cellBuffer = StringBuilder()

        // Binary (image payload) state.
        private var binaryId: String? = null
        private var binaryMime = "image/jpeg"
        private val binaryBuffer = StringBuilder()

        // Document context.
        private var inBody = false
        private var inCoverpage = false
        /** Inside `<title-info>`: the description block that carries the
         *  book's own metadata. */
        private var inTitleInfo = false
        private var coverImageId: String? = null
        /** The decoded binaries, keyed by their FB2 id. */
        private val imagePool = LinkedHashMap<String, BookImage>()

        // Metadata. Each capture has its own buffer: sharing one produced
        // "en en" as the author on the reference's first fixture run.
        private var titleCapture: String? = null
        private var languageCapture: String? = null
        private val authorParts = ArrayList<String>()
        private var capturingTitle = false
        private var capturingLanguage = false
        private var capturingName = false
        private val captureBuffer = StringBuilder()

        fun handle(event: BookXml.Event) {
            when (event) {
                is BookXml.Event.Start -> start(local(event.name), event.attrs)
                is BookXml.Event.Empty -> start(local(event.name), event.attrs)
                is BookXml.Event.End -> end(local(event.name))
                is BookXml.Event.Text -> text(event.text)
                is BookXml.Event.Raw -> {}
            }
        }

        private fun local(name: String): String = name.substringAfterLast(':')

        private fun start(name: String, attrs: List<BookXml.Attr>) {
            when (name) {
                "body" -> inBody = true
                "coverpage" -> inCoverpage = true
                "title-info" -> inTitleInfo = true
                "binary" -> {
                    binaryId = BookXml.Attr.get(attrs, "id")
                    binaryMime = BookXml.Attr.get(attrs, "content-type") ?: "image/jpeg"
                    binaryBuffer.clear()
                }
                "image" -> {
                    val href = BookXml.Attr.get(attrs, "l:href")
                        ?: BookXml.Attr.get(attrs, "xlink:href")
                        ?: BookXml.Attr.get(attrs, "href")
                    val id = href?.takeIf { it.startsWith("#") }?.substring(1)
                    if (id != null) {
                        if (inCoverpage && coverImageId == null) coverImageId = id
                        // A coverpage image is the shelf cover, not body
                        // content — its reference must not leak into the
                        // first paragraph's image list.
                        if (inBody && !inCoverpage) pendingImageIds.add(id)
                    }
                }
                "title" -> {
                    titleDepth++
                    titleLevel = 1
                }
                "p", "v", "text" -> {
                    pending = if (titleDepth > 0) Pending.Title else Pending.Body
                    inParagraph = true
                    paragraphHtml.clear()
                    paragraphPlain.clear()
                    emphasisDepth = 0
                    paragraphLeftEmphasis = false
                    paragraphHadEmphasis = false
                    if (titleDepth == 0 && sectionDepth > 0 && !inCell) {
                        paragraphsInSection++
                        paragraphIsCandidate = paragraphsInSection == 1
                    } else {
                        paragraphIsCandidate = false
                    }
                }
                "subtitle" -> {
                    pending = if (titleDepth > 0) Pending.Subtitle else Pending.Body
                    inParagraph = true
                    paragraphHtml.clear()
                    paragraphPlain.clear()
                    emphasisDepth = 0
                    paragraphLeftEmphasis = false
                    paragraphHadEmphasis = false
                }
                "strong", "emphasis" -> {
                    flushText()
                    emphasisDepth++
                    paragraphHtml.append(if (name == "strong") "<strong>" else "<em>")
                }
                "th" -> {
                    inCell = true
                    inHeaderCell = true
                    cellBuffer.clear()
                }
                "td" -> {
                    inCell = true
                    inHeaderCell = false
                    cellBuffer.clear()
                }
                "table" -> {
                    tableDepth++
                    if (tableDepth == 1) tableRows.clear()
                }
                "tr" -> rowCells.clear()
                "section" -> {
                    sectionDepth++
                    paragraphsInSection = 0
                }
                "book-title" -> {
                    if (inTitleInfo) {
                        capturingTitle = true
                        captureBuffer.clear()
                    }
                }
                "lang" -> {
                    if (inTitleInfo) {
                        capturingLanguage = true
                        captureBuffer.clear()
                    }
                }
                "first-name", "middle-name", "last-name", "nick" -> {
                    // The title-info author only. A `<document-info>` author
                    // is the file's provenance, not the book's writer.
                    if (inTitleInfo) {
                        capturingName = true
                        captureBuffer.clear()
                    }
                }
            }
        }

        private fun end(name: String) {
            when (name) {
                "binary" -> finishBinary()
                "p", "v", "text", "subtitle" -> closeParagraph()
                "level" -> {
                    val value = tidy(textBuffer.toString())
                    textBuffer.clear()
                    value.toIntOrNull()?.let { level ->
                        if (level > 0) titleLevel = level
                    }
                }
                "title" -> {
                    titleDepth = maxOf(0, titleDepth - 1)
                    pending = Pending.Body
                }
                "strong", "emphasis" -> {
                    flushText()
                    emphasisDepth = maxOf(0, emphasisDepth - 1)
                    paragraphHtml.append(if (name == "strong") "</strong>" else "</em>")
                }
                "th", "td" -> {
                    inCell = false
                    val text = tidy(cellBuffer.toString())
                    cellBuffer.clear()
                    if (tableDepth > 0) rowCells.add(Cell(text, inHeaderCell))
                }
                "tr" -> {
                    if (tableDepth > 0 && rowCells.isNotEmpty()) {
                        tableRows.add(ArrayList(rowCells))
                        rowCells.clear()
                    }
                }
                "table" -> {
                    tableDepth = maxOf(0, tableDepth - 1)
                    if (tableDepth == 0 && tableRows.isNotEmpty()) {
                        items.add(Item.Table(ArrayList(tableRows)))
                        tableRows.clear()
                    }
                }
                "section" -> sectionDepth = maxOf(0, sectionDepth - 1)
                "coverpage" -> inCoverpage = false
                "title-info" -> inTitleInfo = false
                "body" -> inBody = false
                "book-title" -> {
                    val value = tidy(captureBuffer.toString())
                    captureBuffer.clear()
                    if (value.isNotEmpty() && titleCapture == null) titleCapture = value
                    capturingTitle = false
                }
                "lang" -> {
                    val value = tidy(captureBuffer.toString())
                    captureBuffer.clear()
                    if (value.isNotEmpty() && languageCapture == null) languageCapture = value
                    capturingLanguage = false
                }
                "first-name", "middle-name", "last-name", "nick" -> {
                    val value = tidy(captureBuffer.toString())
                    captureBuffer.clear()
                    if (value.isNotEmpty()) authorParts.add(value)
                    capturingName = false
                }
            }
        }

        private fun text(t: String) {
            if (binaryId != null) {
                binaryBuffer.append(t)
                return
            }
            if (capturingTitle || capturingLanguage || capturingName) {
                captureBuffer.append(t)
                return
            }
            if (inCell) {
                // Text sitting directly in a `<td>` (FB2 allows it beside the
                // `<p>`-wrapped kind) belongs to the cell.
                cellBuffer.append(t)
                return
            }
            if (titleDepth > 0 || inParagraph) textBuffer.append(t)
        }

        /** The buffered text joins the paragraph — escaped into its XHTML
         *  and kept plain for the heading heuristics. */
        private fun flushText() {
            if (textBuffer.isEmpty()) return
            val raw = textBuffer.toString()
            textBuffer.clear()
            val tidied = tidy(raw)
            if (tidied.isNotEmpty()) {
                paragraphHtml.append(xmlEscape(tidied))
                paragraphPlain.append(tidied)
                if (emphasisDepth == 0) {
                    paragraphLeftEmphasis = true
                } else {
                    paragraphHadEmphasis = true
                }
            } else if (paragraphHtml.isNotEmpty() && paragraphHtml.last().isLetterOrDigit()) {
                // Whitespace between two inline spans: one space, so the
                // words do not run together when the markup renders.
                paragraphHtml.append(' ')
                paragraphPlain.append(' ')
            }
        }

        private fun closeParagraph() {
            if (titleDepth > 0) {
                // Inside a `<title>` the words ARE the title — the title
                // element wraps them in `<p>` and `<subtitle>`.
                flushText()
                inParagraph = false
                val text = paragraphPlain.toString().trim()
                paragraphPlain.clear()
                if (text.isEmpty()) return
                when (pending) {
                    Pending.Subtitle -> {
                        // The half after the em dash joins its title.
                        (items.lastOrNull() as? Item.Heading)?.let { last ->
                            items[items.size - 1] = Item.Heading("${last.text} — $text", last.level)
                        }
                    }
                    else -> {
                        items.add(Item.Heading(text, titleLevel))
                        pending = Pending.Subtitle
                    }
                }
                return
            }

            flushText()
            inParagraph = false
            val html = paragraphHtml.toString()
            paragraphHtml.clear()
            val plain = paragraphPlain.toString()
            paragraphPlain.clear()
            val wasCandidate = paragraphIsCandidate
            paragraphIsCandidate = false
            val empty = plain.trim().isEmpty()

            // A cell paragraph's words belong to the cell, not to the item
            // stream — FB2 wraps every cell's text in `<p>`.
            if (inCell) {
                if (!empty) {
                    if (cellBuffer.isNotEmpty()) cellBuffer.append(' ')
                    cellBuffer.append(plain.trim())
                }
                pendingImageIds.clear()
                return
            }

            if (!empty) {
                // The de-facto heading convention: a section's first
                // paragraph that never left its emphasis wrapper, is short
                // and is not sentence-punctuated, is its title.
                val fullyEmphasized = paragraphHadEmphasis && !paragraphLeftEmphasis
                if (wasCandidate && fullyEmphasized && plain.length <= 120 && !endsSentence(plain)) {
                    items.add(Item.Heading(plain.trim(), 1))
                } else {
                    items.add(Item.Paragraph(html))
                }
            }
            // The images the paragraph referenced ride after it.
            val ids = ArrayList(pendingImageIds)
            pendingImageIds.clear()
            for (id in ids) items.add(Item.Image(id))
        }

        private fun finishBinary() {
            val id = binaryId ?: run {
                binaryBuffer.clear()
                return
            }
            binaryId = null
            val buffer = binaryBuffer.toString()
            binaryBuffer.clear()
            val mime = displayableMime(binaryMime) ?: return
            val data = base64Decode(buffer)
            if (data.isNotEmpty()) {
                imagePool[id] = BookImage(
                    // The book's own `img/…` name is decided when the
                    // reference resolves; this entry is the payload.
                    name = "",
                    mime = mime,
                    data = data,
                )
            }
        }

        /** The parse's end: resolve the image references, promote the cover,
         *  chapterize. A parse that collected nothing is a refusal. */
        fun finish(): BookDoc {
            if (items.isEmpty()) {
                throw BookDocError.Malformed("no readable text in the FictionBook")
            }
            // Only the binaries something referenced ship with the book: an
            // unreferenced `<binary>` is dead weight in memory. The names
            // are flat and ordered by first reference — `img/000000`,
            // `img/000001`.
            val images = ArrayList<BookImage>()
            val used = HashMap<String, Int>() // fb2 id → index
            for (item in items) {
                if (item is Item.Image) {
                    if (used.containsKey(item.id)) continue
                    imagePool[item.id]?.let { poolImage ->
                        used[item.id] = images.size
                        images.add(
                            BookImage(
                                name = "img/${imageRefName(images.size)}",
                                mime = poolImage.mime,
                                data = poolImage.data,
                            )
                        )
                    }
                }
            }
            // The cover: the image the `<coverpage>` named, under its own
            // name the shelf knows.
            val cover = coverImageId?.let { id -> imagePool[id] }?.let { image ->
                BookImage(name = "img/cover", mime = image.mime, data = image.data)
            }

            val author = if (authorParts.isEmpty()) null else authorParts.joinToString(" ")
            val (chapters, toc) = chapterize(items, used)
            return BookDoc(
                title = titleCapture,
                author = author,
                language = languageCapture,
                cover = cover,
                chapters = chapters,
                toc = toc,
                images = images,
            )
        }
    }

    /** Splits the items into chapters at their level 1–2 headings; deeper
     *  titles stay inside as prose. Image references resolve against [used]
     *  (the fb2 id → image index map); a reference nothing answers is
     *  dropped. */
    private fun chapterize(
        items: List<Item>,
        used: Map<String, Int>,
    ): Pair<List<BookChapter>, List<BookToc>> {
        val chapters = ArrayList<BookChapter>()
        val toc = ArrayList<BookToc>()
        var title: String? = null
        val html = StringBuilder()
        var itemCount = 0

        fun flush() {
            if (html.isNotEmpty() || title != null) {
                val label = title ?: "Chapter ${chapters.size + 1}"
                toc.add(BookToc(label, chapters.size, 0))
                chapters.add(BookChapter(title, html.toString()))
                html.clear()
            }
        }

        for (item in items) {
            when (item) {
                is Item.Heading -> {
                    if (item.level <= 2 || title == null) {
                        flush()
                        title = item.text
                    } else {
                        html.append("<p>").append(xmlEscape(item.text)).append("</p>")
                        itemCount++
                    }
                }
                is Item.Paragraph -> {
                    html.append("<p>").append(item.html).append("</p>")
                    itemCount++
                }
                is Item.Table -> {
                    html.append("<table>")
                    for (row in item.rows) {
                        html.append("<tr>")
                        for (cell in row) {
                            val (open, close) = if (cell.header) "<th>" to "</th>" else "<td>" to "</td>"
                            html.append(open).append(xmlEscape(cell.text)).append(close)
                        }
                        html.append("</tr>")
                    }
                    html.append("</table>")
                    itemCount++
                }
                is Item.Image -> {
                    used[item.id]?.let { index ->
                        html.append("<img src=\"img/${imageRefName(index)}\" alt=\"\">")
                    }
                    // A reference nothing answers loses its tag — a broken
                    // image icon helps nobody.
                    itemCount++
                }
            }
            if (itemCount >= CHAPTER_ITEM_LIMIT) {
                // The bound: the section title carries onto the continuation
                // chapter so the contents list never shows "Chapter N"
                // mid-section.
                val carried = title
                title = null
                flush()
                title = carried
                itemCount = 0
            }
        }
        flush()

        return chapters to toc
    }

    /** The `img/` name for image [index] — `000000`, `000001`, in order of
     *  first reference. */
    private fun imageRefName(index: Int): String = "%06d".format(index)

    /** Whitespace and NBSP tidy. FB2's typographic characters arrive as
     *  real characters already; all this does is collapse the indentation
     *  pretty-printed files carry inside `<p>`. */
    private fun tidy(text: String): String {
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

    /** A sentence-ending punctuation mark — the mark that keeps a bold line
     *  from reading as a title. */
    private fun endsSentence(text: String): Boolean {
        val last = text.lastOrNull() ?: return false
        return last == '.' || last == ',' || last == ';' || last == '!' || last == '?'
    }

    /** A mime type the reader can show, or `null` — SVG and unknown
     *  payloads are dropped rather than shipped as an image tag that
     *  renders nothing. */
    private fun displayableMime(mime: String): String? = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "image/jpeg"
        "image/png" -> "image/png"
        "image/gif" -> "image/gif"
        "image/webp" -> "image/webp"
        else -> null
    }

    /** Base64, decoded streaming: six bits at a time, no padding required,
     *  whitespace and stray characters skipped. A truncated tail still
     *  decodes the bytes it has. */
    internal fun base64Decode(input: String): ByteArray {
        val out = ArrayList<Byte>(input.length / 4 * 3)
        var accumulator = 0
        var bits = 0
        for (raw in input.toByteArray(Charsets.ISO_8859_1)) {
            val b = raw.toInt() and 0xFF
            val value = when (b) {
                in 'A'.code..'Z'.code -> b - 'A'.code
                in 'a'.code..'z'.code -> b - 'a'.code + 26
                in '0'.code..'9'.code -> b - '0'.code + 52
                '+'.code -> 62
                '/'.code -> 63
                else -> continue
            }
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((accumulator shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
