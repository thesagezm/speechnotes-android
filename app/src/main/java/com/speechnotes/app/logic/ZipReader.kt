package com.speechnotes.app.logic

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipException

/**
 * Minimal read-only ZIP reader for EPUB containers. Ported from
 * ZipReader.swift (speechnotes-ios): walks the central directory once and
 * serves entries by name with cached data.
 */
class ZipReader private constructor(private val entries: Map<String, ByteArray>) {

    companion object {
        /**
         * Parses [data] as a ZIP. Returns null when the data is not a
         * readable ZIP (missing/partial central directory, corrupt entry).
         */
        fun parse(data: ByteArray): ZipReader? {
            if (data.size < 22) return null
            val eocdSignature = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x05, 0x06)
            var eocd = -1
            val searchStart = maxOf(0, data.size - (22 + 65535))
            for (i in data.size - 22 downTo searchStart) {
                if (data[i] == eocdSignature[0] &&
                    data[i + 1] == eocdSignature[1] &&
                    data[i + 2] == eocdSignature[2] &&
                    data[i + 3] == eocdSignature[3]
                ) { eocd = i; break }
            }
            if (eocd < 0 || eocd + 22 > data.size) return null

            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(eocd)
            // APPNOTE 4.3.16, in order: signature(4) disk(2) cdDisk(2)
            // thisDiskEntries(2) totalEntries(2) cdSize(4) cdOffset(4)
            // commentLen(2). Both entry counts are TWO bytes.
            bb.getInt() // signature
            bb.short   // disk number
            bb.short   // disk with CD
            bb.short   // entries on this disk
            val totalEntries = bb.short.toInt() and 0xFFFF
            bb.int     // CD size
            var cdOffset = bb.int
            bb.short   // comment length

            // A ZIP64 archive stores the real CD offset/size in the ZIP64 EOCD
            // record. EPUBs past 4 GB (and a few mislabelled smaller ones) hit
            // this; without it cdOffset is 0xFFFFFFFF and the walk finds
            // nothing. The ZIP64 EOCD locator sits 20 bytes before the EOCD.
            if (cdOffset == -1 || cdOffset < 0 || cdOffset >= data.size) {
                val locator = eocd - 20
                if (locator >= 0 &&
                    data[locator].toInt() == 0x50 && data[locator + 1].toInt() == 0x4B &&
                    data[locator + 2].toInt() == 0x06 && data[locator + 3].toInt() == 0x07
                ) {
                    val zip64 = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                        .getLong(locator + 8).toInt()
                    if (zip64 >= 0 && zip64 + 56 <= data.size &&
                        data[zip64].toInt() == 0x50 && data[zip64 + 1].toInt() == 0x4B &&
                        data[zip64 + 2].toInt() == 0x06 && data[zip64 + 3].toInt() == 0x06
                    ) {
                        cdOffset = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                            .getLong(zip64 + 48).toInt()
                    }
                }
            }
            if (cdOffset < 0 || cdOffset >= data.size) return null

            val map = HashMap<String, ByteArray>(maxOf(1, totalEntries))
            var cursor = cdOffset
            for (i in 0 until totalEntries) {
                // Read the header with explicit field offsets rather than a
                // sequential ByteBuffer walk. Two of the skipped fields were
                // narrower than the getInt() that consumed them (mod
                // time/date is 2+2, disk-number-start is 2), which shifted
                // every size and pushed localOffset 2 bytes high — so the
                // local-header check failed for EVERY entry and the archive
                // parsed as zero files, i.e. "could not read that EPUB".
                //
                // One bad record must not void the rest either: resync on the
                // next central header instead of aborting.
                if (cursor + 46 > data.size) break
                fun cdInt(at: Int): Long =
                    (data[at].toLong() and 0xFF) or
                        ((data[at + 1].toLong() and 0xFF) shl 8) or
                        ((data[at + 2].toLong() and 0xFF) shl 16) or
                        ((data[at + 3].toLong() and 0xFF) shl 24)
                fun cdShort(at: Int): Int =
                    (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
                if (data[cursor].toInt() != 0x50 || data[cursor + 1].toInt() != 0x4B ||
                    data[cursor + 2].toInt() != 0x01 || data[cursor + 3].toInt() != 0x02
                ) {
                    val next = nextCentralHeader(data, cursor + 1)
                    if (next < 0) break
                    cursor = next
                    continue
                }
                val method = cdShort(cursor + 10)
                val compressedSize = cdInt(cursor + 20).toInt()
                val uncompressedSize = cdInt(cursor + 24).toInt()
                val nameLen = cdShort(cursor + 28)
                val extraLen = cdShort(cursor + 30)
                val commentLen = cdShort(cursor + 32)
                val localOffset = cdInt(cursor + 42).toInt()
                val nameBytes = ByteArray(nameLen)
                System.arraycopy(data, cursor + 46, nameBytes, 0, nameLen)
                val name = String(nameBytes, Charsets.UTF_8)
                cursor += 46 + nameLen + extraLen + commentLen

                // Directory entry?
                if (name.endsWith("/")) continue

                val raw = readLocalEntry(data, localOffset, method, compressedSize) ?: continue
                if (raw.size != uncompressedSize && method == 0) continue
                map[name] = raw
            }
            if (map.isEmpty()) return null
            return ZipReader(map)
        }

        /** Next central-directory-header signature at or after [from]. */
        private fun nextCentralHeader(data: ByteArray, from: Int): Int {
            var i = maxOf(0, from)
            while (i + 4 <= data.size) {
                if (data[i].toInt() == 0x50 && data[i + 1].toInt() == 0x4B &&
                    data[i + 2].toInt() == 0x01 && data[i + 3].toInt() == 0x02
                ) return i
                i++
            }
            return -1
        }

        /**
         * Reads the entry's payload starting at its local header. The
         * CENTRAL directory's compressedSize is authoritative: when a writer
         * streams an entry it sets flag bit 3 (data descriptor) and leaves the
         * sizes in the LOCAL header as zero, so trusting the local copy
         * inflates nothing and the entry silently disappears.
         */
        private fun readLocalEntry(data: ByteArray, localOffset: Int, method: Int, compressedSize: Int): ByteArray? {
            if (localOffset < 0 || localOffset + 30 > data.size) return null
            // Local header, APPNOTE 4.3.7: sig(4) verNeed(2) flags(2)
            // method(2) modTime(2) modDate(2) crc(4) csize(4) usize(4)
            // nameLen(2) extraLen(2). Same narrow-field trap as the central
            // directory: mod time/date is 2+2, not one int.
            fun u16(at: Int): Int =
                (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
            fun u32(at: Int): Long =
                (data[at].toLong() and 0xFF) or
                    ((data[at + 1].toLong() and 0xFF) shl 8) or
                    ((data[at + 2].toLong() and 0xFF) shl 16) or
                    ((data[at + 3].toLong() and 0xFF) shl 24)
            if (data[localOffset].toInt() != 0x50 || data[localOffset + 1].toInt() != 0x4B ||
                data[localOffset + 2].toInt() != 0x03 || data[localOffset + 3].toInt() != 0x04
            ) return null
            val m = u16(localOffset + 8)
            if (m != method) return null
            val nameLen = u16(localOffset + 26)
            val extraLen = u16(localOffset + 28)
            // The CENTRAL directory's compressedSize is authoritative: a writer
            // that streams an entry sets flag bit 3 (data descriptor) and leaves
            // the local sizes as zero, so trusting the local copy inflates
            // nothing and the entry silently disappears.
            val cs = if (compressedSize > 0) compressedSize else u32(localOffset + 18).toInt()
            val dataStart = localOffset + 30 + nameLen + extraLen
            if (cs <= 0 || dataStart + cs > data.size) return null
            val compressed = data.copyOfRange(dataStart, dataStart + cs)
            return when (method) {
                0 -> compressed
                8 -> tryInflate(compressed)
                else -> null
            }
        }

        private fun tryInflate(compressed: ByteArray): ByteArray? {
            return try {
                // nowrap=true assumes a zlib header, which raw DEFLATE lacks.
                // EPUBs in the wild carry both, so sniff the two-byte header
                // instead of guessing — guessing wrong yields a silently
                // empty string for every chapter.
                val hasZlibHeader = compressed.size >= 2 &&
                    (compressed[0].toInt() and 0x0F) == 8 &&
                    ((compressed[0].toInt() and 0xF0) shr 4) <= 7 &&
                    (((compressed[0].toInt() and 0xFF) shl 8) or (compressed[1].toInt() and 0xFF)) % 31 == 0
                val inflater = java.util.zip.Inflater(!hasZlibHeader)
                inflater.setInput(compressed)
                val out = ByteArrayOutputStream(maxOf(64, compressed.size * 4))
                val buffer = ByteArray(32 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        out.write(buffer, 0, n)
                    }
                }
                inflater.end()
                out.toByteArray()
            } catch (e: ZipException) {
                null
            } catch (e: java.util.zip.DataFormatException) {
                null
            }
        }
    }

    val names: Set<String> get() = entries.keys

    fun has(name: String): Boolean = entries.containsKey(name)

    /** Decoded text for the entry, or null when absent. */
    fun text(name: String): String? = entries[name]?.toString(Charsets.UTF_8)

    /** Raw bytes for the entry, or null when absent. */
    fun data(name: String): ByteArray? = entries[name]

    /** Case-insensitive name match (some EPUBs carry odd casing). */
    fun textIgnoringCase(name: String): String? {
        entries[name]?.let { return it.toString(Charsets.UTF_8) }
        val lower = name.lowercase()
        return entries.entries.firstOrNull { it.key.lowercase() == lower }
            ?.value?.toString(Charsets.UTF_8)
    }
}
