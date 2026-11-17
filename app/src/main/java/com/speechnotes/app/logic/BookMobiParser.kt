package com.speechnotes.app.logic

/**
 * Mobipocket / Kindle reader — `.mobi`/`.azw`/`.azw3`/`.prc`/`.pdb`, one
 * parser: a self-contained Palm Database reader, the PalmDOC decompressor,
 * the Huff/CDIC decompressor ([BookHuffCdic]), and an HTML-to-chapters
 * pass. Ported from speechnotes-linux-rust `crates/sn-doc/src/mobi.rs`.
 *
 * ## What the parse takes
 *
 * - **PDB header → record 0 MOBI header**: compression (1 = PalmDOC,
 *   2 = none, 17480 = HUFF/CDIC), the text record count, extra flags, the
 *   first image index, the codepage, and the encryption type — a non-zero
 *   one is a **DRM refusal**, said plainly rather than decoded as noise.
 * - **Hybrid files** (a MOBI6 part with a KF8 part behind a `BOUNDARY`
 *   record, named by EXTH 116): the KF8 part wins, and every record
 *   number its header declares is rebased onto where it starts.
 * - **Text records** (records `1..=count`), each stripped of its
 *   trailing-entry table first, then decompressed — PalmDOC across the
 *   *joined* records (a back-reference may cross a record boundary),
 *   Huff/CDIC record by record (its bitstream restarts at each boundary).
 * - **EXTH** metadata inside record 0: title (503), author (100),
 *   language (524), cover and thumbnail record offsets (201/202).
 * - **Images**: `<img recindex="N">` (MOBI6) and `<img
 *   src="kindle:embed:NNNN…">` (KF8) resolve into the resource records and
 *   become the book's `img/NNNNNN` pool, in first-use order. A reference
 *   nothing answers loses its tag.
 * - **Chapters**, by the first flow that applies: the book's own contents
 *   (CNCX index, cut at the publisher's byte offsets with snapping to tag
 *   boundaries), then — for KF8 rawML — the `<html>…</html>` flow
 *   boundaries, then headings (h1/h2 open chapters), and finally chunking
 *   for a book with no structure at all.
 *
 * Not supported, and said so rather than half-done: DRM-locked books and
 * KFX containers.
 */
object BookMobiParser {

    /** Ceiling on chapters from one book. A book whose markup carries
     *  thousands of headings must not build an array the reader then
     *  renders row by row. */
    private const val MAX_CHAPTERS = 4_000

    /** The lane's bound on one chapter's blocks. */
    private const val CHAPTER_BLOCK_LIMIT = 150

    /** Ceiling on decompressed text — a book that expands without bound
     *  must not take the import down with it. */
    private const val MAX_TEXT_BYTES = 64 * 1024 * 1024

    /** The DRM refusal, verbatim what the import path shows the user. */
    const val DRM_MESSAGE: String =
        "This file is DRM-locked. Books bought from a store are encrypted and can't be read here."

    /** Parse a whole Mobipocket file. Pure and CPU-bound — callers run it
     *  off the main thread (a 50 MB `.azw3` spends real time in here). */
    fun parse(data: ByteArray): BookDoc {
        val pdb = PalmDatabase.parse(data)
        // The hybrid preference: a MOBI6 part with a KF8 part behind a
        // BOUNDARY record reads the KF8 part, rebase and all.
        val (header, base) = pickHeader(pdb)
        if (header.encryption != 0) {
            throw BookDocError.Unsupported(DRM_MESSAGE)
        }
        val exth = exthOf(pdb, base, header)
        if (header.compression !in intArrayOf(1, 2, 17480)) {
            throw BookDocError.Malformed("unknown compression ${header.compression}")
        }

        val records = collectTextRecords(pdb, header, base)
        val body: ByteArray = if (header.compression == 17480) {
            val (huff, cdics) = collectHuffCdicRecords(pdb, header, base)
            val reader = try {
                BookHuffCdic.create(huff, cdics)
            } catch (e: BookHuffCdic.HuffCdicError) {
                throw BookDocError.Malformed("the book's Huff/CDIC tables are corrupt")
            }
            var out = ByteArrayWriter(header.textLength.coerceAtMost(MAX_TEXT_BYTES))
            for (record in records) {
                out.write(reader.unpack(record))
                if (out.size > MAX_TEXT_BYTES) throw tooLarge()
            }
            out.toByteArray()
        } else {
            // The PalmDOC path concatenates the per-record payloads first —
            // a back-reference may reach across a record boundary. Huff/CDIC
            // is the branch above: its bitstream restarts per record.
            var joined = ByteArrayWriter(records.sumOf { it.size })
            for (record in records) joined.write(record)
            val out = decompressPalmdoc(joined.toByteArray())
            if (out.size > MAX_TEXT_BYTES) throw tooLarge()
            out
        }
        // Some writers end the last record with a sentinel '#'; it is not
        // content.
        var bodyLength = body.size
        if (bodyLength > 0 && body[bodyLength - 1] == '#'.code.toByte()) bodyLength--
        val html = decodeText(body.copyOf(bodyLength), header.codepage)

        // The prepared markup first (the head/style bodies and pagebreaks
        // go), then the images: the chapter walk below sees the rewritten
        // `<img src>`, and the pool holds only what the walk will reference.
        val prepared = prepare(html)
        val (displayHtml, images) = extractImages(prepared, pdb, header, base)
        // The book's own table of contents, from its index records. Read
        // BEFORE the chapter paths so they can cut at the publisher's
        // offsets; empty for a book that carries no index.
        val toc = tableOfContents(pdb, header, base)
        val (list, bookToc) = chaptersOf(displayHtml, header.isKf8, toc)
        if (list.isEmpty()) {
            throw BookDocError.Malformed("no readable text in the Mobipocket book")
        }
        val chapters = list.map { (title, html) -> BookChapter(title, html) }

        val cover = coverImage(pdb, header, base, exth)?.let { (mime, data) ->
            BookImage(name = "img/cover", mime = mime, data = data.copyOf())
        }
        return BookDoc(
            title = exth.title ?: header.title,
            author = exth.author,
            language = exth.language,
            cover = cover,
            chapters = chapters,
            toc = bookToc,
            images = images,
        )
    }

    /** True when [data] looks like a Mobipocket book — cheap enough for an
     *  extension sniff, and it rejects a `.mobi` that is really a ZIP. The
     *  `.pdb` extension is shared with every other Palm-era format, so the
     *  import path gates on this before handing a `.pdb` over. */
    fun looksLikeMobi(data: ByteArray): Boolean {
        if (data.size <= 68) return false
        return String(data, 60, 4, Charsets.ISO_8859_1) == "BOOK" &&
            (String(data, 64, 4, Charsets.ISO_8859_1) == "MOBI" ||
                String(data, 64, 4, Charsets.ISO_8859_1) == "REAd")
    }

    private fun tooLarge(): BookDocError.TooLarge =
        BookDocError.TooLarge(MAX_TEXT_BYTES / 1024, MAX_TEXT_BYTES / (1024 * 1024))

    // ---------------------------------------------------------------------------
    // The Palm Database
    // ---------------------------------------------------------------------------

    /**
     * Minimal Palm Database reader: the header, the record-offset list, and
     * record access by index — v0, the only kind Mobipocket books use.
     *
     * Two record-list layouts exist: 8 bytes per entry (offset, id,
     * attributes) and 4 bytes per entry (offset only). The spec's hint —
     * "a zero first entry in the next-IDs list means the list is padded" —
     * is NOT usable: a normal book's next-IDs list is ALL ZEROS. Choosing by
     * VALIDITY instead is what a real reader has to do: read both and keep
     * the one whose offsets are all inside the file and non-decreasing.
     */
    internal class PalmDatabase private constructor(
        private val archive: ByteArray,
        /** Offsets into [archive]; a deleted record sits at 0 and reads as null. */
        private val offsets: List<Int>,
    ) {
        companion object {
            internal fun parse(archive: ByteArray): PalmDatabase {
                if (archive.size <= 86) {
                    throw BookDocError.Malformed(
                        "not a Mobipocket book — the file is too small for a Palm Database"
                    )
                }
                // BOOKMOBI is every Kindle-era book; REAd is ancient PRC.
                if (String(archive, 60, 4, Charsets.ISO_8859_1) != "BOOK" ||
                    (String(archive, 64, 4, Charsets.ISO_8859_1) != "MOBI" &&
                        String(archive, 64, 4, Charsets.ISO_8859_1) != "REAd")
                ) {
                    throw BookDocError.Malformed(
                        "not a Mobipocket book — the Palm Database is not of the book type"
                    )
                }
                val count = u16(archive, 76).toInt()
                if (count == 0 || count >= 200_000) {
                    throw BookDocError.Malformed("record count $count out of range")
                }
                val narrow = readOffsets(archive, 8, count)
                val padded = readOffsets(archive, 4, count)
                val offsets = if (looksValid(archive, padded) && !looksValid(archive, narrow)) {
                    padded
                } else {
                    narrow
                }
                return PalmDatabase(archive, offsets)
            }

            /** Reads [count] offsets with the given entry stride. An entry
             *  that runs off the end yields a sentinel 0 that [looksValid]
             *  rejects. */
            private fun readOffsets(archive: ByteArray, entrySize: Int, count: Int): List<Int> {
                val result = ArrayList<Int>(count)
                var cursor = 78
                for (i in 0 until count) {
                    if (cursor + 4 <= archive.size) {
                        result.add(u32(archive, cursor).toInt())
                    } else {
                        result.add(0)
                    }
                    cursor += entrySize
                }
                return result
            }

            /** A layout is plausible when every offset is inside the file
             *  and the list runs forwards (records are stored in order). */
            private fun looksValid(archive: ByteArray, candidate: List<Int>): Boolean {
                if (candidate.isEmpty()) return false
                var previous = 0
                for (offset in candidate) {
                    if (offset < 78 || offset + 2 >= archive.size || offset < previous) return false
                    previous = offset
                }
                return true
            }
        }

        internal val recordCount: Int get() = offsets.size

        /** Record bytes at the entry's offset. A deleted record (offset 0)
         *  reads as `null` rather than a slice at a nonsense index. There
         *  is NO per-record checksum in the format. */
        internal fun record(index: Int): ByteArray? {
            val start = offsets.getOrNull(index) ?: return null
            if (start == 0 || start >= archive.size) return null
            val end = offsets.getOrNull(index + 1) ?: archive.size
            if (end <= start || end > archive.size) return null
            return archive.copyOfRange(start, end)
        }
    }

