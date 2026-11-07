package com.speechnotes.app.logic

/**
 * The decision behind the "Keep Screen On" setting: whether the device's
 * idle timer — the one that dims and then locks the screen — is allowed to
 * run while the user reads. Ported verbatim from
 * Packages/SpeechLogic/.../ScreenAwakePolicy.swift (speechnotes-ios,
 * commit 484e564). Pure and assertable in CI; the Android window side
 * (View.keepScreenOn) lives in ui/playback/ScreenAwake.kt.
 */

/** The surfaces that can hold the screen awake, one per place the user
 *  actually reads.
 *
 *  A SET of these — not a counter, not a bare boolean — is what makes the
 *  app-wide decision correct: a navigation switch releases the outgoing
 *  surface and acquires the incoming one in an order Compose does not
 *  guarantee, and only set membership makes the answer independent of that
 *  order. */
enum class ScreenAwakeSurface {
    EPUB_READER,
    PDF_READER,
    AUDIOBOOK_READER,
    NOTE_READER,
}

object ScreenAwakePolicy {

    /** The preference key the setting lives under — the exact iOS name
     *  (preference keys are shared with iOS by design). One constant shared
     *  by the toggle, the controller and the tests: a key that drifted
     *  between the two would be exactly the "toggle that silently does
     *  nothing" bug. */
    const val PREFERENCE_KEY = "keepScreenOn"

    /** ON by default. The setting exists because a screen that dims
     *  mid-book loses the reader's place — the mainstream reader apps
     *  (Kindle, Play Books) hold the screen awake while reading for the
     *  same reason. The hold only applies while a reading surface is on
     *  screen, so the battery cost is bounded to actual reading; a user
     *  who dislikes it flips one toggle. */
    const val DEFAULT_ENABLED = true

    /** Whether the note editor counts as a READING surface right now.
     *
     *  THE SCOPE DECISION for the whole setting, written down where it can
     *  be tested: the editor is reading in preview and in read-along, and
     *  is TYPING in edit mode. Editing needs no hold — the software
     *  keyboard already keeps the screen awake — so the setting applies
     *  there silently-by-doing-nothing rather than pinning the screen on
     *  behind a keyboard. */
    fun noteIsReadingSurface(
        renderMarkdown: Boolean,
        showPreview: Boolean,
        readAlongShowing: Boolean,
    ): Boolean = readAlongShowing || (renderMarkdown && showPreview)

    /** The whole decision. Zero surfaces must always yield `false` — that
     *  is the "leaving the reader hands the screen back to the system
     *  timer" invariant the device round checks, and it is why the
     *  preference alone is never enough. */
    fun shouldKeepScreenAwake(
        preferenceEnabled: Boolean,
        activeSurfaces: Set<ScreenAwakeSurface>,
    ): Boolean = preferenceEnabled && activeSurfaces.isNotEmpty()
}
