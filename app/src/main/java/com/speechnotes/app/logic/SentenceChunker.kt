package com.speechnotes.app.logic

/**
 * One contiguous slice of the original text destined for a single TTS
 * synthesis request. Offsets and length are UTF-16 based so a chunk maps
 * directly onto the spoken string for playback highlighting.
 */
data class Chunk(
    val text: String,
    val offset: Int,
    val length: Int,
) {
    val endOffset: Int get() = offset + length
}

/**
 * Sentence-aware chunking for streaming TTS. Ported from SpeechLogic's
 * SentenceChunker.swift (speechnotes-ios) — boundary rules preserved
 * verbatim:
 *  - `.` `!` `?` `…` end a sentence when followed by whitespace or EOF
 *  - `。！？` (CJK) always end a sentence
 *  - newlines always end a sentence
 *  - `.` between two digits is not a boundary (3.14, v1.2)
 *  - terminating punctuation and trailing whitespace belong to the chunk
 *    they terminate, so concatenating all chunk texts reproduces the input
 */
object SentenceChunker {

    private val asciiTerminators = charArrayOf('.', '!', '?', '\u2026')
    private val cjkTerminators = charArrayOf('\u3002', '\uFF01', '\uFF1F')
    private val lineBreaks = charArrayOf('\n', '\r', '\u2028', '\u2029')

    /** The first sentence (capped) so playback can start fast. */
    fun firstChunk(text: String, maxChars: Int = 200): Chunk? {
        if (text.isEmpty() || text.none { !it.isWhitespace() }) return null
        val window = maxOf(1, maxChars)
        // Walk FROM index 0, consuming at most `window` UTF-16 units (iOS
        // passes text.startIndex). The old call passed text.length as the
        // start, so the walk never ran and the "limit" became the end of the
        // text: a long unterminated opening paragraph then came out as ONE
        // giant chunk — the 784-char, 681-token "chunk 0 failed" on device.
        val limit = utf16CappedIndex(text, 0, window)

        var body = 0
        while (body < limit && text[body].isWhitespace()) body++

        val end = sentenceEnd(text, body, limit)
        if (end != null) return chunk(text, 0, end)
        if (limit == text.length) return chunk(text, 0, text.length)
        val cut = lastWordBoundary(text, body, limit) ?: limit
        return chunk(text, 0, cut)
    }