    // ---------------------------------------------------------------------------
    // Record 0: the MOBI header and its EXTH
    // ---------------------------------------------------------------------------

    /** The header record's fields. Offsets are the MobileRead layout; the
     *  ones past 0xE4 exist only on writers long enough to carry them. */
    private class MobiHeader(
        val compression: Int,
        val textLength: Int,
        val textRecordCount: Int,
        val encryption: Int,
        val codepage: Long,
        val isKf8: Boolean,
        /** extra flags: how many trailing-entry counts each text record ends
         *  with. The field only exists once the header is at least 0xF4
         *  bytes long, and a header length of exactly 0xE4 is a known
         *  kindlegen bug where the field holds garbage. */
        val extraFlags: Int,
        /** The record number of the first image resource, local to this
         *  part of the file; 0 when the writer left it unset. */
        val firstImageIndex: Int,
        /** The record number of the NCX index — the book's own contents; 0
         *  when the writer carried none. */
        val ncxIndex: Int,
        /** The header's own title, for writers that keep it outside the EXTH. */
        val title: String?,
        /** Where the EXTH block sits inside the header record, when there is one. */
        val exthRange: Pair<Int, Int>?,
    ) {
        companion object {
            fun parse(record: ByteArray): MobiHeader {
                if (record.size < 16) {
                    throw BookDocError.Malformed("header record too short")
                }
                val compression = u16(record, 0).toInt()
                val textLength = u32(record, 4).toInt()
                val textRecordCount = u16(record, 8).toInt()
                val recordSize = u16(record, 10).toInt()
                val encryption = u16(record, 12).toInt()
                // Ancient PRC files stop after the PalmDOC header (≤16
                // bytes): no MOBI magic, no title, no EXTH. They still read.
                if (record.size <= 16) {
                    return MobiHeader(
                        compression, textLength, textRecordCount, encryption,
                        codepage = 1252, isKf8 = false, extraFlags = 0,
                        firstImageIndex = 0, ncxIndex = 0, title = null, exthRange = null,
                    )
                }
                val magic = String(record, 16, 4, Charsets.ISO_8859_1)
                if (magic != "MOBI" && magic != "BOOK" && magic != "TEXt") {
                    throw BookDocError.Malformed(
                        "not a Mobipocket book — the header record carries no MOBI header"
                    )
                }
                if (recordSize == 0 || recordSize > 64 * 1024) {
                    throw BookDocError.Malformed("implausible record size $recordSize")
                }
                if (textRecordCount == 0 || textRecordCount >= 100_000) {
                    throw BookDocError.Malformed("implausible text record count $textRecordCount")
                }
                if (record.size < 40) {
                    throw BookDocError.Malformed("truncated MOBI header")
                }
                val headerLength = u32(record, 20).toInt()
                val codepage = u32(record, 28)
                val isKf8 = u32(record, 0x68) == 8L

                // Title: an offset/length pair into record 0, or zero on
                // writers that keep the real title in EXTH only.
                val titleOffset = u32(record, 0x54).toInt()
                val titleLength = u32(record, 0x58).toInt()
                var title: String? = null
                if (titleOffset > 0 && titleLength > 0 &&
                    titleOffset + titleLength <= record.size &&
                    titleOffset + titleLength < 0xFFFF_FFFF
                ) {
                    val value = decodeText(record.copyOfRange(titleOffset, titleOffset + titleLength), codepage)
                        .trim()
                    if (value.isNotEmpty()) title = value
                }

                val extraFlags = if (headerLength in 0xE5..0x1000 && record.size >= 0xF4) {
                    u16(record, 0xF2).toInt()
                } else {
                    0
                }
                val firstImageIndex = plausibleRecord(u32(record, 0x6C), record.size >= 0x70)
                val ncxIndex = plausibleRecord(u32(record, 0xF4), record.size >= 0xF8)

                // EXTH sits immediately after the fixed header when the flag
                // word at 0x80 has bit 6 set. Scanning for the "EXTH" magic
                // as a fallback catches writers that set that flag wrongly.
                var exthRange: Pair<Int, Int>? = null
                val exthFlag = if (record.size >= 0x84) u32(record, 0x80) else 0L
                val start = 16 + headerLength
                if (exthFlag and 0x40L != 0L &&
                    start + 12 <= record.size &&
                    String(record, start, 4, Charsets.ISO_8859_1) == "EXTH"
                ) {
                    exthRange = start to record.size
                }
                if (exthRange == null) exthRange = findExth(record)

                return MobiHeader(
                    compression, textLength, textRecordCount, encryption,
                    codepage, isKf8, extraFlags, firstImageIndex, ncxIndex,
                    title, exthRange,
                )
            }

            /** A record-number header field, when the record is long enough
             *  to carry it and the writer set it. 0 = unset. */
            private fun plausibleRecord(value: Long, longEnough: Boolean): Int =
                if (longEnough && value > 0 && value != 0xFFFF_FFFFL && value <= 100_000) value.toInt() else 0

            /** The EXTH block found by its magic, validated against its
             *  declared length. `null` when there is no plausible one. */
            private fun findExth(record: ByteArray): Pair<Int, Int>? {
                if (record.size < 20) return null
                var at = -1
                var i = 16
                while (i + 4 <= record.size) {
                    if (String(record, i, 4, Charsets.ISO_8859_1) == "EXTH") {
                        at = i
                        break
                    }
                    i++
                }
                if (at < 0) return null
                val count = u32(record, at + 8)
                val length = u32(record, at + 4).toInt()
                return if (count < 2_000 && length >= 12 && length <= record.size - at) {
                    at to (at + length)
                } else {
                    null
                }
            }
        }
    }

    /** The metadata records inside an EXTH block. The handful the shelf and
     *  the cover path show are read; subjects, ASINs, UUIDs and update
     *  stamps are skipped rather than decoded. */
    private class Exth {
        var title: String? = null
        var author: String? = null
        var language: String? = null
        /** EXTH 201/202, decoded: the record offset (from the first image
         *  record) of the cover / its thumbnail. `-1` when the writer left
         *  the field unset (`0xFFFFFFFF`). */
        var coverOffset = -1L
        var thumbnailOffset = -1L
        /** EXTH 116: the record number where a KF8 part begins in a hybrid
         *  file (its previous record is the `BOUNDARY` sentinel). */
        var kf8Boundary = -1L
    }

    /** The EXTH block of the header the parse reads (record [base]). */
    private fun exthOf(pdb: PalmDatabase, base: Int, header: MobiHeader): Exth {
        val blob = pdb.record(base)?.let { record ->
            header.exthRange?.let { (s, e) ->
                if (e <= record.size) record.copyOfRange(s, e) else null
            }
        } ?: ByteArray(0)
        return parseExth(blob)
    }

    private fun parseExth(blob: ByteArray): Exth {
        val exth = Exth()
        if (blob.size < 12 || String(blob, 0, 4, Charsets.ISO_8859_1) != "EXTH") return exth
        val count = u32(blob, 8).toInt()
        var cursor = 12
        for (i in 0 until count) {
            if (cursor + 8 > blob.size) break
            val kind = u32(blob, cursor).toInt()
            val length = u32(blob, cursor + 4).toInt()
            if (length < 8 || cursor + length > blob.size) break
            val payload = blob.copyOfRange(cursor + 8, cursor + length)
            val text = decodeText(payload, 65001)
                .trim { it == '\u0000' || it.isWhitespace() }
            when {
                kind == 100 && text.isNotEmpty() && exth.author == null -> exth.author = text
                kind == 503 && text.isNotEmpty() && exth.title == null -> exth.title = text
                kind == 524 && text.isNotEmpty() && exth.language == null -> exth.language = text
                kind == 201 && payload.size >= 4 -> {
                    val value = u32(payload, payload.size - 4)
                    if (value != 0xFFFF_FFFFL) exth.coverOffset = value
                }
                // 202 is the thumbnail record offset; 203 is the fake-cover
                // flag — the reference read the flag as an offset.
                kind == 202 && payload.size >= 4 -> {
                    val value = u32(payload, payload.size - 4)
                    if (value != 0xFFFF_FFFFL) exth.thumbnailOffset = value
                }
                kind == 116 && payload.size >= 4 -> exth.kf8Boundary = u32(payload, payload.size - 4)
            }
            cursor += length
        }
        return exth
    }

