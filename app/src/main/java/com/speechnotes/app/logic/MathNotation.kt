package com.speechnotes.app.logic

/**
 * LaTeX-ish maths → (a) the form the reader sees and (b) the form the TTS
 * engine hears. Ported verbatim from the iOS SpeechLogic MathNotation.swift
 * + MathCommands.swift (v1.8.1, commits 1fa3482 / 1eab53c / 626e505) — the
 * JVM tests are the parity contract.
 *
 * ## Why this exists
 *
 * A note written in maths — `$\beta$`, `\sum_{i=1}^{n} x_i`, `E = mc^2` —
 * used to reach the preview as raw source text and the engine as mangled
 * markers ("backslash", "frac", "dollar"). Nothing understood maths.
 *
 * ## The two outputs
 *
 * `display` is a Unicode approximation of the typeset formula: `β`, `∑`,
 * `x₁`, `E = mc²`. `spoken` is the SAME formula written out in words an
 * engine can say: "beta", "sum from i equals 1 to n of x sub i", "E equals
 * m c squared". Every maths symbol becomes a WORD, because the engines have
 * no phoneme for `∑` or `β`.
 *
 * ## Scope
 *
 * A READER, not a TeX engine. It handles the constructs real notes carry
 * (fractions, roots, powers, subscripts, Greek, operators, sums and
 * integrals with limits, matrices, `\text{}` words, accents) and leaves
 * anything it does not understand as literal source text rather than
 * guessing. Divergence from iOS: formulas are recognized per LINE only
 * (a `$$…$$` block must open and close on one line) — the Android markdown
 * pipeline is line-based where iOS carries a block-level AST.
 */
object MathNotation {

    // MARK: - Public API

    /** One formula, in both forms. */
    data class Formula(
        val source: String,
        val display: String,
        val spoken: String,
        val isDisplay: Boolean,
    )

    /** One maths region inside a line of text. */
    data class Segment(
        val source: String,
        val display: String,
        val spoken: String,
        /** True when the region was a bare command (`\beta`) not inside `$…$`. */
        val isBare: Boolean,
        /** True for a standalone formula (`$$…$$`). */
        val isDisplay: Boolean = false,
    )

    /** A line of text split into plain text and maths regions. */
    data class SegmentLine(val parts: List<Part>) {
        sealed class Part {
            data class Text(val text: String) : Part()
            data class Math(val segment: Segment) : Part()
        }
    }

