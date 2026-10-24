package com.speechnotes.app.logic

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal store-only (uncompressed) ZIP writer — what the
 * normalize-to-EPUB pipeline emits. Uncompressed keeps the writer small and
 * the output still valid for ZipReader, epub.js and every unzip tool.
 *
 * The `mimetype` entry of an EPUB must be FIRST and STORED; callers order
 * entries accordingly (this writer never compresses, so that holds
 * automatically). Ported from ZipWriter.swift (speechnotes-ios).
 */
object ZipWriter {

    /** The 1×1 transparent GIF that stands in for unloadable pool images. */
    val transparentPixelGIF: ByteArray = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
        0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00,
        0x00, 0x2C.toByte(), 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
        0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
    )

    private fun u16(value: Int): ByteArray {
        val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort((value and 0xFFFF).toShort())
        return b.array()
    }

    private fun u32(value: Long): ByteArray {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(value.toInt())
        return b.array()
    }

    private fun dosDateTime(): Pair<Int, Int> {
        val now = java.util.Calendar.getInstance()
        val year = maxOf(1980, now.get(java.util.Calendar.YEAR))
        val time = (now.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (now.get(java.util.Calendar.MINUTE) shl 5) or
            (now.get(java.util.Calendar.SECOND) / 2)
        val day = ((year - 1980) shl 9) or ((now.get(java.util.Calendar.MONTH) + 1) shl 5) or
            now.get(java.util.Calendar.DAY_OF_MONTH)
        return time to day
    }

    /** CRC-32 (IEEE 802.3), table-driven. */
    private val crcTable: IntArray by lazy {
        IntArray(256) { i ->
            var c = i
            for (k in 0 until 8) {
                c = if ((c and 1) == 1) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            c and 0xFFFFFFFF.toInt()
        }
    }

    fun crc32(data: ByteArray): Long {
        var crc = -1 // 0xFFFFFFFF
        for (byte in data) {
            crc = crcTable[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return (crc xor -1).toLong() and 0xFFFFFFFFL
    }

    /** Builds the archive: [name] → bytes, in the given order. */
    fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val central = ByteArrayOutputStream()
        val (dosTime, dosDate) = dosDateTime()

        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = crc32(data)
            val size = data.size.toLong()
            val offset = out.size().toLong()

            out.write(u32(0x04034B50L))
            out.write(u16(20))          // version needed
            out.write(u16(0x0800))      // flags: UTF-8 names
            out.write(u16(0))           // method: stored
            out.write(u16(dosTime))
            out.write(u16(dosDate))
            out.write(u32(crc))
            out.write(u32(size))        // compressed size
            out.write(u32(size))        // uncompressed size
            out.write(u16(nameBytes.size))
            out.write(u16(0))
            out.write(nameBytes)
            out.write(data)

            central.write(u32(0x02014B50L))
            central.write(u16(20))      // version made by
            central.write(u16(20))      // version needed
            central.write(u16(0x0800))
            central.write(u16(0))
            central.write(u16(dosTime))
            central.write(u16(dosDate))
            central.write(u32(crc))
            central.write(u32(size))
            central.write(u32(size))
            central.write(u16(nameBytes.size))
            central.write(u16(0))
            central.write(u16(0))
            central.write(u16(0))       // disk number start
            central.write(u16(0))       // internal attributes
            central.write(u32(0))       // external attributes
            central.write(u32(offset))
            central.write(nameBytes)
        }

        val centralOffset = out.size().toLong()
        out.write(central.toByteArray())
        out.write(u32(0x06054B50L))
        out.write(u16(0))
        out.write(u16(0))
        out.write(u16(entries.size))
        out.write(u16(entries.size))
        out.write(u32(central.size().toLong()))
        out.write(u32(centralOffset))
        out.write(u16(0))
        return out.toByteArray()
    }

    // MARK: - Pixel geometry straight from file headers

    fun pixelSize(data: ByteArray): Pair<Int, Int>? {
        val bytes = if (data.size > 64) data.copyOf(64) else data
        if (bytes.size < 10) return null
        fun be(i: Int): Int =
            if (bytes.size > i + 3) ((bytes[i].toInt() and 0xFF) shl 24) or
                ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                (bytes[i + 3].toInt() and 0xFF) else 0
        fun le(i: Int): Int =
            if (bytes.size > i + 1) (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) else 0

        // PNG IHDR
        if (bytes.size >= 24 &&
            bytes[0].toInt() and 0xFF == 0x89 && bytes[1].toInt() and 0xFF == 0x50 &&
            bytes[2].toInt() and 0xFF == 0x4E && bytes[3].toInt() and 0xFF == 0x47 &&
            bytes[4].toInt() and 0xFF == 0x0D && bytes[5].toInt() and 0xFF == 0x0A &&
            bytes[6].toInt() and 0xFF == 0x1A && bytes[7].toInt() and 0xFF == 0x0A
        ) return be(16) to be(20)
        // GIF logical screen
        if (bytes.size >= 10 &&
            bytes[0].toInt() and 0xFF == 0x47 && bytes[1].toInt() and 0xFF == 0x49 && bytes[2].toInt() and 0xFF == 0x46
        ) return le(6) to le(8)
        // BMP DIB header
        if (bytes.size >= 26 && bytes[0].toInt() and 0xFF == 0x42 && bytes[1].toInt() and 0xFF == 0x4D) {
            return le(18) to le(22)
        }
        // JPEG: walk markers to the first SOF
        if (bytes.size >= 4 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xFF == 0xD8) {
            val all = if (data.size > 256 * 1024) data.copyOf(256 * 1024) else data
            var i = 2
            while (i + 9 < all.size) {
                if (all[i].toInt() and 0xFF != 0xFF || all[i + 1].toInt() and 0xFF == 0x00) { i++; continue }
                if (all[i + 1].toInt() and 0xFF == 0xFF) { i++; continue }
                val marker = all[i + 1].toInt() and 0xFF
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val height = ((all[i + 5].toInt() and 0xFF) shl 8) or (all[i + 6].toInt() and 0xFF)
                    val width = ((all[i + 7].toInt() and 0xFF) shl 8) or (all[i + 8].toInt() and 0xFF)
                    return width to height
                }
                val segLen = ((all[i + 2].toInt() and 0xFF) shl 8) or (all[i + 3].toInt() and 0xFF)
                if (segLen <= 0) break
                i += 2 + segLen
            }
        }
        return null
    }
}

