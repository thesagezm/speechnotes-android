package com.speechnotes.app.speech

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The soft error beep, ported from BeepPlayer.swift (speechnotes-ios):
 * when a chunk fails to synthesize, a short quiet tone tells the user
 * something was skipped — never worse than the error it reports.
 */
object BeepPlayer {

    /** Short, quiet — a nudge, not an alarm. Safe to call from any thread. */
    fun softError() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 50)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 400)
        }
    }
}
