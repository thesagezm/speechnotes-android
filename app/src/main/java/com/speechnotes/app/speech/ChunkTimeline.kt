package com.speechnotes.app.speech

import java.util.Locale

/**
 * The per-chunk performance timeline (the 2026-11-10 acceleration advisory's
 * minimum diagnostic table), one log line per synthesized chunk:
 *
 *     chunk 3/12: chars=148 audio=2.20s render=4.23s rtf=1.92 banked=1.2→11.0s bridge=off track=reused
 *
 * RTF = render wall seconds / generated audio seconds — the number that
 * decides what a gap between chunks MEANS:
 *  - RTF > 1 sustained: inference cannot keep pace with real time; buffering
 *    delays gaps but cannot eliminate them (the Waydroid host measured
 *    Kokoro-small at ≈ 1.9 — 2.2 s of audio in 4.23 s).
 *  - RTF < 1 while the bank reaches empty: producer/consumer scheduling.
 *  - RTF < 1 while audio is still queued: the audio sink (track/HAL), not us.
 *
 * Pure formatting — no clocks, no Android — so the arithmetic is assertable
 * on the JVM. Locale-pinned (US) so the line stays greppable on any device.
 */
internal object ChunkTimeline {

    /**
     * "audio=2.20s render=4.23s rtf=1.92", or "render=1.50s FAILED" when the
     * render produced no samples (null audio). Shared by the playback line
     * and the export line (which has no track/bank facts to report).
     */
    fun speedFragment(audioSeconds: Double?, renderSeconds: Double): String = when (audioSeconds) {
        null -> "render=%.2fs FAILED".format(Locale.US, renderSeconds)
        else -> "audio=%.2fs render=%.2fs rtf=%.2f"
            .format(Locale.US, audioSeconds, renderSeconds, rtf(audioSeconds, renderSeconds))
    }

    /**
     * Render wall time over generated audio time — the whole diagnosis in one
     * number. Zero/negative audio (a failed or empty render) reports 0.0
     * rather than dividing by zero.
     */
    fun rtf(audioSeconds: Double, renderSeconds: Double): Double =
        if (audioSeconds <= 0.0) 0.0 else renderSeconds / audioSeconds

    /** The playback line: [speedFragment] plus the bank depth, bridge and track facts. */
    fun line(
        label: String,
        chars: Int,
        audioSeconds: Double?,
        renderSeconds: Double,
        bankedBeforeSeconds: Double,
        bankedAfterSeconds: Double,
        bridged: Boolean,
        trackCreated: Boolean,
    ): String = "%s: chars=%d %s banked=%.1f→%.1fs bridge=%s track=%s".format(
        Locale.US,
        label,
        chars,
        speedFragment(audioSeconds, renderSeconds),
        bankedBeforeSeconds,
        bankedAfterSeconds,
        if (bridged) "on" else "off",
        if (trackCreated) "new" else "reused",
    )
}
