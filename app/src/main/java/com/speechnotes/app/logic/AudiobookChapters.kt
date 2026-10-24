package com.speechnotes.app.logic

/**
 * Chapter-list resolution for audiobook containers built on MPEG-4 or MP3
 * (M4B/M4A/MP4 and MP3-with-chapters). Ported from AudiobookChapters.swift
 * (speechnotes-ios @ the v1.7.x parser), which was rebuilt over several
 * device rounds against real M4Bs (Nero/mp4chaps, ffmpeg movenc, m4b-tool):
 *
 *  * `moov/chpl` — BOTH layouts in the wild: Nero's 4-byte BE count and
 *    ffmpeg's 1-byte count after 4 zero bytes. Deciding by "which parse
 *    returned non-empty" mis-parses (a 4-byte read of an ffmpeg count reads
 *    0 → falls to the 1-byte variant and lands mid-entry). We parse both
 *    candidates and keep the one that VALIDATES: monotonic starts, first
 *    near 0, last start within the book's duration.
 *  * a real chapter TRACK — a text `trak` (handler `text`/`sbtl`/`subt`)
 *    whose sample table carries one sample per chapter; this is what
 *    m4b-tool and ffmpeg `-map_metadata` write and a chpl-only parser
 *    misses entirely ("Full audiobook" as the only chapter).
 *  * ID3v2 `CHAP` frames (MP3), with v2.3 and v2.4 extended headers.
 *
 * Validation (D1/D3): a candidate wins only when it parses to a plausible
 * chapter list — starts strictly usable, first start ≈ 0, every start
 * inside the file's duration (±2× tolerance the iOS parser allows for
 * trailing metadata drift). The unit divisor for `chpl` (100-ns vs ms) is
 * chosen against the validated max-start, not guessed (D2); the movie
 * timescale read from `mvhd` anchors the chapter-track math (spec-driven).
 *
 * The parser reads only what it needs: boxes are walked by length prefix,
 * so a 900 MB audiobook is never loaded — the caller hands it a HEAD slice.
 * A head slice can clip a chapter track whose chunk offsets point past it;
 * the caller grows the slice when a validated result matters (the ViewModel
 * passes up to 64 MB, still never the whole file).
 */
object AudiobookChapters {

    data class AudioChapter(
        val title: String,
        val startSeconds: Double,
        val endSeconds: Double,
    )

    /** Chapters from an MP4-family container. [data] may be a HEAD slice. */
    fun chaptersFromMP4(data: ByteArray, totalSeconds: Double): List<AudioChapter> {
        var found: List<AudioChapter> = emptyList()
        forEachBox(data, 0 until data.size) { type, payload ->
            if (type == "moov") found = chaptersFromMoov(data, payload, totalSeconds) ?: emptyList()
        }
        val normalized = normalize(found, totalSeconds)
        return if (normalized.isNullOrEmpty()) emptyList() else normalized
    }

    /** Chapters from ID3v2 `CHAP` frames (MP3). */
    fun chaptersFromID3(data: ByteArray): List<AudioChapter> {
        if (data.size <= 10) return emptyList()
        val bytes = if (data.size > 4 * 1024 * 1024) data.copyOf(4 * 1024 * 1024) else data
        if (bytes[0] != 0x49.toByte() || bytes[1] != 0x44.toByte() || bytes[2] != 0x33.toByte()) return emptyList()
        val major = bytes[3].toInt()
        if (major != 3 && major != 4) return emptyList()
        val size = syncSafeInt(bytes[6], bytes[7], bytes[8], bytes[9])
        val tagEnd = minOf(bytes.size, 10 + size)
        var cursor = 10
        // An extended header, when the flag says there is one, must be skipped
        // rather than misread as the first frame. v2.4's size is sync-safe and
        // EXCLUDES its own four bytes; v2.3's is a plain 32-bit size that
        // INCLUDES them. Both encodings exist in the wild.
        if ((bytes[5].toInt() and 0x40) != 0 && cursor + 4 <= tagEnd) {
            cursor += if (major == 4) {
                4 + syncSafeInt(bytes[cursor], bytes[cursor + 1], bytes[cursor + 2], bytes[cursor + 3])
            } else {
                be32(bytes, cursor).toInt()
            }
        }

        val chapters = ArrayList<AudioChapter>()
        while (cursor + 10 <= tagEnd) {
            val id = String(bytes, cursor, 4, Charsets.ISO_8859_1)
            if (id == "\u0000\u0000\u0000\u0000" || id.isEmpty()) break
            val frameSize = if (major == 4) {
                syncSafeInt(bytes[cursor + 4], bytes[cursor + 5], bytes[cursor + 6], bytes[cursor + 7])
            } else {
                (bytes[cursor + 4].toInt() and 0xFF) shl 24 or
                    (bytes[cursor + 5].toInt() and 0xFF) shl 16 or
                    (bytes[cursor + 6].toInt() and 0xFF) shl 8 or
                    (bytes[cursor + 7].toInt() and 0xFF)
            }
            val payloadStart = cursor + 10
            val payloadEnd = minOf(tagEnd, payloadStart + frameSize)
            if (payloadEnd <= payloadStart) break
            if (id == "CHAP") {
                parseChapFrame(bytes.copyOfRange(payloadStart, payloadEnd))?.let { chapters.add(it) }
            }
            cursor = payloadEnd
        }
        return normalize(chapters, null) ?: chapters
    }

