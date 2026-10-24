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
}
