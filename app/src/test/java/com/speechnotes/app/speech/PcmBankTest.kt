package com.speechnotes.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contract tests for the render-ahead PcmBank — the FIFO between synthesis
 * and the AudioTrack writer. Pure Kotlin (no android.* in the bank), runs on
 * the JVM; the concurrency tests use small capacities and real threads with
 * latch-bound timeouts so a regression fails the test, never hangs it.
 */
class PcmBankTest {

    private fun seg(index: Int, frames: Int) = PcmBank.Segment(index, ShortArray(frames) { index.toShort() })

    private val neverCancelled = { false }

    // MARK: - FIFO semantics

    @Test
    fun take_returnsSegmentsInChunkOrder() {
        val bank = PcmBank()
        bank.put(seg(0, 10), 100, neverCancelled)
        bank.put(seg(1, 20), 100, neverCancelled)
        bank.put(seg(2, 30), 100, neverCancelled)
        assertEquals(0, bank.take(0, neverCancelled)?.chunkIndex)
        assertEquals(1, bank.take(0, neverCancelled)?.chunkIndex)
        assertEquals(2, bank.take(0, neverCancelled)?.chunkIndex)
        assertNull(bank.take(0, neverCancelled))
    }

    @Test
    fun take_carrySegmentPayloadIntact() {
        val bank = PcmBank()
        val payload = ShortArray(5) { (it * 7).toShort() }
        bank.put(PcmBank.Segment(3, payload), 100, neverCancelled)
        val out = bank.take(0, neverCancelled)
        assertEquals(3, out?.chunkIndex)
        assertTrue(out?.shorts?.contentEquals(payload) == true)
    }

    @Test
    fun depthTracksFramesAcrossPutAndTake() {
        val bank = PcmBank()
        bank.put(seg(0, 100), 1000, neverCancelled)
        bank.put(seg(1, 50), 1000, neverCancelled)
        assertEquals(150, bank.depthFrames())
        bank.take(0, neverCancelled)
        assertEquals(50, bank.depthFrames())
        bank.take(0, neverCancelled)
        assertEquals(0, bank.depthFrames())
    }

    // MARK: - Empty-bank behavior

    @Test
    fun takeOnEmptyBankWaitsThenReturnsNull() {
        val bank = PcmBank()
        val startedAt = System.nanoTime()
        assertNull(bank.take(80, neverCancelled))
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("take should honor the wait before reporting a dry bank (waited ${waitedMs}ms)", waitedMs >= 50)
    }

    @Test
    fun takeOnEmptyBankWakesEarlyWhenAPutArrives() {
        val bank = PcmBank()
        val putLanded = CountDownLatch(1)
        Thread {
            // Give the taker time to park, then feed it.
            Thread.sleep(40)
            bank.put(seg(0, 10), 1000, neverCancelled)
            putLanded.countDown()
        }.start()
        val startedAt = System.nanoTime()
        val out = bank.take(2_000, neverCancelled)
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(0, out?.chunkIndex)
        assertTrue("take should wake on arrival, not run out the timeout (waited ${waitedMs}ms)", waitedMs < 1_000)
        assertTrue(putLanded.await(1, TimeUnit.SECONDS))
    }

    // MARK: - Capacity backpressure

    @Test
    fun putBlocksAtCapacityAndUnblocksOnDrain() {
        val bank = PcmBank()
        bank.put(seg(0, 100), 100, neverCancelled)
        val putFinished = CountDownLatch(1)
        val secondPutSucceeded = AtomicBoolean(false)
        Thread {
            secondPutSucceeded.set(bank.put(seg(1, 50), 100, neverCancelled))
            putFinished.countDown()
        }.start()
        // The second put must be parked (capacity 100 is full).
        assertFalse("put should block while the bank is at capacity", putFinished.await(120, TimeUnit.MILLISECONDS))
        // Drain below capacity: the parked put completes. Depth math: the
        // take removed the first 100 frames (depth 0), then the parked put
        // added its 50.
        bank.take(0, neverCancelled)
        assertTrue("put should complete once depth falls below capacity", putFinished.await(1, TimeUnit.SECONDS))
        assertTrue(secondPutSucceeded.get())
        assertEquals(50, bank.depthFrames())
    }

    @Test
    fun putExactCapacityAdmitsWithoutBlocking() {
        val bank = PcmBank()
        assertTrue(bank.put(seg(0, 100), 100, neverCancelled))
        assertEquals(100, bank.depthFrames())
    }

    // MARK: - Cancellation

    @Test
    fun putCancelledWhileParkedReturnsFalse() {
        val bank = PcmBank()
        bank.put(seg(0, 100), 100, neverCancelled)
        val cancelled = AtomicBoolean(false)
        val putFinished = CountDownLatch(1)
        val result = AtomicBoolean(true)
        Thread {
            result.set(bank.put(seg(1, 10), 100, isCancelled = { cancelled.get() }))
            putFinished.countDown()
        }.start()
        Thread.sleep(80)
        cancelled.set(true)
        assertTrue(putFinished.await(1, TimeUnit.SECONDS))
        assertFalse("a cancelled put must report failure, not park forever", result.get())
        assertEquals(100, bank.depthFrames())
    }

    @Test
    fun drainClearsDepthAndWakesAParkedTaker() {
        val bank = PcmBank()
        bank.put(seg(0, 40), 100, neverCancelled)
        bank.drain()
        assertEquals(0, bank.depthFrames())
        assertNull(bank.take(0, neverCancelled))
    }
}
