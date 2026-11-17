package com.speechnotes.app.logic

/**
 * HTML book reader — `.html`/`.htm` standalone files and `.htmlz` archives
 * (calibre's HTML container). Ported from speechnotes-linux-rust
 * `crates/sn-doc/src/htmlbook.rs`, which itself ported iOS's
 * HtmlBookParser.swift.
 *
 * HTML is NOT XML, and that single fact drives the design: a strict XML
 * parser aborts on the first `<br>`, unclosed `<p>` or unknown entity —
 * which in practice truncates the book at the top of chapter two. So this
 * parser is a hand-written tolerant tokenizer:
 *
 * - void elements (`<br>`, `<img>`, `<hr>`) need no close;
 * - unclosed `<p>`/`<li>` are closed by the next block tag;
 * - `<script>`/`<style>` bodies and `<!-- … -->` comments are skipped
 *   whole, while `<head>` is deliberately walked (it carries the only
 *   `<title>` and author `<meta>` a saved page has);
 * - entities go through a named table plus numeric references; unknown
 *   names keep their whole `&name;` token verbatim rather than inventing
 *   a word;
 * - tags inside attribute values (the classic `alt="a > b"` bug) cannot
 *   confuse the scan, because attribute strings are consumed atomically.
 *
 * Chapters come from `<h1>`–`<h2>` (deeper levels stay inside as prose),
 * and `<table>` becomes a real table the chapter XHTML carries.
 */
object BookHtmlParser {

    /** The office parsers' bound, so a heading-less HTML dump chunks the
     *  same way a heading-less text file does. */
    private const val CHAPTER_BLOCK_LIMIT = 150

    /** Parse a standalone `.html`/`.htm` document into chapters.
     *
     *  The images a standalone file references live outside the file — the
     *  webview could not serve them, and a broken-image icon helps nobody —
     *  so their tags are dropped. An HTMLZ keeps them; see [parseHtmlz]. */
    fun parse(data: ByteArray): BookDoc = parseInner(data, keepImages = false)

    /** The parse, with the choice of whether image references survive. */
    private fun parseInner(data: ByteArray, keepImages: Boolean): BookDoc {
        val text = decode(data)
        if (text.isBlank()) {
            throw BookDocError.Malformed("the file has no readable text")
        }
        val reader = HtmlReader(text)
        reader.run()
        if (reader.items.isEmpty()) {
            throw BookDocError.Malformed("no readable text in the HTML document")
        }
        val (chapters, toc) = chapterize(reader.items, keepImages)
        return BookDoc(
            title = reader.title,
            author = reader.author,
            chapters = chapters,
            toc = toc,
        )
    }

    /** Parse a `.htmlz` — a ZIP with `index.html`, `metadata.opf` and the
     *  images. The OPF supplies title/author exactly as an EPUB's does; the
     *  images the HTML references get `img/` names and ship in the book. */
    fun parseHtmlz(data: ByteArray): BookDoc {
        val archive = ZipReader.parse(data)
            ?: throw BookDocError.Malformed("not an HTML archive: the ZIP does not parse")
        // The reference's Archive::entries() returns every name SORTED BY
        // NAME, and the picks below ("first .opf", "first image", "first
        // .html") ride that order — calibre's cover.jpg must beat
        // images/pic.png for the cover. The zip's own order must not.
        val names = archive.names.toList().sorted()
        val htmlName = names.firstOrNull { it == "index.html" }
            ?: names.firstOrNull {
                val lower = it.lowercase()
                lower.endsWith(".html") && !lower.endsWith(".htmlz")
            }
            ?: throw BookDocError.Malformed("the archive has no index.html")
        val htmlData = archive.data(htmlName)
            ?: throw BookDocError.Malformed("$htmlName: missing entry")
        val doc0 = parseInner(htmlData, keepImages = true)

        // OPF metadata: Dublin-Core title/creator, the same walk the EPUB
        // parser makes over its OPF. The OPF is the book's own metadata and
        // wins over the HTML page's <title>.
        var doc = doc0
        names.firstOrNull { it.lowercase().endsWith(".opf") }?.let { opfName ->
            archive.text(opfName)?.let { opf ->
                val (title, author) = opfMetadata(opf)
                if (title != null) doc = doc.copyTitle(title)
                if (author != null) doc = doc.copyAuthor(author)
            }
        }

        // Cover: the first image entry, exactly the convention calibre
        // writes (cover.jpg/cover.png beside index.html).
        for (name in names) {
            val lower = name.lowercase()
            val isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
            if (!isImage) continue
            val coverData = archive.data(name)
            if (coverData != null && coverData.isNotEmpty()) {
                doc = doc.copyCover(
                    BookImage(
                        name = "img/cover",
                        mime = if (lower.endsWith(".png")) "image/png" else "image/jpeg",
                        data = coverData,
                    )
                )
                break
            }
        }

        // The images the HTML references: every `<img src>` that resolves to
        // an entry ships as `img/<entry name>`, and the chapter XHTML's srcs
        // are rewritten to those names. An src the archive does not hold
        // loses its tag.
        doc = rewriteHtmlzImages(doc, archive, names)
        return doc
    }