    // MARK: - MP4 walking

    private inline fun forEachBox(data: ByteArray, range: IntRange, body: (String, IntRange) -> Unit) {
        var cursor = range.first
        while (cursor + 8 <= range.last + 1) {
            val length = be32(data, cursor).toLong()
            val type = fourCC(data, cursor + 4)
            var headerSize = 8
            var boxLength = length
            if (length == 1L) {
                if (cursor + 16 > range.last + 1) return
                boxLength = be64(data, cursor + 8)
                headerSize = 16
            } else if (length == 0L) {
                boxLength = (range.last + 1 - cursor).toLong()
            }
            if (boxLength < headerSize || cursor + boxLength > range.last + 1) return
            body(type, (cursor + headerSize) until (cursor + boxLength.toInt()))
            cursor += boxLength.toInt()
        }
    }

    private fun childBox(type: String, data: ByteArray, range: IntRange): IntRange? {
        var found: IntRange? = null
        forEachBox(data, range) { boxType, payload ->
            if (boxType == type && found == null) found = payload
        }
        return found
    }

    private fun chaptersFromMoov(data: ByteArray, range: IntRange, totalSeconds: Double): List<AudioChapter>? {
        // `chpl` is the simple, widely written form — prefer it when present.
        // It lives UNDER `udta` (moov/udta/chpl) in every real file; a few
        // writers place it directly in moov, so both levels are scanned.
        var chplPayload: IntRange? = null
        var mvhdPayload: IntRange? = null
        val traks = ArrayList<IntRange>()
        forEachBox(data, range) { type, payload ->
            when (type) {
                "chpl" -> chplPayload = payload
                "udta" -> forEachBox(data, payload) { innerType, inner ->
                    if (innerType == "chpl") chplPayload = inner
                }
                "mvhd" -> mvhdPayload = payload
                "trak" -> traks.add(payload)
            }
        }
        if (chplPayload != null) {
            val chapters = parseChpl(data.copyOfRange(chplPayload!!.first, chplPayload!!.last + 1), totalSeconds)
            if (chapters.isNotEmpty()) return chapters
        }
        // No `chpl` (or it failed validation): try a real chapter track — a
        // `trak` whose handler is `text` (or `sbtl`/`subt`), whose sample
        // table then gives one chapter per sample. This is what m4b-tool and
        // ffmpeg write.
        val timescale = mvhdPayload?.let { parseMvhdTimescale(data, it) } ?: 0
        if (timescale > 0) {
            for (trak in traks) {
                val chapters = chaptersFromTrak(data, trak, timescale, totalSeconds)
                if (!chapters.isNullOrEmpty()) return chapters
            }
        }
        return null
    }

    // MARK: - mvhd / mdhd

    /**
     * `mvhd`/`mdhd` payload: version(1) flags(3), then creation(4/8),
     * modification(4/8), timescale(4), duration(4/8). Version 0 uses 32-bit
     * fields, version 1 64-bit. Timescale anchors chpl's unit divisor and
     * the chapter-track time math (D2: spec-driven, not guessed).
     */
    private fun parseMvhdTimescale(data: ByteArray, range: IntRange): Int {
        val bytes = range
        if (bytes.first + 4 > bytes.last + 1) return 0
        val version = data[bytes.first].toInt() and 0xFF
        val timescaleOffset = if (version == 0) 12 else if (version == 1) 20 else return 0
        if (bytes.first + timescaleOffset + 4 > bytes.last + 1) return 0
        return be32(data, bytes.first + timescaleOffset).toInt()
    }

