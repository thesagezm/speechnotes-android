package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Mobipocket pins, ported from sn-doc mobi.rs's tests: the Palm
 *  Database and the MOBI header built byte-wise, so the parts a real file
 *  exercises (the record-offset list, the trailing-entry table, EXTH inside
 *  record 0, the command-byte encoding) are all covered without shipping
 *  megabyte fixtures. */
class BookMobiParserTest {

    // ------------------------------------------------------------------
    // Fixture builders (ported verbatim from the Rust tests)
    // ------------------------------------------------------------------

    private fun be16(value: Int): ByteArray = byteArrayOf(
        ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte(),
    )

    private fun be32(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /** A Palm Database with [records], laid out back to back after the
     *  78-byte header plus the record list. Records are contiguous bytes
     *  at their offset — NO per-record checksum (the format has none). */
    private fun makePdb(records: List<ByteArray>): ByteArray {
        val listSize = records.size * 8
        val listEnd = 78 + listSize
        val header = ByteArray(78)
        "BOOK".toByteArray(Charsets.ISO_8859_1).copyInto(header, 60)
        "MOBI".toByteArray(Charsets.ISO_8859_1).copyInto(header, 64)
        be16(records.size).copyInto(header, 76)

        val offsets = ArrayList<Int>()
        val payload = ByteArrayOutputStream()
        var cursor = listEnd + 2 // +2 for the next-IDs list's terminating zero
        for (record in records) {
            offsets.add(cursor)
            payload.write(record)
            cursor += record.size
        }
        val list = ByteArray(listSize)
        for ((index, offset) in offsets.withIndex()) {
            be32(offset.toLong()).copyInto(list, index * 8)
        }
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(list)
        out.write(ByteArray(2)) // the next-IDs list's terminating zero
        payload.writeTo(out)
        return out.toByteArray()
    }

    /** One EXTH entry: its type, its payload (the length covers the 8-byte
     *  type+length pair, the way real records declare it). */
    private fun exthEntry(kind: Int, payload: ByteArray): ByteArray =
        be32(kind.toLong()) + be32((8 + payload.size).toLong()) + payload

    private fun exthTextEntry(kind: Int, text: String): ByteArray =
        exthEntry(kind, text.toByteArray(Charsets.UTF_8))

    /** The header record's variable fields, defaulted the way a real
     *  calibre-produced MOBI6 book has them. */
    private class Hdr {
        var compression: Int = 2
        var textRecordCount: Int = 1
        var encryption: Int = 0
        var extraFlags: Int = 0
        var codepage: Long = 65001
        var version: Long = 6
        var firstImageIndex: Int? = null
        var ncxIndex: Int? = null
        var exth: List<ByteArray> = emptyList()
    }

    /** A MOBI header record: the 16-byte PalmDOC part, the 232-byte MOBI
     *  header, then the EXTH. */
    private fun makeHeaderRecord(h: Hdr, textLength: Int): ByteArray {
        val headerLength = 232
        val record = ByteArrayOutputStream()
        val fixed = ByteArray(16 + headerLength)
        be16(h.compression).copyInto(fixed, 0)
        be32(textLength.toLong()).copyInto(fixed, 4)
        be16(h.textRecordCount).copyInto(fixed, 8)
        be16(4096).copyInto(fixed, 10) // record size
        be16(h.encryption).copyInto(fixed, 12)
        "MOBI".toByteArray(Charsets.ISO_8859_1).copyInto(fixed, 16)
        be32(headerLength.toLong()).copyInto(fixed, 20)
        be32(2).copyInto(fixed, 24) // type: a book
        be32(h.codepage).copyInto(fixed, 28)
        be32(h.version).copyInto(fixed, 0x68)
        h.firstImageIndex?.let { be32(it.toLong()).copyInto(fixed, 0x6C) }
        if (headerLength >= 0xE4) {
            be16(h.extraFlags).copyInto(fixed, 0xF2)
        }
        h.ncxIndex?.let { be32(it.toLong()).copyInto(fixed, 0xF4) }
        if (h.exth.isNotEmpty()) {
            // The flag word at 0x80 tells a reader to look for the block.
            be32(0x40).copyInto(fixed, 0x80)
            val exthBytes = h.exth.sumOf { it.size }
            val exth = ByteArrayOutputStream()
            exth.write("EXTH".toByteArray(Charsets.ISO_8859_1))
            // The declared length includes the 8 bytes after the magic.
            exth.write(be32((12 + exthBytes).toLong()))
            exth.write(be32(h.exth.size.toLong()))
            for (entry in h.exth) exth.write(entry)
            record.write(fixed)
            exth.writeTo(record)
        } else {
            record.write(fixed)
        }
        return record.toByteArray()
    }

    /** PalmDOC "compressed" bytes: everything as literal runs of up to 8 —
     *  valid input in every command-byte case, and the round-trip is exact. */
    private fun palmdocEncode(text: String): ByteArray = palmdocEncodeBytes(text.toByteArray(Charsets.UTF_8))

    private fun palmdocEncodeBytes(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < bytes.size) {
            val take = minOf(8, bytes.size - index)
            out.write(take)
            out.write(bytes, index, take)
            index += take
        }
        return out.toByteArray()
    }

