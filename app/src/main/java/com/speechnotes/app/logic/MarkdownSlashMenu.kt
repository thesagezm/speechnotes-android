package com.speechnotes.app.logic

/**
 * Slash-command autocomplete for the markdown editor. Ported from
 * MarkdownSlashMenu.swift (speechnotes-ios): typing `/` at the start of a
 * line — or after a list/quote prefix like `- `, `> `, `1. `, `- [x] ` —
 * opens the menu. Fuzzy matching means `tbl` finds "Table" and `chk` finds
 * "Todo". Wrap commands bold/italicize the current selection when one exists.
 *
 * All offsets in and out are UTF-16, matching the Android TextField's
 * selection model.
 */
object MarkdownSlashMenu {

    data class Command(
        val id: String,
        val label: String,
        val symbol: String,
        val snippet: String,
        val placeholder: String? = null,
        val keywords: List<String> = emptyList(),
        val kind: Kind = Kind.Insert,
    ) {
        sealed class Kind {
            data object Insert : Kind()
            /** Wrap the selection (or a placeholder) as `before…after`. */
        data class Wrap(val before: String, val after: String) : Kind()
        }
    }

    /** A `/` that opened a command: slashIndex and cursorIndex are UTF-16. */
    data class Trigger(val slashIndex: Int, val cursorIndex: Int, val isValid: Boolean = true)

    data class ApplyResult(val draft: String, val caretUtf16: Int, val selectionUtf16: IntRange?)

    val commands: List<Command> = listOf(
        Command("h1", "Heading 1", "h.square", "# ", "Heading", listOf("title", "header")),
        Command("h2", "Heading 2", "h.square.fill", "## ", "Heading"),
        Command("h3", "Heading 3", "textformat.size.larger", "### ", "Heading"),
        Command("h4", "Heading 4", "textformat.size.smaller", "#### ", "Heading"),
        Command("bullet", "Bullet list", "list.bullet", "- ", "item", listOf("unordered", "ul")),
        Command("numbered", "Numbered list", "list.number", "1. ", "item", listOf("ordered", "ol")),
        Command("todo", "Todo list", "checklist", "- [ ] ", "task", listOf("task", "checkbox", "check", "unchecked")),
        Command("checked", "Completed task", "checkmark.square", "- [x] ", "task", listOf("done", "task", "checkbox", "finished")),
        Command("quote", "Quote", "text.quote", "> ", "quote", listOf("blockquote", "cite")),
        Command("code", "Code block", "curlybraces", "```\n", "code\n```", listOf("fence", "snippet")),
        Command("codeinline", "Inline code", "chevron.left.forwardslash.chevron.right", "`code`", "code", kind = Command.Kind.Wrap("`", "`")),
        Command("divider", "Divider", "minus", "\n---\n", keywords = listOf("hr", "rule", "separator", "line")),
        Command("table", "Table", "tablecells", "| |\n| --- |\n| |", keywords = listOf("grid", "columns", "cols")),
        Command("link", "Link", "link", "[", "label](https://)", listOf("url", "href")),
        Command("image", "Image", "photo", "![", "alt](https://)", listOf("picture", "pic", "img", "embed")),
        Command("bold", "Bold", "bold", "**bold**", "bold", listOf("strong"), Command.Kind.Wrap("**", "**")),
        Command("italic", "Italic", "italic", "*italic*", "italic", listOf("emphasis"), Command.Kind.Wrap("*", "*")),
        Command("strike", "Strikethrough", "strikethrough", "~~strike~~", "strike", listOf("cross", "delete"), Command.Kind.Wrap("~~", "~~")),
    )

    /**
     * The LAST `/` between line start and the caret, so the menu stays open
     * while a filter (`/tbl`) is typed after it. Null for mid-word slashes
     * like `path/to/file` and for slashes outside a command context.
     */
    fun detect(draft: String, caretOffset: Int): Trigger? {
        if (caretOffset <= 0 || caretOffset > draft.length) return null
        val lineStart = draft.lastIndexOf('\n', caretOffset - 1) + 1
        val slash = draft.lastIndexOf('/', caretOffset - 1)
        if (slash < lineStart) return null
        if (!isCommandContext(draft.substring(lineStart, slash))) return null
        return Trigger(slashIndex = slash, cursorIndex = caretOffset)
    }

    /** Valid when only whitespace, earlier slashes, or block prefixes sit between line start and the `/`. */
    private fun isCommandContext(text: String): Boolean {
        var rest = text
        for (i in 0 until 4) {
            while (rest.isNotEmpty() && (rest[0] == ' ' || rest[0] == '\t' || rest[0] == '/')) {
                rest = rest.substring(1)
            }
            if (rest.isEmpty()) return true
            if (rest.startsWith(">")) { rest = rest.substring(1); continue }
            val bullet = Regex("^[-+*][ \t]+").find(rest)
            if (bullet != null) { rest = rest.substring(bullet.value.length); continue }
            val ordered = Regex("^\\d{1,9}[.)][ \t]+").find(rest)
            if (ordered != null) { rest = rest.substring(ordered.value.length); continue }
            val task = Regex("^\\[[xX ]\\][ \t]*").find(rest)
            if (task != null) { rest = rest.substring(task.value.length); continue }
            return false
        }
        return rest.isEmpty()
    }