    // MARK: - Chapter trak

    /**
     * A text chapter track: `trak/mdia` → `hdlr` (handler `text`/`sbtl`/
     * `subt`) + `minf/stbl` → `stts` (sample start times), `stsc`/`stco`/
     * `stsz` (sample placement), `stsd/text` (titles read from the chunk
     * offsets). The walk is BOUNDED by the head slice — a chunk offset
     * pointing past it drops that sample's title but keeps its position.
     */
    private fun chaptersFromTrak(
        data: ByteArray,
        range: IntRange,
        movieTimescale: Int,
        totalSeconds: Double,
    ): List<AudioChapter>? {
        val mdia = childBox("mdia", data, range) ?: return null
        // Handler must be a text/subtitle handler — the audio track's is
        // `soun` and a chapter track riding an audio sample table would be
        // misread as chapters-per-frame.
        val hdlr = childBox("hdlr", data, mdia) ?: return null
        val handler = handlerType(data, hdlr)
        if (handler != "text" && handler != "sbtl" && handler != "subt") return null
        val minf = childBox("minf", data, mdia) ?: return null
        val stbl = childBox("stbl", data, minf) ?: return null

        // Media timescale (mdhd) — durations are in IT; the movie timescale
        // only anchors chpl, not this track.
        var mediaTimescale = movieTimescale
        val mdhd = childBox("mdhd", data, mdia)
        if (mdhd != null) {
            val parsed = parseMvhdTimescale(data, mdhd)
            if (parsed > 0) mediaTimescale = parsed
        }

        val stts = childBox("stts", data, stbl) ?: return null
        val rawStarts = parseStts(data, stts, mediaTimescale)
        if (rawStarts.isEmpty()) return null

        val stsz = childBox("stsz", data, stbl)
        val sampleSizes = if (stsz != null) parseStsz(data, stsz) else emptyList()

        val stco = childBox("stco", data, stbl)
        val chunkOffsets = if (stco != null) parseStco(data, stco) else emptyList()
        if (chunkOffsets.isEmpty()) return null

        val stsc = childBox("stsc", data, stbl)
        val samplesPerChunk = if (stsc != null) parseStsc(data, stsc, sampleSizes.size) else emptyList()

        // Build each sample's (offset, size) by walking ALL stsc runs (D4 —
        // real chapter tracks carry multi-run tables; first-run-only lands
        // titles on the wrong samples). A run is (firstChunk, samplesPerChunk)
        // and holds until the next run's firstChunk.
        var starts = ArrayList<Double>()
        var extents = ArrayList<Pair<Int, Int>>() // (chunkOffset, sampleSize)
        var sampleIndex = 0
        var runIndex = 0
        outer@ for ((chunkOrdinal, chunkOffset) in chunkOffsets.withIndex()) {
            while (runIndex + 1 < samplesPerChunk.size &&
                samplesPerChunk[runIndex + 1].first <= chunkOrdinal + 1
            ) runIndex++
            val perChunk = samplesPerChunk.getOrNull(runIndex)?.second ?: 1
            for (i in 0 until maxOf(1, perChunk)) {
                if (sampleIndex >= rawStarts.size) break@outer
                val size = if (sampleIndex < sampleSizes.size) sampleSizes[sampleIndex] else 0
                starts.add(rawStarts[sampleIndex])
                extents.add(chunkOffset to size)
                sampleIndex++
            }
        }

        // Titles: each sample is a QuickTime text payload — 16-bit BE length,
        // then the string, sometimes behind an `encd` atom.
        val chapters = ArrayList<AudioChapter>(starts.size)
        for (i in starts.indices) {
            val (offset, size) = extents[i]
            val title = if (size > 0 && offset >= 0 && offset + size <= data.size) {
                parseTextSample(data.copyOfRange(offset, offset + size))
            } else ""
            chapters.add(AudioChapter(title, starts[i], 0.0))
        }
        val normalized = normalize(chapters, totalSeconds)
        return normalized
    }

    /** `hdlr` payload: version+flags(4), predefined(4), handler type(4). */
    private fun handlerType(data: ByteArray, range: IntRange): String? {
        if (range.first + 12 > range.last + 1) return null
        return fourCC(data, range.first + 8)
    }

