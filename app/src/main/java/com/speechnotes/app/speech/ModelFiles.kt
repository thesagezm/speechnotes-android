package com.speechnotes.app.speech

import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.model.VoiceCatalog
import java.io.File

/**
 * The model files each neural engine needs, and their download URLs.
 * Ported from ModelManager.swift (speechnotes-ios).
 */
object ModelFiles {

    data class Entry(
        val name: String,
        val url: String,
        /** Minimum byte count for the file to count as complete. */
        val minBytes: Long,
        /** Optional upper bound — rejects superseded builds of the same file. */
        val maxBytes: Long? = null,
    )

    data class ModelSet(
        val engine: EngineKind,
        val directory: File,
        val files: List<Entry>,
    )

    val kokoroKnownVoices: List<String> = VoiceCatalog.kokoroVoices
    val supertonicVoices: List<String> = VoiceCatalog.supertonicVoiceIds

    // MARK: Kokoro (fp32 + uint8 share the graph)

    val kokoro: ModelSet = ModelSet(
        engine = EngineKind.KOKORO,
        directory = AppFiles.kokoroDir,
        files = listOf(
            Entry(
                "model.onnx",
                "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model.onnx",
                300_000_000L,
            ),
            Entry(
                "tokenizer.json",
                "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/tokenizer.json",
                1_000L,
            ),
            Entry(
                "voices.npz",
                "https://raw.githubusercontent.com/mlalma/KokoroTestApp/main/Resources/voices.npz",
                10_000_000L,
            ),
        ),
    )

    val kokoroSmall: ModelSet = ModelSet(
        engine = EngineKind.KOKORO_SMALL,
        directory = AppFiles.kokoroDir,
        files = listOf(
            Entry(
                // iOS parity (ModelManager.swift smallModelURL): the small
                // tier moved off the weight-only uint8 build (~177 MB) to
                // onnxruntime's dynamic-quantized int8 export (~92 MB) —
                // equal-or-better spectral fidelity in the four-way spike,
                // half the download. fp16 is banned (NaN on ORT CPU).
                "model_quantized.onnx",
                "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_quantized.onnx",
                // Window (iOS smallFilesAreValid): rejects the old uint8
                // file (~177 MB) so it re-downloads as the quantized build,
                // and rejects a truncated download.
                80_000_000L,
                maxBytes = 100_000_000L,
            ),
            Entry(
                "tokenizer.json",
                "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/tokenizer.json",
                1_000L,
            ),
            Entry(
                "voices.npz",
                "https://raw.githubusercontent.com/mlalma/KokoroTestApp/main/Resources/voices.npz",
                10_000_000L,
            ),
        ),
    )

    val supertonic: ModelSet = ModelSet(
        engine = EngineKind.SUPERTONIC,
        directory = AppFiles.supertonicDir,
        files = listOf(
            Entry("onnx/duration_predictor.onnx", "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx/duration_predictor.onnx", 3_000_000L),
            Entry("onnx/text_encoder.onnx", "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx/text_encoder.onnx", 30_000_000L),
            Entry("onnx/vector_estimator.onnx", "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx/vector_estimator.onnx", 200_000_000L),
            Entry("onnx/vocoder.onnx", "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx/vocoder.onnx", 90_000_000L),
            Entry("onnx/tts.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx/tts.json", 1_000L),
            Entry("onnx/unicode_indexer.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx/unicode_indexer.json", 100_000L),
            Entry("voice_styles/M1.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/M1.json", 100_000L),
            Entry("voice_styles/M2.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/M2.json", 100_000L),
            Entry("voice_styles/M3.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/M3.json", 100_000L),
            Entry("voice_styles/M4.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/M4.json", 100_000L),
            Entry("voice_styles/M5.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/M5.json", 100_000L),
            Entry("voice_styles/F1.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/F1.json", 100_000L),
            Entry("voice_styles/F2.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/F2.json", 100_000L),
            Entry("voice_styles/F3.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/F3.json", 100_000L),
            Entry("voice_styles/F4.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/F4.json", 100_000L),
            Entry("voice_styles/F5.json", "https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/F5.json", 100_000L),
        ),
    )

    fun setFor(engine: EngineKind): ModelSet? = when (engine) {
        EngineKind.KOKORO -> kokoro
        EngineKind.KOKORO_SMALL -> kokoroSmall
        EngineKind.SUPERTONIC -> supertonic
        EngineKind.SYSTEM -> null
    }

    fun isValid(engine: EngineKind): Boolean {
        val set = setFor(engine) ?: return false
        return set.files.all { entry ->
            val file = File(set.directory, entry.name)
            file.isFile && file.length() >= entry.minBytes &&
                (entry.maxBytes == null || file.length() <= entry.maxBytes)
        }
    }

    /**
     * Reclaims the superseded small-tier build (model_uint8.onnx, ~177 MB).
     * iOS removes stale quantized variants on launch; the quantized set's
     * validity window no longer accepts this file, so it is dead weight.
     */
    fun removeLegacySmallModel() {
        runCatching {
            val stale = File(AppFiles.kokoroDir, "model_uint8.onnx")
            if (stale.isFile) {
                val mb = stale.length() / 1_000_000
                if (stale.delete()) com.speechnotes.app.data.LogStore.info(
                    "ModelFiles: removed superseded uint8 Kokoro model ($mb MB)"
                )
            }
        }
    }
}