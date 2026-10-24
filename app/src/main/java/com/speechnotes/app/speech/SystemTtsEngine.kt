package com.speechnotes.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.speechnotes.app.data.LogStore
import java.util.Locale
import java.util.UUID

/**
 * Android's built-in text-to-speech. Ported from SystemEngine.swift
 * (speechnotes-ios): per-word progress via UtteranceProgressListener,
 * natural completion via onDone, rate mapping (0.5–2.0 → TTS 0.1–1.0),
 * audio-focus handling and epoch-guarded state transitions.
 */
class SystemTtsEngine(context: Context) : SpeechEngine {

    override var name: String = "Android (system)"

    override var onStateChanged: ((SpeechState) -> Unit)? = null
    override var onProgress: ((Double) -> Unit)? = null
    override var onFinished: (() -> Unit)? = null
    override var onPlayedChars: ((Int) -> Unit)? = null

    /** Identifier of the system voice to use; null = engine default. */
    var voiceIdentifier: String? = null

    /** Live rate — applied on the next utterance. */
    override var speed: Float = 1.0f

    private var currentState: SpeechState = SpeechState.IDLE
    private fun setState(newState: SpeechState) {
        if (currentState != newState) {
            LogStore.info("SystemTtsEngine state: $currentState → $newState")
            currentState = newState
            onStateChanged?.invoke(newState)
        }
    }

    /** Every async state jump carries the utterance epoch it belongs to. */
    private var epoch = 0

    /** Per-word progress callbacks coalesced to ~3.3 Hz. */
    private var lastSignalAt = 0L

