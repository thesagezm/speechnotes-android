package com.speechnotes.app.speech

/**
 * Render-ahead bank policy for the streaming TTS engines — the Kotlin port of
 * RenderAheadBankPolicy.swift (speechnotes-ios, Batch B of the
 * background-persistence plan).
 *
 * Replaces the old CHUNK-COUNT throttle (`generationAheadLimit` chunks) with
 * AUDIO SECONDS, because chunk audio length varies ~40× between a short
 * sentence and a packed 200-char chunk: "2 chunks ahead" is anywhere from
 * 2 s to 40 s of protection. Seconds measure the thing the listener actually
 * hears run out.
 *
 * Sizing tracks THERMAL STATE (measured on the iOS device, RenderAheadBankPolicy
 * :120-131): synthesis RTF measured 0.42–0.49 at nominal/fair but 1.68 at
 * critical — above 1.0 the model cannot keep pace with real time, so under
 * pressure the bank shrinks and Supertonic drops its denoising steps from 8
 * to 4.
 *
 * QUALITY GUARD (the 2026-10-06 device verdict): shedding steps at `serious`
 * rendered ~90% of a 17-minute book at 4 steps — audible artifacts, pitch
 * drifting between chunks. 8-step chunks at RTF 0.42–0.49 keep ahead of
 * playback ~2:1. The shed is an EMERGENCY gear only: 4 steps at critical.
 *
 * Pure arithmetic — no clocks, no I/O, no logging — so the whole policy is
 * assertable in unit tests (JVM, no device needed).
 */
object RenderAheadBankPolicy {

    /** Thermal pressure, decoupled from android.os.PowerManager so this file stays testable on the JVM. */
    enum class ThermalPressure {
        NOMINAL, FAIR, SERIOUS, CRITICAL;

        companion object {
            /**
             * Maps a raw `PowerManager.THERMAL_STATUS_*` value. Values are clamped,
             * not trapping: an unknown future raw value degrades to the hottest
             * known state (conservative — under-sizing the bank only makes the
             * producer hold earlier, it never over-runs a hot device), and a
             * negative one to the coolest.
             */
            fun fromStatusRawValue(raw: Int): ThermalPressure = when {
                raw < 1 -> NOMINAL
                raw == 1 -> FAIR
                raw == 2 -> SERIOUS
                else -> CRITICAL
            }
        }
    }

    /**
     * Hard ceiling on the bank in PCM bytes. The seconds targets bind far
     * below it at every sample rate the engines use (24 kHz → 96 KB/s, so
     * the largest seconds target is ~2.9 MB); this is the guard against a
     * future retune or an unexpected sample rate, sized to stay an order of
     * magnitude under OOM territory alongside ~400 MB of resident ONNX
     * sessions.
     */
    const val DEFAULT_BYTE_CAP_BYTES: Int = 32 * 1024 * 1024

    /**
     * Supertonic's full-quality flow-matching step count — the upstream
     * ExampleONNX default. Used verbatim at nominal/fair/serious and on
     * offline exports (where RTF > 1 costs nothing, so the pressure shed
     * never applies).
     */
    const val FULL_QUALITY_TOTAL_STEP: Int = 8

    private val byteCapBytesDefault = DEFAULT_BYTE_CAP_BYTES

    /**
     * Bank target in audio seconds, by thermal state. Nominal and fair share
     * the full target: RTF stays comfortably under 1.0 through fair, and the
     * bank's job there is to ride out main-thread stalls. At serious and
     * critical it shrinks toward the minimum useful buffer — generating
     * further ahead of a machine that has already lost real time burns CPU
     * (heat) for audio that cannot cover a drain anyway.
     */
    fun targetSeconds(thermal: ThermalPressure): Double = when (thermal) {
        ThermalPressure.NOMINAL, ThermalPressure.FAIR -> 30.0
        ThermalPressure.SERIOUS -> 15.0
        ThermalPressure.CRITICAL -> 8.0
    }

