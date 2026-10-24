package com.speechnotes.app.logic

/**
 * Block-aware page layout for the note→PDF share export — the pure, testable
 * half (measuring, word wrap, page splitting); the NoteEditorScreen adapter
 * turns the emitted ops into android.graphics calls.
 *
 * The old export flattened markdown to plain lines and drew them one at a
 * time, losing every heading, list and quote. iOS's own export flattens too
 * (UIMarkupTextPrintFormatter over MarkdownText.plainText), so this is a
 * deliberate improvement over both, fed by MarkdownText.blocks — the same
 * parser the preview uses.
 *
 * Geometry is US Letter at PDF points (612×792) with 48pt margins, matching
 * the iOS export.
 */
object NotePdfLayout {

    // Geometry (PDF points).
    const val PAGE_WIDTH = 612f
    const val PAGE_HEIGHT = 792f
    const val MARGIN = 48f

    /** What to draw, in order. Coordinates are absolute page points. */
    sealed class Op {
        /** Baseline-positioned text run. */
        data class Text(val text: String, val x: Float, val baselineY: Float, val style: Style) : Op()

        /** Horizontal rule at [y] between [x1] and [x2]. */
        data class Rule(val x1: Float, val x2: Float, val y: Float) : Op()

        /** New page — the adapter closes the page and starts the next. */
        data object PageBreak : Op()
    }

    /** Font selection + size the adapter maps onto android.graphics.Paint. */
    data class Style(
        val size: Float,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val monospace: Boolean = false,
    ) {
        companion object {
            val TITLE = Style(22f, bold = true)
            val H1 = Style(20f, bold = true)
            val H2 = Style(17f, bold = true)
            val H3 = Style(15f, bold = true)
            val H4 = Style(14f, bold = true)
            val BODY = Style(12f)
            val BODY_ITALIC = Style(12f, italic = true)
            val CODE = Style(11f, monospace = true)
        }
    }

    /**
     * Text measurement: characters-per-line at a given style. The adapter
     * replaces this with real Paint.measureText; the default is a coarse
     * average-width model so the pure tests exercise real wrapping behavior.
     */
    fun interface Measurer {
        fun width(text: String, style: Style): Float
    }

    /** Average glyph width ≈ 0.52 × point size (close for the default sans). */
    val defaultMeasurer: Measurer = Measurer { text, style -> text.length * style.size * 0.52f }

