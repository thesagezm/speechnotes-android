package com.speechnotes.app.speech

import com.speechnotes.app.data.LogStore
import com.speechnotes.app.logic.Chunk
import com.speechnotes.app.logic.ChunkSchedule
import com.speechnotes.app.logic.SentenceChunker
import com.speechnotes.app.logic.TextHash
import com.speechnotes.app.logic.WavWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Streaming-playback machinery shared by the ONNX engines, ported from
 * StreamingTTSPlaybackCore.swift (speechnotes-ios):
 *
 *  - sentence-chunk pipeline via SentenceChunker
 *  - bounded generation-ahead pacing via a semaphore
 *  - AudioTrack buffer scheduling with play-position tracking
 *  - interruption-safe pause/resume/stop with generation guards
 *  - chunked WAV export
 *
 * Threading contract:
 *  - MAIN THREAD: pipeline state, state transitions, all callbacks.
 *  - generateQueue: model state + synthesis AND the AudioTrack write loop.
 *    The writes used to run on main: a full buffer made the non-blocking
 *    retry loop sleep(20ms)×100 there — up to ~2 s of a frozen UI per
 *    chunk, which is exactly "the play/pause buttons don't respond". The
 *    loop now paces on the generate queue, where sleeping is free; only
 *    state/progress/played-chars notifications hop back to main.
 */