/**
 * Relationship + media helpers shared by the office parsers. Ported from
 * `DocumentMedia` in DocumentEpub.swift (speechnotes-ios).
 */
internal object DocumentMedia {

    /** Lexical target resolution ("word" + "media/img1.png" → "word/media/img1.png"). */
    fun resolveTarget(baseDir: String, target: String): String {
        var raw = target
        if (raw.startsWith("/")) raw = raw.substring(1)
        val stack = if (baseDir.isEmpty()) ArrayList<String>() else baseDir.split("/").toMutableList() as ArrayList
        for (part in raw.split("/")) {
            when (part) {
                ".", "" -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    /** Displayable image mime, or null for formats neither WebKit nor BitmapFactory shows. */
    fun mimeForExtension(ext: String): String? =
        when (ext.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp", "dib" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> null
        }

    fun extensionForMime(mime: String): String =
        when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            else -> "bin"
        }

    fun loadImage(target: String, alt: String, archive: ZipReader): DocumentImage? {
        val ext = target.substringAfterLast('.', "")
        val mime = mimeForExtension(ext) ?: return null
        val data = archive.data(target) ?: return null
        if (data.isEmpty()) return null
        return DocumentImage(data, mime, alt)
    }

    /**
     * Resolves a parse's image pool — pool-ALIGNED, with a 1×1 GIF standing
     * in for any entry that fails to load (blocks reference pool indexes; a
     * shifted array would attach every later image to the wrong block).
     */
    fun resolvePool(pool: DocumentImagePool, archive: ZipReader): List<DocumentImage> =
        pool.targets.map { target ->
            loadImage(target, pool.alt(target), archive)
                ?: DocumentImage(ZipWriter.transparentPixelGIF, "image/gif")
        }

    /** The office suites' embedded document thumbnails, in preference order. */
    fun embeddedThumbnail(archive: ZipReader): DocumentImage? {
        val candidates = listOf(
            "docProps/thumbnail.jpeg", "docProps/thumbnail.png", "Thumbnails/thumbnail.png",
        )
        for (candidate in candidates) {
            val data = archive.data(candidate) ?: continue
            if (data.isEmpty()) continue
            val mime = mimeForExtension(candidate.substringAfterLast('.', "")) ?: continue
            return DocumentImage(data, mime, "Document thumbnail")
        }
        return null
    }
}

/**
 * Shared image-pool bookkeeping: blocks reference images by pool index so
 * the same media file used twice is stored (and shipped) once.
 */
internal class DocumentImagePool {
    private val _targets = ArrayList<String>()
    private val altByTarget = HashMap<String, String>()
    private val indexByTarget = HashMap<String, Int>()

    val targets: List<String> get() = _targets

    fun index(target: String, alt: String): Int {
        indexByTarget[target]?.let { existing ->
            if ((altByTarget[target] ?: "").isEmpty() && alt.isNotEmpty()) altByTarget[target] = alt
            return existing
        }
        _targets.add(target)
        indexByTarget[target] = _targets.size - 1
        altByTarget[target] = alt
        return _targets.size - 1
    }

    fun alt(target: String): String = altByTarget[target] ?: ""
}

/** The flat per-document item stream the delegates emit before chapters are cut. */
internal sealed class DocumentItem {
    data class Heading(val text: String, val level: Int) : DocumentItem()
    data class Paragraph(val text: String) : DocumentItem()
    data class Image(val index: Int) : DocumentItem()
    data class Table(val rows: List<List<DocumentTableCell>>) : DocumentItem()

    /** Slides convert their item stream straight to blocks — no headings there. */
    fun asBlock(): DocumentBlock? =
        when (this) {
            is Paragraph -> DocumentBlock.Paragraph(text)
            is Image -> DocumentBlock.Image(index)
            is Table -> DocumentBlock.Table(rows)
            is Heading -> null
        }
}

/** Element local-name helper — every format's namespace prefixes differ. */
internal fun localName(name: String): String = name.substringAfterLast(':')

/**
 * Element name tolerant of the SAX parser's namespace mode: the same parser
 * reports a bare `w:p` in one mode and a full `uri w:p`/null localName in
 * another, so we take whichever of the two is non-empty and strip the prefix.
 */
internal fun localName(qName: String?, localName: String?): String {
    val name = when {
        !localName.isNullOrEmpty() -> localName
        else -> qName.orEmpty()
    }
    return name.substringAfterLast(':')
}

/** Attribute map keyed by local name AND full qualified name. */
internal fun attrs(attrs: Array<out String>?, names: Array<out String>?): Map<String, String> {
    val map = HashMap<String, String>()
    if (attrs == null || names == null) return map
    var i = 0
    while (i < attrs.size) {
        map[attrs[i]] = names[i]
        i++
    }
    return map
}

/** Looks an attribute up ignoring its namespace prefix (r:embed → embed). */
internal fun Map<String, String>.prefixed(key: String): String? =
    this[key] ?: this[localName(key)] ?: entries.firstOrNull { it.key.substringAfterLast(':') == key }?.value
