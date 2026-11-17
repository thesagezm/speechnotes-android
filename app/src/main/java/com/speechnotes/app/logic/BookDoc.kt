package com.speechnotes.app.logic

/**
 * The shared shape every headless book format reduces to. Ported from
 * speechnotes-linux-rust `crates/sn-doc/src/bookdoc.rs` + the
 * `ServedBookDoc` half of `crates/sn-store/src/books.rs`.
 *
 * EPUB is an archive of XHTML the reader serves verbatim; PDF is pages. The
 * formats this module serves — the Mobipocket/Kindle family, FictionBook,
 * RTF, HTML/HTMLZ and plain text — have no container the reader can serve
 * as-is, so each parser reduces its file to one [BookDoc]: metadata, a
 * cover, chapters of XHTML, a table of contents and the images those
 * chapters reference. Nothing here is EPUB and nothing here becomes EPUB:
 * the chapters render straight into the reader's web engine, the speech
 * path extracts them with the ordinary [XhtmlText] pass, and the file on
 * disk stays the file the user imported (`original.mobi`, `original.fb2`,
 * … — never an `original.epub`).
 *
 * The chapter XHTML is deliberately plain — `h1..h6`, `p`, `em`, `strong`,
 * `blockquote`, `table`, `img` — the vocabulary [XhtmlText] already reads
 * for structure and speech, and the vocabulary the reader's appearance
 * layer styles without help.
 */

/** Why a headless book refused to parse. The import path shows this to the
 *  user, so the messages name the file's own failure, not the parser's. */
sealed class BookDocError(message: String) : Exception(message) {
    /** The file is malformed — not the format it claims. */
    class Malformed(message: String) : BookDocError(message)

    /** The file parses as its format but the format's content is out of
     *  reach: a DRM-locked book, a variant no reader decodes. Distinct
     *  from [Malformed] because the import path tells the user something
     *  different — the file is fine, this app just can't read it. */
    class Unsupported(message: String) : BookDocError(message)

    /** The text is beyond the size cap. */
    class TooLarge(val charsK: Int, val capM: Int) :
        BookDocError("the text is ${charsK}k characters — beyond the ${capM}M cap")
}

/** One image a book carries: its cover, or an inline illustration. */
class BookImage(
    val name: String,
    val mime: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is BookImage && other.name == name && other.mime == mime && other.data.contentEquals(data)
    override fun hashCode(): Int = (31 * name.hashCode() + mime.hashCode()) * 31 + data.contentHashCode()
}

/** One chapter: the XHTML the reader serves and the speech path extracts. */
class BookChapter(
    val title: String?,
    val html: String,
) {
    override fun equals(other: Any?): Boolean =
        other is BookChapter && other.title == title && other.html == html
    override fun hashCode(): Int = 31 * (title?.hashCode() ?: 0) + html.hashCode()
}

/** One table-of-contents row: a label and the chapter it opens. */
class BookToc(
    val label: String,
    /** Index into [BookDoc.chapters]. */
    val chapterIndex: Int,
    /** Nesting depth, 0 = top level, carried to the manifest so the
     *  contents list indents the way the book's own did. */
    val depth: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is BookToc && other.label == label && other.chapterIndex == chapterIndex && other.depth == depth
    override fun hashCode(): Int = (31 * label.hashCode() + chapterIndex) * 31 + depth
}

