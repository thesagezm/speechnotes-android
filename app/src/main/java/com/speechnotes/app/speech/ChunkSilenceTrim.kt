package com.speechnotes.app.speech

/**
 * Trims the model's own leading/trailing silence off every chunk, down to a
 * small natural pad — the sentence-gap tightening pass (Unit G3,
 * 2026-11-15).
 *
 * WHY: the G2 bank made the PIPELINE gapless (the log that confirmed it
 * shows Phonon holding 6–36 s of audio ahead with `bridge=off` on every
 * chunk — no dry-bank event anywhere), yet the listener still hears a pause
 * between sentences. What remains lives INSIDE the PCM: every chunk starts
 * from a fresh model state and runs until EOS, so each chunk carries the
 * model's own lead-in before the first word and trail-off after the last —
 * silence the listener experiences as dead air stacked on top of the
 * sentence's natural pause. (Phonon's EOS countdown even keeps a whole
 * 80 ms frame past the threshold crossing by design — see
 * ChunkBoundaryFades.) Trimming both ends to a fixed pad keeps a natural
 * sentence pause while removing the model's excess.
 *
 * RECORDED DIVERGENCE: iOS (head 1c2a6ad, after cf501b2) performs no PCM
 * silence trimming — SpeechSanitizer is text-only. This pass is
 * Android-first, per the user's device report ("reduce the gap between
 * sentences/chunks a bit more").
 *
 * SAFETY: the trim only ever removes samples BELOW [threshold] (≈ −48 dBFS
 * — room tone and EOS tail-off, never consonants), and it always LEAVES a
 * pad beyond the last audible sample, so a word's decay is never clipped.
 * A chunk with no audible content at all is returned untouched (the
 * pipeline's failed-chunk path owns synthesis anomalies). Applied at the
 * core's two chunk sinks — the playback producer and the WAV export loop —
 * before [ChunkBoundaryFades], so the fades ride the trimmed boundaries and
 * exports match live playback.
 *
 * Pure arithmetic — no clocks, no I/O, no Android imports — assertable in
 * unit tests (JVM, no device needed).
 */
object ChunkSilenceTrim {

    /**
     * Amplitude below which a sample counts as silence. 0.004 ≈ −48 dBFS:
     * comfortably under soft consonant tails (−40..−35 dB) and breaths, well
     * above the model's EOS room tone.
     */
    const val DEFAULT_THRESHOLD: Float = 0.004f

    /**
     * Silence kept beyond the LAST audible sample — the sentence pause the
     * listener should still hear. 120 ms plus the next chunk's head pad
     * lands the inter-sentence gap near ~160 ms, TTS-tight but natural.
     */
    const val DEFAULT_TAIL_PAD_SECONDS: Double = 0.12

    /** Silence kept before the FIRST audible sample. */
    const val DEFAULT_HEAD_PAD_SECONDS: Double = 0.04

    /** The trimmed chunk plus what came off, for the timeline's trim stat. */
    class Result(
        val samples: FloatArray,
        val trimmedHeadFrames: Int,
        val trimmedTailFrames: Int,
    ) {
        val trimmedFrames: Int get() = trimmedHeadFrames + trimmedTailFrames
    }

    /**
     * Trims below-[threshold] silence from both ends of [samples], keeping
     * [tailPadSeconds] after the last audible sample and
     * [headPadSeconds] before the first. Returns the input untouched (zero
     * trim) when it is entirely below threshold or degenerate — a silent
     * chunk must reach the sink unchanged, never as a zero-frame array. The
     * input array is never mutated.
     */
    fun apply(
        samples: FloatArray,
        sampleRate: Double,
        threshold: Float = DEFAULT_THRESHOLD,
        tailPadSeconds: Double = DEFAULT_TAIL_PAD_SECONDS,
        headPadSeconds: Double = DEFAULT_HEAD_PAD_SECONDS,
    ): Result {
        if (sampleRate <= 0.0 || samples.isEmpty()) {
            return Result(samples, 0, 0)
        }
        var firstAudible = -1
        var lastAudible = -1
        for (index in samples.indices) {
            if (kotlin.math.abs(samples[index]) > threshold) {
                if (firstAudible < 0) firstAudible = index
                lastAudible = index
            }
        }
        if (firstAudible < 0) {
            // No audible content — synthesis anomaly; leave it whole.
            return Result(samples, 0, 0)
        }
        val headKeep = (sampleRate * headPadSeconds).toInt()
        val tailKeep = (sampleRate * tailPadSeconds).toInt()
        val start = (firstAudible - headKeep).coerceAtLeast(0)
        val end = (lastAudible + 1 + tailKeep).coerceAtMost(samples.size)
        if (end - start >= samples.size) {
            return Result(samples, 0, 0) // nothing to take off
        }
        val out = samples.copyOfRange(start, end)
        return Result(out, start, samples.size - end)
    }
}
