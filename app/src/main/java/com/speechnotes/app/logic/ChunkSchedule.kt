package com.speechnotes.app.logic

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The chunk schedule — the dial between "speech starts fast" and "speech
 * does not run dry". Ported from SpeechLogic's ChunkSchedule.swift
 * (speechnotes-ios, commit afdc1d1); algorithms preserved verbatim.
 *
 * The problem it solves: time-to-first-audio is the first chunk's RENDER
 * time, and time-to-second-audio is the second chunk's. If the second chunk
 * takes longer to render than the first takes to PLAY, there is audible
 * silence between the two. The break-even opener is `batchMaxChars · RTF`,
 * so no single fixed `firstMaxChars` serves both a fast engine and a slow
 * one — and a slow engine drifts as it thermally throttles.
 *
 * So there is no single right size: there is a schedule.
 *
 *  - The opener starts at a target of 8 s of speech (per engine: 8 s for
 *    Kokoro, 10 s for Supertonic — the measured waits in the iOS device log).
 *  - When a session's second buffer arrives LATE (the audible gap), the
 *    target GROWS — the opener is made big enough to cover the wait next
 *    time.
 *  - When a session's second buffer arrives ON TIME, the target HOLDS. It
 *    is only lowered when the cover was comfortable (>= 2 s of slack), and
 *    then it settles just above the measured wait rather than halving.
 *
 * WHY SECONDS AND NOT CHARACTERS
 *
 * The earlier ladder counted characters, and it failed in two ways that the
 * iOS device log records verbatim:
 *
 *  1. A character count cannot see the RATE of speech. The same 54
 *     characters were 7.64 s of audio in one session and 3.30 s in another —
 *     a rung that covered one session was far too small for the next.
 *     Seconds are the unit the gap is actually measured in.
 *  2. Resetting to the bottom on any covered session made the schedule
 *     OSCILLATE: one lucky session snapped the opener back to 100 chars, and
 *     the next session was short again. A schedule that forgets its own
 *     success teaches nothing.
 *
 * The target is therefore an audio-seconds budget, and the character count
 * it turns into is computed at speak time from the live rate and the
 * chars-per-audio-second this engine actually measured.
 */
object ChunkSchedule {

    // MARK: - The target

    /** The opening target, in seconds of speech the opener should carry.
     *
     *  The engine-specific seeds are what production uses, and they come
     *  from the iOS device logs' measured waits: Kokoro's second buffer
     *  arrived 7.4 s after the first (opener 3.3 s of audio → 1.2 s short),
     *  so 8 s is the smallest target that covers it. */
    const val initialTargetSeconds: Double = 8.0

    /** Supertonic's seed (iOS device log): the second buffer arrived
     *  8.5–13.2 s after the first, so 10 s covers the typical case and the
     *  schedule grows past it when the device is slower still. */
    const val supertonicTargetSeconds: Double = 10.0

    /** The ceiling on the target. Past this, a bigger opener cannot be
     *  rendered in one go by either engine anyway, and the honest answer for
     *  an engine too slow to benefit from one is "no fast-start". */
    const val maximumTargetSeconds: Double = 30.0

    /** The floor on the target. Below this the opener is too small to cover
     *  anything, and shrinking further just guarantees another gap. */
    const val minimumTargetSeconds: Double = 3.0

    /** How much of the opener's audio must be LEFT OVER after the wait for
     *  the second buffer before the schedule probes back down. Without a
     *  slack requirement, one covered-by-10 ms session would shrink the
     *  opener and the very next session would be short again. */
    const val shrinkSlackSeconds: Double = 2.0

    /** How much of the measured wait the next target must cover when the
     *  schedule grows. 1.2 gives the new opener a margin over the wait that
     *  actually happened, so one bad session does not need another. */
    const val growHeadroom: Double = 1.2

    /** The fraction of the current target a comfortable cover may give back.
     *  This is a CEILING on the shrink, never the target itself — the
     *  measured wait (plus the slack) is what the new target settles on, so
     *  a shrink can never fall below the wait it must cover. */
    const val shrinkFactor: Double = 0.75

    /** The fallback chars-per-audio-second used before the engine has
     *  measured its own. The shipped iOS device logs put Kokoro at ~16 chars
     *  per audio second (54 chars → 3.30 s) and Supertonic at ~8–11 (54
     *  chars → 7.64 s, 157 chars → 13.6 s). 15 is the mid-range guess an
     *  engine uses when it has no better figure — and it is replaced by the
     *  MEASUREMENT after the first session, not merely corrected by it. */
    const val defaultCharactersPerAudioSecond: Double = 15.0