    /** Fuzzy filter: prefix > substring > subsequence, with keyword aliases. */
    fun filter(prefix: String): List<Command> {
        val needle = prefix.lowercase()
        if (needle.isEmpty()) return commands
        val scored = ArrayList<Pair<Command, Int>>()
        for (command in commands) {
            val candidates = (listOf(command.id, command.label) + command.keywords).map { it.lowercase() }
            val best = candidates.maxOfOrNull { fuzzyScore(needle, it) } ?: 0
            if (best > 0) scored.add(command to best)
        }
        return scored.sortedWith(compareByDescending<Pair<Command, Int>> { it.second }.thenBy { it.first.id })
            .map { it.first }
    }

    private fun fuzzyScore(needle: String, haystack: String): Int {
        if (needle.isEmpty()) return 0
        if (haystack.startsWith(needle)) return 100
        if (haystack.contains(needle)) return 80
        var index = 0
        var matched = 0
        var consecutive = 0
        var score = 0
        for (character in needle) {
            val found = haystack.indexOf(character, index)
            if (found < 0) return 0
            consecutive = if (found == index) consecutive + 1 else 1
            score += 10 + consecutive * 2
            index = found + 1
            matched++
        }
        score -= maxOf(haystack.length - matched, 0)
        return maxOf(score, 1)
    }

    /** Back-compatible entry point. */
    fun apply(command: Command, draft: String, trigger: Trigger): ApplyResult =
        apply(command, draft, trigger, caret = null, selection = null)

    /**
     * Full version: deletes `/` plus any typed prefix, and wraps the
     * selection for wrap-style commands. [caret] is the CURRENT caret
     * (UTF-16) so the typed filter text is removed too.
     */
    fun apply(
        command: Command,
        draft: String,
        trigger: Trigger,
        caret: Int?,
        selection: IntRange? = null,
    ): ApplyResult {
        val total = draft.length
        val slashStart = trigger.slashIndex
        val slashEnd = trigger.cursorIndex
        val deleteEnd = minOf(maxOf(caret ?: slashEnd, slashEnd), total)
        val deleted = deleteEnd - slashStart
        if (slashStart > deleteEnd || deleteEnd > total) return ApplyResult(draft, caret ?: slashEnd, null)

        val updated = StringBuilder(draft.length + 16)
            .append(draft, 0, slashStart)
            .append(draft, deleteEnd, total)
            .toString()

        when (command.kind) {
            is Command.Kind.Wrap -> {
                val before = command.kind.before
                val after = command.kind.after
                if (selection != null &&
                    selection.first >= deleteEnd && selection.last <= total &&
                    selection.first <= selection.last
                ) {
                    val start = selection.first - deleted
                    val end = selection.last + 1 - deleted
                    // Insert the trailing marker first (later position) so
                    // the leading insertion doesn't shift it.
                    val wrapped = StringBuilder(updated.length + before.length + after.length)
                        .append(updated, 0, start)
                        .append(before)
                        .append(updated, start, end)
                        .append(after)
                        .append(updated, end, updated.length)
                        .toString()
                    val innerStart = start + before.length
                    val innerEnd = end + before.length
                    return ApplyResult(wrapped, innerEnd, innerStart until maxOf(innerStart, innerEnd))
                }
                val inner = command.placeholder ?: ""
                val inserted = before + inner + after
                val result = StringBuilder(updated.length + inserted.length)
                    .append(updated, 0, slashStart)
                    .append(inserted)
                    .append(updated, slashStart, updated.length)
                    .toString()
                val innerStart = slashStart + before.length
                return ApplyResult(result, innerStart + inner.length, innerStart until innerStart + inner.length)
            }
            Command.Kind.Insert -> {
                val snippet = command.snippet
                val result = StringBuilder(updated.length + snippet.length)
                    .append(updated, 0, slashStart)
                    .append(snippet)
                    .append(updated, slashStart, updated.length)
                    .toString()
                var caretUtf16 = slashStart + snippet.length
                val placeholder = command.placeholder
                if (placeholder != null) {
                    val marker = when {
                        placeholder.contains("](") -> "]("
                        placeholder.contains("]") -> "]"
                        else -> null
                    }
                    if (marker != null) {
                        val markerAt = result.indexOf(marker, slashStart)
                        if (markerAt >= 0) caretUtf16 = markerAt
                    }
                }
                return ApplyResult(result, caretUtf16, null)
            }
        }
    }
}
