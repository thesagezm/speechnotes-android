package com.speechnotes.app.logic

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the firstChunk utf16-walk bug: the limit was computed by
 * walking FROM text.length, so it always equalled the text length and a long
 * unterminated opening paragraph became one giant chunk — on device that
 * chunk phonemized to 681 tokens, blew Kokoro's MAX_TOKENS (510), and the
 * whole utterance was skipped ("no audio produced").
 */
class SentenceChunkerRegressionTest {

    private val longParagraph = "The heart beats seventy times per minute, and ".repeat(16) + "end."

    @Test
    fun firstChunk_isCappedAtMaxChars() {
        val first = SentenceChunker.firstChunk(longParagraph, maxChars = 100)!!
        assertTrue("first chunk was ${first.text.length} chars", first.text.length <= 100)
    }

    @Test
    fun chunks_neverExceedBatchMaxChars() {
        val chunks = SentenceChunker.chunks(longParagraph, firstMaxChars = 100, batchMaxChars = 160)
        val oversized = chunks.filter { it.text.length > 164 }
        assertTrue("oversized chunks: ${chunks.map { it.text.length }}", oversized.isEmpty())
    }

    @Test
    fun chunks_concatenateBackToTheOriginalText() {
        val chunks = SentenceChunker.chunks(longParagraph, firstMaxChars = 100, batchMaxChars = 160)
        val joined = chunks.joinToString("") { it.text }
        assertTrue(joined == longParagraph)
    }

    @Test
    fun terminatedText_stillYieldsSentenceChunks() {
        val text = "One. Two. Three. Four. Five. Six. Seven. Eight. Nine. Ten."
        val chunks = SentenceChunker.chunks(text, firstMaxChars = 100, batchMaxChars = 160)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.joinToString("") { it.text } == text)
    }
}
