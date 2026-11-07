package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Keep Screen On" decision. Ported from
 * SpeechLogicTests/ScreenAwakePolicyTests.swift — the app target's window
 * side is device-only; these tests pin the part that can be reasoned about:
 * the key/default contract between the toggle and the controller, the
 * restore-on-empty invariant, and the note-editor scope rule.
 */
class ScreenAwakePolicyTest {

    // MARK: The key/default contract

    /** The toggle and the controller must name the SAME key or the feature
     *  silently does nothing. */
    @Test
    fun preferenceKeyIsStable() {
        assertEquals("keepScreenOn", ScreenAwakePolicy.PREFERENCE_KEY)
    }

    @Test
    fun defaultIsOn() {
        assertTrue(ScreenAwakePolicy.DEFAULT_ENABLED)
    }

    // MARK: The decision

    /** Off is off, no matter what is on screen. */
    @Test
    fun preferenceOffNeverKeepsAwake() {
        assertFalse(
            ScreenAwakePolicy.shouldKeepScreenAwake(
                preferenceEnabled = false,
                activeSurfaces = setOf(
                    ScreenAwakeSurface.EPUB_READER,
                    ScreenAwakeSurface.PDF_READER,
                    ScreenAwakeSurface.AUDIOBOOK_READER,
                    ScreenAwakeSurface.NOTE_READER,
                ),
            ),
        )
    }

    /** The restore invariant: no surface means the system timer runs, even
     *  with the preference on. This is what "leaving the reader must not
     *  leave the screen pinned on" is built on. */
    @Test
    fun noSurfacesNeverKeepsAwake() {
        assertFalse(
            ScreenAwakePolicy.shouldKeepScreenAwake(
                preferenceEnabled = true,
                activeSurfaces = emptySet(),
            ),
        )
    }

    @Test
    fun preferenceOnWithASurfaceKeepsAwake() {
        for (surface in ScreenAwakeSurface.entries) {
            assertTrue(
                "$surface should hold the screen awake",
                ScreenAwakePolicy.shouldKeepScreenAwake(
                    preferenceEnabled = true,
                    activeSurfaces = setOf(surface),
                ),
            )
        }
    }

    /** Two surfaces alive at once (the tab-switch overlap: a reader alive
     *  in the Books stack while the Notes stack shows the editor) keep the
     *  screen awake until the LAST one lets go — one surface disappearing
     *  must not clear another's hold. */
    @Test
    fun overlappingSurfacesStayAwakeUntilTheLast() {
        val surfaces = mutableSetOf(ScreenAwakeSurface.EPUB_READER, ScreenAwakeSurface.NOTE_READER)
        assertTrue(ScreenAwakePolicy.shouldKeepScreenAwake(true, surfaces))

        surfaces.remove(ScreenAwakeSurface.EPUB_READER)
        assertTrue(ScreenAwakePolicy.shouldKeepScreenAwake(true, surfaces))

        surfaces.remove(ScreenAwakeSurface.NOTE_READER)
        assertFalse(ScreenAwakePolicy.shouldKeepScreenAwake(true, surfaces))
    }

    // MARK: The note-editor scope rule

    @Test
    fun noteEditorIsReadingInPreview() {
        assertTrue(ScreenAwakePolicy.noteIsReadingSurface(renderMarkdown = true, showPreview = true, readAlongShowing = false))
    }

    @Test
    fun noteEditorIsReadingInReadAlong() {
        assertTrue(ScreenAwakePolicy.noteIsReadingSurface(renderMarkdown = false, showPreview = false, readAlongShowing = true))
    }

    @Test
    fun noteEditorIsTypingInEditMode() {
        // Editing needs no hold — the software keyboard already keeps the
        // screen awake.
        assertFalse(ScreenAwakePolicy.noteIsReadingSurface(renderMarkdown = true, showPreview = false, readAlongShowing = false))
        assertFalse(ScreenAwakePolicy.noteIsReadingSurface(renderMarkdown = false, showPreview = true, readAlongShowing = false))
    }
}