    /** Kokoro's fallback chars-per-audio-second (iOS device log: 54 chars →
     *  3.30 s). */
    const val kokoroCharactersPerAudioSecond: Double = 16.0

    /** Supertonic's fallback chars-per-audio-second (iOS device log: 54
     *  chars → 7.64 s, 157 chars → 13.6 s). */
    const val supertonicCharactersPerAudioSecond: Double = 10.0

    /**
     * The state of one engine's schedule. The engine owns one per core
     * instance: it hands the current target to the chunker (via
     * [State.openerCharacterBudget]), and when a session ends it tells the
     * schedule what happened. The NEXT session then uses whatever the
     * schedule decided.
     *
     * Main-thread confined — `speak()` reads the budget, the core calls
     * [State.record] when its session ends (main thread).
     */
    class State(
        targetSeconds: Double = ChunkSchedule.initialTargetSeconds,
        fallbackCharactersPerAudioSecond: Double = ChunkSchedule.defaultCharactersPerAudioSecond,
    ) {
        /** The audio-seconds target the next session's opener should meet. */
        var targetSeconds: Double = clampTarget(targetSeconds)
            private set

        /** Whether the most recently RECORDED session had a late second
         *  buffer. null = no session recorded yet. */
        var lastWasLate: Boolean? = null
            private set

        /** The wait from the first buffer to the second, measured by the
         *  most recent session that reported one. null = never measured.
         *  Used when a session ends without a second buffer (a single-chunk
         *  text, or a user stop): the opener's own audio is then compared
         *  against the wait the engine is known to take. */
        var lastSecondBufferSeconds: Double? = null
            private set

        /** How many characters this engine actually speaks per second of
         *  AUDIO, measured from the last session's opener. null until the
         *  first session reports it.
         *
         *  This is the bridge between the schedule's seconds-based target
         *  and the character budget the chunker takes. It is measured, not
         *  assumed: Supertonic and Kokoro differ by more than a factor of
         *  two, and the same engine at a different speech rate differs
         *  again. */
        var measuredCharactersPerAudioSecond: Double? = null
            private set

        /** The speech rate the measurement above was taken at. The
         *  measurement is scaled by `rate / measuredAtRate` when the rate
         *  changes, because a faster voice needs more characters for the
         *  same audio. */
        var measuredAtRate: Double = 1.0
            private set

        /** The chars-per-audio-second used BEFORE the engine has measured
         *  its own. Seeded per engine from the iOS device logs; replaced by
         *  the measurement after the first session. */
        var fallbackCharactersPerAudioSecond: Double =
            if (fallbackCharactersPerAudioSecond > 0) {
                fallbackCharactersPerAudioSecond
            } else {
                ChunkSchedule.defaultCharactersPerAudioSecond
            }
            private set

        /** The target, clamped into the legal band. */
        val currentTargetSeconds: Double get() = clampTarget(targetSeconds)

        /** Records the outcome of one session.
         *
         *  @param secondBufferWasLate true when the second buffer arrived
         *    after the first one had already finished playing — the audible
         *    gap. null means the session never produced a second buffer (a
         *    single-chunk text, or an early stop).
         *  @param chunk0AudioSeconds the audio the FIRST buffer carried.
         *    This is the opener's actual reach, in the unit the gap is
         *    measured in.
         *  @param chunk0Characters the characters in that first buffer.
         *    Used to learn how many characters this engine speaks per second
         *    of audio.
         *  @param secondBufferSeconds the measured wait from the first
         *    buffer to the second. null when no second buffer arrived.
         *  @param rate the speech rate the session ran at (1.0 = normal).
         *    Only used to convert the target into a character budget. */
        fun record(
            secondBufferWasLate: Boolean?,
            chunk0AudioSeconds: Double?,
            chunk0Characters: Int? = null,
            secondBufferSeconds: Double?,
            rate: Double,
        ) {
            lastWasLate = secondBufferWasLate

            if (secondBufferSeconds != null) {
                lastSecondBufferSeconds = secondBufferSeconds
            }

            // Learn this engine's characters-per-audio-second. A session
            // that produced no audio teaches nothing.
            val audio = chunk0AudioSeconds
            val chars = chunk0Characters
            if (audio != null && audio > 0 && chars != null && chars > 0) {
                measuredCharactersPerAudioSecond = chars / audio
                measuredAtRate = if (rate > 0) rate else 1.0
            }

            val openerAudio = chunk0AudioSeconds ?: 0.0
            val wait = secondBufferSeconds

            if (wait == null) {
                // No second buffer: the session taught us nothing about the
                // wait. But the opener's own audio still tells us something
                // — if it was smaller than the wait this engine is KNOWN to
                // take, the opener is too small and grows.
                val remembered = lastSecondBufferSeconds
                if (remembered != null && openerAudio > 0 && openerAudio < remembered) {
                    targetSeconds = clampTarget(max(targetSeconds * 2, remembered * growHeadroom))
                }
                return
            }

            if (secondBufferWasLate == true) {
                // The gap happened: grow until the opener would have covered
                // it. Doubling is the coarse step; the measured wait gives
                // the floor. Both are ABOVE the wait, so the next session
                // cannot be short for the same reason.
                val needed = max(targetSeconds * 2, wait * growHeadroom)
                targetSeconds = clampTarget(needed)
                return
            }

            // Covered. Hold the target unless the cover was comfortable —
            // then settle it just above the wait that actually happened.
            //
            // The new target is `wait + slack`, NOT half the old one: a
            // fractional shrink of a target that merely happened to cover
            // can land BELOW the wait it has to cover, and the next session
            // is short again — the oscillation the old ladder was built out
            // of, one level up.
            val slack = openerAudio - wait
            if (slack >= shrinkSlackSeconds) {
                val settled = max(wait + shrinkSlackSeconds, targetSeconds * shrinkFactor)
                targetSeconds = clampTarget(min(settled, targetSeconds))
            }
        }

        /** The character budget for the opener, derived from the target, the
         *  live rate and this engine's measured speaking rate.
         *
         *  A faster voice plays the SAME characters in LESS time, so the
         *  same audio target needs MORE characters at rate 2.0 and FEWER at
         *  rate 0.5. (The rate multiplies the voice's duration, not its
         *  character count.)
         *
         *  @param batchCeiling the engine's own hard cap. An opener above it
         *    would be split by the chunker into two renders — the opposite
         *    of a fast opener. */
        fun openerCharacterBudget(rate: Double, batchCeiling: Int): Int {
            val effectiveRate = if (rate > 0) rate else 1.0
            val charsPerAudioSecond: Double
            val measured = measuredCharactersPerAudioSecond
            if (measured != null && measured > 0) {
                // Scale the measurement to the current rate: the same
                // characters take proportionally less audio when the voice
                // is faster, so MORE characters fit into the same target.
                charsPerAudioSecond = measured * (effectiveRate / measuredAtRate)
            } else {
                charsPerAudioSecond = fallbackCharactersPerAudioSecond * effectiveRate
            }
            val raw = currentTargetSeconds * charsPerAudioSecond
            val wanted = max(1, ceil(raw).toInt())
            return min(wanted, max(1, batchCeiling))
        }

        /** A one-line summary for the log. */
        val summary: String
            get() {
                val verdict = when (lastWasLate) {
                    true -> " (last second buffer LATE)"
                    false -> " (last second buffer covered)"
                    null -> ""
                }
                val rate = measuredCharactersPerAudioSecond
                    ?.let { ", ${twoDP(it)} chars/audio-s" }
                    ?: ""
                return "target ${twoDP(currentTargetSeconds)}s of opener audio$verdict$rate"
            }

        private fun twoDP(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
    }

    /** The character budget for a given target, rate and engine ceiling,
     *  using the FALLBACK chars-per-audio-second.
     *
     *  The engine's own ceiling is a HARD cap: an opener above it would be
     *  split by the chunker (defeating the point of a fast opener) and would
     *  exceed the model's token budget. */
    fun openerCharacterBudget(
        targetSeconds: Double,
        rate: Double,
        batchCeiling: Int,
    ): Int {
        val effectiveRate = if (rate > 0) rate else 1.0
        val raw = targetSeconds * defaultCharactersPerAudioSecond * effectiveRate
        val wanted = max(1, ceil(raw).toInt())
        return min(wanted, max(1, batchCeiling))
    }

    private fun clampTarget(value: Double): Double =
        min(max(value, minimumTargetSeconds), maximumTargetSeconds)
}