    // The BookDoc fields are immutable; these copies are the htmlz walker's
    // mutation equivalent.
    private fun BookDoc.copyTitle(value: String): BookDoc =
        BookDoc(value, author, language, cover, chapters, toc, images)

    private fun BookDoc.copyAuthor(value: String): BookDoc =
        BookDoc(title, value, language, cover, chapters, toc, images)

    private fun BookDoc.copyCover(image: BookImage): BookDoc =
        BookDoc(title, author, language, image, chapters, toc, images)

    private fun BookDoc.copyChapters(value: List<BookChapter>): BookDoc =
        BookDoc(title, author, language, cover, value, toc, images)

    private fun BookDoc.copyImages(value: List<BookImage>): BookDoc =
        BookDoc(title, author, language, cover, chapters, toc, value)

    /** Rewrite every chapter's `<img src="…">` that resolves to a zip entry
     *  into `<img src="img/…">`, collecting the entries as the book's
     *  images. */
    private fun rewriteHtmlzImages(doc: BookDoc, archive: ZipReader, names: List<String>): BookDoc {
        val images = ArrayList(doc.images)
        val chapters = doc.chapters.map { chapter ->
            val html = StringBuilder(chapter.html.length)
            var rest = chapter.html
            while (true) {
                val at = rest.indexOf("<img")
                if (at < 0) {
                    html.append(rest)
                    break
                }
                html.append(rest, 0, at)
                val after = rest.substring(at)
                val tagEnd = after.indexOf('>')
                if (tagEnd < 0) {
                    html.append(after)
                    break
                }
                val tag = after.substring(0, tagEnd + 1)
                val next = after.substring(tagEnd + 1)
                val newTag = resolveImgTag(tag, archive, names, images)
                if (newTag != null) html.append(newTag)
                rest = next
            }
            BookChapter(chapter.title, html.toString())
        }
        return doc.copyChapters(chapters).copyImages(images)
    }

