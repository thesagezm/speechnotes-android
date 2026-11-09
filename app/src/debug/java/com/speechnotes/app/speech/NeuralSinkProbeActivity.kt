package com.speechnotes.app.speech

import android.app.Activity
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only neural-sink probe (RON, R8).
 *
 * Drives one neural engine end-to-end WITHOUT touching the foreground UI, so
 * a shared Waydroid session is not contended by screen taps:
 *
 *   mode=play   → engine.speak(text), then poll the live AudioTrack head
 *                 (read through the core's private field) every 500 ms and
 *                 log it. The log line is the evidence the user cares about:
 *                 "head=<frames>" must LEAVE 0 and reach the written frames.
 *   mode=export → engine.core.exportWav(...) into the app's external files
 *                 dir (readable via adb), then log the byte size.
 *
 * Invoked explicitly through adb; never exported, never auto-run.
 */
class NeuralSinkProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engineName = intent.getStringExtra("engine") ?: "KOKORO_SMALL"
        val mode = intent.getStringExtra("mode") ?: "play"
        val text = intent.getStringExtra("text") ?: DEFAULT_TEXT
        thread(name = "neural-sink-probe") {
            try {
                when (engineName) {
                    "KOKORO_SMALL" -> runKokoro(EngineKind.KOKORO_SMALL, mode, text)
                    "KOKORO" -> runKokoro(EngineKind.KOKORO, mode, text)
                    "SUPERTONIC" -> runSupertonic(mode, text)
                    else -> Log.e(TAG, "unknown engine $engineName")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "probe failed for $engineName", t)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }

    private fun runKokoro(kind: EngineKind, mode: String, text: String) {
        val engine = KokoroEngine(kind)
        engine.voice = "am_eric"
        try {
            drive(engine.core, engine, mode, text, kind.name)
        } finally {
            engine.release()
        }
    }

    private fun runSupertonic(mode: String, text: String) {
        val engine = SupertonicEngine()
        engine.voice = "M1"
        engine.lang = "en"
        try {
            drive(engine.core, engine, mode, text, "SUPERTONIC")
        } finally {
            engine.release()
        }
    }

    private fun drive(
        core: StreamingPlaybackCore,
        engine: SpeechEngine,
        mode: String,
        text: String,
        label: String,
    ) {
        if (mode == "export") {
            val out = File(getExternalFilesDir(null), "ron-probe-$label.wav")
            Log.i(TAG, "PROBE export start engine=$label target=${out.absolutePath}")
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: File? = null
            core.exportWav(text, out, { p ->
                if (p.toInt() % 25 == 0) Log.i(TAG, "PROBE export progress engine=$label p=$p")
            }, { file ->
                result = file
                latch.countDown()
            })
            latch.await(600, java.util.concurrent.TimeUnit.SECONDS)
            val file = result
            if (file == null || !file.isFile) {
                Log.e(TAG, "PROBE export FAILED engine=$label (no file)")
                return
            }
            val bytes = file.length()
            Log.i(TAG, "PROBE export OK engine=$label file=${file.name} bytes=$bytes")
            return
        }

        // Playback: log the live head while the session runs.
        val stateLog = StringBuilder()
        engine.onStateChanged = { s ->
            synchronized(stateLog) { stateLog.append(s).append(" ") }
            Log.i(TAG, "PROBE state engine=$label $s")
        }
        var finished = false
        engine.onFinished = {
            finished = true
            Log.i(TAG, "PROBE onFinished engine=$label")
        }
        Log.i(TAG, "PROBE speak start engine=$label chars=${text.length}")
        engine.speak(text, 1.0)
        val deadline = System.currentTimeMillis() + 240_000L
        var sawSpeaking = false
        var lastHead = -1L
        var stuckPolls = 0
        while (System.currentTimeMillis() < deadline) {
            val state = stateOf(core)
            if (state == SpeechState.SPEAKING) sawSpeaking = true
            val head = headFrames(core)
            if (head != lastHead) {
                stuckPolls = 0
                Log.i(TAG, "PROBE head engine=$label head=$head state=$state")
                lastHead = head
            } else {
                stuckPolls++
                if (stuckPolls % 20 == 0) {
                    Log.i(TAG, "PROBE head-stuck engine=$label head=$head state=$state for ${stuckPolls * 500}ms")
                }
            }
            if (finished) break
            Thread.sleep(500)
        }
        val finalHead = headFrames(core)
        Log.i(
            TAG,
            "PROBE end engine=$label sawSpeaking=$sawSpeaking finished=$finished " +
                "finalHead=$finalHead state=${stateOf(core)}"
        )
        if (!finished) engine.stop()
    }

    /** The live playback head, read through the core's private track field. */
    private fun headFrames(core: StreamingPlaybackCore): Long {
        return try {
            val field = StreamingPlaybackCore::class.java.getDeclaredField("audioTrack")
            field.isAccessible = true
            val track = field.get(core) as? AudioTrack ?: return -1L
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (t: Throwable) {
            Log.e(TAG, "head read failed", t)
            -1L
        }
    }

    /** The core's current state, read through its private field. */
    private fun stateOf(core: StreamingPlaybackCore): SpeechState {
        return try {
            val field = StreamingPlaybackCore::class.java.getDeclaredField("state")
            field.isAccessible = true
            field.get(core) as? SpeechState ?: SpeechState.IDLE
        } catch (t: Throwable) {
            SpeechState.IDLE
        }
    }

    companion object {
        private const val TAG = "NeuralSinkProbe"
        private const val DEFAULT_TEXT =
            "Neural sink verification sentence for the Waydroid audio sink. " +
                "This text is long enough to render several chunks and to keep the " +
                "playback head moving for several seconds."
    }
}