    /**
     * Parse one formula body (the contents between the delimiters), or null
     * when the source is empty or survives completely unrecognised (an
     * unknown command renders as its own source — refuse it, so the caller
     * keeps the original text).
     */
    fun formula(source: String, isDisplay: Boolean): Formula? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        val mode = if (isDisplay) Mode.DISPLAY else Mode.INLINE
        val parser = Parser(trimmed, mode)
        // ONE parse, TWO walks: the spoken form must describe the same tree
        // the display form shows.
        val nodes = parser.parse()
        val display = parser.renderList(nodes, mode)
        val spoken = normalizeSpeech(parser.speakList(nodes))
        if (display == trimmed && trimmed.contains("\\")) return null
        return Formula(trimmed, display, spoken, isDisplay)
    }

    /**
     * Split a line into text and maths parts. Recognises `$…$` (inline),
     * `$$…$$` (display) and bare commands (`\beta`). A `$` that has no
     * closing partner is literal text — a note about prices ("costs $5")
     * must not lose its dollar sign.
     */
    fun segments(line: String): SegmentLine {
        val parts = ArrayList<SegmentLine.Part>()
        val pending = StringBuilder()
        var index = 0

        fun flushText() {
            if (pending.isNotEmpty()) {
                parts.add(SegmentLine.Part.Text(pending.toString()))
                pending.clear()
            }
        }

        while (index < line.length) {
            val character = line[index]

            // `$$…$$` — display maths (single line; see the scope note).
            if (character == '$' && line.startsWith("$$", index)) {
                val afterOpen = index + 2
                if (afterOpen <= line.length) {
                    val close = line.indexOf("$$", afterOpen)
                    if (close >= 0) {
                        val body = line.substring(afterOpen, close)
                        val parsed = formula(body, isDisplay = true)
                        if (parsed != null) {
                            flushText()
                            parts.add(
                                SegmentLine.Part.Math(
                                    Segment(parsed.source, parsed.display, parsed.spoken, isBare = false, isDisplay = true),
                                ),
                            )
                            index = close + 2
                            continue
                        }
                        pending.append(line, index, close + 2)
                        index = close + 2
                        continue
                    }
                }
            }

            // `$…$` — inline maths. The closing `$` must be on the SAME
            // line, and an escaped `\$` inside the body closes nothing.
            if (character == '$') {
                val afterOpen = index + 1
                val close = unescapedDollar(line, from = afterOpen, before = line.length)
                if (close != null) {
                    val body = line.substring(afterOpen, close)
                    if (looksLikeMath(body)) {
                        val parsed = formula(body, isDisplay = false)
                        if (parsed != null) {
                            flushText()
                            parts.add(
                                SegmentLine.Part.Math(
                                    Segment(parsed.source, parsed.display, parsed.spoken, isBare = false),
                                ),
                            )
                            index = close + 1
                            continue
                        }
                    }
                }
                pending.append('$')
                index++
                continue
            }

            // `\$` — an escaped dollar sign is PROSE: it neither opens nor
            // closes a formula ("the fee is \$5, once").
            if (character == '\\' && line.startsWith("\\$", index)) {
                pending.append('$')
                index += 2
                continue
            }

            // `\command` — a bare maths command.
            if (character == '\\') {
                val command = bareCommand(line, index)
                if (command != null) {
                    flushText()
                    parts.add(SegmentLine.Part.Math(command.first))
                    index = command.second
                    continue
                }
            }

            pending.append(character)
            index++
        }

        flushText()
        return SegmentLine(parts)
    }

    /**
     * The plain text of a line with every maths region replaced by its
     * SPOKEN form — the TTS contract.
     */
    fun spokenText(line: String): String = buildString {
        for (part in segments(line).parts) {
            when (part) {
                is SegmentLine.Part.Text -> append(part.text)
                is SegmentLine.Part.Math -> append(part.segment.spoken)
            }
        }
    }

    /**
     * The display form of a line with every maths region replaced by its
     * Unicode rendering.
     */
    fun displayText(line: String): String = buildString {
        for (part in segments(line).parts) {
            when (part) {
                is SegmentLine.Part.Text -> append(part.text)
                is SegmentLine.Part.Math -> append(part.segment.display)
            }
        }
    }

    // MARK: - What counts as maths

    /**
     * The first `$` at or after [from] (and before [before]) that is not
     * part of a `\X` escape pair — an escaped `\$` closes nothing. Returns
     * the INDEX (iOS 626e505: an index, not a range).
     */
    fun unescapedDollar(line: String, from: Int, before: Int): Int? {
        var cursor = from
        while (cursor < before) {
            when (line[cursor]) {
                '$' -> return cursor
                '\\' -> {
                    // `\X` (including `\\` and `\$`) is one unit, never a
                    // delimiter.
                    val after = cursor + 1
                    if (after >= before) return null
                    cursor = after + 1
                }
                else -> cursor++
            }
        }
        return null
    }

    /**
     * Whether the text between two `$` signs is a FORMULA rather than prose
     * that happens to contain a dollar sign ("costs $5 and $10" vs "the
     * value is $x + y$").
     */
    private fun looksLikeMath(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        // Prices and bare numbers: digits, separators, spaces only.
        if (trimmed.all { it.isDigit() || it == ',' || it == '.' || it == ' ' }) return false
        // Leading/trailing whitespace means the author was writing prose
        // around a dollar sign, not a formula.
        if (body.startsWith(" ") || body.endsWith(" ")) return false
        // A short all-letter body is a variable (`$r$`, `$x$`).
        if (trimmed.length <= 2 && trimmed.all { it.isLetter() }) return true
        // A maths signal: a LaTeX command, a maths operator, a Greek letter,
        // a caret/underscript, a brace, a percent, an asterisk, a degree.
        val signals = charArrayOf(
            '\\', '^', '_', '{', '}', '=', '<', '>', '+', '−', '±',
            '×', '÷', '√', '∑', '∏', '∫', '∞', '≤', '≥', '≠',
            '≈', '→', '←', 'α', 'β', 'γ', 'δ', 'θ', 'λ', 'μ',
            'π', 'σ', 'φ', 'ω', 'Δ', 'Ω', 'Σ', 'Π', '%', '*', '°',
        )
        return signals.any { body.contains(it) }
    }

    // MARK: - Bare commands

    /** A bare `\command` at [index], or null — with the index past its name. */
    private fun bareCommand(line: String, index: Int): Pair<Segment, Int>? {
        val afterSlash = index + 1
        var name = ""
        var cursor = afterSlash
        while (cursor < line.length && line[cursor].isLetter()) {
            name += line[cursor]
            cursor++
        }
        if (name.isEmpty()) return null
        if (name !in MathCommandTable.bareAllowed) return null
        val definition = MathCommandTable.commands[name] ?: return null

        val segment = when (val kind = definition.kind) {
            is MathCommandTable.Kind.Symbol -> Segment("\\" + name, kind.display, kind.spoken, isBare = true)
            is MathCommandTable.Kind.Function -> Segment("\\" + name, kind.display, kind.spoken, isBare = true)
            is MathCommandTable.Kind.Dots -> Segment("\\" + name, kind.display, "and so on", isBare = true)
        }
        return segment to cursor
    }

    // MARK: - The parser

    /** Rendering mode: inline keeps fractions small; display allows larger forms. */
    enum class Mode { INLINE, DISPLAY }

    /**
     * The LaTeX reader. Walks the source once, building a tree of nodes;
     * each node knows how to render itself (Unicode) and how to say itself
     * (words). Index-based (the iOS original uses String.Index; Kotlin
     * Chars are UTF-16 units — every glyph this grammar emits is BMP).
     */
    private class Parser(val source: String, val mode: Mode) {
        var index: Int = 0

        sealed class Node {
            data class Text(val text: String) : Node()
            data class Literal(val text: String) : Node()
            data class Symbol(val display: String, val spoken: String) : Node()
            data class Function(val display: String, val spoken: String) : Node()
            data class Fraction(val numerator: List<Node>, val denominator: List<Node>) : Node()
            data class Root(val rootIndex: List<Node>?, val radicand: List<Node>) : Node()
            data class Superscript(val base: List<Node>, val exponent: List<Node>) : Node()
            data class Sub(val base: List<Node>, val index: List<Node>) : Node()
            data class Group(val children: List<Node>) : Node()
            data class Prose(val children: List<Node>) : Node()
            data class BigOperator(val display: String, val spoken: String, val lower: List<Node>?, val upper: List<Node>?) : Node()
            data class Matrix(val rows: List<List<List<Node>>>) : Node()
            data class Dots(val display: String) : Node()
            data class Accent(val glyph: String, val base: List<Node>) : Node()
            /** A quantity in degrees — `45^\circ`. Spoken "degrees", not composition. */
            data class Degrees(val base: List<Node>) : Node()
        }

        /** Parse the whole source into nodes. */
        fun parse(): List<Node> {
            val out = ArrayList<Node>()
            while (index < source.length) {
                out.add(parseAtom())
            }
            return out
        }

        /** One ATOM: a base plus any scripts attached to it. */
        fun parseAtom(): Node = attachScripts(parseBase())

        /** The scripts that follow a base: `^` for a power, `_` for an index. */
        fun attachScripts(base: Node): Node {
            var result = base
            while (index < source.length) {
                when (source[index]) {
                    '^' -> {
                        index++
                        val exponent = parseArgument()
                        // `45^\circ` — LaTeX's degree notation. As an
                        // EXPONENT the ring is the degree sign ("45°",
                        // spoken "45 degrees"), not composition.
                        val single = exponent.singleOrNull()
                        if (single is Node.Symbol && single.display == "∘") {
                            result = Node.Degrees(listOf(result))
                            continue
                        }
                        result = Node.Superscript(listOf(result), exponent)
                    }
                    '_' -> {
                        index++
                        val sub = parseArgument()
                        result = Node.Sub(listOf(result), sub)
                    }
                    else -> return result
                }
            }
            return result
        }

        /** The base of an atom: a command, a group, an identifier, or one character. */
        fun parseBase(): Node {
            if (index >= source.length) return Node.Text("")
            val character = source[index]

            if (character == '\\') return parseCommand()
            if (character == '{') return Node.Group(parseBracedGroup())
            if (character == '}') {
                // A stray closing brace — keep it rather than swallow it.
                index++
                return Node.Text("}")
            }
            if (character == '^' || character == '_') {
                // A script with no base (malformed). Keep the marker.
                index++
                return Node.Text(character.toString())
            }
            // A run of letters and digits is ONE identifier: "mc" in `mc^2`
            // is the base of the power, not two separate atoms.
            val identifier = StringBuilder()
            while (index < source.length && (source[index].isLetter() || source[index].isDigit())) {
                identifier.append(source[index])
                index++
            }
            if (identifier.isNotEmpty()) return Node.Text(identifier.toString())
            // Any other character — space, operator, bracket.
            index++
            return Node.Text(character.toString())
        }

        /** A `{…}` group. The braces are consumed; the contents are parsed as atoms. */
        fun parseBracedGroup(): List<Node> {
            index++ // consume "{"
            val out = ArrayList<Node>()
            while (index < source.length && source[index] != '}') {
                out.add(parseAtom())
            }
            if (index < source.length) index++ // consume "}"
            return out
        }

        /** The argument of a power/subscript: a group, or one atom. */
        fun parseArgument(): List<Node> {
            skipSpaces()
            if (index >= source.length) return emptyList()
            if (source[index] == '{') return parseBracedGroup()
            return listOf(parseAtom())
        }

        /** A `\command` and whatever arguments it takes. */
        fun parseCommand(): Node {
            index++ // consume the backslash
            var name = ""
            while (index < source.length && source[index].isLetter()) {
                name += source[index]
                index++
            }
            // `\ ` (escaped space) and `\{` / `\}` / `\%` — a literal.
            if (name.isEmpty()) {
                if (index >= source.length) return Node.Literal("\\")
                val character = source[index]
                index++
                return Node.Literal(literalCharacter(character))
            }

            when (name) {
                "frac", "dfrac", "tfrac" -> {
                    val numerator = parseGroupArgument()
                    val denominator = parseGroupArgument()
                    return Node.Fraction(numerator, denominator)
                }
                "sqrt" -> {
                    // `\sqrt[3]{x}` — an optional index.
                    skipSpaces()
                    var rootIndex: List<Node>? = null
                    if (index < source.length && source[index] == '[') {
                        index++
                        val out = ArrayList<Node>()
                        while (index < source.length && source[index] != ']') {
                            out.add(parseAtom())
                        }
                        if (index < source.length) index++
                        rootIndex = out
                    }
                    val radicand = parseGroupArgument()
                    return Node.Root(rootIndex, radicand)
                }
                "text", "textrm", "mathrm", "mathbf", "mathit", "mathsf", "mathtt",
                "operatorname", "mbox",
                -> {
                    // A WORD inside maths — PROSE: its operators are
                    // punctuation, spoken as they read.
                    return Node.Prose(parseGroupArgument())
                }
                "left", "right", "big", "Big", "bigg", "Bigg", "displaystyle",
                "textstyle", "limits", "nolimits",
                -> {
                    // Size/placement modifiers — no meaning on their own.
                    return Node.Text("")
                }
                "mathbb", "mathcal", "mathscr", "mathfrak", "boldsymbol" -> {
                    // The STYLE is dropped; the letters stay.
                    return Node.Group(parseGroupArgument())
                }
                "hat", "bar", "vec", "tilde", "dot", "ddot", "overline",
                "underline", "widehat", "widetilde",
                -> {
                    val accent = accentGlyph(name)
                    val base = parseGroupArgument()
                    return Node.Accent(accent, base)
                }
                "begin" -> return parseMatrix()
                "end" -> {
                    // A stray `\end` — the matrix parser consumes the real one.
                    consumeEndEnvironment()
                    return Node.Text("")
                }
                "binom" -> {
                    val top = parseGroupArgument()
                    val bottom = parseGroupArgument()
                    return Node.Fraction(top, bottom)
                }
                "quad" -> return Node.Text("  ")
                "qquad" -> return Node.Text("    ")
                "," -> return Node.Text(" ")
                ";" -> return Node.Text(" ")
                "!" -> return Node.Text("")
                "not" -> {
                    // `\not=` — the negation applies to the NEXT symbol.
                    val next = parseAtom()
                    if (next is Node.Symbol && next.display == "=") {
                        return Node.Symbol("≠", "not equal to")
                    }
                    return Node.Symbol("¬", "not")
                }
            }

            val definition = MathCommandTable.commands[name]
                // An unknown command stays literal — a note mentioning
                // `\emph` or a package name must not lose the text.
                ?: return Node.Literal("\\" + name)

            return when (val kind = definition.kind) {
                is MathCommandTable.Kind.Symbol -> {
                    // A BIG OPERATOR with limits: `\sum_{i=1}^{n}`. The
                    // limits belong to the operator, not to the text after.
                    if (kind.display in BIG_OPERATORS) {
                        val limits = parseLimits()
                        Node.BigOperator(kind.display, kind.spoken, limits.first, limits.second)
                    } else {
                        Node.Symbol(kind.display, kind.spoken)
                    }
                }
                is MathCommandTable.Kind.Function -> Node.Function(kind.display, kind.spoken)
                is MathCommandTable.Kind.Dots -> Node.Dots(kind.display)
            }
        }

        /** The argument of a command: a `{…}` group, or one atom. */
        fun parseGroupArgument(): List<Node> {
            skipSpaces()
            if (index >= source.length) return emptyList()
            if (source[index] == '{') return parseBracedGroup()
            return listOf(parseAtom())
        }

        /** The `_lower` and `^upper` that follow a big operator, either order. */
        fun parseLimits(): Pair<List<Node>?, List<Node>?> {
            var lower: List<Node>? = null
            var upper: List<Node>? = null
            repeat(2) {
                skipSpaces()
                if (index >= source.length) return lower to upper
                when (source[index]) {
                    '_' -> {
                        index++
                        lower = parseArgument()
                    }
                    '^' -> {
                        index++
                        upper = parseArgument()
                    }
                    else -> return lower to upper
                }
            }
            return lower to upper
        }

        /** The matrix environment: `\begin{matrix}…\end{matrix}`. */
        fun parseMatrix(): Node {
            // Skip the environment name (`{matrix}`, `{pmatrix}`, …).
            skipSpaces()
            if (index < source.length && source[index] == '{') {
                parseBracedGroup()
            }
            val rows = ArrayList<List<List<Node>>>()
            var row = ArrayList<List<Node>>()
            var cell = ArrayList<Node>()

            while (index < source.length) {
                if (atEndEnvironment()) {
                    consumeEndEnvironment()
                    break
                }
                // `\\` ends a row.
                if (source[index] == '\\' && index + 1 < source.length && source[index + 1] == '\\') {
                    index += 2
                    row.add(cell)
                    cell = ArrayList()
                    if (row.isNotEmpty()) {
                        rows.add(row)
                        row = ArrayList()
                    }
                    continue
                }
                // `&` ends a cell.
                if (source[index] == '&') {
                    row.add(cell)
                    cell = ArrayList()
                    index++
                    continue
                }
                // A stray `}` at this level means the source was malformed.
                if (source[index] == '}') break
                cell.add(parseAtom())
            }
            if (cell.isNotEmpty() || row.isNotEmpty()) {
                row.add(cell)
                if (row.isNotEmpty()) rows.add(row)
            }
            return Node.Matrix(rows)
        }

        /** True when the source is at an `\end{…}` marker. */
        fun atEndEnvironment(): Boolean {
            if (index >= source.length || source[index] != '\\') return false
            val after = index + 1
            if (after >= source.length || source[after] != 'e') return false
            return source.startsWith("nd", after + 1)
        }

        /** Consume an `\end{…}` marker. */
        fun consumeEndEnvironment() {
            // "\end"
            index++
            repeat(3) { if (index < source.length) index++ }
            // "{name}"
            skipSpaces()
            if (index < source.length && source[index] == '{') {
                parseBracedGroup()
            }
        }

        fun accentGlyph(name: String): String = when (name) {
            "hat", "widehat" -> "^"
            "bar", "overline" -> "‾"
            "vec" -> "→"
            "tilde", "widetilde" -> "~"
            "dot" -> "˙"
            "ddot" -> "¨"
            else -> ""
        }

        /** The escaped character after a backslash that is not a command. */
        fun literalCharacter(character: Char): String = when (character) {
            '%', '_', '#', '&', '$', '{', '}', '\\', ' ' -> character.toString()
            else -> character.toString()
        }

        fun skipSpaces() {
            while (index < source.length && source[index] == ' ') index++
        }

        companion object {
            private val BIG_OPERATORS = setOf("∑", "∏", "∫", "∮", "∬", "∭")
        }

        // MARK: Rendering

        /** The display form of a node list. */
        fun renderList(nodes: List<Node>, mode: Mode): String = buildString {
            for ((offset, node) in nodes.withIndex()) {
                append(renderNode(node, mode))
                // A named function is followed by a space: "sin x", not "sinx".
                val next = nodes.getOrNull(offset + 1)
                if (node is Node.Function && next is Node.Text && !next.text.startsWith(" ")) {
                    append(" ")
                }
            }
        }

        private fun renderNode(node: Node, mode: Mode): String = when (node) {
            is Node.Text -> node.text
            is Node.Literal -> node.text
            is Node.Symbol -> node.display
            is Node.Function -> node.display
            is Node.Dots -> node.display
            is Node.Group -> renderList(node.children, mode)
            is Node.Prose -> renderList(node.children, mode)
            is Node.Fraction -> {
                val top = renderList(node.numerator, mode)
                val bottom = renderList(node.denominator, mode)
                if (mode == Mode.DISPLAY) "($top)/($bottom)" else "$top/$bottom"
            }
            is Node.Root -> {
                val body = renderList(node.radicand, mode)
                val rootIndex = node.rootIndex
                if (rootIndex != null) "√[${renderList(rootIndex, mode)}]($body)" else "√($body)"
            }
            is Node.Superscript -> {
                val baseText = renderList(node.base, mode)
                val exponentText = renderList(node.exponent, mode)
                baseText + superscriptGlyphs(exponentText)
            }
            is Node.Sub -> {
                val baseText = renderList(node.base, mode)
                val subscriptText = renderList(node.index, mode)
                baseText + subscriptGlyphs(subscriptText)
            }
            is Node.BigOperator -> {
                var out = node.display
                // The limits stay in plain text: `∑_i=1` reads better than a
                // row of superscript glyphs nobody can parse.
                node.lower?.let { out += "_${renderList(it, mode)}" }
                node.upper?.let { out += "^${renderList(it, mode)}" }
                out
            }
            is Node.Matrix -> node.rows.joinToString(", ") { row ->
                "(" + row.joinToString(", ") { cell -> renderList(cell, mode) } + ")"
            }
            is Node.Accent -> {
                val body = renderList(node.base, mode)
                if (node.glyph.isEmpty()) body else "${node.glyph}$body"
            }
            is Node.Degrees -> renderList(node.base, mode) + "°"
        }

        // MARK: Speaking

        /**
         * The spoken form of a node list: every node boundary gets a
         * SEPARATOR, so "i", "=", "1" never weld into "iequals1".
         */
        fun speakList(nodes: List<Node>): String = nodes
            .mapIndexed { offset, node -> if (offset > 0) " " + speakNode(node) else speakNode(node) }
            .joinToString("")

        private fun speakNode(node: Node): String = when (node) {
            is Node.Text ->
                // The operators in a formula become WORDS.
                speakOperators(node.text)
            is Node.Literal -> node.text
            is Node.Symbol -> node.spoken
            is Node.Function -> node.spoken
            is Node.Dots -> "and so on"
            is Node.Group -> speakList(node.children)
            is Node.Prose ->
                // Prose keeps its punctuation: "if x > 5" reads as prose.
                node.children.joinToString("") { speakRawNode(it) }
            is Node.Fraction -> "${speakList(node.numerator)} divided by ${speakList(node.denominator)}"
            is Node.Root -> {
                val body = speakList(node.radicand)
                val rootIndex = node.rootIndex
                if (rootIndex != null) "the ${speakList(rootIndex)}th root of $body" else "the square root of $body"
            }
            is Node.Superscript -> "${speakList(node.base)} to the power of ${speakList(node.exponent)}"
            is Node.Sub -> "${speakList(node.base)} sub ${speakList(node.index)}"
            is Node.BigOperator -> {
                var out = node.spoken
                node.lower?.let { out += " from ${speakList(it)}" }
                node.upper?.let { out += " to ${speakList(it)}" }
                out
            }
            is Node.Matrix -> node.rows.mapIndexed { offset, row ->
                val cells = row.joinToString(", ") { cell -> speakList(cell) }
                if (offset == 0) "the matrix $cells" else "and $cells"
            }.joinToString(", ")
            is Node.Accent -> speakList(node.base)
            is Node.Degrees -> "${speakList(node.base)} degrees"
        }

        /** The PROSE path: a node's operators left as they are. */
        private fun speakRawNode(node: Node): String = when (node) {
            is Node.Text -> node.text
            is Node.Literal -> node.text
            is Node.Symbol -> node.spoken
            is Node.Function -> node.spoken
            is Node.Dots -> "and so on"
            is Node.Group -> node.children.joinToString("") { speakRawNode(it) }
            is Node.Prose -> node.children.joinToString("") { speakRawNode(it) }
            is Node.Fraction -> "${rawList(node.numerator)} divided by ${rawList(node.denominator)}"
            is Node.Root -> {
                val body = rawList(node.radicand)
                val rootIndex = node.rootIndex
                if (rootIndex != null) "the ${rawList(rootIndex)}th root of $body" else "the square root of $body"
            }
            is Node.Superscript -> "${rawList(node.base)} to the power of ${rawList(node.exponent)}"
            is Node.Sub -> "${rawList(node.base)} sub ${rawList(node.index)}"
            is Node.BigOperator -> {
                var out = node.spoken
                node.lower?.let { out += " from ${rawList(it)}" }
                node.upper?.let { out += " to ${rawList(it)}" }
                out
            }
            is Node.Matrix -> node.rows.joinToString(", ") { row ->
                row.joinToString(", ") { cell -> rawList(cell) }
            }
            is Node.Accent -> rawList(node.base)
            is Node.Degrees -> "${rawList(node.base)} degrees"
        }

        private fun rawList(nodes: List<Node>): String = nodes.joinToString("") { speakRawNode(it) }
    }

    // MARK: - Operators the engine speaks as words

    /**
     * The ASCII operators a formula is built from, and what the engine says
     * for them. The DISPLAY keeps the symbol; the SPOKEN form uses a word.
     * Words carry NO padding: spacing is decided by the surrounding text.
     */
    private val operatorWords: Map<Char, String> = mapOf(
        '=' to "equals",
        '+' to "plus",
        '-' to "minus",
        '*' to "times",
        '/' to "divided by",
        '<' to "less than",
        '>' to "greater than",
        '(' to "open bracket",
        ')' to "close bracket",
        '[' to "open bracket",
        ']' to "close bracket",
        '%' to "percent",
        '°' to "degrees",
    )

    /**
     * The spoken form of a plain-text run: the operators inside it become
     * words. A word is padded with a space on a side only when the source
     * had no whitespace there — `x = 5` keeps its spacing, `x=5` becomes
     * "x equals 5" not "xequals5".
     */
    private fun speakOperators(text: String): String {
        val out = StringBuilder()
        val characters = text.toCharArray()
        for ((offset, character) in characters.withIndex()) {
            val word = operatorWords[character]
            if (word == null) {
                out.append(character)
                continue
            }
            val before = if (offset > 0) characters[offset - 1] else ' '
            val after = if (offset + 1 < characters.size) characters[offset + 1] else ' '
            if (!before.isWhitespace()) out.append(' ')
            out.append(word)
            if (!after.isWhitespace()) out.append(' ')
        }
        return out.toString()
    }

    /** One space between words, no leading or trailing space. */
    private fun normalizeSpeech(text: String): String {
        val out = StringBuilder()
        var pendingSpace = false
        for (character in text) {
            if (character.isWhitespace()) {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(character)
        }
        return out.toString()
    }

    // MARK: - Superscript / subscript glyphs

    /** Unicode super/subscript digits and letters, with the caret fallback. */
    private val superscriptMap: Map<Char, String> = mapOf(
        '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴",
        '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹",
        '+' to "⁺", '-' to "⁻", '=' to "⁼",
        '(' to "⁽", ')' to "⁾",
        'a' to "ᵃ", 'b' to "ᵇ", 'c' to "ᶜ", 'd' to "ᵈ", 'e' to "ᵉ", 'f' to "ᶠ",
        'g' to "ᵍ", 'h' to "ʰ", 'i' to "ⁱ", 'j' to "ʲ", 'k' to "ᵏ", 'l' to "ˡ",
        'm' to "ᵐ", 'n' to "ⁿ", 'o' to "ᵒ", 'p' to "ᵖ", 'r' to "ʳ", 's' to "ˢ",
        't' to "ᵗ", 'u' to "ᵘ", 'v' to "ᵛ", 'w' to "ʷ", 'x' to "ˣ", 'y' to "ʸ", 'z' to "ᶻ",
    )

    private val subscriptMap: Map<Char, String> = mapOf(
        '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄",
        '5' to "₅", '6' to "₆", '7' to "₇", '8' to "₈", '9' to "₉",
        '+' to "₊", '-' to "₋", '=' to "₌",
        '(' to "₍", ')' to "₎",
        'a' to "ₐ", 'e' to "ₑ", 'h' to "ₕ", 'i' to "ᵢ", 'j' to "ⱼ", 'k' to "ₖ",
        'l' to "ₗ", 'm' to "ₘ", 'n' to "ₙ", 'o' to "ₒ", 'p' to "ₚ", 'r' to "ᵣ",
        's' to "ₛ", 't' to "ₜ", 'u' to "ᵤ", 'v' to "ᵥ", 'x' to "ₓ",
    )

    private fun superscriptGlyphs(body: String): String {
        val out = StringBuilder()
        for (character in body) {
            val glyph = superscriptMap[character] ?: return "^($body)"
            out.append(glyph)
        }
        return if (out.isEmpty()) "^" else out.toString()
    }

    private fun subscriptGlyphs(body: String): String {
        val out = StringBuilder()
        for (character in body) {
            val glyph = subscriptMap[character] ?: return "_$body"
            out.append(glyph)
        }
        return if (out.isEmpty()) "_" else out.toString()
    }
}