    /**
     * Lays the note out into ops. [title] renders once as the document
     * heading; [blocks] are MarkdownText.blocks of the body.
     */
    fun layout(
        title: String,
        blocks: List<MarkdownText.MarkdownBlock>,
        measurer: Measurer = defaultMeasurer,
    ): List<Op> {
        val ops = ArrayList<Op>()
        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        var y = MARGIN + Style.TITLE.size // first baseline
        var pageStarted = false

        fun newPage() {
            ops.add(Op.PageBreak)
            y = MARGIN + 16f + Style.BODY.size // continuation top margin
            pageStarted = true
        }

        fun ensureRoom(height: Float) {
            if (y + height > PAGE_HEIGHT - MARGIN) newPage()
        }

        /** Word-wraps [text] at [style], drawing each line at the given indent. */
        fun drawWrapped(text: String, style: Style, indent: Float = 0f, lineSpacing: Float = 1.25f) {
            val width = contentWidth - indent
            val lines = wrap(text, style, width, measurer)
            for ((index, line) in lines.withIndex()) {
                ensureRoom(style.size * lineSpacing)
                if (y + style.size > PAGE_HEIGHT - MARGIN) newPage()
                ops.add(Op.Text(line, MARGIN + indent, y, style))
                y += style.size * lineSpacing
                // Subsequent pages keep the wrapped paragraph going.
                if (index == lines.lastIndex) pageStarted = false
            }
        }

        // Document title.
        drawWrapped(title, Style.TITLE, lineSpacing = 1.3f)
        y += 6f

        for (block in blocks) {
            when (block) {
                is MarkdownText.MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> Style.H1
                        2 -> Style.H2
                        3 -> Style.H3
                        else -> Style.H4
                    }
                    y += style.size * 0.6f // space above headings
                    drawWrapped(block.text, style)
                    y += 4f
                }
                is MarkdownText.MarkdownBlock.Paragraph ->
                    drawWrapped(block.text, Style.BODY)
                is MarkdownText.MarkdownBlock.Quote -> {
                    y += 2f
                    drawWrapped(block.text, Style.BODY_ITALIC, indent = 18f)
                    y += 2f
                }
                is MarkdownText.MarkdownBlock.BulletList -> {
                    for (item in block.items) {
                        val indent = 14f + item.level * 16f
                        val marker = if (item.isTask) {
                            if (item.isDone) "☑" else "☐"
                        } else "•"
                        ensureRoom(Style.BODY.size * 1.25f)
                        ops.add(Op.Text(marker, MARGIN + indent - 12f, y, Style.BODY))
                        drawWrapped(item.text, Style.BODY, indent = indent)
                    }
                    y += 2f
                }
                is MarkdownText.MarkdownBlock.OrderedList -> {
                    var counter = 1
                    var lastLevel = -1
                    for (item in block.items) {
                        // Number sequentially within a run of the same level;
                        // a level change (nested in or out) restarts at 1.
                        if (item.level != lastLevel) counter = 1
                        lastLevel = item.level
                        val indent = 14f + item.level * 16f
                        ensureRoom(Style.BODY.size * 1.25f)
                        ops.add(Op.Text("$counter.", MARGIN + indent - 12f, y, Style.BODY))
                        counter += 1
                        drawWrapped(item.text, Style.BODY, indent = indent)
                    }
                    y += 2f
                }
                is MarkdownText.MarkdownBlock.Code -> {
                    y += 2f
                    for (line in block.text.split('\n')) {
                        drawWrapped(line, Style.CODE, indent = 12f, lineSpacing = 1.2f)
                    }
                    y += 4f
                }
                is MarkdownText.MarkdownBlock.Divider -> {
                    ensureRoom(12f)
                    ops.add(Op.Rule(MARGIN, PAGE_WIDTH - MARGIN, y - Style.BODY.size * 0.4f))
                    y += 12f
                }
                is MarkdownText.MarkdownBlock.Image -> {
                    // A PDF export of a note can't inline remote/local images
                    // without decoding them here; the alt text carries the
                    // meaning (same choice iOS's plain-text pipeline makes).
                    drawWrapped("[image: ${block.alt.ifBlank { "unlabeled" }}]", Style.BODY_ITALIC)
                }
                is MarkdownText.MarkdownBlock.Table -> {
                    y = drawTable(block, y, ::ensureRoom, ops) { newY -> y = newY }
                }
            }
        }
        return ops
    }

    /** Returns the y below the last row. */
    private fun drawTable(
        block: MarkdownText.MarkdownBlock.Table,
        startY: Float,
        ensureRoom: (Float) -> Unit,
        ops: MutableList<Op>,
        setY: (Float) -> Unit,
    ): Float {
        var y = startY
        val columnCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
        if (columnCount == 0) return y
        val columnWidth = (PAGE_WIDTH - 2 * MARGIN) / columnCount
        val cellStyle = Style(10f)

        fun drawRow(cells: List<String>, bold: Boolean) {
            val style = if (bold) Style(10f, bold = true) else cellStyle
            ensureRoom(style.size * 1.2f)
            var rowBottom = y
            for ((index, cell) in cells.withIndex()) {
                if (index >= columnCount) break
                val lines = wrap(cell, style, columnWidth - 8f, defaultMeasurer)
                var cellY = y
                for (line in lines) {
                    ensureRoom(style.size * 1.2f)
                    ops.add(Op.Text(line, MARGIN + index * columnWidth + 4f, cellY, style))
                    cellY += style.size * 1.2f
                }
                rowBottom = maxOf(rowBottom, cellY)
            }
            y = rowBottom
            setY(y)
        }

        if (block.headers.isNotEmpty()) drawRow(block.headers, bold = true)
        for (row in block.rows) drawRow(row, bold = false)
        return y
    }

    /** Greedy word wrap on spaces; a single unbreakable token overflows rather than splitting. */
    internal fun wrap(text: String, style: Style, maxWidth: Float, measurer: Measurer): List<String> {
        if (text.isEmpty()) return listOf("")
        val words = text.split(' ')
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measurer.width(candidate, style) <= maxWidth || current.isEmpty()) {
                current.clear()
                current.append(candidate)
            } else {
                lines.add(current.toString())
                current.clear()
                current.append(word)
            }
        }
        lines.add(current.toString())
        return lines
    }
}
