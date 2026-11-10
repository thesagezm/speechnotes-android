package com.speechnotes.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the per-chunk RTF timeline line (ChunkTimeline) — the one
 * log line that makes any device run diagnose itself: RTF > 1 sustained
 * means inference-bound, RTF < 1 with a dry bank means scheduling, RTF < 1
 * with audio still queued means the sink. The headline test pins the real
 * release-path datapoint (Kokoro-small on Waydroid: 2.2 s of audio in
 * 4.23 s → RTF 1.92) so a formatting change cannot silently bend the number
 * the diagnosis leans on.
 */
class ChunkTimelineTest {

    @Test
    fun `normal chunk line carries chars audio render rtf bank bridge and track facts`() {
        val line = ChunkTimeline.line(
            label = "chunk 3/12",
            chars = 148,
            audioSeconds = 2.20,
            renderSeconds = 4.23,
            bankedBeforeSeconds = 1.24,
            bankedAfterSeconds = 11.03,
            bridged = false,
            trackCreated = false,
        )
        // The real release-path datapoint: 2.2 s audio rendered in 4.23 s.
        assertTrue(line, line.contains("chunk 3/12: chars=148"))
        assertTrue(line, line.contains("audio=2.20s render=4.23s rtf=1.92"))
        assertTrue(line, line.contains("banked=1.2→11.0s"))
        assertTrue(line, line.contains("bridge=off"))
        assertTrue(line, line.contains("track=reused"))
    }

    @Test
    fun `failed render reports FAILED with no audio or rtf`() {
        val line = ChunkTimeline.line(
            label = "chunk 0/5",
            chars = 96,
            audioSeconds = null,
            renderSeconds = 1.5,
            bankedBeforeSeconds = 0.0,
            bankedAfterSeconds = 0.0,
            bridged = false,
            trackCreated = true,
        )
        assertTrue(line, line.contains("render=1.50s FAILED"))
        assertFalse(line, line.contains("rtf="))
        assertFalse(line, line.contains("audio="))
        assertTrue(line, line.contains("track=new"))
    }

    @Test
    fun `bridge armed and first-chunk track creation are flagged`() {
        val line = ChunkTimeline.line(
            label = "chunk 1/2",
            chars = 10,
            audioSeconds = 0.5,
            renderSeconds = 3.0,
            bankedBeforeSeconds = 0.0,
            bankedAfterSeconds = 0.5,
            bridged = true,
            trackCreated = true,
        )
        assertTrue(line, line.contains("bridge=on"))
        assertTrue(line, line.contains("track=new"))
    }

    @Test
    fun `rtf guards zero audio and divides wall by audio`() {
        assertEquals(0.0, ChunkTimeline.rtf(0.0, 1.0), 0.0)
        assertEquals(0.0, ChunkTimeline.rtf(-1.0, 1.0), 0.0)
        assertEquals(0.5, ChunkTimeline.rtf(2.0, 1.0), 0.0)
    }

    @Test
    fun `export fragment omits sink facts but keeps the speed reading`() {
        val ok = ChunkTimeline.speedFragment(2.20, 4.23)
        assertTrue(ok, ok.contains("audio=2.20s render=4.23s rtf=1.92"))
        val failed = ChunkTimeline.speedFragment(null, 0.75)
        assertEquals("render=0.75s FAILED", failed)
    }
}
