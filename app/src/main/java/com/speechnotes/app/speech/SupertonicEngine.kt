package com.speechnotes.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.Result
import com.speechnotes.app.data.LogStore
import com.speechnotes.app.logic.AppFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Supertonic engine (Supertone supertonic-3): flow-matching TTS over 4 ONNX
 * sessions, CPU-only. Ported from SupertonicEngine.swift +
 * Supertonic/Helper.swift (speechnotes-ios), which vendored the upstream
 * supertone-inc/supertonic runner (MIT). 31 languages via the unicode
 * indexer (no G2P), 10 voice styles (M1–M5 male, F1–F5 female), native
 * speed control through the duration predictor.
 *
 * Graph contract (from the vendored Helper):
 *   duration_predictor: text_ids [1,N] i64, style_dp [1,d1,d2] f32,
 *                       text_mask [1,1,N] f32 → duration [1] f32
 *   text_encoder:       text_ids, style_ttl [1,t1,t2] f32, text_mask → text_emb
 *   vector_estimator:   noisy_latent [1,latentDimVal,latentLen] f32, text_emb,
 *                       style_ttl, latent_mask [1,1,latentLen], text_mask,
 *                       current_step [1] f32 (step index), total_step [1] f32
 *                       → denoised_latent
 *   vocoder:            latent → wav_tts (trimmed to sampleRate · duration)
 */
class SupertonicEngine : SpeechEngine {

    override var name: String = "Supertonic (CPU)"

