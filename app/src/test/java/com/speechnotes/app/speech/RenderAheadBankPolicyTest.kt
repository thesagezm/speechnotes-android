package com.speechnotes.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for RenderAheadBankPolicy — ported from the iOS package's
 * RenderAheadBankPolicy tests. Pure arithmetic, runs on the JVM.
 */
class RenderAheadBankPolicyTest {

    // MARK: - Thermal mapping

    @Test
    fun thermalMapping_clampsUnknownHighToCritical() {
        // An unknown future raw value degrades to the hottest known state:
        // under-sizing the bank only makes the producer hold earlier.
        assertEquals(
            RenderAheadBankPolicy.ThermalPressure.CRITICAL,
            RenderAheadBankPolicy.ThermalPressure.fromStatusRawValue(9),
        )
    }

    @Test
    fun thermalMapping_clampsNegativeToNominal() {
        assertEquals(
            RenderAheadBankPolicy.ThermalPressure.NOMINAL,
            RenderAheadBankPolicy.ThermalPressure.fromStatusRawValue(-1),
        )
    }

    @Test
    fun thermalMapping_matchesPowerManagerStatusValues() {
        // PowerManager.THERMAL_STATUS_NONE=0 … LIGHT=1, MODERATE=2, SEVERE+=3
        assertEquals(
            RenderAheadBankPolicy.ThermalPressure.NOMINAL,
            RenderAheadBankPolicy.ThermalPressure.fromStatusRawValue(0),
        )
        assertEquals(
            RenderAheadBankPolicy.ThermalPressure.FAIR,
            RenderAheadBankPolicy.ThermalPressure.fromStatusRawValue(1),
        )
        assertEquals(
            RenderAheadBankPolicy.ThermalPressure.SERIOUS,
            RenderAheadBankPolicy.ThermalPressure.fromStatusRawValue(2),
        )
        assertEquals(
            RenderAheadBankPolicy.ThermalPressure.CRITICAL,
            RenderAheadBankPolicy.ThermalPressure.fromStatusRawValue(3),
        )
    }

    // MARK: - Targets

    @Test
    fun targetSeconds_nominalAndFairShareFullTarget() {
        assertEquals(30.0, RenderAheadBankPolicy.targetSeconds(RenderAheadBankPolicy.ThermalPressure.NOMINAL), 0.0)
        assertEquals(30.0, RenderAheadBankPolicy.targetSeconds(RenderAheadBankPolicy.ThermalPressure.FAIR), 0.0)
    }

    @Test
    fun targetSeconds_shrinksUnderPressure() {
        assertEquals(15.0, RenderAheadBankPolicy.targetSeconds(RenderAheadBankPolicy.ThermalPressure.SERIOUS), 0.0)
        assertEquals(8.0, RenderAheadBankPolicy.targetSeconds(RenderAheadBankPolicy.ThermalPressure.CRITICAL), 0.0)
    }

    @Test
    fun totalStep_shedsOnlyAtCritical() {
        // The 2026-10-06 device verdict: shedding at serious rendered ~90% of
        // a book at 4 steps — audible artifacts. Quality outranks speed.
        assertEquals(8, RenderAheadBankPolicy.totalStep(RenderAheadBankPolicy.ThermalPressure.NOMINAL))
        assertEquals(8, RenderAheadBankPolicy.totalStep(RenderAheadBankPolicy.ThermalPressure.FAIR))
        assertEquals(8, RenderAheadBankPolicy.totalStep(RenderAheadBankPolicy.ThermalPressure.SERIOUS))
        assertEquals(4, RenderAheadBankPolicy.totalStep(RenderAheadBankPolicy.ThermalPressure.CRITICAL))
    }

    @Test
    fun pressureFactor_halvesAtSerious_quartersAtCritical() {
        assertEquals(1.0, RenderAheadBankPolicy.pressureFactor(RenderAheadBankPolicy.ThermalPressure.NOMINAL), 0.0)
        assertEquals(0.5, RenderAheadBankPolicy.pressureFactor(RenderAheadBankPolicy.ThermalPressure.SERIOUS), 0.0)
        assertEquals(0.25, RenderAheadBankPolicy.pressureFactor(RenderAheadBankPolicy.ThermalPressure.CRITICAL), 0.0)
    }

