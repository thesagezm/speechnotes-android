package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the note→PDF export: block → layout ops. Asserts heading
 * sizing, list indentation/markers, wrapping, and page splitting without
 * touching android.graphics.
 */
class NotePdfLayoutTest {

    private val m = NotePdfLayout.defaultMeasurer

    private fun opsOf(vararg blocks: MarkdownText.MarkdownBlock): List<NotePdfLayout.Op> =
        NotePdfLayout.layout("Title", blocks.toList())

    private fun texts(ops: List<NotePdfLayout.Op>): List<String> =
        ops.filterIsInstance<NotePdfLayout.Op.Text>().map { it.text }

    private fun pageBreaks(ops: List<NotePdfLayout.Op>): Int =
        ops.count { it is NotePdfLayout.Op.PageBreak }

    @Test
    fun headings_render_bigger_and_bolder_than_body() {
        val ops = opsOf(
            MarkdownText.MarkdownBlock.Heading(1, "One"),
            MarkdownText.MarkdownBlock.Paragraph("Body"),
            MarkdownText.MarkdownBlock.Heading(2, "Two"),
        )
        val runs = ops.filterIsInstance<NotePdfLayout.Op.Text>()
        val h1 = runs.first { it.text == "One" }
        val body = runs.first { it.text == "Body" }
        val h2 = runs.first { it.text == "Two" }
        assertTrue(h1.style.bold && h1.style.size > body.style.size)
        assertTrue(h2.style.bold && h2.style.size < h1.style.size && h2.style.size > body.style.size)
        // h3/h4 shrink further but stay bold.
        val ops2 = opsOf(MarkdownText.MarkdownBlock.Heading(4, "Four"))
        val h4 = ops2.filterIsInstance<NotePdfLayout.Op.Text>().first { it.text == "Four" }
        assertTrue(h4.style.bold && h4.style.size < h2.style.size)
    }

    @Test
    fun bullet_lists_indent_by_level_and_mark_items() {
        val ops = opsOf(
            MarkdownText.MarkdownBlock.BulletList(
                listOf(
                    MarkdownText.ListItem(level = 0, text = "Top"),
                    MarkdownText.ListItem(level = 1, text = "Nested"),
                    MarkdownText.ListItem(level = 2, text = "Deeper"),
                ),
            ),
        )
        val runs = ops.filterIsInstance<NotePdfLayout.Op.Text>()
        val top = runs.first { it.text == "Top" }
        val nested = runs.first { it.text == "Nested" }
        val deeper = runs.first { it.text == "Deeper" }
        assertTrue(nested.x > top.x)
        assertTrue(deeper.x > nested.x)
        // A bullet marker precedes each item.
        assertEquals(3, runs.count { it.text == "•" })
    }

    @Test
    fun ordered_lists_number_per_level() {
        val ops = opsOf(
            MarkdownText.MarkdownBlock.OrderedList(
                listOf(
                    MarkdownText.ListItem(level = 0, text = "First"),
                    MarkdownText.ListItem(level = 0, text = "Second"),
                    MarkdownText.ListItem(level = 0, text = "Third"),
                ),
            ),
        )
        val runs = ops.filterIsInstance<NotePdfLayout.Op.Text>()
        assertEquals(listOf("1.", "2.", "3."), runs.filter { it.text.matches(Regex("\\d+\\.")) }.map { it.text })
    }

    @Test
    fun long_paragraphs_wrap_within_margins() {
        val long = "word ".repeat(120).trim()
        val ops = opsOf(MarkdownText.MarkdownBlock.Paragraph(long))
        val runs = ops.filterIsInstance<NotePdfLayout.Op.Text>()
        // Many lines, every one inside the printable area.
        assertTrue(runs.size > 3)
        for (run in runs) {
            assertTrue(run.x >= NotePdfLayout.MARGIN - 0.5f)
            assertTrue(run.x + m.width(run.text, run.style) <= NotePdfLayout.PAGE_WIDTH - NotePdfLayout.MARGIN + 0.5f)
        }
    }

    @Test
    fun page_breaks_appear_when_content_exceeds_one_page() {
        val paragraphs = (1..40).map { MarkdownText.MarkdownBlock.Paragraph("Paragraph $it " + "filler ".repeat(20)) }
        val ops = opsOf(*paragraphs.toTypedArray())
        assertTrue(pageBreaks(ops) >= 1)
        // After a break, the first baseline resets near the top margin.
        val afterBreak = ops.dropWhile { it !is NotePdfLayout.Op.PageBreak }
            .filterIsInstance<NotePdfLayout.Op.Text>()
            .firstOrNull()
        if (afterBreak != null) {
            assertTrue(afterBreak.baselineY < NotePdfLayout.MARGIN + 60f)
        }
    }

    @Test
    fun quote_is_italic_and_indented_divider_draws_a_rule() {
        val ops = opsOf(
            MarkdownText.MarkdownBlock.Quote("Wise words"),
            MarkdownText.MarkdownBlock.Divider,
        )
        val quote = ops.filterIsInstance<NotePdfLayout.Op.Text>().first { it.text == "Wise words" }
        assertTrue(quote.style.italic)
        assertTrue(quote.x > NotePdfLayout.MARGIN)
        assertEquals(1, ops.count { it is NotePdfLayout.Op.Rule })
    }

    @Test
    fun code_blocks_use_monospace_and_tables_draw_cells() {
        val ops = opsOf(
            MarkdownText.MarkdownBlock.Code(language = null, text = "val x = 1"),
            MarkdownText.MarkdownBlock.Table(
                headers = listOf("A", "B"),
                rows = listOf(listOf("1", "2")),
            ),
        )
        val code = ops.filterIsInstance<NotePdfLayout.Op.Text>().first { it.text == "val x = 1" }
        assertTrue(code.style.monospace)
        val cellA = ops.filterIsInstance<NotePdfLayout.Op.Text>().first { it.text == "A" }
        val cell1 = ops.filterIsInstance<NotePdfLayout.Op.Text>().first { it.text == "1" }
        assertTrue(cellA.style.bold) // header row
        assertTrue(cell1.x > cellA.x || cell1.baselineY > cellA.baselineY)
    }

    @Test
    fun title_draws_first_at_top() {
        val ops = NotePdfLayout.layout("My Note", listOf(MarkdownText.MarkdownBlock.Paragraph("Body")))
        val first = ops.filterIsInstance<NotePdfLayout.Op.Text>().first()
        assertEquals("My Note", first.text)
        assertEquals(22f, first.style.size, 0.01f)
    }
}