    /**
     * All chunks in order. First element is [firstChunk]; later pieces are
     * whole sentences packed greedily up to [batchMaxChars], with single
     * oversized sentences word-split so no chunk exceeds the cap.
     *
     * @param minOpenerChars The opener's MINIMUM size, in UTF-16 units.
     *   `firstChunk` stops at the FIRST sentence that fits the cap, so a
     *   text whose first sentence is short produces a short opener no matter
     *   how large the cap is — and a short opener is exactly what runs out
     *   of audio before the second buffer arrives (the audible gap the
     *   chunk schedule exists to close). When this is > 0, whole following
     *   sentences are appended to the opener until it reaches
     *   `minOpenerChars` UTF-16 units, never past `batchMaxChars`.
     *
     *   The cap is `minOpenerChars` ITSELF, not `max(firstMaxChars,
     *   minOpenerChars)`: `firstMaxChars` bounds the FIRST SENTENCE, and
     *   folding it into the opener cap would let a large `firstMaxChars`
     *   grow the opener far past what was asked for.
     *
     *   DEFAULT OFF (0): the opener is exactly `firstChunk(in:maxChars:)`,
     *   so every existing behaviour — and the doc contract above — is
     *   unchanged. Production callers opt in and pass the same value for
     *   `firstMaxChars` and `minOpenerChars` — the opener's total size is
     *   the budget, and the first sentence is merely where it starts.
     */
    fun chunks(
        text: String,
        firstMaxChars: Int = 200,
        batchMaxChars: Int = 400,
        minOpenerChars: Int = 0,
    ): List<Chunk> {
        val maxBatch = maxOf(1, batchMaxChars)
        val wantsCompletion = minOpenerChars > 0
        // The opener's cap. `minOpenerChars` IS the opener's TOTAL size —
        // the first sentence is merely where it starts. The batch ceiling is
        // the hard cap on any chunk, so the opener never exceeds it.
        val openerCap = if (wantsCompletion) minOf(maxOf(1, minOpenerChars), maxBatch) else 0
        // The first sentence's own cap: the opener's cap when completion is
        // on, so an inconsistent caller (a `firstMaxChars` above the engine's
        // ceiling) cannot produce an oversized opener. Otherwise the caller's
        // `firstMaxChars`, unchanged.
        val firstCap = if (wantsCompletion) minOf(maxOf(1, firstMaxChars), openerCap) else firstMaxChars
        val first = firstChunk(text, firstCap) ?: return emptyList()
        val result = ArrayList<Chunk>()
        result.add(first)
        val minPieceLength = minOf(maxBatch, 4)

        var cursor = first.length

        // Opener completion — opt-in (see the parameter docs). The opener
        // grows by WHOLE following sentences, never by a cut, so it still
        // ends at a sentence boundary the engine can speak cleanly.
        if (wantsCompletion) {
            val completed = completedOpenerEnd(text, first.length, openerCap, maxBatch)
            if (completed != null) {
                result[0] = chunkUtf16(text, 0, completed)
                cursor = completed
            }
        }

        val pieces = ArrayList<Pair<Int, Int>>()

        while (cursor < text.length) {
            val end = sentenceEnd(text, cursor, text.length) ?: text.length
            for (piece in splitOversized(text, cursor, end, maxBatch)) {
                val last = pieces.lastOrNull()
                if (last != null) {
                    val lastLen = last.second - last.first
                    val pieceLen = piece.second - piece.first
                    if (pieceLen < minPieceLength && lastLen + pieceLen <= maxBatch + minPieceLength) {
                        pieces[pieces.size - 1] = last.first to piece.second
                        continue
                    }
                }
                pieces.add(piece)
            }
            cursor = end
        }

        var batchStart = -1
        var batchEnd = -1
        var batchLen = 0
        for ((start, end) in pieces) {
            val pieceLen = end - start
            if (batchLen == 0) {
                batchStart = start; batchEnd = end; batchLen = pieceLen
            } else if (batchLen + pieceLen <= maxBatch) {
                batchEnd = end; batchLen += pieceLen
            } else {
                result.add(chunkUtf16(text, batchStart, batchEnd))
                batchStart = start; batchEnd = end; batchLen = pieceLen
            }
        }
        if (batchLen > 0) result.add(chunkUtf16(text, batchStart, batchEnd))
        return result
    }

    /**
     * UTF-16 offset where speech should resume so it starts at a sentence
     * boundary instead of mid-word. 0 = restart from the beginning.
     */
    fun resumeOffset(text: String, charsDone: Int): Int {
        if (text.isEmpty() || charsDone <= 0) return 0
        return sentencePieces(text).lastOrNull { charsDone >= it.first }?.first ?: 0
    }

    /**
     * Sentence-aligned, UNPACKED pieces (one piece ≈ one sentence, oversized
     * sentences word-split). The source of truth for resume offsets and
     * read-along highlighting — chunks(for:) packs batches and must not be
     * used for either.
     */
    fun sentencePieces(text: String, maxChars: Int = 200): List<Pair<Int, Int>> {
        if (text.isEmpty() || text.none { !it.isWhitespace() }) return emptyList()
        val result = ArrayList<Pair<Int, Int>>()
        var cursor = 0
        var runningOffset = 0
        while (cursor < text.length) {
            val end = sentenceEnd(text, cursor, text.length) ?: text.length
            for ((s, e) in splitOversized(text, cursor, end, maxOf(1, maxChars))) {
                result.add(runningOffset to runningOffset + (e - s))
                runningOffset += (e - s)
            }
            cursor = end
        }
        return result
    }

    // MARK: - Internals (all offsets UTF-16)