    // MARK: - Byte cap

    @Test
    fun effectiveTarget_byteCapNeverBindsAtEngineSampleRates() {
        // 32 MB / (24000·4 B/s) ≈ 349 s — far above the 30 s seconds target.
        val effective = RenderAheadBankPolicy.effectiveTargetSeconds(
            RenderAheadBankPolicy.ThermalPressure.NOMINAL, 24_000.0,
        )
        assertEquals(30.0, effective, 0.5)
    }

    @Test
    fun effectiveTarget_nonPositiveSampleRate_fallsBackToSecondsTarget() {
        // A zero sample rate cannot be converted to bytes; refusing to
        // generate at all would be a worse failure than an unguarded bank.
        assertEquals(
            30.0,
            RenderAheadBankPolicy.effectiveTargetSeconds(RenderAheadBankPolicy.ThermalPressure.NOMINAL, 0.0),
            0.0,
        )
        assertEquals(
            30.0,
            RenderAheadBankPolicy.effectiveTargetSeconds(RenderAheadBankPolicy.ThermalPressure.NOMINAL, -1.0),
            0.0,
        )
    }

    @Test
    fun pressuredTarget_multipliesAfterTheCap() {
        val serious = RenderAheadBankPolicy.pressuredTargetSeconds(
            RenderAheadBankPolicy.ThermalPressure.SERIOUS, 24_000.0,
        )
        assertEquals(7.5, serious, 0.01)
    }

    // MARK: - allows()

    @Test
    fun allows_holdsAtExactlyTarget() {
        // The producer parked at the full mark must drain before admitting
        // more work — otherwise it generates back-to-back at full depth.
        assertFalse(RenderAheadBankPolicy.allows(bankedSeconds = 30.0, targetSeconds = 30.0))
    }

    @Test
    fun allows_admitsBelowTarget() {
        assertTrue(RenderAheadBankPolicy.allows(bankedSeconds = 29.9, targetSeconds = 30.0))
    }

    @Test
    fun allows_doesNotDeadlockWhenTargetIsBelowOneChunk() {
        // Critical's 8 s target vs a ~20 s chunk: an estimate-including check
        // (banked + next ≤ target) would never pass and the producer wedges
        // forever. The shipped check admits on any drain.
        assertTrue(RenderAheadBankPolicy.allows(bankedSeconds = 5.0, targetSeconds = 8.0))
    }

    // MARK: - Exhaustion

    @Test
    fun isExhausted_belowHalfSecond() {
        assertTrue(RenderAheadBankPolicy.isExhausted(0.4))
        assertFalse(RenderAheadBankPolicy.isExhausted(0.5))
        assertFalse(RenderAheadBankPolicy.isExhausted(10.0))
    }

    // MARK: - Live bank accounting (R1: the seconds-based render-ahead bank)

    @Test
    fun bankedSeconds_isFramesAheadOfHeadOverRate() {
        // 48_000 frames written, 24_000 played, 24 kHz → exactly 1 s banked.
        assertEquals(
            1.0,
            RenderAheadBankPolicy.bankedSeconds(
                framesWritten = 48_000, playbackHead = 24_000, sampleRateHz = 24_000,
            ),
            1e-9,
        )
    }

    @Test
    fun bankedSeconds_drainsAsTheHeadAdvances() {
        // The producer's hold releases when playback consumes the bank: the
        // same write count with a caught-up head reads as empty.
        assertEquals(
            0.0,
            RenderAheadBankPolicy.bankedSeconds(
                framesWritten = 48_000, playbackHead = 48_000, sampleRateHz = 24_000,
            ),
            0.0,
        )
    }

    @Test
    fun bankedSeconds_clampsHeadAheadOfWritesToZero() {
        // Cannot happen on a live track, but the two reads race — a negative
        // depth must never read as a drained bank AND admit… or worse, as a
        // negative hold. Clamp to 0 (an empty bank admits the next chunk).
        assertEquals(
            0.0,
            RenderAheadBankPolicy.bankedSeconds(
                framesWritten = 1_000, playbackHead = 2_000, sampleRateHz = 24_000,
            ),
            0.0,
        )
    }

