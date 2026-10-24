package com.speechnotes.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * JVM tests for the stats store. StatsStore.init() points at a temp
 * directory; the day math uses the real clock, so tests assert on relative
 * structure (streak of a fresh store, fold accumulation, kind split)
 * rather than absolute day keys.
 */
class StatsStoreTest {

    private fun initToTemp() {
        val dir = Files.createTempDirectory("sn-stats").toFile()
        StatsStore.init(dir)
    }

    @Test
    fun `fresh store has empty totals`() = runBlocking {
        initToTemp()
        assertEquals(0.0, StatsStore.totals(daysBack = 7), 0.001)
        assertEquals(0, StatsStore.streak())
        assertEquals(0, StatsStore.activeDays())
    }

    @Test
    fun `record folds same day+subject+kind rows`() = runBlocking {
        initToTemp()
        StatsStore.record("book-1", StatsStore.Kind.READING, 30.0)
        StatsStore.record("book-1", StatsStore.Kind.READING, 45.0)
        StatsStore.record("book-1", StatsStore.Kind.LISTENING, 60.0)
        assertEquals(135.0, StatsStore.totals(daysBack = 1), 0.001)
        assertEquals(75.0, StatsStore.totals(daysBack = 1, kind = StatsStore.Kind.READING), 0.001)
        assertEquals(60.0, StatsStore.totals(daysBack = 1, kind = StatsStore.Kind.LISTENING), 0.001)
    }

    @Test
    fun `junk records are ignored`() = runBlocking {
        initToTemp()
        StatsStore.record("", StatsStore.Kind.READING, 30.0)
        StatsStore.record("x", StatsStore.Kind.READING, -5.0)
        StatsStore.record("x", StatsStore.Kind.READING, 0.0)
        assertEquals(0.0, StatsStore.totals(daysBack = 1), 0.001)
    }

    @Test
    fun `subject totals split by subject`() = runBlocking {
        initToTemp()
        StatsStore.record("a", StatsStore.Kind.READING, 10.0)
        StatsStore.record("b", StatsStore.Kind.READING, 20.0)
        StatsStore.record("b", StatsStore.Kind.LISTENING, 5.0)
        val reading = StatsStore.subjectTotals(daysBack = 1, kind = StatsStore.Kind.READING)
        assertEquals(10.0, reading["a"] ?: 0.0, 0.001)
        assertEquals(20.0, reading["b"] ?: 0.0, 0.001)
        val all = StatsStore.subjectTotals(daysBack = 1)
        assertEquals(25.0, all["b"] ?: 0.0, 0.001)
    }

    @Test
    fun `streak counts today or yesterday anchored runs`() = runBlocking {
        initToTemp()
        StatsStore.record("s", StatsStore.Kind.READING, 120.0)
        assertTrue(StatsStore.streak() >= 1)
        // A fresh store with only 30 s today (below the 60 s minimum) and
        // no history has a zero streak.
        initToTemp()
        StatsStore.record("s", StatsStore.Kind.READING, 30.0)
        assertEquals(0, StatsStore.streak())
    }
}
