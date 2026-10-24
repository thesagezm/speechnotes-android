package com.speechnotes.app.logic

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * A SAX delegate that collects each named element's full attribute map.
 * Used for relationship parts and slide-order lists, where only attributes
 * matter.
 */
internal class AttributeCollector(private val targets: Set<String>) : DefaultHandler() {

    private val captured = HashMap<String, List<Map<String, String>>>()

    fun captured(element: String): List<Map<String, String>> = captured[element] ?: emptyList()

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
        // Names on the bare (prefix-stripped) name: the relationship part's
        // elements are prefixed in some generators and bare in others.
        val bare = localName(qName, localName)
        if (bare !in targets) return
        val map = HashMap<String, String>()
        for (i in 0 until attributes.length) {
            map[attributes.getQName(i)] = attributes.getValue(i)
        }
        captured[bare] = (captured[bare] ?: emptyList()) + map
    }
}

/** Collects relationship rIds for a part, skipping external targets. */
internal fun relationships(data: String): Map<String, String> {
    val collector = AttributeCollector(setOf("Relationship"))
    parseXml(data, collector)
    val out = HashMap<String, String>()
    for (attrs in collector.captured("Relationship")) {
        val id = attrs["Id"] ?: continue
        val target = attrs["Target"] ?: continue
        if (attrs["TargetMode"] == "External") continue
        out[id] = target
    }
    return out
}

internal fun parseXml(xml: String, handler: org.xml.sax.ContentHandler): Boolean {
    val factory = javax.xml.parsers.SAXParserFactory.newInstance()
    factory.isNamespaceAware = false
    return try {
        // Off the network: XXE in a book/office file is an attack surface
        // with no upside for parsers that never resolve external entities.
        // Feature flags on SAXParserFactory vary by runtime — the
        // no-op/default state already refuses to resolve external entities
        // on the Android/runtime pairs this app ships on, so an unsupported
        // flag is a non-event, not a failure.
        runCatching {
            (factory as? javax.xml.parsers.SAXParserFactory)?.setFeature(
                "http://xml.org/sax/features/external-general-entities", false,
            )
            (factory as? javax.xml.parsers.SAXParserFactory)?.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false,
            )
            (factory as? javax.xml.parsers.SAXParserFactory)?.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false,
            )
        }
        factory.newSAXParser().xmlReader.apply { contentHandler = handler }
            .parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        // Some JVMs abort mid-document on malformed input: the caller checks
        // whether the delegate collected anything, so a FALSE here doesn't
        // have to mean the file is unreadable.
        true
    } catch (e: Exception) {
        false
    }
}

internal fun attributesToMap(attrs: Attributes): Map<String, String> {
    val map = HashMap<String, String>()
    for (i in 0 until attrs.length) map[attrs.getQName(i)] = attrs.getValue(i)
    return map
}

/** Splits a heading-annotated item stream into chapters and emits an EPUB. */
internal fun chapterize(items: List<DocumentItem>): List<DocumentChapter> {
    val chapters = ArrayList<DocumentChapter>()
    var currentTitle: String? = null
    val currentBlocks = ArrayList<DocumentBlock>()

    fun flushCarry() {
        val carry = currentTitle
        flushChapter(chapters, currentTitle, currentBlocks)
        currentTitle = carry
        currentBlocks.clear()
    }

    // A local flush that clears in place; flushCarry re-seeds the title so
    // continuation chapters keep the section name in the TOC.
    fun flush() {
        flushChapter(chapters, currentTitle, currentBlocks)
        currentTitle = null
        currentBlocks.clear()
    }

    fun addBlock(block: DocumentBlock) {
        currentBlocks.add(block)
        if (currentBlocks.size >= 150) flushCarry()
    }

    for (item in items) {
        when (item) {
            // A level 1-2 heading opens a chapter; deeper headings only open
            // one when nothing is open yet (documents whose only structure
            // is h3+).
            is DocumentItem.Heading -> if (item.level <= 2 || currentTitle == null) {
                flush()
                currentTitle = item.text
            } else {
                currentBlocks.add(DocumentBlock.Paragraph(item.text))
            }
            is DocumentItem.Paragraph -> addBlock(DocumentBlock.Paragraph(item.text))
            is DocumentItem.Image -> addBlock(DocumentBlock.Image(item.index))
            is DocumentItem.Table -> addBlock(DocumentBlock.Table(item.rows))
        }
    }
    flush()

    return chapters.ifEmpty { listOf(DocumentChapter(null, emptyList())) }
}

