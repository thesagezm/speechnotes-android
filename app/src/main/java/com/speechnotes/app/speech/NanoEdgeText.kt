package com.speechnotes.app.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The pure nano-edge-tts text math, ported from speechnotes-linux sn-tts —
 * [nano_edge.rs] plus the kokoro-family front end it calls
 * ([kokoro.rs] `tokenize_for_kokoro_family`). Android-free so the JVM
 * tests pin it directly; the ONNX shell lives in [NanoEdgeEngine].
 *
 * The nano text side IS kokoro's: input_ids are the same unwrapped stream
 * kokoro gets (the BOS/EOS wrap the reference KModel.forward applies lives
 * inside the exported graph), and a voice code means the same speaker as
 * the Kokoro engine's. Divergences from the Linux original are recorded at
 * each site; they exist because Android's G2P is MisakiG2P (dictionary), not
 * espeak — see [tokenize].
 */
object NanoEdgeText {

    /** Style-dim: every voice bank row is 256 floats — 128 for the decoder,
     *  the rest for the prosody predictor. The text-side graph does the split. */
    const val REF_STYLE_DIM = 256

    /**
     * The frame count the creak-noise window was precomputed at in the export
     * (16384 frames at 80 Hz — 204 s of audio, far past anything the app's
     * 510-token chunk cap can produce). The count is the F0 axis's — the text
     * side emits asr at half that rate, and the decoder's mapper aligns them.
     * A text side that predicts past it has nothing to draw the jitter from;
     * erroring the chunk beats reading the window off its end.
     */
    const val MAX_FRAMES = 16_384

    /** The kokoro family's token cap (kokoro.rs MAX_TOKENS). */
    const val MAX_TOKENS = 510

    /** space_id fallback when the vocab lost its space (kokoro.rs). */
    const val DEFAULT_SPACE_ID = 16

    /**
     * Load a voice bank: a headerless plane of f32, [REF_STYLE_DIM]-wide —
     * the same published banks the kokoro set ships, so one voice code is
     * one speaker in both engines. Whole rows only, and at least one row: a
     * truncated download fails here, at load (nano_edge.rs load_voice).
     */
    fun validateVoiceBank(bytes: ByteArray, styleDim: Int = REF_STYLE_DIM): FloatArray {
        require(bytes.isNotEmpty() && bytes.size % (styleDim * 4) == 0) {
            "${bytes.size} bytes, not whole rows of $styleDim f32"
        }
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    /**
     * The full 256-wide style row, by phoneme length — the reference
     * pipeline's ref_s; the text-side graph slices the decoder's half.
     * nano_edge.rs: `adjusted = (phoneme_chars - 1).min(rows - 1)`, i.e. the
     * row index clamps into what the bank actually holds.
     */
    fun styleRowOffset(phonemeChars: Int, rows: Int): Int =
        (phonemeChars - 1).coerceAtLeast(0).coerceAtMost((rows - 1).coerceAtLeast(0))

    /**
     * The pause symbol the chunk's own ending punctuation maps to, or the
     * long pause for an unpunctuated end (kokoro.rs terminal_pause). The
     * phonemiser's IPA drops punctuation, and the pause symbols are how this
     * model was trained to END an utterance: without one the stream stops at
     * the last phoneme mid-waveform and the chunk ends with a chopped final
     * phone — the click at chunk ends.
     */
    fun terminalPauseSymbol(chunkText: String): String {
        val last = chunkText.lastOrNull { !it.isWhitespace() } ?: return ELLIPSIS
        return when (last) {
            '.', '!', '?' -> last.toString()
            ',', ';', ':' -> ","
            else -> ELLIPSIS
        }
    }

    private const val ELLIPSIS = "…"

    /** Token ids + the pre-tokenisation phoneme count (the style row keys on
     *  it) + the phonemes the vocab lacked (the engine logs them). */
    class Tokens(
        val ids: IntArray,
        val phonemeChars: Int,
        val dropped: List<Char>,
    )

    /**
     * The kokoro family's front end (kokoro.rs tokenize_for_kokoro_family):
     * IPA phonemes into the vocab's ids, unknown graphemes as spaces (never
     * deletions — deleting silently shortens the stream and the word comes
     * out wrong with nothing in the log), and the chunk's own terminal
     * punctuation re-attached as the pause symbol the model was trained to
     * end an utterance on.
     *
     * Divergence from Linux, recorded: Android's MisakiG2P passes `.;:!?`
     * through (its word tokenizer keeps them as tokens) while espeak drops
     * punctuation entirely — so the terminal pause is appended only when the
     * token stream does not already end with that exact id, else a punctuated
     * chunk would carry a doubled pause. An unpunctuated end (a run-on cut, a
     * heading) still takes the long "…" pause, the "to be continued" prosody
     * a cut boundary wants. Skipped on the one chunk size that would overflow
     * the graph's token cap with it.
     *
     * Throws when the token count leaves the graph's range — Linux's
     * "token count out of range" contract.
     */
    fun tokenize(
        phonemes: String,
        chunkText: String,
        vocab: Map<String, Int>,
        spaceId: Int,
        maxTokens: Int = MAX_TOKENS,
    ): Tokens {
        // The phonemiser echoes back what it cannot classify — hyphens and
        // apostrophes above all — and the reference tokenizer deletes those
        // upstream. Replace before counting, so the style row sees the same
        // length the vocab lookup does.
        val cleaned = phonemes.replace('-', ' ').replace('\'', ' ')
        val phonemeChars = cleaned.length

        val dropped = LinkedHashSet<Char>()
        // +1: room for the terminal pause — the chunk's own punctuation
        // re-attaches after the substitution loop (kokoro.rs does the same
        // push past the phoneme count).
        val ids = IntArray(phonemeChars + 1)
        var n = 0
        for (ch in cleaned) {
            ids[n++] = vocab[ch.toString()] ?: run {
                dropped.add(ch)
                spaceId
            }
        }
        if (n <= 1) {
            throw IllegalStateException(
                "kokoro token count out of range: $n (phoneme chars $phonemeChars)"
            )
        }
        if (n < maxTokens) {
            val id = vocab[terminalPauseSymbol(chunkText)]
            if (id != null && ids[n - 1] != id) {
                ids[n++] = id
            }
        }
        if (n > maxTokens) {
            throw IllegalStateException(
                "kokoro token count out of range: $n (phoneme chars $phonemeChars)"
            )
        }
        return Tokens(ids.copyOf(n), phonemeChars, dropped.toList())
    }
}