    /** A forward variable-width integer: 7-bit groups, most significant
     *  first, the last byte carrying the terminator bit. */
    private fun vwi(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        out.add(((value and 0x7F) or 0x80).toByte())
        var rest = value shr 7
        while (rest > 0) {
            out.add(0, (rest and 0x7F).toByte())
            rest = rest shr 7
        }
        return out.toByteArray()
    }

    /** One index entry: `[length byte][ident][control byte][vwi values]`,
     *  the values in TAGX order. */
    private fun makeIndexEntry(ident: String, control: Int, values: List<Long>): ByteArray {
        val entry = ByteArrayOutputStream()
        entry.write(ident.length)
        entry.write(ident.toByteArray(Charsets.ISO_8859_1))
        entry.write(control)
        for (value in values) entry.write(vwi(value))
        return entry.toByteArray()
    }

    /** The NCX-style TAGX block: position (1), length (2), CNCX label (3),
     *  depth (4), and the eof marker. */
    private fun ncxTagx(): ByteArray {
        val tagx = ByteArrayOutputStream()
        tagx.write("TAGX".toByteArray(Charsets.ISO_8859_1))
        tagx.write(be32(32)) // first entry offset: 12 + 5·4
        tagx.write(be32(1)) // one control byte
        for (t in listOf(longArrayOf(1, 1, 1, 0), longArrayOf(2, 1, 2, 0), longArrayOf(3, 1, 4, 0), longArrayOf(4, 1, 8, 0), longArrayOf(0, 0, 0, 1))) {
            tagx.write(byteArrayOf(t[0].toByte(), t[1].toByte(), t[2].toByte(), t[3].toByte()))
        }
        return tagx.toByteArray()
    }

    /** The index header record: 192 bytes of header, then the TAGX at 192. */
    private fun makeIndxHeader(ncncx: Int): ByteArray {
        val record = ByteArray(192)
        "INDX".toByteArray(Charsets.ISO_8859_1).copyInto(record, 0)
        be32(192).copyInto(record, 4)
        be32(1).copyInto(record, 0x18) // one continuation
        be32(65001).copyInto(record, 0x1C) // encoding
        be32(ncncx.toLong()).copyInto(record, 0x34)
        be32(192).copyInto(record, 180) // the TAGX offset
        return record + ncxTagx()
    }

    /** A continuation record: its own header (the count is its OWN entry
     *  count), the entries, then the IDXT offset table. */
    private fun makeIndxContinuation(entries: List<ByteArray>): ByteArray {
        val entriesSize = entries.sumOf { it.size }
        val idxtAt = 192 + entriesSize
        val record = ByteArray(idxtAt + 4 + 2 * entries.size)
        "INDX".toByteArray(Charsets.ISO_8859_1).copyInto(record, 0)
        be32(192).copyInto(record, 4)
        be32(idxtAt.toLong()).copyInto(record, 0x14)
        be32(entries.size.toLong()).copyInto(record, 0x18)
        var at = 192
        val offsets = ArrayList<Int>()
        for (entry in entries) {
            offsets.add(at)
            entry.copyInto(record, at)
            at += entry.size
        }
        "IDXT".toByteArray(Charsets.ISO_8859_1).copyInto(record, idxtAt)
        for ((index, offset) in offsets.withIndex()) {
            be16(offset).copyInto(record, idxtAt + 4 + 2 * index)
        }
        return record
    }

    /** A CNCX record — no header, just the strings: vwi length + bytes. */
    private fun makeCncx(strings: List<String>): ByteArray {
        val record = ByteArrayOutputStream()
        for (text in strings) {
            record.write(vwi(text.length.toLong()))
            record.write(text.toByteArray(Charsets.UTF_8))
        }
        return record.toByteArray()
    }

    /** The minimal-but-real HUFF/CDIC table pair from the huffcdic tests. */
    private fun huffCdicRecords(): Pair<ByteArray, ByteArray> {
        val huff = ByteArrayOutputStream()
        huff.write("HUFF".toByteArray(Charsets.ISO_8859_1))
        huff.write(be32(0x18))
        huff.write(be32(24))
        huff.write(be32(24 + 4 * 256))
        huff.write(ByteArray(8))
        repeat(256) { huff.write(be32(0xFF88)) }
        huff.write(ByteArray(4 * 64))

        val phrase = "Hi ".toByteArray(Charsets.ISO_8859_1)
        val entries = 256
        val cdic = ByteArrayOutputStream()
        cdic.write("CDIC".toByteArray(Charsets.ISO_8859_1))
        cdic.write(be32(0x10))
        cdic.write(be32(entries.toLong()))
        cdic.write(be32(8))
        var offset = 2 * entries
        repeat(entries) {
            cdic.write(be16(offset))
            offset += 2 + phrase.size
        }
        repeat(entries) {
            cdic.write(be16(0x8000 or phrase.size))
            cdic.write(phrase)
        }
        return huff.toByteArray() to cdic.toByteArray()
    }

