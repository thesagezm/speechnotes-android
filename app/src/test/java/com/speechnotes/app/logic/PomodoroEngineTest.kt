package com.speechnotes.app.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The Pomodoro engine's date math. Ported from
 * SpeechLogicTests/PomodoroEngineTests.swift (speechnotes-ios) — every
 * pinned behaviour preserved. Everything runs on a UTC calendar so
 * finish-time windows are deterministic; `t0` is a fixed instant and every
 * assertion names its offset from it.
 */
class PomodoroEngineTest {

    private fun utcCalendar(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            firstDayOfWeek = Calendar.MONDAY
        }

    /// 2026-03-10 14:00:00 UTC.
    private val t0: Double = epochSeconds(hour = 14)

    private fun epochSeconds(hour: Int, minute: Int = 0, day: Int = 10): Double {
        val cal = utcCalendar()
        cal.set(2026, Calendar.MARCH, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000.0
    }

    private fun date(hour: Int, minute: Int = 0, day: Int = 10): Double = epochSeconds(hour, minute, day)

    private fun seconds(n: Double): Double = t0 + n

    /** `secondsRemaining` is optional (open-ended flow focus / done return
     *  null); tests expect a real number, so null degrades to -1 and fails
     *  the assertion loudly instead of the type checker. */
    private fun secs(remaining: Double?): Double = remaining ?: -1.0

    // MARK: Classic

    /** Blocks [25, 30] + breaks [5, 15] (breaks == blocks → trailing break)
     *  + 2 min prep. The whole run walks through every phase in order and
     *  each event carries the configured duration. */
    @Test
    fun classicFullRun() {
        val config = PomodoroConfig(mode = PomodoroMode.CLASSIC, prepSeconds = 120).apply {
            classicFocusMinutes = mutableListOf(25, 30)
            classicBreakMinutes = mutableListOf(5, 15)
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())

        assertEquals(PomodoroPhase.PREP, session.phase)
        assertEquals(120.0, secs(session.secondsRemaining(t0)), 0.001)

        // Prep ends → first focus block.
        var events = session.advanceIfDue(seconds(120.0))
        assertEquals(PomodoroPhase.FOCUS, session.phase)
        assertEquals(listOf(PomodoroPhase.PREP), events.map { it.completedPhase })

        // Mid first block, nothing due.
        assertTrue(session.advanceIfDue(seconds(600.0)).isEmpty())
        assertEquals(1500.0 - 480.0, secs(session.secondsRemaining(seconds(600.0))), 0.001)

        // First block completes at 120 + 1500.
        events = session.advanceIfDue(seconds(1620.0))
        assertEquals(listOf(PomodoroPhase.FOCUS), events.map { it.completedPhase })
        assertEquals(1500.0, events.firstOrNull()?.seconds ?: 0.0, 0.001)
        assertEquals(PomodoroPhase.REST, session.phase)
        assertEquals(300.0, secs(session.secondsRemaining(seconds(1620.0))), 0.001)

        // Break then second (longer) block.
        events = session.advanceIfDue(seconds(1920.0))
        assertEquals(listOf(PomodoroPhase.REST), events.map { it.completedPhase })
        assertEquals(1800.0, secs(session.secondsRemaining(seconds(1920.0))), 0.001)

        events = session.advanceIfDue(seconds(1920.0 + 1800))
        assertEquals(listOf(PomodoroPhase.FOCUS), events.map { it.completedPhase })
        assertEquals(1800.0, events.firstOrNull()?.seconds ?: 0.0, 0.001)
        assertEquals(PomodoroPhase.REST, session.phase)
        assertEquals(900.0, secs(session.secondsRemaining(seconds(3720.0))), 0.001)

        // Trailing break runs out → done.
        events = session.advanceIfDue(seconds(4620.0))
        assertEquals(listOf(PomodoroPhase.REST), events.map { it.completedPhase })
        assertEquals(PomodoroPhase.DONE, session.phase)
        assertNull(session.phaseEnd)
        assertTrue(session.advanceIfDue(seconds(99999.0)).isEmpty())
    }

    /** Fewer breaks than blocks → no break after the final block. */
    @Test
    fun classicFewerBreaksThanBlocks() {
        val config = PomodoroConfig(mode = PomodoroMode.CLASSIC, prepSeconds = 0).apply {
            classicFocusMinutes = mutableListOf(25, 25)
            classicBreakMinutes = mutableListOf(5)
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())

        assertEquals(listOf(PomodoroPhase.FOCUS, PomodoroPhase.REST, PomodoroPhase.FOCUS), session.steps.map { it.phase })
        session.advanceIfDue(seconds(1500.0))
        session.advanceIfDue(seconds(1800.0))
        val events = session.advanceIfDue(seconds(3300.0))
        assertEquals(listOf(PomodoroPhase.FOCUS), events.map { it.completedPhase })
        assertEquals(PomodoroPhase.DONE, session.phase)
    }

    /** A relaunch after the app was gone for hours reconciles every elapsed
     *  phase in one call and lands in the step that contains "now". */
    @Test
    fun classicReconcileAfterLongGap() {
        val config = PomodoroConfig(mode = PomodoroMode.CLASSIC, prepSeconds = 120).apply {
            classicFocusMinutes = mutableListOf(25)
            classicBreakMinutes = mutableListOf(5)
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())

        // Gone for 40 minutes: prep (2) + focus (25) + break (5) all elapsed,
        // now 8 minutes into nothing — the timeline is exhausted.
        val events = session.advanceIfDue(seconds(40.0 * 60))
        assertEquals(listOf(PomodoroPhase.PREP, PomodoroPhase.FOCUS, PomodoroPhase.REST), events.map { it.completedPhase })
        assertEquals(1500.0, events[1].seconds, 0.001)
        assertEquals(PomodoroPhase.DONE, session.phase)
    }

    // MARK: Structured

    /** 3 h window, 2 min prep, 3 breaks × 5 min → four equal focus segments
     *  of (10800 − 120 − 900) / 4 = 2445 s, and the final focus ends exactly
     *  at the window end. */
    @Test
    fun structuredDurationTimeline() {
        val config = PomodoroConfig(mode = PomodoroMode.STRUCTURED, prepSeconds = 120).apply {
            structuredByDuration = true
            structuredDurationSeconds = 3 * 3600
            structuredBreakCount = 3
            structuredBreakSeconds = 300
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())

        assertEquals(
            listOf(
                PomodoroPhase.PREP, PomodoroPhase.FOCUS, PomodoroPhase.REST, PomodoroPhase.FOCUS,
                PomodoroPhase.REST, PomodoroPhase.FOCUS, PomodoroPhase.REST, PomodoroPhase.FOCUS,
            ),
            session.steps.map { it.phase },
        )
        assertEquals(t0 + 10800, session.windowEnd!!, 0.001)
        assertEquals(t0 + 10800, session.steps.last().end!!, 0.001)

        // Walk the whole window; the focus events must sum to the focus pool.
        val events = session.advanceIfDue(t0 + 10800)
        assertEquals(
            listOf(
                PomodoroPhase.PREP, PomodoroPhase.FOCUS, PomodoroPhase.REST, PomodoroPhase.FOCUS,
                PomodoroPhase.REST, PomodoroPhase.FOCUS, PomodoroPhase.REST, PomodoroPhase.FOCUS,
            ),
            events.map { it.completedPhase },
        )
        val focusSeconds = events.filter { it.completedPhase == PomodoroPhase.FOCUS }.sumOf { it.seconds }
        assertEquals(9780.0, focusSeconds, 0.001)
        assertEquals(10800.0, events.sumOf { it.seconds }, 0.001)
        assertEquals(PomodoroPhase.DONE, session.phase)
    }

    /** Finish-time mode resolves to the next occurrence of the wall-clock
     *  time: same day when still ahead, tomorrow when already past. */
    @Test
    fun structuredFinishTimeWindow() {
        val config = PomodoroConfig(mode = PomodoroMode.STRUCTURED, prepSeconds = 120).apply {
            structuredByDuration = false
            structuredFinishHour = 17
        }

        // Started 14:00, finish 17:00 → same-day window of 3 h.
        var end = config.structuredWindowEnd(date(14), utcCalendar())
        assertEquals(date(17), end, 0.001)
        // Started 18:00 → tomorrow 17:00.
        end = config.structuredWindowEnd(date(18), utcCalendar())
        assertEquals(date(17, day = 11), end, 0.001)
    }

    /** A window too small for prep + breaks + a minute of focus per segment
     *  builds no timeline beyond prep and refuses to segment. */
    @Test
    fun structuredTooSmallWindow() {
        val config = PomodoroConfig(mode = PomodoroMode.STRUCTURED, prepSeconds = 300).apply {
            structuredByDuration = true
            structuredDurationSeconds = 1200
            structuredBreakCount = 5
            structuredBreakSeconds = 600
        }
        assertNull(config.structuredSegmentSeconds(1200.0))
        val session = PomodoroSession.start(config, t0, utcCalendar())
        assertEquals(listOf(PomodoroPhase.PREP), session.steps.map { it.phase })
    }

    /** Skipping a structured phase ends it now but leaves the later
     *  boundaries absolute — the finish time does not move. */
    @Test
    fun structuredSkipKeepsWindow() {
        val config = PomodoroConfig(mode = PomodoroMode.STRUCTURED, prepSeconds = 0).apply {
            structuredBreakCount = 1
            structuredBreakSeconds = 300
            structuredDurationSeconds = 3600
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())
        // segment = (3600 − 300) / 2 = 1650

        assertTrue(session.skipPhase(seconds(600.0)))
        val events = session.advanceIfDue(seconds(600.0))
        assertEquals(listOf(PomodoroPhase.FOCUS), events.map { it.completedPhase })
        assertEquals(600.0, events.firstOrNull()?.seconds ?: 0.0, 0.001)
        assertEquals(PomodoroPhase.REST, session.phase)
        // The break still ends on the original grid (t0 + 1950)…
        assertEquals(t0 + 1950, session.phaseEnd!!, 0.001)
        // …and the final focus still ends exactly at the window end.
        assertEquals(t0 + 3600, session.steps.last().end!!, 0.001)
    }

    // MARK: Pause / resume

    @Test
    fun pauseSlidesCurrentAndFutureBoundaries() {
        val config = PomodoroConfig(mode = PomodoroMode.CLASSIC, prepSeconds = 0).apply {
            classicFocusMinutes = mutableListOf(25)
            classicBreakMinutes = mutableListOf(5)
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())

        // Ten minutes into the block, pause for five.
        assertTrue(session.pause(seconds(600.0)))
        assertTrue(session.isPaused)
        assertFalse(session.pause(seconds(610.0))) // double pause is a no-op
        assertEquals(900.0, secs(session.secondsRemaining(seconds(3600.0))), 0.001) // frozen

        assertTrue(session.resume(seconds(900.0)))
        assertEquals(900.0, secs(session.secondsRemaining(seconds(900.0))), 0.001)
        // The following break slides by the paused span too.
        assertEquals(t0 + 1800 + 300, session.steps[1].end!!, 0.001)
    }

    // MARK: Flowmodoro

    @Test
    fun flowmodoroAccrualAndClaim() {
        val config = PomodoroConfig(mode = PomodoroMode.FLOWMODORO, prepSeconds = 120)
        val session = PomodoroSession.start(config, t0, utcCalendar())

        // Prep → open-ended focus.
        var events = session.advanceIfDue(seconds(120.0))
        assertEquals(listOf(PomodoroPhase.PREP), events.map { it.completedPhase })
        assertEquals(PomodoroPhase.FOCUS, session.phase)
        assertNull(session.phaseEnd)

        // Below the claim threshold: the pot is earned but not claimable.
        // The gate is on the POT — 3 break minutes = 15 focus minutes — so
        // 3 focus minutes (a 36-second pot) is earned but far from claimable.
        assertFalse(session.flowCanClaim(seconds(120.0 + 180)))
        assertEquals(36.0, session.flowEarnedBreak(seconds(120.0 + 180)), 0.001)
        assertFalse(session.flowCanClaim(seconds(120.0 + 899)))
        assertTrue(session.flowCanClaim(seconds(120.0 + 900)))
        assertEquals(180.0, session.flowEarnedBreak(seconds(120.0 + 900)), 0.001)

        // Fifteen focus minutes → a 3-minute pot; claiming banks it and restarts focus.
        events = session.advanceIfDue(seconds(120.0 + 900))
        assertTrue(events.isEmpty()) // focus never "completes" on its own
        assertTrue(session.claimBreak(seconds(120.0 + 900)))
        assertEquals(PomodoroPhase.REST, session.phase)
        assertEquals(180.0, secs(session.secondsRemaining(seconds(120.0 + 900))), 0.001)
        assertEquals(0.0, session.flowAccruedFocus(seconds(120.0 + 900)), 0.001)
        assertEquals(1, session.flowBreaksClaimed)

        // Rest runs out → focus restarts from zero.
        events = session.advanceIfDue(seconds(120.0 + 1080))
        assertEquals(listOf(PomodoroPhase.REST), events.map { it.completedPhase })
        assertEquals(180.0, events.firstOrNull()?.seconds ?: 0.0, 0.001)
        assertEquals(PomodoroPhase.FOCUS, session.phase)
        assertEquals(0.0, session.flowEarnedBreak(seconds(120.0 + 1080)), 0.001)
    }

    @Test
    fun flowmodoroPauseStopsAccrual() {
        val config = PomodoroConfig(mode = PomodoroMode.FLOWMODORO, prepSeconds = 0)
        val session = PomodoroSession.start(config, t0, utcCalendar())

        // 4 minutes of focus, then a 3-minute pause: the pot freezes at 4 min.
        session.pause(seconds(240.0))
        assertEquals(240.0, session.flowAccruedFocus(seconds(420.0)), 0.001)
        session.resume(seconds(420.0))
        assertEquals(240.0, session.flowAccruedFocus(seconds(420.0)), 0.001)
        assertEquals(300.0, session.flowAccruedFocus(seconds(480.0)), 0.001)

        // A paused focus cannot be claimed even once the pot is full:
        // accrue to exactly 15 focus minutes ACROSS the earlier pause
        // (240 s + 660 s of live focus), freeze, then try to claim
        // mid-pause — false however long the pause runs.
        session.pause(seconds(1080.0))
        assertEquals(900.0, session.flowAccruedFocus(seconds(1200.0)), 0.001)
        assertEquals(180.0, session.flowEarnedBreak(seconds(1200.0)), 0.001)
        assertFalse(session.claimBreak(seconds(1200.0)))
        // Resuming unlocks it: the pot is exactly at the threshold and the
        // claim lands there.
        session.resume(seconds(1200.0))
        assertTrue(session.flowCanClaim(seconds(1200.0)))
        assertTrue(session.claimBreak(seconds(1200.0)))
        assertEquals(180.0, secs(session.secondsRemaining(seconds(1200.0))), 0.001)
    }

    @Test
    fun flowmodoroSkipRest() {
        val config = PomodoroConfig(mode = PomodoroMode.FLOWMODORO, prepSeconds = 0)
        val session = PomodoroSession.start(config, t0, utcCalendar())

        session.advanceIfDue(seconds(900.0))
        assertTrue(session.claimBreak(seconds(900.0))) // 3 min rest
        assertTrue(session.skipPhase(seconds(930.0))) // 30 s into the rest
        assertEquals(PomodoroPhase.FOCUS, session.phase)
        assertEquals(0.0, session.flowAccruedFocus(seconds(930.0)), 0.001)
        // Focus in structured/classic has no "next" to skip to in flow mode.
        assertFalse(session.skipPhase(seconds(960.0)))
    }

    // MARK: Skip

    /** Skipping a classic phase slides the rest of the schedule earlier. */
    @Test
    fun classicSkipSlidesSchedule() {
        val config = PomodoroConfig(mode = PomodoroMode.CLASSIC, prepSeconds = 0).apply {
            classicFocusMinutes = mutableListOf(25, 25)
            classicBreakMinutes = mutableListOf(5, 5)
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())

        // Skip 15 minutes into the first block → every later boundary pulls
        // forward by the reclaimed 10 minutes.
        assertTrue(session.skipPhase(seconds(900.0)))
        val events = session.advanceIfDue(seconds(900.0))
        assertEquals(listOf(PomodoroPhase.FOCUS), events.map { it.completedPhase })
        assertEquals(900.0, events.firstOrNull()?.seconds ?: 0.0, 0.001)
        assertEquals(PomodoroPhase.REST, session.phase)
        assertEquals(t0 + 1200, session.phaseEnd!!, 0.001) // was 1800
        assertEquals(t0 + 2700, session.steps[2].end!!, 0.001) // was 3300
        assertEquals(t0 + 3000, session.steps[3].end!!, 0.001) // was 3600
    }

    // MARK: Stop

    @Test
    fun stopMidFocusReportsPartial() {
        val config = PomodoroConfig(mode = PomodoroMode.CLASSIC, prepSeconds = 0).apply {
            classicFocusMinutes = mutableListOf(25)
            classicBreakMinutes = mutableListOf()
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())

        val event = session.stop(seconds(600.0))
        assertEquals(PomodoroPhase.FOCUS, event?.completedPhase)
        assertEquals(600.0, event?.seconds ?: 0.0, 0.001)
        assertEquals(PomodoroPhase.DONE, session.phase)
        assertNull(session.stop(seconds(700.0))) // already done
    }

    @Test
    fun stopMidPrep() {
        val config = PomodoroConfig(mode = PomodoroMode.CLASSIC, prepSeconds = 120).apply {
            classicFocusMinutes = mutableListOf(25)
            classicBreakMinutes = mutableListOf()
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())
        val event = session.stop(seconds(60.0))
        assertEquals(PomodoroPhase.PREP, event?.completedPhase)
        assertEquals(60.0, event?.seconds ?: 0.0, 0.001)
    }

    @Test
    fun stopFlowFocusCountsAccrued() {
        val config = PomodoroConfig(mode = PomodoroMode.FLOWMODORO, prepSeconds = 0)
        val session = PomodoroSession.start(config, t0, utcCalendar())
        val event = session.stop(seconds(360.0)) // 6 min of focus in flight
        assertEquals(PomodoroPhase.FOCUS, event?.completedPhase)
        assertEquals(360.0, event?.seconds ?: 0.0, 0.001)
    }

    // MARK: Progress + persistence

    @Test
    fun phaseProgressBounds() {
        val config = PomodoroConfig(mode = PomodoroMode.CLASSIC, prepSeconds = 120).apply {
            classicFocusMinutes = mutableListOf(25)
            classicBreakMinutes = mutableListOf()
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())

        assertEquals(0.0, session.phaseProgress(t0), 0.001)
        assertEquals(0.5, session.phaseProgress(seconds(60.0)), 0.001)
        session.advanceIfDue(seconds(120.0))
        assertEquals(0.5, session.phaseProgress(seconds(870.0)), 0.001) // 750 of 1500 s
        assertEquals(1.0, session.phaseProgress(seconds(120.0 + 1500)), 0.001)
    }

    @Test
    fun flowProgressCyclesPerEarnedMinute() {
        val config = PomodoroConfig(mode = PomodoroMode.FLOWMODORO, prepSeconds = 0)
        val session = PomodoroSession.start(config, t0, utcCalendar())
        session.advanceIfDue(t0)
        // Halfway through the first 5-minute earning unit.
        assertEquals(0.5, session.phaseProgress(seconds(150.0)), 0.001)
        // Into the second unit: 6 min accrued → 1/5 of the lap.
        assertEquals(0.2, session.phaseProgress(seconds(360.0)), 0.001)
    }

    @Test
    fun codableRoundTrip() {
        val config = PomodoroConfig(mode = PomodoroMode.STRUCTURED, prepSeconds = 150).apply {
            structuredByDuration = false
            structuredFinishHour = 17
            structuredBreakCount = 2
        }
        val session = PomodoroSession.start(config, t0, utcCalendar())
        session.pause(seconds(30.0))
        session.resume(seconds(90.0))
        session.advanceIfDue(seconds(600.0))

        val json = testJson
        val decoded = json.decodeFromString(PomodoroSession.serializer(), json.encodeToString(PomodoroSession.serializer(), session))
        assertEquals(session, decoded)
        assertEquals(config, decoded.config)

        val decodedConfig = json.decodeFromString(
            PomodoroConfig.serializer(),
            json.encodeToString(PomodoroConfig.serializer(), config),
        )
        assertEquals(config, decodedConfig)
    }

    /** A session JSON written before an app update must decode rather than
     *  wipe the running timer — every field is optional in the reader. */
    @Test
    fun decodeToleratesMissingOptionalFields() {
        val session = PomodoroSession.start(
            PomodoroConfig(mode = PomodoroMode.FLOWMODORO), t0, utcCalendar(),
        )
        session.advanceIfDue(seconds(200.0))
        val json = testJson
        val encoded = json.encodeToString(PomodoroSession.serializer(), session)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject
        val stripped = kotlinx.serialization.json.buildJsonObject {
            obj.forEach { (key, value) ->
                if (key != "flowBreaksClaimed" && key != "windowEnd") put(key, value)
            }
        }
        val decoded = json.decodeFromString(
            PomodoroSession.serializer(), stripped.toString(),
        )
        assertEquals(0, decoded.flowBreaksClaimed)
        assertNull(decoded.windowEnd)
    }

    private val testJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