    @Volatile
    private var ready = false
    private var pendingText: Pair<String, Double>? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            LogStore.info("SystemTtsEngine ready")
            val pending = pendingText
            pendingText = null
            if (pending != null) speak(pending.first, pending.second)
        } else {
            LogStore.error("SystemTtsEngine init failed (status=$status)")
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val e = epochOf(utteranceId) ?: return
                if (e == epoch) main { setState(SpeechState.SPEAKING) }
            }

            override fun onDone(utteranceId: String?) {
                val e = epochOf(utteranceId) ?: return
                if (e != epoch) return
                main {
                    onFinished?.invoke()
                    setState(SpeechState.IDLE)
                }
            }

            override fun onError(utteranceId: String?) {
                LogStore.error("SystemTtsEngine utterance error")
                val e = epochOf(utteranceId) ?: return
                if (e != epoch) return
                main { setState(SpeechState.IDLE) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                LogStore.error("SystemTtsEngine utterance error $errorCode")
                val e = epochOf(utteranceId) ?: return
                if (e != epoch) return
                main { setState(SpeechState.IDLE) }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val e = epochOf(utteranceId) ?: return
                if (e != epoch) return
                lastRangeEnd = end
                val now = System.currentTimeMillis()
                val total = totalLength
                if (total <= 0) return
                val charsDone = end
                if (now - lastSignalAt < 300) return
                lastSignalAt = now
                main {
                    onProgress?.invoke(minOf(1.0, charsDone.toDouble() / total))
                    onPlayedChars?.invoke(charsDone)
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                val e = epochOf(utteranceId) ?: return
                if (e != epoch) return
                main { setState(SpeechState.IDLE) }
            }
        })
    }

    /** UTF-16 length of the utterance currently speaking. */
    @Volatile
    private var totalLength = 0

    /** Last char range the engine reported as sounding (for pause→resume). */
    @Volatile
    private var lastRangeEnd = 0

    /** The full text of the current/last utterance, for the pause→resume replay. */
    @Volatile
    private var utteranceText: String = ""

    /** UTF-16 offset actually reached before the last pause. */
    @Volatile
    private var resumeChar = 0

    /** The rate the paused utterance was spoken with. */
    private var pausedRate: Double = 1.0

    /** Utterance ids carry their epoch so stale callbacks can be ignored. */
    private fun utteranceId(e: Int): String = "$e:${UUID.randomUUID()}"

    private fun epochOf(id: String?): Int? {
        if (id == null) return null
        val e = id.substringBefore(':', "").toIntOrNull() ?: return null
        return e
    }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
    }

    private fun locale(): Locale = Locale.US

    override fun speak(text: String, rateMultiplier: Double) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!ready) {
            pendingText = clean to rateMultiplier
            return
        }
        tts.stop()
        epoch++
        val e = epoch
        totalLength = text.length
        utteranceText = text
        resumeChar = 0
        lastSignalAt = 0
        pausedRate = rateMultiplier

        val effectiveRate = if (speed != 1.0f) speed.toDouble() else rateMultiplier
        // Android TTS rate: 0.1…2.0 (1.0 = normal). Map our 0.5–2.0 multiplier
        // directly — the range overlaps enough for a natural mapping.
        val mapped = effectiveRate.coerceIn(0.5, 2.0)
        tts.setSpeechRate(mapped.toFloat())
        voiceIdentifier?.let { id ->
            runCatching { tts.voice = tts.voices.firstOrNull { it.name == id } }
        }
        if (tts.voice == null) tts.language = locale()

        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId(e))
    }

    override fun pause() {
        // Android TTS cannot pause mid-utterance: stop now and remember the
        // last char range the engine reported so resume() can replay the
        // remainder (the same approach the iOS system engine takes).
        if (currentState != SpeechState.SPEAKING) return
        val at = lastRangeEnd.coerceIn(0, utteranceText.length)
        if (at > 0) resumeChar = at
        // Bump the epoch FIRST: tts.stop() fires onStop/onDone for the
        // in-flight utterance, and without the bump those callbacks pass the
        // epoch guard and overwrite PAUSED with IDLE — the state machine then
        // treated every pause as a full stop (no resume possible, and no
        // bookmark position).
        epoch++
        tts.stop()
        LogStore.info("SystemTtsEngine: pause -> PAUSED (engineState=$currentState)")
        setState(SpeechState.PAUSED)
    }

    override fun resume() {
        if (currentState != SpeechState.PAUSED) return
        val text = utteranceText
        if (text.isEmpty()) {
            setState(SpeechState.IDLE)
            return
        }
        val remainder = if (resumeChar > 0 && resumeChar < text.length) text.substring(resumeChar) else text
        val offset = if (resumeChar in 1 until text.length) resumeChar else 0
        speakFrom(offset, remainder, pausedRate)
    }

    /** Speaks [text] while keeping progress/read-along offsets relative to the FULL utterance. */
    private fun speakFrom(charOffset: Int, text: String, rate: Double) {
        if (!ready) {
            pendingText = text to rate
            return
        }
        tts.stop()
        epoch++
        val e = epoch
        totalLength = charOffset + text.length
        lastSignalAt = 0
        val effectiveRate = if (speed != 1.0f) speed.toDouble() else rate
        tts.setSpeechRate(effectiveRate.coerceIn(0.5, 2.0).toFloat())
        voiceIdentifier?.let { id ->
            runCatching { tts.voice = tts.voices.firstOrNull { it.name == id } }
        }
        if (tts.voice == null) tts.language = locale()
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId(e))
    }

    override fun stop() {
        epoch++
        tts.stop()
        setState(SpeechState.IDLE)
    }

    /** True when the device has a usable TTS engine installed. */
    val isAvailable: Boolean get() = ready

    /**
     * Installed system voices for the settings picker, sorted by language
     * tag then name (iOS SettingsView.onAppear parity). Empty until the
     * engine finished connecting; a device with no voices yields [].
     */
    fun installedVoices(): List<android.speech.tts.Voice> =
        if (!ready) emptyList()
        else runCatching {
            tts.voices.sortedWith(
                compareBy({ it.locale.toLanguageTag() }, { it.name })
            )
        }.getOrDefault(emptyList())

    fun shutdown() = tts.shutdown()
}