    private fun jpeg(size: Int, fill: Int): ByteArray {
        val data = ByteArray(size)
        data[0] = 0xFF.toByte(); data[1] = 0xD8.toByte(); data[2] = 0xFF.toByte(); data[3] = 0xE0.toByte()
        for (i in 4 until size) data[i] = fill.toByte()
        return data
    }

    // ------------------------------------------------------------------
    // Decompression
    // ------------------------------------------------------------------

    @Test
    fun literalBytesDecodeThemselves() {
        // '<' is 0x3C — a literal, NOT a flag byte with four
        // back-references. This single assertion is the whole reason the
        // decoder exists: every widely-copied flag-byte decoder fails
        // exactly here.
        assertEquals("<html><body>", String(BookMobiParser.decompressPalmdoc("<html><body>".toByteArray())))
    }

    @Test
    fun literalRunCommand() {
        val input = byteArrayOf(8) + "abcdefgh".toByteArray() + byteArrayOf(3) + "ijk".toByteArray()
        assertEquals("abcdefghijk", String(BookMobiParser.decompressPalmdoc(input)))
    }

    @Test
    fun spacePlusAsciiShorthand() {
        // 0xC0-0xFF is the byte-pair form: ' ' then (byte ^ 0x80).
        assertEquals(" Ai", String(BookMobiParser.decompressPalmdoc(byteArrayOf(0xC1.toByte(), 0xE9.toByte()))))
    }

    @Test
    fun backReferenceRepeatsEarlierBytes() {
        // 'a' literal, then distance 1, length 3 → "aaaa".
        assertEquals("aaaa", String(BookMobiParser.decompressPalmdoc(byteArrayOf(0x61, 0x80.toByte(), 0x08))))
    }

    @Test
    fun outOfRangeBackReferenceIsSkippedNotClamped() {
        // A reference pointing before the start of the output emits
        // nothing — clamping would splice unrelated bytes into the prose.
        assertEquals("a", String(BookMobiParser.decompressPalmdoc(byteArrayOf(0x61, 0x80.toByte(), 0x40))))
    }

    @Test
    fun aBackReferenceMayCrossARecordBoundary() {
        // The PalmDOC path decompresses the JOINED records.
        val first = palmdocEncode("hello ")
        val second = byteArrayOf(0x80.toByte(), 0x18) + "world".toByteArray()
        val joined = first + second
        assertEquals("hello lo world", String(BookMobiParser.decompressPalmdoc(joined)))
    }

    // ------------------------------------------------------------------
    // Trailing entries
    // ------------------------------------------------------------------

    @Test
    fun trailingEntryBytesAreStripped() {
        val record = "body".toByteArray() + byteArrayOf(0x41, 0x81.toByte(), 0x42, 0x43, 0x82.toByte())
        val cut = BookMobiParser.trailingEntryBytes(record, 3)
        assertEquals(5, cut)
        assertEquals("body", String(record.copyOfRange(0, record.size - cut)))
    }

    @Test
    fun trailingBytesMatchTheReferenceOnARealRecordTail() {
        // The exact tail the real book's records carry — hex-dumped from
        // record 1 of Pocket Pediatrics. flags 0x3 strips 5.
        val record = "x".toByteArray() + byteArrayOf(0x00, 0x00, 0x86.toByte(), 0x88.toByte(), 0x02, 0x84.toByte())
        assertEquals(5, BookMobiParser.trailingEntryBytes(record, 3))
    }

    @Test
    fun aTrailingVarintCarriesAValueNotACount() {
        val record = "body".toByteArray() + byteArrayOf(0x88.toByte(), 0x02)
        // Backward walk: 0x02 (bitpos 0) then 0x88 (bitpos 7, terminator)
        // → value 8 | (2 << 7) = 1026 — NOT 2.
        assertEquals(1026, BookMobiParser.trailingEntryBytes(record, 2))
    }

    @Test
    fun zeroExtraFlagsMeansNoStripping() {
        assertEquals(0, BookMobiParser.trailingEntryBytes("body".toByteArray(), 0))
    }

    @Test
    fun theMultibyteFlagAddsItsCountByte() {
        // flags 0x1: one byte, (byte & 0x3) + 1.
        val record = palmdocEncode("<p>x</p>") + byteArrayOf(0x02)
        assertEquals(3, BookMobiParser.trailingEntryBytes(record, 1))
    }

    // ------------------------------------------------------------------
    // Whole-book parse
    // ------------------------------------------------------------------

    @Test
    fun parsesASingleRecordBook() {
        val html = "<html><body><p>The first line of prose.</p></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val text = palmdocEncode(html)
        val doc = BookMobiParser.parse(makePdb(listOf(header, text)))
        assertEquals(1, doc.chapters.size)
        assertTrue(doc.chapters[0].html.contains("The first line of prose."))
        assertNull(doc.title)
    }

