package com.speechnotes.app.speech

import com.speechnotes.app.data.LogStore
import com.speechnotes.app.logic.Chunk
import com.speechnotes.app.logic.ChunkSchedule
import com.speechnotes.app.logic.SentenceChunker
import com.speechnotes.app.logic.TextHash
import com.speechnotes.app.logic.WavWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Streaming-playback machinery shared by the ONNX engines, ported from
 * StreamingTTSPlaybackCore.swift (speechnotes-ios):
 *
 *  - sentence-chunk pipeline via SentenceChunker
 *  - render-ahead bank (PcmBank) + a dedicated writer thread — synthesis
 *    overlaps playback instead of trailing it
 *  - AudioTrack buffer scheduling with play-position tracking
 *  - interruption-safe pause/resume/stop with generation guards
 *  - chunked WAV export
 *
 * Threading contract:
 *  - MAIN THREAD: pipeline state, state transitions, all callbacks.
 *  - generateQueue (the producer): model state + synthesis. It renders a
 *    chunk and APPENDS it to the bank — it never touches the AudioTrack.
 *  - the writer thread (one per session): drains the bank into the
 *    AudioTrack and feeds the bridge silence when the bank runs dry
 *    mid-render. The writes used to run on main, then on the producer's
 *    queue — the latter serialized the pipeline: the producer could not
 *    START render(N+1) until it had pushed ALL of chunk N into the 32 KB
 *    (0.68 s) native FIFO, so every render began with 0.68 s of runway and
 *    every sentence boundary carried `render − 0.68 s` ≈ 4–5 s of dead air
 *    (the audible-break diagnosis, 2026-11-15). With render and sink
 *    decoupled through [PcmBank], rendering overlaps playback; only
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

    /**
     * The render-ahead bank — rendered chunks waiting for the sink. The
     * producer appends here and moves straight to the next render; the
     * writer thread drains it into the AudioTrack. THIS is the bank depth
     * the render-ahead policy always meant to pace on — before it existed
     * the only buffer was the 32 KB native FIFO (0.68 s), so the 30 s
     * target was unreachable and render-ahead was dead (see PcmBank).
     */
    private val bank = PcmBank()

    private var audioTrack: android.media.AudioTrack? = null
    private var trackAttached = false
    private var trackStarted = false

    /** Written on main (speak/stop), read on the producer and writer
     *  threads and the watchdogs — the generation guard everywhere. */
    @Volatile
    private var generation = 0

    /**
     * Total frames ever WRITTEN TO THE TRACK (real chunks + bridge
     * silence). Banked audio is not counted until it lands in the FIFO.
     * Single-writer: the writer thread (segments + bridge). Read
     * cross-thread (bankedSeconds on the generate thread, the completion
     * poller on main) — @Volatile.
     */
    @Volatile
    private var totalFramesWritten = 0

    /**
     * [totalFramesWritten] at the moment the CURRENT AudioTrack was created.
     * A recreated track (sample-rate change mid-engine) restarts its playback
     * head at 0 while the running total keeps climbing — the bank arithmetic
     * must subtract the base so the two counts stay in the same coordinate
     * space (RenderAheadBankPolicy.bankedSeconds). Writer-thread written,
     * generate-thread read — @Volatile.
     */
    @Volatile
    private var trackBaseFrames = 0
    private var trackBufferSeconds = 0.0

    /**
     * Wall-clock seconds the previous chunk took to synthesize. Sets the
     * prebuffer floor: if chunk N took 3.4 s to render, the bank must hold
     * ≥ 4.4 s before the track starts, or the drain catches up mid-render
     * and AudioFlinger disables the track (RTF > 1 environments — very slow
     * phones, emulators).
     */
    @Volatile
    private var lastChunkRenderSeconds: Double = 0.0

    // MARK: Never-run-dry keepalive (the silence bridge, iOS 241888e; the
    // ROLLING re-arms are iOS cf501b2). When the playback head consumes
    // everything written while the next chunk is still to be rendered, the
    // AudioTrack's real queue is dry — AudioFlinger then underruns and
    // DISABLES the track, the head freezes, and the session dies in silence
    // even though synthesis is about to land (the Waydroid frozen-head-at-0
    // pathology: HAL write blocked → BUFFER TIMEOUT: remove from active
    // list → completion poller spins at head 0). The bridge keeps the track
    // fed with silence while the render is in flight.
    //
    // WHERE IT RUNS NOW: the writer thread's dry branch ([bridgeTick]).
    // The bank going empty mid-render is exactly the dry moment, and the
    // writer is the one thread already parked waiting to feed the sink —
    // so the bridge needs no thread of its own, no arming protocol and no
    // join before the chunk write: the writer cannot interleave with
    // itself. (The previous design armed a feeder at ADMISSION time —
    // before the render — which in practice never fired: at admission the
    // FIFO still held ~0.68 s, above the 0.5 s exhaustion threshold, and
    // the actual dry moment 0.68 s INTO the render was never re-checked.
    // Every timeline line read `bridge=off` while the air was dead.)
    // Silence and chunk audio still never interleave: bridge slices only
    // leave the writer between segments, never mid-segment.

    /** Silence bridged in the current dry spell — incident log ledger. */
    @Volatile
    private var bridgeIncidentSeconds = 0.0

    /** True once an incident exceeded the safety cap — stop bridging this session. */
    @Volatile
    private var bridgeDisabled = false

    /** Cached silence buffer for bridge slices (writer-thread confined). */
    private var bridgeSilence: ShortArray? = null

    // MARK: Session metrics for the chunk schedule (ChunkSchedule.State).
    // Written on the generate queue (inside bankChunk()), read on main when
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

    /**
     * The last chunk index ADMITTED to the bank (or whose render failed —
     * a failed chunk is no longer "coming"). Producer-written, writer-read
     * (the bridge's has-pending-chunk check) — @Volatile.
     */
    @Volatile
    private var scheduledUpTo = -1
    private var totalChars = 1

    /**
     * Frames the producer has PLANNED for the track — the running total of
     * successfully banked chunk audio. Producer-confined. The completion
     * poller needs the FINAL frame count before the writer has finished
     * writing (the last-chunk-render-failed path registers the poller from
     * the producer while chunks are still banked): planned total is exact
     * there, while a snapshot of written+banked races the writer moving
     * frames between the two counters.
     */
    private var totalPlannedFrames = 0L

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
        // engine loaded with the provisional 24000). Tear the track down AND
        // drop banked audio — banked chunks were rendered at the old rate,
        // which is the same wrong-pitch failure, just deferred; the next
        // renders land at the right one and the writer rebuilds the track
        // at it.
        bank.drain()
        bridgeSilence = null
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
        trackAttached = false
    }

    /** Runs [block] on the generate queue (engine prewarm). */
    fun prewarm(block: () -> Unit) {
        try {
            generateQueue.execute { runCatching { block() } }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            LogStore.warn("${config.logPrefix}: prewarm rejected — the engine queue is shut down")
        }
    }

    /**
     * Runs [block] on the generate queue and WAITS for it (bounded).
     *
     * Used when the queue is about to be shut down and the block MUST run
     * first — closing an ONNX session. A plain [prewarm] would let
     * `shutdownNow()` discard the queued task and leak the native session.
     */
    fun prewarmAndWait(timeoutMs: Long = 2_000L, block: () -> Unit) {
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            generateQueue.execute {
                runCatching { block() }
                done.countDown()
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            done.countDown()
        }
        runCatching { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
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
        scheduledUpTo = -1
        totalFramesWritten = 0
        totalPlannedFrames = 0
        trackStarted = false
        // Fresh session, fresh bank: a stop()'s drain already emptied it,
        // but a same-generation re-speak must never inherit stale PCM.
        bank.drain()
        bridgeSilence = null
        // Fresh keepalive ledger for the fresh session.
        bridgeIncidentSeconds = 0.0
        bridgeDisabled = false
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

        // The session's writer thread: drains the bank into the AudioTrack
        // and feeds the bridge silence when the bank runs dry mid-render.
        // Started before the producer's work is queued so the sink is
        // waiting when the first chunk lands; exits via its generation/
        // running guards when stop()/finish() end the generation — never
        // joined from main.
        Thread({ writerLoop(g) }, "${config.logPrefix}-writer").apply {
            priority = Thread.NORM_PRIORITY
        }.start()

        // R6a — the never-started start-stall watchdog (iOS StreamingTTSPlaybackCore
        // background-health parity, the foreground tail-stall guard's sibling).
        // A session that reaches GENERATING and then produces NO buffer — a
        // wedged model load, a generateChunk that never returns — used to sit
        // there forever: UI on Pause-able GENERATING, no audio, no error, and
        // the R2 bridge (rightly) never arms because there is no track. One
        // check at [START_STALL_SECONDS]: still nothing scheduled, still
        // running, not paused → the synthesis is provably dead; end the
        // session visibly (IDLE, error log) rather than loop silence forever.
        // Fires on main; the scheduledBufferCount read is the @Volatile the
        // generate queue maintains.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (g == generation && running.get() && !paused.get() && scheduledBufferCount == 0) {
                LogStore.error(
                    "${config.logPrefix}: start-stall watchdog — no audio buffer ${START_STALL_SECONDS.toInt()}s " +
                        "after GENERATING (model load or first render wedged); ending the session"
                )
                BeepPlayer.softError()
                state = SpeechState.IDLE
                running.set(false)
            }
        }, START_STALL_SECONDS)

        generateQueue.execute {
            // Pacing IS the bank, in audio seconds (RenderAheadBankPolicy —
            // iOS StreamingTTSPlaybackCore): hold before rendering the next
            // chunk until the banked depth (the PcmBank queue PLUS the
            // track FIFO ahead of the live playback head) drains below the
            // seconds target. The old gate was a CHUNK-COUNT semaphore
            // (target/4 chunks), but chunk audio length varies ~40× —
            // "7 chunks ahead" is anywhere from 3.5 s to 140 s of
            // protection. Seconds measure the thing the listener actually
            // hears run out — and with the bank this reading can finally
            // REACH the target, which the 0.68 s FIFO it was paced on
            // before never let it do. The head advances on the audio
            // thread, so the wait is a poll: 50 ms is fine against a 30 s
            // bank and free on the generate queue. allows() admits on any
            // drain, so a target below one chunk's audio length cannot
            // wedge the producer.
            val bankTarget = config.bankTargetSeconds
            for ((index, chunk) in utteranceChunks.withIndex()) {
                if (!running.get() || g != generation) return@execute
                var holdPolls = 0
                // Dead-sink escape for the hold: a full bank drains only
                // through the playback head. If the head does not move for
                // 15 s while the bank is full and we are not paused, the
                // audio sink is dead (the Waydroid HAL freeze) — holding
                // forever would wedge the session at SPEAKING with no
                // completion poller running mid-session. Same bound as the
                // completion poller's dead-head guard.
                var frozenHead = -1L
                var frozenPolls = 0
                while (running.get() && g == generation &&
                    !RenderAheadBankPolicy.allows(bankedSeconds(), bankTarget)
                ) {
                    // While holding, keep reviving a track an underrun may
                    // have disabled — the head cannot advance on a disabled
                    // track, so the bank would never drain without this
                    // (play() on a live track is a no-op). Logged at most
                    // every 5 s of continuous holding, so a slow device's
                    // routine holding doesn't flood the log.
                    if (!paused.get() && holdPolls % 20 == 0) runCatching { audioTrack?.play() }
                    if (holdPolls == 40 || (holdPolls > 0 && holdPolls % 100 == 0)) {
                        LogStore.info(
                            "${config.logPrefix}: bank full — holding chunk $index " +
                                "(banked %.1fs / target %.1fs)"
                                    .format(bankedSeconds(), bankTarget)
                        )
                    }
                    if (!paused.get()) {
                        val head = runCatching { audioTrack?.playbackHeadPosition?.toLong() }
                            .getOrDefault(-1L) ?: -1L
                        if (head == frozenHead) {
                            frozenPolls++
                            if (frozenPolls >= DEAD_HEAD_POLLS) {
                                LogStore.error(
                                    "${config.logPrefix}: head frozen at $head while bank full " +
                                        "(banked %.1fs) for ${DEAD_HEAD_POLLS / 10}s — audio sink is dead; ending session"
                                            .format(bankedSeconds())
                                )
                                BeepPlayer.softError()
                                main {
                                    if (g == generation && running.get()) {
                                        state = SpeechState.IDLE
                                        running.set(false)
                                    }
                                }
                                return@execute
                            }
                        } else {
                            frozenHead = head
                            frozenPolls = 0
                        }
                    }
                    holdPolls++
                    try { Thread.sleep(50) } catch (_: InterruptedException) { return@execute }
                }
                if (!running.get() || g != generation) return@execute
                // A pause must also pause GENERATION — otherwise the queue
                // keeps synthesizing ahead and, worse, bankChunk() would stomp
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
                // Bank depth at admission — post-hold, pre-render: the "PCM
                // queue immediately before generation" reading of the per-chunk
                // timeline. Whether the track already existed completes the
                // timeline's session-init fact (the WRITER creates it now, on
                // the first segment it drains).
                val bankedBefore = bankedSeconds()
                val hadTrack = audioTrack != null
                // The bridge incident ledger at admission — whether silence
                // was fed DURING this render is the delta across it.
                val incidentBefore = bridgeIncidentSeconds
                val renderStartedAt = System.nanoTime()
                val generated = runCatching { generateChunk(chunk.text) }
                    .onFailure { LogStore.error("${config.logPrefix}: chunk $index failed: ${it.message}") }
                    .getOrNull()
                // WALL seconds, not audio seconds: the prebuffer must cover
                // the next chunk's SYNTHESIS time, or the drain catches the
                // bank mid-render and AudioFlinger disables the track.
                val wallSeconds = (System.nanoTime() - renderStartedAt) / 1_000_000_000.0
                lastChunkRenderSeconds = lastChunkRenderSeconds * 0.3 + wallSeconds * 0.7
                // Sentence-gap tightening (Unit G3): the pipeline is
                // gapless now (the bank), but every chunk still carries the
                // model's own lead-in and EOS trail-off — silence the
                // listener hears stacked on the sentence pause. Trim both
                // ends down to the natural pads (ChunkSilenceTrim), THEN
                // boundary fades so they ride the trimmed edges — all AFTER
                // the wall measurement so the RTF line stays inference-only
                // and audio= reflects the audio actually delivered.
                val trimmed = generated?.let { ChunkSilenceTrim.apply(it, sampleRateHz.toDouble()) }
                val samples = trimmed?.samples?.let { ChunkBoundaryFades.apply(it, sampleRateHz.toDouble()) }
                if (samples == null) BeepPlayer.softError()
                // The chunk is BANKED, not written: the writer thread feeds
                // the sink, so this thread is free to render the next chunk
                // immediately — rendering now OVERLAPS playback instead of
                // trailing it (the audible-break fix; see PcmBank).
                bankChunk(index, samples, g)
                // The per-chunk timeline (ChunkTimeline): one line that makes
                // any device run diagnose itself — RTF > 1 sustained means
                // inference-bound (buffering cannot close the gaps); RTF < 1
                // with a dry bank means scheduling; RTF < 1 with audio still
                // queued means the sink. banked-after reads post-bank depth
                // (the new chunk admitted, bridge silence included). The
                // trim stat rides at the end of the line when the pass took
                // anything off (the G3 sentence-gap tightening).
                val timeline = ChunkTimeline.line(
                    label = "chunk $index/${utteranceChunks.size}",
                    chars = chunk.text.length,
                    audioSeconds = samples?.let { it.size / sampleRateHz.toDouble() },
                    renderSeconds = wallSeconds,
                    bankedBeforeSeconds = bankedBefore,
                    bankedAfterSeconds = bankedSeconds(),
                    bridged = bridgeIncidentSeconds > incidentBefore,
                    trackCreated = !hadTrack && samples != null,
                )
                val trimNote = trimmed?.takeIf { it.trimmedFrames > 0 }
                    ?.let {
                        " trim=%.2fs (head %.2f / tail %.2f)".format(
                            java.util.Locale.US,
                            it.trimmedFrames / sampleRateHz.toDouble(),
                            it.trimmedHeadFrames / sampleRateHz.toDouble(),
                            it.trimmedTailFrames / sampleRateHz.toDouble(),
                        )
                    }
                LogStore.info("${config.logPrefix} $timeline${trimNote ?: ""}")
            }
            main {
                // No audio produced — aborting. The scheduledBufferCount==0
                // guard is what makes this safe now that chunks are banked
                // rather than written: every successful chunk stamps the
                // count at bank-append, so zero means the writer has nothing
                // coming and SPEAKING can never fire for this generation —
                // the session would sit in GENERATING forever. (Chunks
                // produced but unwritable end the session from the writer's
                // ensureTrack failure path instead.)
                if (g == generation && state == SpeechState.GENERATING &&
                    scheduledBufferCount == 0
                ) {
                    LogStore.error("${config.logPrefix}: no audio produced — aborting")
                    state = SpeechState.IDLE
                    running.set(false)
                }
            }
        }
    }

    /**
     * Producer side: admit one rendered chunk into the bank. Runs on the
     * generate queue, straight from the speak loop — the put is instant
     * (heap FIFO) unless the capacity valve is engaged, so the next render
     * starts immediately. The chunk-schedule metrics stamp HERE, at
     * pipeline admission: the wait the opener must cover is the arrival of
     * the second buffer AT THE PIPELINE. Stamping at write time — as the
     * old schedule() did — measured wait = write(0) + render(1), where
     * write(0) alone consumed nearly all of chunk 0's audio draining the
     * 0.68 s FIFO, so every session was structurally "second buffer LATE"
     * and the opener ratcheted to a ceiling the chunker could never
     * deliver.
     *
     * A failed render (samples == null) steps the cursor without stamping:
     * one bad sentence must not deadlock the pipeline.
     */
    private fun bankChunk(index: Int, samples: FloatArray?, generationId: Int) {
        if (generationId != generation || !running.get()) {
            return
        }
        if (samples == null || samples.isEmpty()) {
            scheduledUpTo = index
            val p = indexOf(index + 1).toDouble() / totalChars
            main { if (generationId == generation) onProgress?.invoke(p) }
            // A failed FINAL chunk must still end the session: the writer
            // will never see a last segment, so nobody would register the
            // completion poller and the session hung at SPEAKING after the
            // earlier chunks played out. Register it here —
            // [totalPlannedFrames] is exact (all banked audio will be
            // written; the bridge is gated off past the last chunk), while
            // a written+banked snapshot would race the writer moving frames
            // between the two counters.
            if (index == chunks.size - 1 && scheduledBufferCount > 0) {
                registerCompletionPoller(generationId, totalPlannedFrames)
            }
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
        totalPlannedFrames += samples.size
        val shorts = ShortArray(samples.size) { i ->
            val clamped = samples[i].coerceIn(-1f, 1f)
            (clamped * 32767f).toInt().toShort()
        }
        val admitted = bank.put(
            PcmBank.Segment(index, shorts),
            bankCapacityFrames(),
            isCancelled = { generationId != generation || !running.get() },
        )
        if (!admitted) return
        scheduledUpTo = index
    }

    /**
     * The writer loop — drains the bank into the AudioTrack in chunk order,
     * one segment at a time. Runs on its own thread for the session's
     * lifetime. Rendering must NEVER wait on the sink (that serialization
     * was the audible-break bug — see PcmBank), and the sink must NEVER
     * wait on rendering: when the bank runs dry while more chunks are still
     * coming — the render is in flight or parked on the hold-gate — this
     * loop feeds the rolling bridge silence itself ([bridgeTick], GO1's
     * keepalive, finally reachable).
     */
    private fun writerLoop(generationId: Int) {
        while (generationId == generation && running.get()) {
            // A suspended track must not accumulate unseen audio; hold until
            // resume(). The bank keeps its content.
            if (paused.get()) {
                try { Thread.sleep(120) } catch (_: InterruptedException) { return }
                continue
            }
            val seg = bank.take(50L) { generationId != generation || !running.get() }
            if (seg == null) {
                // Bank dry. More chunks coming? Keep the track fed so
                // AudioFlinger never disables it; nothing to do otherwise.
                bridgeTick(generationId)
                continue
            }
            if (!writeSegment(seg, generationId)) return
        }
    }

    /**
     * Writes one banked segment into the AudioTrack FIFO — the writer's
     * whole job. NON-BLOCKING writes paced by retries: a full FIFO means
     * the head is still chewing (fine — the bank behind it is the
     * protection now), a disabled track is revived with play(). The tail is
     * never abandoned: unlike the old producer-side loop, this thread has
     * nowhere else to be, and a bail would drop mid-sentence audio — the
     * only exits are session-over guards and a provably dead sink.
     *
     * Progress/played-chars/SPEAKING fire when the segment's FIRST bytes
     * enter the FIFO — the sentence is sounding within one track buffer
     * (0.68 s). The old code fired them when the chunk finished scheduling,
     * which — because scheduling paced on the drain — meant the read-along
     * highlight advanced a full chunk late.
     *
     * Returns false only when the session is over (generation changed,
     * stop, or the sink provably failed).
     */
    private fun writeSegment(seg: PcmBank.Segment, generationId: Int): Boolean {
        val shorts = seg.shorts
        var offset = 0
        var startedFired = false
        // Dead-sink guard for the full-FIFO spin (the hold-gate's escape
        // cannot see this state — the producer is parked in the bank's
        // capacity valve by then, so the WRITER owns it): a head that does
        // not move while we spin on a full buffer means the sink is dead.
        var spinHead = -1L
        var spinPolls = 0
        while (offset < shorts.size) {
            if (generationId != generation || !running.get()) return false
            if (paused.get()) {
                try { Thread.sleep(120) } catch (_: InterruptedException) { return false }
                continue
            }
            val track = ensureTrack(shorts.size * 2)
            if (track == null) {
                LogStore.error("${config.logPrefix}: ensureTrack failed for chunk ${seg.chunkIndex} (${shorts.size} samples)")
                main {
                    if (generationId == generation && running.get()) {
                        state = SpeechState.IDLE
                        running.set(false)
                    }
                }
                return false
            }
            val fit = try {
                track.write(shorts, offset, shorts.size - offset, android.media.AudioTrack.WRITE_NON_BLOCKING)
            } catch (e: IllegalStateException) {
                // stop() (or a sample-rate change) released the track under
                // us — routine on stop, an error only if the session is live.
                if (generationId == generation && running.get()) {
                    LogStore.error("${config.logPrefix}: AudioTrack.write failed: ${e.message}")
                }
                return false
            }
            if (fit < 0) {
                LogStore.error("${config.logPrefix}: AudioTrack.write=$fit")
                return false
            }
            if (fit == 0) {
                // Buffer full or track disabled. Restart the track (an
                // underrun disable is cleared by play()) and pace with the
                // head; a paused track just accumulates — wait it out here
                // (the segment's tail must not be dropped).
                if (!paused.get()) runCatching { track.play() }
                val head = runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(-1L)
                if (head == spinHead) {
                    spinPolls++
                    if (spinPolls >= DEAD_HEAD_POLLS) {
                        LogStore.error(
                            "${config.logPrefix}: head frozen at $head while the bank is full " +
                                "(banked %.1fs) for ${DEAD_HEAD_POLLS / 10}s — audio sink is dead; ending session"
                                    .format(bankedSeconds())
                        )
                        BeepPlayer.softError()
                        main {
                            if (generationId == generation && running.get()) {
                                state = SpeechState.IDLE
                                running.set(false)
                            }
                        }
                        return false
                    }
                } else {
                    spinHead = head
                    spinPolls = 0
                }
                try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
                continue
            }
            offset += fit
            totalFramesWritten += fit
            if (!startedFired) {
                startedFired = true
                fireSegmentStarted(seg.chunkIndex, generationId)
                if (bridgeIncidentSeconds > 0.0) {
                    LogStore.info(
                        "${config.logPrefix} keepalive bridge ended — real audio resumed after %.1fs of bridged silence"
                            .format(bridgeIncidentSeconds)
                    )
                    bridgeIncidentSeconds = 0.0
                }
            }
            // The start threshold must fit the native buffer. Waiting
            // for more seconds than the buffer can hold would deadlock
            // the writer after the first partial write.
            val prebuffer = minOf(
                maxOf(1.2, lastChunkRenderSeconds + 1.0),
                trackBufferSeconds * 0.75,
            )
            if (!trackStarted && !paused.get() &&
                (bankedSeconds() >= prebuffer || seg.chunkIndex == chunks.size - 1)
            ) {
                trackStarted = true
                runCatching { track.play() }
                    .onFailure { LogStore.error("${config.logPrefix}: track.play: ${it.message}") }
            }
        }
        if (seg.chunkIndex == chunks.size - 1) {
            registerCompletionPoller(generationId, totalFramesWritten.toLong())
        }
        return true
    }

    /** Progress + played-chars + the SPEAKING flip, fired when a segment's
     *  first bytes enter the FIFO. Runs on the writer; the notification
     *  hops to main. */
    private fun fireSegmentStarted(index: Int, generationId: Int) {
        val chars = indexOf(index + 1)
        val p = minOf(1.0, chars.toDouble() / totalChars)
        main {
            if (generationId != generation || !running.get()) return@main
            onProgress?.invoke(p)
            onPlayedChars?.invoke(chars)
            // Only SPEAKING when actually sounding — a chunk written while paused
            // (track suspended) must not flip the UI's Pause button back to Pause.
            if (state != SpeechState.SPEAKING && !paused.get()) state = SpeechState.SPEAKING
        }
    }

    /**
     * Natural completion: the final chunk's audio was scheduled last. The
     * handler polls the PLAYBACK HEAD, not the wall-clock duration — with a
     * deep write buffer the final chunk can sit queued behind ~10 s of
     * audio, and the old duration+120 ms estimate cut the tail off
     * mid-word. Registered by the writer when the last segment is fully in
     * the FIFO ([totalFramesWritten] is then the final count), or by the
     * producer from the last-chunk-render-failed path (against
     * [totalPlannedFrames]).
     */
    private fun registerCompletionPoller(generationId: Int, finalFrames: Long) {
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
                    // Dead-head guard (the never-started sibling of the
                    // tail-stall guard): a track whose head has not moved
                    // AT ALL for 15 s while it should be playing is
                    // provably not consuming — the Waydroid container's
                    // HAL does this every session (head frozen at 0, HAL
                    // write blocked, BUFFER TIMEOUT) and the poll used to
                    // spin forever until a manual stop. A live track at
                    // 1× moves its head one second per second; 15 s of
                    // zero movement is death, not slowness.
                    if (stallPolls >= DEAD_HEAD_POLLS) {
                        LogStore.error(
                            "${config.logPrefix}: head frozen at $head/${finalFrames} for " +
                                "${DEAD_HEAD_POLLS / 10}s — audio sink is dead; ending session"
                        )
                        BeepPlayer.softError()
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

    /**
     * The writer's dry branch — the rolling bridge (iOS 241888e + cf501b2),
     * finally REACHABLE. Called when the bank returned nothing: if more
     * chunks are still coming (a render is in flight or parked on the
     * hold-gate) and the FIFO is nearly dry, feed one
     * [RenderAheadBankPolicy.BRIDGE_SLICE_SECONDS] silence slice so
     * AudioFlinger never disables the track (the frozen-head pathology)
     * while the dead air ahead of the next chunk stays bounded by one
     * slice (≤ 0.2 s). The old admission-time arming never fired in the
     * normal flow: at admission the FIFO was always ~full — above the 0.5 s
     * exhaustion threshold — and the dry moment, 0.68 s INTO the render,
     * was never re-checked (every timeline line read `bridge=off` while the
     * air was dead). The writer re-checks every loop round (≤ 50 ms), which
     * IS the rolling re-arm. Silence and chunk audio still never interleave
     * in the FIFO: slices only leave the writer BETWEEN segments, never
     * mid-segment. Never bridges unbounded: an incident over 120 s of
     * bridged silence disables the bridge for the session with an error
     * log — a synthesis that dry for that long is dead, and the start-stall
     * watchdog (R6) ends the session rather than silence looping forever.
     */
    private fun bridgeTick(generationId: Int) {
        if (generationId != generation || !running.get() || paused.get()) return
        if (scheduledUpTo >= chunks.size - 1) return // nothing more is coming
        if (bridgeDisabled) return
        val track = audioTrack ?: return
        if (bankedSeconds() >= RenderAheadBankPolicy.BRIDGE_SLICE_SECONDS) return // still covered
        if (bridgeIncidentSeconds == 0.0) {
            LogStore.info(
                "${config.logPrefix} keepalive bridge — real queue ran dry before chunk ${scheduledUpTo + 1} " +
                    "of ${chunks.size}, banked %.1fs; feeding %.2fs silence slices so the track never idles"
                        .format(bankedSeconds(), RenderAheadBankPolicy.BRIDGE_SLICE_SECONDS)
            )
        }
        val silence = bridgeSilenceSlice()
        var offset = 0
        while (offset < silence.size) {
            if (generationId != generation || !running.get() || paused.get()) return
            val fit = try {
                track.write(silence, offset, silence.size - offset, android.media.AudioTrack.WRITE_NON_BLOCKING)
            } catch (_: IllegalStateException) {
                // Track released under us (stop() / sample-rate change).
                return
            }
            if (fit < 0) {
                if (generationId == generation && running.get()) {
                    LogStore.error("${config.logPrefix}: bridge write failed ($fit)")
                }
                return
            }
            if (fit == 0) {
                // Buffer full with a DRY bank cannot happen (the head is
                // at the write point); treat it as a wedged track and
                // bail — the revival paths own that case.
                return
            }
            offset += fit
            totalFramesWritten += fit
            bridgeIncidentSeconds += fit / sampleRateHz.toDouble()
        }
        if (bridgeIncidentSeconds > BRIDGE_CAP_SECONDS) {
            bridgeDisabled = true
            LogStore.error(
                "${config.logPrefix}: keepalive bridge exceeded %.0fs this session — synthesis looks dead; disabling bridge (watchdog should end the session)"
                    .format(BRIDGE_CAP_SECONDS)
            )
        }
    }

    /** Writer-confined silence buffer, rebuilt only when the rate changes. */
    private fun bridgeSilenceSlice(): ShortArray {
        val frames = (RenderAheadBankPolicy.BRIDGE_SLICE_SECONDS * sampleRateHz).toInt().coerceAtLeast(1)
        var slice = bridgeSilence
        if (slice == null || slice.size != frames) {
            slice = ShortArray(frames)
            bridgeSilence = slice
        }
        return slice
    }

    /**
     * The bank's capacity valve, in frames: the pacing target plus one
     * chunk-width of slack, so the hold-gate (which admits below the
     * target) stays the operative pacing and the valve only engages on
     * something pathological (a rate change, a dead sink the frozen-head
     * escape has not yet ended). Recomputed per put — the live sample rate
     * can change mid-session.
     */
    private fun bankCapacityFrames(): Int =
        ((config.bankTargetSeconds + BANK_SLACK_SECONDS) * sampleRateHz).toInt().coerceAtLeast(1)

    /** Safety cap on one session's total bridged silence, in seconds. */
    private val BRIDGE_CAP_SECONDS = 120.0

    /**
     * Slack above the pacing target in the bank's capacity valve
     * ([bankCapacityFrames]): one chunk-width of headroom so the hold-gate
     * stays the operative pacing. 30 s covers the largest chunk this
     * pipeline produces at any sane rate (180 chars ≈ 13 s at 1.0×, ≈ 26 s
     * at 0.5×); a pathological rate parks the producer in the valve until
     * the bank drains — correct backpressure, never a wedge (the writer
     * drains as the head consumes).
     */
    private val BANK_SLACK_SECONDS = 30.0

    /**
     * The R6a start-stall watchdog delay. Generous: a cold neural load is
     * 2–3 s typical, tens of seconds on a wedged-slow device — 45 s means
     * "nothing will ever come", not "slow".
     */
    private val START_STALL_SECONDS = 45_000L

    /** Completion-poll ticks (100 ms) before a fully frozen head ends the session. */
    private val DEAD_HEAD_POLLS = 150

    /** Largest native streaming buffer; larger tracks timed out without draining. */
    private val MAX_TRACK_BUFFER_BYTES = 32 * 1024

    /** Head position at the previous completion poll — stall detection. */
    private var lastPolledHead = 0L

    /** Consecutive polls with a frozen head while the track should be playing. */
    private var stallPolls = 0

    /**
     * Audio seconds generated-but-unplayed right now — BANK + FIFO: the
     * [PcmBank] depth (rendered chunks not yet handed to the track) plus
     * the track's accepted-minus-head occupancy
     * (RenderAheadBankPolicy.bankedSeconds). The render-ahead policy paces
     * on this reading; before the bank existed it maxed out at the 32 KB
     * FIFO's 0.68 s and the 30 s target was unreachable — the hold-gate
     * never engaged and render-ahead was dead. Runs on the generate thread
     * (hold-gate, timeline) and the writer (prebuffer, bridge): the volatile
     * counters cover the cross-thread reads, the bank depth is
     * monitor-confined, and the playback head advances on the audio thread
     * by design.
     */
    private fun bankedSeconds(): Double {
        val bankSeconds = bank.depthFrames() / sampleRateHz.toDouble()
        val track = audioTrack ?: return bankSeconds
        val head = runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(0L)
        return bankSeconds + RenderAheadBankPolicy.bankedSeconds(
            framesWritten = (totalFramesWritten - trackBaseFrames).toLong(),
            playbackHead = head,
            sampleRateHz = sampleRateHz,
        )
    }

    private fun ensureTrack(byteSize: Int): android.media.AudioTrack? {
        if (trackAttached && audioTrack != null) return audioTrack
        return try {
            val minimum = android.media.AudioTrack.getMinBufferSize(
                sampleRateHz,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) {
                LogStore.error("${config.logPrefix}: invalid minimum AudioTrack buffer ($minimum)")
                return null
            }
            // Keep the native stream buffer small. Large buffers do not
            // reliably drain on Android audio stacks (including the phone
            // that produced the user log): AudioFlinger leaves the head at
            // zero and removes the track on BUFFER TIMEOUT. Render-ahead is
            // paced by the producer; it does not require a 1 MB HAL buffer.
            val bufferBytes = maxOf(minimum, minOf(byteSize, MAX_TRACK_BUFFER_BYTES))
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
            audioTrack = track
            trackAttached = true
            trackBaseFrames = totalFramesWritten
            trackBufferSeconds = bufferBytes / 2.0 / sampleRateHz
            LogStore.info(
                "${config.logPrefix}: AudioTrack ready rate=$sampleRateHz " +
                    "buffer=$bufferBytes bytes (minimum=$minimum)"
            )
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
        // Retire the session's writer the same way: flip the flags, drain
        // the bank (wakes a producer parked in the capacity valve and frees
        // the banked PCM). No join here (stop() runs on main); the writer's
        // guards make it exit within one loop round and its writes catch
        // the release that follows below.
        bank.drain()
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
                    val renderStartedAt = System.nanoTime()
                    val generated = runCatching { generateChunk(chunk.text) }.getOrNull()
                    val wallSeconds = (System.nanoTime() - renderStartedAt) / 1_000_000_000.0
                    // Same silence trim + boundary fades as playback, so an
                    // exported WAV has the same sentence gaps and carries no
                    // click its live twin no longer has (iOS cf501b2);
                    // applied after the wall measurement so the export RTF
                    // stays inference-only.
                    val trimmed = generated?.let { ChunkSilenceTrim.apply(it, sampleRateHz.toDouble()) }
                    val samples = trimmed?.samples?.let { ChunkBoundaryFades.apply(it, sampleRateHz.toDouble()) }
                    if (samples != null) all.addAll(samples.toTypedArray())
                    for (i in 0 until silenceSamples) all.add(0f)
                    done++
                    // Export-side timeline line: no track/bank facts to report
                    // (offline render), but the per-chunk RTF feeds the same
                    // table — export RTF is the clean inference-only reading.
                    LogStore.info(
                        "${config.logPrefix} export $done/${utteranceChunks.size}: " +
                            "chars=${chunk.text.length} " +
                            ChunkTimeline.speedFragment(
                                samples?.let { it.size / sampleRateHz.toDouble() },
                                wallSeconds,
                            ) +
                            (trimmed?.takeIf { it.trimmedFrames > 0 }
                                ?.let {
                                    " trim=%.2fs (head %.2f / tail %.2f)".format(
                                        java.util.Locale.US,
                                        it.trimmedFrames / sampleRateHz.toDouble(),
                                        it.trimmedHeadFrames / sampleRateHz.toDouble(),
                                        it.trimmedTailFrames / sampleRateHz.toDouble(),
                                    )
                                }
                                ?: "")
                    )
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
