package com.speechnotes.app.logic

/**
 * Huff/CDIC decompression — the `'DH'` compression word (17480) every
 * Kindle-produced `.azw`/`.azw3` carries, and the compression the Mobipocket
 * reader delegates to when record 0 names it. Ported from
 * speechnotes-linux-rust `crates/sn-doc/src/huffcdic.rs`, itself after
 * KindleUnpack's `HuffcdicReader` — the algorithm calibre runs.
 *
 * 1. **The HUFF record holds two tables.** `off1` (u32 at HUFF+8) points at
 *    256 u32 "dict1" entries; `off2` (HUFF+12) at 64 u32 "dict2" entries.
 *    Each dict1 entry packs `codelen` (bits 0–4), `term` (bit 7) and
 *    `maxcode` (bits 8–31): the top byte of a 32-bit bitstream lookahead
 *    indexes dict1 directly, `codelen` bits are consumed, and for
 *    non-terminal entries dict2's mincode/maxcode ladder extends the code
 *    length until the code falls inside `[mincode[codelen], maxcode[codelen]]`.
 *    The phrase index is `maxcode − code`, right-shifted back to the
 *    codelen's scale.
 * 2. **The CDIC records hold the phrase dictionary.** After the 16-byte
 *    header sits a table of one u16 per phrase — cumulative byte offsets
 *    to each entry's `[length][payload]` pair, the length's bit 15 marking
 *    "already expanded". A compressed entry recursively expands through the
 *    same bitstream.
 *
 * One instance per book: the dictionary is shared and lazily expanded
 * across all of that book's text records.
 */