    @Test
    fun headingsBecomeTitledChapters() {
        val html = "<html><body><h1>Chapter One</h1><p>Alpha text.</p><h2>Chapter Two</h2><p>Beta text.</p></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        assertEquals(listOf("Chapter One", "Chapter Two"), doc.chapters.map { it.title })
        assertTrue(doc.chapters[0].html.contains("Alpha text."))
        assertTrue(doc.chapters[1].html.contains("Beta text."))
        assertEquals("Chapter One", doc.toc[0].label)
        assertEquals(1, doc.toc[1].chapterIndex)
    }

    @Test
    fun deepHeadingsStayInsideTheirChapter() {
        val html = "<html><body><h1>Part One</h1><p>Intro.</p><h3>A subsection</h3><p>Body of the subsection.</p></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        assertEquals(1, doc.chapters.size)
        assertEquals("Part One", doc.chapters[0].title)
        assertTrue(doc.chapters[0].html.contains("A subsection"))
        assertTrue(doc.chapters[0].html.contains("Body of the subsection."))
    }

    @Test
    fun multiRecordTextConcatenates() {
        val first = "<html><body><h1>One</h1><p>First half."
        val second = "Second half.</p></body></html>"
        val hdr = Hdr().apply { textRecordCount = 2 }
        val header = makeHeaderRecord(hdr, first.length + second.length)
        val doc = BookMobiParser.parse(
            makePdb(listOf(header, palmdocEncode(first), palmdocEncode(second)))
        )
        assertEquals(1, doc.chapters.size)
        assertTrue(doc.chapters[0].html.contains("First half."))
        assertTrue(doc.chapters[0].html.contains("Second half."))
    }

    @Test
    fun exthMetadataBecomesTitleAndAuthor() {
        val html = "<html><body><p>Body.</p></body></html>"
        val hdr = Hdr().apply {
            exth = listOf(exthTextEntry(503, "A Real Book"), exthTextEntry(100, "A Writer"))
        }
        val header = makeHeaderRecord(hdr, html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        assertEquals("A Real Book", doc.title)
        assertEquals("A Writer", doc.author)
    }

    @Test
    fun editorialMarkersAreStripped() {
        val html = "<html><body><p>[Illustration] The prose itself. [Pg 42]</p></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        val out = doc.chapters[0].html
        assertTrue(out.contains("The prose itself."))
        assertTrue(!out.contains("Illustration"))
        assertTrue(!out.contains("Pg 42"))
    }

    @Test
    fun glyphReferencesAndEmphasisMarksGo() {
        assertEquals("a b", BookMobiParser.sanitize("a (cid:77) b"))
        assertEquals("so bold of you", BookMobiParser.sanitize("so *bold* of you"))
        // A word wrapped in underscores keeps them.
        assertEquals("so _bold_ of them", BookMobiParser.sanitize("so _bold_ of them"))
        // Words with underscores keep them; a four-run matches nowhere.
        assertEquals("snake_case and four", BookMobiParser.sanitize("snake_case and ____ four"))
    }

    @Test
    fun scriptAndStyleBodiesAreNeverRead() {
        val html = "<html><head><style>p { color: red }</style></head><body><p>Visible prose.</p></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        val out = doc.chapters.joinToString("") { it.html }
        assertTrue(out.contains("Visible prose."))
        assertTrue(!out.contains("color"))
    }

    @Test
    fun aPagebreakSeparatesTheParagraphs() {
        val html = "<p>Before.</p><mbp:pagebreak/><p>After.</p>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        val out = doc.chapters[0].html
        assertTrue(out.contains("<p>Before.</p>"))
        assertTrue(out.contains("<p>After.</p>"))
    }

    @Test
    fun aCp1252BookDecodes() {
        // 0x93/0x94 are “ ” in cp1252 — the codepage header must be honored.
        val text = palmdocEncodeBytes(
            "<p>A ".toByteArray() + byteArrayOf(0x93.toByte()) +
                "quote".toByteArray() + byteArrayOf(0x94.toByte()) + "</p>".toByteArray()
        )
        val hdr = Hdr().apply { codepage = 1252 }
        val header = makeHeaderRecord(hdr, text.size)
        val doc = BookMobiParser.parse(makePdb(listOf(header, text)))
        assertTrue(doc.chapters[0].html.contains("A “quote”"), doc.chapters[0].html)
    }

    // ------------------------------------------------------------------
    // Failures, honestly reported
    // ------------------------------------------------------------------

    @Test
    fun aDrmLockedBookSaysSo() {
        val html = "<html><body><p>x</p></body></html>"
        val hdr = Hdr().apply { encryption = 2 }
        val header = makeHeaderRecord(hdr, html.length)
        try {
            BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
            fail("expected DRM refusal")
        } catch (e: BookDocError.Unsupported) {
            assertTrue(e.message!!.contains("DRM"))
        }
    }

    @Test
    fun aHuffCdicBookWithoutTablesSaysMalformedNotNoise() {
        val hdr = Hdr().apply { compression = 17480 }
        val header = makeHeaderRecord(hdr, 10)
        try {
            BookMobiParser.parse(makePdb(listOf(header, "x".toByteArray())))
            fail("expected malformed")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("tables are missing"))
        }
    }