    /** Exclusive UTF-16 end of the sentence starting at [start]; nil if none. */
    private fun sentenceEnd(text: String, start: Int, limit: Int): Int? {
        var i = start
        while (i < limit) {
            val c = text[i]
            if (c in asciiTerminators || c in cjkTerminators) {
                var j = i
                var sawCJK = false
                while (j < limit) {
                    val t = text[j]
                    if (t in asciiTerminators) j++
                    else if (t in cjkTerminators) { sawCJK = true; j++ }
                    else break
                }
                var isBoundary = sawCJK || j == limit || j < text.length && text[j].isWhitespace()
                // "3.14" / "v1.2": a period between two digits is not a boundary.
                if (isBoundary && c == '.' && i + 1 == j && i > 0 &&
                    j < text.length && text[i - 1].isDigit() && text[j].isDigit()
                ) {
                    isBoundary = false
                }
                if (isBoundary) {
                    var k = j
                    while (k < limit && k < text.length && text[k].isWhitespace()) k++
                    return k
                }
                i = j
                continue
            }
            if (c in lineBreaks) {
                var k = i
                while (k < limit && k < text.length && text[k].isWhitespace()) k++
                return k
            }
            i++
        }
        return null
    }

    /**
     * Extends an opener with WHOLE following sentences, up to [cap] UTF-16
     * units in total, and returns where the next chunk must start.
     *
     * Only complete pieces are appended: a sentence that does not fit in the
     * remaining budget is left whole for the next chunk, because a cut
     * opener would end on a clipped word.
     *
     * Returns null when nothing could be appended — the opener stays as it
     * was.
     */
    private fun completedOpenerEnd(text: String, firstLength: Int, cap: Int, maxBatchLength: Int): Int? {
        if (cap <= firstLength) return null
        if (firstLength >= text.length) return null
        var cursor = firstLength
        var used = firstLength
        while (cursor < text.length) {
            val end = sentenceEnd(text, cursor, text.length) ?: text.length
            for (piece in splitOversized(text, cursor, end, maxBatchLength)) {
                val pieceLength = piece.second - piece.first
                if (used + pieceLength > cap) return cursor
                used += pieceLength
                cursor = piece.second
            }
        }
        return cursor
    }

    /** End of the last whitespace run at or before [limit], or null. */
    private fun lastWordBoundary(text: String, start: Int, limit: Int): Int? {
        var cut: Int? = null
        var i = start
        while (i < limit) {
            if (text[i].isWhitespace()) {
                var j = i
                while (j < limit && text[j].isWhitespace()) j++
                cut = j
                i = j
            } else {
                i++
            }
        }
        return cut
    }

    private fun splitOversized(text: String, start: Int, end: Int, maxUtf16: Int): List<Pair<Int, Int>> {
        if (end - start <= maxUtf16) return listOf(start to end)
        val pieces = ArrayList<Pair<Int, Int>>()
        var pieceStart = start
        while (pieceStart < end) {
            val limit = utf16CappedIndex(text, pieceStart, maxUtf16)
            val cap = minOf(limit, end)
            if (cap >= end) {
                pieces.add(pieceStart to end)
                break
            }
            val wordCut = lastWordBoundary(text, pieceStart, cap)
            val cut = if (wordCut != null && wordCut > pieceStart) wordCut else cap
            pieces.add(pieceStart to cut)
            pieceStart = cut
        }
        return pieces
    }

    /** Index at most [maxUtf16Length] UTF-16 units after [start]. */
    private fun utf16CappedIndex(text: String, start: Int, maxUtf16Length: Int): Int {
        var index = start
        var used = 0
        while (index < text.length) {
            val cost = Character.charCount(codePointAt(text, index))
            if (used + cost > maxUtf16Length) break
            used += cost
            index += cost
        }
        return index
    }

    /** The code point starting at [utf16Index] (handles surrogate pairs). */
    private fun codePointAt(text: String, utf16Index: Int): Int {
        if (utf16Index >= text.length) return 0
        val high = text[utf16Index]
        if (high.isHighSurrogate() && utf16Index + 1 < text.length && text[utf16Index + 1].isLowSurrogate()) {
            return Character.toCodePoint(high, text[utf16Index + 1])
        }
        return high.code
    }

    private fun chunk(text: String, from: Int, to: Int): Chunk {
        val slice = text.substring(from, to)
        return Chunk(slice, from, slice.length)
    }

    /** Chunk defined by UTF-16 offsets (no surrogate risk: offsets are exact). */
    private fun chunkUtf16(text: String, from: Int, to: Int): Chunk {
        val slice = text.substring(from, to)
        return Chunk(slice, from, slice.length)
    }
}
