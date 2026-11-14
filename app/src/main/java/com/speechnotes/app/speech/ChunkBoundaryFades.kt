package com.speechnotes.app.speech

import kotlin.math.PI
import kotlin.math.cos

/**
 * Raised-cosine fades on every chunk's first and last few milliseconds —
 * the Kotlin port of StreamingTTSPlaybackCore.boundaryFaded (speechnotes-ios
 * commit cf501b2, the "Phonon chunk click+gap" fix).
 *
 * Every engine cuts a chunk's PCM at a synthesis boundary — Phonon's EOS
 * countdown keeps one 80 ms frame past the threshold crossing, Kokoro and
 * Supertonic end at their own step counts — and every chunk starts from a
 * FRESH model state, so the outgoing chunk's final amplitude and the
 * incoming chunk's first amplitude are unrelated samples. Played back-to-back
 * on one track, the step between them is a broadband transient: the audible
 * click at the end of every chunk. A few milliseconds of raised-cosine ramp
 * on both ends turns each step into a slope; at sentence boundaries the
 * shaped audio is far below what speech perception notices, and the engines'
 * own natural decay keeps sounding through the ramp.
 *
 * Applied at the core's two chunk sinks — the playback producer (before the
 * AudioTrack write) and the WAV export loop — so live playback and exports
 * get identical boundaries. The keepalive bridge's silence never passes
 * through here (a fade of zeros is a no-op by construction).
 *
 * Pure arithmetic — no clocks, no I/O, no Android imports — so the shape is
 * assertable in unit tests (JVM, no device needed).
 */
object ChunkBoundaryFades {

    /** iOS cf501b2 constants: 5 ms in, 12 ms out. */
    private const val FADE_IN_SECONDS = 0.005
    private const val FADE_OUT_SECONDS = 0.012

    /**
     * Returns [samples] with raised-cosine ramps on both ends. Fade lengths
     * clamp to half the chunk so a degenerate one-frame buffer cannot overlap
     * itself. The input array is never mutated — [generateChunk] callers may
     * hold the original — the copy is one pass a synthesis already outspent
     * by orders of magnitude.
     */
    fun apply(samples: FloatArray, sampleRate: Double): FloatArray {
        if (sampleRate <= 0.0 || samples.isEmpty()) return samples
        val out = samples.copyOf()
        val fadeInFrames = minOf(out.size / 2, (sampleRate * FADE_IN_SECONDS).toInt())
        val fadeOutFrames = minOf(out.size / 2, (sampleRate * FADE_OUT_SECONDS).toInt())
        if (fadeInFrames > 0) {
            for (index in 0 until fadeInFrames) {
                val t = index.toDouble() / fadeInFrames
                out[index] *= (0.5 * (1.0 - cos(PI * t))).toFloat()
            }
        }
        if (fadeOutFrames > 0) {
            val start = out.size - fadeOutFrames
            for (index in 0 until fadeOutFrames) {
                val t = index.toDouble() / fadeOutFrames
                out[start + index] *= (0.5 * (1.0 + cos(PI * t))).toFloat()
            }
        }
        return out
    }
}
