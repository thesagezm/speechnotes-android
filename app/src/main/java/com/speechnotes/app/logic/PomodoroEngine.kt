package com.speechnotes.app.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * The Pomodoro engine, ported verbatim from
 * Packages/SpeechLogic/Sources/SpeechLogic/PomodoroEngine.swift
 * (speechnotes-ios, commits 484e564 + e07841f). Pure Kotlin — no android.*
 * — and every wall-clock boundary is an epoch-seconds double (the Swift
 * TimeInterval model), so a killed app reconciles from the clock alone.
 *
 * Modes:
 * - [PomodoroMode.STRUCTURED]: one fixed window (a duration or a finish
 *   wall-clock time) with N evenly-spaced breaks carved out of it. The
 *   window is absolute — pauses shift it, skips do not pull the finish
 *   earlier.
 * - [PomodoroMode.FLOWMODORO]: open-ended focus; every 5 focus minutes bank
 *   1 break minute, claimable once 3 BREAK minutes are banked (15 focus
 *   minutes).
 * - [PomodoroMode.CLASSIC]: hand-tuned focus blocks and breaks, each with
 *   its own length.
 *
 * JSON field names are shared with the iOS types so state can move between
 * platforms (the persisted file names match PomodoroStore.swift's).
 */

// MARK: - Modes

@Serializable
enum class PomodoroMode {
    @SerialName("structured") STRUCTURED,
    @SerialName("flowmodoro") FLOWMODORO,
    @SerialName("classic") CLASSIC,
}

// MARK: - Phases

/** `rest` (encoded "rest") is the break phase — spelled around keywords. */
@Serializable
enum class PomodoroPhase {
    @SerialName("prep") PREP,
    @SerialName("focus") FOCUS,
    @SerialName("rest") REST,
    @SerialName("done") DONE,
}

// MARK: - Config

/** A full description of a timer run. The running session snapshots its
 *  config at start, so editing preferences never bends a live timer. */
@Serializable
data class PomodoroConfig(
    val mode: PomodoroMode = PomodoroMode.CLASSIC,
    /** Mandatory prep window before the first focus phase, seconds.
     *  Clamped 120...300 (2–5 min) by the editor; not skippable. */
    var prepSeconds: Int = 180,
    // Structured
    /** true → the window is a duration from start; false → a wall-clock
     *  finish time (hour/minute below, next occurrence). */
    var structuredByDuration: Boolean = true,
    var structuredDurationSeconds: Int = 3 * 3600,
    var structuredFinishHour: Int = 17,
    var structuredFinishMinute: Int = 0,
    var structuredBreakCount: Int = 3,
    var structuredBreakSeconds: Int = 5 * 60,
    // Classic — per-block/per-break minutes, adjustable individually.
    /** Focus block lengths in minutes; the count of blocks IS the list size. */
    var classicFocusMinutes: MutableList<Int> = mutableListOf(25, 25, 25, 25),
    /** Break lengths in minutes; a break follows each block while entries
     *  remain (breaks.count <= blocks keeps them strictly in between). */
    var classicBreakMinutes: MutableList<Int> = mutableListOf(5, 5, 5),
) {
    /** The absolute end (epoch seconds) of a structured window that starts
     *  at [startEpoch] — either start + duration, or the next occurrence of
     *  the finish wall-clock time (today if still ahead, otherwise tomorrow).
     *
     *  Divergence note: Swift's `nextDate(after:matching:.nextTime)` also
     *  skips wall times that do not exist (DST spring-forward); Calendar's
     *  normalization is close enough for a finish-time picker and pinned by
     *  the same UTC tests. */
    fun structuredWindowEnd(afterEpoch: Double, calendar: Calendar): Double {
        if (structuredByDuration) return afterEpoch + structuredDurationSeconds
        val cal = calendar.clone() as Calendar
        cal.timeInMillis = (afterEpoch * 1000).toLong()
        cal.set(Calendar.HOUR_OF_DAY, structuredFinishHour)
        cal.set(Calendar.MINUTE, structuredFinishMinute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= (afterEpoch * 1000).toLong()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis / 1000.0
    }

    /** Focus seconds per structured segment for a window of [window] seconds,
     *  or null when the window cannot hold prep + the breaks + at least one
     *  focus minute per segment. Residual seconds land in the final segment
     *  (the timeline builder ends it exactly at the window end). */
    fun structuredSegmentSeconds(window: Double): Int? {
        val fixed = prepSeconds + structuredBreakCount * structuredBreakSeconds
        val segments = structuredBreakCount + 1
        val focus = window.toInt() - fixed
        if (focus < segments * 60) return null
        return focus / segments
    }
}

// MARK: - Timeline steps

/** A scheduled step. `end` is absolute for structured AND classic (classic's
 *  chained durations are resolved to dates at start, and pauses slide every
 *  not-yet-reached end) — null only for the open-ended flowmodoro focus,
 *  which lives outside the timeline entirely. */
@Serializable
data class PomodoroStep(
    val phase: PomodoroPhase,
    var end: Double?,
)

// MARK: - Events

/** One phase that just completed, surfaced so the store can fold focus
 *  seconds into history and fire banners/haptics. */
data class PomodoroEvent(
    val completedPhase: PomodoroPhase,
    /** How long the completed phase ran, seconds. Focus events feed the
     *  day log; partial focus (stop/skip mid-block) is included. */
    val seconds: Double,
)

// MARK: - Session

/** The live timer, as a plain @Serializable value. All time math is anchored
 *  to wall-clock instants, never to tick counts, so the session survives the
 *  app being backgrounded or killed: on relaunch the store feeds the current
 *  instant to [advanceIfDue] and every phase that elapsed while away is
 *  reconciled (and logged) from the timeline alone. */
@Serializable
data class PomodoroSession(
    val config: PomodoroConfig,
    val startedAt: Double,
    /** Current phase; DONE once the timeline (or a stop) has run out. */
    var phase: PomodoroPhase = PomodoroPhase.PREP,
    /** Start of the current phase (null when done). */
    var phaseStart: Double? = startedAt,
    /** End of the current phase — null for the open-ended flowmodoro focus
     *  and for DONE. */
    var phaseEnd: Double? = startedAt + config.prepSeconds,
    /** Structured/classic: the absolute timeline, with [stepIndex] pointing
     *  at the current step. Flowmodoro keeps this empty. */
    var steps: List<PomodoroStep> = emptyList(),
    var stepIndex: Int = 0,
    /** Non-null while paused: the instant the freeze began. Every boundary
     *  slides by the paused span on resume. */
    var pausedAt: Double? = null,
    /** Structured only: the absolute window end (shifts with pauses; display
     *  uses it, the math uses the step ends). */
    var windowEnd: Double? = null,
    // Flowmodoro bookkeeping.
    /** Focus seconds banked toward the next break, EXCLUDING the live stint. */
    var flowBanked: Double = 0.0,
    /** When the current focus stint began (null while resting/paused). */
    var flowStintStart: Double? = null,
    var flowBreaksClaimed: Int = 0,
) {
    companion object {
        // Flowmodoro constants (the rule is the mode — not configurable).

        /** Focus seconds that earn one break minute. */
        const val FLOW_UNIT_FOCUS: Double = 300.0

        /** BANKED BREAK seconds before a break may be claimed at all. The
         *  gate is on the POT, not on focus: 3 break minutes = 15 focus
         *  minutes. (The first rule unlocked the claim at 3 focus minutes,
         *  which offered a 36-second break nobody would take — the user
         *  asked for the claim to wait until the break itself is worth
         *  taking.) */
        const val FLOW_CLAIM_THRESHOLD: Double = 180.0

        /** The full structured/classic timeline: prep, then focus blocks
         *  with breaks interleaved. Structured ends exactly at the window
         *  end; classic runs purely off the configured lengths. */
        fun buildTimeline(
            config: PomodoroConfig,
            startEpoch: Double,
            calendar: Calendar,
        ): List<PomodoroStep> {
            val steps = ArrayList<PomodoroStep>()
            var t = startEpoch
            if (config.prepSeconds > 0) {
                t = startEpoch + config.prepSeconds
                steps.add(PomodoroStep(PomodoroPhase.PREP, t))
            }
            when (config.mode) {
                PomodoroMode.FLOWMODORO -> return steps
                PomodoroMode.CLASSIC -> {
                    for ((i, minutes) in config.classicFocusMinutes.withIndex()) {
                        t += minutes * 60
                        steps.add(PomodoroStep(PomodoroPhase.FOCUS, t))
                        if (i < config.classicBreakMinutes.size) {
                            t += config.classicBreakMinutes[i] * 60
                            steps.add(PomodoroStep(PomodoroPhase.REST, t))
                        }
                    }
                }
                PomodoroMode.STRUCTURED -> {
                    val windowEnd = config.structuredWindowEnd(startEpoch, calendar)
                    val segment = config.structuredSegmentSeconds(windowEnd - startEpoch)
                        ?: return steps // invalid window — the editor blocks starting it
                    repeat(config.structuredBreakCount) {
                        t += segment
                        steps.add(PomodoroStep(PomodoroPhase.FOCUS, t))
                        t += config.structuredBreakSeconds
                        steps.add(PomodoroStep(PomodoroPhase.REST, t))
                    }
                    steps.add(PomodoroStep(PomodoroPhase.FOCUS, windowEnd))
                }
            }
            return steps
        }

        /** The Swift `PomodoroSession(config:start:calendar:)` initializer:
         *  a fresh session opens in PREP, then schedules the mode's timeline
         *  (or flowmodoro's open-ended focus). The bare constructor — used
         *  by the JSON decoder only — restores the persisted fields as-is. */
        fun start(config: PomodoroConfig, startEpoch: Double, calendar: Calendar): PomodoroSession {
            val session = PomodoroSession(config = config, startedAt = startEpoch)
            if (config.mode == PomodoroMode.FLOWMODORO) {
                // Open-ended focus after prep — nothing to schedule.
                if (config.prepSeconds <= 0) session.startFlowFocus(at = startEpoch)
                return session
            }
            if (config.prepSeconds <= 0) session.phaseEnd = startEpoch
            session.steps = buildTimeline(config, startEpoch, calendar)
            session.windowEnd = if (config.mode == PomodoroMode.STRUCTURED) {
                config.structuredWindowEnd(startEpoch, calendar)
            } else null
            session.syncCurrentStep(fallbackNow = startEpoch)
            return session
        }
    }

    /** The instant time is measured against: a paused session is frozen at
     *  the moment it paused, whatever the clock says now. */
    private fun anchor(now: Double): Double = pausedAt ?: now

    val isPaused: Boolean get() = pausedAt != null
    val isRunning: Boolean get() = phase != PomodoroPhase.DONE

    /** Seconds left in the current phase at [now] — null for the open-ended
     *  flowmodoro focus and when done. Countdowns round up. */
    fun secondsRemaining(now: Double): Double? {
        val end = phaseEnd ?: return null
        return maxOf(0.0, end - anchor(now))
    }

    /** 0...1 fill for the glass. Fixed-length phases fill by elapsed share;
     *  the open-ended flowmodoro focus fills cyclically toward the next
     *  earned break minute (300 s per lap). */
    fun phaseProgress(now: Double): Double {
        // Snapshot the boundaries: they are mutable properties, so Kotlin
        // cannot smart-cast them inside the branch — and a `!!` here would
        // be a crash if a phase boundary ever raced this read.
        val start = phaseStart
        val end = phaseEnd
        return when {
            start != null && end != null -> {
                val total = end - start
                // A degenerate step (start >= end — a corrupted or raced
                // boundary) must NOT paint the glass full: that is exactly
                // the "stuck at 100% while the countdown still runs" report.
                // An empty fill beside a live countdown reads as a cosmetic
                // glitch; a full one reads as finished, which is a lie.
                if (total <= 0) return 0.0
                val elapsed = total.coerceAtMost(maxOf(0.0, anchor(now) - start))
                elapsed / total
            }
            start != null && end == null -> {
                val unit = FLOW_UNIT_FOCUS
                if (unit <= 0) return 0.0
                val accrued = flowAccruedFocus(now)
                val lap = accrued % unit
                if (lap < 0) 0.0 else lap / unit
            }
            else -> 0.0
        }
    }

    /** Flowmodoro: focus seconds banked toward the next break, including the
     *  live stint (frozen while paused). */
    fun flowAccruedFocus(now: Double): Double {
        var accrued = flowBanked
        if (phase == PomodoroPhase.FOCUS) {
            flowStintStart?.let { stint ->
                accrued += maxOf(0.0, anchor(now) - stint)
            }
        }
        return maxOf(0.0, accrued)
    }

    /** Break seconds currently earned: 1 minute per 5 focus minutes. */
    fun flowEarnedBreak(now: Double): Double =
        flowAccruedFocus(now) / (FLOW_UNIT_FOCUS / 60.0)

    fun flowCanClaim(now: Double): Boolean =
        config.mode == PomodoroMode.FLOWMODORO && phase == PomodoroPhase.FOCUS && pausedAt == null &&
            flowEarnedBreak(now) >= FLOW_CLAIM_THRESHOLD

    // MARK: Transitions

    /** Completes every phase whose end has passed by [now] and lands in the
     *  step that contains it — one call reconciles both a normal tick and a
     *  relaunch after the app was gone for hours. Returns the completed
     *  phases in order (durations included) for logging. */
    fun advanceIfDue(now: Double): List<PomodoroEvent> {
        if (pausedAt != null || phase == PomodoroPhase.DONE) return emptyList()

        if (config.mode == PomodoroMode.FLOWMODORO) {
            val end = phaseEnd ?: return emptyList()
            if (now < end) return emptyList()
            return when (phase) {
                PomodoroPhase.PREP -> {
                    val seconds = end - (phaseStart ?: end)
                    startFlowFocus(at = end)
                    listOf(PomodoroEvent(PomodoroPhase.PREP, seconds))
                }
                PomodoroPhase.REST -> {
                    val seconds = end - (phaseStart ?: end)
                    startFlowFocus(at = end)
                    listOf(PomodoroEvent(PomodoroPhase.REST, seconds))
                }
                else -> emptyList() // open-ended focus has no end to pass
            }
        }

        val events = ArrayList<PomodoroEvent>()
        while (stepIndex < steps.size) {
            val end = steps[stepIndex].end ?: break
            if (now < end) break
            val start = if (stepIndex == 0) startedAt else (steps[stepIndex - 1].end ?: startedAt)
            events.add(PomodoroEvent(steps[stepIndex].phase, end - start))
            stepIndex += 1
        }
        syncCurrentStep(fallbackNow = now)
        return events
    }

    /** Points phase/phaseEnd at steps[stepIndex]; done when the timeline is
     *  exhausted. (Flowmodoro never has steps — untouched.) */
    private fun syncCurrentStep(fallbackNow: Double) {
        if (stepIndex >= steps.size) {
            phase = PomodoroPhase.DONE
            phaseStart = null
            phaseEnd = null
            return
        }
        phase = steps[stepIndex].phase
        phaseEnd = steps[stepIndex].end
        phaseStart = if (stepIndex == 0) startedAt else (steps[stepIndex - 1].end ?: fallbackNow)
    }

    /** Flowmodoro: turn accrued focus into a break now. The break lasts the
     *  whole pot (1 min per 5 focus min) and the accumulator resets — focus
     *  starts from zero after it. The claim only unlocks once the pot holds
     *  FLOW_CLAIM_THRESHOLD (3 break minutes = 15 focus minutes). */
    fun claimBreak(now: Double): Boolean {
        if (config.mode != PomodoroMode.FLOWMODORO || phase != PomodoroPhase.FOCUS ||
            pausedAt != null || flowEarnedBreak(now) < FLOW_CLAIM_THRESHOLD
        ) return false
        val rest = flowEarnedBreak(now)
        flowBanked = 0.0
        flowStintStart = null
        flowBreaksClaimed += 1
        phase = PomodoroPhase.REST
        phaseStart = now
        phaseEnd = now + rest
        return true
    }

    /** End the current phase immediately.
     *
     *  - structured: the window is absolute, so later boundaries stay put —
     *    only the skipped phase ends early.
     *  - classic: the schedule slides — everything after the skipped phase
     *    pulls forward by the reclaimed time.
     *  - flowmodoro: only a rest can be skipped (focus has no "next" without
     *    a claim); focus resumes immediately. */
    fun skipPhase(now: Double): Boolean {
        if (phase == PomodoroPhase.DONE || pausedAt != null) return false
        val end = phaseEnd
        if (end != null && now >= end) return false
        return when (config.mode) {
            PomodoroMode.FLOWMODORO -> {
                if (phase != PomodoroPhase.REST) return false
                startFlowFocus(at = now)
                true
            }
            PomodoroMode.STRUCTURED -> {
                if (stepIndex >= steps.size) return false
                steps[stepIndex].end = now
                true
            }
            PomodoroMode.CLASSIC -> {
                if (stepIndex >= steps.size) return false
                val stepEnd = steps[stepIndex].end ?: return false
                val delta = stepEnd - now
                steps[stepIndex].end = now
                if (stepIndex + 1 < steps.size) {
                    for (i in (stepIndex + 1) until steps.size) {
                        steps[i].end = steps[i].end?.plus(-delta)
                    }
                }
                true
            }
        }
    }

    /** Freeze the clock. Flow focus banks the live stint first so accrual
     *  stops exactly at the pause. */
    fun pause(now: Double): Boolean {
        if (phase == PomodoroPhase.DONE || pausedAt != null) return false
        if (config.mode == PomodoroMode.FLOWMODORO && phase == PomodoroPhase.FOCUS) {
            flowStintStart?.let { stint ->
                flowBanked += maxOf(0.0, now - stint)
                flowStintStart = null
            }
        }
        pausedAt = now
        return true
    }

    /** Slide every pending boundary by the paused span and unfreeze. */
    fun resume(now: Double): Boolean {
        val paused = pausedAt ?: return false
        val delta = maxOf(0.0, now - paused)
        pausedAt = null
        if (delta <= 0) return true
        phaseEnd = phaseEnd?.plus(delta)
        windowEnd = windowEnd?.plus(delta)
        for (i in stepIndex until steps.size) {
            steps[i].end = steps[i].end?.plus(delta)
        }
        if (config.mode == PomodoroMode.FLOWMODORO && phase == PomodoroPhase.FOCUS) {
            flowStintStart = now
        }
        return true
    }

    /** End the timer now. The phase in flight is reported with its partial
     *  duration so the store can fold focus seconds into the day log before
     *  dropping the session. */
    fun stop(now: Double): PomodoroEvent? {
        if (phase == PomodoroPhase.DONE) return null
        val stoppedPhase = phase
        val seconds: Double = if (config.mode == PomodoroMode.FLOWMODORO && phase == PomodoroPhase.FOCUS) {
            flowAccruedFocus(now)
        } else {
            val start = phaseStart
            if (start != null) {
                val end = minOf(phaseEnd ?: now, anchor(now))
                maxOf(0.0, end - start)
            } else 0.0
        }
        phase = PomodoroPhase.DONE
        phaseStart = null
        phaseEnd = null
        pausedAt = null
        return PomodoroEvent(stoppedPhase, seconds)
    }

    private fun startFlowFocus(at: Double) {
        phase = PomodoroPhase.FOCUS
        phaseStart = at
        phaseEnd = null
        flowStintStart = at
    }

    /** Break length that FOLLOWS the current focus phase, for copy —
     *  structured: the configured break; classic: the matching break entry;
     *  flow: the current pot. (PomodoroStore.swift's nextRestSeconds.) */
    fun nextRestSeconds(): Double {
        if (config.mode == PomodoroMode.FLOWMODORO) return 0.0
        if (stepIndex + 1 >= steps.size) return 0.0
        val next = steps[stepIndex + 1]
        if (next.phase != PomodoroPhase.REST) return 0.0
        val restEnd = next.end ?: return 0.0
        val focusEnd = phaseEnd ?: nowEpochSeconds()
        return restEnd - focusEnd
    }
}

/** Wall-clock now as the epoch-seconds double the engine speaks. */
fun nowEpochSeconds(): Double = System.currentTimeMillis() / 1000.0
