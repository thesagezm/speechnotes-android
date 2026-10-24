package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chunk schedule: the seconds-based target that trades a slightly slower
 * first buffer for a second buffer that arrives on time. Ported from
 * SpeechLogicTests/ChunkScheduleTests.swift (speechnotes-ios) — every pinned
 * behaviour preserved.
 */
class ChunkScheduleTest {

    // MARK: - The starting point

    @Test
    fun freshState_startsAtInitialTarget() {
        val state = ChunkSchedule.State()
        assertEquals(ChunkSchedule.initialTargetSeconds, state.currentTargetSeconds, 0.0)
        assertNull(state.lastWasLate)
        assertNull(state.lastSecondBufferSeconds)
        assertNull(state.measuredCharactersPerAudioSecond)
    }

    @Test
    fun outOfRangeTarget_isClamped() {
        assertEquals(
            ChunkSchedule.minimumTargetSeconds,
            ChunkSchedule.State(targetSeconds = 0.1).currentTargetSeconds,
            0.0,
        )
        assertEquals(
            ChunkSchedule.maximumTargetSeconds,
            ChunkSchedule.State(targetSeconds = 9999.0).currentTargetSeconds,
            0.0,
        )
    }

    // MARK: - Growing

    @Test
    fun lateSession_growsAboveTheMeasuredWait() {
        val state = ChunkSchedule.State()
        state.record(
            secondBufferWasLate = true,
            chunk0AudioSeconds = 3.0,
            secondBufferSeconds = 8.5,
            rate = 1.0,
        )
        assertEquals(true, state.lastWasLate)
        assertTrue(state.currentTargetSeconds > 8.5)
        assertTrue(state.currentTargetSeconds >= 8.5 * ChunkSchedule.growHeadroom)
    }

    @Test
    fun repeatedFailures_growToTheCeilingAndStop() {
        val state = ChunkSchedule.State()
        repeat(10) {
            state.record(
                secondBufferWasLate = true,
                chunk0AudioSeconds = 2.0,
                secondBufferSeconds = 12.0,
                rate = 1.0,
            )
        }
        assertEquals(ChunkSchedule.maximumTargetSeconds, state.currentTargetSeconds, 0.0)
    }

    @Test
    fun growth_isAtLeastADoubling() {
        val state = ChunkSchedule.State()
        val before = state.currentTargetSeconds
        state.record(
            secondBufferWasLate = true,
            chunk0AudioSeconds = 1.0,
            secondBufferSeconds = 1.0,
            rate = 1.0,
        )
        assertTrue(state.currentTargetSeconds >= before * 2)
    }

    // MARK: - Holding on success (the fix for the oscillating ladder)

    @Test
    fun coveredSession_holdsTheTarget() {
        val state = ChunkSchedule.State()
        state.record(
            secondBufferWasLate = true,
            chunk0AudioSeconds = 2.0,
            secondBufferSeconds = 6.0,
            rate = 1.0,
        )
        val grown = state.currentTargetSeconds
        assertTrue(grown > ChunkSchedule.initialTargetSeconds)

        // A tight cover (under the slack threshold) holds the target.
        state.record(
            secondBufferWasLate = false,
            chunk0AudioSeconds = grown,
            secondBufferSeconds = grown - 0.5,
            rate = 1.0,
        )
        assertEquals(grown, state.currentTargetSeconds, 0.0)
        assertEquals(false, state.lastWasLate)
    }

    @Test
    fun comfortableCover_shrinksButStaysAboveTheWait() {
        val state = ChunkSchedule.State(targetSeconds = 20.0)
        state.record(
            secondBufferWasLate = false,
            chunk0AudioSeconds = 20.0,
            secondBufferSeconds = 5.0,
            rate = 1.0,
        )
        assertTrue(state.currentTargetSeconds < 20.0)
        assertTrue(state.currentTargetSeconds >= 5.0 + ChunkSchedule.shrinkSlackSeconds)
    }

    @Test
    fun tightCover_doesNotShrink() {
        val state = ChunkSchedule.State(targetSeconds = 20.0)
        state.record(
            secondBufferWasLate = false,
            chunk0AudioSeconds = 20.0,
            secondBufferSeconds = 19.5,
            rate = 1.0,
        )
        assertEquals(20.0, state.currentTargetSeconds, 0.0)
    }

    @Test
    fun target_neverFallsBelowTheFloor() {
        val state = ChunkSchedule.State(targetSeconds = 4.0)
        repeat(10) {
            state.record(
                secondBufferWasLate = false,
                chunk0AudioSeconds = 4.0,
                secondBufferSeconds = 0.2,
                rate = 1.0,
            )
        }
        assertTrue(state.currentTargetSeconds >= ChunkSchedule.minimumTargetSeconds)
    }

    // MARK: - The no-second-buffer case

    @Test
    fun noSecondBuffer_holdsTheTargetWhenNothingIsKnown() {
        val state = ChunkSchedule.State()
        state.record(
            secondBufferWasLate = true,
            chunk0AudioSeconds = 2.0,
            secondBufferSeconds = 8.0,
            rate = 1.0,
        )
        val grown = state.currentTargetSeconds

        state.record(
            secondBufferWasLate = null,
            chunk0AudioSeconds = grown,
            chunk0Characters = null,
            secondBufferSeconds = null,
            rate = 1.0,
        )
        assertEquals(grown, state.currentTargetSeconds, 0.0)
    }

