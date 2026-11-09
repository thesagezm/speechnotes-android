package com.speechnotes.app.speech

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.sin

/** Debug-only PCM sink control. Invoked explicitly through adb on Waydroid. */
class AudioTrackProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bufferBytes = intent.getIntExtra("bufferBytes", 1_000_000)
        val startBeforeWrite = intent.getBooleanExtra("startBeforeWrite", false)
        val blocking = intent.getBooleanExtra("blocking", false)
        thread(name = "audio-track-probe") {
            try {
                probe(bufferBytes, startBeforeWrite, blocking)
            } catch (t: Throwable) {
                Log.e(TAG, "probe crashed", t)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }

    private fun probe(bufferBytes: Int, startBeforeWrite: Boolean, blocking: Boolean) {
        val sampleRate = 24_000
        val minimumBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val resolvedBufferBytes = if (bufferBytes > 0) bufferBytes else minimumBufferBytes
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(resolvedBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            Log.i(TAG, "start state=${track.state} playState=${track.playState} rate=$sampleRate bufferBytes=$resolvedBufferBytes blocking=$blocking")
            val samples = ShortArray(sampleRate) { i ->
                (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 12_000.0).toInt().toShort()
            }
            var offset = 0
            var playing = false
            if (startBeforeWrite) {
                track.play()
                playing = true
            }
            val writeDeadline = System.nanoTime() + 3_000_000_000L
            val mode = if (blocking) AudioTrack.WRITE_BLOCKING else AudioTrack.WRITE_NON_BLOCKING
            while (offset < samples.size && System.nanoTime() < writeDeadline) {
                val wrote = track.write(samples, offset, samples.size - offset, mode)
                check(wrote >= 0) { "write failed: $wrote" }
                if (wrote > 0) {
                    offset += wrote
                    if (!playing) {
                        track.play()
                        playing = true
                        Log.i(TAG, "play after $offset frames; playState=${track.playState}")
                    }
                } else {
                    Thread.sleep(10)
                }
            }
            check(offset == samples.size) { "write stalled at $offset/${samples.size}" }
            if (!playing) {
                track.play()
                playing = true
            }
            repeat(20) {
                Thread.sleep(100)
                Log.i(
                    TAG,
                    "head=${track.playbackHeadPosition}/${samples.size} playState=${track.playState} underruns=${track.underrunCount}",
                )
                if (track.playbackHeadPosition >= samples.size - 1) return
            }
            error("audio head never consumed the known PCM stream")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    companion object {
        private const val TAG = "AudioTrackProbe"
    }
}