    /** `stts` payload: version+flags(4), entryCount(4), then (count, delta) pairs → CUMULATIVE start seconds. */
    private fun parseStts(data: ByteArray, range: IntRange, timescale: Int): List<Double> {
        if (range.last + 1 - range.first < 8 || timescale <= 0) return emptyList()
        val entries = be32(data, range.first + 4).toInt()
        val starts = ArrayList<Double>()
        var ticks = 0.0
        var cursor = range.first + 8
        for (i in 0 until entries) {
            if (cursor + 8 > range.last + 1) break
            val count = be32(data, cursor).toInt()
            val delta = be32(data, cursor + 4).toInt()
            cursor += 8
            for (j in 0 until maxOf(1, count)) {
                starts.add(ticks / timescale)
                ticks += delta.toDouble()
            }
        }
        return starts
    }

    /** `stsz` payload: version+flags(4), sampleSize(4), sampleCount(4), then per-sample sizes when sampleSize == 0. */
    private fun parseStsz(data: ByteArray, range: IntRange): List<Int> {
        if (range.last + 1 - range.first < 12) return emptyList()
        val uniform = be32(data, range.first + 4).toInt()
        val count = be32(data, range.first + 8).toInt()
        if (uniform > 0) return List(count.coerceAtMost(100_000)) { uniform }
        val sizes = ArrayList<Int>(count.coerceAtMost(100_000))
        var cursor = range.first + 12
        for (i in 0 until count) {
            if (cursor + 4 > range.last + 1) break
            sizes.add(be32(data, cursor).toInt())
            cursor += 4
        }
        return sizes
    }

    /** `stco` payload: version+flags(4), entryCount(4), then 32-bit offsets. */
    private fun parseStco(data: ByteArray, range: IntRange): List<Int> {
        if (range.last + 1 - range.first < 8) return emptyList()
        val count = be32(data, range.first + 4).toInt()
        val offsets = ArrayList<Int>(count.coerceAtMost(100_000))
        var cursor = range.first + 8
        for (i in 0 until count) {
            if (cursor + 4 > range.last + 1) break
            offsets.add(be32(data, cursor).toInt())
            cursor += 4
        }
        return offsets
    }

    /**
     * `stsc` payload: version+flags(4), entryCount(4), then (firstChunk,
     * samplesPerChunk, sampleDescriptionIndex) triples — kept as RUNS, not
     * collapsed to the first one (D4).
     */
    private fun parseStsc(data: ByteArray, range: IntRange, totalSamples: Int): List<Pair<Int, Int>> {
        if (range.last + 1 - range.first < 8) return emptyList()
        val entries = be32(data, range.first + 4).toInt()
        val runs = ArrayList<Pair<Int, Int>>(entries.coerceAtMost(10_000))
        var cursor = range.first + 8
        for (i in 0 until entries) {
            if (cursor + 12 > range.last + 1) break
            val firstChunk = be32(data, cursor).toInt()
            val samplesPerChunk = be32(data, cursor + 4).toInt()
            cursor += 12
            if (firstChunk > 0 && samplesPerChunk > 0) runs.add(firstChunk to samplesPerChunk)
        }
        return runs
    }

    /**
     * A QuickTime text sample: 16-bit BE length prefix, then UTF-8/UTF-16 or
     * Pascal bytes. Tolerates the `encd` atom wrapper some encoders add.
     */
    private fun parseTextSample(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        var cursor = 0
        if (bytes.size >= 8) {
            val atomSize = be32(bytes, 0).toInt()
            val atomType = fourCC(bytes, 4)
            if (atomSize > 8 && atomSize <= bytes.size && atomType == "encd") cursor = atomSize
        }
        if (cursor + 2 > bytes.size) return ""
        // 16-bit BE length — a 32-bit read here swallows the first two string
        // bytes and every title comes out empty.
        val length = ((bytes[cursor].toInt() and 0xFF) shl 8) or (bytes[cursor + 1].toInt() and 0xFF)
        cursor += 2
        val end = minOf(bytes.size, cursor + length)
        if (end <= cursor) return ""
        val payload = bytes.copyOfRange(cursor, end)
        return decodeStringLenient(payload)
    }

    /** UTF-8 first, UTF-16 fallback (chapter tracks are usually plain UTF-8). */
    private fun decodeStringLenient(payload: ByteArray): String {
        val utf8 = runCatching { String(payload, Charsets.UTF_8) }.getOrNull()
        if (!utf8.isNullOrEmpty() && !utf8.contains('\uFFFD')) return utf8.trim()
        val utf16 = runCatching { String(payload, Charsets.UTF_16) }.getOrNull()
        if (!utf16.isNullOrEmpty()) return utf16.trim()
        return String(payload, Charsets.ISO_8859_1).trim()
    }

