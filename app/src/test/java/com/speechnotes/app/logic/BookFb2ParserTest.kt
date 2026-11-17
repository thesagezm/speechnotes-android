package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The FictionBook pins, ported from sn-doc fb2.rs's tests (fixture
 *  verbatim). */
class BookFb2ParserTest {

    /** A whole FB2 fixture: description with title/author/lang, two
     *  sections with titles, a table, a cover and one body image. */
    private val fixture = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
  <description>
    <title-info>
      <author><first-name>Lev</first-name><last-name>Tolstoy</last-name></author>
      <book-title>War and Peace</book-title>
      <lang>ru</lang>
    </title-info>
  </description>
  <body>
    <coverpage><image l:href="#cover.jpg"/></coverpage>
    <section>
      <title><p>Book One</p><subtitle>1805</subtitle></title>
      <p>Well, Prince, what do you think of it?</p>
      <p>The second paragraph of the first section.</p>
    </section>
    <section>
      <title><level>2</level><p>Chapter Two</p></title>
      <table><tr><th>Name</th><th>Qty</th></tr><tr><td><p>Apples</p></td><td><p>12</p></td></tr></table>
      <p>The table chapter's prose.</p>
    </section>
    <section>
      <p><strong>The Bold Line</strong></p>
      <p>Prose under the bold line.</p>
      <p>See the map <image l:href="#map.png"/>.</p>
    </section>
  </body>
  <binary id="cover.jpg" content-type="image/jpeg">aGVsbG8=</binary>
  <binary id="map.png" content-type="image/png">aGVsbG8gdG9v</binary>
  <binary id="svg-thing.svg" content-type="image/svg+xml">PHN2Zy8+</binary>
</FictionBook>"""

    @Test
    fun metadataCoverAndChaptersComeOut() {
        val doc = BookFb2Parser.parse(fixture.toByteArray())
        assertEquals("War and Peace", doc.title)
        assertEquals("Lev Tolstoy", doc.author)
        assertEquals("ru", doc.language)
        // The cover is the binary the coverpage named.
        val cover = doc.cover
        assertNotNull(cover)
        assertEquals("img/cover", cover!!.name)
        assertEquals("image/jpeg", cover.mime)
        assertEquals("hello", String(cover.data))
        // Two titled sections, then the bold-line fallback.
        assertEquals(3, doc.chapters.size)
        assertEquals("Book One — 1805", doc.chapters[0].title)
        assertTrue(doc.chapters[0].html.contains("<p>Well, Prince"))
        assertEquals("Chapter Two", doc.chapters[1].title)
        // The TOC names the chapters in order.
        assertEquals("Book One — 1805", doc.toc[0].label)
        assertEquals("Chapter Two", doc.toc[1].label)
    }

    @Test
    fun theDocumentInfoAuthorIsNotTheBookAuthor() {
        // FB2 names an author twice: `<title-info>` (the book's metadata)
        // and `<document-info>` (the file's provenance — calibre writes the
        // converter there). Joining both put "James Joyce James Joyce" on
        // the shelf of every calibre-made .fb2.
        val withProvenance = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
  <description>
    <title-info>
      <author><first-name>James</first-name><last-name>Joyce</last-name></author>
      <book-title>Ulysses</book-title>
      <lang>en</lang>
    </title-info>
    <document-info>
      <author><first-name>calibre</first-name><nick>8.3.0</nick></author>
      <program-used>calibre 8.3.0</program-used>
      <id>b01e88d5</id>
    </document-info>
  </description>
  <body>
    <section><p>Stately, plump Buck Mulligan came from the stairhead.</p></section>
  </body>
</FictionBook>"""
        val doc = BookFb2Parser.parse(withProvenance.toByteArray())
        assertEquals("James Joyce", doc.author)
        assertEquals("Ulysses", doc.title)
        assertEquals("en", doc.language)
    }

    @Test
    fun imagesResolveAndAnUnshowablePayloadIsDropped() {
        val doc = BookFb2Parser.parse(fixture.toByteArray())
        // The body image ships in `images`; the SVG does not. The cover
        // rides in doc.cover — a coverpage reference is not a body
        // reference.
        assertEquals(1, doc.images.size)
        assertNotNull(doc.cover)
        val map = doc.images.single()
        assertEquals("image/png", map.mime)
        assertEquals("hello too", String(map.data))
        assertEquals("img/000000", map.name)
        // The chapter that referenced it carries the reference rewritten,
        // after the paragraph that made it.
        val last = doc.chapters[2].html
        assertTrue(last.contains("<img src=\"img/000000\""))
        assertTrue(last.contains("See the map"))
        assertTrue(last.indexOf("See the map") < last.indexOf("<img"))
    }

    @Test
    fun aBoldFirstParagraphIsTheSectionsTitle() {
        val doc = BookFb2Parser.parse(fixture.toByteArray())
        assertEquals("The Bold Line", doc.chapters[2].title)
        assertTrue(doc.chapters[2].html.contains("Prose under the bold line."))
    }

    @Test
    fun tablesTakeTheirPWrappedCells() {
        val doc = BookFb2Parser.parse(fixture.toByteArray())
        val html = doc.chapters[1].html
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<th>Name</th>"))
        assertTrue(html.contains("<td>Apples</td>"))
        // The cells' paragraphs must not leak into the item stream as
        // loose prose around the table.
        assertTrue(!html.contains("<p>Apples</p>"))
    }

    @Test
    fun theCoverReferenceDoesNotLeakIntoTheFirstChapter() {
        val doc = BookFb2Parser.parse(fixture.toByteArray())
        val first = doc.chapters[0].html
        assertTrue(!first.contains("<img"))
    }

    @Test
    fun aDeeperTitleStaysInsideAsProse() {
        val fb2 = """<?xml version="1.0"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
  <body><section>
    <title><p>Part</p></title>
    <p>a.</p>
    <section><title><level>3</level><p>Sub</p></title><p>b.</p></section>
  </section></body>
</FictionBook>"""
        val doc = BookFb2Parser.parse(fb2.toByteArray())
        assertEquals(1, doc.chapters.size)
        assertEquals("Part", doc.chapters[0].title)
        assertTrue(doc.chapters[0].html.contains("<p>Sub</p>"))
    }

    @Test
    fun aCyrillicFileDeclaringCp1251Decodes() {
        // "Привет" in windows-1251, as the declaration demands.
        val data = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><FictionBook><body><p>".toByteArray() +
            byteArrayOf(0xCF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte()) +
            "</p></body></FictionBook>".toByteArray()
        val doc = BookFb2Parser.parse(data)
        assertTrue(doc.chapters[0].html.contains("Привет"))
    }

    @Test
    fun anEmptyFictionBookIsRefused() {
        try {
            BookFb2Parser.parse("<?xml version=\"1.0\"?><FictionBook/>".toByteArray())
            fail("expected refusal")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("no readable text"))
        }
    }

    @Test
    fun base64DecodeKnowsTheAlphabetAndIgnoresTheNoise() {
        assertEquals("hello", String(BookFb2Parser.base64Decode("aGVsbG8=")))
        // Line-wrapped payload, the shape every real file carries.
        assertEquals("hello too", String(BookFb2Parser.base64Decode("aGVs\nbG8g\ndG9v")))
        // No padding at all — a truncated tail still decodes what it has.
        assertEquals("hello", String(BookFb2Parser.base64Decode("aGVsbG8")))
        assertEquals("hello", String(BookFb2Parser.base64Decode("  aG Vs bG8= ")))
    }

    @Test
    fun aLatin1FileDeclaringItsEncodingDecodes() {
        // é (0xE9) declared as iso-8859-1 — the codepage tables answer, not
        // just UTF-8.
        val data = "<?xml version=\"1.0\" encoding=\"iso-8859-1\"?><FictionBook><body><p>".toByteArray() +
            byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte()) + "</p></body></FictionBook>".toByteArray()
        val doc = BookFb2Parser.parse(data)
        assertTrue(doc.chapters[0].html.contains("Café"))
    }
}