    /** The header the parse reads: record 0, unless the file is a hybrid
     *  whose KF8 part (named by EXTH 116, behind a `BOUNDARY` record)
     *  supersedes it. Returns the header and the record base its numbers
     *  are relative to. */
    private fun pickHeader(pdb: PalmDatabase): Pair<MobiHeader, Int> {
        val record0 = pdb.record(0)
            ?: throw BookDocError.Malformed("missing book header record")
        val header = MobiHeader.parse(record0)
        val kf8Boundary = header.exthRange?.let { (s, e) ->
            if (e <= record0.size) parseExth(record0.copyOfRange(s, e)).kf8Boundary else -1L
        } ?: -1L
        if (kf8Boundary > 0) {
            val boundary = kf8Boundary.toInt()
            val boundaryRecord = if (boundary < pdb.recordCount) pdb.record(boundary - 1) else null
            if (boundaryRecord != null &&
                boundaryRecord.size == 8 &&
                String(boundaryRecord, 0, 8, Charsets.ISO_8859_1) == "BOUNDARY"
            ) {
                val kf8Record = pdb.record(boundary)
                if (kf8Record != null) {
                    val kf8Header = runCatching { MobiHeader.parse(kf8Record) }.getOrNull()
                    if (kf8Header != null) {
                        // The KF8 part is its own book: prefer it unless it is
                        // the DRM'd one — a DRM refusal must name the part
                        // the reader would actually open.
                        if (kf8Header.encryption == 0 || header.encryption != 0) {
                            return kf8Header to boundary
                        }
                    }
                }
            }
        }
        return header to 0
    }

    // ---------------------------------------------------------------------------
    // Text records: trailing entries, PalmDOC, Huff/CDIC
    // ---------------------------------------------------------------------------

    /** The text records, each stripped of its trailing-entry table first.
     *  Text always starts at record 1: EXTH lives INSIDE record 0 in
     *  Mobipocket books, there is no separate EXTH record.
     *
     *  Returns the STRIPPED records rather than a joined blob: PalmDOC
     *  decompresses the concatenation (a back-reference may span records),
     *  but Huff/CDIC decompresses RECORD BY RECORD (its bitstream restarts
     *  at each record boundary). */
    private fun collectTextRecords(pdb: PalmDatabase, header: MobiHeader, base: Int): List<ByteArray> {
        val count = header.textRecordCount
        if (pdb.recordCount < base + 1 + count) {
            throw BookDocError.Malformed(
                "header declares $count text record(s) but the file has ${pdb.recordCount - base}"
            )
        }
        val out = ArrayList<ByteArray>(count)
        var total = 0
        for (index in 0 until count) {
            val record = pdb.record(base + 1 + index)
                ?: throw BookDocError.Malformed("text record ${index + 1} of $count is missing")
            val cut = trailingEntryBytes(record, header.extraFlags)
            val payload = if (cut < record.size) record.copyOfRange(0, record.size - cut) else record
            total += payload.size
            if (total > MAX_TEXT_BYTES) throw tooLarge()
            out.add(payload)
        }
        return out
    }

    /** The HUFF record and its CDIC dictionary records, found by magic among
     *  the resource records after the text — their position is not fixed.
     *  Missing tables are an error, not a fallback. */
    private fun collectHuffCdicRecords(
        pdb: PalmDatabase,
        header: MobiHeader,
        base: Int,
    ): Pair<ByteArray, List<ByteArray>> {
        var huff: ByteArray? = null
        val cdics = ArrayList<ByteArray>()
        for (index in (base + 1 + header.textRecordCount) until pdb.recordCount) {
            val record = pdb.record(index) ?: continue
            if (record.size < 16) continue
            val magic = String(record, 0, 4, Charsets.ISO_8859_1)
            if (magic == "HUFF") {
                if (huff == null) huff = record
            } else if (magic == "CDIC") {
                cdics.add(record)
            }
        }
        val huffFound = huff
        if (huffFound != null && cdics.isNotEmpty()) return huffFound to cdics
        throw BookDocError.Malformed(
            "Huff/CDIC compression is declared but its tables are missing"
        )
    }

    /** One trailing-entry varint VALUE: seven bits per byte, read BACKWARD
     *  from [size], top bit marks the last byte, capped at 28 bits. This is
     *  calibre's `sizeof_trailing_entry` — the value, not a byte count, is
     *  what the caller adds. */
    private fun trailingCount(record: ByteArray, size: Int): Int {
        var bitPosition = 0
        var result = 0
        var cursor = size
        while (cursor > 0 && bitPosition < 28) {
            val byte = record[cursor - 1].toInt() and 0xFF
            result = result or ((byte and 0x7F) shl bitPosition)
            bitPosition += 7
            cursor -= 1
            if (byte and 0x80 != 0) return result
        }
        return result
    }

    /** How many trailing bytes [extraFlags] says a record ends with.
     *
     *  This is calibre's `sizeof_trailing_entries`: bits 15…1 of the flags
     *  peel varint entries from the record's end, and bit 0 adds
     *  `(byte & 0x3) + 1`. Every set bit does the same backward-varint
     *  read, so the peel order does not change the total. */
    internal fun trailingEntryBytes(record: ByteArray, extraFlags: Int): Int {
        if (extraFlags == 0 || record.isEmpty()) return 0
        var total = 0
        var bit = 15
        while (bit >= 1) {
            if (extraFlags and (1 shl bit) != 0) {
                if (record.size <= total) return 0
                total += trailingCount(record, record.size - total)
            }
            bit -= 1
        }
        if (extraFlags and 1 == 1) {
            val offset = record.size - total - 1
            if (offset < 0) return 0
            total += (record[offset].toInt() and 0x3) + 1
        }
        return total
    }

    /** The PalmDOC LZ77 decompression, exactly — the command byte is not a
     *  flag byte: 0 and 0x09…0x7F are literals (so a record can begin with
     *  literal `<html>`), 1…8 is a literal RUN of that length, 0xC0…0xFF is
     *  "space + `byte ^ 0x80`", and only 0x80…0xBF is a back-reference.
     *
     *  A back-reference whose distance is zero, or reaches past the start
     *  of the output, is skipped rather than clamped, exactly as the
     *  reference implementations do: clamping would splice unrelated bytes
     *  into prose. */
    fun decompressPalmdoc(input: ByteArray): ByteArray {
        val out = ByteArrayWriter(minOf(input.size * 4, MAX_TEXT_BYTES).coerceAtLeast(16))
        var index = 0
        val count = input.size
        while (index < count) {
            val command = input[index].toInt() and 0xFF
            index += 1
            if (command in 1..8) {
                // A literal RUN of that many bytes.
                var remaining = command
                while (remaining > 0 && index < count) {
                    out.write(input[index])
                    index += 1
                    remaining -= 1
                }
            } else if (command <= 0x7F) {
                out.write(command.toByte())
            } else if (command >= 0xC0) {
                // The writer's space-plus-ASCII shorthand.
                out.write(0x20.toByte())
                out.write((command xor 0x80).toByte())
            } else if (index < count) {
                // 0x80…0xBF: a back reference. The low 3 bits are length − 3,
                // the upper 14 bits the distance.
                val value = (command shl 8) or (input[index].toInt() and 0xFF)
                index += 1
                val distance = (value and 0x3FFF) shr 3
                val length = (value and 0x7) + 3
                if (distance > 0 && distance <= out.size) {
                    var source = out.size - distance
                    for (i in 0 until length) {
                        out.write(out[source])
                        source += 1
                    }
                }
            }
            if (out.size > MAX_TEXT_BYTES) break
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------------
    // Decoding
    // ---------------------------------------------------------------------------

    /** The header's codepage first, then the CP1252 floor — a real book in
     *  any encoding must come back as text, never an error. */
    private fun decodeText(data: ByteArray, codepage: Long): String {
        when (codepage) {
            65001L -> {
                decodeStrictUtf8(data)?.let { return it }
            }
            1251L -> return BookCodepages.decodeCp1251(data)
            1250L -> return BookCodepages.decodeCp1250(data)
        }
        return BookCodepages.decodeCp1252(data)
    }

    private fun decodeStrictUtf8(data: ByteArray): String? = runCatching {
        java.nio.charset.Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(data)).toString()
    }.getOrNull()

    // ---------------------------------------------------------------------------
    // The book's HTML as items: headings, paragraphs, images
    // ---------------------------------------------------------------------------

    /** One thing the walk collected, in document order. Headings here are
     *  the h1/h2 that open chapters; an h3+'s words are prose. */
    private sealed class Item {
        class Heading(val text: String) : Item()
        class Paragraph(val text: String) : Item()
        /** An `<img>` whose reference resolved to the pool — `name` is the
         *  `img/…` reference the chapter XHTML carries. */
        class Image(val name: String, val alt: String?) : Item()
    }

    /** The prepared markup: `<script>`/`<style>`/`<head>` bodies become a
     *  space, `<mbp:pagebreak/>` becomes a blank line. */
    private fun prepare(html: String): String {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(html.length)
        var at = 0
        while (at < bytes.size) {
            if (bytes[at] == '<'.code.toByte()) {
                val skipped = skippedElementOrPagebreak(bytes, at)
                if (skipped != null) {
                    val (end, replacement) = skipped
                    out.append(replacement)
                    at = end
                    continue
                }
            }
            val ch = charAtUtf8(bytes, at)
            out.append(ch)
            at += ch.toString().toByteArray(Charsets.UTF_8).size
        }
        return out.toString()
    }

    /** `<script|style|head …>…</name>` → one space; `<mbp:pagebreak/>` → a
     *  blank line. Returns the end offset and the replacement. */
    private fun skippedElementOrPagebreak(bytes: ByteArray, at: Int): Pair<Int, String>? {
        // The pagebreak first: its colon breaks the plain-name scan below.
        val head = "<mbp:pagebreak".toByteArray(Charsets.ISO_8859_1)
        if (at + head.size <= bytes.size && equalsIgnoreCaseAscii(bytes, at, head)) {
            var q = at + head.size
            while (q < bytes.size && (bytes[q].toInt() and 0xFF).toChar().isWhitespaceAscii()) q++
            if (q < bytes.size && bytes[q] == '/'.code.toByte()) q++
            if (q < bytes.size && bytes[q] == '>'.code.toByte()) return (q + 1) to "\n\n"
            return null
        }
        var p = at + 1
        val name = tagNameAt(bytes, p)?.also { p = it.second }?.first ?: return null
        if (p >= bytes.size) return null
        val nameString = String(name, Charsets.ISO_8859_1).lowercase()
        return when (nameString) {
            "script", "style", "head" -> {
                // The matching close: `</name`, then to its '>'.
                val needle = "</$nameString".toByteArray(Charsets.ISO_8859_1)
                val close = findCi(bytes, p, needle) ?: return null
                var end = close + 2 + nameString.length
                while (end < bytes.size && bytes[end] != '>'.code.toByte()) end++
                (end + 1) to " "
            }
            else -> null
        }
    }

    /** The lowercased tag-name run starting at [pIn] (after any `/`),
     *  consuming it past the name. `null` when there is no name. */
    private fun tagNameAt(bytes: ByteArray, pIn: Int): Pair<ByteArray, Int>? {
        var at = pIn
        if (at < bytes.size && bytes[at] == '/'.code.toByte()) at++
        val start = at
        while (at < bytes.size && (bytes[at].toInt() and 0xFF).toChar().let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            at++
        }
        if (at == start) return null
        return bytes.copyOfRange(start, at) to at
    }

    /** The next occurrence of an ASCII needle, case-insensitively. */
    private fun findCi(haystack: ByteArray, from: Int, needle: ByteArray): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        var at = from
        while (at + needle.size <= haystack.size) {
            if (equalsIgnoreCaseAscii(haystack, at, needle)) return at
            at++
        }
        return null
    }