    // MARK: - chpl

    /**
     * `chpl` — BOTH layouts in the wild, validated against real files
     * (the user's own M4B and an ffmpeg-muxed sample, cross-checked against
     * ffprobe):
     *
     *   * Nero / mp4chaps: version(1) flags(3) chapterCount(4, BE) —
     *     entries from offset 8.
     *   * ffmpeg movenc (m4b-tool & friends): version(1) flags(3) ZEROS(4)
     *     chapterCount(1 BYTE at offset 8) — entries from offset 9.
     *
     * Entries are startTime(8, big-endian) nameLength(1) name(UTF-8). Both
     * probed files store 100-NANOSECOND units; the divisor is chosen against
     * the VALIDATED reading (D1/D2): with a known duration, pick the variant
     * and unit that is plausible (within the book's length) and CLOSEST to
     * it; a 400 s prologue no longer flips the choice because validation
     * compares all starts, not just the max.
     */
    private fun parseChpl(bytes: ByteArray, totalSeconds: Double): List<AudioChapter> {
        fun entries(count: Int, startCursor: Int): Pair<List<Long>, List<String>>? {
            val starts = ArrayList<Long>()
            val titles = ArrayList<String>()
            var cursor = startCursor
            for (i in 0 until count) {
                if (cursor + 9 > bytes.size) break
                val start = be64(bytes, cursor)
                cursor += 8
                val titleLength = bytes[cursor].toInt() and 0xFF
                cursor++
                if (cursor + titleLength > bytes.size) break
                val title = String(bytes, cursor, titleLength, Charsets.UTF_8).trim()
                cursor += titleLength
                starts.add(start)
                titles.add(title)
            }
            return if (starts.isEmpty()) null else starts to titles
        }

        data class Candidate(val starts: List<Long>, val titles: List<String>, val divisor: Double)

        val candidates = ArrayList<Candidate>()
        // Variant A — the 4-byte Nero count, 100-ns units (spec).
        if (bytes.size >= 8) {
            val count = be32(bytes, 4).toInt()
            if (count > 0 && count < 10_000) {
                entries(count, 8)?.let { (starts, titles) ->
                    candidates.add(Candidate(starts, titles, 10_000_000.0))
                }
            }
        }
        // Variant A-ms — the same 4-byte count but millisecond units (some
        // writers); only considered when the spec reading fails validation.
        if (bytes.size >= 8) {
            val count = be32(bytes, 4).toInt()
            if (count > 0 && count < 10_000) {
                entries(count, 8)?.let { (starts, titles) ->
                    candidates.add(Candidate(starts, titles, 1000.0))
                }
            }
        }
        // Variant B — ffmpeg's 1-byte count after 4 zero bytes (only when A
        // found nothing: a 4-byte BE read of `00 00 00 NN` IS NN, so the
        // variants agree on the count when B's reserved field is zeros; the
        // entry offset still differs by 1, so both entry layouts are tried
        // and validation picks).
        if (bytes.size >= 9) {
            val count = bytes[8].toInt() and 0xFF
            if (count > 0 && count < 255) {
                entries(count, 9)?.let { (starts, titles) ->
                    candidates.add(Candidate(starts, titles, 10_000_000.0))
                    candidates.add(Candidate(starts, titles, 1000.0))
                }
            }
        }

        // D1/D2: VALIDATE, then rank. A valid candidate has usable starts,
        // every start inside the book's duration, and — the load-bearing
        // check — its LAST start must actually approach the book's length:
        // a ms-list read as 100-ns collapses everything into the first
        // second, which is exactly the mis-parse this validation exists to
        // reject. Among valid candidates the reading whose max start lands
        // CLOSEST to the duration wins (the iOS tiebreak, and physically
        // meaningful: real chapters span the book).
        fun valid(c: Candidate): Boolean {
            if (c.starts.isEmpty()) return false
            val maxStart = c.starts.max()
            val seconds = c.starts.map { it / c.divisor }
            if (seconds.any { it < 0 }) return false
            if (totalSeconds <= 0) return seconds.all { it < 86_400 * 2 } // no duration: only sanity
            if (maxStart / c.divisor > totalSeconds * 2) return false
            // The chapter list must SPAN the book: with >1 chapter the last
            // start is well past the first, and single-chapter books still
            // cover a real fraction. Reject collapsed readings.
            if (maxStart / c.divisor < totalSeconds * 0.1 && c.starts.size > 1) return false
            if (c.starts.size == 1 && maxStart / c.divisor < totalSeconds * 0.02) return false
            // Monotonic non-decreasing (sorted-with-duplicates ok).
            for (i in 1 until seconds.size) {
                if (seconds[i] < seconds[i - 1]) return false
            }
            return true
        }

        val chosen = candidates.filter { valid(it) }.minByOrNull { c ->
            if (totalSeconds > 0) kotlin.math.abs(c.starts.max() / c.divisor - totalSeconds) else 0.0
        }
            ?: candidates.firstOrNull() // last resort: best-effort Nero reading
            ?: return emptyList()
        return chosen.starts.mapIndexed { index, raw ->
            AudioChapter(
                title = chosen.titles[index],
                startSeconds = raw / chosen.divisor,
                endSeconds = 0.0,
            )
        }
    }

