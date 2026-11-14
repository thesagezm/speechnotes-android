package com.speechnotes.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pins for ChunkBoundaryFades — the Kotlin port of iOS cf501b2's
 * boundaryFaded (the "Phonon chunk click+gap" fix): raised-cosine ramps of
 * 5 ms in / 12 ms out on every chunk at both sinks (playback + WAV export),
 * so the unrelated amplitudes at a chunk boundary meet as a slope, not a
 * step (the end-of-chunk click).
 */
class ChunkBoundaryFadesTest {

    private val rate = 24_000.0
    private val fadeIn = (rate * 0.005).toInt()    // 120 frames
    private val fadeOut = (rate * 0.012).toInt()   // 288 frames

    @Test
    fun fades_areZeroAtTheVeryEnds() {
        // A full-amplitude constant tone: after the fades the first sample
        // is silent (t=0 → 0.5·(1−cos 0) = 0) and the last sample lands on
        // the fade-out ramp's near-zero tail (t=(n−1)/n).
        val samples = FloatArray(10_000) { 0.8f }
        val out = ChunkBoundaryFades.apply(samples, rate)
        assertEquals(0.0f, out[0], 1e-6f)
        val expectedLast = 0.8f * (0.5 * (1.0 + cos(PI * (fadeOut - 1.0) / fadeOut))).toFloat()
        assertEquals(expectedLast, out[out.size - 1], 1e-6f)
    }

    @Test
    fun fades_matchTheRaisedCosineShapeInsideTheRamp() {
        val samples = FloatArray(10_000) { 1.0f }
        val out = ChunkBoundaryFades.apply(samples, rate)
        // Mid-ramp fade-in factor: 0.5·(1−cos πt) — exact iOS arithmetic.
        val t = 0.25
        val index = (fadeIn * t).toInt()
        assertEquals((0.5 * (1.0 - cos(PI * index.toDouble() / fadeIn))).toFloat(), out[index], 1e-6f)
        // Fade-out mirror: 0.5·(1+cos πt) from the end.
        val outIndex = out.size - fadeOut + (fadeOut * 0.5).toInt()
        val tt = (outIndex - (out.size - fadeOut)).toDouble() / fadeOut
        assertEquals((0.5 * (1.0 + cos(PI * tt))).toFloat(), out[outIndex], 1e-6f)
    }

    @Test
    fun middle_isUntouched() {
        val samples = FloatArray(10_000) { if (it % 2 == 0) 0.9f else -0.7f }
        val out = ChunkBoundaryFades.apply(samples, rate)
        for (index in fadeIn until out.size - fadeOut) {
            assertEquals(samples[index], out[index], 0.0f)
        }
    }

    @Test
    fun ramps_neverExceedTheOriginalEnvelope() {
        // A sine at full scale: every faded sample stays within ±1.0.
        val samples = FloatArray(10_000) { i -> sin(2.0 * PI * i / 128.0).toFloat() }
        val out = ChunkBoundaryFades.apply(samples, rate)
        for (value in out) assertTrue(abs(value) <= 1.0f + 1e-6f)
    }

    @Test
    fun degenerate_buffers_neverOverlapThemselves() {
        // One frame: min(count/2, …) clamps both fades to 0 — values unchanged
        // (apply still returns a fresh array; the input is never mutated).
        val one = floatArrayOf(0.5f)
        val oneOut = ChunkBoundaryFades.apply(one, rate)
        assertEquals(1, oneOut.size)
        assertEquals(0.5f, oneOut[0], 0.0f)
        // Two frames: fades clamp to 1 frame each, no overlap. The 1-frame
        // fade-in hits t=0 (silent); the 1-frame fade-out's only sample is
        // also t=0 → factor 1.0, unchanged (iOS's arithmetic, verbatim).
        val two = floatArrayOf(0.5f, 0.5f)
        val out = ChunkBoundaryFades.apply(two, rate)
        assertEquals(0.0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
    }

    @Test
    fun empty_orInvalidRate_returnsTheInputUnchanged() {
        val empty = FloatArray(0)
        assertSame(empty, ChunkBoundaryFades.apply(empty, rate))
        val samples = floatArrayOf(0.5f, 0.5f, 0.5f)
        assertSame(samples, ChunkBoundaryFades.apply(samples, 0.0))
        assertSame(samples, ChunkBoundaryFades.apply(samples, -24_000.0))
    }

    @Test
    fun input_isNeverMutated() {
        val samples = FloatArray(10_000) { 1.0f }
        val snapshot = samples.copyOf()
        ChunkBoundaryFades.apply(samples, rate)
        assertTrue(samples.contentEquals(snapshot))
    }

    private fun abs(value: Float): Float = if (value < 0) -value else value
}