    @Test
    fun aNonBookFileIsRejected() {
        for (data in listOf("just some text".toByteArray(), ByteArray(0))) {
            try {
                BookMobiParser.parse(data)
                fail("expected refusal")
            } catch (e: BookDocError.Malformed) {
                assertTrue(e.message!!.contains("not a Mobipocket book"))
            }
        }
        // A ZIP named .mobi: the type/creator check catches it.
        val zipLike = ByteArray(200) { 0x50 }
        "BOOK".toByteArray(Charsets.ISO_8859_1).copyInto(zipLike, 60)
        "ZIPS".toByteArray(Charsets.ISO_8859_1).copyInto(zipLike, 64)
        try {
            BookMobiParser.parse(zipLike)
            fail("expected refusal")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("not a Mobipocket book"))
        }
    }

    @Test
    fun aHeaderClaimingMoreRecordsThanExistThrows() {
        val hdr = Hdr().apply { textRecordCount = 5 }
        val header = makeHeaderRecord(hdr, 10)
        try {
            BookMobiParser.parse(makePdb(listOf(header, "x".toByteArray())))
            fail("expected malformed")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("5 text record(s)"))
        }
    }

    @Test
    fun anUnknownCompressionRefuses() {
        val hdr = Hdr().apply { compression = 3 }
        val header = makeHeaderRecord(hdr, 10)
        try {
            BookMobiParser.parse(makePdb(listOf(header, "x".toByteArray())))
            fail("expected malformed")
        } catch (e: BookDocError.Malformed) {
            assertTrue(e.message!!.contains("unknown compression 3"))
        }
    }

    @Test
    fun looksLikeMobiSniffsTheTypeAndCreator() {
        val html = "<p>x</p>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val text = palmdocEncode(html)
        val book = makePdb(listOf(header, text))
        assertTrue(BookMobiParser.looksLikeMobi(book))
        assertTrue(BookMobiParser.looksLikeMobi(book.copyOf(minOf(200, book.size))))
        assertTrue(!BookMobiParser.looksLikeMobi("not a book".toByteArray()))
        assertTrue(!BookMobiParser.looksLikeMobi(ByteArray(0)))
        // The ancient PRC creator reads too.
        val prc = book.copyOf()
        "REAd".toByteArray(Charsets.ISO_8859_1).copyInto(prc, 64)
        assertTrue(BookMobiParser.looksLikeMobi(prc))
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    @Test
    fun aRecindexReferenceBecomesAPoolImage() {
        val html = "<html><body><p>before <img recindex=\"1\" alt=\"a pic\"> after</p></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val image = jpeg(48, 0x11)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html), image)))
        assertEquals(1, doc.images.size)
        assertEquals("img/000000", doc.images[0].name)
        assertEquals("image/jpeg", doc.images[0].mime)
        assertTrue(doc.images[0].data.contentEquals(image))
        assertTrue(doc.chapters[0].html.contains("<img src=\"img/000000\" alt=\"a pic\">"))
    }

    @Test
    fun anUnresolvableReferenceLosesItsTag() {
        val html = "<html><body><p>before <img recindex=\"5\"> after</p></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val image = jpeg(48, 0x11)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html), image)))
        assertTrue(doc.images.isEmpty())
        assertTrue(!doc.chapters[0].html.contains("<img"))
        assertTrue(doc.chapters[0].html.contains("after"))
    }

    @Test
    fun twoReferencesToOneRecordPoolOneImage() {
        val html = "<html><body><img recindex=\"1\"><p>x</p><img recindex=\"1\"></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val image = jpeg(48, 0x11)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html), image)))
        assertEquals(1, doc.images.size)
        assertEquals(2, Regex("img/000000").findAll(doc.chapters[0].html).count())
    }

    @Test
    fun aKindleEmbedReferenceResolvesThroughTheFirstImageIndex() {
        val html = "<html><body><p><img src=\"kindle:embed:0001?mime=image/jpg\" alt=\"\"></p></body></html>"
        val hdr = Hdr().apply {
            version = 8
            firstImageIndex = 2
        }
        val header = makeHeaderRecord(hdr, html.length)
        val image = jpeg(48, 0x22)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html), image)))
        assertEquals(1, doc.images.size)
        assertTrue(doc.images[0].data.contentEquals(image))
        assertTrue(doc.chapters[0].html.contains("<img src=\"img/000000\">"))
    }

    @Test
    fun theExthCoverOffsetWinsOverTheFirstImage() {
        val html = "<html><body><p>x</p></body></html>"
        val hdr = Hdr().apply { exth = listOf(exthEntry(201, be32(3))) }
        val header = makeHeaderRecord(hdr, html.length)
        val logo = jpeg(44, 0x11)
        val filler = ByteArray(12)
        val cover = jpeg(64, 0x22)
        val doc = BookMobiParser.parse(
            makePdb(listOf(header, palmdocEncode(html), logo, filler, filler, cover))
        )
        assertNotNull(doc.cover)
        assertTrue(doc.cover!!.data.contentEquals(cover))
        assertEquals("img/cover", doc.cover!!.name)
    }

    @Test
    fun theExthThumbnailOffsetIsTheFallback() {
        // 202, not 203: the reference read the fake-cover flag as an
        // offset. Here 201 is unset and 202 names the cover.
        val html = "<html><body><p>x</p></body></html>"
        val hdr = Hdr().apply { exth = listOf(exthEntry(202, be32(1)), exthEntry(203, be32(1))) }
        val header = makeHeaderRecord(hdr, html.length)
        val logo = jpeg(44, 0x11)
        val cover = jpeg(64, 0x22)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html), logo, cover)))
        assertTrue(doc.cover!!.data.contentEquals(cover))
    }

    @Test
    fun theCoverFallsBackToTheFirstImageRecord() {
        val html = "<html><body><p>x</p></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val logo = jpeg(44, 0x11)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html), logo)))
        assertTrue(doc.cover!!.data.contentEquals(logo))
    }

    @Test
    fun aBookWithoutImagesHasNoCover() {
        val html = "<html><body><p>x</p></body></html>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        assertNull(doc.cover)
    }

    // ------------------------------------------------------------------
    // KF8 flow chapters
    // ------------------------------------------------------------------

    /** The KF8 rawML shape, distilled from a real store book. */
    private val kf8Rawml =
        "<html><head><title>t</title></head><body></body></html>" +
            "<section epub:type=\"titlepage\"><p>Title page art</p></section>" +
            "<html><head><title>t</title></head><body></body></html>" +
            "<section epub:type=\"chapter\"><h1>DEVELOPMENTAL MILESTONES</h1>" +
            "<p>Chapter body.</p><h2>A subsection</h2><p>Still this chapter.</p></section>" +
            "<html><head><title>t</title></head><body></body></html>" +
            "<section epub:type=\"chapter\"><h1>TRAUMA EXAM</h1><p>Next body.</p></section>"

    @Test
    fun kf8FlowsSplitAtDocumentBoundariesNotEveryHeading() {
        // The device report: a reference .azw3 whose 165 h1s and 265 h2s
        // all opened "chapters". The flow boundary is the book's own
        // structure: three flows here, three chapters — the h2 stays inside.
        val hdr = Hdr().apply { version = 8 }
        val header = makeHeaderRecord(hdr, kf8Rawml.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(kf8Rawml))))
        assertEquals(3, doc.chapters.size)
        // Flow 1 has no h1: its epub:type becomes the title.
        assertEquals("Titlepage", doc.chapters[0].title)
        assertEquals("DEVELOPMENTAL MILESTONES", doc.chapters[1].title)
        assertTrue(doc.chapters[1].html.contains("Still this chapter."))
        // The h2 stays inside as prose, not a new chapter.
        assertTrue(doc.chapters[1].html.contains("<p>A subsection</p>"))
        assertEquals("TRAUMA EXAM", doc.chapters[2].title)
    }

    @Test
    fun kf8FlowChaptersCarryNoHtmlWrapper() {
        // A flow's <html>…</html> must NOT ride into the chapter XHTML.
        val hdr = Hdr().apply { version = 8 }
        val header = makeHeaderRecord(hdr, kf8Rawml.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(kf8Rawml))))
        for (chapter in doc.chapters) {
            assertTrue(!chapter.html.lowercase().contains("<html"))
            assertTrue(!chapter.html.lowercase().contains("</html"))
        }
    }

    @Test
    fun kf8RepeatedTitleOnlyAppearsOnce() {
        val html =
            "<html><head><title>t</title></head><body></body></html>" +
                "<section><h1>DEVELOPMENTAL MILESTONES</h1><p>Body one.</p></section>" +
                "<html><head><title>t</title></head><body></body></html>" +
                "<section><h1>DEVELOPMENTAL MILESTONES</h1></section>" +
                "<html><head><title>t</title></head><body></body></html>" +
                "<section><h1>TRAUMA EXAM</h1><p>Body two.</p></section>"
        val hdr = Hdr().apply { version = 8 }
        val header = makeHeaderRecord(hdr, html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        assertEquals(listOf("DEVELOPMENTAL MILESTONES", "TRAUMA EXAM"), doc.chapters.mapNotNull { it.title })
    }

    @Test
    fun kf8TitleEchoInsideTheFlowIsDropped() {
        val html =
            "<html><head><title>t</title></head><body></body></html>" +
                "<section epub:type=\"chapter\"><h1>Chapter 3—Repentance</h1>" +
                "<div><p>Chapter 3—Repentance</p></div>" +
                "<p>How shall a man be just with God?</p></section>" +
                "<html><head><title>t</title></head><body></body></html>" +
                "<section epub:type=\"chapter\"><h1>Chapter 4—Confession</h1><p>Body.</p></section>"
        val hdr = Hdr().apply { version = 8 }
        val header = makeHeaderRecord(hdr, html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        assertEquals(2, doc.chapters.size)
        assertEquals("Chapter 3—Repentance", doc.chapters[0].title)
        val first = doc.chapters[0].html
        assertTrue(!first.contains("<p>Chapter 3—Repentance</p>"))
        assertTrue(first.contains("How shall a man be just with God?"))
    }

    @Test
    fun kf8RequiresTwoFlowsFallsBackToHeadings() {
        // One stray <html>…</html> inside a MOBI6-shaped document is not a
        // KF8 structure — the heading path must run.
        val html = "<h1>One</h1><p>First.</p><html><head><title>t</title></head><body></body></html><h1>Two</h1><p>Second.</p>"
        val header = makeHeaderRecord(Hdr(), html.length)
        val doc = BookMobiParser.parse(makePdb(listOf(header, palmdocEncode(html))))
        assertEquals(listOf("One", "Two"), doc.chapters.mapNotNull { it.title })
    }

    // ------------------------------------------------------------------
    // Huff/CDIC
    // ------------------------------------------------------------------

    @Test
    fun aHuffCdicBookDecodesEndToEnd() {
        val (huff, cdic) = huffCdicRecords()
        val hdr = Hdr().apply { compression = 17480 }
        val header = makeHeaderRecord(hdr, 8)
        val payload = byteArrayOf(0, 0, 0)
        val doc = BookMobiParser.parse(makePdb(listOf(header, payload, huff, cdic)))
        assertEquals(1, doc.chapters.size)
        assertTrue(doc.chapters[0].html.contains("Hi Hi Hi"))
    }

    // ------------------------------------------------------------------
    // The book's own contents (CNCX)
    // ------------------------------------------------------------------

    /** The byte distance from the h1 to the h2 in the fixture's text. */
    private fun htmlH2Delta(): Long {
        val html = "<html><body><h1>One</h1><p>Alpha text.</p><h2>Two</h2><p>Beta text.</p></body></html>"
        return (html.indexOf("<h2") - html.indexOf("<h1")).toLong()
    }

    /** A book whose index names two chapters: the publisher's labels win
     *  and the cuts land on the index's byte offsets. */
    private fun tocFixture(entryValues: List<Triple<Long, Long, Long>>): ByteArray {
        val html = "<html><body><h1>One</h1><p>Alpha text.</p><h2>Two</h2><p>Beta text.</p></body></html>"
        val h1At = html.indexOf("<h1").toLong()
        val cncx = makeCncx(listOf("Chapter One", "Chapter Two"))
        val entries = entryValues.map { (base, one, two) ->
            // control 0x0D: tags 1 (position), 3 (CNCX), 4 (depth) —
            // tag 2's mask is clear.
            val position = h1At + base
            val label = if (one == 1L) 0 else 12
            makeIndexEntry("000", 0x0D, listOf(position + two, label, 0))
        }
        val indx = makeIndxHeader(1)
        val continuation = makeIndxContinuation(entries)
        val hdr = Hdr().apply { ncxIndex = 2 }
        val header = makeHeaderRecord(hdr, html.length)
        val text = palmdocEncode(html)
        return makePdb(listOf(header, text, indx, continuation, cncx))
    }

    @Test
    fun thePublishersContentsCutTheChapters() {
        val pdb = tocFixture(listOf(Triple(0L, 1L, 0L), Triple(htmlH2Delta(), 0L, 0L)))
        val doc = BookMobiParser.parse(pdb)
        assertEquals(2, doc.chapters.size)
        assertEquals("Chapter One", doc.chapters[0].title)
        assertTrue(doc.chapters[0].html.contains("Alpha text."))
        assertTrue(!doc.chapters[0].html.contains("Beta"))
        assertEquals("Chapter Two", doc.chapters[1].title)
        assertTrue(doc.chapters[1].html.contains("Beta text."))
        assertEquals(2, doc.toc.size)
        assertEquals("Chapter One", doc.toc[0].label)
        assertEquals(0, doc.toc[0].chapterIndex)
        assertEquals("Chapter Two", doc.toc[1].label)
        assertEquals(1, doc.toc[1].chapterIndex)
    }

    @Test
    fun aTocOffsetInsideATagSnapsForward() {
        val pdb = tocFixture(listOf(Triple(0L, 1L, 0L), Triple(htmlH2Delta() + 2, 0L, 0L)))
        val doc = BookMobiParser.parse(pdb)
        assertEquals(2, doc.chapters.size)
        assertEquals("Chapter Two", doc.chapters[1].title)
        assertTrue(doc.chapters[1].html.contains("Beta text."))
    }

    @Test
    fun aOneEntryTocFallsBackToTheHeadings() {
        val pdb = tocFixture(listOf(Triple(0L, 1L, 0L)))
        val doc = BookMobiParser.parse(pdb)
        assertEquals(listOf("One", "Two"), doc.chapters.mapNotNull { it.title })
    }

    @Test
    fun aTocEntryWithoutALabelIsDropped() {
        val html = "<html><body><h1>One</h1><p>Alpha text.</p><h2>Two</h2><p>Beta text.</p></body></html>"
        val h1At = html.indexOf("<h1").toLong()
        val h2At = html.indexOf("<h2").toLong()
        val cncx = makeCncx(listOf("Chapter One"))
        val entries = listOf(
            makeIndexEntry("000", 0x0D, listOf(h1At, 0, 0)),
            makeIndexEntry("001", 0x0D, listOf(h2At, 40, 0)), // offset 40: no string
        )
        val indx = makeIndxHeader(1)
        val continuation = makeIndxContinuation(entries)
        val hdr = Hdr().apply { ncxIndex = 2 }
        val header = makeHeaderRecord(hdr, html.length)
        val doc = BookMobiParser.parse(
            makePdb(listOf(header, palmdocEncode(html), indx, continuation, cncx))
        )
        assertEquals(listOf("One", "Two"), doc.chapters.mapNotNull { it.title })
    }

    @Test
    fun aTocOffsetNamesADepth() {
        val html = "<html><body><h1>One</h1><p>Alpha text.</p><h2>Two</h2><p>Beta text.</p></body></html>"
        val h1At = html.indexOf("<h1").toLong()
        val h2At = html.indexOf("<h2").toLong()
        val cncx = makeCncx(listOf("Chapter One", "Section One"))
        val entries = listOf(
            makeIndexEntry("000", 0x0D, listOf(h1At, 0, 0)),
            makeIndexEntry("001", 0x0D, listOf(h2At, 12, 1)),
        )
        val indx = makeIndxHeader(1)
        val continuation = makeIndxContinuation(entries)
        val hdr = Hdr().apply { ncxIndex = 2 }
        val header = makeHeaderRecord(hdr, html.length)
        val doc = BookMobiParser.parse(
            makePdb(listOf(header, palmdocEncode(html), indx, continuation, cncx))
        )
        assertEquals(0, doc.toc[0].depth)
        assertEquals(1, doc.toc[1].depth)
    }

    // ------------------------------------------------------------------
    // Snapping, directly
    // ------------------------------------------------------------------

    @Test
    fun snapOffsetWalksToATagBoundary() {
        val bytes = "<p>text</p> and <b>more</b>".toByteArray(Charsets.UTF_8)
        assertEquals(0, BookMobiParser.snapOffset(0, bytes)) // already at '<'
        assertEquals(7, BookMobiParser.snapOffset(1, bytes)) // inside the <p>: to </p>
        assertEquals(16, BookMobiParser.snapOffset(13, bytes)) // to the <b>
        assertEquals(null, BookMobiParser.snapOffset(bytes.size, bytes))
        assertEquals(null, BookMobiParser.snapOffset(bytes.size + 5, bytes))
        // An offset already at a '<' snaps nowhere.
        val stray = "two < things <p>x</p>".toByteArray(Charsets.UTF_8)
        assertEquals(4, BookMobiParser.snapOffset(4, stray))
        assertEquals(13, BookMobiParser.snapOffset(8, stray))
    }

    // ------------------------------------------------------------------
    // The index entry walk, directly
    // ------------------------------------------------------------------

    @Test
    fun anIndexEntryDecodesItsMaskedValues() {
        // The real book's control-byte shape: 0x8f = tags 1, 2, 3, 4 and
        // the pair tag all present; the pair tag (numvals 2) reads two
        // vwis for one count.
        val tags = listOf(
            byteArrayOf(1, 1, 1, 0),
            byteArrayOf(2, 1, 2, 0),
            byteArrayOf(3, 1, 4, 0),
            byteArrayOf(6, 2, 8, 0),
            byteArrayOf(0, 0, 0, 1),
        )
        val entry = makeIndexEntry("000", 0x8F, emptyList()) +
            vwi(363) + vwi(2035) + vwi(0) + vwi(0) + vwi(1588)
        val values = BookMobiParser.entryTagValues(entry, 1, tags)!!
        val value = { tag: Int -> values.firstOrNull { it.first == tag }?.second }
        assertEquals(listOf(363L), value(1))
        assertEquals(listOf(2035L), value(2))
        assertEquals(listOf(0L), value(3))
        assertEquals(listOf(0L, 1588L), value(6))
    }

    @Test
    fun theByteCountFormReadsItsOwnVwis() {
        // When the masked value fills a multi-bit mask, a vwi gives the
        // BYTE length of the value area, and that many bytes' vwis follow.
        val tags = listOf(byteArrayOf(6, 2, 0xC0.toByte(), 0), byteArrayOf(0, 0, 0, 1))
        val entry = makeIndexEntry("00", 0xC0, emptyList()) + vwi(3) + vwi(7) + vwi(300)
        val values = BookMobiParser.entryTagValues(entry, 1, tags)!!
        assertEquals(listOf(7L, 300L), values[0].second)
    }

    @Test
    fun aCncxRecordIsItsStrings() {
        // No header: the record IS [vwi length][bytes] from byte 0. Each
        // string is keyed at its length byte.
        val pdbData = makePdb(listOf(makeCncx(listOf("Copyright", "Table of Contents"))))
        val pdb = BookMobiParser.PalmDatabase.parse(pdbData)
        val strings = BookMobiParser.cncxStrings(pdb, 0, 1, 65001)
        assertEquals("Copyright", strings[0L])
        // "Copyright" = vwi(9) + 9 bytes → the next length byte at 10.
        assertEquals("Table of Contents", strings[10L])
        assertEquals(null, strings[11L])
    }
}
