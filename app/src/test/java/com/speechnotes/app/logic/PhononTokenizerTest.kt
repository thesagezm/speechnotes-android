package com.speechnotes.app.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Phonon Unigram tokenizer against the reference `tokenizers`
 * implementation: the fixture IS the hub's tokenizer.json, and every
 * expected id list was produced by
 * `Tokenizer.from_file(...).encode(text, add_special_tokens=False)` —
 * the exact ids the model was validated against. The SAME fixture files
 * pin the iOS port (PhononTokenizerTests.swift), so Android, iOS and the
 * reference all emit identical ids for these texts by construction.
 */
class PhononTokenizerTest {

    private data class Fixture(val text: String, val ids: List<Int>)

    private fun makeTokenizer(): PhononTokenizer {
        val bytes = javaClass.getResourceAsStream("/phonon-tokenizer.json")?.readBytes()
            ?: error("phonon-tokenizer.json fixture missing from test resources")
        return PhononTokenizer(bytes)
    }

    private fun fixtures(): List<Fixture> {
        val text = javaClass.getResourceAsStream("/phonon-tokenizer-fixtures.json")?.readBytes()
            ?.decodeToString()
            ?: error("phonon-tokenizer-fixtures.json fixture missing from test resources")
        val array = Json.parseToJsonElement(text).jsonArray
        return array.map { element ->
            val obj = element.jsonObject
            Fixture(
                text = obj["text"]?.jsonPrimitive?.content ?: error("fixture without text"),
                ids = obj["ids"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }
                    ?: error("fixture without ids"),
            )
        }
    }

    /** The 14 reference-verified cases: plain prose, punctuation, typographic
     *  quotes/dashes, accented letters, digits, off-alphabet symbols and
     *  emoji (byte fallback), whitespace collapse. */
    @Test
    fun `encoding matches the reference tokenizers ids`() {
        val tokenizer = makeTokenizer()
        val fixtures = fixtures()
        assertEquals(14, fixtures.size)
        for (fixture in fixtures) {
            val ids = tokenizer.encode(fixture.text)
            assertEquals("ids diverge for «${fixture.text}»", fixture.ids, ids)
        }
    }

    @Test
    fun `audio specials are never emitted from user text`() {
        val tokenizer = makeTokenizer()
        // Text that literally contains the control pieces must not inject
        // token 4/5/6 into the stream — those fall back to bytes.
        val ids = tokenizer.encode("<|audio_bos|> <|audio_prompt|> <|audio_latent|>")
        assertFalse(ids.contains(PhononTokenizer.AUDIO_BOS_TOKEN_ID))
        assertFalse(ids.contains(PhononTokenizer.AUDIO_PROMPT_TOKEN_ID))
        assertFalse(ids.contains(PhononTokenizer.AUDIO_LATENT_TOKEN_ID))
        assertFalse(ids.isEmpty())
    }

    @Test
    fun `empty and whitespace-only encode to nothing`() {
        val tokenizer = makeTokenizer()
        assertEquals(emptyList<Int>(), tokenizer.encode(""))
        assertEquals(emptyList<Int>(), tokenizer.encode("   "))
        assertEquals(emptyList<Int>(), tokenizer.encode("\n\t \n"))
    }

    @Test
    fun `terminal period and capitalization are normalizer work`() {
        // The hub normalizer capitalizes the first letter and appends a final
        // period (steps 0-189 + the Alphabetic/N terminal period). The engine
        // hands the sanitizer's output straight in, so "hello world" must
        // encode the same ids as "Hello world." — the model never sees the
        // un-normalized form.
        val tokenizer = makeTokenizer()
        assertEquals(tokenizer.encode("Hello world."), tokenizer.encode("hello world"))
    }

    @Test
    fun `digits and currency survive byte fallback`() {
        // The hub does NOT spell out currency/symbols (its known gap) —
        // "€5.50" must still encode deterministically via byte fallback,
        // never throw.
        val tokenizer = makeTokenizer()
        val ids = tokenizer.encode("It costs €5.50 today.")
        assertEquals(13, ids.size)
        assertTrue(ids.all { it >= 0 && it < 4001 })
    }
}