    override var onStateChanged: ((SpeechState) -> Unit)? = null
    override var onProgress: ((Double) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null
    override var onPlayedChars: ((Int) -> Unit)? = null

    /** Voice style id — one of "M1"…"F5". */
    var voice: String = "M1"

    /** ISO language code (see AVAILABLE_LANGS); "en" default. */
    var lang: String = "en"

    /**
     * The quality dial (iOS SupertonicEngine.swift:61-95 parity): how the
     * user trades render quality against speed, stored under
     * `supertonicRenderQuality` and applied from the NEXT chunk (the step
     * count is read per synth, no restart). Default HIGH.
     */
    enum class RenderQuality(val raw: String, val label: String) {
        HIGH("high", "High — always full"),
        BALANCED("balanced", "Balanced — middle step"),
        AUTOMATIC("automatic", "Automatic — sheds when very hot"),
        FAST("fast", "Fast — lowest quality");

        companion object {
            fun fromRaw(raw: String?): RenderQuality = entries.firstOrNull { it.raw == raw } ?: HIGH
        }
    }

    /** The user's dial; SpeechPlayer keeps this in sync with the pref. */
    @Volatile
    var renderQuality: RenderQuality = RenderQuality.HIGH

    /**
     * Flow-matching denoising steps for the chunk being rendered. HIGH is
     * the full 8 — the best sound, roughly twice the generation work
     * (still faster than playback). BALANCED is 6 of the 8 steps: the
     * ladder is not linear in time (first steps = coarse trajectory, last
     * = detail), so 6 lands measurably closer to 8 at ~3/4 of its time.
     * AUTOMATIC is the policy's thermal gear — sheds to 4 ONLY at critical
     * thermal (RTF 1.68 measured there vs 0.42–0.49 nominal — iOS
     * RenderAheadBankPolicy:120-131; the 2026-10-06 device verdict was that
     * shedding at `serious` rendered ~90% of a book at 4 steps with audible
     * artifacts, so quality outranks speed everywhere below critical).
     * FAST always uses 4: quickest, with audible artifacts.
     */
    private val totalStep: Int
        get() = when {
            // An offline export has no deadline — always full quality
            // (iOS `core.isExporting` bypass parity).
            core.isExporting -> RenderAheadBankPolicy.FULL_QUALITY_TOTAL_STEP
            else -> when (renderQuality) {
                RenderQuality.HIGH -> RenderAheadBankPolicy.FULL_QUALITY_TOTAL_STEP
                RenderQuality.BALANCED -> 6
                RenderQuality.AUTOMATIC -> RenderAheadBankPolicy.totalStep(thermalState())
                RenderQuality.FAST -> 4
            }
        }

    /**
     * Android thermal status at read time. Read per CHUNK (the getter runs
     * inside generateChunk), not once at init — a long book session heats
     * the phone mid-listen and the shed must respond.
     */
    private fun thermalState(): RenderAheadBankPolicy.ThermalPressure {
        val pm = com.speechnotes.app.logic.AppFiles.context
            ?.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return RenderAheadBankPolicy.ThermalPressure.NOMINAL
        val status = pm.currentThermalStatus
        return RenderAheadBankPolicy.ThermalPressure.fromStatusRawValue(status)
    }

    /**
     * Upstream ExampleONNX caps the FIRST chunk small so speech starts fast:
     * a 155-char opener took 15.3 s to render on device, an 11-char one 1.7 s.
     * Later chunks stay full size.
     */
    private val FIRST_CHUNK_MAX_CHARS = 60

    val core: StreamingPlaybackCore

    // Model state — the core's generateQueue only.
    private var durationPredictor: OrtSession? = null
    private var textEncoder: OrtSession? = null
    private var vectorEstimator: OrtSession? = null
    private var vocoder: OrtSession? = null
    private var indexer: LongArray = LongArray(0)
    private var styles: MutableMap<String, Style> = HashMap()
    private var modelLoadAttempted = false

    private var sampleRate: Int = 24_000
    private var baseChunkSize: Int = 1024
    private var chunkCompress: Int = 1
    private var latentDim: Int = 1024

    private class Style(val ttl: FloatArray, val ttlDims: LongArray, val dp: FloatArray, val dpDims: LongArray)

    init {
        core = StreamingPlaybackCore(
            config = StreamingPlaybackCore.Config(
                sampleRate = 24_000,
                chunkMaxChars = 200,
                firstChunkMaxChars = FIRST_CHUNK_MAX_CHARS,
                exportInterChunkSilence = 0.05f,
                logPrefix = "SupertonicEngine",
            ),
            generateChunk = { text -> generateChunk(text) },
            isModelReady = {
                loadModelIfNeeded()
                durationPredictor != null
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
        if (!ModelFiles.isValid(EngineKind.SUPERTONIC)) {
            LogStore.error("SupertonicEngine: model files missing")
            return
        }
        if (lang !in AVAILABLE_LANGS) {
            LogStore.error("SupertonicEngine: unsupported language $lang")
            return
        }
        core.speak(text)
    }

    override fun pause() = core.pause()
    override fun resume() = core.resume()
    override fun stop() = core.stop()

    /** Loads the four ONNX sessions + style bank on the generate queue. */
    override fun prewarm() {
        core.prewarm { loadModelIfNeeded() }
    }

    fun release() = core.release()

    /**
     * Drops the four resident ONNX sessions (iOS idle-unload parity:
     * SpeechPlayer.swift:694,717 — sessions dropped after 5 min idle). Runs
     * on the generate queue via prewarm; the next speak() reloads from disk.
     */
    fun unloadModel() {
        core.prewarm {
            clearSessions()
            indexer = LongArray(0)
            styles = HashMap()
            modelLoadAttempted = false
            LogStore.info("SupertonicEngine: model unloaded (idle)")
        }
    }

    // MARK: - Model loading (generateQueue only)

    private fun loadModelIfNeeded() {
        if (modelLoadAttempted) return
        try {
            if (!ModelFiles.isValid(EngineKind.SUPERTONIC)) {
                LogStore.error("SupertonicEngine: files missing or invalid at ${AppFiles.supertonicDir}")
                return
            }
            val started = System.currentTimeMillis()
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // The flow-matching loop is the throughput ceiling: on a phone
                // a 600-char batch chunk must render faster than it plays or
                // the stream starves (the "PDF narration is slow/stops" report).
                // Give the CPU every core; 4 left the bigger cores idle.
                setIntraOpNumThreads(minOf(8, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            }
            durationPredictor = env.createSession(File(AppFiles.supertonicOnnxDir, "duration_predictor.onnx").absolutePath, opts)
            textEncoder = env.createSession(File(AppFiles.supertonicOnnxDir, "text_encoder.onnx").absolutePath, opts)
            vectorEstimator = env.createSession(File(AppFiles.supertonicOnnxDir, "vector_estimator.onnx").absolutePath, opts)
            vocoder = env.createSession(File(AppFiles.supertonicOnnxDir, "vocoder.onnx").absolutePath, opts)

            val ttsJson = JSONObject(File(AppFiles.supertonicOnnxDir, "tts.json").readText())
            // Sample rate comes from ae (44_100 for the shipped graphs — the
            // engine's 24_000 provisional value was only correct for an older
            // revision, and an AudioTrack built at the wrong rate plays noise
            // at the wrong pitch or drops the buffer outright).
            sampleRate = ttsJson.getJSONObject("ae").getInt("sample_rate")
            baseChunkSize = ttsJson.getJSONObject("ae").optInt("base_chunk_size", 512)
            chunkCompress = ttsJson.getJSONObject("ttl").getInt("chunk_compress_factor")
            // ttl.latent_dim is the UNCOMPRESSED latent (24). The graphs take
            // latent_dim × chunk_compress = 144 — using 24 straight from the
            // config made every vector_estimator/vocoder run die on a shape
            // mismatch, which is the whole "Supertonic is silent" bug.
            latentDim = ttsJson.getJSONObject("ttl").getInt("latent_dim") * chunkCompress
            core.setSampleRate(sampleRate)

            val indexerText = File(AppFiles.supertonicOnnxDir, "unicode_indexer.json").readText()
            val jsonArray = JSONArray(indexerText)
            indexer = LongArray(jsonArray.length()) { jsonArray.getLong(it) }

            // Each style carries batch dim 1 (one call per voice).
            for (v in ModelFiles.supertonicVoices) {
                val styleFile = File(AppFiles.supertonicStylesDir, "$v.json")
                styles[v] = parseStyle(JSONObject(styleFile.readText()))
            }

            modelLoadAttempted = true
            LogStore.info(
                "SupertonicEngine: 4 sessions + ${styles.size} styles ready in ${System.currentTimeMillis() - started} ms " +
                    "(sr=$sampleRate, baseChunk=$baseChunkSize, compress=$chunkCompress, latentDim=$latentDim)"
            )
            // The exact graph contract. A model revision that renames an input
            // is then visible in the device log instead of showing up as a
            // generic "chunk failed".
            listOf(
                "dp" to durationPredictor,
                "te" to textEncoder,
                "ve" to vectorEstimator,
                "vo" to vocoder,
            ).forEach { (tag, session) ->
                session?.let {
                    LogStore.info(
                        "SupertonicEngine: $tag in=[${it.inputNames.joinToString()}] out=[${it.outputNames.joinToString()}]"
                    )
                }
            }
        } catch (e: Exception) {
            clearSessions()
            modelLoadAttempted = false
            LogStore.error("SupertonicEngine: model load failed: ${e.message}")
        }
    }

    private fun clearSessions() {
        listOf(durationPredictor, textEncoder, vectorEstimator, vocoder).forEach { runCatching { it?.close() } }
        durationPredictor = null; textEncoder = null; vectorEstimator = null; vocoder = null
    }

    /** Flattens `data` per `dims` (batch dim 1). */
    private fun parseStyle(json: JSONObject): Style {
        fun component(name: String): Pair<FloatArray, LongArray> {
            val obj = json.getJSONObject(name)
            val dims = obj.getJSONArray("dims").let { arr -> LongArray(arr.length()) { arr.getLong(it) } }
            val flat = flatten3d(obj.getJSONArray("data"))
            return flat to dims
        }
        val (ttl, ttlDims) = component("style_ttl")
        val (dp, dpDims) = component("style_dp")
        return Style(ttl, ttlDims, dp, dpDims)
    }

    // MARK: - Synthesis (generateQueue only)

    private fun generateChunk(text: String): FloatArray {
        val dp = durationPredictor ?: error("model unavailable")
        val te = textEncoder ?: error("model unavailable")
        val ve = vectorEstimator ?: error("model unavailable")
        val vo = vocoder ?: error("model unavailable")
        val style = styles[voice] ?: styles.values.firstOrNull() ?: error("no style for voice $voice")
        val started = System.currentTimeMillis()
        val env = OrtEnvironment.getEnvironment()

        val processed = preprocessText(text, lang)
        val scalars = processed.codePoints().toArray()
        // Unknown scalars (and any negative indexer entries) must NOT reach
        // the model as -1: the duration predictor returns garbage (≤ 0 s) and
        // the chunk gets skipped — the "Supertonic is silent" bug. iOS maps
        // them to the space id; same fix here.
        val spaceId = if (indexer.size > 32) indexer[32] else 0L
        val textIds = LongArray(scalars.size) { j ->
            val v = scalars[j]
            if (v < 0) {
                spaceId
            } else if (v < indexer.size) {
                val mapped = indexer[v]
                if (mapped < 0) spaceId else mapped
            } else {
                spaceId
            }
        }

        var textIdsTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null
        var ttlTensor: OnnxTensor? = null
        var dpTensor: OnnxTensor? = null
        var embTensor: OnnxTensor? = null
        var noisyTensor: OnnxTensor? = null
        var latentMaskTensor: OnnxTensor? = null
        var stepTensor: OnnxTensor? = null
        var totalTensor: OnnxTensor? = null
        var latentTensor: OnnxTensor? = null
        var result: Result? = null
        LogStore.info(
            "SupertonicEngine: chunk tokens=${textIds.size} voice=$voice lang=$lang " +
                "sr=$sampleRate latentDim=$latentDim chunkSize=${baseChunkSize.toLong() * chunkCompress}"
        )
        try {
            textIdsTensor = longTensor(env, textIds, longArrayOf(1, textIds.size.toLong()))
            maskTensor = floatTensor(env, FloatArray(textIds.size) { 1f }, longArrayOf(1, 1, textIds.size.toLong()))
            ttlTensor = floatTensor(env, style.ttl, style.ttlDims)
            dpTensor = floatTensor(env, style.dp, style.dpDims)

            // 1) duration predictor → per-sample duration, speed applied.
            dp.run(mapOf("text_ids" to textIdsTensor, "style_dp" to dpTensor, "text_mask" to maskTensor)).use { out ->
                val duration = floatsOf(out, "duration")
                var d = duration.firstOrNull() ?: error("no duration")
                d /= speed
                // 2) text encoder → text_emb
                te.run(mapOf("text_ids" to textIdsTensor, "style_ttl" to ttlTensor, "text_mask" to maskTensor)).use { teOut ->
                    embTensor = teOut.get("text_emb").orElse(null) as? OnnxTensor
                        ?: error("no text_emb")
                    // 3) noisy latent + latent mask ([1, latentDimVal, latentLen]).
                    // latentDim already carries the ×chunkCompress applied at
                    // load, so it is used as-is here.
                    val latentDimVal = latentDim
                    val chunkSize = baseChunkSize.toLong() * chunkCompress
                    val wavLen = (d * sampleRate).toLong()
                    val latentLen = ((wavLen + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
                    val latentMask = FloatArray(latentLen) { 1f }
                    val noise = FloatArray(latentDimVal * latentLen) { gaussian() }
                    for (t in 0 until latentLen) noise[t] *= latentMask[t]
                    noisyTensor = floatTensor(env, noise, longArrayOf(1, latentDimVal.toLong(), latentLen.toLong()))
                    latentMaskTensor = floatTensor(env, latentMask, longArrayOf(1, 1, latentLen.toLong()))
                    totalTensor = floatTensor(env, floatArrayOf(totalStep.toFloat()), longArrayOf(1))

                    // 4) flow-matching denoise loop, current_step = step index.
                    for (step in 0 until totalStep) {
                        stepTensor = floatTensor(env, floatArrayOf(step.toFloat()), longArrayOf(1))
                        val inputs = HashMap<String, OnnxTensor>()
                        inputs["noisy_latent"] = noisyTensor!!
                        inputs["text_emb"] = embTensor!!
                        inputs["style_ttl"] = ttlTensor!!
                        inputs["latent_mask"] = latentMaskTensor!!
                        inputs["text_mask"] = maskTensor!!
                        inputs["current_step"] = stepTensor!!
                        inputs["total_step"] = totalTensor!!
                        result = ve.run(inputs)
                        val denoised = result!!.get("denoised_latent").orElse(null) as? OnnxTensor ?: error("no denoised_latent")
                        val flat = floatsOf(denoised, "denoised_latent")
                        runCatching { result?.close() }
                        result = null
                        runCatching { stepTensor?.close() }
                        // The next step's noisy_latent IS the denoised output.
                        runCatching { noisyTensor?.close() }
                        noisyTensor = floatTensor(env, flat, longArrayOf(1, latentDimVal.toLong(), latentLen.toLong()))
                    }

                    // 5) vocoder → waveform, trimmed to the predicted duration.
                    latentTensor = noisyTensor
                    vo.run(mapOf("latent" to latentTensor!!)).use { vocOut ->
                        val wav = floatsOf(vocOut, "wav_tts")
                        // The vocoder emits whole 3072-sample chunks, so it can
                        // come back SHORTER than the predicted duration (the
                        // iOS engine hit this too). Trim to whichever is
                        // smaller — short audio beats no audio.
                        val actualLen = (sampleRate.toFloat() * d).toInt().coerceIn(0, wav.size)
                        if (actualLen <= 0) error("Supertonic returned no audio for this chunk")
                        val audio = wav.copyOf(actualLen)
                        LogStore.info(
                            "SupertonicEngine: %.1fs audio in %.2fs (%s, %s)"
                                .format(audio.size / sampleRate.toDouble(), (System.currentTimeMillis() - started) / 1000.0, voice, lang)
                        )
                        return audio
                    }
                }
            }
            error("unreachable")
        } finally {
            closeAll(textIdsTensor, maskTensor, ttlTensor, dpTensor, embTensor, latentMaskTensor, totalTensor, stepTensor, latentTensor, result)
        }
    }

    // MARK: - Tensor helpers

    // Typed-buffer overloads only: a raw ByteBuffer is read as 1-byte
    // elements, so an int64 [1,N] input reports "N*8 elements" and every
    // run() dies with a shape mismatch (the 2026-09-28 chunk-0 failure).
    private fun longTensor(env: OrtEnvironment, values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)

    private fun floatTensor(env: OrtEnvironment, values: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)

    private fun floatsOf(result: Result, name: String): FloatArray {
        val tensor = result.get(name).orElse(null) as? OnnxTensor ?: error("missing output $name")
        return floatsOf(tensor, name)
    }

    private fun floatsOf(tensor: OnnxTensor, name: String): FloatArray {
        val buffer = tensor.floatBuffer ?: error("output $name is not float32")
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private fun closeAll(vararg closeables: AutoCloseable?) {
        closeables.forEach { runCatching { it?.close() } }
    }

    /** Box–Muller transform, matching the Helper's sampleNoisyLatent. */
    private fun gaussian(): Float {
        val u1 = 0.0001f + Math.random().toFloat() * 0.9999f
        val u2 = Math.random().toFloat()
        return sqrt(-2f * ln(u1)) * cos(2f * Math.PI.toFloat() * u2)
    }

    private fun flatten3d(data: JSONArray): FloatArray {
        val out = ArrayList<Float>(data.length() * 64)
        for (i in 0 until data.length()) {
            val mid = data.getJSONArray(i)
            for (j in 0 until mid.length()) {
                val inner = mid.getJSONArray(j)
                for (k in 0 until inner.length()) out.add(inner.getDouble(k).toFloat())
            }
        }
        val arr = FloatArray(out.size)
        for (i in arr.indices) arr[i] = out[i]
        return arr
    }

    companion object {
        val AVAILABLE_LANGS = listOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
            "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na",
        )

        // MARK: - Text preprocessing (the vendored UnicodeProcessor)

        private val REPLACEMENTS = linkedMapOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ",
            "\u201C" to "\"", "\u201D" to "\"", "\u2018" to "'", "\u2019" to "'",
            "´" to "'", "`" to "'",
            "[" to " ", "]" to " ", "|" to " ",
            // Everything below maps to -1 in the unicode indexer — the same
            // "duration predictor returns ≤ 0s, chunk skipped" device bug the
            // iOS Helper fixed by rewriting them before tokenizing.
            "…" to "...",   // ellipsis
            "„" to "\"",    // low-9 double quote
            "‚" to "'",     // low-9 single quote
            "«" to "\"",    // guillemet open
            "»" to "\"",    // guillemet close
            "‹" to "'",     // single guillemet open
            "›" to "'",     // single guillemet close
            "′" to "'",     // prime
            "≥" to "at least ",
            "≤" to "at most ",
            "−" to "-",     // minus sign (mapped but reads oddly)
            "\uFFFD" to "", // replacement char from a lossy decode
            "/" to " ", "#" to " ",
            "→" to " ", "←" to " ",
        )
        private val SPECIAL_SYMBOLS = listOf("♥", "☆", "♡", "©", "\\")
        private val EXPR_REPLACEMENTS = linkedMapOf(
            "@" to " at ", "e.g.," to "for example, ", "i.e.," to "that is, ",
        )

        /**
         * NFKD decomposition, emoji removal, symbol/dash/quote normalization,
         * punctuation spacing fixes, a trailing period, and the
         * `<lang>…</lang>` wrapper the graphs expect.
         */
        fun preprocessText(text: String, lang: String): String {
            var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
            val sb = StringBuilder(t.length)
            for (c in t) {
                if (c.isSurrogate()) continue
                val code = c.code
                val isEmoji =
                    code in 0x1F600..0x1F64F || code in 0x1F300..0x1F5FF || code in 0x1F680..0x1F6FF ||
                        code in 0x1F700..0x1F77F || code in 0x1F780..0x1F7FF || code in 0x1F800..0x1F8FF ||
                        code in 0x1F900..0x1F9FF || code in 0x1FA00..0x1FA6F || code in 0x1FA70..0x1FAFF ||
                        code in 0x2600..0x26FF || code in 0x2700..0x27BF || code in 0x1F1E6..0x1F1FF
                if (!isEmoji) sb.append(c)
            }
            t = sb.toString()
            for ((old, new) in REPLACEMENTS) t = t.replace(old, new)
            for (symbol in SPECIAL_SYMBOLS) t = t.replace(symbol, "")
            for ((old, new) in EXPR_REPLACEMENTS) t = t.replace(old, new)
            for (pair in listOf(" ,", " .", " !", " ?", " ;", " :", " '")) {
                t = t.replace(pair, pair.substring(1))
            }
            while (t.contains("\"\"")) t = t.replace("\"\"", "\"")
            while (t.contains("''")) t = t.replace("''", "'")
            while (t.contains("``")) t = t.replace("``", "`")
            t = t.replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty() && t.last() !in ".!?,;:'\"…”』」》›»)]}") {
                t += "."
            }
            return "<$lang>$t</$lang>"
        }
    }
}