private fun flushChapter(
    out: MutableList<DocumentChapter>,
    title: String?,
    blocks: List<DocumentBlock>,
) {
    if (blocks.isNotEmpty() || title != null) {
        out.add(DocumentChapter(title, ArrayList(blocks)))
    }
}

// MARK: - DOCX

/**
 * WordprocessingML (DOCX) parser — heading structure, tables and images out
 * of word/document.xml, media targets resolved through
 * word/_rels/document.xml.rels. Ported from DocxParser (DocumentEpub.swift).
 */
internal object DocxParser {

    private class Paragraph(var text: String = "", var headingLevel: Int = 0)

    private class Delegate(
        private val relsByRID: Map<String, String>,
        private val pool: DocumentImagePool,
    ) : DefaultHandler() {

        val items = ArrayList<DocumentItem>()

        private var current: Paragraph? = null
        private var inText = false
        private var latestImageAlt = ""
        private val pendingImageIndexes = ArrayList<Int>()

        // Table state (nested tables' content is dropped — a table inside a
        // cell is rare in prose, and keeping only the outer row's text avoids
        // scrambled output).
        private var tblDepth = 0
        private val tableRows = ArrayList<List<DocumentTableCell>>()
        private val rowCells = ArrayList<DocumentTableCell>()
        private var rowIsHeader = false
        private var inCell = false
        private val cellLines = ArrayList<String>()
        private var cellLine = ""
        private var cellSpan = 1

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            val a = attributesToMap(attrs)
            when (localName(qName, localName)) {
                "p" -> {
                    if (tblDepth == 0) current = Paragraph() else if (inCell) cellLine = ""
                    inText = false
                }
                "pStyle" -> {
                    val paragraph = current ?: return
                    val style = a.prefixed("w:val") ?: a.values.firstOrNull { it.startsWith("Heading") } ?: ""
                    when {
                        style == "Title" -> paragraph.headingLevel = 1
                        style.startsWith("Heading") ->
                            paragraph.headingLevel = style.removePrefix("Heading").toIntOrNull()?.coerceIn(1, 6) ?: 0
                    }
                }
                "t" -> inText = true
                "tab" -> appendChar(" ")
                "br" -> appendChar("\n")
                "tbl" -> {
                    tblDepth++
                    if (tblDepth == 1) tableRows.clear()
                }
                "tr" -> if (tblDepth == 1) {
                    rowCells.clear()
                    rowIsHeader = false
                }
                "tblHeader" -> if (tblDepth == 1) rowIsHeader = true
                "tc" -> if (tblDepth == 1) {
                    inCell = true
                    cellLines.clear()
                    cellLine = ""
                    cellSpan = 1
                }
                "gridSpan" ->
                    cellSpan = maxOf(1, (a.prefixed("w:val") ?: a.values.firstOrNull() ?: "1").toIntOrNull() ?: 1)
                "blip" -> (a.prefixed("r:embed") ?: a.prefixed("r:link"))?.let { recordImage(it) }
                "imagedata" -> a.prefixed("r:id")?.let { recordImage(it) }
                "docPr" -> {
                    val alt = a.prefixed("descr") ?: a.prefixed("name") ?: ""
                    if (alt.isNotEmpty()) latestImageAlt = alt
                }
            }
        }

