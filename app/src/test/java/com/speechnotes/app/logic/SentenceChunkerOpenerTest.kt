package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opener-completion contract (iOS afdc1d1): with `minOpenerChars` > 0 the
 * opener grows by WHOLE following sentences up to the budget, so a short
 * first sentence no longer yields a short opener — a short opener is exactly
 * what runs out of audio before the second buffer arrives. Default OFF: the
 * pinned `SentenceChunkerRegressionTest` contract is unchanged.
 */
class SentenceChunkerOpenerTest {

    // "Ok." (4) + " Second sentence has a few more words. " (39) +
    // " Third sentence." (16) = 59.
    private val text = "Ok. Second sentence has a few more words. Third sentence."

    @Test
    fun defaultOff_keepsTheShortOpener() {
        val chunks = SentenceChunker.chunks(text, firstMaxChars = 4, batchMaxChars = 200)
        assertEquals(2, chunks.size)
        // Terminating punctuation AND its trailing whitespace belong to the
        // chunk they terminate ("Ok. " = 4 UTF-16 units).
        assertEquals("Ok. ", chunks[0].text)
        assertTrue(chunks[0].length < 10)
    }

    @Test
    fun openerCompletes_byWholeSentencesToTheBudget() {
        val chunks = SentenceChunker.chunks(
            text,
            firstMaxChars = 4,
            batchMaxChars = 200,
            minOpenerChars = 45,
        )
        // " Second sentence…" (39) fits under 45; " Third sentence." (16)
        // does not — the opener ends at the sentence boundary and the third
        // sentence stays whole for the next chunk.
        assertEquals(2, chunks.size)
        assertEquals("Ok. Second sentence has a few more words. ", chunks[0].text)
        assertEquals("Third sentence.", chunks[1].text)
        assertEquals(0, chunks[0].offset)
    }

    @Test
    fun openerTakesEverything_whenAllSentencesFit() {
        val chunks = SentenceChunker.chunks(
            text,
            firstMaxChars = 4,
            batchMaxChars = 200,
            minOpenerChars = 100,
        )
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].text)
    }

    @Test
    fun openerNeverExceedsTheBatchCeiling() {
        val chunks = SentenceChunker.chunks(
            text,
            firstMaxChars = 4,
            batchMaxChars = 30,
            minOpenerChars = 500,
        )
        // The opener cap is min(minOpenerChars, batchMaxChars) = 30: the
        // opener completes only with pieces that fit under it (whole
        // sentences, word-split when oversized) and never past it.
        assertTrue("opener was ${chunks[0].length}", chunks[0].length <= 30)
        assertTrue(chunks[0].length > 4)
        assertTrue(
            "oversized: ${chunks.map { it.length }}",
            chunks.all { it.length <= 30 },
        )
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun completedChunks_concatenateBackToTheOriginalText() {
        val chunks = SentenceChunker.chunks(
            text,
            firstMaxChars = 4,
            batchMaxChars = 45,
            minOpenerChars = 60,
        )
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun budgetedOpener_alsoUsesTheBudgetForLaterChunks() {
        // The budget only extends the OPENER; later chunks still pack to
        // batchMaxChars.
        val long = "Ok. " + "Another whole sentence follows here. ".repeat(12)
        val chunks = SentenceChunker.chunks(
            long,
            firstMaxChars = 4,
            batchMaxChars = 80,
            minOpenerChars = 60,
        )
        assertTrue("opener was ${chunks[0].length}", chunks[0].length >= 40)
        assertEquals(long, chunks.joinToString("") { it.text })
        assertTrue(
            "oversized: ${chunks.map { it.length }}",
            chunks.drop(1).all { it.length <= 80 },
        )
    }
}