    /**
     * Supertonic's flow-matching denoising step count, by thermal state.
     * Audio DURATION is set by the duration predictor and does not depend on
     * the step count — only synthesis time (and quality margin) does — so
     * fewer steps means faster chunks and NOTHING else in the pacing
     * arithmetic. Kokoro has no step parameter and ignores this.
     *
     * The shed fires ONLY at critical, where the Batch A log measured RTF
     * 1.68 at 8 steps and speech would stall outright.
     */
    fun totalStep(thermal: ThermalPressure): Int = when (thermal) {
        ThermalPressure.CRITICAL -> 4
        else -> FULL_QUALITY_TOTAL_STEP
    }

    /**
     * How far the producer may pre-render under thermal pressure, as a
     * multiplier on the state's seconds target.
     *
     * Kokoro has no compute dial — no step parameter, and shrinking chunks
     * does not lower a transformer's RTF, which is roughly constant in chunk
     * size. What smaller banks DO buy is GRANULARITY: at a 15 s bank a
     * thermal transition costs at most 15 s of margin swing instead of 30 s,
     * so the drain that follows recovers in smaller steps. That is a real
     * property, and it is not throughput.
     */
    fun pressureFactor(thermal: ThermalPressure): Double = when (thermal) {
        ThermalPressure.NOMINAL, ThermalPressure.FAIR -> 1.0
        ThermalPressure.SERIOUS -> 0.5
        ThermalPressure.CRITICAL -> 0.25
    }

    /** Bytes of mono Float32 PCM per second of audio at [sampleRate]. */
    fun bytesPerSecond(sampleRate: Double): Double = sampleRate * 4

    /**
     * The target that actually gates the producer: the thermal seconds
     * target, CAPPED by the byte cap expressed in seconds. Non-positive
     * sample rates fall back to treating the seconds target as binding
     * (a zero sample rate cannot be converted to bytes; refusing to
     * generate at all would be a worse failure than an unguarded bank).
     */
    fun effectiveTargetSeconds(thermal: ThermalPressure, sampleRate: Double): Double {
        val secondsTarget = targetSeconds(thermal)
        if (sampleRate <= 0) return secondsTarget
        val bytesTarget = byteCapBytesDefault / bytesPerSecond(sampleRate)
        return minOf(secondsTarget, bytesTarget)
    }

    /**
     * The seconds target under thermal pressure, after the byte cap.
     * [effectiveTargetSeconds] plus the pressure factor, in one call.
     */
    fun pressuredTargetSeconds(thermal: ThermalPressure, sampleRate: Double): Double =
        effectiveTargetSeconds(thermal, sampleRate) * pressureFactor(thermal)

    /**
     * Whether the producer may synthesize the next chunk. At `banked ==
     * target` the producer HOLDS (this returns false): the bank must DRAIN
     * below target before more work is admitted, so a producer parked at
     * exactly the full mark cannot generate back-to-back.
     *
     * Deliberately just `banked < target` — NOT `banked + estimatedNextChunk
     * ≤ target`. A target can drop below one chunk's audio length (critical's
     * 8 s vs a ~20 s chunk), and an estimate-including check never passes
     * then: the producer wedges forever with a starving bank. The shipped
     * check overshoots the target by at most one chunk — bounded, and unable
     * to deadlock.
     */
    fun allows(bankedSeconds: Double, targetSeconds: Double): Boolean =
        bankedSeconds < targetSeconds

    /**
     * Bank depth below which the bank counts as exhausted (synthesis-bound
     * silence) rather than merely draining. Half a second: enough to absorb
     * ticker quantisation, small enough that a genuinely empty bank never
     * reads as covered.
     */
    const val EXHAUSTION_THRESHOLD_SECONDS: Double = 0.5

    fun isExhausted(bankedSeconds: Double): Boolean =
        bankedSeconds < EXHAUSTION_THRESHOLD_SECONDS
}