/** A whole headless book, parsed. */
class BookDoc(
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    /** The cover, when the format carries one — what the shelf shows. */
    val cover: BookImage? = null,
    /** The reading order. An empty list is a refused import, the same rule
     *  an EPUB with no spine answers to. */
    val chapters: List<BookChapter> = emptyList(),
    /** The book's own contents, when the format carries one (a MOBI's
     *  CNCX, an FB2's nested sections). A format with none — plain text —
     *  leaves this empty and the manifest derives its TOC from the
     *  chapters' titles. */
    val toc: List<BookToc> = emptyList(),
    /** Every image the chapters reference, [BookImage.name] keyed. */
    val images: List<BookImage> = emptyList(),
) {
    /** Chapter [index]'s image by reference name (`000123` for an
     *  `img/000123` src), if the book carries it. */
    fun image(name: String): BookImage? =
        images.firstOrNull { it.name.removePrefix("img/") == name && it.name != name }

    override fun equals(other: Any?): Boolean =
        other is BookDoc && other.title == title && other.author == author &&
            other.language == language && other.cover == cover &&
            other.chapters == chapters && other.toc == toc && other.images == images
    override fun hashCode(): Int =
        (((((title?.hashCode() ?: 0) * 31 + (author?.hashCode() ?: 0)) * 31 +
            (language?.hashCode() ?: 0)) * 31 + (cover?.hashCode() ?: 0)) * 31 +
            chapters.hashCode()) * 31 + images.hashCode()

    companion object {
        /** The spine entry name for chapter [index] — the virtual path the
         *  reader's scheme handler serves the chapter's page under.
         *  `chapter-0`, `chapter-1`, …, 0-based like a PDF's `page-N`. */
        fun spineName(index: Int): String = "chapter-$index"

        /** The whole book's spine, in order. */
        fun spine(chapterCount: Int): List<String> = (0 until chapterCount).map { spineName(it) }
    }
}

/** Which parser a file extension goes to. One table, read by the import
 *  path, the BookDrop receiver, the file picker and the narration path —
 *  the same one-list discipline iOS's DocumentBook.supportedExtensions
 *  keeps. Ported from bookdoc.rs DocFamily. */
enum class DocFamily {
    /** `.mobi`/`.azw`/`.azw3`/`.prc`/`.pdb` — one parser, the whole
     *  Mobipocket/Kindle family (PalmDOC and KF8 variants alike). */
    Mobi,

    /** `.fb2` — FictionBook. */
    Fb2,

    /** `.rtf` — Rich Text. */
    Rtf,

    /** `.html`/`.htm`/`.htmlz` — web pages saved as books. */
    Html,

    /** `.txt`/`.text` — plain-text books. */
    Txt;

    companion object {
        /** The family a file extension imports as, or `null` when the
         *  extension is not a headless book format. */
        fun fromExtension(extension: String): DocFamily? = when (extension.lowercase()) {
            "mobi", "azw", "azw3", "prc", "pdb" -> Mobi
            "fb2" -> Fb2
            "rtf" -> Rtf
            "html", "htm", "htmlz" -> Html
            "txt", "text" -> Txt
            else -> null
        }

        /** Whether [extension] names a headless book format. The cheap half
         *  of [fromExtension], for lists that only need the yes/no. */
        fun isDocumentExtension(extension: String): Boolean = fromExtension(extension) != null
    }
}

/**
 * Wrap a chapter's body XHTML into the standalone page the reader's web
 * engine loads. The reader's own script is injected into every frame by the
 * view, so the page needs nothing but the body — no styles, no scripts of
 * its own: the appearance layer dresses it.
 */
fun chapterPage(title: String, bodyHtml: String): String =
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<title>${xmlEscape(title)}</title></head><body>$bodyHtml</body></html>"

/** Escape a string for text content and double-quoted attribute values. */
fun xmlEscape(value: String): String {
    val out = StringBuilder(value.length)
    for (c in value) {
        when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(c)
        }
    }
    return out.toString()
}

/** Latin-1: every byte is a glyph, byte for byte. The floor every decoding
 *  battery lands on, because it never fails. */
fun decodeLatin1(data: ByteArray): String {
    val out = StringBuilder(data.size)
    for (b in data) out.append((b.toInt() and 0xFF).toChar())
    return out.toString()
}

/**
 * The `.pdb` refusal: the extension is every Palm-era format's, not just
 * the books'. Said in the parser's own voice — the import path shows it
 * to the user. Ported from sn-store books.rs NOT_MOBIPOCKET_PDB.
 */