    private fun equalsIgnoreCaseAscii(haystack: ByteArray, at: Int, needle: ByteArray): Boolean {
        for (i in needle.indices) {
            val a = haystack[at + i].toInt() and 0xFF
            val b = needle[i].toInt() and 0xFF
            if (a != b && (a xor 0x20) != b) return false
        }
        return true
    }

    private fun Char.isWhitespaceAscii(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'

    /** Decode one UTF-8 char starting at [at]; the replacement char on
     *  invalid bytes. */
    private fun charAtUtf8(bytes: ByteArray, at: Int): Char {
        val b0 = bytes[at].toInt() and 0xFF
        return when {
            b0 < 0x80 -> b0.toChar()
            b0 and 0xE0 == 0xC0 && at + 1 < bytes.size -> decodeTwo(bytes, at)
            b0 and 0xF0 == 0xE0 && at + 2 < bytes.size -> decodeThree(bytes, at)
            b0 and 0xF8 == 0xF0 && at + 3 < bytes.size -> decodeFour(bytes, at)
            else -> '\uFFFD'
        }
    }

    private fun decodeTwo(bytes: ByteArray, at: Int): Char {
        val cp = ((bytes[at].toInt() and 0x1F) shl 6) or (bytes[at + 1].toInt() and 0x3F)
        return codePointChar(cp)
    }

    private fun decodeThree(bytes: ByteArray, at: Int): Char {
        val cp = ((bytes[at].toInt() and 0x0F) shl 12) or
            ((bytes[at + 1].toInt() and 0x3F) shl 6) or
            (bytes[at + 2].toInt() and 0x3F)
        return codePointChar(cp)
    }

    private fun decodeFour(bytes: ByteArray, at: Int): Char {
        val cp = ((bytes[at].toInt() and 0x07) shl 18) or
            ((bytes[at + 1].toInt() and 0x3F) shl 12) or
            ((bytes[at + 2].toInt() and 0x3F) shl 6) or
            (bytes[at + 3].toInt() and 0x3F)
        return codePointChar(cp)
    }

    private fun codePointChar(cp: Int): Char {
        if (cp in 0xD800..0xDFFF || cp > 0x10FFFF) return '\uFFFD'
        return String(Character.toChars(cp)).first()
    }

    /** Flattens the book's HTML into heading/paragraph/image items.
     *
     *  Mobipocket books are HTML, so the markup IS the structure: `<h1>`–
     *  `<h2>` open chapters, `<h3>`+ stay inside the current chapter as
     *  prose, and block tags separate paragraphs. Hard-wrapped source lines
     *  are rejoined into paragraphs — Gutenberg wraps at ~70 columns. What
     *  is left after the boundary tags are INLINE tags (font, em, span, a…)
     *  — they vanish, because browsers render inline markup without
     *  whitespace and replacing it with a space shredded words. */
    private fun items(from: String): List<Item> {
        val bytes = from.toByteArray(Charsets.UTF_8)
        val out = ArrayList<Item>()
        // A heading OPENING tag (`<h1 …>`) vs. its CLOSING tag (`</h1>`):
        // both match the boundary pattern, and treating the closing tag as
        // an opening one is what made every fixture come back untitled.
        var pendingHeading = false
        val paragraph = ArrayList<String>()
        var cursor = 0

        while (true) {
            val boundary = nextBoundary(bytes, cursor) ?: break
            val (start, end, kind) = boundary
            // A boundary that starts BEFORE the cursor would make the chunk
            // a reversed range — a truncated back reference in the
            // decompressed text can produce an unbalanced tag whose boundary
            // overlaps the previous one. Skip it and keep the cursor.
            if (start < cursor) continue
            val chunk = from.substring(cursor, start)
            cursor = end
            val text = sanitize(decodeEntitiesLenient(stripTags(chunk)))

            when (kind) {
                is Boundary.HeadingOpen -> {
                    // An opening heading tag ends whatever came before it;
                    // the text inside is its title.
                    flushParagraph(out, paragraph, { pendingHeading })
                    if (text.isNotEmpty()) {
                        if (kind.level <= 2) {
                            out.add(Item.Heading(text))
                        } else {
                            // h3+ nests inside the chapter: its words are prose.
                            paragraph.add(text)
                        }
                    }
                    pendingHeading = kind.level <= 2
                }
                is Boundary.Image -> {
                    // An image is a boundary even without a close tag — an
                    // alt-less image must not glue two words together.
                    flushParagraph(out, paragraph, { pendingHeading })
                    val src = attributeOf(kind.tag, "src") ?: ""
                    if (src.isNotEmpty()) {
                        val alt = attributeOf(kind.tag, "alt")?.takeIf { it.isNotEmpty() }
                        out.add(Item.Image(name = src, alt = alt))
                    }
                }
                Boundary.Block -> {
                    if (text.isNotEmpty()) {
                        if (pendingHeading) {
                            out.add(Item.Heading(text))
                            pendingHeading = false
                        } else {
                            paragraph.add(text)
                        }
                    }
                    // Every other boundary ends a block: `</p>`, `<br/>`,
                    // `</h2>`, …
                    flushParagraph(out, paragraph, { pendingHeading })
                }
            }
        }

        val tail = sanitize(decodeEntitiesLenient(stripTags(from.substring(cursor))))
        if (tail.isNotEmpty()) {
            if (pendingHeading) {
                out.add(Item.Heading(tail))
            } else {
                paragraph.add(tail)
            }
        }
        flushParagraph(out, paragraph, { pendingHeading })
        return out
    }

    private fun flushParagraph(items: MutableList<Item>, paragraph: MutableList<String>, pending: () -> Boolean) {
        if (paragraph.isEmpty()) return
        val joined = sanitize(paragraph.joinToString(" "))
        paragraph.clear()
        if (joined.isEmpty()) return
        if (pending()) {
            items.add(Item.Heading(joined))
        } else {
            items.add(Item.Paragraph(joined))
        }
    }

    private sealed class Boundary {
        object Block : Boundary()
        class HeadingOpen(val level: Int) : Boundary()
        /** An `<img …>` whose tag text rides along for the attribute reader. */
        class Image(val tag: String) : Boundary()
    }

    /** One structural boundary at or after [from]: a block tag's open or
     *  close, an h1–h6 opening (with its level), or an `<img>`. Returns the
     *  tag's start, the offset past its `>`, and the kind. */
    private fun nextBoundary(bytes: ByteArray, from: Int): Triple<Int, Int, Boundary>? {
        var at = from
        while (at < bytes.size) {
            if (bytes[at] != '<'.code.toByte()) {
                at += 1
                continue
            }
            var p = at + 1
            val closing = p < bytes.size && bytes[p] == '/'.code.toByte()
            if (closing) p += 1
            val nameStart = p
            val name = tagNameAt(bytes, p)
            if (name != null) p = name.second
            // The name must end at a non-word byte — `\b` in the reference's
            // pattern. `<param>` is not a `<p>`; `<h10>` is not an `<h1>`.
            if (p < bytes.size) {
                val b = bytes[p].toInt() and 0xFF
                if (b in '0'.code..'9'.code || b in 'a'.code..'z'.code ||
                    b in 'A'.code..'Z'.code || b == '_'.code
                ) {
                    at += 1
                    continue
                }
            }
            val kind: Boundary = when {
                name != null && !closing && String(name.first, Charsets.ISO_8859_1).equals("img", ignoreCase = true) -> {
                    val tagEnd = tagEnd(bytes, p) ?: return null
                    Boundary.Image(String(bytes, at, tagEnd + 1 - at, Charsets.UTF_8))
                }
                name != null && !closing && String(name.first, Charsets.ISO_8859_1).equals("br", ignoreCase = true) -> {
                    // `<br\s*/?>` — only whitespace, an optional slash, then
                    // '>' counts; `<br class="x">` is inline markup, not a
                    // boundary, in the reference's pattern too.
                    var q = p
                    while (q < bytes.size && (bytes[q].toInt() and 0xFF).toChar().isWhitespaceAscii()) q++
                    if (q < bytes.size && bytes[q] == '/'.code.toByte()) q++
                    if (q >= bytes.size || bytes[q] != '>'.code.toByte()) {
                        at += 1
                        continue
                    }
                    Boundary.Block
                }
                name != null && name.first.size == 2 &&
                    (name.first[0] == 'h'.code.toByte() || name.first[0] == 'H'.code.toByte()) &&
                    name.first[1] in '1'.code.toByte()..'6'.code.toByte() -> {
                    if (closing) Boundary.Block
                    else Boundary.HeadingOpen((name.first[1].toInt() and 0xFF) - '0'.code)
                }
                name != null && String(name.first, Charsets.ISO_8859_1).lowercase() in BLOCK_TAGS -> Boundary.Block
                else -> {
                    at += 1
                    continue
                }
            }
            // The tag runs to its '>' (`[^>]*>` — no '>' inside, the same
            // limitation the reference's pattern had).
            val end = when (kind) {
                is Boundary.Image -> (tagEnd(bytes, p) ?: return null) + 1
                else -> {
                    val gt = byteIndexOf(bytes, '>'.code.toByte(), p)
                    if (gt < 0) return null
                    gt + 1
                }
            }
            return Triple(at, end, kind)
        }
        return null
    }

    /** The offset of the '>' that closes the tag whose name ends at [p]. */
    private fun tagEnd(bytes: ByteArray, p: Int): Int? {
        val gt = byteIndexOf(bytes, '>'.code.toByte(), p)
        return if (gt < 0) null else gt
    }

    private val BLOCK_TAGS = setOf(
        "p", "div", "li", "tr", "blockquote", "section", "article", "dd", "dt", "pre", "figcaption",
    )

    /** Every remaining `<…>` run the walk did not name: what is left after
     *  the boundary tags are inline tags (font, em, span, a…). */
    private fun stripTags(chunk: String): String {
        val bytes = chunk.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(chunk.length)
        var at = 0
        while (at < bytes.size) {
            if (bytes[at] == '<'.code.toByte()) {
                // `<[^>]{0,400}>` — a '>' beyond 400 bytes does not close
                // the "tag"; the '<' stays as text, as in the reference.
                val limit = (at + 1 + 400).coerceAtMost(bytes.size)
                val gt = byteIndexOf(bytes, '>'.code.toByte(), at + 1)
                if (gt in (at + 1) until limit) {
                    at = gt + 1
                    continue
                }
            }
            val ch = charAtUtf8(bytes, at)
            out.append(ch)
            at += ch.toString().toByteArray(Charsets.UTF_8).size
        }
        return out.toString()
    }

    /** One attribute of a tag, entity-decoded: `name="value"`, `'value'` or
     *  a bare word. */
    private fun attributeOf(tag: String, name: String): String? {
        val lower = tag.lowercase()
        val needle = "$name="
        val at = lower.indexOf(needle)
        if (at < 0) return null
        val rest = tag.substring(at + needle.length)
        val quote = rest.firstOrNull() ?: return null
        val value = if (quote == '"' || quote == '\'') {
            val end = rest.substring(1).indexOf(quote)
            if (end < 0) return null
            rest.substring(1, end + 1)
        } else {
            rest.split(' ', '\t', '\n', '>', '"').firstOrNull() ?: return null
        }
        return decodeEntitiesLenient(value)
    }

    /** Entities decoded once — the text the chapter XHTML carries is
     *  written back escaped by the builders, so `&amp;` in the source must
     *  not become `&amp;amp;`. */
    private fun decodeEntitiesLenient(text: String): String =
        BookXml.decodeEntities(text) ?: text

    /** Gutenberg's editorial furniture, which a reader would never say:
     *  `[Illustration]`, `[Pg 42]`, footnote markers, emphasis marks, the
     *  glyph references encrypted-font files leave behind — and the control
     *  bytes an engine chokes on. Whitespace runs collapse here too. */
    internal fun sanitize(text: String): String {
        var out = stripEditorialMarkers(text)
        out = stripCidReferences(out)
        // Emphasis: keep the words, drop the marks. Every `*` goes — a run
        // of four is two matches of the reference's `\*{1,3}` either way.
        out = out.replace("*", "")
        out = stripUnderscoreEmphasis(out)
        out = out.replace("────", " ").replace("————", " ")
        out = out.replace("\u0000", "")
        return tidy(out)
    }

    /** `[Illustration]`, `[Pg 42]`, `[Footnote …]` and `[Transcriber's note
     *  …]` markers → a space. Case-insensitive. */
    private fun stripEditorialMarkers(text: String): String {
        val out = StringBuilder(text.length)
        var at = 0
        while (at < text.length) {
            if (text[at] == '[') {
                val end = text.indexOf(']', at)
                if (end >= 0 && isEditorialMarker(text.substring(at + 1, end))) {
                    out.append(' ')
                    at = end + 1
                    continue
                }
            }
            out.append(text[at])
            at++
        }
        return out.toString()
    }

    private fun isEditorialMarker(inner: String): Boolean {
        val lower = inner.trim().lowercase()
        if (lower == "illustration") return true
        if (lower.startsWith("pg ")) {
            val rest = lower.substring(3)
            return rest.isNotEmpty() && rest.all { it in '0'..'9' || it in "ivxlcdm" }
        }
        return lower.startsWith("footnote") || lower.startsWith("transcriber")
    }

    /** `(cid:123)` — the glyph references encrypted-font files leave behind. */
    private fun stripCidReferences(text: String): String {
        val out = StringBuilder(text.length)
        var at = 0
        while (at < text.length) {
            if (text[at] == '(' && text.startsWith("(cid:", at)) {
                val end = text.indexOf(')', at)
                if (end >= 0 && text.substring(at + 5, end).all { it in '0'..'9' }) {
                    at = end + 1
                    continue
                }
            }
            out.append(text[at])
            at++
        }
        return out.toString()
    }

    /** Runs of 1–3 underscores with a non-letter/non-digit on both sides
     *  are emphasis marks, not words-with-underscores (`snake_case` keeps
     *  its). A run of four or more matches nowhere — every candidate has an
     *  underscore for a neighbour — the way the reference's lookarounds
     *  behave. */
    private fun stripUnderscoreEmphasis(text: String): String {
        val out = StringBuilder(text.length)
        var at = 0
        while (at < text.length) {
            if (text[at] == '_') {
                var run = 0
                while (run < 3 && text.getOrNull(at + run) == '_') run++
                val beforeOk = at == 0 || !text[at - 1].isLetterOrDigit()
                val afterChar = text.getOrNull(at + run)
                val afterOk = afterChar == null || !afterChar.isLetterOrDigit()
                if (beforeOk && afterOk) {
                    at += run
                    continue
                }
            }
            out.append(text[at])
            at++
        }
        return out.toString()
    }

    /** Collapse the whitespace the source's own formatting carried —
     *  newlines inside a paragraph, runs of indentation — into the single
     *  spaces prose has. */
    private fun tidy(text: String): String {
        val out = StringBuilder(text.length)
        var inSpace = false
        for (c in text) {
            if (c.isWhitespace()) {
                if (!inSpace) {
                    out.append(' ')
                    inSpace = true
                }
            } else {
                out.append(c)
                inSpace = false
            }
        }
        return out.toString().trim()
    }

    // ---------------------------------------------------------------------------
    // Images: recindex / kindle:embed → the pool
    // ---------------------------------------------------------------------------

    /** Every image the text references, in first-use order, with the
     *  `<img>` tags rewritten to the pool's names.
     *
     *  `recindex` is 1-based over the resource records that follow the text
     *  records; writers disagree about the exact base — both the header's
     *  first image record and the mobiunpack convention
     *  (`firstResource + n − 1`) are tried, and whichever lands on a real
     *  image payload wins. KF8 books spell the same reference
     *  `kindle:embed:NNNN`. A reference that lands on nothing drops its
     *  image; the text never breaks. */
    private fun extractImages(
        html: String,
        pdb: PalmDatabase,
        header: MobiHeader,
        base: Int,
    ): Pair<String, List<BookImage>> {
        val firstResource = 1 + header.textRecordCount
        val pool = ArrayList<BookImage>()
        val poolByRecord = HashMap<Int, Int>()
        val bytes = html.toByteArray(Charsets.UTF_8)
        // The walk's indices are BYTE offsets; the rewrite copies byte ranges
        // directly (decoding only the small `<img …>` tags), so a 5 MB book
        // is one pass, not one re-encode per tag.
        val rewritten = ByteArrayWriter(html.length)
        var at = 0

        while (at < bytes.size) {
            // Copy the text up to the next `<img` tag.
            val tagAt = findImgTag(bytes, at)
            if (tagAt == null) {
                rewritten.write(bytes.copyOfRange(at, bytes.size))
                break
            }
            rewritten.write(bytes.copyOfRange(at, tagAt))
            // The tag runs to its '>' — or ends the document, in which case
            // it is not a tag this reader can rewrite.
            val gt = byteIndexOf(bytes, '>'.code.toByte(), tagAt)
            if (gt < 0) {
                rewritten.write(bytes.copyOfRange(tagAt, bytes.size))
                break
            }
            val tag = String(bytes, tagAt, gt + 1 - tagAt, Charsets.UTF_8)
            at = gt + 1
            val index = referencedImage(tag, pdb, header, base, firstResource, pool, poolByRecord)
                ?: continue // unresolvable: the tag is dropped
            var tagOut = "<img src=\"${imageRefName(index)}\""
            val alt = attributeOf(tag, "alt")?.takeIf { it.isNotEmpty() }
            if (alt != null) tagOut += " alt=\"${xmlEscape(alt)}\""
            tagOut += ">"
            rewritten.write(tagOut.toByteArray(Charsets.UTF_8))
        }
        return String(rewritten.toByteArray(), Charsets.UTF_8) to pool
    }

    /** The next `<img` tag at or after [from] — `img` as a whole tag name,
     *  so `<imgsrc>` is text but `<IMG src=…>` counts. */
    private fun findImgTag(bytes: ByteArray, from: Int): Int? {
        var at = from
        while (true) {
            var lt = -1
            var i = at
            while (i < bytes.size) {
                if (bytes[i] == '<'.code.toByte()) {
                    lt = i
                    break
                }
                i++
            }
            if (lt < 0) return null
            if (lt + 4 <= bytes.size && equalsIgnoreCaseAscii(bytes, lt + 1, "img".toByteArray(Charsets.ISO_8859_1))) {
                val next = bytes.getOrNull(lt + 4)?.toInt()?.and(0xFF)
                if (next == null || next == ' '.code || next == '\t'.code || next == '\n'.code ||
                    next == '\r'.code || next == '>'.code || next == '/'.code
                ) {
                    return lt
                }
            }
            at = lt + 1
        }
    }

    /** The pool index an `<img>` tag's reference resolves to, adding the
     *  record's payload to the pool when it is new. */
    private fun referencedImage(
        tag: String,
        pdb: PalmDatabase,
        header: MobiHeader,
        base: Int,
        firstResource: Int,
        pool: MutableList<BookImage>,
        poolByRecord: MutableMap<Int, Int>,
    ): Int? {
        val number = recindexOf(tag) ?: embedOf(tag) ?: return null
        // Bases to try, in order: the header's own first image record (KF8
        // books, and MOBI6 books whose writer set it), then the record after
        // the text (the mobiunpack convention), then the raw one-based guess.
        val candidates = ArrayList<Int>()
        if (header.firstImageIndex > 0) {
            candidates.add(base + header.firstImageIndex + number - 1)
        }
        candidates.add(base + firstResource + number - 1)
        candidates.add(base + firstResource + number)
        for (candidate in candidates) {
            val record = pdb.record(candidate) ?: continue
            val (mime, data) = imagePayload(record) ?: continue
            val existing = poolByRecord[candidate]
            if (existing != null) return existing
            poolByRecord[candidate] = pool.size
            pool.add(BookImage(name = imageRefName(pool.size), mime = mime, data = data.copyOf()))
            return pool.size - 1
        }
        return null
    }

    private fun recindexOf(tag: String): Int? {
        val lower = tag.lowercase()
        val at = lower.indexOf("recindex")
        if (at < 0) return null
        var rest = tag.substring(at + "recindex".length).trimStart(' ')
        if (!rest.startsWith("=")) return null
        rest = rest.substring(1).trimStart(' ')
        val value = if (rest.startsWith("\"")) {
            rest.substring(1).substringBefore('"')
        } else if (rest.startsWith("'")) {
            rest.substring(1).substringBefore('\'')
        } else {
            rest.split(' ', '\t', '\n', '>', '"').firstOrNull() ?: return null
        }
        return value.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun embedOf(tag: String): Int? {
        val lower = tag.lowercase()
        val at = lower.indexOf("kindle:embed:")
        if (at < 0) return null
        val digits = tag.substring(at + "kindle:embed:".length).takeWhile { it in '0'..'9' }
        return digits.toIntOrNull()?.takeIf { it > 0 }
    }

    /** The `img/` reference name for pool index [index] — `000000`,
     *  `000001`, in order of first reference. */
    private fun imageRefName(index: Int): String = "img/%06d".format(index)

    /** A record's bytes when they are a decodable image payload (JPEG, PNG
     *  or GIF), with the mime the reader serves them under. A record that
     *  is none of these is index or layout furniture, and writing it as
     *  `cover.jpg` would produce an undecodable file. */
    private fun imagePayload(record: ByteArray): Pair<String, ByteArray>? {
        if (record.size <= 8) return null
        val isJpeg = (record[0].toInt() and 0xFF) == 0xFF && (record[1].toInt() and 0xFF) == 0xD8
        val isPng = (record[0].toInt() and 0xFF) == 0x89 && (record[1].toInt() and 0xFF) == 0x50 &&
            (record[2].toInt() and 0xFF) == 0x4E && (record[3].toInt() and 0xFF) == 0x47
        val isGif = (record[0].toInt() and 0xFF) == 0x47 && (record[1].toInt() and 0xFF) == 0x49 &&
            (record[2].toInt() and 0xFF) == 0x46
        return when {
            isJpeg -> "image/jpeg" to record
            isPng -> "image/png" to record
            isGif -> "image/gif" to record
            else -> null
        }
    }

    /** The book's cover image record.
     *
     *  Order of preference: the EXTH 201 cover offset (the writer's own
     *  declaration), the EXTH 202 thumbnail offset (calibre writes it),
     *  then the first record that actually IS an image — the resource area
     *  holds an index, a FLIS/FCIS pair and a DCTL before the pictures, in
     *  no guaranteed order, so a magic-byte check against "first record"
     *  gives up on the index.
     *
     *  The 201/202 offsets count from the header's first image record,
     *  falling back to the record after the text on writers that left the
     *  field unset. */
    private fun coverImage(
        pdb: PalmDatabase,
        header: MobiHeader,
        base: Int,
        exth: Exth,
    ): Pair<String, ByteArray>? {
        val imageBase = if (header.firstImageIndex == 0) {
            base + 1 + header.textRecordCount
        } else {
            base + header.firstImageIndex
        }
        for (offset in listOf(exth.coverOffset, exth.thumbnailOffset)) {
            if (offset < 0) continue
            val record = pdb.record(imageBase + offset.toInt())
            if (record != null) {
                imagePayload(record)?.let { return it.first to it.second }
            }
        }
        for (index in (base + 1 + header.textRecordCount) until pdb.recordCount) {
            val record = pdb.record(index) ?: continue
            imagePayload(record)?.let { return it.first to it.second }
        }
        return null
    }

    // ---------------------------------------------------------------------------
    // Chapters
    // ---------------------------------------------------------------------------

    /** Splits the book into chapters, by the first flow that applies.
     *
     *  * **The book's own contents** comes first when the index carried two
     *    or more placeable entries: the publisher's own contents page is
     *    the authority on where chapters start and what they are called.
     *  * **KF8 rawML** is a sequence of XHTML "flows" — `<html>…</html>` —
     *    and a reference book's flows carry 165 h1s and 265 h2s. The flow
     *    boundary is the book's own structure; a flow's title is its first
     *    h1, or its `epub:type` for front matter.
     *  * **MOBI6** keeps the heading rule: chapters at level-1/2 headings,
     *    h3+ staying inside as prose. A book with no headings at all
     *    chunks into bounded spans of paragraphs. */
    private fun chaptersOf(prepared: String, isKf8: Boolean, toc: List<TocEntry>): Pair<List<Pair<String?, String>>, List<BookToc>> {
        if (toc.size >= 2) {
            val (list, rows) = tocChapters(prepared, toc)
            if (list.isNotEmpty()) return list to rows
        }
        val list = if (isKf8) {
            val flows = kf8FlowChapters(prepared)
            if (flows.isNotEmpty()) flows else headingChapters(prepared)
        } else {
            headingChapters(prepared)
        }
        val rows = list.mapIndexed { index, (title, _) ->
            BookToc(title ?: "Chapter ${index + 1}", index, 0)
        }
        return list to rows
    }

    /** Cuts chapters at the index's own byte offsets in the prepared text,
     *  labelling each with the publisher's words. Offsets that fall inside
     *  a tag are snapped forward to the next tag boundary, so a chapter
     *  never opens mid-tag; entries whose offset cannot be placed are
     *  dropped rather than guessed at. */
    private fun tocChapters(prepared: String, toc: List<TocEntry>): Pair<List<Pair<String?, String>>, List<BookToc>> {
        val bytes = prepared.toByteArray(Charsets.UTF_8)
        // Placeable offsets only, in document order, deduplicated.
        val cuts = ArrayList<Triple<String, Int, Int>>() // (label, start, depth)
        for (entry in toc) {
            val snapped = snapOffset(entry.offset, bytes) ?: continue
            if (cuts.isNotEmpty() && cuts.last().second == snapped) {
                // Two entries naming the same byte (a part and its
                // part-title): keep the FIRST label — the publisher's order
                // is the one the contents page shows.
                continue
            }
            cuts.add(Triple(entry.label, snapped, entry.depth))
        }
        if (cuts.size < 2) return emptyList<Pair<String?, String>>() to emptyList<BookToc>()

        val list = ArrayList<Pair<String?, String>>()
        val rows = ArrayList<BookToc>()
        for ((index, cut) in cuts.withIndex()) {
            val (label, start, depth) = cut
            if (list.size >= MAX_CHAPTERS) break
            val end = cuts.getOrNull(index + 1)?.second ?: bytes.size
            if (end <= start) continue
            val segment = String(bytes, start, end - start, Charsets.UTF_8)
            val (_, html) = segmentChapter(items(segment), label)
            if (html.isEmpty()) continue
            rows.add(BookToc(label, list.size, depth))
            // The publisher's label wins: it is what the contents page says.
            list.add(label to html)
        }
        return list to rows
    }

    /** Snaps a byte offset to a tag boundary at or after it. An offset
     *  inside a tag (or inside text) walks forward to the next `<` that
     *  opens an element, so the chapter's markup stays well-formed; `null`
     *  when nothing plausible follows, which drops the entry. */
    internal fun snapOffset(offset: Int, bytes: ByteArray): Int? {
        if (offset >= bytes.size) return null
        if (bytes[offset] == '<'.code.toByte()) return offset
        var cursor = offset
        while (cursor < bytes.size) {
            if (bytes[cursor] == '<'.code.toByte() && cursor + 1 < bytes.size) {
                val next = bytes[cursor + 1].toInt() and 0xFF
                // An element open (or a comment), not a stray '<' the
                // back-reference corruption leaves behind.
                if (next in 'a'.code..'z'.code || next in 'A'.code..'Z'.code ||
                    next == '/'.code || next == '!'.code
                ) {
                    return cursor
                }
            }
            cursor++
        }
        return null
    }

    /** KF8: one chapter per flow. The flow's `<html>…</html>` wrapper and
     *  its following bare content both belong to the chapter — the walk
     *  rebuilds the body XHTML, so the wrapper itself cannot leak into a
     *  chapter. The title is the chapter's own first `<h1>` text, never
     *  invented; front-matter flows without an h1 take their `epub:type`
     *  as a title. */
    private fun kf8FlowChapters(prepared: String): List<Pair<String?, String>> {
        val bytes = prepared.toByteArray(Charsets.UTF_8)
        // The flow OPEN tags; the flows must actually tile the book — a
        // single stray <html>…</html> inside one MOBI6-style document is
        // not a KF8 structure, and cutting it would throw away everything
        // around it.
        val boundaries = ArrayList<Int>()
        var at = 0
        while (true) {
            val start = findCi(bytes, at, "<html".toByteArray(Charsets.ISO_8859_1)) ?: break
            // A whole tag name: `<htmlbody>` is text.
            val after = bytes.getOrNull(start + 5)?.toInt()?.and(0xFF)
            if (after == null ||
                !(after in 'a'.code..'z'.code || after in 'A'.code..'Z'.code ||
                    after in '0'.code..'9'.code || after == '_'.code)
            ) {
                boundaries.add(start)
            }
            at = start + 5
        }
        if (boundaries.size < 2) return emptyList()

        val result = ArrayList<Pair<String?, String>>()
        for ((index, start) in boundaries.withIndex()) {
            if (result.size >= MAX_CHAPTERS) break
            val end = boundaries.getOrNull(index + 1) ?: bytes.size
            val segment = String(bytes, start, end - start, Charsets.UTF_8)
            val its = items(segment)
            if (its.isEmpty()) continue
            var (title, html) = segmentChapter(its, null)
            if (title == null) title = kf8SectionTitle(segment)
            // A titleless flow with nothing in it is not a chapter; the
            // same heading twice in a row with nothing between is the
            // writer re-stating the title — not two chapters.
            if (title == null && html.isEmpty()) continue
            if (html.isEmpty() && result.lastOrNull()?.first == title) continue
            result.add(title to html)
        }
        return result
    }

    /** A title for a front-matter flow with no `<h1>`: its own
     *  `epub:type="…"` on the section element — capitalized for the
     *  contents list. `null` when the flow declares neither. */
    private fun kf8SectionTitle(segment: String): String? {
        val lower = segment.lowercase()
        val at = lower.indexOf("epub:type=\"")
        if (at < 0) return null
        val raw = segment.substring(at + "epub:type=\"".length).substringBefore('"')
        if (raw.isEmpty() || raw.any { !(it in 'a'..'z' || it == '-') }) return null
        val readable = raw.replace('-', ' ')
        val head = readable.firstOrNull()?.uppercaseChar() ?: return null
        return head + readable.substring(1)
    }

    /** MOBI6: the heading-delimited path (KF8's fallback too, for a book
     *  whose flows did not tile). */
    private fun headingChapters(prepared: String): List<Pair<String?, String>> {
        val segments = chapterSegments(prepared)
        if (segments.size <= 1) {
            val its = items(prepared)
            if (its.isEmpty()) return wholeTextChapter(prepared)
            val chunked = chunkedChapters(its)
            if (chunked.isNotEmpty()) return chunked
            // Only headings, no prose: one chapter of the whole text.
            return wholeTextChapter(prepared)
        }

        val result = ArrayList<Pair<String?, String>>()
        for (segment in segments) {
            if (result.size >= MAX_CHAPTERS) break
            val its = items(segment)
            if (its.isEmpty()) continue
            // A titleless segment that only repeats the previous chapter's
            // title is the writer re-stating the header, not new content.
            if (its.size == 1 && its[0] is Item.Paragraph) {
                val text = (its[0] as Item.Paragraph).text
                if (result.lastOrNull()?.first == text) continue
            }
            val (title, html) = segmentChapter(its, null)
            if (title == null && html.isEmpty()) continue
            result.add(title to html)
        }
        return result
    }

    /** A book with no structure at all: one chapter of the whole text, when
     *  there is any. */
    private fun wholeTextChapter(prepared: String): List<Pair<String?, String>> {
        val whole = sanitize(decodeEntitiesLenient(stripTags(prepared)))
        return if (whole.isEmpty()) emptyList()
        else listOf(null to "<p>${xmlEscape(whole)}</p>")
    }

    /** The prepared markup cut at the level-1/2 heading tags — the same
     *  boundaries the item walk opens chapters at. */
    private fun chapterSegments(prepared: String): List<String> {
        val bytes = prepared.toByteArray(Charsets.UTF_8)
        val starts = ArrayList<Int>()
        var at = 0
        while (true) {
            val boundary = nextBoundary(bytes, at) ?: break
            at = boundary.second
            val kind = boundary.third
            if (kind is Boundary.HeadingOpen && kind.level <= 2) starts.add(boundary.first)
        }
        if (starts.size < 2) return listOf(prepared)
        val segments = ArrayList<String>()
        var cursor = 0
        for (start in starts) {
            if (start > cursor) {
                segments.add(String(bytes, cursor, start - cursor, Charsets.UTF_8))
            } else if (segments.isEmpty()) {
                segments.add("")
            }
            cursor = start
        }
        if (cursor < bytes.size) {
            segments.add(String(bytes, cursor, bytes.size - cursor, Charsets.UTF_8))
        }
        return segments
    }

    /** The heading-less fallback: bounded spans of items, the section title
     *  carried onto the continuation chapter so the contents list never
     *  shows "Chapter N" mid-section. */
    private fun chunkedChapters(its: List<Item>): List<Pair<String?, String>> {
        val result = ArrayList<Pair<String?, String>>()
        var title: String? = null
        val html = StringBuilder()
        var count = 0
        fun flush() {
            if (html.isNotEmpty()) {
                result.add(title to html.toString())
                title = null
                html.clear()
            }
        }
        for (item in its) {
            if (result.size >= MAX_CHAPTERS) break
            when (item) {
                is Item.Heading -> {
                    flush()
                    title = item.text
                }
                is Item.Paragraph -> {
                    html.append("<p>").append(xmlEscape(item.text)).append("</p>")
                    count++
                }
                is Item.Image -> pushImage(html, item.name, item.alt)
            }
            if (count >= CHAPTER_BLOCK_LIMIT) {
                // The bound: the section title carries onto the continuation.
                val carried = title
                title = null
                flush()
                title = carried
                count = 0
            }
        }
        flush()
        return result
    }

    /** One chapter's build from its items: the first heading is the title,
     *  the rest is prose (a segment opened with this heading, so anything
     *  after is prose — a malformed second h1/h2 is prose, not a chapter
     *  split). A paragraph that merely echoes the title — the writer
     *  restating the chapter label inside the section — is dropped from the
     *  prose. Images keep their places in the flow. */
    private fun segmentChapter(its: List<Item>, forcedLabel: String?): Pair<String?, String> {
        // One emit per item, in document order.
        val emits = ArrayList<Pair<String, String?>>() // (text, alt) — alt non-null marks an image
        var title: String? = null
        for (item in its) {
            when (item) {
                is Item.Heading -> {
                    if (title == null) title = item.text
                    else emits.add(item.text to null)
                }
                is Item.Paragraph -> emits.add(item.text to null)
                is Item.Image -> emits.add(item.name to (item.alt ?: ""))
            }
        }
        // The echo: the first paragraph repeating the title (or the
        // publisher's label) rides out.
        val echoAgainst = forcedLabel ?: title
        val echoAt = echoAgainst?.let { against ->
            emits.indexOfFirst { it.second == null && it.first == against }.takeIf { it >= 0 }
        }
        val html = StringBuilder()
        for ((position, emit) in emits.withIndex()) {
            if (position == echoAt) continue
            val (text, alt) = emit
            if (alt == null) {
                html.append("<p>").append(xmlEscape(text)).append("</p>")
            } else {
                pushImage(html, text, alt.takeIf { it.isNotEmpty() })
            }
        }
        return title to html.toString()
    }

    private fun pushImage(html: StringBuilder, name: String, alt: String?) {
        html.append("<img src=\"$name\"")
        alt?.let { html.append(" alt=\"${xmlEscape(it)}\"") }
        html.append(">")
    }

    // ---------------------------------------------------------------------------
    // The book's own contents: INDX / TAGX / CNCX
    // ---------------------------------------------------------------------------

    /** One entry of the book's own table of contents.
     *
     *  [offset] is a byte offset into the decompressed text stream — the
     *  format's own coordinate for "the chapter starts here", which is what
     *  a reader must cut chapters at when the markup's headings disagree
     *  with the publisher's contents page. Never invented: the label is the
     *  publisher's words from the CNCX, and the offset is the index's. */
    private class TocEntry(val label: String, val offset: Int, val depth: Int)

    /** The book's own table of contents, read from its index records.
     *
     *  The chain, in the format's own order:
     *
     *  1. **INDX** — the index record the MOBI header names at 0xF4. Its
     *     TAGX block maps tag numbers to (values, mask) pairs; the control
     *     bytes of every indexed entry say which tags it carries.
     *  2. **The index's continuation records** — the number the INDX header
     *     declares, each with its own IDXT entry table.
     *  3. **CNCX** — the records after the continuations (the header's
     *     `ncncx` says how many): bare vwi-length-prefixed strings,
     *     addressed by their byte offset within the concatenated blob.
     *
     *  An entry's tag 1 is the byte offset into the decompressed text, tag
     *  3 the CNCX offset of its label, tag 4 its nesting depth.
     *
     *  Every step is optional in the wild (writers omit the index, point
     *  the header at nothing, or ship an empty CNCX), so each reader
     *  returns empty rather than throwing — the caller falls back to the
     *  heading paths and the book still imports. */
    private fun tableOfContents(pdb: PalmDatabase, header: MobiHeader, base: Int): List<TocEntry> {
        if (header.ncxIndex == 0) return emptyList()
        val ncx = base + header.ncxIndex
        val indexRecord = pdb.record(ncx)?.takeIf {
            it.size > 4 && String(it, 0, 4, Charsets.ISO_8859_1) == "INDX"
        } ?: return emptyList()
        val (controlByteCount, tags) = tagxBlock(indexRecord) ?: return emptyList()
        val continuationCount = u32(indexRecord, 0x18).toInt()
        val ncncx = u32(indexRecord, 0x34).toInt()
        val codepage = u32(indexRecord, 0x1C)
        val strings = cncxStrings(pdb, ncx + continuationCount + 1, ncncx, codepage)

        val entries = ArrayList<TocEntry>()
        for (continuation in 1..continuationCount) {
            val record = pdb.record(ncx + continuation)?.takeIf {
                it.size > 4 && String(it, 0, 4, Charsets.ISO_8859_1) == "INDX"
            } ?: continue
            val count = u32(record, 0x18).toInt()
            val idxt = u32(record, 0x14).toInt()
            if (idxt < 4 || idxt + 4 + 2 * count > record.size) continue
            for (e in 0 until count) {
                val start = u16(record, idxt + 4 + 2 * e).toInt()
                // The last entry ends at the IDXT block; zero-fill may sit
                // between.
                val end = if (e + 1 < count) {
                    u16(record, idxt + 4 + 2 * (e + 1)).toInt()
                } else {
                    idxt
                }
                if (start >= end || end > record.size) continue
                val entry = tocEntry(record.copyOfRange(start, end), controlByteCount, tags, strings)
                if (entry != null) {
                    entries.add(entry)
                    if (entries.size >= MAX_CHAPTERS) return entries
                }
            }
        }
        return entries
    }

    /** The TAGX block inside an INDX: `TAGX`, a u32 first-entry offset, a
     *  u32 control-byte count, then 4-byte `[tag, values, mask, eof]`
     *  entries up to the first-entry offset. The `eof` entry marks the
     *  table's end. */
    private fun tagxBlock(indexRecord: ByteArray): Pair<Int, List<ByteArray>>? {
        // TAGX starts after the INDX header: the header's own length sits
        // at offset 4 (192 on every book this reader has seen), and its
        // declared TAGX offset at 180.
        val declared = u32(indexRecord, 180).toInt()
        val start = if (indexRecord.size > declared + 8 &&
            String(indexRecord, declared, 4, Charsets.ISO_8859_1) == "TAGX"
        ) {
            declared
        } else {
            var found = -1
            var i = 16
            while (i + 4 <= indexRecord.size) {
                if (String(indexRecord, i, 4, Charsets.ISO_8859_1) == "TAGX") {
                    found = i
                    break
                }
                i++
            }
            if (found < 0) return null
            found
        }
        val firstEntry = u32(indexRecord, start + 4).toInt()
        val controlByteCount = u32(indexRecord, start + 8).toInt()
        if (firstEntry < 12 || controlByteCount == 0 || controlByteCount > 64) return null
        val tags = ArrayList<ByteArray>()
        var cursor = start + 12
        while (cursor + 4 <= indexRecord.size && cursor < start + firstEntry) {
            tags.add(indexRecord.copyOfRange(cursor, cursor + 4))
            cursor += 4
        }
        if (tags.isEmpty()) return null
        return controlByteCount to tags
    }

    /** One index entry's tag values: `[length byte][ident][control bytes]`,
     *  then per TAGX tag the values its control-byte mask allows — every
     *  tag reads the same control byte, its own mask selecting its slot.
     *
     *  When the masked value fills the whole mask and the mask has more
     *  than one bit, a vwi follows giving the BYTE LENGTH of the value area
     *  (not a count) — the multi-value tags use that form. */
    internal fun entryTagValues(
        entry: ByteArray,
        controlByteCount: Int,
        tags: List<ByteArray>,
    ): List<Pair<Int, List<Long>>>? {
        if (entry.isEmpty()) return null
        val identLen = entry[0].toInt() and 0xFF
        var pos = 1 + identLen
        var controlStart = pos
        val controlEnd = controlStart + controlByteCount
        if (controlEnd > entry.size) return null
        pos = controlEnd
        val values = ArrayList<Pair<Int, List<Long>>>()
        for (tag in tags) {
            val tagNumber = tag[0].toInt() and 0xFF
            val numvals = tag[1].toInt() and 0xFF
            val mask = tag[2].toInt() and 0xFF
            val eof = tag[3].toInt() and 0xFF
            if (eof == 1) {
                // The eof entry eats one control byte per group.
                controlStart = minOf(controlStart + 1, controlEnd)
                continue
            }
            if (controlStart >= controlEnd) break
            val masked = (entry[controlStart].toInt() and 0xFF) and mask
            if (masked == 0) continue
            val tagValues = ArrayList<Long>()
            if (masked == mask && Integer.bitCount(mask) > 1) {
                val (byteLength, consumed) = decint(entry, pos) ?: break
                pos += consumed
                var read = 0
                while (read < byteLength) {
                    val (value, consumed2) = decint(entry, pos) ?: break
                    pos += consumed2
                    read += consumed2
                    tagValues.add(value)
                }
            } else {
                var count = masked
                var m = mask
                while (m and 1 == 0) {
                    m = m shr 1
                    count = count shr 1
                }
                for (i in 0 until count * numvals) {
                    val (value, consumed) = decint(entry, pos) ?: break
                    pos += consumed
                    tagValues.add(value)
                }
            }
            values.add(tagNumber to tagValues)
        }
        return values
    }

    /** One TOC entry, when the index entry carries both a position (tag 1)
     *  and a label (tag 3); the depth comes from tag 4 when it is there. */
    private fun tocEntry(
        entry: ByteArray,
        controlByteCount: Int,
        tags: List<ByteArray>,
        strings: Map<Long, String>,
    ): TocEntry? {
        val values = entryTagValues(entry, controlByteCount, tags) ?: return null
        val position = values.firstOrNull { it.first == 1 }?.second?.firstOrNull() ?: return null
        val labelOffset = values.firstOrNull { it.first == 3 }?.second?.firstOrNull() ?: return null
        val label = strings[labelOffset] ?: return null
        if (label.isEmpty()) return null
        val depth = values.firstOrNull { it.first == 4 }?.second?.firstOrNull()?.toInt() ?: 0
        return TocEntry(label, position.toInt(), depth)
    }

    /** The CNCX records' strings, keyed by their byte offset within the
     *  blob. A CNCX record has no header — it IS the strings:
     *  `[vwi length][bytes]`, back to back, from byte 0, each string keyed
     *  at its length byte (the index entries' tag-3 values count from
     *  there — the real book's first label sits at 0). Multi-record blobs
     *  address the later records 0x10000 up, the convention calibre reads. */
    internal fun cncxStrings(
        pdb: PalmDatabase,
        firstRecord: Int,
        count: Int,
        codepage: Long,
    ): Map<Long, String> {
        val strings = HashMap<Long, String>()
        var recordBase = 0L
        for (index in firstRecord until firstRecord + count) {
            val record = pdb.record(index) ?: continue
            var pos = 0
            while (pos < record.size) {
                val start = pos
                val (length, consumed) = decint(record, pos) ?: break
                pos += consumed
                if (length == 0L) continue
                val end = pos + length.toInt()
                if (end > record.size) break
                val bytes = record.copyOfRange(pos, end)
                val text = decodeText(bytes, codepage)
                if (text.isNotEmpty()) strings[recordBase + start] = text
                pos = end
            }
            recordBase += 0x10000
        }
        return strings
    }

    /** A variable-width integer, forward: seven bits per byte, most
     *  significant first, read until a byte with the top bit set — that
     *  byte's bits are part of the value (calibre's `decint`). */
    private fun decint(data: ByteArray, pos: Int): Pair<Long, Int>? {
        var value = 0L
        var consumed = 0
        while (true) {
            val byte = data.getOrNull(pos + consumed)?.toInt()?.and(0xFF) ?: return null
            value = (value shl 7) or (byte and 0x7F).toLong()
            consumed += 1
            if (byte and 0x80 != 0 || consumed > 10) return value to consumed
        }
    }

    // ---------------------------------------------------------------------------
    // Byte helpers
    // ---------------------------------------------------------------------------

    /** ByteArray has no indexOf(element, from) in the Kotlin stdlib. */
    private fun byteIndexOf(bytes: ByteArray, value: Byte, from: Int): Int {
        var i = from
        while (i < bytes.size) {
            if (bytes[i] == value) return i
            i++
        }
        return -1
    }

    /** PalmDB/MOBI structure fields are stored BIG-endian (68k-era format). */
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

    /** A growable byte buffer shared with the HUFF/CDIC reader's needs. */
    private class ByteArrayWriter(initial: Int) {
        private var buffer = ByteArray(initial.coerceAtLeast(16))
        private var length = 0

        val size: Int get() = length

        operator fun get(index: Int): Byte = buffer[index]

        fun write(b: Byte) {
            if (length == buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, 16))
            }
            buffer[length++] = b
        }

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
