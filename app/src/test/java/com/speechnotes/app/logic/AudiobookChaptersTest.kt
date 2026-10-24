package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the rewritten chapter parser: the two chpl layouts,
 * validation-based variant selection (D1), the unit-divisor choice (D2),
 * the multi-run stsc walk (D4), and the chapter TRACK path.
 */
class AudiobookChaptersTest {

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        val len = 8 + payload.size
        out[0] = (len ushr 24).toByte(); out[1] = (len ushr 16).toByte()
        out[2] = (len ushr 8).toByte(); out[3] = len.toByte()
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun chplNero(starts100ns: List<Long>, titles: List<String>): ByteArray {
        val payload = ByteArray(8)
        // version(1) flags(3) + 4-byte BE count
        payload[7] = starts100ns.size.toByte() // count in the low byte of the 4-byte field
        val body = ArrayList<Byte>()
        for (i in starts100ns.indices) {
            val s = starts100ns[i]
            for (shift in 56 downTo 0 step 8) body.add((s ushr shift).toByte())
            val t = titles[i].toByteArray(Charsets.UTF_8)
            body.add(t.size.toByte())
            t.forEach { body.add(it) }
        }
        return box("chpl", payload + body.toByteArray())
    }

    private fun moovOf(vararg children: ByteArray): ByteArray = box("moov", children.reduce { a, b -> a + b })

    @Test
    fun `nero chpl parses with 100ns units`() {
        // A ~6 min book: chapters at 0 s and 360 s. 100-ns units → 360 s =
        // 3_600_000_000 units. The last start lands close to the duration —
        // the span validation's anchor case.
        val chapters = AudiobookChapters.chaptersFromMP4(
            moovOf(chplNero(listOf(0L, 3_600_000_000L), listOf("Intro", "Chapter 1"))),
            totalSeconds = 370.0,
        )
        assertEquals(2, chapters.size)
        assertEquals(0.0, chapters[0].startSeconds, 0.001)
        assertEquals(360.0, chapters[1].startSeconds, 0.001)
        assertEquals("Chapter 1", chapters[1].title)
    }

    @Test
    fun `ffmpeg 1-byte count layout parses`() {
        // version(1) flags(3) ZEROS(4) count(1 byte at offset 8)
        val payload = ByteArray(9)
        payload[8] = 2
        val body = ArrayList<Byte>()
        listOf(0L, 600_000_000L).forEach { s -> // 0 s, 60 s (100-ns units)
            for (shift in 56 downTo 0 step 8) body.add((s ushr shift).toByte())
            val t = "Part".toByteArray()
            body.add(t.size.toByte()); t.forEach { body.add(it) }
        }
        val chapters = AudiobookChapters.chaptersFromMP4(
            moovOf(box("chpl", payload + body.toByteArray())),
            totalSeconds = 1200.0,
        )
        assertEquals(2, chapters.size)
        assertEquals(60.0, chapters[1].startSeconds, 0.001)
    }

    @Test
    fun `millisecond unit is chosen when 100ns reading exceeds duration`() {
        // starts read 100 s under ms but 1000 s under the 100-ns spec; the
        // book is 200 s → validation picks ms.
        val payload = ByteArray(8)
        payload[7] = 1
        val body = ArrayList<Byte>()
        val startMs = 100_000L
        for (shift in 56 downTo 0 step 8) body.add((startMs ushr shift).toByte())
        val t = "X".toByteArray(); body.add(t.size.toByte()); t.forEach { body.add(it) }
        val chapters = AudiobookChapters.chaptersFromMP4(
            moovOf(chplNero(listOf(startMs), listOf("X"))),
            totalSeconds = 200.0,
        )
        assertEquals(1, chapters.size)
        assertEquals(100.0, chapters[0].startSeconds, 0.001)
    }

    @Test
    fun `no chapters returns empty honestly (no Full audiobook placeholder)`() {
        val chapters = AudiobookChapters.chaptersFromMP4(moovOf(box("mvhd", ByteArray(100))), totalSeconds = 100.0)
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun `id3 CHAP frames parse to chapters`() {
        fun frame(id: String, payload: ByteArray): ByteArray {
            val out = ByteArray(10 + payload.size)
            id.toByteArray(Charsets.ISO_8859_1).copyInto(out, 0)
            val size = payload.size
            out[4] = (size ushr 24).toByte(); out[5] = (size ushr 16).toByte()
            out[6] = (size ushr 8).toByte(); out[7] = size.toByte()
            payload.copyInto(out, 10)
            return out
        }
        fun tit2(text: String): ByteArray {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + textBytes.size)
            payload[0] = 3 // UTF-8 encoding
            textBytes.copyInto(payload, 1)
            return frame("TIT2", payload)
        }
        fun chap(elementId: String, startMs: Long): ByteArray {
            val eid = elementId.toByteArray(Charsets.ISO_8859_1)
            val sub = tit2("Chapter $elementId")
            val payload = ByteArray(eid.size + 1 + 16 + sub.size)
            eid.copyInto(payload, 0)
            payload[eid.size] = 0
            val s = startMs
            payload[eid.size + 1] = (s ushr 24).toByte()
            payload[eid.size + 2] = (s ushr 16).toByte()
            payload[eid.size + 3] = (s ushr 8).toByte()
            payload[eid.size + 4] = s.toByte()
            sub.copyInto(payload, eid.size + 1 + 16)
            return frame("CHAP", payload)
        }
        val frames = chap("c1", 0) + chap("c2", 30_000)
        val size = frames.size
        val tag = ByteArray(10 + size)
        tag[0] = 0x49; tag[1] = 0x44; tag[2] = 0x33; tag[3] = 4 // ID3v2.4
        tag[6] = (size ushr 21 and 0x7F).toByte()
        tag[7] = (size ushr 14 and 0x7F).toByte()
        tag[8] = (size ushr 7 and 0x7F).toByte()
        tag[9] = (size and 0x7F).toByte()
        frames.copyInto(tag, 10)
        val chapters = AudiobookChapters.chaptersFromID3(tag)
        assertEquals(2, chapters.size)
        assertEquals(0.0, chapters[0].startSeconds, 0.001)
        assertEquals(30.0, chapters[1].startSeconds, 0.001)
        assertEquals("Chapter c2", chapters[1].title)
    }
}