private const val NOT_MOBIPOCKET_PDB: String =
    "not a Mobipocket book — the .pdb extension is shared with " +
        "other Palm-era formats this app cannot read"

/**
 * The bytes for one virtual name the reader's scheme handler asks for.
 * `chapter-N` answers with the chapter's own standalone page, `img/<name>`
 * with an image the chapters reference — the cover under the name its XHTML
 * would carry even when no chapter does. A name outside the book is `null`:
 * the handler's 404, not a guess. Ported from sn-store ServedBookDoc.
 */
class ServedBookDoc(
    /** The original file, verbatim — what `original.<ext>` holds. */
    val bytes: ByteArray,
    /** The parsed book. */
    val doc: BookDoc,
) {
    /** The parsed book: its metadata, cover, chapters, contents and images. */
    fun book(): BookDoc = doc

    /** The bytes for one virtual name, or `null`. */
    fun read(name: String): ByteArray? {
        val chapterIndex = name.removePrefix("chapter-").toIntOrNull()
        if (name.startsWith("chapter-") && chapterIndex != null) {
            val chapter = doc.chapters.getOrNull(chapterIndex) ?: return null
            // The page's `<title>` is the chapter's own when the format
            // carried one; a heading-less chunk is "Chapter N", the same
            // label its contents row falls back to.
            val title = chapter.title ?: "Chapter ${chapterIndex + 1}"
            return chapterPage(title, chapter.html).toByteArray(Charsets.UTF_8)
        }
        if (name.startsWith("img/")) return image(name.removePrefix("img/"))?.data
        return null
    }

    /** The content type a served name carries: the image's own from the
     *  parse, XHTML for a chapter page, the generic stream otherwise. */
    fun mimeFor(name: String): String {
        if (name.startsWith("img/")) {
            image(name.removePrefix("img/"))?.let { return it.mime }
        }
        if (name.startsWith("chapter-")) return "text/html"
        return "application/octet-stream"
    }

    /** One image by its reference name — the `img/`-stripped side of
     *  [BookDoc.image], with the cover answering as `cover` even when no
     *  chapter references it. */
    private fun image(name: String): BookImage? {
        doc.image(name)?.let { return it }
        if (name == "cover") return doc.cover
        return null
    }

    companion object {
        /**
         * Parse [bytes] as the headless format [extension] names. The one
         * dispatch every document import and open goes through, so the
         * `.pdb` gate and the parser choice cannot drift apart. Ported from
         * sn-store ServedBookDoc::parse.
         */
        fun parse(extension: String, bytes: ByteArray): ServedBookDoc {
            val doc = when (DocFamily.fromExtension(extension)
                ?: throw BookDocError.Malformed("not a book format this app reads: .$extension")) {
                DocFamily.Mobi -> {
                    // A `.pdb` that is not a Mobipocket book gets the plain
                    // refusal before the parser runs — decoding some other
                    // Palm format as noise would only invent garbage.
                    if (extension == "pdb" && !BookMobiParser.looksLikeMobi(bytes)) {
                        throw BookDocError.Unsupported(NOT_MOBIPOCKET_PDB)
                    }
                    BookMobiParser.parse(bytes)
                }
                DocFamily.Fb2 -> BookFb2Parser.parse(bytes)
                DocFamily.Rtf -> BookRtfParser.parse(bytes)
                // The one family member whose extension changes the parser:
                // an HTMLZ is the ZIP calibre's converter writes, images
                // included.
                DocFamily.Html -> if (extension == "htmlz") {
                    BookHtmlParser.parseHtmlz(bytes)
                } else {
                    BookHtmlParser.parse(bytes)
                }
                DocFamily.Txt -> BookTxtParser.parse(bytes)
            }
            return ServedBookDoc(bytes, doc)
        }
    }
}
