package com.speechnotes.app.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Phonon's Unigram tokenizer — the `onnx-community/phonon-31m-en-ONNX`
 * tokenizer.json contract, ported for the PhononEngine (speech/). Ported
 * from PhononTokenizer.swift (speechnotes-ios), whose ids are verified
 * against the reference `tokenizers` implementation.
 *
 * Pipeline, mirroring the hub tokenizer exactly:
 *   1. the 191-step normalizer chain ([PhononNormalizerTable], generated
 *      data: quote/dash folding, accented-letter folding to ASCII,
 *      subscript → digit, symbol/emoji runs → space, whitespace collapse,
 *      terminal period, leading "▁");
 *   2. the Metaspace pre-tokenizer: remaining spaces become "▁", and the
 *      text splits into segments at every "▁" (a piece never spans a space);
 *   3. per segment, a Unigram Viterbi over the vocab's 4000 pieces; bytes
 *      no piece covers fall back to the vocab's 256 <0xXX> byte tokens.
 *
 * The audio specials (<|audio_bos|> 4, <|audio_prompt|> 5,
 * <|audio_latent|> 6) are excluded from matching on purpose: a note that
 * literally contained "<|audio_bos|>" must never inject that control token
 * into the flow model's stream — it falls back to bytes like any other
 * unknown text. The NUL-prefixed pieces (<unk>, <s>, <|c###|>) cannot match
 * normalized text at all and are dropped at load.
 *
 * The Android JVM tests pin the SAME ids as the iOS tests via the same
 * fixture file (app/src/test/resources/phonon-tokenizer-fixtures.json,
 * produced by `Tokenizer.from_file(...).encode(text,
 * add_special_tokens=False)` on the hub) — cross-platform parity by
 * construction.
 */
class PhononTokenizer(tokenizerData: ByteArray) {

    private data class Piece(val id: Int, val score: Double)

    private sealed interface CompiledStep {
        data class Replace(val pattern: Pattern, val replacement: String) : CompiledStep
        data object Strip : CompiledStep
        data class Prepend(val text: String) : CompiledStep
    }

    private val vocab = HashMap<String, Piece>(4000)
    /** Byte value → token id, 256 entries, all filled by the load guard. */
    private val byteTokens = IntArray(256) { -1 }
    private val steps = ArrayList<CompiledStep>(PhononNormalizerTable.steps.size)
    private val utf8Decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    init {
        val json = runCatching { Json.parseToJsonElement(tokenizerData.decodeToString()).jsonObject }
            .getOrElse { throw IllegalArgumentException("Phonon's tokenizer.json is not valid JSON: ${it.message}") }
        val model = json["model"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: throw IllegalArgumentException("Phonon's tokenizer.json has no model object")
        if (model["type"]?.jsonPrimitive?.content != "Unigram") {
            throw IllegalArgumentException("Phonon's tokenizer.json is not a Unigram vocab the engine can read.")
        }
        if (model["byte_fallback"]?.jsonPrimitive?.content != "true") {
            throw IllegalArgumentException("Phonon's tokenizer.json lacks byte fallback — it cannot encode off-alphabet text.")
        }
        val rawVocab = model["vocab"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: throw IllegalArgumentException("Phonon's tokenizer.json has no vocab array")

        // The id of a piece IS its index in the raw vocab list.
        for ((index, entry) in rawVocab.withIndex()) {
            val pair = runCatching { entry.jsonArray }.getOrNull() ?: continue
            if (pair.size < 2) continue
            val piece = runCatching { pair[0].jsonPrimitive.content }.getOrNull() ?: continue
            val score = runCatching { pair[1].jsonPrimitive.content.toDouble() }.getOrNull() ?: continue
            // Byte-fallback tokens (<0x00>…<0xFF>) resolve by name here; the
            // \x00-prefixed control pieces (<unk>, <s>, <|c###|>) are
            // unmatchable by construction and skipped.
            if (piece.contains('\u0000')) continue
            if (piece.startsWith("<0x") && piece.endsWith(">") && piece.length == 6) {
                val byte = piece.substring(3, 5).toIntOrNull(16)
                if (byte != null && byte in 0..255) {
                    byteTokens[byte] = index
                    continue
                }
            }
            // The audio control tokens must never be produced from user text
            // (see the type doc); treat them as absent.
            if (piece in AUDIO_SPECIAL_PIECES) continue
            vocab[piece] = Piece(index, score)
        }
        if (vocab.size <= 3000 || byteTokens.any { it == -1 }) {
            throw IllegalArgumentException("Phonon's tokenizer.json is not a Unigram vocab the engine can read.")
        }

        for (step in PhononNormalizerTable.steps) {
            when (step) {
                is PhononNormalizerStep.Replace -> steps.add(
                    // Flags-free compile: Android's Pattern REJECTS
                    // UNICODE_CHARACTER_CLASS ("Unsupported flags: 256" —
                    // hit on device). The table carries the reference
                    // whitespace set explicitly instead of leaning on \s.
                    CompiledStep.Replace(
                        Pattern.compile(step.pattern),
                        step.replacement,
                    )
                )
                PhononNormalizerStep.Strip -> steps.add(CompiledStep.Strip)
                is PhononNormalizerStep.Prepend -> steps.add(CompiledStep.Prepend(step.text))
            }
        }
    }

    private fun applyNormalizer(text: String): String {
        var result = text
        for (step in steps) {
            when (step) {
                is CompiledStep.Replace -> {
                    val matcher = step.pattern.matcher(result)
                    result = matcher.replaceAll(Matcher.quoteReplacement(step.replacement))
                }
                CompiledStep.Strip -> result = stripWhitespacesAndNewlines(result)
                is CompiledStep.Prepend ->
                    // HF's Prepend skips empty strings — this is what keeps
                    // stripped-bare text from becoming a lone "▁" piece.
                    if (result.isNotEmpty()) result = step.text + result
            }
        }
        return result
    }

    /** Text → token ids, the exact input contract of the flow graph's
     *  `input_ids` (add_special_tokens is implicit: this vocab adds none). */
    fun encode(text: String): List<Int> {
        var normalized = applyNormalizer(text)
        // HF's Prepend/Metaspace skip empty strings — text the normalizer
        // strips bare ("   ") encodes to NOTHING, not to a lone "▁" piece
        // (verified against the reference tokenizer).
        if (normalized.isEmpty()) return emptyList()
        // Metaspace pre-tokenizer: spaces → "▁" (the normalizer's final
        // Prepend already put the leading one), then split at every "▁".
        val metaspaced = StringBuilder(normalized.length)
        for (char in normalized) metaspaced.append(if (char == ' ') METASPACE else char)
        normalized = metaspaced.toString()

        val ids = ArrayList<Int>()
        val segment = StringBuilder()
        fun flush() {
            if (segment.isNotEmpty()) {
                ids.addAll(encodeSegment(segment.toString()))
                segment.setLength(0)
            }
        }
        for (char in normalized) {
            if (char == METASPACE) {
                flush()
                segment.append(METASPACE)
            } else {
                segment.append(char)
            }
        }
        flush()
        return ids
    }

    /**
     * Unigram Viterbi over one "▁"-anchored segment: a byte lattice where
     * every byte position gets the vocab pieces matching there, and byte
     * tokens only when no piece matches starting at that byte (HF Unigram's
     * lattice rule).
     */
    private fun encodeSegment(segment: String): List<Int> {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val count = bytes.size
        if (count == 0) return emptyList()

        val best = arrayOfNulls<Double>(count + 1)
        val backId = IntArray(count + 1)
        val backPrev = IntArray(count + 1)
        best[0] = 0.0

        val pieceBuffer = ByteArray(MAX_PIECE_BYTES)

        for (i in 0 until count) {
            // HF's lattice keeps nodes at every byte position (byte tokens
            // wherever no piece matches), so a position can sit unreachable
            // behind a longer piece's span — skip it; reachability always
            // advances past it via that span.
            val current = best[i] ?: continue
            val upper = minOf(i + MAX_PIECE_BYTES, count)
            var matched = false
            for (j in i + 1..upper) {
                val length = j - i
                System.arraycopy(bytes, i, pieceBuffer, 0, length)
                val piece = utf8OrNull(pieceBuffer, length) ?: continue
                val entry = vocab[piece] ?: continue
                matched = true
                val candidate = current + entry.score
                if (best[j] == null || candidate > best[j]!!) {
                    best[j] = candidate
                    backId[j] = entry.id
                    backPrev[j] = i
                }
            }
            if (!matched) {
                val byteID = byteTokens[bytes[i].toInt() and 0xFF]
                if (byteID == -1) {
                    throw IllegalStateException("Phonon's tokenizer could not encode a sentence.")
                }
                // Byte tokens carry score 0 in this vocab.
                if (best[i + 1] == null || current > best[i + 1]!!) {
                    best[i + 1] = current
                    backId[i + 1] = byteID
                    backPrev[i + 1] = i
                }
            }
        }
        if (best[count] == null) {
            throw IllegalStateException("Phonon's tokenizer could not encode a sentence.")
        }

        val ids = ArrayList<Int>()
        var cursor = count
        while (cursor > 0) {
            ids.add(backId[cursor])
            cursor = backPrev[cursor]
        }
        ids.reverse()
        return ids
    }

    /** Strict-UTF-8 decode (null on invalid bytes — String(bytes, UTF_8)
     *  semantics, not the replacement-char fallback). */
    private fun utf8OrNull(bytes: ByteArray, length: Int): String? = try {
        utf8Decoder.reset().decode(ByteBuffer.wrap(bytes, 0, length)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** Swift `trimmingCharacters(in: .whitespacesAndNewlines)` — the exact
     *  set, NOT Character.isWhitespace (which disagrees on U+00A0). */
    private fun stripWhitespacesAndNewlines(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && text[start].isSwiftWhitespace()) start++
        while (end > start && text[end - 1].isSwiftWhitespace()) end--
        return if (start == 0 && end == text.length) text else text.substring(start, end)
    }

    private fun Char.isSwiftWhitespace(): Boolean =
        this == ' ' || this in '\u0009'..'\u000D' ||
            this == '\u0085' || this == '\u00A0' ||
            this == '\u2028' || this == '\u2029'

    companion object {
        /** The audio control tokens, by their fixed ids in this vocab. */
        const val AUDIO_BOS_TOKEN_ID = 4
        const val AUDIO_PROMPT_TOKEN_ID = 5
        const val AUDIO_LATENT_TOKEN_ID = 6

        private val METASPACE: Char = '\u2581'
        private val AUDIO_SPECIAL_PIECES = setOf(
            "<|audio_bos|>", "<|audio_prompt|>", "<|audio_latent|>",
        )
        /** Longest possible piece in bytes (16 chars × 4 UTF-8 bytes worst case). */
        private const val MAX_PIECE_BYTES = 64
    }
}