    @Test
    fun bankedSeconds_nonPositiveSampleRate_reportsEmptyBank() {
        // A zero rate cannot convert frames to seconds; refusing to admit
        // forever would be a worse failure than an unguarded bank.
        assertEquals(
            0.0,
            RenderAheadBankPolicy.bankedSeconds(
                framesWritten = 48_000, playbackHead = 0, sampleRateHz = 0,
            ),
            0.0,
        )
        assertEquals(
            0.0,
            RenderAheadBankPolicy.bankedSeconds(
                framesWritten = 48_000, playbackHead = 0, sampleRateHz = -1,
            ),
            0.0,
        )
    }

    @Test
    fun bankedSeconds_feedsAllows_admittingOnAnyDrain() {
        // The wiring the producer runs: hold while the banked reading blocks
        // admission, admit the moment it drains below target — even when the
        // target is below one chunk's audio length (critical thermal).
        val target = RenderAheadBankPolicy.pressuredTargetSeconds(
            RenderAheadBankPolicy.ThermalPressure.CRITICAL, 24_000.0,
        )
        assertEquals(2.0, target, 0.01) // 8 s × 0.25 pressure factor
        val banked = RenderAheadBankPolicy.bankedSeconds(
            framesWritten = 72_000, playbackHead = 24_000, sampleRateHz = 24_000,
        )
        assertEquals(2.0, banked, 1e-9)
        assertFalse(RenderAheadBankPolicy.allows(banked, target))
        assertTrue(
            RenderAheadBankPolicy.allows(
                RenderAheadBankPolicy.bankedSeconds(
                    framesWritten = 72_000, playbackHead = 48_001, sampleRateHz = 24_000,
                ),
                target,
            ),
        )
    }

    // MARK: - Never-run-dry silence bridge (R2, iOS 241888e)

    @Test
    fun bridgeNeeded_armsOnlyWhenDryPendingPlayingAndAttached() {
        // The exact dry spell the bridge exists for: the head consumed
        // everything written, the next chunk is still to render, the user
        // has not paused, and a track exists to keep fed.
        assertTrue(
            RenderAheadBankPolicy.bridgeNeeded(
                bankedSeconds = 0.0, hasPendingChunk = true, paused = false, trackAttached = true,
            )
        )
    }

    @Test
    fun bridgeNeeded_staysInertWhenTheBankStillHoldsAudio() {
        // Real audio queued — AudioFlinger has something to play; bridging
        // would pad the gap the listener has not reached yet.
        assertFalse(
            RenderAheadBankPolicy.bridgeNeeded(
                bankedSeconds = 0.5, hasPendingChunk = true, paused = false, trackAttached = true,
            )
        )
        assertFalse(
            RenderAheadBankPolicy.bridgeNeeded(
                bankedSeconds = 12.0, hasPendingChunk = true, paused = false, trackAttached = true,
            )
        )
    }

    @Test
    fun bridgeNeeded_staysInertWithoutATrackOrWhenPausedOrWhenNothingPending() {
        // No track yet (the first chunk of a session — nothing to keep fed).
        assertFalse(
            RenderAheadBankPolicy.bridgeNeeded(
                bankedSeconds = 0.0, hasPendingChunk = true, paused = false, trackAttached = false,
            )
        )
        // Paused: the user asked for silence.
        assertFalse(
            RenderAheadBankPolicy.bridgeNeeded(
                bankedSeconds = 0.0, hasPendingChunk = true, paused = true, trackAttached = true,
            )
        )
        // Nothing pending: the completion poller owns the tail.
        assertFalse(
            RenderAheadBankPolicy.bridgeNeeded(
                bankedSeconds = 0.0, hasPendingChunk = false, paused = false, trackAttached = true,
            )
        )
    }

    @Test
    fun bridgeSilence_coversTheExpectedRender() {
        // No render history yet (first bridge of a session): the margin
        // floor applies — one full second for the HAL to chew.
        assertEquals(1.0, RenderAheadBankPolicy.bridgeSilenceSeconds(0.0), 0.0)
        // After a 3.4 s render, the silence must outlast a repeat of it:
        // render + margin, or the track drains mid-render and AudioFlinger
        // disables it — the pathology the bridge exists to prevent.
        assertEquals(4.4, RenderAheadBankPolicy.bridgeSilenceSeconds(3.4), 1e-9)
    }
}