    /** One `<img …>` tag: when its src names an entry in the archive, return
     *  the tag rewritten to the `img/` name (adding the entry to [images]);
     *  otherwise `null` — the tag is dropped. */
    private fun resolveImgTag(
        tag: String,
        archive: ZipReader,
        names: List<String>,
        images: MutableList<BookImage>,
    ): String? {
        val srcAt = tag.lowercase().indexOf("src=")
        if (srcAt < 0) return null
        val rest = tag.substring(srcAt + 4)
        val quote = rest.firstOrNull() ?: return null
        val src = if (quote == '"' || quote == '\'') {
            val end = rest.substring(1).indexOf(quote)
            if (end < 0) return null
            rest.substring(1, end + 1)
        } else {
            rest.split(' ', '\t', '\n', '>', '"').firstOrNull() ?: return null
        }
        val decoded = percentDecode(src)
        // Exact entry name, then entry-basename — htmlz mirrors vary in how
        // much of the original site's directory tree they keep.
        val entry = names.firstOrNull { it == decoded } ?: run {
            val base = decoded.substringAfterLast('/')
            names.firstOrNull {
                it.substringAfterLast('/') == base &&
                    (base.endsWith(".jpg") || base.endsWith(".jpeg") ||
                        base.endsWith(".png") || base.endsWith(".gif"))
            }
        } ?: return null
        val data = archive.data(entry) ?: return null
        // The book's image names are flat — the file name alone.
        val base = entry.substringAfterLast('/')
        val imageName = uniqueName(base, images)
        val mime = when {
            entry.lowercase().endsWith(".png") -> "image/png"
            entry.lowercase().endsWith(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
        images.add(BookImage(name = "img/$imageName", mime = mime, data = data))
        val alt = altOf(tag) ?: ""
        return "<img src=\"img/$imageName\" alt=\"$alt\">"
    }

    /** A name no other image in the book holds: `pic.png`, then
     *  `pic-2.png`, `pic-3.png`. Two mirrors may carry the same file name
     *  in different directories, and both pictures belong in the book. */
    private fun uniqueName(base: String, images: List<BookImage>): String {
        fun taken(name: String) = images.any { it.name == "img/$name" }
        if (!taken(base)) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot >= 0) base.substring(0, dot) else base
        val extension = if (dot >= 0) base.substring(dot) else ""
        for (n in 2..999) {
            val candidate = "$stem-$n$extension"
            if (!taken(candidate)) return candidate
        }
        return "$stem-${images.size}"
    }

    /** An `<img>` tag's alt text, if it carried one. */
    private fun altOf(tag: String): String? {
        val lower = tag.lowercase()
        val at = lower.indexOf("alt=")
        if (at < 0) return null
        val rest = tag.substring(at + 4)
        val quote = rest.firstOrNull() ?: return null
        return if (quote == '"' || quote == '\'') {
            val end = rest.substring(1).indexOf(quote)
            if (end < 0) return null
            rest.substring(1, end + 1)
        } else {
            rest.split(' ', '\t', '\n', '>', '"').firstOrNull()
        }
    }

    /** Title/creator out of an HTMLZ's `metadata.opf` — Dublin-Core. */
    private fun opfMetadata(opf: String): Pair<String?, String?> {
        var title: String? = null
        var creator: String? = null
        var current: String? = null
        val buffer = StringBuilder()
        val parser = BookXml.Parser(opf)
        while (true) {
            val event = try {
                parser.nextEvent()
            } catch (e: BookXml.XmlError) {
                break
            } ?: break
            when (event) {
                is BookXml.Event.Start -> {
                    val local = localName(event.name)
                    current = if ((local == "title" && title == null) || (local == "creator" && creator == null)) {
                        buffer.clear()
                        local
                    } else {
                        null
                    }
                }
                is BookXml.Event.Empty -> {
                    val local = localName(event.name)
                    current = if ((local == "title" && title == null) || (local == "creator" && creator == null)) {
                        buffer.clear()
                        local
                    } else {
                        null
                    }
                }
                is BookXml.Event.Text -> {
                    if (current != null) buffer.append(event.text)
                }
                is BookXml.Event.End -> {
                    if (current == localName(event.name)) {
                        val value = buffer.toString().trim()
                        if (value.isNotEmpty()) {
                            val local = localName(event.name)
                            if (local == "title" && title == null) title = value
                            else if (local == "creator" && creator == null) creator = value
                        }
                        current = null
                    }
                }
                is BookXml.Event.Raw -> {}
            }
        }
        return title to creator
    }

    private fun localName(name: String): String = name.substringAfterLast(':')

    /** Percent-decode `%XX` runs as UTF-8 bytes; other bytes pass through. */
    private fun percentDecode(src: String): String {
        if (!src.contains('%')) return src
        val bytes = ArrayList<Byte>(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '%' && i + 3 <= src.length) {
                val v = src.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes.add(v.toByte())
                    i += 3
                    continue
                }
            }
            // One char — its UTF-8 bytes.
            for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Browsers sniff; we can't. A UTF-16 BOM first (it is the only
     * unambiguous signal), then UTF-8 (validates), then a meta charset
     * naming UTF-16, then Windows-1252 — the same floor as the plain-text
     * battery: latin-1's table never fails.
     */
    fun decode(data: ByteArray): String {
        decodeUtf16Bom(data)?.let { return it }
        decodeStrictUtf8(data)?.let { return it }
        val head = String(data, 0, minOf(data.size, 2048), Charsets.UTF_8).lowercase()
        if (head.contains("charset=utf-16")) {
            // No BOM, but the document says UTF-16. The declaration is
            // evidence enough for little-endian, the x86 norm.
            return decodeUtf16(data, bigEndian = false)
        }
        return BookCodepages.decodeCp1252(data)
    }

