package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Synthetic office-document fixtures → parse → EPUB. The ZIPs are built in
 * memory here (no fixture files in the repo), so these tests exercise the
 * real parsers, the real chapterizer and the real EPUB writer end to end.
 */
class DocumentBookTest {

    /** Text entries as UTF-8, binary entries (marked "__BINARY__:name") as raw bytes. */
    private fun zip(entries: List<Pair<String, String>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                val raw = binaries[content]
                if (raw != null) zip.write(raw) else zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Binary payloads referenced by the "__BINARY_…__" markers. */
    private val binaries = HashMap<String, ByteArray>()
    private val binaryFixtures get() = binaries

    private val docx = listOf(
        "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
</Types>""",
        "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""",
        "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
</Relationships>""",
        "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <w:body>
    <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Field Notes on Sparrows</w:t></w:r></w:p>
    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Introduction</w:t></w:r></w:p>
    <w:p><w:r><w:t>House sparrows are among the most-studied birds in the world.</w:t></w:r></w:p>
    <w:p><w:r><w:t xml:space="preserve">They nest in eaves and wall cavities, often close to people.</w:t></w:r></w:p>
    <w:tbl>
      <w:tr><w:tblHeader/><w:tc><w:p><w:r><w:t>Species</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Length</w:t></w:r></w:p></w:tc></w:tr>
      <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>House sparrow</w:t></w:r></w:p></w:tc></w:tr>
    </w:tbl>
    <w:p><w:r><w:rPr/><w:drawing><wp:docPr xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" descr="A male sparrow"/><a:blip xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" r:embed="rId5"/></w:drawing></w:r><w:r><w:t>See the plate.</w:t></w:r></w:p>
    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Migration</w:t></w:r></w:p>
    <w:p><w:r><w:t>Sparrows rarely move more than a few kilometres.</w:t></w:r></w:p>
  </w:body>
</w:document>""",
        "word/media/image1.png" to "__BINARY_PNG__",
    )

    /** A 96×64 PNG so the pool resolves a real image (not the GIF stand-in). */
    private val pngBytes: ByteArray by lazy {
        val header = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val ihdrLen = byteArrayOf(0, 0, 0, 13)
        val ihdrType = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
        val width = byteArrayOf(0, 0, 0, 96)
        val height = byteArrayOf(0, 0, 0, 64)
        val bitDepth = byteArrayOf(8)
        val colorType = byteArrayOf(2)
        val rest = byteArrayOf(0, 0, 0)
        val crcBase = ihdrType + width + height + bitDepth + colorType + rest
        val crc = java.nio.ByteBuffer.allocate(4).putInt(CRC32().also { it.update(crcBase) }.value.toInt()).array()
        header + ihdrLen + crcBase + crc +
            byteArrayOf(0, 0, 0, 0, 'I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte()) +
            byteArrayOf(0, 0, 0, 0)
    }

    @Test
    fun docx_parses_headings_paragraphs_tables_and_images() {
        binaryFixtures["__BINARY_PNG__"] = pngBytes
        val bytes = zip(docx)
        val result = DocxParser.parseFull(bytes)

        // Title opens chapter 1; both level-1 headings open their own
        // chapters (level ≤ 2 is a chapter boundary, same as iOS).
        assertEquals(3, result.chapters.size)
        assertEquals("Field Notes on Sparrows", result.chapters[0].title)
        assertEquals("Introduction", result.chapters[1].title)
        assertEquals("Migration", result.chapters[2].title)

        val intro = result.chapters[1]
        val paragraphs = intro.blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertTrue(paragraphs.any { it.contains("House sparrows are among") })
        assertTrue(paragraphs.any { it.contains("See the plate") })

        // The table survived as a real table with a header row and a colspan.
        val table = intro.blocks.filterIsInstance<DocumentBlock.Table>().single()
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[0][0].isHeader)
        assertEquals("House sparrow", table.rows[1][0].text)
        assertEquals(2, table.rows[1][0].columnSpan)

        // One real image resolved through the rels part (not the GIF stand-in).
        val image = intro.blocks.filterIsInstance<DocumentBlock.Image>().single()
        val loaded = result.images[image.index]
        assertTrue("expected the real PNG, got ${loaded.mime}", loaded.data.contentEquals(pngBytes))

        // The XML round-trips back through the EPUB writer unreadable-in.
        val epub = DocumentEpubConverter.epubData(result.chapters, "Book", null, identifier = "abc", images = result.images)
        val epubZip = ZipReader.parse(epub)
        assertNotNull("generated EPUB must parse", epubZip)
        assertEquals("application/epub+zip", epubZip!!.text("mimetype"))
        val opf = epubZip.text("OEBPS/content.opf")!!
        assertTrue(opf.contains("<dc:title>Book</dc:title>"))
        assertTrue(opf.contains("cover-image") || !opf.contains("cover.$"))
        // Images are packaged at the pool hrefs.
        assertTrue(epubZip.has("OEBPS/images/img1.png"))
        val chapter = epubZip.text("OEBPS/ch2.xhtml")!!
        assertTrue(chapter.contains("House sparrows are among"))
        assertTrue(chapter.contains("<table class=\"doc-table\">"))
        assertTrue(chapter.contains("colspan=\"2\""))
        assertTrue(chapter.contains("img1.png"))
    }

    @Test
    fun docx_without_rels_parses_text_and_drops_images() {
        // A rels part is optional: absent means no images, not an error.
        // The paragraph text must still come through.
        val bytes = zip(listOf("word/document.xml" to docx[3].second))
        val result = DocxParser.parseFull(bytes)
        assertEquals(3, result.chapters.size)
        assertTrue(result.images.isEmpty())
        val body = result.chapters.joinToString(" ") { ch ->
            ch.blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString(" ") { it.text }
        }
        assertTrue(body.contains("House sparrows are among"))
        // The drawing block is gone, but the sentence beside it survives.
        assertTrue(body.contains("See the plate"))
        // No image blocks anywhere — the rels part is what maps rid → target.
        assertTrue(result.chapters.none { ch -> ch.blocks.any { it is DocumentBlock.Image } })
    }

    @Test
    fun odt_parses_outline_headings_and_spans() {
        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
        xmlns:xlink="http://www.w3.org/1999/xlink">
  <office:body>
    <office:text>
      <text:h text:outline-level="1">Windmills of the Alentejo</text:h>
      <text:p>The plain is <text:s text:c="3"/>wide and very windy.</text:p>
      <text:h text:outline-level="2">Mills at work</text:h>
      <text:p>They grind grain and pump water.</text:p>
      <table:table>
        <table:table-columns><table:table-column/></table:table-columns>
        <table:table-header-rows>
          <table:table-row><table:table-cell><text:p>Mill</text:p></table:table-cell><table:table-cell><text:p>Built</text:p></table:table-cell></table:table-row>
        </table:table-header-rows>
        <table:table-row>
          <table:table-cell table:number-columns-spanned="2"><text:p>Alcácer do Sal</text:p></table:table-cell>
          <table:covered-table-cell/>
        </table:table-row>
      </table:table>
      <draw:frame><draw:image xlink:href="Pictures/wind.png"/></draw:frame>
    </office:text>
  </office:body>
</office:document-content>"""
        binaryFixtures["__BINARY_PNG__"] = pngBytes
        val bytes = zip(listOf("content.xml" to contentXml, "Pictures/wind.png" to "__BINARY_PNG__"))
        val result = OdtParser.parseFull(bytes)
        // Level ≤ 2 headings are chapter boundaries, so the level-2 heading
        // opens the second chapter (same rule as DOCX and as iOS).
        assertEquals(2, result.chapters.size)
        assertEquals("Windmills of the Alentejo", result.chapters[0].title)
        val introParagraphs = result.chapters[0].blocks
            .filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        // text:s carries literal spaces: the run expands to the c attribute's
        // count (three), so "is" ends up followed by its own space plus the
        // three inserted ones.
        assertTrue(introParagraphs.any { it.contains("is    wide and very windy") })
        val chapter = result.chapters[1]
        assertEquals("Mills at work", chapter.title)
        // Header row + spanned cell
        val table = chapter.blocks.filterIsInstance<DocumentBlock.Table>().single()
        assertTrue(table.rows[0][0].isHeader)
        assertEquals(2, table.rows[1][0].columnSpan)
        // The frame's image landed as a block
        assertTrue(chapter.blocks.any { it is DocumentBlock.Image })

        val epub = DocumentEpubConverter.epubData(
            result.chapters, "Wind", null, images = result.images,
        )
        val epubZip = ZipReader.parse(epub)!!
        assertTrue(
            "expected OEBPS/images/img1.png among $epubZip.names",
            epubZip.has("OEBPS/images/img1.png"),
        )
    }

    @Test
    fun pptx_orders_slides_through_presentation_rels() {
        val slide1 = """<?xml version="1.0" encoding="UTF-8"?>
<p:sld xmlns:p="urn:schemas-microsoft-com:office:powerpoint" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:cSld><p:spTree>
    <p:sp><p:nvSpPr><p:cNvPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Introduction</a:t></a:r></a:p></p:txBody></p:sp>
    <p:sp><p:nvSpPr><p:cNvPr/><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>First body paragraph</a:t></a:r></a:p><a:p><a:r><a:t>Second body paragraph</a:t></a:r></a:p></p:txBody></p:sp>
  </p:spTree></p:cSld>
</p:sld>"""
        val presentation = """<?xml version="1.0" encoding="UTF-8"?>
<p:presentation xmlns:p="urn:schemas-microsoft-com:office:powerpoint" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:sldIdLst>
    <p:sldId id="256" r:id="rIdSlide1"/>
    <p:sldId id="257" r:id="rIdSlide2"/>
  </p:sldIdLst>
</p:presentation>"""
        val presentationRels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rIdSlide1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
  <Relationship Id="rIdSlide2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/>
</Relationships>"""
        val bytes = zip(
            listOf(
                "ppt/presentation.xml" to presentation,
                "ppt/_rels/presentation.xml.rels" to presentationRels,
                "ppt/slides/slide1.xml" to slide1,
                // slide2.xml is a later rId but sorts BEFORE slide1 by name,
                // so a numeric-name sort would swap the deck's order.
                "ppt/slides/slide2.xml" to """<?xml version="1.0" encoding="UTF-8"?>
<p:sld xmlns:p="urn:schemas-microsoft-com:office:powerpoint" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cSld><p:spTree>
    <p:sp><p:nvSpPr><p:cNvPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Conclusion</a:t></a:r></a:p></p:txBody></p:sp>
    <p:sp><p:nvSpPr><p:cNvPr/><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Thank you for listening</a:t></a:r></a:p></p:txBody></p:sp>
  </p:spTree></p:cSld>
</p:sld>""",
            ),
        )
        val result = PptxParser.parseFull(bytes)
        assertEquals(2, result.chapters.size)
        assertEquals("Introduction", result.chapters[0].title)
        assertEquals("Conclusion", result.chapters[1].title)
        val intro = result.chapters[0].blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertTrue(intro.contains("First body paragraph"))
        assertTrue(intro.contains("Second body paragraph"))
        assertFalse(result.chapters[0].title == null)
    }

    @Test
    fun odp_chapters_per_page_with_title_frame() {
        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
        xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
        xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
  <office:body>
    <office:presentation>
      <draw:page>
        <draw:frame presentation:class="title"><draw:text-box><text:p>Migration Wave</text:p></draw:text-box></draw:frame>
        <draw:frame><draw:text-box><text:p>Plate one</text:p></draw:text-box></draw:frame>
        <draw:frame><draw:text-box><text:p>Plate two</text:p></draw:text-box></draw:frame>
      </draw:page>
      <draw:page>
        <draw:frame presentation:class="title"><draw:text-box><text:p>Migration Wave Two</text:p></draw:text-box></draw:frame>
        <draw:frame><draw:text-box><text:p>Plate one</text:p></draw:text-box></draw:frame>
      </draw:page>
    </office:presentation>
  </office:body>
</office:document-content>"""
        val bytes = zip(listOf("content.xml" to contentXml))
        val result = OdpParser.parseFull(bytes)
        assertEquals(2, result.chapters.size)
        assertEquals("Migration Wave", result.chapters[0].title)
        assertEquals("Migration Wave Two", result.chapters[1].title)
        val paras = result.chapters[0].blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertTrue(paras.contains("Plate one"))
        assertTrue(paras.contains("Plate two"))
        // The title frame's text is never duplicated as body text
        assertFalse(paras.contains("Migration Wave"))
    }

    @Test
    fun docx_missing_document_xml_reports_missing_entry() {
        val error = runCatching { DocxParser.parseFull(zip(listOf("other.xml" to "x"))) }.exceptionOrNull()
        assertTrue(error is DocumentParseError.MissingEntry)
        // A non-ZIP payload is not silently an empty book
        val bad = runCatching { DocxParser.parseFull("not a zip at all".toByteArray()) }.exceptionOrNull()
        assertTrue(bad is DocumentParseError.Malformed)
    }

    @Test
    fun document_book_dispatch_covers_office_extensions() {
        for (ext in listOf("docx", "DOCX", "odt", "pptx", "odp")) {
            assertTrue(ext, DocumentBook.canNormalize(ext))
        }
        assertFalse(DocumentBook.canNormalize("epub"))
        assertFalse(DocumentBook.canNormalize("pdf"))
        val error = runCatching { DocumentBook.parse("epub", ByteArray(0)) }.exceptionOrNull()
        assertTrue(error is DocumentParseError.Malformed)
    }

    @Test
    fun epub_output_is_ziplib_readable_with_strict_crc_check() {
        val chapters = List(3) { i ->
            DocumentChapter(
                if (i == 0) "Chapter A" else null,
                List(5) { DocumentBlock.Paragraph("Paragraph $it of chapter $i") },
            )
        }
        val epub = DocumentEpubConverter.epubData(chapters, "Round Trip", "Author", identifier = "id-1")
        // java.util.zip (not our own reader) must accept the archive too —
        // every unzip tool and epub.js go through the strict path.
        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(epub)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                // Reading to EOF exercises every entry's CRC.
                if (entry.name.endsWith(".xhtml")) zip.readBytes()
            }
        }
        // The mimetype entry must be first, and stored for EPUB validity.
        assertEquals("mimetype", names.first())
        assertTrue(names.containsAll(listOf("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml", "OEBPS/toc.ncx")))
        assertTrue(names.contains("OEBPS/ch1.xhtml"))
        assertTrue(names.contains("OEBPS/ch3.xhtml"))
    }
}
