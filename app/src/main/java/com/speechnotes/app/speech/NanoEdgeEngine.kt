package com.speechnotes.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.speechnotes.app.data.LogStore
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.logic.ChunkSchedule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.json.JSONObject

/**
 * The nano-edge-tts engine: Kokoro-82M's text side with its 53.3M-parameter
 * waveform decoder replaced by drmhse's 1.75M-parameter harmonic-plus-noise
 * decoder ([drmhse/nano-edge-tts](https://huggingface.co/drmhse/nano-edge-tts),
 * Apache-2.0, distilled from Kokoro's own decoder — same voices, same
 * pronunciation, same 24 kHz). Ported from speechnotes-linux
 * `crates/sn-tts/src/nano_edge.rs`; the catalog entry mirrors sn-tts
 * manager.rs. The published weights are PyTorch; the model set installs the
 * ONNX export in the same two-graph split the Linux runtime runs:
 *
 * * `text-side.onnx` — input_ids [1, N], style [1, 256], speed [1] → the
 *   decoder inputs (asr [1, 512, T], F0 [1, T], N [1, T]) plus the style
 *   slice the decoder itself takes (s [1, 128]). The BOS/EOS wrap the
 *   reference `KModel.forward` applies lives inside the graph, so the ids
 *   fed here are the same unwrapped stream Kokoro gets.
 * * `nano-decoder.onnx` — those four tensors → waveform [1, S].
 *
 * Two sessions instead of one because the decoder's interpolations size
 * themselves off input shapes: the export keeps them symbolic through the
 * dynamo path, and the handoff between the graphs is where the runtime
 * meets the frame count — which is also the cap the creak-noise window was
 * precomputed at.
 *
 * The voice banks, the tokenizer and the phoneme front end are kokoro's
 * own — the text side IS kokoro's, so the engines tokenize identically (via
 * the shared MisakiG2P) and a voice code means the same speaker in both.
 * Unlike the Kokoro engine (voices.npz, every bank resident), only the
 * CURRENT voice's bank is loaded — one 522 KB plane, reloaded on voice
 * change, the Linux design.
 *
 * All pipeline machinery (chunking, pacing, scheduling, play-time position
 * tracking, WAV export) lives in [StreamingPlaybackCore]; this class
 * contributes model loading + synthesis, confined to the core's
 * generateQueue.
 */
class NanoEdgeEngine : SpeechEngine {

    override var name: String = "Nano Edge — on-device neural (English)"