    @Test
    fun noSecondBuffer_growsWhenTheOpenerWasShorterThanTheKnownWait() {
        val state = ChunkSchedule.State()
        state.record(
            secondBufferWasLate = false,
            chunk0AudioSeconds = 8.0,
            secondBufferSeconds = 8.0,
            rate = 1.0,
        )
        val before = state.currentTargetSeconds

        state.record(
            secondBufferWasLate = null,
            chunk0AudioSeconds = 3.0,
            chunk0Characters = null,
            secondBufferSeconds = null,
            rate = 1.0,
        )
        assertTrue(state.currentTargetSeconds > before)
        assertTrue(state.currentTargetSeconds >= 8.0 * ChunkSchedule.growHeadroom)
    }

    // MARK: - Learning the engine's speaking rate

    @Test
    fun speakingRate_isMeasuredFromTheOpener() {
        val state = ChunkSchedule.State()
        // 54 characters of audio took 7.64 s.
        state.record(
            secondBufferWasLate = false,
            chunk0AudioSeconds = 7.64,
            chunk0Characters = 54,
            secondBufferSeconds = 6.0,
            rate = 1.0,
        )
        assertEquals(54.0 / 7.64, state.measuredCharactersPerAudioSecond!!, 0.0001)
        assertEquals(1.0, state.measuredAtRate, 0.0)
    }

    @Test
    fun budget_scalesWithTheSpeechRate() {
        val state = ChunkSchedule.State(targetSeconds = 6.0)
        state.record(
            secondBufferWasLate = false,
            chunk0AudioSeconds = 3.3,
            chunk0Characters = 54,
            secondBufferSeconds = 2.0,
            rate = 1.0,
        )
        val atNormal = state.openerCharacterBudget(rate = 1.0, batchCeiling = 400)
        val atDouble = state.openerCharacterBudget(rate = 2.0, batchCeiling = 400)
        val atHalf = state.openerCharacterBudget(rate = 0.5, batchCeiling = 400)
        assertTrue(atDouble > atNormal)
        assertTrue(atHalf < atNormal)
        // Doubling the rate doubles the characters that fit in the same
        // audio target (within one character of rounding).
        assertEquals(atNormal * 2.0, atDouble.toDouble(), 2.0)
        assertEquals(atNormal / 2.0, atHalf.toDouble(), 2.0)
    }

    @Test
    fun budget_beforeAnyMeasurement_usesTheFallback() {
        val state = ChunkSchedule.State(targetSeconds = 6.0)
        val budget = state.openerCharacterBudget(rate = 1.0, batchCeiling = 400)
        assertEquals(kotlin.math.ceil(6.0 * ChunkSchedule.defaultCharactersPerAudioSecond).toInt(), budget)
        assertEquals(10, state.openerCharacterBudget(rate = 1.0, batchCeiling = 10))
    }

    @Test
    fun perEngineFallback_usedBeforeAnyMeasurement() {
        val kokoro = ChunkSchedule.State(
            targetSeconds = ChunkSchedule.initialTargetSeconds,
            fallbackCharactersPerAudioSecond = ChunkSchedule.kokoroCharactersPerAudioSecond,
        )
        val supertonic = ChunkSchedule.State(
            targetSeconds = ChunkSchedule.supertonicTargetSeconds,
            fallbackCharactersPerAudioSecond = ChunkSchedule.supertonicCharactersPerAudioSecond,
        )
        assertEquals(16.0, kokoro.fallbackCharactersPerAudioSecond, 0.0)
        assertEquals(10.0, supertonic.fallbackCharactersPerAudioSecond, 0.0)
        assertTrue(
            kokoro.openerCharacterBudget(rate = 1.0, batchCeiling = 400) >
                supertonic.openerCharacterBudget(rate = 1.0, batchCeiling = 400),
        )
    }

    // MARK: - The character budget

    @Test
    fun budget_followsTargetAndRate() {
        // 6 s at 15 chars/s = 90 chars.
        assertEquals(
            90,
            ChunkSchedule.openerCharacterBudget(targetSeconds = 6.0, rate = 1.0, batchCeiling = 400),
        )
        // A faster voice needs more characters for the same audio.
        assertEquals(
            180,
            ChunkSchedule.openerCharacterBudget(targetSeconds = 6.0, rate = 2.0, batchCeiling = 400),
        )
        // A slower voice needs fewer.
        assertEquals(
            45,
            ChunkSchedule.openerCharacterBudget(targetSeconds = 6.0, rate = 0.5, batchCeiling = 400),
        )
    }

    @Test
    fun budget_isClampedToTheEngineCeiling() {
        // Supertonic's 200 ceiling.
        assertEquals(
            90,
            ChunkSchedule.openerCharacterBudget(targetSeconds = 6.0, rate = 1.0, batchCeiling = 200),
        )
        // A 30 s target at 15 chars/s is 450 chars — pinned to Kokoro's 160.
        assertEquals(
            160,
            ChunkSchedule.openerCharacterBudget(targetSeconds = 30.0, rate = 1.0, batchCeiling = 160),
        )
    }

    @Test
    fun budget_handlesANonPositiveRate() {
        assertEquals(
            90,
            ChunkSchedule.openerCharacterBudget(targetSeconds = 6.0, rate = 0.0, batchCeiling = 400),
        )
        assertEquals(
            90,
            ChunkSchedule.openerCharacterBudget(targetSeconds = 6.0, rate = -1.0, batchCeiling = 400),
        )
    }

    // MARK: - Log line

    @Test
    fun summary_mentionsTheTargetAndTheVerdict() {
        val state = ChunkSchedule.State()
        assertTrue(state.summary.contains("8.00"))
        state.record(
            secondBufferWasLate = true,
            chunk0AudioSeconds = 2.0,
            secondBufferSeconds = 8.0,
            rate = 1.0,
        )
        assertTrue(state.summary.contains("LATE"))
        assertFalse(state.summary.contains("8.00"))
        assertNotNull(state.summary)
    }
}
