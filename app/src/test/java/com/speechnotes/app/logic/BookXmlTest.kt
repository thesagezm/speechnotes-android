package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The XML pull-parser pins, ported from sn-doc xml.rs's tests. */
class BookXmlTest {

    private fun events(src: String): List<BookXml.Event> {
        val p = BookXml.Parser(src)
        val out = ArrayList<BookXml.Event>()
        while (true) {
            val e = p.nextEvent() ?: break
            out.add(e)
        }
        return out
    }

    @Test
    fun parsesElementsAndText() {
        val ev = events("<a><b>hi</b> tail</a>")
        assertEquals(6, ev.size)
        assertEquals("a", (ev[0] as BookXml.Event.Start).name)
        assertEquals("b", (ev[1] as BookXml.Event.Start).name)
        assertEquals("hi", (ev[2] as BookXml.Event.Text).text)
        assertEquals("b", (ev[3] as BookXml.Event.End).name)
        assertEquals(" tail", (ev[4] as BookXml.Event.Text).text)
        assertEquals("a", (ev[5] as BookXml.Event.End).name)
    }

    @Test
    fun decodesNamedAndNumericEntities() {
        assertEquals(
            "Wicked & the Witch — by “G.M.”",
            BookXml.decodeEntities("Wicked &amp; the Witch &#8212; by &#x201c;G.M.&#x201d;")
        )
        val ev = events("<t>&lt;b&gt;</t>")
        assertEquals("<b>", (ev[1] as BookXml.Event.Text).text)
    }

    @Test
    fun attributesDecodeAndQuery() {
        val ev = events("<dc:title id=\"t\">Wicked &amp; the Witch</dc:title>")
        val start = ev[0] as BookXml.Event.Start
        assertEquals("dc:title", start.name)
        assertEquals(1, start.attrs.size)
        assertEquals("t", BookXml.Attr.get(start.attrs, "id"))
        assertNull(BookXml.Attr.get(start.attrs, "missing"))
        assertEquals("Wicked & the Witch", (ev[1] as BookXml.Event.Text).text)
    }

    @Test
    fun emptyElementsCommentsAndDeclarationAreHandled() {
        val ev = events("<?xml version=\"1.0\"?>\n<!-- a comment --><spine toc=\"ncx\"><item/></spine>")
        assertEquals(3, ev.size)
        val start = ev[0] as BookXml.Event.Start
        assertEquals("spine", start.name)
        assertEquals("ncx", BookXml.Attr.get(start.attrs, "toc"))
        assertEquals("item", (ev[1] as BookXml.Event.Empty).name)
        assertEquals("spine", (ev[2] as BookXml.Event.End).name)
    }

    @Test
    fun cdataIsText() {
        val ev = events("<a><![CDATA[<not a tag> & fine]]></a>")
        assertEquals("<not a tag> & fine", (ev[1] as BookXml.Event.Text).text)
    }

    @Test
    fun anUnknownEntityIsKeptLiterallyAndABareAmpIsToo() {
        assertEquals("a &notanentity; b", BookXml.decodeEntities("a &notanentity; b"))
        assertEquals("a & b", BookXml.decodeEntities("a & b"))
        // A numeric reference that names no character is still an error.
        assertNull(BookXml.decodeEntities("&#xFFFFFF;"))
    }

    @Test
    fun anUnterminatedTagFailsWithALine() {
        val p = BookXml.Parser("<a>\n<b")
        p.nextEvent()
        try {
            p.nextEvent()
            fail("expected XmlError")
        } catch (e: BookXml.XmlError) {
            assertTrue(e.message!!.contains("line 2"))
        }
    }

    @Test
    fun scriptBodiesAreOneRawEvent() {
        val ev = events("<a><script>if (a<b) x();</script><p>ok</p></a>")
        // Start a, Raw, Start p, Text ok, End p, End a
        assertTrue(ev[1] is BookXml.Event.Raw)
        assertEquals("script", (ev[1] as BookXml.Event.Raw).name)
        assertEquals("if (a<b) x();", (ev[1] as BookXml.Event.Raw).text)
        assertEquals("p", (ev[2] as BookXml.Event.Start).name)
    }
}