    private fun decodeStrictUtf8(data: ByteArray): String? = runCatching {
        java.nio.charset.Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(data)).toString()
    }.getOrNull()

    /** UTF-16 text, when the bytes carry a byte-order mark. */
    private fun decodeUtf16Bom(data: ByteArray): String? {
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            return decodeUtf16(data.copyOfRange(2, data.size), bigEndian = true)
        }
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
            return decodeUtf16(data.copyOfRange(2, data.size), bigEndian = false)
        }
        return null
    }

    /** UTF-16 units → text. An odd trailing byte is dropped: the half a
     *  character lost is not worth a parse failure. */
    private fun decodeUtf16(data: ByteArray, bigEndian: Boolean): String {
        val units = CharArray(data.size / 2)
        var at = 0
        for (i in units.indices) {
            units[i] = if (bigEndian) {
                (((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)).toChar()
            } else {
                (((data[at + 1].toInt() and 0xFF) shl 8) or (data[at].toInt() and 0xFF)).toChar()
            }
            at += 2
        }
        return String(units)
    }

    // ---------------------------------------------------------------------------
    // The tolerant reader
    // ---------------------------------------------------------------------------

    /** One table cell: its text and whether it was a header cell. */
    private class Cell(val text: String, val header: Boolean)

    /** One thing the tokenizer found, in document order. */
    private sealed class Item {
        class Heading(val text: String, val level: Int) : Item()
        class Paragraph(val text: String) : Item()
        class Table(val rows: List<List<Cell>>) : Item()
        /** An image reference. A standalone HTML file's images live outside
         *  the file and are dropped; an HTMLZ's images live in the archive
         *  and are rewritten to the book's own `img/` names. */
        class Image(val src: String, val alt: String?) : Item()
    }

    /**
     * Hand-written HTML tokenizer → heading/paragraph/table items.
     *
     * The tokenizer's only lookahead mechanism is a one-character pushback
     * stack: `peek` never consumes, and `pushback` hands a character back
     * when the scan has to reconsider it (an entity that turned out not to
     * be one, an unquoted attribute value's terminator).
     */
    private class HtmlReader(text: String) {
        private val source: CharArray = text.toCharArray()
        private var sourcePos = 0
        /** Characters handed back by the scanner, last one first. */
        private val pushback = ArrayDeque<Char>()
        val items = ArrayList<Item>()
        var title: String? = null
            private set
        var author: String? = null
            private set
        private val buffer = StringBuilder()
        private var pendingHeadingLevel = 0
        private val tableRows = ArrayList<List<Cell>>()
        private val rowCells = ArrayList<Cell>()
        private val cellBuffer = StringBuilder()
        private var inCell = false
        private var inHeaderCell = false
        private var inTitle = false
        private val titleBuffer = StringBuilder()
        /** Inside a `<pre>` block: the source's newlines are the author's
         *  line breaks, not formatting to collapse. */
        private var inPre = false

        /** The next character without consuming it. */
        private fun peek(): Char? {
            pushback.lastOrNull()?.let { return it }
            return source.getOrNull(sourcePos)
        }

        /** The next character, consuming it. */
        private fun nextChar(): Char? {
            pushback.removeLastOrNull()?.let { return it }
            val c = source.getOrNull(sourcePos) ?: return null
            sourcePos++
            return c
        }

        /** Hand a character back to the scanner. */
        private fun pushBack(c: Char) {
            pushback.addLast(c)
        }

        fun run() {
            while (true) {
                val c = nextChar() ?: break
                if (c == '<') {
                    flushText()
                    readTag()
                    continue
                }
                if (c == '&') {
                    // `readEntity` consumes the entity's own terminator; the
                    // scanner must not eat a second character here.
                    val entity = readEntity()
                    appendLiteral(entity)
                    continue
                }
                appendLiteralChar(c)
            }
            flushText()
        }

        /** One run of document text. While a `<title>` is open it goes to
         *  the title buffer instead of the paragraph buffer. */
        private fun appendLiteral(value: String) {
            if (inTitle) titleBuffer.append(value) else buffer.append(value)
        }

        private fun appendLiteralChar(value: Char) {
            if (inTitle) titleBuffer.append(value) else buffer.append(value)
        }

        /** Consumes one tag (and, for script/style/comment, its whole body).
         *  The open `<` was already consumed. */
        private fun readTag() {
            val first = peek() ?: return
            // Comment: `<!--` skips to `-->`.
            if (first == '!') {
                nextChar()
                val second = peek()
                val third = secondAfterPushback()
                val isComment = second == '-' && third == '-'
                if (isComment) {
                    nextChar()
                    nextChar()
                    skipToCommentEnd()
                } else {
                    // `<!DOCTYPE …>` and other declarations: to the '>'.
                    skipTo('>')
                }
                return
            }
            val name = StringBuilder()
            var isClosing = false
            if (first == '/') {
                isClosing = true
                nextChar()
            }
            while (true) {
                val c = nextChar() ?: break
                if (c == '>') {
                    handle(name.toString(), isClosing, emptyList())
                    return
                }
                if (c.isWhitespace()) break
                name.append(c.lowercaseChar())
            }
            // Attributes: consume to the closing '>', honouring quoted
            // strings so an `alt="a > b"` cannot end the tag early.
            val attributes = ArrayList<Pair<String, String>>()
            while (true) {
                var c = peek() ?: break
                if (c == '>') break
                val attributeName = StringBuilder()
                while (true) {
                    val n = nextChar() ?: break
                    if (n == '=' || n == '>' || n.isWhitespace()) {
                        c = n
                        break
                    }
                    attributeName.append(n.lowercaseChar())
                }
                if (c == '=') {
                    // The '=' was already consumed by the scan above.
                    val value = StringBuilder()
                    val quote = peek()
                    if (quote == '"' || quote == '\'') {
                        nextChar()
                        while (true) {
                            val v = nextChar() ?: break
                            if (v == quote) break
                            value.append(v)
                        }
                    } else {
                        while (true) {
                            val v = nextChar() ?: break
                            if (v == '>' || v.isWhitespace()) {
                                // Unquoted value: leave the terminator for
                                // the attribute loop.
                                pushBack(v)
                                break
                            }
                            value.append(v)
                        }
                    }
                    if (attributeName.isNotEmpty()) attributes.add(attributeName.toString() to value.toString())
                } else if (c == '>') {
                    break
                } else {
                    // A bare attribute name with no value; the terminator stays.
                    if (attributeName.isNotEmpty()) attributes.add(attributeName.toString() to "")
                }
            }
            // Past the '>'.
            if (peek() == '>') nextChar()
            handle(name.toString(), isClosing, attributes)
        }

        /** The character *after* the one `peek` would return — the second
         *  '-' of a `<!--` comment opener. */
        private fun secondAfterPushback(): Char? {
            if (pushback.isNotEmpty()) {
                // kotlin.collections.ArrayDeque is random-access; index 1
                // from the end is the second char back.
                return pushback.getOrNull(pushback.size - 2) ?: source.getOrNull(sourcePos)
            }
            return source.getOrNull(sourcePos + 1)
        }

        private fun skipTo(terminator: Char) {
            while (true) {
                val c = nextChar() ?: return
                if (c == terminator) return
            }
        }

        private fun skipToCommentEnd() {
            var lastTwo: Pair<Char?, Char?> = null to null
            while (true) {
                val c = nextChar() ?: return
                if (lastTwo == ('-' to '-') && c == '>') return
                lastTwo = lastTwo.second to c
            }
        }

        /** Skips the whole body of `<script>`/`<style>` to the matching
         *  close, honouring nesting. `readTag` has consumed the OPENING tag
         *  already, so depth starts at ONE. */
        private fun skipUntilClose(tag: String) {
            var depth = 1
            while (true) {
                val c = nextChar() ?: return
                if (c != '<') continue
                val name = StringBuilder()
                var isClosing = false
                if (peek() == '/') {
                    isClosing = true
                    nextChar()
                }
                var cursorTag = ""
                while (true) {
                    val n = nextChar() ?: break
                    if (n == '>' || n.isWhitespace()) {
                        cursorTag = name.toString()
                        break
                    }
                    name.append(n.lowercaseChar())
                }
                if (cursorTag != tag) continue
                if (isClosing) {
                    depth--
                    if (depth == 0) return
                } else {
                    depth++
                }
            }
        }

        /** One named entity or numeric reference, decoded. Unknown names
         *  keep their whole `&weirdname;` token. */
        private fun readEntity(): String {
            val name = StringBuilder()
            // The characters read so far, so a failed entity can hand them
            // back: `&foo` without the ';' is literal text.
            val consumed = ArrayList<Char>()
            while (name.length < 32) {
                val c = peek() ?: break
                if (c == ';') break
                if (!(c.isLetterOrDigit() || c == '#')) break
                name.append(c)
                consumed.add(c)
                nextChar()
            }
            if (peek() != ';' || name.isEmpty()) {
                for (c in consumed.reversed()) pushBack(c)
                return "&"
            }
            nextChar() // the ';'
            val entity = name.toString()
            if (entity.startsWith("#")) {
                val digits = entity.substring(1)
                val scalar = if (digits.length > 1 && (digits[0] == 'x' || digits[0] == 'X')) {
                    digits.substring(1).toIntOrNull(16)
                } else {
                    digits.toIntOrNull()
                }?.takeIf { it > 0 && it <= 0x10FFFF && it !in 0xD800..0xDFFF }
                return scalar?.let { String(Character.toChars(it)) } ?: ""
            }
            return namedEntity(entity) ?: "&$entity;"
        }

        // -- semantics ------------------------------------------------------

        private fun handle(tag: String, isClosing: Boolean, attributes: List<Pair<String, String>>) {
            when (tag) {
                "script", "style" -> {
                    // The whole body is unreadable noise for a reader.
                    // `<head>` is deliberately NOT skipped: it carries the
                    // only `<title>` and author `<meta>` a saved page has.
                    if (!isClosing) {
                        flushText()
                        skipUntilClose(tag)
                    }
                }
                "head" -> {}
                "title" -> {
                    if (isClosing) {
                        val value = tidy(titleBuffer.toString())
                        if (value.isNotEmpty() && title == null) title = value
                        inTitle = false
                    } else {
                        inTitle = true
                        titleBuffer.clear()
                    }
                }
                "meta" -> {
                    val name = attributes.firstOrNull { it.first == "name" || it.first == "property" }
                        ?.second?.lowercase() ?: ""
                    val content = attributes.firstOrNull { it.first == "content" }?.second ?: ""
                    if (name == "author" || name == "citation_author" || name == "dc.creator") {
                        val value = tidy(content)
                        if (value.isNotEmpty() && author == null) author = value
                    }
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = tag.substring(1).toIntOrNull() ?: 6
                    if (isClosing) {
                        flushText()
                        pendingHeadingLevel = 0
                    } else {
                        flushText()
                        pendingHeadingLevel = level
                    }
                }
                "p", "div", "li", "tr", "blockquote", "section", "article", "dd", "dt",
                "figcaption", "ul", "ol" -> {
                    if (isClosing) closeBlock(isTable = false)
                }
                // Preformatted text: the line breaks are the book's own. The
                // block's lines become one paragraph each.
                "pre" -> {
                    if (isClosing) {
                        flushPre()
                        inPre = false
                    } else {
                        flushText()
                        inPre = true
                    }
                }
                "table" -> {
                    if (isClosing) closeBlock(isTable = true)
                }
                "br", "hr" -> flushText()
                "img" -> {
                    // An image is a boundary even without a close tag — an
                    // alt-less image must not glue two words together.
                    flushText()
                    if (!isClosing) {
                        val src = attributes.firstOrNull { it.first == "src" }?.second ?: ""
                        if (src.isNotEmpty()) {
                            val alt = attributes.firstOrNull { it.first == "alt" }?.second?.takeIf { it.isNotEmpty() }
                            items.add(Item.Image(src, alt))
                        }
                    }
                }
                "td", "th" -> {
                    if (isClosing) {
                        if (inCell) {
                            val text = tidy(cellBuffer.toString())
                            rowCells.add(Cell(text, inHeaderCell))
                            cellBuffer.clear()
                            inCell = false
                            inHeaderCell = false
                        }
                    } else {
                        inCell = true
                        inHeaderCell = tag == "th"
                        cellBuffer.clear()
                    }
                }
            }
        }

        /** Text since the last boundary becomes a paragraph — or the
         *  heading's title when a heading is pending. */
        private fun flushText() {
            if (inPre) {
                flushPre()
                return
            }
            val text = tidy(buffer.toString())
            buffer.clear()
            if (text.isEmpty()) return
            if (pendingHeadingLevel > 0) {
                items.add(Item.Heading(text, pendingHeadingLevel))
                pendingHeadingLevel = 0
            } else if (inCell) {
                if (cellBuffer.isNotEmpty()) cellBuffer.append(' ')
                cellBuffer.append(text)
            } else {
                items.add(Item.Paragraph(text))
            }
        }

        /** The `<pre>` buffer's lines, as paragraphs. One line each: the
         *  source formatted these lines by hand (a Gutenberg header, a
         *  poem's stanza), and each line is a unit the reader's own
         *  wrapping can lay out. An empty line is a blank line the author
         *  typed — it becomes nothing. */
        private fun flushPre() {
            val text = buffer.toString()
            buffer.clear()
            for (line in text.split('\n')) {
                val tidied = tidy(line)
                if (tidied.isEmpty()) continue
                items.add(Item.Paragraph(tidied))
            }
        }

        /** A closing block tag: text first, then the cell/row bookkeeping.
         *  `<tr>` closes a ROW and `</table>` closes the TABLE — two
         *  different boundaries; conflating them meant every table came out
         *  as run-on paragraphs ("NameQty", "Apples12"). */
        private fun closeBlock(isTable: Boolean) {
            flushText()
            if (inCell) {
                val text = tidy(cellBuffer.toString())
                rowCells.add(Cell(text, inHeaderCell))
                cellBuffer.clear()
                inCell = false
                inHeaderCell = false
            }
            if (rowCells.isNotEmpty()) {
                tableRows.add(ArrayList(rowCells))
                rowCells.clear()
            }
            if (isTable && tableRows.isNotEmpty()) {
                items.add(Item.Table(ArrayList(tableRows)))
                tableRows.clear()
            }
        }
    }

    /** The named entities the reader decodes: XML's five, the HTML5 set
     *  XHTML books use, the accented letters older books spell with
     *  entities, and the Greek letters a scientific book reaches for.
     *  Unknown names keep their whole `&name;` token. */
    private fun namedEntity(name: String): String? = when (name) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        "hellip" -> "…"
        "mdash" -> "—"
        "ndash" -> "–"
        "lsquo" -> "‘"
        "rsquo" -> "’"
        "ldquo" -> "“"
        "rdquo" -> "”"
        "bull" -> "•"
        "dagger" -> "†"
        "permil" -> "‰"
        "lsaquo" -> "‹"
        "rsaquo" -> "›"
        "copy" -> "©"
        "reg" -> "®"
        "trade" -> "™"
        "deg" -> "°"
        "plusmn" -> "±"
        "middot" -> "·"
        "para" -> "¶"
        "sect" -> "§"
        "frac12" -> "½"
        "frac14" -> "¼"
        "frac34" -> "¾"
        "times" -> "×"
        "divide" -> "÷"
        "agrave" -> "à"
        "aacute" -> "á"
        "acirc" -> "â"
        "atilde" -> "ã"
        "auml" -> "ä"
        "aring" -> "å"
        "aelig" -> "æ"
        "ccedil" -> "ç"
        "egrave" -> "è"
        "eacute" -> "é"
        "ecirc" -> "ê"
        "euml" -> "ë"
        "igrave" -> "ì"
        "iacute" -> "í"
        "icirc" -> "î"
        "iuml" -> "ï"
        "ntilde" -> "ñ"
        "ograve" -> "ò"
        "oacute" -> "ó"
        "ocirc" -> "ô"
        "otilde" -> "õ"
        "ouml" -> "ö"
        "oslash" -> "ø"
        "ugrave" -> "ù"
        "uacute" -> "ú"
        "ucirc" -> "û"
        "uuml" -> "ü"
        "yacute" -> "ý"
        "yuml" -> "ÿ"
        "Agrave" -> "À"
        "Aacute" -> "Á"
        "Acirc" -> "Â"
        "Atilde" -> "Ã"
        "Auml" -> "Ä"
        "Aring" -> "Å"
        "AElig" -> "Æ"
        "Ccedil" -> "Ç"
        "Egrave" -> "È"
        "Eacute" -> "É"
        "Ecirc" -> "Ê"
        "Euml" -> "Ë"
        "Igrave" -> "Ì"
        "Iacute" -> "Í"
        "Icirc" -> "Î"
        "Iuml" -> "Ï"
        "Ntilde" -> "Ñ"
        "Ograve" -> "Ò"
        "Oacute" -> "Ó"
        "Ocirc" -> "Ô"
        "Otilde" -> "Õ"
        "Ouml" -> "Ö"
        "Oslash" -> "Ø"
        "Ugrave" -> "Ù"
        "Uacute" -> "Ú"
        "Ucirc" -> "Û"
        "Uuml" -> "Ü"
        "Yacute" -> "Ý"
        "szlig" -> "ß"
        "alpha" -> "α"
        "beta" -> "β"
        "gamma" -> "γ"
        "delta" -> "δ"
        "pi" -> "π"
        "mu" -> "μ"
        "sigma" -> "σ"
        "omega" -> "ω"
        else -> null
    }

    /** Collapse the whitespace the source's own formatting carried —
     *  newlines inside a paragraph, runs of indentation — into the single
     *  spaces prose has. */
    internal fun tidy(text: String): String {
        val out = StringBuilder(text.length)
        var inSpace = false
        for (c in text) {
            if (c.isWhitespace()) {
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

    // ---------------------------------------------------------------------------
    // Chapters
    // ---------------------------------------------------------------------------

    /** Splits the items into chapters at their `h1`/`h2` headings; deeper
     *  headings stay inside as prose. [keepImages] decides whether an
     *  `<img>` becomes a tag in the chapter XHTML: yes for an archive whose
     *  images travel with the book, no for a standalone page whose images
     *  live somewhere the reader cannot reach. */
    private fun chapterize(items: List<Item>, keepImages: Boolean): Pair<List<BookChapter>, List<BookToc>> {
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
        }

        for (item in items) {
            when (item) {
                is Item.Heading -> {
                    if (item.level <= 2 || title == null) {
                        flush()
                        title = item.text
                    } else {
                        html.append("<p>").append(xmlEscape(item.text)).append("</p>")
                        paragraphCount++
                    }
                }
                is Item.Paragraph -> {
                    html.append("<p>").append(xmlEscape(item.text)).append("</p>")
                    paragraphCount++
                }
                is Item.Table -> {
                    html.append("<table>")
                    for (row in item.rows) {
                        html.append("<tr>")
                        for (cell in row) {
                            if (cell.header) html.append("<th>") else html.append("<td>")
                            html.append(xmlEscape(cell.text))
                            if (cell.header) html.append("</th>") else html.append("</td>")
                        }
                        html.append("</tr>")
                    }
                    html.append("</table>")
                }
                is Item.Image -> {
                    if (keepImages) {
                        html.append("<img src=\"").append(xmlEscape(item.src)).append("\"")
                        item.alt?.let {
                            html.append(" alt=\"").append(xmlEscape(it)).append("\"")
                        }
                        html.append(">")
                    }
                }
            }
            // The bound: a heading-less dump chunks like the text reader
            // does, and the section title carries onto the continuation
            // chapter so the contents list never shows "Chapter N"
            // mid-section.
            if (paragraphCount >= CHAPTER_BLOCK_LIMIT) {
                val carried = title
                title = null
                flush()
                title = carried
                paragraphCount = 0
            }
        }
        flush()

        return chapters to toc
    }
}
