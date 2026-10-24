package com.speechnotes.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.speechnotes.app.data.LogStore
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.logic.TextHash
import com.speechnotes.app.model.VoiceCatalog
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Kokoro TTS via ONNX Runtime on the CPU — ported from OnnxKokoroEngine.swift
 * (speechnotes-ios).
 *
 * Inference spec (verified against the iOS implementation): inputs
 * `input_ids` int64 [1, N], `style` float32 [1, 256] (flat voice array
 * sliced at clamp(N-2, 0, 509) * 256), `speed` float32 [1]; output
 * `waveform` float32 @ 24 kHz. The model takes speed natively, so playback
 * is a plain AudioTrack stream.
 *
 * The engine keeps model loading + synthesis, confined to the core's
 * generateQueue (a single-thread executor inside StreamingPlaybackCore).
 */
class KokoroEngine(
    val engineKind: EngineKind,
) : SpeechEngine {

    override var name: String = when (engineKind) {
        EngineKind.KOKORO -> "Kokoro ONNX (CPU)"
        EngineKind.KOKORO_SMALL -> "Kokoro small (CPU)"
        else -> "Kokoro"
    }

    override var onStateChanged: ((SpeechState) -> Unit)? = null
    override var onProgress: ((Double) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null
    override var onPlayedChars: ((Int) -> Unit)? = null

    /** Kokoro voice codename (e.g. "af_heart"). */
    var voice: String = "am_eric"

    private var modelFileURL: File = modelFileFor(engineKind)
    private val modelFilesValid: () -> Boolean = { ModelFiles.isValid(engineKind) }

    private var validationCalls = 0

    val core: StreamingPlaybackCore

    // Model state — the core's generateQueue only.
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val vocab = HashMap<String, Int>()
    private var voicesFlat: Map<String, FloatArray> = emptyMap()
    private var outputName: String = "waveform"

    /** G2P — the Kotlin port of MisakiSwift. */
    private val g2p = MisakiG2P()

    private var modelLoadAttempted = false

    init {
        modelFileURL = modelFileFor(engineKind)
        core = StreamingPlaybackCore(
            config = StreamingPlaybackCore.Config(
                sampleRate = 24_000,
                chunkMaxChars = 160,
                // iOS OnnxKokoroEngine.swift:131. Batch B3 measured this as
                // `RTF · c1` with RTF assumed 0.53 (TTS_BASELINE §2); the
                // real RTF printed per session settles it. If measured RTF
                // ≥ 1.0 the fast-start premise is dead — no legal
                // firstMaxChars reaches break-even against a 160-char
                // chunkMaxChars — and the bank becomes the primary fix.
                // The two exist side by side for now.
                firstChunkMaxChars = 100,
                exportInterChunkSilence = 0.05f,
                logPrefix = "KokoroEngine",
            ),
            generateChunk = { text -> generateChunk(text) },
            isModelReady = {
                loadModelIfNeeded()
                ortSession != null
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
        if (!modelFilesValid()) {
            LogStore.error("KokoroEngine: model files missing for $engineKind")
            return
        }
        core.speak(text)
    }

    override fun pause() = core.pause()
    override fun resume() = core.resume()
    override fun stop() = core.stop()

    /** Loads the ONNX session + voice bank and warms G2P on the generate queue. */
    override fun prewarm() {
        core.prewarm {
            loadModelIfNeeded()
            if (ortSession != null) runCatching { g2p.phonemize("Warm up.", british = voice.startsWith("b")) }
        }
    }

    fun release() = core.release()

    /**
     * Drops the resident ONNX session and voice bank (iOS idle-unload parity:
     * SpeechPlayer.swift:694,717 — sessions dropped after 5 min idle). The
     * generate-queue-only contract holds: called via [SpeechPlayer] on that
     * queue through prewarm; the next speak() reloads from disk.
     */
    fun unloadModel() {
        core.prewarm {
            runCatching { ortSession?.close() }
            ortSession = null
            ortEnv = null
            voicesFlat = emptyMap()
            modelLoadAttempted = false
            LogStore.info("KokoroEngine: model unloaded (idle)")
        }
    }

    // MARK: - Model loading (generateQueue only)

    private fun modelFileFor(kind: EngineKind): File = when (kind) {
        // Small tier = the dynamic-quantized int8 export (iOS smallModelFileURL
        // parity) — superseded model_uint8.onnx is rejected by the validity window.
        EngineKind.KOKORO_SMALL -> File(AppFiles.kokoroDir, "model_quantized.onnx")
        else -> File(AppFiles.kokoroDir, "model.onnx")
    }

    private fun loadModelIfNeeded() {
        if (modelLoadAttempted) return
        modelLoadAttempted = true
        validationCalls++
        try {
            if (!modelFilesValid()) {
                LogStore.error("KokoroEngine: files missing or invalid — will retry on next speak")
                modelLoadAttempted = false
                return
            }
            val started = System.currentTimeMillis()
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(modelFileURL.absolutePath, opts)

            // Tokenizer vocab (per-character lookup, matching the reference).
            val tokenizerFile = File(AppFiles.kokoroDir, "tokenizer.json")
            val tokenizerJson = JSONObject(tokenizerFile.readText())
            val modelJson = tokenizerJson.optJSONObject("model")
            val vocabJson = modelJson?.optJSONObject("vocab")
            if (vocabJson != null) {
                val map = HashMap<String, Int>(vocabJson.length())
                for (key in vocabJson.keys()) {
                    vocab[key] = vocabJson.getInt(key)
                }
            }
            if (vocab.isEmpty()) error("tokenizer.json has no vocab")

            // Voice bank (npz: one .npy member per voice).
            val voicesFile = File(AppFiles.kokoroDir, "voices.npz")
            val voices = NpzReader.read(voicesFile)
            val flat = HashMap<String, FloatArray>(voices.size)
            for ((key, array) in voices) flat[key] = array
            voicesFlat = flat
            if (flat.isEmpty()) error("voices.npz has no voices")

            val outputs = session.outputNames
            LogStore.info("KokoroEngine: model outputs = $outputs")
            outputName = outputs.firstOrNull { it.equals("waveform", true) || it.equals("audio", true) }
                ?: outputs.firstOrNull()
                ?: "waveform"
            LogStore.info("KokoroEngine: using output '$outputName'")
            ortEnv = env
            ortSession = session
            LogStore.info("KokoroEngine: ${vocab.size} vocab entries, ${flat.size} voices ready in ${System.currentTimeMillis() - started} ms")
        } catch (e: Exception) {
            ortSession = null
            modelLoadAttempted = false
            LogStore.error("KokoroEngine: model load failed: ${e.message} — will retry on next speak")
        }
    }

    /** The voice flat array, choosing the fallback voice when unset/missing. */
    private fun voiceFlat(): FloatArray? {
        val key = if (voicesFlat.containsKey("$voice.npy")) "$voice.npy" else voicesFlat.keys.sorted().firstOrNull()
        return key?.let { voicesFlat[it] }
    }

    // MARK: - Synthesis (generateQueue only)

    private fun generateChunk(text: String): FloatArray {
        val flat = voiceFlat() ?: error("no voice available")
        val phonemes = g2p.phonemize(text, british = voice.startsWith("b"))
        if (phonemes.isEmpty()) error("phonemization failed")
        val tokens = phonemes.mapNotNull { vocab[it.toString()] }
        if (tokens.size <= 1 || tokens.size > MAX_TOKENS) {
            // Diagnostic: which text and how long — the token count alone
            // can't distinguish "chunk too big" from "G2P exploded".
            LogStore.error("KokoroEngine: token count ${tokens.size} for ${text.length} chars: '${text.take(60)}…'")
            error("token count ${tokens.size}")
        }
        val session = ortSession ?: error("model unavailable")

        // Style slice: clamp(N - 2, 0, rows - 1) * 256.
        val styleDim = 256
        val rows = maxOf(1, flat.size / styleDim)
        val adjusted = minOf(maxOf(tokens.size - 2, 0), rows - 1)
        val offset = adjusted * styleDim
        if (offset + styleDim > flat.size) error("voice shape ${flat.size}")
        val style = flat.copyOfRange(offset, offset + styleDim)

        val env = ortEnv!!
        val tokensLong = tokens.map { it.toLong() }
        val shape = longArrayOf(1, tokensLong.size.toLong())

        val tensors = runCatching {
            // Typed-buffer + shape overloads: LongBuffer [1, N] for token ids,
            // FloatBuffer [1, 256] for the style slice, FloatBuffer [1] speed.
            val tokensBuffer = ByteBuffer
                .allocateDirect(tokensLong.size * 8)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer()
                .put(tokensLong.toLongArray())
                .flip() as java.nio.LongBuffer
            val tokensTensor = OnnxTensor.createTensor(
                env,
                tokensBuffer,
                longArrayOf(1, tokensLong.size.toLong()),
            )
            val styleBuffer = ByteBuffer
                .allocateDirect(style.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(style)
                .flip() as java.nio.FloatBuffer
            val styleTensor = OnnxTensor.createTensor(
                env,
                styleBuffer,
                longArrayOf(1, style.size.toLong()),
            )
            val speedBuffer = ByteBuffer
                .allocateDirect(4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(core.speed)
                .flip() as java.nio.FloatBuffer
            val speedTensor = OnnxTensor.createTensor(env, speedBuffer, longArrayOf(1))
            Triple(tokensTensor, styleTensor, speedTensor)
        }.getOrElse { throw it }

        val (tokensTensor, styleTensor, speedTensor) = tensors
        val started = System.currentTimeMillis()
        return try {
            val results = session.run(
                mapOf(
                    "input_ids" to tokensTensor,
                    "style" to styleTensor,
                    "speed" to speedTensor,
                )
            )
            // Prefer the named output, then position 0. The waveform is the
            // only tensor for this export.
            // ORT's Result.get returns Optional<OnnxTensor>.
            val rawValue = (results.get(outputName) ?: results.get(0))
            val result = when (rawValue) {
                is OnnxTensor -> rawValue
                is java.util.Optional<*> -> rawValue.orElse(null) as? OnnxTensor
                else -> null
            } ?: error("no output tensor (outputs=${results.map { it.key }}, type=${rawValue?.javaClass?.name})")
            val buffer = result.floatBuffer
            if (buffer == null) error("output is not float32")
            val out = FloatArray(buffer.remaining())
            buffer.get(out)
            val duration = out.size / 24000.0
            LogStore.info("KokoroEngine: ${tokens.size} tokens → %.1fs audio in %.2fs".format(duration, (System.currentTimeMillis() - started) / 1000.0))
            out
        } finally {
            runCatching { tokensTensor.close() }
            runCatching { styleTensor.close() }
            runCatching { speedTensor.close() }
        }
    }

    companion object {
        private const val MAX_TOKENS = 510
    }
}

/**
 * Minimal NPZ reader: a ZIP of `.npy` members (voices.npz carries one
 * Float32 array per voice). Ported from the iOS NpzReader.
 */
object NpzReader {

    fun read(file: File): Map<String, FloatArray> {
        val result = HashMap<String, FloatArray>()

        // Preferred path: the platform ZIP reader (central directory).
        runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement() ?: break
                    if (entry.isDirectory) continue
                    val bytes = zip.getInputStream(entry).readBytes()
                    val array = parseNpy(bytes)
                    if (array == null) {
                        LogStore.warn("NpzReader: skipped ${entry.name}: ${rejectReason(bytes)}")
                        continue
                    }
                    result[entry.name.removeSuffix(".npy")] = array
                }
            }
        }.onFailure { LogStore.warn("NpzReader: zip path failed: ${it.message}") }

        // Fallback: walk the local file headers ourselves. npz writers emit
        // ZIP64 entries whose sizes live only in the extra field; a manual
        // walk sidesteps any platform quirk with those.
        if (result.isEmpty()) {
            LogStore.warn("NpzReader: falling back to manual walk for ${file.name}")
            runCatching { readLocal(file).forEach { (name, array) -> result[name] = array } }
                .onFailure { LogStore.warn("NpzReader: manual walk failed: ${it.message}") }
        }

        if (result.isEmpty()) {
            LogStore.warn("NpzReader: no members in ${file.name} (${file.length()} bytes)")
        } else {
            LogStore.info("NpzReader: ${result.size} voices from ${file.name}")
        }
        return result
    }

    /** Manual ZIP walk over local headers, honoring the ZIP64 extra field. */
    private fun readLocal(file: File): Map<String, FloatArray> {
        val data = file.readBytes()
        val result = HashMap<String, FloatArray>()
        var i = 0
        while (i + 30 <= data.size) {
            if (data[i] != 0x50.toByte() || data[i + 1] != 0x4B.toByte() ||
                data[i + 2] != 0x03.toByte() || data[i + 3] != 0x04.toByte()
            ) break
            val flags = u16(data, i + 6)
            var compressed = u32(data, i + 18)
            var uncompressed = u32(data, i + 22)
            val nameLen = u16(data, i + 26)
            val extraLen = u16(data, i + 28)
            val dataStart = i + 30 + nameLen + extraLen

            if (compressed == 0xFFFFFFFFL || uncompressed == 0xFFFFFFFFL) {
                val extra = data.copyOfRange(i + 30 + nameLen, dataStart)
                var e = 0
                while (e + 4 <= extra.size) {
                    val id = u16(extra, e)
                    val size = u16(extra, e + 2)
                    if (id == 0x0001) {
                        var p = e + 4
                        if (uncompressed == 0xFFFFFFFFL && p + 8 <= extra.size) {
                            uncompressed = u64(extra, p); p += 8
                        }
                        if (compressed == 0xFFFFFFFFL && p + 8 <= extra.size) {
                            compressed = u64(extra, p); p += 8
                        }
                        break
                    }
                    e += 4 + size
                }
            }

            val name = String(data, i + 30, nameLen, Charsets.US_ASCII)
            val size = uncompressed.toInt()
            if (size >= 0 && dataStart + size <= data.size && !name.endsWith("/")) {
                val array = parseNpy(data.copyOfRange(dataStart, dataStart + size))
                if (array != null) result[name.removeSuffix(".npy")] = array
            }

            // Bit 3 (data descriptor) makes the computed jump unreliable —
            // re-sync on the next local-header signature in that case.
            i = if (flags and 0x0008 != 0) {
                var j = dataStart + size.coerceAtLeast(0)
                while (j + 4 <= data.size &&
                    !(data[j] == 0x50.toByte() && data[j + 1] == 0x4B.toByte() &&
                        data[j + 2] == 0x03.toByte() && data[j + 3] == 0x04.toByte())
                ) j++
                j
            } else {
                dataStart + size.coerceAtLeast(0)
            }
        }
        return result
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun u64(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = v or ((data[offset + k].toLong() and 0xFF) shl (8 * k))
        return v
    }

    /** Why [parseNpy] rejected an entry — for diagnostics only. */
    private fun rejectReason(bytes: ByteArray): String {
        if (bytes.size < 10) return "too short (${bytes.size})"
        if (bytes[0] != 0x93.toByte()) return "magic0=${bytes[0]}"
        val magic = "NUMPY".toByteArray(Charsets.US_ASCII)
        for (i in magic.indices) if (bytes[i + 1] != magic[i]) return "magic[${i + 1}]=${bytes[i + 1]}"
        val versionMajor = bytes[6].toInt() and 0xFF
        val headerStart = if (versionMajor == 1) 10 else 12
        val headerLen = if (versionMajor == 1) {
            (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        } else {
            (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8) or
                ((bytes[10].toInt() and 0xFF) shl 16) or ((bytes[11].toInt() and 0xFF) shl 24)
        }
        if (headerStart + headerLen > bytes.size) return "header overruns (hl=$headerLen, size=${bytes.size})"
        val header = String(bytes, headerStart, headerLen, Charsets.US_ASCII)
        if (!header.contains("<f4") && !header.contains("float32")) {
            return "header dtype: ${header.take(60)}"
        }
        return "unknown (v=$versionMajor, hl=$headerLen, size=${bytes.size})"
    }

    /** Parses an `.npy` v1/v2 Float32 array. */
    private fun parseNpy(bytes: ByteArray): FloatArray? {
        if (bytes.size < 10) return null
        if (bytes[0] != 0x93.toByte()) return null
        // Magic:  followed by "NUMPY" (the  is byte 0, the name the next five).
        val magic = "NUMPY".toByteArray(Charsets.US_ASCII)
        for (i in magic.indices) if (bytes[i + 1] != magic[i]) return null
        val versionMajor = bytes[6].toInt() and 0xFF
        val headerLen = if (versionMajor == 1) {
            (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        } else {
            (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8) or
                ((bytes[10].toInt() and 0xFF) shl 16) or ((bytes[11].toInt() and 0xFF) shl 24)
        }
        val headerStart = if (versionMajor == 1) 10 else 12
        val header = String(bytes, headerStart, headerLen, Charsets.US_ASCII)
        if (!header.contains("<f4") && !header.contains("float32")) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(headerStart + headerLen)
        val floatBuffer = buffer.asFloatBuffer()
        val out = FloatArray(floatBuffer.remaining())
        floatBuffer.get(out)
        return out
    }

    /** Stable hash used by bookmarks (kept for parity with the iOS app). */
    fun hashOf(text: String): Long = TextHash.of(text)
}