/**
 * The LaTeX command table behind [MathNotation]. Ported from
 * MathCommands.swift. Every entry carries TWO forms of the same symbol:
 * `display` (the Unicode glyph) and `spoken` (a word) — the two must never
 * be derived from each other, because the engines choke on raw glyphs.
 *
 * Structural commands (`\frac`, `\sqrt`, `\text`, `\left`…) are NOT here —
 * the parser handles them directly because they take arguments.
 */
object MathCommandTable {

    sealed class Kind {
        /** A glyph with a word for it. */
        data class Symbol(val display: String, val spoken: String) : Kind()
        /** A named function: displayed upright, spoken as a word. */
        data class Function(val display: String, val spoken: String) : Kind()
        /** A run of dots. */
        data class Dots(val display: String) : Kind()
    }

    class Definition(val kind: Kind)

    val commands: Map<String, Definition> = mapOf(
        // Greek — lowercase.
        "alpha" to Definition(Kind.Symbol("α", "alpha")),
        "beta" to Definition(Kind.Symbol("β", "beta")),
        "gamma" to Definition(Kind.Symbol("γ", "gamma")),
        "delta" to Definition(Kind.Symbol("δ", "delta")),
        "epsilon" to Definition(Kind.Symbol("ϵ", "epsilon")),
        "varepsilon" to Definition(Kind.Symbol("ε", "epsilon")),
        "zeta" to Definition(Kind.Symbol("ζ", "zeta")),
        "eta" to Definition(Kind.Symbol("η", "eta")),
        "theta" to Definition(Kind.Symbol("θ", "theta")),
        "vartheta" to Definition(Kind.Symbol("ϑ", "theta")),
        "iota" to Definition(Kind.Symbol("ι", "iota")),
        "kappa" to Definition(Kind.Symbol("κ", "kappa")),
        "lambda" to Definition(Kind.Symbol("λ", "lambda")),
        "mu" to Definition(Kind.Symbol("μ", "mu")),
        "nu" to Definition(Kind.Symbol("ν", "nu")),
        "xi" to Definition(Kind.Symbol("ξ", "xi")),
        "omicron" to Definition(Kind.Symbol("ο", "omicron")),
        "pi" to Definition(Kind.Symbol("π", "pi")),
        "rho" to Definition(Kind.Symbol("ρ", "rho")),
        "varrho" to Definition(Kind.Symbol("ϱ", "rho")),
        "sigma" to Definition(Kind.Symbol("σ", "sigma")),
        "varsigma" to Definition(Kind.Symbol("ς", "sigma")),
        "tau" to Definition(Kind.Symbol("τ", "tau")),
        "upsilon" to Definition(Kind.Symbol("υ", "upsilon")),
        "phi" to Definition(Kind.Symbol("φ", "phi")),
        "varphi" to Definition(Kind.Symbol("ϕ", "phi")),
        "chi" to Definition(Kind.Symbol("χ", "chi")),
        "psi" to Definition(Kind.Symbol("ψ", "psi")),
        "omega" to Definition(Kind.Symbol("ω", "omega")),

        // Greek — uppercase.
        "Gamma" to Definition(Kind.Symbol("Γ", "Gamma")),
        "Delta" to Definition(Kind.Symbol("Δ", "Delta")),
        "Theta" to Definition(Kind.Symbol("Θ", "Theta")),
        "Lambda" to Definition(Kind.Symbol("Λ", "Lambda")),
        "Xi" to Definition(Kind.Symbol("Ξ", "Xi")),
        "Pi" to Definition(Kind.Symbol("Π", "Pi")),
        "Sigma" to Definition(Kind.Symbol("Σ", "Sigma")),
        "Upsilon" to Definition(Kind.Symbol("Υ", "Upsilon")),
        "Phi" to Definition(Kind.Symbol("Φ", "Phi")),
        "Psi" to Definition(Kind.Symbol("Ψ", "Psi")),
        "Omega" to Definition(Kind.Symbol("Ω", "Omega")),

        // Binary operators.
        "times" to Definition(Kind.Symbol("×", "times")),
        "cdot" to Definition(Kind.Symbol("·", "times")),
        "div" to Definition(Kind.Symbol("÷", "divided by")),
        "pm" to Definition(Kind.Symbol("±", "plus or minus")),
        "mp" to Definition(Kind.Symbol("∓", "minus or plus")),
        "ast" to Definition(Kind.Symbol("∗", "times")),
        "star" to Definition(Kind.Symbol("⋆", "star")),
        "circ" to Definition(Kind.Symbol("∘", "composed with")),
        "bullet" to Definition(Kind.Symbol("•", "times")),
        "oplus" to Definition(Kind.Symbol("⊕", "plus")),
        "otimes" to Definition(Kind.Symbol("⊗", "times")),
        "odot" to Definition(Kind.Symbol("⊙", "dot")),
        "dagger" to Definition(Kind.Symbol("†", "dagger")),
        "ddagger" to Definition(Kind.Symbol("‡", "double dagger")),

        // Relations.
        "leq" to Definition(Kind.Symbol("≤", "less than or equal to")),
        "le" to Definition(Kind.Symbol("≤", "less than or equal to")),
        "geq" to Definition(Kind.Symbol("≥", "greater than or equal to")),
        "ge" to Definition(Kind.Symbol("≥", "greater than or equal to")),
        "neq" to Definition(Kind.Symbol("≠", "not equal to")),
        "ne" to Definition(Kind.Symbol("≠", "not equal to")),
        "approx" to Definition(Kind.Symbol("≈", "approximately equal to")),
        "equiv" to Definition(Kind.Symbol("≡", "is equivalent to")),
        "sim" to Definition(Kind.Symbol("∼", "is similar to")),
        "simeq" to Definition(Kind.Symbol("≃", "is asymptotically equal to")),
        "propto" to Definition(Kind.Symbol("∝", "is proportional to")),
        "subset" to Definition(Kind.Symbol("⊂", "is a subset of")),
        "subseteq" to Definition(Kind.Symbol("⊆", "is a subset of or equal to")),
        "supset" to Definition(Kind.Symbol("⊃", "is a superset of")),
        "supseteq" to Definition(Kind.Symbol("⊇", "is a superset of or equal to")),
        "in" to Definition(Kind.Symbol("∈", "is an element of")),
        "notin" to Definition(Kind.Symbol("∉", "is not an element of")),
        "ni" to Definition(Kind.Symbol("∋", "is an element of")),
        "perp" to Definition(Kind.Symbol("⊥", "is perpendicular to")),
        "parallel" to Definition(Kind.Symbol("∥", "is parallel to")),
        "mid" to Definition(Kind.Symbol("∣", "divides")),
        "doteq" to Definition(Kind.Symbol("≐", "is defined as")),

        // Arrows.
        "to" to Definition(Kind.Symbol("→", "right arrow")),
        "rightarrow" to Definition(Kind.Symbol("→", "right arrow")),
        "gets" to Definition(Kind.Symbol("←", "left arrow")),
        "leftarrow" to Definition(Kind.Symbol("←", "left arrow")),
        "leftrightarrow" to Definition(Kind.Symbol("↔", "left right arrow")),
        "Leftrightarrow" to Definition(Kind.Symbol("⇔", "if and only if")),
        "Rightarrow" to Definition(Kind.Symbol("⇒", "implies")),
        "implies" to Definition(Kind.Symbol("⟹", "implies")),
        "mapsto" to Definition(Kind.Symbol("↦", "maps to")),

        // Big operators.
        "sum" to Definition(Kind.Symbol("∑", "sum")),
        "prod" to Definition(Kind.Symbol("∏", "product")),
        "int" to Definition(Kind.Symbol("∫", "integral")),
        "oint" to Definition(Kind.Symbol("∮", "contour integral")),
        "iint" to Definition(Kind.Symbol("∬", "double integral")),
        "iiint" to Definition(Kind.Symbol("∭", "triple integral")),

        // Named functions.
        "sin" to Definition(Kind.Function("sin", "sine")),
        "cos" to Definition(Kind.Function("cos", "cosine")),
        "tan" to Definition(Kind.Function("tan", "tangent")),
        "cot" to Definition(Kind.Function("cot", "cotangent")),
        "sec" to Definition(Kind.Function("sec", "secant")),
        "csc" to Definition(Kind.Function("csc", "cosecant")),
        "arcsin" to Definition(Kind.Function("arcsin", "arc sine")),
        "arccos" to Definition(Kind.Function("arccos", "arc cosine")),
        "arctan" to Definition(Kind.Function("arctan", "arc tangent")),
        "sinh" to Definition(Kind.Function("sinh", "hyperbolic sine")),
        "cosh" to Definition(Kind.Function("cosh", "hyperbolic cosine")),
        "tanh" to Definition(Kind.Function("tanh", "hyperbolic tangent")),
        "log" to Definition(Kind.Function("log", "log")),
        "ln" to Definition(Kind.Function("ln", "natural log")),
        "lg" to Definition(Kind.Function("lg", "log base ten")),
        "exp" to Definition(Kind.Function("exp", "e to the x")),
        "lim" to Definition(Kind.Function("lim", "limit")),
        "max" to Definition(Kind.Function("max", "maximum")),
        "min" to Definition(Kind.Function("min", "minimum")),
        "det" to Definition(Kind.Function("det", "determinant")),
        "dim" to Definition(Kind.Function("dim", "dimension")),
        "ker" to Definition(Kind.Function("ker", "kernel")),
        "deg" to Definition(Kind.Function("deg", "degrees")),
        "gcd" to Definition(Kind.Function("gcd", "greatest common divisor")),
        "hom" to Definition(Kind.Function("hom", "homomorphism")),
        "arg" to Definition(Kind.Function("arg", "argument")),
        "Pr" to Definition(Kind.Function("Pr", "probability")),

        // Constants and misc symbols.
        "infty" to Definition(Kind.Symbol("∞", "infinity")),
        "infin" to Definition(Kind.Symbol("∞", "infinity")),
        "partial" to Definition(Kind.Symbol("∂", "partial")),
        "nabla" to Definition(Kind.Symbol("∇", "nabla")),
        "aleph" to Definition(Kind.Symbol("ℵ", "aleph")),
        "hbar" to Definition(Kind.Symbol("ℏ", "h bar")),
        "ell" to Definition(Kind.Symbol("ℓ", "ell")),
        "imath" to Definition(Kind.Symbol("ℹ", "imaginary part")),
        "jmath" to Definition(Kind.Symbol("ℼ", "imaginary part")),
        "Re" to Definition(Kind.Symbol("ℜ", "real part")),
        "Im" to Definition(Kind.Symbol("ℑ", "imaginary part")),
        "wp" to Definition(Kind.Symbol("℘", "Weierstrass p")),
        "angle" to Definition(Kind.Symbol("∠", "angle")),
        "degree" to Definition(Kind.Symbol("°", "degrees")),
        "textdegree" to Definition(Kind.Symbol("°", "degrees")),
        "triangle" to Definition(Kind.Symbol("△", "triangle")),
        "square" to Definition(Kind.Symbol("□", "square")),
        "neg" to Definition(Kind.Symbol("¬", "not")),
        "lnot" to Definition(Kind.Symbol("¬", "not")),
        "emptyset" to Definition(Kind.Symbol("∅", "the empty set")),
        "varnothing" to Definition(Kind.Symbol("∅", "the empty set")),
        "forall" to Definition(Kind.Symbol("∀", "for all")),
        "exists" to Definition(Kind.Symbol("∃", "there exists")),
        "prime" to Definition(Kind.Symbol("′", "prime")),
        "top" to Definition(Kind.Symbol("⊤", "top")),
        "bot" to Definition(Kind.Symbol("⊥", "bottom")),

        // Dots.
        "ldots" to Definition(Kind.Dots("…")),
        "cdots" to Definition(Kind.Dots("⋯")),
        "dots" to Definition(Kind.Dots("…")),
        "vdots" to Definition(Kind.Dots("⋮")),
        "ddots" to Definition(Kind.Dots("⋱")),
    )

    /**
     * Commands recognised WITHOUT `$…$` delimiters: GREEK LETTERS and
     * SYMBOLS only. A bare `\beta` in a note is maths the user expects to
     * see rendered, but a bare command is indistinguishable from a WINDOWS
     * PATH (`C:\Users\…`), so the function names (`\sin`, `\log`, `\max`…)
     * are excluded — a note that says `C:\Users\max` must not have its path
     * silently rewritten into "C:Usersmaximum". Anything with an argument
     * (`\frac`, `\sqrt`, `\text`) needs the delimiters anyway.
     */
    val bareAllowed: Set<String> = commands.keys - setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "log", "ln", "lg", "exp", "lim", "max", "min",
        "det", "dim", "ker", "deg", "gcd", "hom", "arg", "Pr",
    )
}
