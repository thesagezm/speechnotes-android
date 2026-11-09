package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the markdown formatting bar's text mutations.
 *
 * The convention under test: a selection IntRange's `last` is the EXCLUSIVE
 * end (Compose `TextRange.start..TextRange.end`). The first Android
 * implementation read it as inclusive, and every test below names the exact
 * symptom that disagreement caused.
 */
class MarkdownFormatActionsTest {

    // MARK: - The convention

    @Test
    fun selectionCoversTheRangeExclusively() {
        val text = "abcdef"
        // "abc" as 0..3 — NOT 0..2, NOT 0..4.
        assertEquals("abc", MarkdownFormatActions.selectedText(text, 0..3))
        assertEquals(6, text.length)
    }

    @Test
    fun currentRangeRejectsOutOfBoundsSelections() {
        val text = "abc"
        assertNull(MarkdownFormatActions.currentRange(text, null))
        assertNull(MarkdownFormatActions.currentRange(text, 0..4)) // past the end
        assertNull(MarkdownFormatActions.currentRange(text, -1..2))
        assertNull(MarkdownFormatActions.currentRange(text, 3..1)) // inverted
        assertEquals(0..3, MarkdownFormatActions.currentRange(text, 0..3))
    }

    // MARK: - wrap: the off-by-one that duplicated characters

    @Test
    fun wrapDoesNotDuplicateTheCharacterAfterTheSelection() {
        // Selecting "abc" of "abcdef" and bolding used to leave "**abc**bc"
        // — the character after the selection was duplicated, so the text
        // read "**abc**bc" and the 'd' was lost to the bold run.
        val result = MarkdownFormatActions.wrap("abcdef", caret = 3, selection = 0..3, "**", "**", "bold")
        assertEquals("**abc**def", result.text)
    }

    @Test
    fun wrapOfTheEntireTextKeepsEveryCharacter() {
        // "abc" fully selected used to read as "no selection" and the bold
        // button REPLACED the whole note with the word "bold".
        val result = MarkdownFormatActions.wrap("abc", caret = 3, selection = 0..3, "**", "**", "bold")
        assertEquals("**abc**", result.text)
    }

    @Test
    fun wrapWithNoSelectionInsertsThePlaceholderAtTheCaret() {
        val result = MarkdownFormatActions.wrap("hello world", caret = 5, selection = null, "**", "**", "bold")
        assertEquals("hello**bold** world", result.text)
        assertEquals(13, result.caret) // after the whole "**bold**" insertion
    }

    @Test
    fun wrapWithCollapsedSelectionInsertsThePlaceholderAtTheCaret() {
        val result = MarkdownFormatActions.wrap("hello world", caret = 5, selection = 5..5, "`", "`", "code")
        assertEquals("hello`code` world", result.text)
    }

    @Test
    fun isWrappedDetectsTheMarkerOnBothEnds() {
        val text = "**bold**"
        assertTrue(MarkdownFormatActions.isWrapped(text, 2..6, "**"))
        // "bold" plus the closing marker's first char must be the marker.
        assertFalse(MarkdownFormatActions.isWrapped(text, 2..5, "**"))
        assertFalse(MarkdownFormatActions.isWrapped(text, null, "**"))
    }

    // MARK: - the crash: selection touching the end of the text

    @Test
    fun listMarkersOnASelectionThatReachesTheEndDoNotCrash() {
        // This is the throw. The old code ran
        // substring(sel.first, sel.last + 1) on a selection whose end was
        // the last index, so the list buttons crashed the app for exactly
        // the selection a user makes most often: to the end of the note.
        val result = MarkdownFormatActions.applyList("one\ntwo", caret = 3, selection = 0..7, "- ")
        assertEquals("- one\n- two", result.text)
        // A selection covering the second line only toggles that line.
        val second = MarkdownFormatActions.applyList("one\ntwo", caret = 3, selection = 4..7, "- ")
        assertEquals("one\n- two", second.text)
    }

    @Test
    fun listToggleRemovesTheMarkerAgain() {
        // 0..11 covers the whole string — the marker is removed, not
        // re-added twice.
        val result = MarkdownFormatActions.applyList("- one\n- two", caret = 3, selection = 0..11, "- ")
        assertEquals("one\ntwo", result.text)
    }

    @Test
    fun listOnTheCaretLineWhenNothingIsSelected() {
        val result = MarkdownFormatActions.applyList("one\ntwo", caret = 5, selection = null, "> ")
        assertEquals("one\n> two", result.text)
    }

    // MARK: - the dead heading buttons

    @Test
    fun headingTogglesOntoTheLine() {
        // The old implementation committed twice — mutate, then onEdit with
        // the ORIGINAL draft — so the second commit overwrote the first and
        // H1/H2 never changed the text at all.
        val result = MarkdownFormatActions.applyHeading("first line\nsecond", caret = 5, level = 1)
        assertEquals("# first line\nsecond", result.text)
        // "# " adds 2 chars, so a caret mid-line shifts by 2.
        assertEquals(7, result.caret)
    }

    @Test
    fun headingTogglesBackOffTheLine() {
        val result = MarkdownFormatActions.applyHeading("# first line", caret = 5, level = 1)
        assertEquals("first line", result.text)
        assertEquals(3, result.caret)
    }

    @Test
    fun headingLevelTwo() {
        val result = MarkdownFormatActions.applyHeading("title\nbody", caret = 3, level = 2)
        assertEquals("## title\nbody", result.text)
    }

    // MARK: - the caret's line

    @Test
    fun caretLineRangeAcrossLines() {
        val text = "one\ntwo\nthree"
        assertEquals(0..3, MarkdownFormatActions.caretLineRange(text, caret = 2))
        assertEquals(4..7, MarkdownFormatActions.caretLineRange(text, caret = 6))
        assertEquals(8..13, MarkdownFormatActions.caretLineRange(text, caret = 10))
        // A caret at the very end still resolves to the last line.
        assertEquals(8..13, MarkdownFormatActions.caretLineRange(text, caret = 13))
    }

    @Test
    fun caretAtZeroOnTextStartingWithNewline() {
        // lastIndexOf('\n', startIndex = 0) would find index 0 and put
        // lineStart PAST the caret at 0.
        assertEquals(0..0, MarkdownFormatActions.caretLineRange("\nsecond", caret = 0))
        assertEquals(1..7, MarkdownFormatActions.caretLineRange("\nsecond", caret = 4))
    }

    // MARK: - mutate

    @Test
    fun mutatePlacesTheCaretAfterTheInsertion() {
        val result = MarkdownFormatActions.mutate("abcdef", 3..3, "X")
        assertEquals("abcXdef", result.text)
        assertEquals(4, result.caret)
        assertEquals(4..4, result.selection)
    }

    @Test
    fun mutateReplacingTheWholeText() {
        val result = MarkdownFormatActions.mutate("old", 0..3, "new longer")
        assertEquals("new longer", result.text)
        assertEquals(10, result.caret)
    }
}