class StreamingPlaybackCore(
    private val config: Config,
    private val generateChunk: (String) -> FloatArray,
    private val isModelReady: () -> Boolean,
) {

    data class Config(
        val sampleRate: Int,
        val chunkMaxChars: Int,
        /**
         * Cap for the FIRST chunk only — the FALLBACK opener size when no
         * [ChunkSchedule.State] is wired. With a schedule the schedule owns
         * the opener size (its audio-seconds target converted to a character
         * budget from the engine's measured speaking rate, clamped to
         * [chunkMaxChars]); this fixed value is the pre-schedule behavior
         * that lost the break-even race on any engine whose RTF exceeded
         * firstChunkMaxChars / chunkMaxChars.
         */
        val firstChunkMaxChars: Int = chunkMaxChars,
        /**
         * The seconds-based opener schedule (iOS ChunkSchedule, afdc1d1),
         * owned by the engine so its learned target and measured
         * chars-per-audio-second persist across sessions. null = the fixed
         * [firstChunkMaxChars] opener.
         */
        val schedule: ChunkSchedule.State? = null,
        /**
         * Seconds of generated-but-unplayed audio the pipeline may hold ahead
         * of the playhead (RenderAheadBankPolicy). Replaces the old chunk-count
         * throttle: chunk audio length varies ~40×, so "2 chunks ahead" is
         * anywhere from 2 s to 40 s of protection. Defaults to the bank
         * policy's pressured target — callers with a live thermal reading
         * pass an explicit value.
         */
        val thermal: RenderAheadBankPolicy.ThermalPressure =
            RenderAheadBankPolicy.ThermalPressure.NOMINAL,
        val bankTargetSeconds: Double = RenderAheadBankPolicy
            .pressuredTargetSeconds(thermal, sampleRate.toDouble()),
        val exportInterChunkSilence: Float,
        val logPrefix: String,
    )

    // Callbacks — main thread.
    var onStateChanged: ((SpeechState) -> Unit)? = null
    var onProgress: ((Double) -> Unit)? = null
    var onPlayedChars: ((Int) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    private val generateQueue = Executors.newSingleThreadExecutor { r ->
        Thread(r, config.logPrefix + "-generate").apply { priority = Thread.NORM_PRIORITY }
    }

    private var audioTrack: android.media.AudioTrack? = null
    private var trackAttached = false
    private var trackStarted = false
    private var generation = 0
    private var totalFramesWritten = 0

    /**
     * Wall-clock seconds the previous chunk took to synthesize. Sets the
     * prebuffer floor: if chunk N took 3.4 s to render, the bank must hold
     * ≥ 4.4 s before the track starts, or the drain catches up mid-render
     * and AudioFlinger disables the track (RTF > 1 environments — very slow
     * phones, emulators).
     */
    @Volatile
    private var lastChunkRenderSeconds: Double = 0.0

    // MARK: Session metrics for the chunk schedule (ChunkSchedule.State).
    // Written on the generate queue (inside schedule()), read on main when
    // the session ends (finish/stop → reportToSchedule). @Volatile gives the
    // cross-thread visibility; single-writer, so no other sync is needed.

    /** Successful buffers scheduled so far this session (0 = none yet). */
    @Volatile
    private var scheduledBufferCount = 0

    /** When the FIRST successful buffer was scheduled (nanoTime; -1 = none). */
    @Volatile
    private var firstBufferAtNanos = -1L

    /** When the SECOND successful buffer was scheduled (nanoTime; -1 = none). */
    @Volatile
    private var secondBufferAtNanos = -1L

    /** The audio the first successful buffer carried — the opener's actual
     *  reach, in the unit the gap is measured in. */
    @Volatile
    private var firstBufferAudioSeconds = 0.0

    /** The characters the first successful buffer carried — learns this
     *  engine's chars-per-audio-second for the schedule. */
    @Volatile
    private var firstBufferChars = 0

    /** True once this session's outcome has been fed to the schedule. */
    @Volatile
    private var sessionReportedToSchedule = false

    private var chunks: List<Chunk> = emptyList()
    private var scheduledUpTo = -1
    private var totalChars = 1
    private var charsDone = 0

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    /** Live rate — written from main (the rate slider), read per chunk. */
    @Volatile
    var speed: Float = 1.0f

    @Volatile
    private var sampleRateHz: Int = config.sampleRate

    private var state: SpeechState = SpeechState.IDLE
        set(value) {
            if (value != field) {
                LogStore.info("${config.logPrefix} state: $field → $value")
                field = value
                onStateChanged?.invoke(value)
            }
        }

    fun setSampleRate(rate: Int) {
        if (rate == sampleRateHz) return
        sampleRateHz = rate
        // A track built at the old rate would play the new rate's audio at
        // the wrong pitch (Supertonic publishes 44100 from tts.json after the
        // engine loaded with the provisional 24000). Tear the track down; the
        // next schedule() rebuilds it at the right rate.
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
        trackAttached = false
    }

    /** Runs [block] on the generate queue (engine prewarm). */
    fun prewarm(block: () -> Unit) {
        generateQueue.execute { runCatching { block() } }
    }

    // MARK: - Playback

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        stop()
        generation++
        val g = generation
        // The opener size. With a schedule, the schedule owns it: the
        // character budget is derived from the schedule's audio-seconds
        // target and the live rate, then clamped to this engine's batch
        // ceiling (a bigger opener would be split by the chunker — two
        // renders, not one fast one). Without a schedule, the engine's fixed
        // firstChunkMaxChars. Main-thread confined: this read and the
        // record() at session end are the schedule's only touches.
        val openerSize = config.schedule?.openerCharacterBudget(
            rate = speed.toDouble(),
            batchCeiling = config.chunkMaxChars,
        ) ?: config.firstChunkMaxChars
        val utteranceChunks = SentenceChunker.chunks(
            clean,
            firstMaxChars = openerSize,
            // iOS StreamingTTSPlaybackCore:693 packs later chunks to exactly
            // chunkMaxChars — NOT a ×3 multiple. A ×3 batch (480 chars) can
            // phonemize past the engine's MAX_TOKENS (Kokoro 510): the device
            // log hit "chunk 0 failed: token count 681" on a 137-word note,
            // and the whole utterance was then aborted.
            batchMaxChars = config.chunkMaxChars,
            // With a schedule the opener COMPLETES by whole sentences up to
            // the budget — a short first sentence no longer yields a short
            // opener, which is what ran dry before the second buffer.
            minOpenerChars = if (config.schedule != null) openerSize else 0,
        )
        chunks = utteranceChunks
        totalChars = maxOf(1, clean.length)
        charsDone = 0
        scheduledUpTo = -1
        totalFramesWritten = 0
        trackStarted = false
        // Fresh session metrics: the previous session was reported by the
        // stop() above.
        scheduledBufferCount = 0
        firstBufferAtNanos = -1L
        secondBufferAtNanos = -1L
        firstBufferAudioSeconds = 0.0
        firstBufferChars = 0
        sessionReportedToSchedule = false
        running.set(true)
        paused.set(false)
        state = SpeechState.GENERATING

        generateQueue.execute {
            // The gate is the bank: acquire a permit per chunk, release one
            // per chunk actually scheduled. Its capacity is the bank target
            // in CHUNKS — recomputed from the bank's seconds so a slow chunk
            // and a packed chunk cost the same protection. A semaphore needs
            // ≥1 permit to ever pass; a tiny target (critical thermal) still
            // admits one chunk at a time.
            val bankChunks = maxOf(1, (config.bankTargetSeconds / 4.0).toInt())
            val gate = Semaphore(bankChunks)
            for ((index, chunk) in utteranceChunks.withIndex()) {
                if (!running.get() || g != generation) return@execute
                // Pacing: block while the producer is ahead of playback.
                runCatching { gate.acquire() }
                if (!running.get() || g != generation) return@execute
                // A pause must also pause GENERATION — otherwise the queue
                // keeps synthesizing ahead and, worse, schedule() would stomp
                // the PAUSED state back to SPEAKING while the track is
                // suspended (the "pause doesn't respond" bug).
                while (paused.get() && running.get() && g == generation) {
                    try { Thread.sleep(120) } catch (_: InterruptedException) { return@execute }
                }
                if (!running.get() || g != generation) return@execute
                if (!isModelReady()) {
                    main {
                        if (g == generation) {
                            state = SpeechState.IDLE
                            running.set(false)
                        }
                    }
                    return@execute
                }
                val renderStartedAt = System.nanoTime()
                val samples = runCatching { generateChunk(chunk.text) }
                    .onFailure { LogStore.error("${config.logPrefix}: chunk $index failed: ${it.message}") }
                    .getOrNull()
                // WALL seconds, not audio seconds: the prebuffer must cover
                // the next chunk's SYNTHESIS time, or the drain catches the
                // bank mid-render and AudioFlinger disables the track.
                val wallSeconds = (System.nanoTime() - renderStartedAt) / 1_000_000_000.0
                lastChunkRenderSeconds = lastChunkRenderSeconds * 0.3 + wallSeconds * 0.7
                if (samples == null) BeepPlayer.softError()
                schedule(index, samples, g, gate)
            }
            main {
                if (g == generation && state == SpeechState.GENERATING) {
                    LogStore.error("${config.logPrefix}: no audio produced — aborting")
                    state = SpeechState.IDLE
                    running.set(false)
                }
            }
        }
    }

    /** Schedules one synthesized chunk into the AudioTrack. Runs on the
     *  generateQueue (called straight from the speak loop): the non-blocking
     *  retry sleeps below are producer-side pacing and must never sit on the
     *  main thread. Only progress/state notifications hop back to main. */
    private fun schedule(index: Int, samples: FloatArray?, generationId: Int, gate: Semaphore) {
        if (generationId != generation || !running.get()) {
            gate.release()
            return
        }
        if (samples == null || samples.isEmpty()) {
            // Failed chunk: skip it and step the cursor — one bad sentence
            // must not deadlock the pipeline.
            scheduledUpTo = index
            charsDone = indexOf(index + 1)
            val p = charsDone.toDouble() / totalChars
            main { if (generationId == generation) onProgress?.invoke(p) }
            gate.release()
            return
        }
        // Schedule metrics (single-writer: the generate queue). The first
        // and second SUCCESSFUL buffers define the wait the opener must
        // cover — a failed chunk between them neither starts nor ends the
        // measured interval.
        val stampedAt = System.nanoTime()
        when (scheduledBufferCount) {
            0 -> {
                firstBufferAtNanos = stampedAt
                firstBufferAudioSeconds = samples.size / sampleRateHz.toDouble()
                firstBufferChars = chunks.getOrNull(index)?.length ?: 0
            }
            1 -> secondBufferAtNanos = stampedAt
        }
        scheduledBufferCount++
        val track = ensureTrack(samples.size * 2)
        if (track == null) {
            LogStore.error("${config.logPrefix}: ensureTrack failed for chunk $index (${samples.size} samples)")
            gate.release()
            return
        }
        val shorts = ShortArray(samples.size) { i ->
            val clamped = samples[i].coerceIn(-1f, 1f)
            (clamped * 32767f).toInt().toShort()
        }
        // Write in NON-BLOCKING mode. A blocking write on a disabled track
        // (AudioFlinger disables a track after an underrun) parks the writing
        // thread forever — the buffer never drains because the track isn't
        // playing, so schedule() never returns, so the gate never releases
        // and generation stalls: silence with the UI showing SPEAKING.
        // Non-blocking returns what fit (0 on a full buffer); the retry
        // below handles the remainder.
        var offset = 0
        var spinCount = 0
        while (offset < shorts.size) {
            val fit = track.write(shorts, offset, shorts.size - offset, android.media.AudioTrack.WRITE_NON_BLOCKING)
            if (fit < 0) {
                LogStore.error("${config.logPrefix}: AudioTrack.write=$fit")
                break
            }
            if (fit == 0) {
                // Buffer full or track disabled. Restart the track (an
                // underrun disable is cleared by play()) and yield; a paused
                // track just accumulates — hand the tail to writeRemainder
                // (same queue thread, synchronous) and let the NEXT chunk's
                // schedule() retry what it left. Hard-capped so a wedged
                // track can't stall the pipeline forever.
                if (!paused.get()) runCatching { track.play() }
                if (paused.get() || ++spinCount > 100) {
                    if (offset < shorts.size) {
                        writeRemainder(shorts.copyOfRange(offset, shorts.size), generationId)
                    }
                    break
                }
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                if (generationId != generation || !running.get()) break
            } else {
                offset += fit
                totalFramesWritten += fit
                // First audible moment: the track starts once the bank holds
                // the next chunk's expected WALL render time + 1 s of margin
                // (bounded prebuffer, iOS TTS_BASELINE §2), or everything is
                // already queued. Starting earlier loses the race whenever
                // the next chunk renders slower than the bank plays — the
                // underrun then disables the track at the AudioFlinger level.
                val bankedSeconds = (totalFramesWritten - maxOf(0, track.playbackHeadPosition)) / sampleRateHz.toDouble()
                val prebuffer = maxOf(1.2, lastChunkRenderSeconds + 1.0)
                if (!trackStarted && !paused.get() &&
                    (bankedSeconds >= prebuffer || index == chunks.size - 1)
                ) {
                    trackStarted = true
                    runCatching { track.play() }
                        .onFailure { LogStore.error("${config.logPrefix}: track.play: ${it.message}") }
                }
            }
        }
        scheduledUpTo = index
        charsDone = indexOf(index + 1)
        val p = minOf(1.0, charsDone.toDouble() / totalChars)
        val chars = charsDone
        main {
            if (generationId != generation || !running.get()) return@main
            onProgress?.invoke(p)
            onPlayedChars?.invoke(chars)
            // Only SPEAKING when actually sounding — a chunk written while paused
            // (track suspended) must not flip the UI's Pause button back to Pause.
            if (state != SpeechState.SPEAKING && !paused.get()) state = SpeechState.SPEAKING
        }
        gate.release()

        // Natural completion: the final chunk's audio was scheduled last.
        // The handler polls the PLAYBACK HEAD, not the wall-clock duration —
        // with a deep write buffer the final chunk can sit queued behind
        // ~10 s of audio, and the old duration+120 ms estimate cut the tail
        // off mid-word.
        if (scheduledUpTo == chunks.size - 1) {
            val finalFrames = totalFramesWritten
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            lastPolledHead = 0L
            stallPolls = 0
            val completionCheck = object : Runnable {
                override fun run() {
                    if (generationId != generation || !running.get()) return
                    val track = audioTrack
                    // toLong() before the elvis: an Int boxed here is an
                    // Integer, and `as Long` on it throws ClassCastException —
                    // which killed the app seconds after every neural play.
                    val head = track?.playbackHeadPosition?.toLong() ?: 0L
                    if (head >= finalFrames - 1) { finish(); return }
                    // A mid-final-chunk underrun disables the track: the head
                    // freezes with frames still queued and the poll would
                    // spin forever with the UI stuck on Pause. play() on a
                    // disabled track revives it; on a live one it's a no-op.
                    if (!paused.get() && head < finalFrames - 1 && head == lastPolledHead) {
                        runCatching { track?.play() }
                        stallPolls += 1
                        // Tail-stall guard: the Waydroid HAL (and some
                        // device HALs) can stop advancing the head for the
                        // last fraction of a second of queued audio while
                        // the track still reports "playing" — revival is a
                        // no-op there and the poll would spin forever with
                        // the UI stuck on Pause. If the head is frozen
                        // within ~300 ms of the end for a full second of
                        // polls, the audio HAS sounded; end the session.
                        val tailFrames = config.sampleRate * 3 / 10
                        if (stallPolls >= 10 && head >= finalFrames - tailFrames) {
                            LogStore.info("${config.logPrefix}: head stalled at $head/${finalFrames} (<300 ms tail) — ending session")
                            finish(); return
                        }
                        if (stallPolls == 10 || stallPolls % 50 == 0) {
                            LogStore.warn("${config.logPrefix}: completion head frozen at $head/${finalFrames} (poll $stallPolls)")
                        }
                    } else if (head != lastPolledHead) {
                        stallPolls = 0
                    }
                    lastPolledHead = head
                    handler.postDelayed(this, 100)
                }
            }
            handler.postDelayed(completionCheck, 120)
        }
    }

    /** Head position at the previous completion poll — stall detection. */
    private var lastPolledHead = 0L

    /** Consecutive polls with a frozen head while the track should be playing. */
    private var stallPolls = 0

    /**
     * Writes an unwritten tail back into the pipeline when the non-blocking
     * write loop bailed on a full/disabled buffer. Runs on the generate
     * queue (called from schedule), so the sleeps that pace it never sit on
     * the main thread.
     */
    private fun writeRemainder(tail: ShortArray, generationId: Int) {
        if (generationId != generation || !running.get()) return
        val track = audioTrack ?: return
        var offset = 0
        while (offset < tail.size) {
            if (paused.get()) {
                try { Thread.sleep(120) } catch (_: InterruptedException) { return }
                continue
            }
            val fit = track.write(tail, offset, tail.size - offset, android.media.AudioTrack.WRITE_NON_BLOCKING)
            if (fit < 0) return
            if (fit == 0) {
                if (generationId != generation || !running.get()) return
                runCatching { track.play() }
                try { Thread.sleep(20) } catch (_: InterruptedException) { return }
            } else {
                offset += fit
                totalFramesWritten += fit
            }
        }
    }

    private fun ensureTrack(byteSize: Int): android.media.AudioTrack? {
        if (trackAttached && audioTrack != null) return audioTrack
        return try {
            // The buffer is the streaming pipeline's shock absorber. The old
            // max(chunk, 32 KB) held ~0.4 s of audio, so a batch chunk whose
            // render time exceeded its play time (long PDF chapters on a
            // phone) drained the buffer between writes — the narration
            // stalled/gapped. ~2× the chunk (≥1 MB, ≈11 s at 44.1 kHz mono)
            // lets generation run well ahead of the playback head.
            val bufferBytes = maxOf(byteSize * 2, 1_000_000).coerceAtMost(8_000_000)
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
            // NOT played here. Playing an empty track is a guaranteed
            // underrun — AudioFlinger disables the track and drops it from
            // the active list, and every later write then fights the
            // disable/revive cycle (the "BUFFER TIMEOUT: remove from active
            // list" spam in the device log). The track starts at the
            // prebuffer threshold in schedule(), or on resume() / the
            // writeRemainder() revival path. Chunks arriving while paused
            // are buffered, not sounded.
            audioTrack = track
            trackAttached = true
            track
        } catch (e: Exception) {
            LogStore.error("${config.logPrefix}: AudioTrack setup failed: ${e.message}")
            null
        }
    }

    /** The UTF-16 offset just past chunk [index]'s text. */
    private fun indexOf(chunkIndex: Int): Int {
        if (chunkIndex <= 0) return 0
        if (chunkIndex >= chunks.size) return chunks.lastOrNull()?.endOffset ?: 0
        return chunks[chunkIndex - 1].endOffset
    }

    private fun finish() {
        // Feed the schedule BEFORE the callbacks: an onFinished handler that
        // starts the next chapter (book auto-advance) will size its opener
        // from what THIS session just measured.
        reportToSchedule()
        // IDLE before the callback: an onFinished handler that starts the
        // next chapter (book auto-advance) must win over the trailing
        // IDLE — the old order clobbered the fresh GENERATING state and
        // killed the chain.
        state = SpeechState.IDLE
        running.set(false)
        onFinished?.invoke()
    }

    /**
     * Feeds the session's outcome to the chunk schedule, so the NEXT session
     * opens with a size that fits this engine's speed (iOS
     * StreamingTTSPlaybackCore.reportToSchedule). Called on the main thread
     * from finish() and stop() — a session the user cut short STILL measured
     * whether the second buffer was late.
     */
    private fun reportToSchedule() {
        val schedule = config.schedule ?: return
        if (sessionReportedToSchedule) return
        val wait: Double? = if (firstBufferAtNanos >= 0L && secondBufferAtNanos >= 0L) {
            (secondBufferAtNanos - firstBufferAtNanos) / 1_000_000_000.0
        } else {
            null
        }
        val late: Boolean? = when {
            wait == null || firstBufferAudioSeconds <= 0.0 -> null
            else -> wait > firstBufferAudioSeconds
        }
        // Nothing was measured at all (the session produced no buffers) —
        // there is no verdict to record; leave the flag clear so a
        // subsequent stop in the same session can still report.
        if (late == null && firstBufferAudioSeconds <= 0.0) return
        sessionReportedToSchedule = true
        schedule.record(
            secondBufferWasLate = late,
            chunk0AudioSeconds = firstBufferAudioSeconds.takeIf { it > 0.0 },
            chunk0Characters = firstBufferChars.takeIf { it > 0 },
            secondBufferSeconds = wait,
            rate = speed.toDouble(),
        )
        val nextBudget = schedule.openerCharacterBudget(speed.toDouble(), config.chunkMaxChars)
        val verdict = when (late) {
            true -> "second buffer was LATE"
            false -> "second buffer covered the first"
            null -> "no second buffer"
        }
        LogStore.info("${config.logPrefix} chunk schedule — $verdict, ${schedule.summary}, next opener $nextBudget chars")
    }

    fun pause() {
        if (!running.get()) return
        paused.set(true)
        audioTrack?.pause()
        state = SpeechState.PAUSED
    }

    fun resume() {
        if (!running.get() || !paused.get()) return
        paused.set(false)
        // The track may have been underrun-disabled while suspended; play()
        // clears the disable. trackStarted stays true — the prebuffer already
        // ran for this generation.
        runCatching { audioTrack?.play() }
        state = SpeechState.SPEAKING
    }

    fun stop() {
        // The schedule learns from a user stop too: a session the user cut
        // short STILL measured whether the second buffer was late.
        reportToSchedule()
        generation++
        running.set(false)
        paused.set(false)
        // Wake the generate queue if it's parked on the pause-wait.
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
        trackAttached = false
        state = SpeechState.IDLE
        onProgress?.invoke(0.0)
    }

    fun release() {
        stop()
        generateQueue.shutdownNow()
    }

    // MARK: - WAV export

    /**
     * True while an export renders on the generate queue. Chunk synthesis
     * reads this to bypass any quality shedding — an offline render has no
     * deadline, so exports are always full quality (iOS isExporting parity).
     */
    @Volatile
    var isExporting: Boolean = false
        private set

    /** Renders [text] to a WAV file (off-thread); progress 0…1. */
    fun exportWav(text: String, output: File, onProgress: (Double) -> Unit, onDone: (File?) -> Unit) {
        val clean = text.trim()
        if (clean.isEmpty()) { onDone(null); return }
        // iOS renderWAV runs the same chunker as speak(): the schedule owns
        // the opener size here too (an offline render has no deadline, so a
        // big opener is free). Exports never feed the schedule back.
        val openerSize = config.schedule?.openerCharacterBudget(
            rate = speed.toDouble(),
            batchCeiling = config.chunkMaxChars,
        ) ?: config.firstChunkMaxChars
        val utteranceChunks = SentenceChunker.chunks(
            clean,
            firstMaxChars = openerSize,
            // Same token-budget ceiling as playback — a ×3 batch blew past
            // Kokoro's MAX_TOKENS on the device (see the speak() comment).
            batchMaxChars = config.chunkMaxChars,
            minOpenerChars = if (config.schedule != null) openerSize else 0,
        )
        val silenceSamples = (config.exportInterChunkSilence * sampleRateHz).toInt()
        generateQueue.execute {
            // Same readiness gate as speak(): the model may sit idle-unloaded
            // (5-min timer) and exportWav runs no isModelReady check of its
            // own — without this, every generateChunk fails, only the
            // inter-chunk silences land in the buffer, and the "successful"
            // WAV is a quarter second of silence.
            if (!isModelReady()) { main { onDone(null) }; return@execute }
            isExporting = true
            try {
                val all = ArrayList<Float>()
                var done = 0
                for (chunk in utteranceChunks) {
                    val samples = runCatching { generateChunk(chunk.text) }.getOrNull()
                    if (samples != null) all.addAll(samples.toTypedArray())
                    for (i in 0 until silenceSamples) all.add(0f)
                    done++
                    main { onProgress(done.toDouble() / utteranceChunks.size) }
                }
                if (all.isEmpty()) { main { onDone(null) }; return@execute }
                val bytes = WavWriter.encode(all.toFloatArray(), sampleRateHz)
                runCatching {
                    output.parentFile?.mkdirs()
                    output.writeBytes(bytes)
                }
                main { onDone(output) }
            } finally {
                isExporting = false
            }
        }
    }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
    }

    /** Stable hash for bookmarks (unused here; kept symmetric with the player). */
    fun hashOf(text: String): Long = TextHash.of(text)
}
