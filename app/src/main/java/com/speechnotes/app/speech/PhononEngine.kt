package com.speechnotes.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.speechnotes.app.data.LogStore
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.logic.ChunkSchedule
import com.speechnotes.app.logic.PhononTokenizer
import com.speechnotes.app.model.VoiceCatalog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phonon engine (Gradium phonon-31m-en): a 31M-param English streaming TTS
 * over TWO fp32 ONNX graphs — a causal flow transformer that emits one
 * 32-dim latent per 80 ms frame plus an end-of-speech logit, and the
 * stateless Mimi codec decoder that turns latents into 24 kHz PCM.
 *
 * Ported from PhononEngine.swift (speechnotes-ios) AT COMMIT `75ff1ba` —
 * the WORKING prefill-once recipe. The pinned call contract lives in the
 * iOS repo's Docs/PHONON-PORT.md; every constant's provenance is recorded
 * there. Older snapshots (the re-run-prompt recipe with the head-major KV
 * walk) are OBSOLETE — do not port from them.
 *
 * PREFILL-ONCE (the export's own convention): call 0 runs voice+text
 * against an EMPTY cache and every present.* is fed back WHOLE, so the
 * prompt K/V sit in the cache FROZEN forever (reference prefill
 * semantics). Calls 1+ feed an EMPTY prompt (`input_ids` [1,0], voice
 * [1,0,512] — zero-size tensors) and carry only the slot + 7 frame rows.
 * Re-feeding the prompt every call made layers 1–5 attend to latents after
 * them (the causal mask reaching GQA is a shared additive column bias) —
 * the text K/V drifted every call and chunks over-generated at the sentence
 * end; the frozen-prompt recipe took the x86 sweep from 8/120 degenerate
 * runs to 0/120. Whole-present feedback also deletes the per-head KV copy
 * — the whole class of head-major striding bugs the old loop defended
 * against is gone.
 *
 * All pipeline machinery (chunking, pacing, scheduling, play-time position
 * tracking, WAV export) lives in [StreamingPlaybackCore]; this class
 * contributes model loading + synthesis, confined to the core's
 * generateQueue. Each sentence chunk gets a fresh KV/latent state — the
 * hub's own driver resets per chunk, which is also what makes successive
 * sentences join cleanly.
 *
 * Rate: Phonon has NO native speed control (the flow export baked its
 * duration conditioning into a constant), so `speed` is accepted and
 * ignored — the engine speaks at its natural pace at every slider value.
 * The slider still drives the core's chunk-opener sizing. (Recorded
 * divergence from the other engines, same as iOS.)
 */
class PhononEngine : SpeechEngine {

    override var name: String = "Phonon — on-device neural (English)"