class BookHuffCdic private constructor(
    /** dict1 entry: the code length this top-byte block starts, whether the
     *  entry is terminal, and the max code value the block covers,
     *  pre-shifted to the 32-bit scale. */
    private val dict1: List<Triple<Int, Boolean, Long>>,
    /** Canonical-code ladders for the non-terminal walk, indexed by codelen
     *  1…32, values pre-shifted to the 32-bit lookahead's scale. */
    private val mincode: List<Long>,
    private val maxcode: List<Long>,
    /** The CDIC phrase dictionary; compressed entries expand in place on
     *  first use. */
    private val dictionary: MutableList<Pair<ByteArray, Boolean>>,
) {
    class HuffCdicError(val kind: Kind) : Exception() {
        enum class Kind { BAD_HUFF_HEADER, BAD_CDIC_HEADER }
    }

    /** Builds the tables from one HUFF record and its CDIC dictionary
     *  records. Missing or corrupt tables are an error, not a fallback:
     *  the alternative is decoding the bitstream as noise. */
    companion object {
        fun create(huff: ByteArray, cdics: List<ByteArray>): BookHuffCdic {
            if (huff.size < 16 || magicAt(huff, 0) != "HUFF") {
                throw HuffCdicError(HuffCdicError.Kind.BAD_HUFF_HEADER)
            }
            val off1 = u32(huff, 8).toInt()
            val off2 = u32(huff, 12).toInt()
            if (off1 < 24 || off2 < off1 || off2 + 4 * 64 > huff.size) {
                throw HuffCdicError(HuffCdicError.Kind.BAD_HUFF_HEADER)
            }

            val dict1 = ArrayList<Triple<Int, Boolean, Long>>(256)
            for (index in 0 until 256) {
                val value = u32(huff, off1 + 4 * index)
                val codelen = (value and 0x1F).toInt()
                if (codelen == 0) {
                    throw HuffCdicError(HuffCdicError.Kind.BAD_HUFF_HEADER)
                }
                val term = value and 0x80L != 0L
                // ((maxcode + 1) << (32 - codelen)) - 1, on the 24-bit payload.
                // The parens are load-bearing: the shift applies to the
                // incremented value, and the `- 1` sits outside it.
                val maxcode = ((value shr 8) + 1L shl (32 - codelen)) - 1
                dict1.add(Triple(codelen, term, maxcode))
            }

            // dict2: 64 u32s consumed as (mincode, maxcode) pairs per codelen
            // 1…32. Both ladders carry a 0 sentinel at index 0.
            val mincode = ArrayList<Long>(33)
            val maxcode = ArrayList<Long>(33)
            mincode.add(0L)
            maxcode.add(0L)
            for (codelen in 1..32) {
                if (off2 + 8 * (codelen - 1) + 8 > huff.size) {
                    throw HuffCdicError(HuffCdicError.Kind.BAD_HUFF_HEADER)
                }
                val min = u32(huff, off2 + 8 * (codelen - 1))
                val max = u32(huff, off2 + 8 * (codelen - 1) + 4)
                mincode.add(min shl (32 - codelen))
                maxcode.add((max + 1L shl (32 - codelen)) - 1)
            }

            val dictionary = ArrayList<Pair<ByteArray, Boolean>>()
            var remainingPhrases = -1L
            for (cdic in cdics) {
                if (cdic.size < 16 || magicAt(cdic, 0) != "CDIC") {
                    throw HuffCdicError(HuffCdicError.Kind.BAD_CDIC_HEADER)
                }
                val phrases = u32(cdic, 8)
                val bits = (u32(cdic, 12) and 0xFL).toInt()
                if (remainingPhrases < 0) remainingPhrases = phrases
                // n = min(1 << bits, phrases - alreadyCollected) — a
                // multi-CDIC dictionary fills each record to the smaller of
                // its index capacity and what is left of the book's phrase
                // count.
                val already = dictionary.size.toLong()
                val declared = minOf(1L shl bits, maxOf(phrases - already, 0L))
                val count = minOf(declared, remainingPhrases).toInt()
                // The offset table: one u16 per entry, cumulative from the
                // table's own start. Each entry is `[length u16][payload]`
                // at its offset; bit 15 of the length marks it still
                // compressed.
                val tableEnd = 16 + 2 * count
                if (tableEnd > cdic.size) {
                    throw HuffCdicError(HuffCdicError.Kind.BAD_CDIC_HEADER)
                }
                for (index in 0 until count) {
                    val entryAt = 16 + u16(cdic, 16 + 2 * index).toInt()
                    if (entryAt + 2 > cdic.size) {
                        throw HuffCdicError(HuffCdicError.Kind.BAD_CDIC_HEADER)
                    }
                    val header = u16(cdic, entryAt).toInt()
                    val length = header and 0x7FFF
                    val payloadAt = entryAt + 2
                    if (payloadAt + length > cdic.size) {
                        throw HuffCdicError(HuffCdicError.Kind.BAD_CDIC_HEADER)
                    }
                    // Bit 15 SET is a plain entry; clear means the payload is
                    // itself a bitstream and expands on first use.
                    dictionary.add(
                        cdic.copyOfRange(payloadAt, payloadAt + length) to
                            ((header and 0x8000) != 0)
                    )
                }
                remainingPhrases -= count
            }
            if (dictionary.isEmpty()) {
                throw HuffCdicError(HuffCdicError.Kind.BAD_CDIC_HEADER)
            }

            return BookHuffCdic(dict1, mincode, maxcode, dictionary)
        }

        private fun magicAt(data: ByteArray, offset: Int): String {
            if (offset + 4 > data.size) return ""
            return String(data, offset, 4, Charsets.ISO_8859_1)
        }

        private fun u16(data: ByteArray, offset: Int): Int {
            if (offset + 2 > data.size) return 0
            return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        }

        private fun u32(data: ByteArray, offset: Int): Long {
            if (offset + 4 > data.size) return 0L
            return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
        }

        private fun load64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
            }
            return value
        }
    }

    /**
     * Decompresses one record payload (its trailing-entry table already
     * stripped by the caller). Reads the bitstream through a 64-bit window:
     * `code` is the 32 bits starting at bit `n`; consuming `codelen` bits
     * decrements `n`, and when `n` runs dry the window slides four bytes —
     * padded with eight zero bytes so the final lookahead never reads past
     * the end. The loop ends on `bitsLeft < 0`, not on the padding.
     */
    fun unpack(payload: ByteArray): ByteArray {
        val bytes = ByteArray(payload.size + 8)
        payload.copyInto(bytes, 0)
        // The eight trailing zeros are already there (ByteArray default).
        var bitsLeft = payload.size * 8L
        var pos = 0
        var window = load64(bytes, 0)
        var n: Long = 32

        val out = ByteArrayWriter(4096)

        while (true) {
            if (n <= 0) {
                pos += 4
                if (pos + 8 > bytes.size) break
                window = load64(bytes, pos)
                n += 32
            }
            // code = (window >> n) & 0xFFFFFFFF — the 32-bit lookahead.
            val code = (window ushr n.toInt()) and 0xFFFF_FFFFL
            val (baseCodelen, term, tableMax) = dict1[(code shr 24).toInt()]
            var codelen = baseCodelen
            var walkMaxcode = tableMax
            if (!term) {
                // Canonical walk: extend the code length until the code
                // falls inside [mincode[codelen], maxcode[codelen]].
                while (codelen < mincode.size && code < mincode[codelen]) {
                    codelen++
                }
                if (codelen >= maxcode.size) break
                walkMaxcode = maxcode[codelen]
            }
            n -= codelen
            bitsLeft -= codelen
            if (bitsLeft < 0) break
            val reference = ((walkMaxcode - code) shr (32 - codelen)).toInt()
            if (reference < 0 || reference >= dictionary.size) break
            if (!dictionary[reference].second) {
                val raw = dictionary[reference].first
                dictionary[reference] = raw to true
                val expanded = unpack(raw)
                dictionary[reference] = expanded to true
            }
            out.write(dictionary[reference].first)
        }
        return out.toByteArray()
    }

    /** A growable byte buffer that avoids the boxing of streaming into a
     *  Kotlin ArrayList<Byte>. */
    private class ByteArrayWriter(initial: Int) {
        private var buffer = ByteArray(initial)
        private var length = 0

        fun write(data: ByteArray) {
            if (length + data.size > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, length + data.size))
            }
            data.copyInto(buffer, length)
            length += data.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(length)
    }
}
