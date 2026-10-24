package com.speechnotes.app.logic

/**
 * Turns DocumentChapters into a valid EPUB 3 (with NCX fallback) the existing
 * import pipeline, epub.js reader and TTS spine pipeline consume unchanged.
 * Store-only zip, mimetype first. Ported from DocumentEpubConverter
 * (DocumentEpub.swift, speechnotes-ios).
 */
object DocumentEpubConverter {

    private const val LANGUAGE = "en"

    /** Document-reader CSS: images full width, tables with real collapsed borders. */
    private val chapterCSS = """
        <style>
          img.doc-image { width: 100%; height: auto; display: block; margin: 0.6em auto; }
          img.doc-image-inline { max-width: 100%; height: auto; display: block; margin: 0.6em auto; }
          div.doc-table-wrap { overflow-x: auto; width: 100%; }
          table.doc-table { border-collapse: collapse; margin: 0.8em 0; }
          table.doc-table th, table.doc-table td { border: 1px solid #8a8a8a; padding: 4px 7px; text-align: left; vertical-align: top; }
          table.doc-table th { background: rgba(128, 128, 128, 0.15); font-weight: 600; }
        </style>
    """.trimIndent()

    fun epubData(
        chapters: List<DocumentChapter>,
        title: String,
        author: String?,
        identifier: String = java.util.UUID.randomUUID().toString(),
        images: List<DocumentImage> = emptyList(),
        cover: DocumentImage? = null,
    ): ByteArray {
        val files = ArrayList<Pair<String, ByteArray>>()
        files.add("mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8))
        files.add(
            "META-INF/container.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

        val manifest = StringBuilder()
        val spine = StringBuilder()
        val nav = StringBuilder()
        val ncx = StringBuilder()

        // Media entries first (chapters reference them by href). A class per
        // image: real figures go full width, small icons keep size.
        val imageHREFs = ArrayList<String>()
        val imageClasses = ArrayList<String>()
        for ((index, image) in images.withIndex()) {
            val ext = DocumentMedia.extensionForMime(image.mime)
            val href = "images/img${index + 1}.$ext"
            imageHREFs.add(href)
            val (width, height) = ZipWriter.pixelSize(image.data) ?: (0 to 0)
            imageClasses.add(if (width >= 320 && height >= 320) "doc-image" else "doc-image-inline")
            manifest.append("<item id=\"img${index + 1}\" href=\"$href\" media-type=\"${image.mime}\"/>\n")
            files.add("OEBPS/$href" to image.data)
        }

        if (cover != null) {
            val ext = DocumentMedia.extensionForMime(cover.mime)
            // A COVER PAGE, first in the spine. The `cover-image` manifest
            // property only feeds the shelf thumbnail; opening a normalized
            // book (docx, odt…) went straight to chapter 1 with no cover at
            // all. A spine entry that is the cover image, marked
            // `rendition:cover-*`, is what every real EPUB does and what
            // epub.js renders as a full-page first item.
            manifest.append("<item id=\"cover-image\" href=\"cover.$ext\" media-type=\"${cover.mime}\" properties=\"cover-image\"/>\n")
            files.add("OEBPS/cover.$ext" to cover.data)
            spine.append("<itemref idref=\"coverpage\" linear=\"yes\"/>\n")
            nav.append("<li><a href=\"coverpage.xhtml\">Cover</a></li>\n")
            ncx.append("""
                <navPoint id="np0" playOrder="1">
                    <navLabel><text>Cover</text></navLabel>
                    <content src="coverpage.xhtml"/>
                </navPoint>
            """.trimIndent())
            manifest.append("<item id=\"coverpage\" href=\"coverpage.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            files.add("OEBPS/coverpage.xhtml" to coverPageXHTML("cover.$ext").toByteArray(Charsets.UTF_8))
        }

        for ((index, chapter) in chapters.withIndex()) {
            val id = "ch${index + 1}"
            val href = "$id.xhtml"
            val label = chapter.title ?: "Chapter ${index + 1}"
            manifest.append("<item id=\"$id\" href=\"$href\" media-type=\"application/xhtml+xml\"/>\n")
            spine.append("<itemref idref=\"$id\"/>\n")
            nav.append("<li><a href=\"$href\">${escape(label)}</a></li>\n")
            ncx.append("""
                <navPoint id="np${index + 1}" playOrder="${index + 1}">
                  <navLabel><text>${escape(label)}</text></navLabel>
                  <content src="$href"/>
                </navPoint>
            """.trimIndent())
            files.add("OEBPS/$href" to chapterXHTML(chapter, index + 1, imageHREFs, imageClasses).toByteArray(Charsets.UTF_8))
        }

        val creatorLine = author?.let { "<dc:creator>${escape(it)}</dc:creator>" } ?: ""
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="$LANGUAGE">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:uuid:$identifier</dc:identifier>
                <dc:title>${escape(title)}</dc:title>
                $creatorLine
                <dc:language>$LANGUAGE</dc:language>
                <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                $manifest
              </manifest>
              <spine toc="ncx">
                $spine
              </spine>
            </package>
        """.trimIndent()
        files.add("OEBPS/content.opf" to opf.toByteArray(Charsets.UTF_8))

        val navDoc = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$LANGUAGE">
              <head><meta charset="utf-8"/><title>Contents</title></head>
              <body>
                <nav epub:type="toc" id="toc">
                  <ol>
                    $nav
                  </ol>
                </nav>
              </body>
            </html>
        """.trimIndent()
        files.add("OEBPS/nav.xhtml" to navDoc.toByteArray(Charsets.UTF_8))

        val ncxDoc = """
            <?xml version="1.0" encoding="utf-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="urn:uuid:$identifier"/><meta name="dtb:depth" content="1"/></head>
              <docTitle><text>${escape(title)}</text></docTitle>
              <navMap>
                $ncx
              </navMap>
            </ncx>
        """.trimIndent()
        files.add("OEBPS/toc.ncx" to ncxDoc.toByteArray(Charsets.UTF_8))

        return ZipWriter.archive(files)
    }

    /** The book's cover as the first spine item — a full-page image, no chrome. */
    private fun coverPageXHTML(href: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
          <head><meta charset="utf-8"/>$chapterCSS<title>Cover</title></head>
          <body>
            <p class="doc-image-wrap"><img class="doc-image" src="$href" alt="Cover"/></p>
          </body>
        </html>
    """.trimIndent()

    private fun chapterXHTML(
        chapter: DocumentChapter,
        index: Int,
        imageHREFs: List<String>,
        imageClasses: List<String>,
    ): String {
        val body = StringBuilder()
        chapter.title?.let { body.append("<h1>${escape(it)}</h1>\n") }
        for (block in chapter.blocks) {
            when (block) {
                is DocumentBlock.Paragraph -> body.append("<p>${escape(block.text)}</p>\n")
                is DocumentBlock.Image -> {
                    if (block.index !in imageHREFs.indices) continue
                    val cls = imageClasses.getOrNull(block.index) ?: "doc-image-inline"
                    body.append("<p class=\"doc-image-wrap\"><img class=\"$cls\" src=\"${imageHREFs[block.index]}\" alt=\"\"/></p>\n")
                }
                is DocumentBlock.Table -> body.append("<div class=\"doc-table-wrap\">\n${tableXHTML(block.rows)}</div>\n")
                is DocumentBlock.Html -> body.append(inlineMarkupXHTML(block.markup, imageHREFs)).append("\n")
            }
        }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
              <head><meta charset="utf-8"/>$chapterCSS<title>Chapter $index</title></head>
              <body>
                $body
              </body>
            </html>
        """.trimIndent()
    }

    /** Rows → XHTML. `colspan` keeps merged columns aligned; header cells render as `th`. */
    private fun tableXHTML(rows: List<List<DocumentTableCell>>): String {
        val html = StringBuilder("<table class=\"doc-table\">\n")
        for (row in rows) {
            html.append("<tr>\n")
            for (cell in row) {
                val tag = if (cell.isHeader) "th" else "td"
                val span = if (cell.columnSpan > 1) " colspan=\"${cell.columnSpan}\"" else ""
                // Multi-line cell text keeps its line breaks.
                val content = escape(cell.text).replace("\n", "<br/>")
                html.append("<$tag$span>$content</$tag>\n")
            }
            html.append("</tr>\n")
        }
        html.append("</table>\n")
        return html.toString()
    }

    /**
     * Format-native markup (mobi) → chapter body. Closes the void tags old
     * HTML leaves open (`<br>`, `<img …>`), escapes bare ampersands, and
     * swaps the parser's image tokens for real pool references.
     */
    private fun inlineMarkupXHTML(markup: String, imageHREFs: List<String>): String {
        var out = stripUndefinedNamespacePrefixes(markup)
        out = replace(out, Regex("""data-pool-index="(\d+)""")) { match ->
            val index = match.groupValues[1].toIntOrNull()
            if (index != null && index in imageHREFs.indices) imageHREFs[index] else ""
        }
        // Void tags → self-closing, attributes untouched.
        out = replace(out, Regex("""<(br|hr|img|meta|link)((?:[^>"]|"[^"]*")*?)(/?)>""")) { match ->
            "<${match.groupValues[1]}${match.groupValues[2]}/>"
        }
        // A stray `<` that opens nothing breaks the chapter's XML — escape it
        // so the glitch renders as text instead of truncating the chapter there.
        out = out.replace(Regex("""<(?!/?[A-Za-z][^>]*>|!--|!\[CDATA\[)"""), "&lt;")
        // Bare `&` → `&amp;` (an existing entity is left alone).
        out = out.replace(Regex("""&(?!(?:[A-Za-z][A-Za-z0-9]*|#\d+|#x[0-9A-Fa-f]+);)"""), "&amp;")
        // Control characters XML forbids.
        out = out.filter { it.code > 0x1F || it == '\t' || it == '\n' || it == '\r' }
        return out
    }

    /**
     * Drops `prefix:name="…"` attributes (epub:type is the common one — KF8
     * section tags carry it) from format-native markup that declares no
     * namespaces of its own. The generated chapter wrapper declares only the
     * XHTML namespace, so an undefined prefix is a hard XML parse error and
     * the reader stops rendering AT that attribute. The attributes are
     * reading-order semantics, never rendered; dropping them loses nothing
     * the reader shows.
     */
    private fun stripUndefinedNamespacePrefixes(markup: String): String {
        if (markup.contains("xmlns:")) return markup
        return replaceAll(markup, Regex("""\s+[A-Za-z][A-Za-z0-9_.-]*:[A-Za-z][A-Za-z0-9_.-]*\s*=\s*"[^"]*\""""), "")
    }

    /** Tag-stripped text of native markup — the speech path for `.html` blocks. */
    private fun strippedText(markup: String): String =
        XhtmlText.plainText(markup.replace(Regex("""<[^>]*>"""), " "))

    private fun replace(input: String, pattern: Regex, handler: (MatchResult) -> String): String =
        pattern.replace(input, transform = handler)

    private fun replaceAll(input: String, pattern: Regex, replacement: String): String =
        pattern.replace(input, replacement)

    /** XML 1.0 text-node escape, plus control characters XML forbids. */
    fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> if (c.code > 0x1F || c == '\t' || c == '\n' || c == '\r') append(c)
            }
        }
    }
}
