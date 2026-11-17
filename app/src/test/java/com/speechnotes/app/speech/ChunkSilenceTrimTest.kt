package com.speechnotes.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the chunk silence trim — the sentence-gap tightening
 * pass (Unit G3). Pure arithmetic, runs on the JVM.
 */
class ChunkSilenceTrimTest {

    private val rate = 24000.0

    private val headPad = (rate * ChunkSilenceTrim.DEFAULT_HEAD_PAD_SECONDS).toInt()
    private val tailPad = (rate * ChunkSilenceTrim.DEFAULT_TAIL_PAD_SECONDS).toInt()

    /** headSilence + tone + tailSilence, tone amplitude 0.5 (loud). */
    private fun chunk(headSilence: Int, toneFrames: Int, tailSilence: Int): FloatArray {
        val out = FloatArray(headSilence + toneFrames + tailSilence)
        for (i in 0 until toneFrames) out[headSilence + i] = 0.5f
        return out
    }

    // MARK: - Basic trimming

    @Test
    fun trimsHeadAndTailDownToThePads() {
        val head = (rate * 0.5).toInt()      // 500 ms of lead-in silence
        val tail = (rate * 0.6).toInt()      // 600 ms of trail-off silence
        val src = chunk(head, 1000, tail)
        val result = ChunkSilenceTrim.apply(src, rate)
        assertEquals(head - headPad, result.trimmedHeadFrames)
        assertEquals(tail - tailPad, result.trimmedTailFrames)
        assertEquals(1000 + headPad + tailPad, result.samples.size)
    }

    @Test
    fun keepsSilenceUpToThePadUncut() {
        // Head/tail silence exactly at the pad widths: nothing to trim, and
        // the SAME array is returned (no copy for a no-op).
        val src = chunk(headPad, 800, tailPad)
        val result = ChunkSilenceTrim.apply(src, rate)
        assertEquals(0, result.trimmedFrames)
        assertSame(src, result.samples)
    }

    @Test
    fun quietTailAboveThresholdIsKeptBelowThresholdIsTrimmed() {
        val tail = (rate * 0.3).toInt()
        // 0.005 > the 0.004 threshold — a soft speech decay survives whole.
        val soft = FloatArray(1000 + tail)
        for (i in 0 until 1000) soft[i] = 0.5f
        for (i in 1000 until soft.size) soft[i] = 0.005f
        assertEquals(0, ChunkSilenceTrim.apply(soft, rate).trimmedTailFrames)

        // 0.003 < the threshold — room tone goes, the pad stays.
        val roomTone = FloatArray(1000 + tail)
        for (i in 0 until 1000) roomTone[i] = 0.5f
        for (i in 1000 until roomTone.size) roomTone[i] = 0.003f
        assertTrue(ChunkSilenceTrim.apply(roomTone, rate).trimmedTailFrames > 0)
    }

    // MARK: - Safety

    @Test
    fun entirelySilentChunkIsReturnedUntouched() {
        val src = FloatArray((rate * 2.0).toInt())
        val result = ChunkSilenceTrim.apply(src, rate)
        assertEquals(0, result.trimmedFrames)
        assertSame(src, result.samples)
        assertEquals(src.size, result.samples.size)
    }

    @Test
    fun inputArrayIsNeverMutated() {
        val src = chunk(200, 500, 300)
        val copy = src.copyOf()
        ChunkSilenceTrim.apply(src, rate)
        assertTrue(src.contentEquals(copy))
    }

    @Test
    fun degenerateChunkAndRatePassThrough() {
        assertEquals(0, ChunkSilenceTrim.apply(FloatArray(0), rate).trimmedFrames)
        val tiny = FloatArray(3) { 0.5f }
        assertEquals(0, ChunkSilenceTrim.apply(tiny, 0.0).trimmedFrames)
        assertEquals(0, ChunkSilenceTrim.apply(tiny, -100.0).trimmedFrames)
    }

    @Test
    fun padsNeverOverlapOnAShortChunk() {
        // 100 ms of tone: the pads (40+120 ms) want more than exists — the
        // tone must survive whole, the result never empty.
        val toneFrames = (rate * 0.1).toInt()
        val src = chunk(0, toneFrames, 0)
        val result = ChunkSilenceTrim.apply(src, rate)
        assertEquals(0, result.trimmedFrames)
        assertEquals(toneFrames, result.samples.size)
    }

    @Test
    fun customPadsAreHonored() {
        val head = (rate * 0.4).toInt()
        val src = chunk(head, 600, head)
        val result = ChunkSilenceTrim.apply(
            src,
            rate,
            tailPadSeconds = 0.0,
            headPadSeconds = 0.0,
        )
        assertEquals(head, result.trimmedHeadFrames)
        assertEquals(head, result.trimmedTailFrames)
        assertEquals(600, result.samples.size)
    }
}
