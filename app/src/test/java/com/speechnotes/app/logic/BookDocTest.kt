package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The BookDoc model pins, ported from sn-doc bookdoc.rs's tests, plus the
 *  ServedBookDoc dispatch (sn-store books.rs). */
class BookDocTest {

    @Test
    fun extensionsLandOnTheirFamily() {
        for (ext in listOf("mobi", "azw", "azw3", "prc", "pdb", "MOBI", "Azw3")) {
            assertEquals(DocFamily.Mobi, DocFamily.fromExtension(ext))
        }
        assertEquals(DocFamily.Fb2, DocFamily.fromExtension("fb2"))
        assertEquals(DocFamily.Rtf, DocFamily.fromExtension("RTF"))
        for (ext in listOf("html", "htm", "htmlz")) {
            assertEquals(DocFamily.Html, DocFamily.fromExtension(ext))
        }
        assertEquals(DocFamily.Txt, DocFamily.fromExtension("txt"))
        assertEquals(DocFamily.Txt, DocFamily.fromExtension("text"))
    }

    @Test
    fun nearMissExtensionsAreNotDocuments() {
        // `mobile`, `mobi3`, `fbz`, `text/plain` — the wrong-form traps the
        // starts_with family of bugs produce.
        for (ext in listOf("", "mobile", "mobi3", "mob", "azw4", "fbz", "rt", "html5", "txt2", "epub")) {
            assertNull(DocFamily.fromExtension(ext))
        }
        assertEquals(false, DocFamily.isDocumentExtension("epub"))
        assertEquals(true, DocFamily.isDocumentExtension("pdb"))
    }

    @Test
    fun theSpineNamesChaptersInOrder() {
        assertEquals(listOf("chapter-0", "chapter-1"), BookDoc.spine(2))
        assertEquals("chapter-11", BookDoc.spineName(11))
    }

    @Test
    fun imagesResolveByTheirReferenceName() {
        val doc = BookDoc(
            images = listOf(
                BookImage("img/cover", "image/jpeg", byteArrayOf(1, 2)),
                BookImage("img/000123", "image/png", byteArrayOf(3)),
            )
        )
        assertNotNull(doc.image("000123"))
        assertNotNull(doc.image("cover"))
        assertNull(doc.image("img/cover"), "the src, not the name")
        assertNull(doc.image("missing"))
    }

    @Test
    fun aChapterPageIsAStandaloneDocument() {
        val page = chapterPage("Chapter & One", "<h1>Hi</h1><p>Body &amp; more</p>")
        assertTrue(page.startsWith("<!DOCTYPE html>"))
        assertTrue(page.contains("<title>Chapter &amp; One</title>"))
        assertTrue(page.contains("<body><h1>Hi</h1><p>Body &amp; more</p></body>"))
        // The body is the parser's own XHTML: passed through, not re-escaped.
        assertTrue(!page.contains("&amp;amp;"))
    }

    @Test
    fun escapingCoversTheFiveMarkupCharacters() {
        assertEquals("a&lt;b&gt;&amp;&quot;&#39;c", xmlEscape("a<b>&\"'c"))
        assertEquals("", xmlEscape(""))
        assertEquals("plain text 123", xmlEscape("plain text 123"))
    }

    @Test
    fun servedDocAnswersChaptersAndImages() {
        val doc = BookDoc(
            chapters = listOf(BookChapter("One", "<p>alpha</p>"), BookChapter(null, "<p>beta</p>")),
            images = listOf(BookImage("img/000000", "image/png", byteArrayOf(9))),
        )
        val served = ServedBookDoc(byteArrayOf(1), doc)
        val page0 = String(served.read("chapter-0")!!, Charsets.UTF_8)
        assertTrue(page0.contains("<title>One</title>"))
        assertTrue(page0.contains("<p>alpha</p>"))
        val page1 = String(served.read("chapter-1")!!, Charsets.UTF_8)
        assertTrue(page1.contains("<title>Chapter 2</title>"), "a heading-less chunk is 'Chapter N'")
        assertEquals("text/html", served.mimeFor("chapter-0"))
        assert(java.util.Arrays.equals(byteArrayOf(9), served.read("img/000000")))
        assertEquals("image/png", served.mimeFor("img/000000"))
        assertNull(served.read("chapter-99"))
        assertNull(served.read("img/missing"))
        assertEquals("application/octet-stream", served.mimeFor("other"))
    }

    @Test
    fun aPdbOfAnotherFlavorRefusesBeforeTheParserRuns() {
        // The `.pdb` extension is shared with every Palm-era format; a
        // TealDoc file reaches the dispatch through it and gets the typed
        // refusal, not a half-decode.
        val pdb = ByteArray(200)
        "TEXt".toByteArray(Charsets.ISO_8859_1).copyInto(pdb, 60)
        "REAd".toByteArray(Charsets.ISO_8859_1).copyInto(pdb, 64)
        try {
            ServedBookDoc.parse("pdb", pdb)
            throw AssertionError("expected refusal")
        } catch (e: BookDocError.Unsupported) {
            assertTrue(e.message!!.contains("not a Mobipocket book"))
        }
    }
}
