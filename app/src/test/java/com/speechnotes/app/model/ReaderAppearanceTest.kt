package com.speechnotes.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for ReaderAppearance — the shape reader.js consumes on both
 * sides of the bridge (URL query at load, JSON command for live changes).
 * Pure JVM; the fields double as the reader.js STATE keys.
 */
class ReaderAppearanceTest {

    @Test
    fun defaults_matchTheiOSStruct() {
        val d = ReaderAppearance()
        assertEquals("light", d.theme)
        assertEquals(100, d.fontSize)
        assertEquals("scrolled", d.flow)
        assertEquals("book", d.font)
        assertEquals(1.6, d.lineHeight, 0.001)
        assertEquals(0.0, d.paraSpacing, 0.001)
        assertEquals(0.0, d.letterSpacing, 0.001)
        assertEquals(16, d.margin)
        assertFalse(d.respectStyles)
        assertFalse(d.autoScroll)
        assertEquals(40.0, d.autoScrollSpeed, 0.001)
        // Booleans that default TRUE (Anx-style navigation on by default).
        assertTrue(d.tapTurn)
        assertTrue(d.swipeTurn)
        assertFalse(d.tapInverted)
    }

    @Test
    fun queryItems_carryEveryAppearanceField() {
        val q = ReaderAppearance().queryItems()
        assertEquals(
            listOf(
                "theme", "fontSize", "flow", "font", "lineHeight", "paraSpacing",
                "letterSpacing", "padding", "respectStyles", "tapTurn", "swipeTurn", "tapInv",
            ),
            q.keys.toList(),
        )
        assertEquals("light", q["theme"])
        assertEquals("100", q["fontSize"])
        assertEquals("scrolled", q["flow"])
        assertEquals("16", q["padding"])
        assertEquals("1", q["tapTurn"])
        assertEquals("1", q["swipeTurn"])
        assertEquals("0", q["tapInv"])
    }

    @Test
    fun queryItems_encodeBooleansAsZeroOrOne() {
        val q = ReaderAppearance(
            respectStyles = true, tapTurn = false, swipeTurn = false, tapInverted = true,
        ).queryItems()
        assertEquals("1", q["respectStyles"])
        assertEquals("0", q["tapTurn"])
        assertEquals("0", q["swipeTurn"])
        assertEquals("1", q["tapInv"])
    }

    @Test
    fun flowIsPaginated_onlyForThePaginatedFlow() {
        assertFalse(ReaderAppearance(flow = "scrolled").flowIsPaginated)
        assertTrue(ReaderAppearance(flow = "paginated").flowIsPaginated)
    }

    @Test
    fun jsCommand_sendsEveryFieldTheBridgeReads() {
        val cmd = ReaderAppearance(
            theme = "trueBlack", fontSize = 140, flow = "paginated", font = "serif",
            lineHeight = 2.0, paraSpacing = 1.5, letterSpacing = 0.5, margin = 24,
            respectStyles = true, autoScroll = true, autoScrollSpeed = 120.0,
            tapTurn = false, swipeTurn = false, tapInverted = true,
        ).jsCommand()
        // Guarded so a webview mid-navigation is a no-op rather than an error.
        assertTrue(cmd.startsWith("window.readerAppearance && window.readerAppearance("))
        assertTrue(cmd.trimEnd().endsWith(");"))
        for (field in listOf(
            "theme", "fontSize", "flow", "font", "lineHeight", "paraSpacing",
            "letterSpacing", "margin", "respectStyles", "autoScroll", "autoScrollSpeed",
            "tapTurn", "swipeTurn", "tapInverted",
        )) {
            assertTrue("missing $field in $cmd", cmd.contains("\"$field\""))
        }
        assertTrue(cmd.contains("\"theme\":\"trueBlack\""))
        assertTrue(cmd.contains("\"fontSize\":140"))
        assertTrue(cmd.contains("\"tapTurn\":false"))
        assertTrue(cmd.contains("\"tapInverted\":true"))
    }

    @Test
    fun autoScrollCommands_reflectCurrentState() {
        val a = ReaderAppearance(autoScroll = true, autoScrollSpeed = 75.0)
        assertTrue(a.autoScrollCommand().contains("readerAutoScroll(true, 75.0)"))
        assertTrue(a.autoScrollSpeedCommand().contains("readerAutoScrollSpeed(75.0)"))
        assertFalse(ReaderAppearance().autoScrollCommand().contains("readerAutoScroll(true"))
    }
}