        private fun recordImage(rid: String) {
            // Images inside table cells are dropped for now — they complicate
            // the cell text model and are rare in prose documents.
            if (tblDepth != 0) return
            val target = relsByRID[rid] ?: return
            val alt = latestImageAlt
            latestImageAlt = ""
            pendingImageIndexes.add(pool.index(target, alt))
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) appendChar(String(ch, start, length))
        }

        private fun appendChar(value: String) {
            if (tblDepth == 0) current?.let { it.text += value }
            else if (inCell) cellLine += value
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (localName(qName, localName)) {
                "t" -> inText = false
                "p" -> if (tblDepth == 0) {
                    flushParagraphWithImages()
                } else if (inCell) {
                    cellLines.add(cellLine)
                    cellLine = ""
                }
                "tc" -> if (inCell && tblDepth == 1) {
                    val text = cellLines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                    rowCells.add(DocumentTableCell(text, cellSpan, rowIsHeader))
                    inCell = false
                }
                "tr" -> if (tblDepth == 1 && rowCells.isNotEmpty()) {
                    tableRows.add(ArrayList(rowCells))
                    rowCells.clear()
                }
                "tbl" -> {
                    tblDepth = maxOf(0, tblDepth - 1)
                    if (tblDepth == 0 && tableRows.isNotEmpty()) {
                        items.add(DocumentItem.Table(ArrayList(tableRows)))
                        tableRows.clear()
                    }
                }
            }
        }

        private fun flushParagraphWithImages() {
            val paragraph = current
            if (paragraph != null) {
                paragraph.text = paragraph.text.replace('\u00A0', ' ').trim()
                if (paragraph.text.isNotEmpty()) {
                    items.add(
                        if (paragraph.headingLevel > 0)
                            DocumentItem.Heading(paragraph.text, paragraph.headingLevel)
                        else DocumentItem.Paragraph(paragraph.text),
                    )
                }
            }
            current = null
            inText = false
            // The paragraph ended: its images (inline and anchored drawings
            // both live in runs of a w:p) land right after the text.
            for (index in pendingImageIndexes) items.add(DocumentItem.Image(index))
            pendingImageIndexes.clear()
        }
    }

    fun parse(data: ByteArray): List<DocumentChapter> = parseFull(data).chapters

    fun parseFull(archive: ByteArray): DocumentParseResult {
        val zip = ZipReader.parse(archive) ?: throw DocumentParseError.Malformed("not a ZIP container")
        val documentXML = zip.text("word/document.xml")
            ?: throw DocumentParseError.MissingEntry("word/document.xml")
        val relsData = zip.text("word/_rels/document.xml.rels")
        val relsByRID = relsData?.let { data ->
            relationships(data).mapValues { (_, target) -> DocumentMedia.resolveTarget("word", target) }
        } ?: emptyMap()

        val pool = DocumentImagePool()
        val delegate = Delegate(relsByRID, pool)
        if (!parseXml(documentXML, delegate) && delegate.items.isEmpty()) {
            throw DocumentParseError.Malformed("word/document.xml could not be parsed")
        }
        return DocumentParseResult(
            chapters = chapterize(delegate.items),
            images = DocumentMedia.resolvePool(pool, zip),
            cover = DocumentMedia.embeddedThumbnail(zip),
        )
    }
}

// MARK: - ODT

/**
 * OpenDocument Text parser — text:h / text:p, draw:image frames and
 * table:table out of content.xml. Ported from OdtParser (DocumentEpub.swift).
 */
internal object OdtParser {

    private class Delegate(private val pool: DocumentImagePool) : DefaultHandler() {

        val items = ArrayList<DocumentItem>()

        private var currentText: String? = null
        private var currentHeadingLevel = 0
        private var inside = false
        private val pendingImageIndexes = ArrayList<Int>()

