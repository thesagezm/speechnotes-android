package com.speechnotes.app.logic

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

// MARK: - PPTX (slides as chapters)

/**
 * PowerPoint parser — one chapter per slide, slide ORDER resolved through
 * ppt/presentation.xml + its relationships (rIds), numeric-name sort as the
 * fallback. Ported from PptxParser (DocumentEpub.swift).
 */
internal object PptxParser {

    private fun readEntry(zip: ZipReader, path: String): String? =
        zip.text(path) ?: zip.textIgnoringCase(path)

    private class SlideDelegate(
        private val relsByRID: Map<String, String>,
        private val pool: DocumentImagePool,
    ) : DefaultHandler() {

        var title: String? = null
        val items = ArrayList<DocumentItem>()
        private var inTitleShape = false
        private var currentText: String? = null
        private val pendingImageIndexes = ArrayList<Int>()

        // Table state (a:tbl inside p:graphicFrame)
        private var tblDepth = 0
        private val tableRows = ArrayList<List<DocumentTableCell>>()
        private val rowCells = ArrayList<DocumentTableCell>()
        private var nextRowIsHeader = false
        private var inCell = false
        private val cellLines = ArrayList<String>()
        private var cellLine = ""
        private var cellSpan = 1

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            val a = attributesToMap(attrs)
            when (localName(qName, localName)) {
                "ph" -> {
                    inTitleShape = a.prefixed("type").let { it == "title" || it == "ctrTitle" }
                }
                "p" -> if (tblDepth > 0 && inCell) {
                    cellLine = ""
                } else {
                    currentText = ""
                }
                "t" -> if (currentText == null && !(tblDepth > 0 && inCell)) currentText = ""
                "blip" -> (a.prefixed("r:embed") ?: a.prefixed("r:link"))?.let { rid ->
                    relsByRID[rid]?.let { pendingImageIndexes.add(pool.index(DocumentMedia.resolveTarget("ppt/slides", it), "")) }
                }
                "tbl" -> {
                    tblDepth++
                    if (tblDepth == 1) {
                        tableRows.clear()
                        nextRowIsHeader = false
                    }
                }
                "tblPr" -> if (tblDepth == 1 && (a.prefixed("firstRow") ?: "0") == "1") nextRowIsHeader = true
                "tr" -> if (tblDepth == 1) rowCells.clear()
                "tc" -> if (tblDepth == 1) {
                    inCell = true
                    cellLines.clear()
                    cellLine = ""
                    cellSpan = maxOf(1, (a["gridSpan"] ?: "1").toIntOrNull() ?: 1)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (tblDepth > 0 && inCell) {
                cellLine += String(ch, start, length)
            } else {
                currentText = (currentText ?: "") + String(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (localName(qName, localName)) {
                "p" -> if (tblDepth > 0 && inCell) {
                    cellLines.add(cellLine)
                    cellLine = ""
                } else {
                    val text = currentText?.replace('\u00A0', ' ')?.trim()
                    if (!text.isNullOrEmpty()) {
                        if (inTitleShape && title == null) title = text else items.add(DocumentItem.Paragraph(text))
                    }
                    currentText = null
                }
                // The whole picture shape ended — its image lands as a block.
                "pic" -> {
                    for (index in pendingImageIndexes) items.add(DocumentItem.Image(index))
                    pendingImageIndexes.clear()
                }
                "tc" -> if (inCell && tblDepth == 1) {
                    val text = cellLines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                    rowCells.add(DocumentTableCell(text, cellSpan, nextRowIsHeader))
                    inCell = false
                }
                "tr" -> if (tblDepth == 1 && rowCells.isNotEmpty()) {
                    tableRows.add(ArrayList(rowCells))
                    rowCells.clear()
                    nextRowIsHeader = false
                }
                "tbl" -> {
                    tblDepth = maxOf(0, tblDepth - 1)
                    if (tblDepth == 0 && tableRows.isNotEmpty()) {
                        items.add(DocumentItem.Table(ArrayList(tableRows)))
                        tableRows.clear()
                    }
                }
                "sp" -> inTitleShape = false
            }
        }
    }

    fun parse(data: ByteArray): List<DocumentChapter> = parseFull(data).chapters

    fun parseFull(archive: ByteArray): DocumentParseResult {
        val zip = ZipReader.parse(archive) ?: throw DocumentParseError.Malformed("not a ZIP container")
        val slidePaths = orderedSlidePaths(zip)
        if (slidePaths.isEmpty()) throw DocumentParseError.Malformed("no slides found")

        val pool = DocumentImagePool()
        val chapters = ArrayList<DocumentChapter>()
        for ((index, path) in slidePaths.withIndex()) {
            // This slide's own relationships — targets are relative to
            // ppt/slides/.
            val relsPath = directory(path) + "/_rels/" + fileName(path) + ".rels"
            val slideRels = readEntry(zip, relsPath)?.let { data ->
                relationships(data).mapValues { (_, target) -> DocumentMedia.resolveTarget("ppt/slides", target) }
            } ?: emptyMap()
            // The presentation-level rels are a secondary source for decks
            // whose slides lack a rels part.
            val merged = HashMap(slideRels)
            presentationRels(zip).forEach { (k, v) -> merged.putIfAbsent(k, v) }

            val xml = readEntry(zip, path) ?: throw DocumentParseError.MissingEntry(path)
            val delegate = SlideDelegate(merged, pool)
            if (!parseXml(xml, delegate) && delegate.items.isEmpty() && delegate.title == null) {
                throw DocumentParseError.Malformed("slide ${index + 1}: XML error")
            }
            val label = delegate.title ?: "Slide ${index + 1}"
            chapters.add(DocumentChapter(label, delegate.items.mapNotNull { it.asBlock() }))
        }
        return DocumentParseResult(
            chapters = chapters,
            images = DocumentMedia.resolvePool(pool, zip),
            cover = DocumentMedia.embeddedThumbnail(zip),
        )
    }

    private var cachedPresentationRels: Map<String, String>? = null

    private fun presentationRels(zip: ZipReader): Map<String, String> {
        cachedPresentationRels?.let { return it }
        val data = readEntry(zip, "ppt/_rels/presentation.xml.rels")
        val map = data?.let {
            relationships(it).mapValues { (_, target) -> DocumentMedia.resolveTarget("ppt", target) }
        } ?: emptyMap()
        cachedPresentationRels = map
        return map
    }

    /**
     * Reads ppt/presentation.xml's sldIdLst (rIds) and maps them through
     * ppt/_rels/presentation.xml.rels to slide files. Any deck that ships
     * without one of the two falls back to natural numeric sort of
     * ppt/slides/slideN.xml.
     */
    private fun orderedSlidePaths(zip: ZipReader): List<String> {
        val names = zip.names
        val rels = presentationRels(zip)

        val ordered = ArrayList<String>()
        val presentation = readEntry(zip, "ppt/presentation.xml")
        if (presentation != null) {
            val collector = AttributeCollector(setOf("sldId"))
            parseXml(presentation, collector)
            for (attrs in collector.captured("sldId")) {
                // r:id is the namespaced attribute; SAX hands the qualified
                // name through.
                val rId = attrs.prefixed("r:id") ?: attrs["id"] ?: continue
                val path = rels[rId] ?: continue
                if (names.contains(path) && path !in ordered) ordered.add(path)
            }
        }
        if (ordered.isEmpty()) {
            ordered.addAll(
                names.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                    .sortedBy { slideNumber(it) },
            )
        }
        return ordered
    }

    private fun slideNumber(path: String): Int {
        val stem = path.removeSuffix(".xml").substringAfterLast('/')
        val digits = stem.filter { it.isDigit() }
        return digits.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun directory(path: String): String = path.substringBeforeLast('/', "")
    private fun fileName(path: String): String = path.substringAfterLast('/')
}

// MARK: - ODP (slides as chapters)

/**
 * OpenDocument Presentation parser — one chapter per draw:page; the frame
 * marked presentation:class="title" provides the chapter title. Ported from
 * OdpParser (DocumentEpub.swift).
 */
internal object OdpParser {

    private class Delegate(private val pool: DocumentImagePool) : DefaultHandler() {

        val pages = ArrayList<DocumentChapter>()
        private var currentTitle: String? = null
        private val pageBlocks = ArrayList<DocumentBlock>()
        private var inTitleFrame = false
        private var inPage = false
        private var currentText: String? = null
        private val pendingImageIndexes = ArrayList<Int>()

        // Table state — same shape as ODT's
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
                "page" -> {
                    inPage = true
                    currentTitle = null
                    pageBlocks.clear()
                }
                "frame" -> inTitleFrame = inPage && a.prefixed("presentation:class") == "title"
                "h", "p" -> if (inPage) {
                    if (tblDepth > 0 && inCell) cellLine = "" else currentText = ""
                }
                // Cell images are dropped for the same block-ordering reason.
                "image" -> if (inPage && tblDepth == 0) {
                    val href = a.prefixed("xlink:href") ?: a["href"]
                    if (!href.isNullOrEmpty()) {
                        pendingImageIndexes.add(pool.index(DocumentMedia.resolveTarget("", href), ""))
                    }
                }
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
                    inCell = true
                    cellIsCovered = true
                    cellLines.clear()
                    cellLine = ""
                    cellSpan = 1
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (tblDepth > 0 && inCell) {
                cellLine += String(ch, start, length)
            } else {
                currentText = (currentText ?: "") + String(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (localName(qName, localName)) {
                "h", "p" -> if (tblDepth > 0 && inCell) {
                    cellLines.add(cellLine)
                    cellLine = ""
                } else {
                    val text = currentText?.replace('\u00A0', ' ')?.trim()
                    if (!text.isNullOrEmpty()) {
                        if (inTitleFrame && currentTitle == null) currentTitle = text
                        else pageBlocks.add(DocumentBlock.Paragraph(text))
                    }
                    currentText = null
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
                        pageBlocks.add(DocumentBlock.Table(ArrayList(tableRows)))
                        tableRows.clear()
                    }
                }
                "frame" -> {
                    inTitleFrame = false
                    // Page images can sit in frames with no text around them.
                    for (index in pendingImageIndexes) pageBlocks.add(DocumentBlock.Image(index))
                    pendingImageIndexes.clear()
                }
                "page" -> if (inPage) {
                    pages.add(DocumentChapter(currentTitle ?: "Slide ${pages.size + 1}", ArrayList(pageBlocks)))
                    pageBlocks.clear()
                    inPage = false
                }
            }
        }
    }

    fun parse(data: ByteArray): List<DocumentChapter> = parseFull(data).chapters

    fun parseFull(archive: ByteArray): DocumentParseResult {
        val zip = ZipReader.parse(archive) ?: throw DocumentParseError.Malformed("not a ZIP container")
        val contentXML = zip.text("content.xml") ?: throw DocumentParseError.MissingEntry("content.xml")
        val pool = DocumentImagePool()
        val delegate = Delegate(pool)
        if (!parseXml(contentXML, delegate) && delegate.pages.isEmpty()) {
            throw DocumentParseError.Malformed("content.xml could not be parsed")
        }
        return DocumentParseResult(
            chapters = delegate.pages.ifEmpty { listOf(DocumentChapter("Slide 1", emptyList())) },
            images = DocumentMedia.resolvePool(pool, zip),
            cover = DocumentMedia.embeddedThumbnail(zip),
        )
    }
}
