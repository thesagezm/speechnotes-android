package com.speechnotes.app.speech

/**
 * The render-ahead bank — a bounded FIFO of rendered chunks sitting between
 * the producer (synthesis) and the AudioTrack writer.
 *
 * WHY THIS EXISTS (the chunk-gap diagnosis, 2026-11-15): the Android port of
 * iOS StreamingTTSPlaybackCore banked rendered audio directly into the
 * AudioTrack's native FIFO, which is capped at 32 KB (0.68 s at 24 kHz —
 * RenderAheadBankPolicy's frozen-head fix). The producer therefore could not
 * START rendering chunk N+1 until it had pushed ALL of chunk N into that
 * 0.68 s FIFO — its write loop paces on the playback drain — so every render
 * began with only 0.68 s of runway and the listener heard
 * `render(next) − 0.68 s` ≈ 4–5 s of dead air at every sentence boundary.
 * iOS never had this problem: it banks into a ring buffer the audio render
 * callback pulls from, so rendering overlaps playback. This bank restores
 * that shape: the producer appends a rendered chunk here (instant — the
 * depth is heap, not the HAL) and immediately renders the next one; a
 * dedicated writer thread drains the bank into the AudioTrack.
 *
 * Capacity is the safety valve, not the pacing: the producer's hold-gate
 * (`RenderAheadBankPolicy.allows`) paces synthesis on bankedSeconds() and
 * the bank capacity sits one chunk-width above it, so a put only blocks when
 * something pathological happens (a rate change mid-session, a dead sink
 * that the hold-gate's frozen-head escape has not yet ended). Bounded
 * anyway, so the heap cost is known: at 24 kHz, 60 s of stereo-int is
 * ~2.9 MB of short arrays.
 *
 * Pure Kotlin — no android.* imports — so the FIFO semantics are assertable
 * on the JVM. Waits are `Object.wait` under the bank's own monitor with
 * short timeouts and caller-supplied cancellation, matching the codebase's
 * generation-guard style; no blocking primitive ever parks a thread that a
 * `stop()` cannot wake.
 */
class PcmBank {

    /** One rendered chunk waiting for the writer. [chunkIndex] travels with
     *  the audio so the writer can fire per-chunk progress and register the
     *  completion poller when the LAST chunk lands. */
    class Segment(val chunkIndex: Int, val shorts: ShortArray)

    private val lock = Object()
    private val queue = ArrayDeque<Segment>()

    /** Frames currently banked. Guard: [lock]. */
    private var depthFrames = 0

    /** Audio seconds banked right now. Sample rate supplied by the caller
     *  (the core owns the live rate; the bank stays rate-agnostic). */
    fun depthFrames(): Int = synchronized(lock) { depthFrames }

    /**
     * Appends a rendered chunk, blocking while the bank is at
     * [capacityFrames]. Returns false when cancelled (or interrupted) —
     * the caller must then discard the segment; the session is over.
     * Wakes parked takers on success.
     */
    fun put(
        segment: Segment,
        capacityFrames: Int,
        isCancelled: () -> Boolean,
        pollMs: Long = 50L,
    ): Boolean = synchronized(lock) {
        while (depthFrames >= capacityFrames) {
            if (isCancelled()) return false
            try {
                lock.wait(pollMs)
            } catch (_: InterruptedException) {
                return false
            }
        }
        queue.addLast(segment)
        depthFrames += segment.shorts.size
        lock.notifyAll()
        true
    }

    /**
     * Takes the oldest banked chunk, waiting up to [waitMs] for one to
     * arrive. A non-positive [waitMs] never waits (Object.wait(0) waits
     * FOREVER — the empty-bank probe must not). Returns null when the bank
     * stays empty (the caller re-polls — this is the writer's 50 ms cadence)
     * or when cancelled. Wakes a put parked on capacity, because depth just
     * fell.
     */
    fun take(
        waitMs: Long,
        isCancelled: () -> Boolean,
    ): Segment? = synchronized(lock) {
        if (queue.isEmpty()) {
            if (waitMs <= 0 || isCancelled()) return null
            try {
                lock.wait(waitMs)
            } catch (_: InterruptedException) {
                return null
            }
            if (queue.isEmpty()) return null
        }
        val segment = queue.removeFirst()
        depthFrames -= segment.shorts.size
        lock.notifyAll()
        segment
    }

    /** Drops everything banked (stop / sample-rate change) and wakes any
     *  thread parked in [put] or [take]. */
    fun drain() = synchronized(lock) {
        queue.clear()
        depthFrames = 0
        lock.notifyAll()
    }
}
