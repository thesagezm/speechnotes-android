package com.speechnotes.app.logic

/**
 * WAV encoding. Ported from WAVWriter.swift (speechnotes-ios): canonical
 * 16-bit PCM RIFF/WAVE, interleaved mono, exact byte counts, tolerant of
 * odd trailing samples.
 */
object WavWriter {

    /**
     * Encodes mono Float samples (expected in -1…1; values outside are
     * clamped rather than wrapped) as 16-bit PCM WAV.
     */
    fun encode(samples: FloatArray, sampleRate: Int = 24_000, channels: Int = 1): ByteArray {
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val blockAlign = channels * bytesPerSample
        val dataSize = samples.size * blockAlign
        val byteRate = sampleRate * blockAlign
        val totalSize = 44 + dataSize

        val out = ByteArray(totalSize)
        var p = 0
        fun ascii(s: String) { for (c in s) out[p++] = c.code.toByte() }
        fun le16(v: Int) { out[p++] = (v and 0xFF).toByte(); out[p++] = ((v shr 8) and 0xFF).toByte() }
        fun le32(v: Int) {
            out[p++] = (v and 0xFF).toByte(); out[p++] = ((v shr 8) and 0xFF).toByte()
            out[p++] = ((v shr 16) and 0xFF).toByte(); out[p++] = ((v shr 24) and 0xFF).toByte()
        }

        ascii("RIFF")
        le32(totalSize - 8)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)                      // PCM chunk size
        le16(1)                       // format = PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(bitsPerSample)
        ascii("data")
        le32(dataSize)

        for (s in samples) {
            val clamped = if (s > 1f) 1f else if (s < -1f) -1f else s
            le16((clamped * 32767f).toInt())
        }
        return out
    }
}