    // MARK: - ID3 CHAP

    /** CHAP payload: element id (NUL-terminated), start(4), end(4), offsets(8), then sub-frames (TIT2 = title). */
    private fun parseChapFrame(bytes: ByteArray): AudioChapter? {
        var cursor = 0
        while (cursor < bytes.size && bytes[cursor] != 0.toByte()) cursor++
        if (cursor >= bytes.size) return null
        cursor++ // NUL
        if (cursor + 16 > bytes.size) return null
        val startMS = be32(bytes, cursor)
        val endMS = be32(bytes, cursor + 4)
        cursor += 16
        var title = ""
        while (cursor + 10 <= bytes.size) {
            val id = String(bytes, cursor, 4, Charsets.ISO_8859_1)
            val size = (bytes[cursor + 4].toInt() and 0xFF) shl 24 or
                (bytes[cursor + 5].toInt() and 0xFF) shl 16 or
                (bytes[cursor + 6].toInt() and 0xFF) shl 8 or
                (bytes[cursor + 7].toInt() and 0xFF)
            val payloadStart = cursor + 10
            val payloadEnd = minOf(bytes.size, payloadStart + size)
            if (payloadEnd <= payloadStart) break
            if (id == "TIT2") title = decodeTextFrame(bytes.copyOfRange(payloadStart, payloadEnd))
            cursor = payloadEnd
        }
        return AudioChapter(title.trim(), startMS / 1000.0, endMS / 1000.0)
    }

    private fun decodeTextFrame(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val encodingByte = bytes[0].toInt() and 0xFF
        val payload = bytes.copyOfRange(1, bytes.size)
        return when (encodingByte) {
            0 -> String(payload, Charsets.ISO_8859_1)
            1 -> String(payload, Charsets.UTF_16)
            2 -> String(payload, Charsets.UTF_16BE)
            else -> String(payload, Charsets.UTF_8)
        }
    }

    // MARK: - Shared

    /**
     * Fills in missing end times (each chapter ends where the next starts),
     * gives untitled chapters a positional name, and keeps only entries with
     * a real start. Returns null when there is nothing usable.
     */
    private fun normalize(chapters: List<AudioChapter>, totalSeconds: Double?): List<AudioChapter>? {
        val usable = chapters.filter { it.startSeconds >= 0 }.sortedBy { it.startSeconds }
        if (usable.isEmpty()) return null
        val out = ArrayList<AudioChapter>(usable.size)
        for ((index, chapter) in usable.withIndex()) {
            var fixed = chapter
            if (index + 1 < usable.size) {
                if (fixed.endSeconds <= fixed.startSeconds) fixed = fixed.copy(endSeconds = usable[index + 1].startSeconds)
            } else if (totalSeconds != null && totalSeconds > fixed.startSeconds) {
                if (fixed.endSeconds <= fixed.startSeconds) fixed = fixed.copy(endSeconds = totalSeconds)
            }
            if (fixed.endSeconds <= fixed.startSeconds) fixed = fixed.copy(endSeconds = fixed.startSeconds + 1)
            if (fixed.title.isEmpty()) fixed = fixed.copy(title = "Chapter ${index + 1}")
            out.add(fixed)
        }
        return out
    }

    // MARK: - Byte helpers

    private fun be32(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }

    private fun be64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }

    private fun fourCC(data: ByteArray, offset: Int): String =
        String(data, offset, 4, Charsets.ISO_8859_1)

    private fun syncSafeInt(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int =
        ((b0.toInt() and 0x7F) shl 21) or ((b1.toInt() and 0x7F) shl 14) or
            ((b2.toInt() and 0x7F) shl 7) or (b3.toInt() and 0x7F)
}
