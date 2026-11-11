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
    val phononVoices: List<String> = VoiceCatalog.phononVoiceIds

    /** iOS ModelManager.phononBaseURL parity — the hub's resolve root. */
    private const val phononBaseURL =
        "https://huggingface.co/onnx-community/phonon-31m-en-ONNX/resolve/main"

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

    /**
     * Gradium Phonon — 31M-param English streaming TTS (Apache-2.0) via the
     * ONNX conversion `onnx-community/phonon-31m-en-ONNX`. Two fp32 graphs
     * (flow transformer + stateless Mimi decoder) with external weight
     * files, the Unigram tokenizer and 26 precomputed voice bins (~192 MB
     * total). Byte gates mirror iOS ModelManager.phononFilesAreValid();
     * the voice bins are gated EXACTLY at 256,000 bytes (min=max) so a
     * truncated transfer can never count as complete. fp16/q4 are
     * deliberately NOT shipped (the Kokoro fp16 NaN lesson; iOS
     * PHONON-PORT.md).
     */
    val phonon: ModelSet = ModelSet(
        engine = EngineKind.PHONON,
        directory = AppFiles.phononDir,
        files = buildList {
            add(Entry(
                "onnx/model.onnx",
                "$phononBaseURL/onnx/model.onnx",
                100_000L,
            ))
            add(Entry(
                "onnx/model.onnx_data",
                "$phononBaseURL/onnx/model.onnx_data",
                100_000_000L,
            ))
            add(Entry(
                "onnx/mimi_decoder.onnx",
                "$phononBaseURL/onnx/mimi_decoder.onnx",
                10_000L,
            ))
            add(Entry(
                "onnx/mimi_decoder.onnx_data",
                "$phononBaseURL/onnx/mimi_decoder.onnx_data",
                40_000_000L,
            ))
            add(Entry(
                "tokenizer.json",
                "$phononBaseURL/tokenizer.json",
                100_000L,
            ))
            for (voice in VoiceCatalog.phononVoiceIds) {
                add(Entry(
                    "voices/$voice.bin",
                    "$phononBaseURL/voices/$voice.bin",
                    256_000L,
                    maxBytes = 256_000L,
                ))
            }
        },
    )

    fun setFor(engine: EngineKind): ModelSet? = when (engine) {
        EngineKind.KOKORO -> kokoro
        EngineKind.KOKORO_SMALL -> kokoroSmall
        EngineKind.PHONON -> phonon
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