    override var onStateChanged: ((SpeechState) -> Unit)? = null
    override var onProgress: ((Double) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null
    override var onPlayedChars: ((Int) -> Unit)? = null

    /** Voice id — one of ModelFiles.phononVoices ("default" + the hub's
     *  en-us/en-gb voice_ids). Friendly names live in VoiceCatalog. */
    var voice: String = "default"

    val core: StreamingPlaybackCore

    // Model state — the core's generateQueue only.
    private var ortEnv: OrtEnvironment? = null
    private var flowSession: OrtSession? = null
    private var decodeSession: OrtSession? = null
    private var tokenizer: PhononTokenizer? = null
    /** The one resident voice embedding ([1, 125, 512] transposed) — single slot. */
    private var loadedVoice: Pair<String, FloatArray>? = null
    private var modelLoadAttempted = false

    init {
        core = StreamingPlaybackCore(
            config = StreamingPlaybackCore.Config(
                sampleRate = 24_000,
                // The hub treats ~50 tokens as one sentence chunk; ~50 tokens
                // ≈ 200 chars, kept slightly tighter for responsiveness.
                // Real chunks decode in ONE decoder call at the 160-latent
                // window (see DECODE_WINDOW).
                chunkMaxChars = 180,
                // Opener seed: Phonon's RTF is unmeasured on Android (iOS's
                // device round is still pending too); Kokoro's seeds are the
                // closest starting point (24 kHz flow TTS, similar chunk
                // sizes). The schedule adapts from its own measurements —
                // these only decide the FIRST opener.
                firstChunkMaxChars = 80,
                schedule = ChunkSchedule.State(
                    targetSeconds = ChunkSchedule.initialTargetSeconds,
                    fallbackCharactersPerAudioSecond = ChunkSchedule.kokoroCharactersPerAudioSecond,
                ),
                exportInterChunkSilence = 0.05f,
                logPrefix = "PhononEngine",
            ),
            generateChunk = { text -> generateChunk(text) },
            isModelReady = {
                loadModelIfNeeded()
                flowSession != null
            },
        )
        core.onStateChanged = { onStateChanged?.invoke(it) }
        core.onProgress = { onProgress?.invoke(it) }
        core.onPlayedChars = { onPlayedChars?.invoke(it) }
        core.onFinished = { onFinished?.invoke() }
    }

    override var speed: Float
        get() = core.speed
        set(value) { core.speed = value }

    override fun speak(text: String, rateMultiplier: Double) {
        if (!ModelFiles.isValid(EngineKind.PHONON)) {
            LogStore.error("PhononEngine: model files missing — asked to speak")
            return
        }
        core.speak(text)
    }

    override fun pause() = core.pause()
    override fun resume() = core.resume()
    override fun stop() = core.stop()

    /** Loads the ONNX sessions + tokenizer on the generate queue. */
    override fun prewarm() {
        core.prewarm { loadModelIfNeeded() }
    }

    fun release() = core.release()

    /**
     * Retires the engine for good: both ONNX sessions are closed ON the
     * generate queue (before the queue dies, so the native memory is really
     * released) and the queue is shut down. Used on engine switch — two
     * resident neural sessions are 300+ MB.
     */
    fun shutdown() {
        core.prewarmAndWait {
            closeSessions()
        }
        core.release()
        LogStore.info("PhononEngine: engine retired (sessions closed, queue shut down)")
    }

    /**
     * Drops the resident sessions (iOS idle-unload parity: sessions dropped
     * after 5 min idle). The next speak() reloads from disk.
     */
    fun unloadModel() {
        core.prewarm {
            closeSessions()
            LogStore.info("PhononEngine: model unloaded (idle)")
        }
    }

    private fun closeSessions() {
        runCatching { flowSession?.close() }
        runCatching { decodeSession?.close() }
        flowSession = null
        decodeSession = null
        tokenizer = null
        loadedVoice = null
        modelLoadAttempted = false
    }

    // MARK: - Model loading (generateQueue only)

    private fun loadModelIfNeeded() {
        if (modelLoadAttempted) return
        modelLoadAttempted = true
        try {
            if (!ModelFiles.isValid(EngineKind.PHONON)) {
                LogStore.error("PhononEngine: files missing or invalid — will retry on next speak")
                modelLoadAttempted = false
                return
            }
            val started = System.currentTimeMillis()
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val flow = env.createSession(File(AppFiles.phononOnnxDir, "model.onnx").absolutePath, opts)
            val decode = env.createSession(File(AppFiles.phononOnnxDir, "mimi_decoder.onnx").absolutePath, opts)
            val tok = PhononTokenizer(File(AppFiles.phononOnnxDir, "tokenizer.json").readBytes())
            ortEnv = env
            flowSession = flow
            decodeSession = decode
            tokenizer = tok
            LogStore.info("PhononEngine: 2 sessions + tokenizer loaded in ${System.currentTimeMillis() - started} ms")
            // The latch only survives success — a transient ORT error
            // (memory pressure, file lock) must not brick the engine (the
            // iOS lesson, already the KokoroEngine pattern here).
        } catch (e: Exception) {
            runCatching { flowSession?.close() }
            runCatching { decodeSession?.close() }
            flowSession = null
            decodeSession = null
            tokenizer = null
            modelLoadAttempted = false
            // The failing call and the top frames ride the log — the device
            // round diagnosed "Unsupported flags: 256" with nothing but the
            // message the first time.
            LogStore.error(
                "PhononEngine: model load failed: ${e.javaClass.simpleName}: ${e.message} @ " +
                    e.stackTrace.take(5).joinToString(" | ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } +
                    " — will retry on next speak"
            )
        }
    }

    /**
     * Voice embedding for the CURRENT voice, loaded on demand and cached.
     * The .bin is raw little-endian float32 in [1, 512, 125] order — the
     * graph takes [1, 125, 512], so this is a TRANSPOSE (NOT a reshape; a
     * reshape silently transposes the conditioning and the voice turns to
     * mush — the iOS contract-pinning round caught exactly that bug).
     */
    private fun voiceEmbedding(): FloatArray {
        loadedVoice?.let { (id, data) -> if (id == voice) return data }
        val file = File(AppFiles.phononVoicesDir, "$voice.bin")
        if (!file.isFile) error("Phonon voice $voice isn't downloaded — re-download the model.")
        val bytes = file.readBytes()
        if (bytes.size != VOICE_DIM * VOICE_FRAMES * 4) {
            error("Phonon voice embedding has an unexpected size (${bytes.size / 4} floats)")
        }
        val raw = FloatArray(VOICE_DIM * VOICE_FRAMES).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
        }
        val transposed = FloatArray(VOICE_DIM * VOICE_FRAMES)
        for (frame in 0 until VOICE_FRAMES) {
            for (dim in 0 until VOICE_DIM) {
                transposed[frame * VOICE_DIM + dim] = raw[dim * VOICE_FRAMES + frame]
            }
        }
        // One resident voice per slot: switching voices frees the last one.
        loadedVoice = voice to transposed
        return transposed
    }

    // MARK: - Synthesis (generateQueue only)

    /**
     * One sentence chunk: fresh KV/latent state → the prefill-once call
     * loop → windowed decode → PCM. The live speed is read per chunk from
     * the core (schedule sizing only — the model has no speed input).
     */
    private fun generateChunk(text: String): FloatArray {
        val flow = flowSession ?: error("Phonon flow model unavailable")
        val decode = decodeSession ?: error("Phonon decoder unavailable")
        val tokenizer = tokenizer ?: error("Phonon tokenizer unavailable")
        val env = ortEnv ?: error("ONNX environment unavailable")

        val ids = tokenizer.encode(text)
        val t = ids.size
        if (t <= 0) error("Phonon tokenizer produced no tokens for «${text.take(50)}»")
        val p = VOICE_FRAMES + t + 1
        // The hub's frame budget, with a 1.5× margin: EOS timing is
        // noise-dependent and two observed iOS runs of the same sentence
        // landed at 20/26 calls (Python seed) and past 26/26 (Swift seed).
        // The countdown stops the loop the moment EOS actually crosses; the
        // margin only buys the unlucky draws.
        val budget = ceil((t / 3.0 + 2) * FRAME_RATE * EOS_BUDGET_MARGIN).toInt()
        val framesAfterEOS = if (words(text) <= 4) 3 else 1
        val voiceData = voiceEmbedding()

        // TWO books: `kvRows` is the fed-back KV row count (P + 7 after
        // prefill, +8 per continuation call) and drives attention_mask/
        // position_ids; `latentCount` is the cumulative audio_latents row
        // count (+8 every call) and drives the latent history + this call's
        // frame slice.
        var kvRows = 0
        var latentCount = 0
        val newLatents = ArrayList<Float>(budget * LATENT_DIM)

        // Initial empty cache — owned HERE, closed in the outer finally.
        val initialKV = Array(FLOW_LAYERS * 2) {
            floatTensor(env, FloatArray(0), longArrayOf(1, ATTENTION_HEADS.toLong(), 0, HEAD_DIM.toLong()))
        }
        val initialLatent = floatTensor(env, FloatArray(0), longArrayOf(1, 0, LATENT_DIM.toLong()))

        // The whole-present feedback keeps each call's Result alive: its
        // present.* tensors ARE the next call's past.* inputs. The previous
        // result closes the moment the new one exists; per-call inputs close
        // right after each run (the KokoroEngine pattern).
        var result: OrtSession.Result? = null

        var countdown: Int? = null
        val rng = SeededGenerator(System.nanoTime())
        // Diagnostics for the device log: a healthy run's eos logits sit
        // deep negative while speaking and SPIKE at the sentence end; a
        // gradual climb or a never-crossing run within budget is the
        // degenerate-context signature, and a non-finite latent means the
        // runtime itself corrupted the graph state.
        var eosPeak = Float.NEGATIVE_INFINITY
        var eosPeakCall = 0

        val started = System.currentTimeMillis()
        var crossedEOS = false
        var softEOS = false
        var softStreak = 0
        var callIndex = 0

        try {
            val maxCalls = maxOf(1, ceil(budget.toDouble() / FRAMES_PER_CALL).toInt())
            while (callIndex < maxCalls) {
                val prefill = callIndex == 0
                val newRows = if (prefill) p + FRAMES_PER_CALL - 1 else FRAMES_PER_CALL
                val total = kvRows + newRows
                val noise = FloatArray(FRAMES_PER_CALL * LATENT_DIM) { rng.next() * sqrt(TEMPERATURE) }

                val created = ArrayList<OnnxTensor>(5)
                try {
                    // Continuation calls carry an EMPTY text/voice prompt —
                    // see the prefill note in the class doc. ORT accepts
                    // zero-size tensors (the iOS CI spike re-proves this on
                    // the vendored runtime every run).
                    val idsTensor = if (prefill) {
                        longTensor(env, ids.map { it.toLong() }.toLongArray(), longArrayOf(1, t.toLong()))
                    } else {
                        longTensor(env, LongArray(0), longArrayOf(1, 0))
                    }
                    created.add(idsTensor)
                    val voiceTensor = if (prefill) {
                        floatTensor(env, voiceData, longArrayOf(1, VOICE_FRAMES.toLong(), VOICE_DIM.toLong()))
                    } else {
                        floatTensor(env, FloatArray(0), longArrayOf(1, 0, VOICE_DIM.toLong()))
                    }
                    created.add(voiceTensor)
                    val maskTensor = longTensor(env, LongArray(total) { 1L }, longArrayOf(1, total.toLong()))
                    created.add(maskTensor)
                    // Positions: prefill pins the voice+text rows at their
                    // true absolute positions 0..<P-1 (RoPE baked exactly
                    // like the reference prefill) with the input slot at P-1
                    // and the 7 frames after — i.e. 0..<(P+7) contiguous.
                    // Continuation rows ride the same timeline: latent n sits
                    // at position P-1+n, and since the fed-back cache holds
                    // kvRows = P-1+n rows at call n, the next 8 rows are
                    // simply [kvRows, kvRows+8).
                    val positionValues = if (prefill) {
                        LongArray(p + FRAMES_PER_CALL - 1) { it.toLong() }
                    } else {
                        LongArray(FRAMES_PER_CALL) { (kvRows + it).toLong() }
                    }
                    val positionTensor = longTensor(env, positionValues, longArrayOf(1, positionValues.size.toLong()))
                    created.add(positionTensor)
                    val noiseTensor = floatTensor(env, noise, longArrayOf(1, FRAMES_PER_CALL.toLong(), LATENT_DIM.toLong()))
                    created.add(noiseTensor)

                    val inputs = LinkedHashMap<String, OnnxTensor>()
                    inputs["input_ids"] = idsTensor
                    inputs["audio_prompt_latents"] = voiceTensor
                    inputs["attention_mask"] = maskTensor
                    inputs["position_ids"] = positionTensor
                    inputs["noise"] = noiseTensor
                    val current = result
                    if (current != null) {
                        // Whole-present feedback: the model card's driver
                        // convention ("feed every output back under its past
                        // name"). The prefill's present carries the voice+text
                        // K/V computed against an empty cache; from then on
                        // they are never recomputed.
                        for (layer in 0 until FLOW_LAYERS) {
                            inputs["past_key_values.$layer.key"] = tensorOrError(current, "present.$layer.key")
                            inputs["past_key_values.$layer.value"] = tensorOrError(current, "present.$layer.value")
                        }
                        inputs["past_key_values.audio_latents"] = tensorOrError(current, "present.audio_latents")
                    } else {
                        for (layer in 0 until FLOW_LAYERS) {
                            inputs["past_key_values.$layer.key"] = initialKV[layer * 2]
                            inputs["past_key_values.$layer.value"] = initialKV[layer * 2 + 1]
                        }
                        inputs["past_key_values.audio_latents"] = initialLatent
                    }

                    val newResult = flow.run(inputs)

                    // The flow's audio_latents is the CUMULATIVE latent
                    // history, denormalized; only the trailing 8 rows are
                    // this call's frames. Read before the previous result
                    // (this call's inputs) is retired.
                    val cumulative = tensorOrError(newResult, "audio_latents")
                    val cumulativeBuffer = cumulative.floatBuffer ?: error("audio_latents is not float32")
                    val frameValues = FloatArray(FRAMES_PER_CALL * LATENT_DIM)
                    cumulativeBuffer.position(latentCount * LATENT_DIM)
                    cumulativeBuffer.get(frameValues)
                    for (value in frameValues) {
                        if (!value.isFinite()) {
                            error("Phonon: non-finite latent at call $callIndex — the runtime corrupted the graph state")
                        }
                    }
                    for (value in frameValues) newLatents.add(value)
                    val eosBuffer = tensorOrError(newResult, "eos_logits").floatBuffer
                        ?: error("eos_logits is not float32")
                    val eos = FloatArray(FRAMES_PER_CALL).also { eosBuffer.get(it) }

                    runCatching { current?.close() }
                    result = newResult
                    kvRows += newRows
                    latentCount += FRAMES_PER_CALL

                    var callPeak = Float.NEGATIVE_INFINITY
                    for (value in eos) if (value > callPeak) callPeak = value
                    if (callPeak > eosPeak) {
                        eosPeak = callPeak
                        eosPeakCall = callIndex
                    }

                    // EOS countdown (Python's PocketTTSEosThresholdCriteria):
                    // the frame that crossed the threshold and the tail
                    // frames are kept.
                    //
                    // SOFT-EOS SAFETY NET: healthy runs sit deep negative
                    // (−5…−9) until the final spike, but a degenerate context
                    // can leave the eos logit hovering just under the
                    // threshold (~1–2, never crossing 4.0) — iOS saw 20–45 s
                    // tails of that. Two consecutive CALLS with their
                    // per-call peak above the soft threshold without a real
                    // crossing = stop and keep the clean head. The two-call
                    // window is load-bearing: healthy runs show single-call
                    // dips up to ~+0.5 (x86 sweep), never a sustained
                    // plateau.
                    var done = false
                    for (value in eos) {
                        val c = countdown
                        if (c == null && value > EOS_THRESHOLD) {
                            countdown = framesAfterEOS
                            crossedEOS = true
                        } else if (c != null) {
                            val next = c - 1
                            countdown = next
                            done = next <= 0
                        }
                    }
                    if (countdown == null) {
                        softStreak = if (callPeak > SOFT_EOS_THRESHOLD) softStreak + 1 else 0
                        if (softStreak >= 2) {
                            softEOS = true
                            done = true
                        }
                    }
                    callIndex += 1
                    if (done) break
                } finally {
                    created.forEach { runCatching { it.close() } }
                }
            }
        } finally {
            runCatching { result?.close() }
            initialKV.forEach { runCatching { it.close() } }
            runCatching { initialLatent.close() }
        }
        if (softEOS) {
            LogStore.info(
                "PhononEngine: soft-EOS stop (sustained eos > $SOFT_EOS_THRESHOLD, never crossed $EOS_THRESHOLD) — kept clean head «${text.take(50)}»"
            )
        } else if (!crossedEOS) {
            LogStore.info("PhononEngine: frame budget reached before EOS — audio may be truncated «${text.take(50)}»")
        }

        // Windowed decode (decoder rotary ceiling — see DECODE_WINDOW).
        val totalLatents = newLatents.size / LATENT_DIM
        val pcm = ArrayList<Float>(totalLatents * SAMPLES_PER_FRAME)
        var start = 0
        while (start < totalLatents) {
            val count = minOf(DECODE_WINDOW, totalLatents - start)
            val window = newLatents.subList(start * LATENT_DIM, (start + count) * LATENT_DIM).toFloatArray()
            val latentsTensor = floatTensor(env, window, longArrayOf(1, count.toLong(), LATENT_DIM.toLong()))
            val numFramesTensor = longTensor(env, longArrayOf(count.toLong()), longArrayOf(1))
            try {
                decode.run(
                    mapOf(
                        "audio_latents" to latentsTensor,
                        "num_frames" to numFramesTensor,
                    )
                ).use { out ->
                    val lengths = tensorOrError(out, "audio_lengths")
                    val lengthsBuffer = lengths.longBuffer ?: error("audio_lengths is not int64")
                    val samples = LongArray(1).also { lengthsBuffer.get(it) }[0].toInt()
                    if (samples <= 0) error("Phonon decoder produced no samples for this window")
                    val values = tensorOrError(out, "audio_values")
                    val valuesBuffer = values.floatBuffer ?: error("audio_values is not float32")
                    val windowPcm = FloatArray(samples).also { valuesBuffer.get(it) }
                    pcm.addAll(windowPcm.toList())
                }
            } finally {
                runCatching { latentsTensor.close() }
                runCatching { numFramesTensor.close() }
            }
            start += count
        }
        if (pcm.isEmpty()) error("Phonon returned no audio for this chunk")

        val duration = pcm.size / SAMPLE_RATE.toDouble()
        val renderSeconds = (System.currentTimeMillis() - started) / 1000.0
        LogStore.info(
            String.format(
                Locale.US,
                "PhononEngine: %d tokens → %.2fs audio (%d frames, %d calls) in %.2fs, eos peak %.2f at call %d (%s)",
                t, duration, totalLatents, callIndex, renderSeconds, eosPeak, eosPeakCall,
                VoiceCatalog.shortName(voice, "phonon"),
            )
        )
        return pcm.toFloatArray()
    }

    private fun words(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotEmpty() }

    // MARK: - Tensor plumbing

    private fun tensorOrError(result: OrtSession.Result, name: String): OnnxTensor =
        result.get(name).orElse(null) as? OnnxTensor ?: error("Phonon: missing output '$name'")

    private fun longTensor(env: OrtEnvironment, values: LongArray, dims: LongArray): OnnxTensor {
        val buffer = ByteBuffer
            .allocateDirect(values.size * 8)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
            .put(values)
            .flip() as java.nio.LongBuffer
        return OnnxTensor.createTensor(env, buffer, dims)
    }

    private fun floatTensor(env: OrtEnvironment, values: FloatArray, dims: LongArray): OnnxTensor {
        val buffer = ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .flip() as java.nio.FloatBuffer
        return OnnxTensor.createTensor(env, buffer, dims)
    }

    /**
     * Deterministic SplitMix64 → Box-Muller standard normal — the same noise
     * recipe as iOS's SeededGenerator (PhononEngine.swift), so the two
     * platforms draw the same kind of noise. EOS timing is noise-dependent;
     * only the recipe matters, not the seed (time-based on both).
     */
    private class SeededGenerator(seed: Long) {
        private var state = seed
        private var hasSpare = false
        private var spare = 0f

        private fun nextUniform(): Float {
            // The SplitMix64 constants exceed Long.MAX_VALUE — Kotlin hex
            // literals need the ULong form, converted back to the same bit
            // pattern (Swift's UInt64 &+/&* wrap semantics).
            state += 0x9E3779B97F4A7C15uL.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            z = z xor (z ushr 31)
            return (z ushr 11).toFloat() / (1L shl 53).toFloat()
        }

        fun next(): Float {
            if (hasSpare) {
                hasSpare = false
                return spare
            }
            val u1 = maxOf(nextUniform(), 1e-12f)
            val u2 = nextUniform()
            val magnitude = sqrt(-2 * ln(1 - u1.toDouble())).toFloat()
            spare = magnitude * cos(2 * Math.PI.toFloat() * u2)
            hasSpare = true
            return magnitude * sin(2 * Math.PI.toFloat() * u2)
        }
    }

    private companion object {
        // Pinned graph contract — see the iOS repo's Docs/PHONON-PORT.md for
        // every constant's provenance.
        const val SAMPLE_RATE = 24_000
        const val SAMPLES_PER_FRAME = 1920
        const val FRAMES_PER_CALL = 8
        const val LATENT_DIM = 32
        const val VOICE_DIM = 512
        const val VOICE_FRAMES = 125
        const val FRAME_RATE = 12.5
        const val EOS_THRESHOLD = 4.0f
        /** Soft-EOS safety net (see the countdown block in generateChunk). */
        const val SOFT_EOS_THRESHOLD = 1.0f
        const val EOS_BUDGET_MARGIN = 1.5
        const val TEMPERATURE = 0.3f
        const val ATTENTION_HEADS = 8
        const val HEAD_DIM = 64
        const val FLOW_LAYERS = 6

        /**
         * The Mimi decoder's rotary positions run over the upsampled
         * sequence against an 8000-position cos/sin cache — empirically
         * ~500 latents per call is safe (512 fails). The ceiling that
         * actually bites is MEMORY: the decoder's ConvTranspose activations
         * cost ~2.7 MB per latent (iOS measured: 160 latents ≈ +430 MB RSS,
         * 400 ≈ +1.1 GB). chunkMaxChars 180 keeps real chunks ≤ ~150
         * latents, so this window never splits in practice; it is the
         * safety valve for pathological tokenization.
         */
        const val DECODE_WINDOW = 160
    }
}
