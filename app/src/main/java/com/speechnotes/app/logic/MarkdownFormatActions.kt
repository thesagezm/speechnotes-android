package com.speechnotes.app.logic

/**
 * The pure text-mutation half of the markdown formatting bar — the port of
 * MarkdownFormattingBar.swift's "Range editing helpers" (currentRange /
 * mutate / wrap / applyHeading / applyList), minus every Compose binding.
 *
 * ## THE SELECTION CONVENTION (read this before touching anything)
 *
 * A range is a Kotlin `IntRange` whose `last` is the **EXCLUSIVE** end —
 * exactly what Compose's `TextFieldValue.selection` (`TextRange.start..
 * TextRange.end`) is, and exactly what the editor hands the bar. A collapsed
 * caret at 5 is `5..5`; "abc" of "abcdef" is `0..3`.
 *
 * The first Android implementation of this file treated `last` as the
 * INCLUSIVE end in the read paths while `mutate` treated it as exclusive.
 * Every symptom came from that single disagreement:
 *  - wrapping duplicated the character AFTER the selection;
 *  - a selection that touched the end of the note read as "no selection"
 *    and was replaced by the placeholder word;
 *  - the list/quote buttons ran `substring(sel.first, sel.last + 1)` and
 *    threw `StringIndexOutOfBoundsException` on exactly that same
 *    selection — the app crashed from a formatting tap.
 *
 * ## THE CARET/EDIT CONTRACT
 *
 * `applyHeading` (and every action) produces ONE result that the caller
 * commits in ONE edit. The original called `mutate` and then called the
 * edit callback a second time with the ORIGINAL draft, so the second commit
 * overwrote the first and the heading markers never reached the text at all.
 *
 * Every function here returns the full post-edit state — text, caret and
 * selection — so the caller has no partially-updated intermediate to sync.
 */
object MarkdownFormatActions {

    /**
     * Everything the caller must commit after one action.
     *
     * @param selection a *collapsed* range (caret until caret) unless the
     *   action deliberately selects something (e.g. a code block body).
     * @param caret UTF-16 offset the caret lands on.
     */
    data class Result(
        val text: String,
        val caret: Int,
        val selection: IntRange,
    )

    /** The selection clamped to the draft; null when there is none. */
    fun currentRange(draft: String, selection: IntRange?): IntRange? {
        val sel = selection ?: return null
        if (sel.first < 0 || sel.last > draft.length || sel.first > sel.last) return null
        return sel
    }

    /** The text the selection covers ("" for a collapsed selection). */
    fun selectedText(draft: String, selection: IntRange?): String {
        val sel = currentRange(draft, selection) ?: return ""
        return draft.substring(sel.first, sel.last)
    }

    /**
     * The caret's line as a half-open range `lineStart..lineEnd`.
     *
     * A caret at 0 with no preceding newline gives `0..0` — the old code ran
     * `lastIndexOf('\n', startIndex = caret - 1)` and, when the text began
     * with a newline, put lineStart PAST the caret.
     */
    fun caretLineRange(draft: String, caret: Int): IntRange {
        val safeCaret = caret.coerceIn(0, draft.length)
        val lineStart = if (safeCaret <= 0) {
            0
        } else {
            val nl = draft.lastIndexOf('\n', startIndex = safeCaret - 1)
            if (nl < 0) 0 else nl + 1
        }
        val nl = draft.indexOf('\n', startIndex = safeCaret)
        val lineEnd = if (nl < 0) draft.length else nl
        return lineStart..lineEnd
    }

    /**
     * Replace [range] with [replacement]; the caret lands after the inserted
     * text (iOS `mutate`).
     *
     * The exclusive-end convention means `substring(end)` is exactly
     * right — the range covers `first` up to but not including `last`.
     *
     * The returned selection is COLLAPSED as `caret..caret` — the
     * convention, not `caret until caret`. `until` yields `caret..caret - 1`
     * when the bounds are equal, an inverted range that reads as "no
     * selection" everywhere and forces every caller to re-derive the caret.
     */
    fun mutate(draft: String, range: IntRange, replacement: String): Result {
        val start = range.first.coerceIn(0, draft.length)
        val end = range.last.coerceIn(start, draft.length)
        val updated = draft.substring(0, start) + replacement + draft.substring(end)
        val caret = (start + replacement.length).coerceIn(0, updated.length)
        return Result(updated, caret, caret..caret)
    }

    /** Wrap the selection (or insert a placeholder) with the markers. */
    fun wrap(
        draft: String,
        caret: Int,
        selection: IntRange?,
        prefix: String,
        suffix: String,
        placeholder: String,
    ): Result {
        val range = currentRange(draft, selection) ?: (caret..caret)
        val body = selectedText(draft, selection).ifBlank { placeholder }
        return mutate(draft, range, "$prefix$body$suffix")
    }

    /**
     * Toggle the heading marker on the caret's line, keeping the caret where
     * the user was typing.
     *
     * Divergence from iOS (recorded): iOS makes this a marker INSERTION at
     * the line start and jumps the caret to the line start, so pressing H1
     * on a line that already has "# " produces "## # text". Android keeps
     * the original toggle intent — press H1 twice and the heading comes off
     * — and the caret keeps its offset inside the line.
     */
    fun applyHeading(draft: String, caret: Int, level: Int): Result {
        val line = caretLineRange(draft, caret)
        val text = draft.substring(line.first, line.last)
        val marker = "#".repeat(level) + " "
        val replacement = if (text.startsWith(marker)) {
            text.removePrefix(marker)
        } else {
            marker + text
        }
        val updated = draft.substring(0, line.first) + replacement + draft.substring(line.last)
        val newCaret = (caret + (replacement.length - text.length)).coerceIn(0, updated.length)
        return Result(updated, newCaret, newCaret until newCaret)
    }

    /**
     * Toggle the marker on every line of the selection — or on the caret's
     * line when nothing is selected (iOS `applyList`).
     */
    fun applyList(draft: String, caret: Int, selection: IntRange?, marker: String): Result {
        val sel = currentRange(draft, selection)
        if (sel != null && sel.last > sel.first) {
            val lines = draft.substring(sel.first, sel.last).split('\n')
            val allPrefixed = lines.all { it.startsWith(marker) }
            val mutated = lines.joinToString("\n") {
                if (allPrefixed) it.removePrefix(marker) else marker + it
            }
            return mutate(draft, sel, mutated)
        }
        val line = caretLineRange(draft, caret)
        val text = draft.substring(line.first, line.last)
        val replacement = if (text.startsWith(marker)) {
            text.removePrefix(marker)
        } else {
            marker + text
        }
        return mutate(draft, line, replacement)
    }

    /** True when the selection is already wrapped by [marker] on both ends. */
    fun isWrapped(text: String, selection: IntRange?, marker: String): Boolean {
        val sel = selection ?: return false
        if (sel.first < marker.length || sel.last + marker.length > text.length) return false
        return text.substring(sel.first - marker.length, sel.first) == marker &&
            text.substring(sel.last, sel.last + marker.length) == marker
    }
}