        // Table state
        private var tblDepth = 0
        private var inHeaderRows = false
        private val tableRows = ArrayList<List<DocumentTableCell>>()
        private val rowCells = ArrayList<DocumentTableCell>()
        private var inCell = false
        private var cellIsCovered = false
        private val cellLines = ArrayList<String>()
        private var cellLine = ""
        private var cellSpan = 1

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            val a = attributesToMap(attrs)
            when (localName(qName, localName)) {
                "h" -> {
                    inside = true
                    currentText = ""
                    currentHeadingLevel = (a.prefixed("text:outline-level") ?: "1").toIntOrNull()?.coerceIn(1, 6) ?: 1
                }
                "p" -> {
                    inside = true
                    currentText = ""
                    currentHeadingLevel = 0
                    if (tblDepth > 0) cellLine = ""
                }
                "tab" -> append(" ")
                "line-break" -> append("\n")
                "s" -> append(" ".repeat(maxOf(1, (a.prefixed("text:c") ?: "1").toIntOrNull() ?: 1)))
                "table" -> {
                    tblDepth++
                    if (tblDepth == 1) tableRows.clear()
                }
                "table-header-rows" -> inHeaderRows = true
                "table-row" -> if (tblDepth == 1) rowCells.clear()
                "table-cell" -> if (tblDepth == 1) {
                    inCell = true
                    cellIsCovered = false
                    cellLines.clear()
                    cellLine = ""
                    cellSpan = maxOf(1, (a.prefixed("table:number-columns-spanned") ?: "1").toIntOrNull() ?: 1)
                }
                "covered-table-cell" -> if (tblDepth == 1) {
                    // The masked continuation of a spanned cell: HTML's colspan
                    // already covers it, emitting one would duplicate the column.
                    inCell = true
                    cellIsCovered = true
                    cellLines.clear()
                    cellLine = ""
                    cellSpan = 1
                }
                "image" -> if (tblDepth == 0) {
                    val href = a.prefixed("xlink:href") ?: a["href"]
                    if (!href.isNullOrEmpty()) {
                        pendingImageIndexes.add(pool.index(DocumentMedia.resolveTarget("", href), ""))
                    }
                }
            }
        }

        private fun append(value: String) {
            if (tblDepth > 0 && inCell) cellLine += value
            else if (inside) currentText = (currentText ?: "") + value
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            append(String(ch, start, length))
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (localName(qName, localName)) {
                "h", "p" -> if (tblDepth > 0 && inCell) {
                    cellLines.add(cellLine)
                    cellLine = ""
                } else if (inside) {
                    flushParagraph()
                }
                "table-cell", "covered-table-cell" -> if (inCell && tblDepth == 1) {
                    if (!cellIsCovered) {
                        val text = cellLines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                        rowCells.add(DocumentTableCell(text, cellSpan, inHeaderRows))
                    }
                    inCell = false
                    cellIsCovered = false
                }
                "table-row" -> if (tblDepth == 1 && rowCells.isNotEmpty()) {
                    tableRows.add(ArrayList(rowCells))
                    rowCells.clear()
                }
                "table-header-rows" -> inHeaderRows = false
                "table" -> {
                    tblDepth = maxOf(0, tblDepth - 1)
                    if (tblDepth == 0 && tableRows.isNotEmpty()) {
                        items.add(DocumentItem.Table(ArrayList(tableRows)))
                        tableRows.clear()
                    }
                }
                // Images sit in draw:frames, sometimes with no paragraph
                // around them — the frame close is their last chance to land.
                "frame" -> {
                    for (index in pendingImageIndexes) items.add(DocumentItem.Image(index))
                    pendingImageIndexes.clear()
                }
            }
        }

        private fun flushParagraph() {
            val text = currentText
            if (text != null) {
                val clean = text.replace('\u00A0', ' ').trim()
                if (clean.isNotEmpty()) {
                    items.add(
                        if (currentHeadingLevel > 0) DocumentItem.Heading(clean, currentHeadingLevel)
                        else DocumentItem.Paragraph(clean),
                    )
                }
            }
            currentText = null
            inside = false
            currentHeadingLevel = 0
            for (index in pendingImageIndexes) items.add(DocumentItem.Image(index))
            pendingImageIndexes.clear()
        }
    }

    fun parse(data: ByteArray): List<DocumentChapter> = parseFull(data).chapters

    fun parseFull(archive: ByteArray): DocumentParseResult {
        val zip = ZipReader.parse(archive) ?: throw DocumentParseError.Malformed("not a ZIP container")
        val contentXML = zip.text("content.xml") ?: throw DocumentParseError.MissingEntry("content.xml")
        val pool = DocumentImagePool()
        val delegate = Delegate(pool)
        if (!parseXml(contentXML, delegate) && delegate.items.isEmpty()) {
            throw DocumentParseError.Malformed("content.xml could not be parsed")
        }
        return DocumentParseResult(
            chapters = chapterize(delegate.items),
            images = DocumentMedia.resolvePool(pool, zip),
            cover = DocumentMedia.embeddedThumbnail(zip),
        )
    }
}
