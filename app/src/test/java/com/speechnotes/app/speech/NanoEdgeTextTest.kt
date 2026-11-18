package com.speechnotes.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins for the nano-edge-tts pure text math (NanoEdgeText — the port of
 * speechnotes-linux sn-tts nano_edge.rs + kokoro.rs's
 * tokenize_for_kokoro_family). The ONNX shell itself is device-tested.
 */
class NanoEdgeTextTest {

    // ------------------------------------------------------------------
    // Voice-bank validation (nano_edge.rs load_voice): headerless f32
    // plane, whole rows of 256, at least one row.
    // ------------------------------------------------------------------

    @Test
    fun a_full_bank_loads_into_whole_rows() {
        val rows = 510
        val bytes = ByteArray(rows * 256 * 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, 0.25f)
        val flat = NanoEdgeText.validateVoiceBank(bytes)
        assertEquals(rows * 256, flat.size)
        assertEquals(0.25f, flat[0], 0f)
    }

    @Test
    fun a_mid_row_bank_fails_at_load() {
        // One byte past a whole row — the truncated-download shape.
        assertThrows(IllegalArgumentException::class.java) {
            NanoEdgeText.validateVoiceBank(ByteArray(256 * 4 + 1))
        }
    }

    @Test
    fun an_empty_bank_fails_at_load() {
        assertThrows(IllegalArgumentException::class.java) {
            NanoEdgeText.validateVoiceBank(ByteArray(0))
        }
    }

    @Test
    fun a_little_endian_bank_decodes_to_the_published_values() {
        val bytes = ByteArray(256 * 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(4 * 7, -1.5f)
        val flat = NanoEdgeText.validateVoiceBank(bytes)
        assertEquals(-1.5f, flat[7], 0f)
    }

    // ------------------------------------------------------------------
    // Style row (nano_edge.rs): index = clamp(phoneme_chars - 1, 0, rows-1).
    // ------------------------------------------------------------------

    @Test
    fun style_row_keys_on_the_phoneme_count_minus_one() {
        assertEquals(0, NanoEdgeText.styleRowOffset(1, 510))
        assertEquals(1, NanoEdgeText.styleRowOffset(2, 510))
        assertEquals(2, NanoEdgeText.styleRowOffset(3, 510))
        assertEquals(41, NanoEdgeText.styleRowOffset(42, 510))
    }

    @Test
    fun style_row_clamps_into_the_bank() {
        assertEquals(509, NanoEdgeText.styleRowOffset(600, 510))
        assertEquals(509, NanoEdgeText.styleRowOffset(Int.MAX_VALUE, 510))
        // Degenerate bank: never a negative index.
        assertEquals(0, NanoEdgeText.styleRowOffset(10, 0))
    }

    // ------------------------------------------------------------------
    // Tokenize (kokoro.rs tokenize_for_kokoro_family, Android adaptation):
    // substitution, cleaning, terminal pause, token cap.
    // ------------------------------------------------------------------

    private val vocab = mapOf(
        " " to 16, "a" to 1, "b" to 2, "ʃ" to 33, "." to 5, "," to 6, "…" to 7,
    )

    @Test
    fun vocab_chars_map_and_unknowns_become_spaces() {
        val t = NanoEdgeText.tokenize("aʃb z", "test", vocab, spaceId = 16)
        // a → 1, ʃ → 33, b → 2, space → 16, z unknown → 16, then the
        // unpunctuated end's "…" long pause (id 7).
        assertEquals(listOf(1, 33, 2, 16, 16, 7), t.ids.toList())
        assertEquals(listOf('z'), t.dropped)
        assertEquals(5, t.phonemeChars)
    }

    @Test
    fun hyphens_and_apostrophes_are_replaced_before_counting() {
        // The phonemiser echoes back what it cannot classify; the reference
        // tokenizer replaces before the style-row count.
        val t = NanoEdgeText.tokenize("a-'b", "test", vocab, spaceId = 16)
        assertEquals(4, t.phonemeChars) // "a  b" after cleaning
        assertEquals(listOf(1, 16, 16, 2, 7), t.ids.toList())
    }

    @Test
    fun an_unpunctuated_end_takes_the_long_pause() {
        val t = NanoEdgeText.tokenize("ab", "A heading cut", vocab, spaceId = 16)
        assertEquals(listOf(1, 2, 7), t.ids.toList()) // "…" appended
    }

    @Test
    fun terminal_punctuation_reattaches_its_own_symbol() {
        val t = NanoEdgeText.tokenize("ab", "It ends.", vocab, spaceId = 16)
        assertEquals(listOf(1, 2, 5), t.ids.toList())
        // , ; : all take the comma pause (kokoro.rs terminal_pause).
        assertEquals(listOf(1, 2, 6), NanoEdgeText.tokenize("ab", "it ends,", vocab, 16).ids.toList())
        assertEquals(listOf(1, 2, 6), NanoEdgeText.tokenize("ab", "it ends;", vocab, 16).ids.toList())
    }

    @Test
    fun a_pause_already_in_the_stream_is_not_doubled() {
        // Android's MisakiG2P passes . ; : ! ? through — the recorded
        // divergence from espeak. The dot the phonemiser already emitted is
        // the pause; appending another would double it.
        val t = NanoEdgeText.tokenize("ab.", "It ends.", vocab, spaceId = 16)
        assertEquals(listOf(1, 2, 5), t.ids.toList())
    }

    @Test
    fun a_symbol_the_vocab_lost_skips_the_pause_silently() {
        val small = mapOf(" " to 16, "a" to 1, "b" to 2)
        val t = NanoEdgeText.tokenize("ab", "It ends.", small, spaceId = 16)
        assertEquals(listOf(1, 2), t.ids.toList())
    }

    @Test
    fun one_token_is_out_of_range() {
        assertThrows(IllegalStateException::class.java) {
            NanoEdgeText.tokenize("z", "x", vocab, spaceId = 16) // one unknown → one space id
        }
    }

    @Test
    fun over_the_token_cap_is_out_of_range() {
        val phonemes = "a".repeat(NanoEdgeText.MAX_TOKENS + 1)
        assertThrows(IllegalStateException::class.java) {
            NanoEdgeText.tokenize(phonemes, "x", vocab, spaceId = 16)
        }
    }

    @Test
    fun the_pause_is_skipped_at_the_cap_not_pushed_past_it() {
        // n == MAX_TOKENS: no pause push (the "would overflow with it" case),
        // and no error either.
        val phonemes = "a".repeat(NanoEdgeText.MAX_TOKENS)
        val t = NanoEdgeText.tokenize(phonemes, "x", vocab, spaceId = 16)
        assertEquals(NanoEdgeText.MAX_TOKENS, t.ids.size)
        assertTrue(t.ids.all { it == 1 })
    }

    // ------------------------------------------------------------------
    // Constants (nano_edge.rs).
    // ------------------------------------------------------------------

    @Test
    fun the_frame_window_and_style_dim_match_the_export() {
        assertEquals(16_384, NanoEdgeText.MAX_FRAMES)
        assertEquals(256, NanoEdgeText.REF_STYLE_DIM)
        assertEquals(510, NanoEdgeText.MAX_TOKENS)
        assertEquals(16, NanoEdgeText.DEFAULT_SPACE_ID)
    }
}
