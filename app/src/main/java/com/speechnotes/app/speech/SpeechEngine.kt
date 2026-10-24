package com.speechnotes.app.speech

/**
 * Playback state machine shared by every engine. Mirrors the iOS app's
 * SpeechState.
 */
enum class SpeechState {
    IDLE, GENERATING, SPEAKING, PAUSED;

    val isActive: Boolean get() = this == SPEAKING || this == PAUSED || this == GENERATING
}

/**
 * Anything that can turn text into spoken audio. Implementations:
 * SystemTtsEngine (android.speech.tts), KokoroEngine and SupertonicEngine
 * (ONNX Runtime, CPU).
 */
interface SpeechEngine {
    val name: String

    /** State transitions (main thread). */
    var onStateChanged: ((SpeechState) -> Unit)?

    /** Progress through the spoken text, 0…1 (chunk-granular). */
    var onProgress: ((Double) -> Unit)?

    /** Fired ONLY when playback reaches its natural end. */
    var onFinished: (() -> Unit)?

    /**
     * Play-time signal: UTF-16 character count of the spoken string whose
     * audio is ACTUALLY sounding or has finished — never schedule-ahead.
     */
    var onPlayedChars: ((Int) -> Unit)?

    /** Live speech rate — takes effect from the next chunk/utterance. */
    var speed: Float

    fun speak(text: String, rateMultiplier: Double)
    fun pause()
    fun resume()
    fun stop()

    /**
     * Warms the engine off the UI thread (ONNX sessions, G2P tables) so the
     * first speak starts synthesis immediately. Best-effort; default no-op.
     */
    fun prewarm() {}
}

/** What the app can select in Settings. Order = picker order, worst → best. */
enum class EngineKind(val key: String, val label: String) {
    SYSTEM("system", "Android system voice"),
    KOKORO_SMALL("kokoroSmall", "Kokoro — small · int8 (~92 MB)"),
    KOKORO("kokoroOnnx", "Kokoro — on-device neural, 28 voices"),
    SUPERTONIC("supertonic", "Supertonic — best quality, multilingual");

    val isNeural: Boolean get() = this != SYSTEM

    companion object {
        fun fromKey(key: String): EngineKind =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** What the player is doing with exported audio. */
sealed class ExportState {
    data object Idle : ExportState()
    data class Running(val progress: Double) : ExportState()
    data class Failed(val message: String) : ExportState()
}
