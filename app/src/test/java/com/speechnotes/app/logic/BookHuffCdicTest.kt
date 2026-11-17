package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Huff/CDIC pins, ported from sn-doc huffcdic.rs's tests (fixtures
 *  verbatim). */
class BookHuffCdicTest {

    private fun be16(value: Int): ByteArray = byteArrayOf(
        ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte(),
    )

    private fun be32(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /** A minimal but REAL Huff/CDIC table pair: a canonical Huffman code
     *  where every top byte is a terminal 8-bit entry, and a CDIC
     *  dictionary of 256 identical phrases. Code 0x00 decodes through
     *  dict1[0] to dictionary index 255 — the whole path. */
    private fun terminalFixture(): Pair<ByteArray, List<ByteArray>> {
        val huff = ByteArrayOutputStream()
        huff.write("HUFF".toByteArray(Charsets.ISO_8859_1))
        huff.write(be32(0x18))
        huff.write(be32(24)) // off1
        huff.write(be32(24 + 4 * 256)) // off2
        huff.write(ByteArray(8))
        repeat(256) {
            // codelen 8 (bits 0–4), terminal (bit 7), maxcode byte 0xFF.
            huff.write(be32(0xFF88))
        }
        huff.write(ByteArray(4 * 64)) // dict2

        val entries = 256
        val phrase = "Hi ".toByteArray(Charsets.ISO_8859_1)
        val cdic = ByteArrayOutputStream()
        cdic.write("CDIC".toByteArray(Charsets.ISO_8859_1))
        cdic.write(be32(0x10))
        cdic.write(be32(entries.toLong()))
        cdic.write(be32(8)) // index bits
        var offset = 2 * entries
        repeat(entries) {
            cdic.write(be16(offset))
            offset += 2 + phrase.size
        }
        repeat(entries) {
            // Bit 15 SET = plain entry.
            cdic.write(be16(0x8000 or phrase.size))
            cdic.write(phrase)
        }
        return huff.toByteArray() to listOf(cdic.toByteArray())
    }

    /** A CDIC record built the way real writers lay it out. */
    private fun buildCdic(entries: List<Pair<ByteArray, Boolean>>): ByteArray {
        val cdic = ByteArrayOutputStream()
        cdic.write("CDIC".toByteArray(Charsets.ISO_8859_1))
        cdic.write(be32(0x10))
        cdic.write(be32(entries.size.toLong()))
        cdic.write(be32(8)) // index bits
        var offset = 2 * entries.size
        for ((payload, _) in entries) {
            cdic.write(be16(offset))
            offset += 2 + payload.size
        }
        for ((payload, compressed) in entries) {
            // Bit 15 SET = plain; clear = the payload is itself a bitstream.
            cdic.write(be16(payload.size or if (compressed) 0 else 0x8000))
            cdic.write(payload)
        }
        return cdic.toByteArray()
    }

    @Test
    fun aTerminalLiteralBookDecodes() {
        val (huff, cdics) = terminalFixture()
        val reader = BookHuffCdic.create(huff, cdics)
        // 3 codes → "Hi Hi Hi "
        assertEquals("Hi Hi Hi ", String(reader.unpack(byteArrayOf(0, 0, 0))))
    }

    @Test
    fun aCompressedDictionaryEntryExpandsOnFirstUse() {
        // The same fixture, but phrase 254 is "still compressed": its
        // payload is itself a bitstream of three 0x00 codes.
        val (huff, _) = terminalFixture()
        val entries: MutableList<Pair<ByteArray, Boolean>> =
            (0 until 256).map { "Hi ".toByteArray() to false }.toMutableList()
        entries[254] = byteArrayOf(0, 0, 0) to true
        val cdic = buildCdic(entries)

        val reader = BookHuffCdic.create(huff, listOf(cdic))
        val out = reader.unpack(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        assertEquals("Hi Hi Hi Hi Hi Hi ", String(out))
        // The expansion is cached: a second pass reuses it.
        val again = reader.unpack(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        assertEquals("Hi Hi Hi Hi Hi Hi ", String(again))
    }

    @Test
    fun corruptTablesAreErrorsNotNoise() {
        val (huff, cdics) = terminalFixture()
        fun badHuff(block: () -> Unit) {
            try {
                block()
                fail("expected BadHuffHeader")
            } catch (e: BookHuffCdic.HuffCdicError) {
                assertEquals(BookHuffCdic.HuffCdicError.Kind.BAD_HUFF_HEADER, e.kind)
            }
        }
        fun badCdic(block: () -> Unit) {
            try {
                block()
                fail("expected BadCdicHeader")
            } catch (e: BookHuffCdic.HuffCdicError) {
                assertEquals(BookHuffCdic.HuffCdicError.Kind.BAD_CDIC_HEADER, e.kind)
            }
        }
        // Wrong magic, truncated tables, a zero codelen — all refused.
        badHuff { BookHuffCdic.create("nope".toByteArray(), cdics) }
        badHuff { BookHuffCdic.create(huff.copyOf(10), cdics) }
        val zeroCodelen = huff.copyOf()
        be32(0xFF00).copyInto(zeroCodelen, 24) // codelen 0
        badHuff { BookHuffCdic.create(zeroCodelen, cdics) }
        badCdic { BookHuffCdic.create(huff, emptyList()) }
        badCdic { BookHuffCdic.create(huff, listOf("xxxx".toByteArray())) }
    }

    @Test
    fun aTruncatedBitstreamStopsCleanly() {
        val (huff, cdics) = terminalFixture()
        val reader = BookHuffCdic.create(huff, cdics)
        // A payload shorter than the bits it claims still decodes the whole
        // codes it has and stops — the padding keeps the lookahead in bounds.
        assertEquals("Hi ", String(reader.unpack(byteArrayOf(0))))
        assertEquals("", String(reader.unpack(byteArrayOf())))
    }
}