    override var onStateChanged: ((SpeechState) -> Unit)? = null
    override var onProgress: ((Double) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null
    override var onPlayedChars: ((Int) -> Unit)? = null

    /** Voice codename — one of the 28 English Kokoro voices (same bank
     *  codes as the Kokoro engine; default af_heart, sn-tts manager.rs). */
    var voice: String = "af_heart"

    val core: StreamingPlaybackCore

    // Model state — the core's generateQueue only.
    private var ortEnv: OrtEnvironment? = null
    private var textSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private val vocab = HashMap<String, Int>()
    private var spaceId: Int = NanoEdgeText.DEFAULT_SPACE_ID
    /** The loaded voice bank, rows of [NanoEdgeText.REF_STYLE_DIM] floats —
     *  single slot, reloaded when [voice] changes. A missing .bin surfaces
     *  at synthesis time, as on Linux. */
    private var loadedVoice: Pair<String, FloatArray>? = null
    private var modelLoadAttempted = false

    /** G2P — the Kotlin port of MisakiSwift (kokoro's own front end). */
    private val g2p = MisakiG2P()

    init {
        core = StreamingPlaybackCore(
            config = StreamingPlaybackCore.Config(
                sampleRate = 24_000,
                // The text side IS kokoro's, so the kokoro chunk caps and the
                // kokoro schedule seed apply verbatim (Linux runs nano in the
                // kokoro-family plan unchanged).
                chunkMaxChars = 160,
                firstChunkMaxChars = 100,
                schedule = ChunkSchedule.State(
                    targetSeconds = ChunkSchedule.initialTargetSeconds,
                    fallbackCharactersPerAudioSecond = ChunkSchedule.kokoroCharactersPerAudioSecond,
                ),
                exportInterChunkSilence = 0.05f,
                logPrefix = "NanoEdgeEngine",
            ),
            generateChunk = { text -> generateChunk(text) },
            isModelReady = {
                loadModelIfNeeded()
                textSession != null && decoderSession != null
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
        if (!ModelFiles.isValid(EngineKind.NANO_EDGE)) {
            LogStore.error("NanoEdgeEngine: model files missing — asked to speak")
            return
        }
        core.speak(text)
    }

    override fun pause() = core.pause()
    override fun resume() = core.resume()
    override fun stop() = core.stop()

    /** Loads the ONNX sessions + vocab and warms G2P on the generate queue. */
    override fun prewarm() {
        core.prewarm {
            loadModelIfNeeded()
            if (textSession != null) {
                runCatching { g2p.phonemize("Warm up.", british = voice.startsWith("b")) }
            }
        }
    }

    fun release() = core.release()

    /**
     * Retires the engine for good: both ONNX sessions are closed ON the
     * generate queue (before the queue dies, so the native memory is really
     * released) and the queue is shut down. Used on engine switch.
     */
    fun shutdown() {
        core.prewarmAndWait {
            closeSessions()
        }
        core.release()
        LogStore.info("NanoEdgeEngine: engine retired (sessions closed, queue shut down)")
    }

    /**
     * Drops the resident sessions + voice bank (idle-unload parity with the
     * other neural engines). The next speak() reloads from disk.
     */
    fun unloadModel() {
        core.prewarm {
            closeSessions()
            LogStore.info("NanoEdgeEngine: model unloaded (idle)")
        }
    }

    private fun closeSessions() {
        runCatching { textSession?.close() }
        runCatching { decoderSession?.close() }
        textSession = null
        decoderSession = null
        ortEnv = null
        vocab.clear()
        loadedVoice = null
        modelLoadAttempted = false
    }

    // MARK: - Model loading (generateQueue only)

    private fun loadModelIfNeeded() {
        if (modelLoadAttempted) return
        modelLoadAttempted = true
        try {
            if (!ModelFiles.isValid(EngineKind.NANO_EDGE)) {
                LogStore.error("NanoEdgeEngine: files missing or invalid — will retry on next speak")
                modelLoadAttempted = false
                return
            }
            val started = System.currentTimeMillis()
            val dir = AppFiles.nanoEdgeDir
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val text = env.createSession(File(dir, "text-side.onnx").absolutePath, opts)
            val decoder = env.createSession(File(dir, "nano-decoder.onnx").absolutePath, opts)

            // Tokenizer vocab (per-character lookup, matching the reference).
            val tokenizerJson = JSONObject(File(dir, "tokenizer.json").readText())
            val vocabJson = tokenizerJson.optJSONObject("model")?.optJSONObject("vocab")
            if (vocabJson != null) {
                for (key in vocabJson.keys()) {
                    vocab[key] = vocabJson.getInt(key)
                }
            }
            if (vocab.isEmpty()) error("tokenizer.json has no vocab")
            spaceId = vocab[" "] ?: NanoEdgeText.DEFAULT_SPACE_ID

            ortEnv = env
            textSession = text
            decoderSession = decoder
            LogStore.info(
                "NanoEdgeEngine: ${vocab.size} vocab entries, 2 graphs ready " +
                    "in ${System.currentTimeMillis() - started} ms"
            )
        } catch (e: Exception) {
            closeSessions()
            LogStore.error(
                "NanoEdgeEngine: model load failed: ${e.message} — will retry on next speak"
            )
        }
    }

    /**
     * The CURRENT voice's bank, loaded on the generate queue and cached in a
     * single slot (Linux load_voice/set_voice design). A missing or
     * mis-shaped .bin throws HERE, at synthesis time — the download gate
     * (min=max 522,240 bytes) should make that unreachable.
     */
    private fun voiceFlatFor(name: String): FloatArray {
        loadedVoice?.let { (loaded, flat) -> if (loaded == name) return flat }
        val bytes = File(AppFiles.nanoEdgeVoicesDir, "$name.bin").readBytes()
        val flat = NanoEdgeText.validateVoiceBank(bytes)
        loadedVoice = name to flat
        return flat
    }

    // MARK: - Synthesis (generateQueue only)

    /** The text side's outputs, all read (and copied) while its result lives. */
    private class TextSide(
        val asr: FloatArray,
        val asrFrames: Int,
        val f0: FloatArray,
        val n: FloatArray,
        val s: FloatArray,
    )

    private fun generateChunk(text: String): FloatArray {
        val started = System.currentTimeMillis()
        val flat = voiceFlatFor(voice)
        val phonemes = g2p.phonemize(text, british = voice.startsWith("b"))
        if (phonemes.isEmpty()) error("phonemization failed")
        val tokens = NanoEdgeText.tokenize(phonemes, text, vocab, spaceId)
        if (tokens.dropped.isNotEmpty()) {
            LogStore.warn(
                "NanoEdgeEngine: phoneme(s) not in kokoro vocab, substituted with space: ${tokens.dropped}"
            )
        }
        val session = textSession ?: error("model unavailable")
        val decoder = decoderSession ?: error("decoder unavailable")

        // The full 256-wide style row, by phoneme length — the reference
        // pipeline's ref_s; the text-side graph slices the decoder's half.
        val styleDim = NanoEdgeText.REF_STYLE_DIM
        val rows = flat.size / styleDim
        val offset = NanoEdgeText.styleRowOffset(tokens.phonemeChars, rows) * styleDim
        if (offset + styleDim > flat.size) error("voice shape ${flat.size}")
        val style = flat.copyOfRange(offset, offset + styleDim)

        val env = ortEnv ?: error("ONNX environment unavailable")

        // ---- Text side: input_ids [1,N], style [1,256], speed [1]
        //      → asr [1,512,T], f0 [1,T], n [1,T], s [1,128].
        val idsTensor = longTensor(env, tokens.ids.map { it.toLong() }.toLongArray(), longArrayOf(1, tokens.ids.size.toLong()))
        val styleTensor = floatTensor(env, style, longArrayOf(1, styleDim.toLong()))
        val speedTensor = floatTensor(env, floatArrayOf(core.speed), longArrayOf(1))
        val textSide: TextSide = try {
            session.run(
                mapOf(
                    "input_ids" to idsTensor,
                    "style" to styleTensor,
                    "speed" to speedTensor,
                )
            ).use { result ->
                val asrTensor = tensorOrError(result, "asr")
                val shape = asrTensor.info.shape
                if (shape.size < 3 || shape[2] <= 0) {
                    error("nano-edge-tts text side gave no frame count")
                }
                val f0 = floatOutput(result, "f0")
                val n = floatOutput(result, "n")
                val s = floatOutput(result, "s")
                if (f0.size != n.size) {
                    error("nano-edge-tts text side: f0 ${f0.size} vs n ${n.size} frames")
                }
                // The text side emits asr at half the F0 frame count (the
                // duration expansion keys the prosody predictor at 80 Hz, the
                // text encoder at 40); the decoder's mapper upsamples asr
                // internally, so each tensor feeds at its own length. The
                // creak-noise window is precomputed at MAX_FRAMES against
                // THIS axis (F0, the 80 Hz one).
                if (f0.size > NanoEdgeText.MAX_FRAMES) {
                    error("nano-edge-tts chunk predicts ${f0.size} frames, past the ${NanoEdgeText.MAX_FRAMES}-frame window")
                }
                val asrBuffer = asrTensor.floatBuffer ?: error("nano-edge-tts: asr is not float32")
                val asr = FloatArray(asrBuffer.remaining())
                asrBuffer.get(asr)
                TextSide(asr, shape[2].toInt(), f0, n, s)
            }
        } finally {
            runCatching { idsTensor.close() }
            runCatching { styleTensor.close() }
            runCatching { speedTensor.close() }
        }

        // ---- Decoder: asr [1,512,T], f0 [1,T], n [1,T], s [1,128] → waveform [1,S].
        val asrTensor = floatTensor(env, textSide.asr, longArrayOf(1, 512, textSide.asrFrames.toLong()))
        val f0Tensor = floatTensor(env, textSide.f0, longArrayOf(1, textSide.f0.size.toLong()))
        val nTensor = floatTensor(env, textSide.n, longArrayOf(1, textSide.n.size.toLong()))
        val sTensor = floatTensor(env, textSide.s, longArrayOf(1, 128))
        val wave: FloatArray = try {
            decoder.run(
                mapOf(
                    "asr" to asrTensor,
                    "f0" to f0Tensor,
                    "n" to nTensor,
                    "s" to sTensor,
                )
            ).use { result ->
                floatOutput(result, "waveform")
            }
        } finally {
            runCatching { asrTensor.close() }
            runCatching { f0Tensor.close() }
            runCatching { nTensor.close() }
            runCatching { sTensor.close() }
        }
        if (wave.isEmpty()) {
            error("nano-edge-tts produced no samples for speakable text")
        }
        LogStore.info(
            String.format(
                Locale.US,
                "NanoEdgeEngine: %d tokens → %.1fs audio in %.2fs",
                tokens.ids.size,
                wave.size / 24_000.0,
                (System.currentTimeMillis() - started) / 1000.0,
            )
        )
        return wave
    }

    private fun tensorOrError(result: OrtSession.Result, name: String): OnnxTensor =
        result.get(name).orElse(null) as? OnnxTensor ?: error("nano-edge-tts: missing output '$name'")

    /** Copies an output tensor's float payload while [result] is open. */
    private fun floatOutput(result: OrtSession.Result, name: String): FloatArray {
        val tensor = tensorOrError(result, name)
        val buffer = tensor.floatBuffer ?: error("nano-edge-tts: output '$name' is not float32")
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

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
}
