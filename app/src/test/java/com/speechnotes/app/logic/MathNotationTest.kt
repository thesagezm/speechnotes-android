package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths contract, ported from the iOS MathNotationTests.swift: the
 * DISPLAY form (what the reader sees), the SPOKEN form (what the engine
 * says), and the integration — a formula inside a note reaches both the
 * preview and the speech path, and prices (`$5`) are NOT mistaken for maths.
 */
class MathNotationTest {

    // MARK: - Display form

    @Test
    fun greekLetterRendersAsGlyph() {
        assertEquals("β", MathNotation.formula("\\beta", isDisplay = false)?.display)
    }

    @Test
    fun uppercaseGreekKeepsItsCase() {
        assertEquals("Ω", MathNotation.formula("\\Omega", isDisplay = false)?.display)
        assertEquals("ω", MathNotation.formula("\\omega", isDisplay = false)?.display)
    }

    @Test
    fun operatorsRenderAsSymbols() {
        assertEquals("×", MathNotation.formula("\\times", isDisplay = false)?.display)
        assertEquals("≤", MathNotation.formula("\\leq", isDisplay = false)?.display)
        assertEquals("≠", MathNotation.formula("\\neq", isDisplay = false)?.display)
        assertEquals("∞", MathNotation.formula("\\infty", isDisplay = false)?.display)
    }

    @Test
    fun fractionRendersAsSlashInlineAndParensWhenDisplayed() {
        assertEquals("a/b", MathNotation.formula("\\frac{a}{b}", isDisplay = false)?.display)
        assertEquals("(a)/(b)", MathNotation.formula("\\frac{a}{b}", isDisplay = true)?.display)
    }

    @Test
    fun squareRootRenders() {
        assertEquals("√(x² + y²)", MathNotation.formula("\\sqrt{x^2 + y^2}", isDisplay = false)?.display)
    }

    @Test
    fun superscriptUsesUnicodeGlyphs() {
        assertEquals("x²", MathNotation.formula("x^2", isDisplay = false)?.display)
        assertEquals("E = mc²", MathNotation.formula("E = mc^2", isDisplay = false)?.display)
    }

    @Test
    fun subscriptUsesUnicodeGlyphs() {
        assertEquals("x₁", MathNotation.formula("x_1", isDisplay = false)?.display)
        assertEquals("aₙ", MathNotation.formula("a_n", isDisplay = false)?.display)
    }

    @Test
    fun sumWithLimits() {
        // The limits stay in plain text: a row of superscript glyphs nobody
        // can parse is worse than `∑_i=1^n`.
        assertEquals("∑_i=1^n xᵢ", MathNotation.formula("\\sum_{i=1}^{n} x_i", isDisplay = true)?.display)
    }

    @Test
    fun integralWithLimits() {
        val formula = MathNotation.formula("\\int_0^1 f(x) dx", isDisplay = true)
        assertNotNull(formula)
        assertTrue(formula!!.display.contains("∫"))
    }

    @Test
    fun unknownCommandStaysLiteral() {
        // A note mentioning a package name must not lose the text: `formula`
        // REFUSES the unrecognised source so the caller keeps the original.
        assertNull(MathNotation.formula("\\emph", isDisplay = false))
    }

    // MARK: - Spoken form

    @Test
    fun greekSpokenAsAWord() {
        assertEquals("beta", MathNotation.formula("\\beta", isDisplay = false)?.spoken)
        assertEquals("alpha", MathNotation.formula("\\alpha", isDisplay = false)?.spoken)
    }

    @Test
    fun operatorsSpokenAsWords() {
        assertEquals("times", MathNotation.formula("\\times", isDisplay = false)?.spoken)
        assertEquals("less than or equal to", MathNotation.formula("\\leq", isDisplay = false)?.spoken)
        assertEquals("not equal to", MathNotation.formula("\\neq", isDisplay = false)?.spoken)
        assertEquals("infinity", MathNotation.formula("\\infty", isDisplay = false)?.spoken)
    }

    @Test
    fun fractionSpokenAsDividedBy() {
        assertEquals("a divided by b", MathNotation.formula("\\frac{a}{b}", isDisplay = false)?.spoken)
    }

    @Test
    fun squareRootSpokenAsWords() {
        assertEquals("the square root of 9", MathNotation.formula("\\sqrt{9}", isDisplay = false)?.spoken)
    }

    @Test
    fun superscriptSpokenAsToThePowerOf() {
        assertEquals("x to the power of 2", MathNotation.formula("x^2", isDisplay = false)?.spoken)
    }

    @Test
    fun subscriptSpokenAsSub() {
        assertEquals("x sub 1", MathNotation.formula("x_1", isDisplay = false)?.spoken)
    }

    @Test
    fun sumSpokenWithItsLimits() {
        assertEquals(
            "sum from i equals 1 to n x sub i",
            MathNotation.formula("\\sum_{i=1}^{n} x_i", isDisplay = true)?.spoken,
        )
    }

    @Test
    fun functionSpokenAsItsName() {
        assertEquals("sine", MathNotation.formula("\\sin", isDisplay = false)?.spoken)
        assertEquals("cosine", MathNotation.formula("\\cos", isDisplay = false)?.spoken)
    }

    /** The engine must NEVER receive a raw maths glyph. */
    @Test
    fun spokenFormCarriesNoMathsGlyphs() {
        val sources = listOf(
            "\\beta", "\\sum_{i=1}^{n} x_i", "\\frac{a}{b}", "\\sqrt{x}",
            "x^2", "\\leq", "\\infty", "\\times", "\\int_0^1 x dx",
        )
        val glyphs = charArrayOf('β', 'α', 'γ', 'δ', '∑', '∏', '∫', '√', '≤', '≥', '≠', '∞', '×', '÷', 'π', 'λ')
        for (source in sources) {
            val formula = MathNotation.formula(source, isDisplay = true)
            assertNotNull("no formula for $source", formula)
            for (character in formula!!.spoken) {
                assertFalse("${source} speaks the glyph $character", glyphs.contains(character))
            }
        }
    }

    // MARK: - Segments in a line

    @Test
    fun inlineFormulaSplitsTheLine() {
        val line = MathNotation.segments("the rate is \$r\$ today")
        assertEquals(3, line.parts.size)
        assertEquals("the rate is ", (line.parts[0] as MathNotation.SegmentLine.Part.Text).text)
        val math = (line.parts[1] as MathNotation.SegmentLine.Part.Math).segment
        assertEquals("r", math.display)
        assertEquals("r", math.spoken)
        assertEquals(" today", (line.parts[2] as MathNotation.SegmentLine.Part.Text).text)
    }

    @Test
    fun bareCommandIsRecognised() {
        val line = MathNotation.segments("the value \\beta rises")
        val maths = line.parts.mapNotNull { (it as? MathNotation.SegmentLine.Part.Math)?.segment }
        assertEquals(1, maths.size)
        assertEquals("β", maths.first().display)
        assertTrue(maths.first().isBare)
    }

    @Test
    fun bareFunctionNameIsNotMaths() {
        // `C:\Users\max` is a PATH, not a formula.
        val line = MathNotation.segments("C:\\Users\\max\\file")
        val maths = line.parts.mapNotNull { (it as? MathNotation.SegmentLine.Part.Math)?.segment }
        assertEquals(0, maths.size)
    }

    @Test
    fun dollarAmountIsNotMaths() {
        // Prices must keep their dollar signs.
        for (text in listOf("costs \$5", "it is \$100 and \$250", "\$5")) {
            val maths = MathNotation.segments(text)
                .parts.mapNotNull { (it as? MathNotation.SegmentLine.Part.Math)?.segment }
            assertEquals("$text was read as maths", 0, maths.size)
        }
    }

    @Test
    fun percentSignIsNotMaths() {
        for (text in listOf("50% of the time", "a 20% increase", "100%")) {
            val maths = MathNotation.segments(text)
                .parts.mapNotNull { (it as? MathNotation.SegmentLine.Part.Math)?.segment }
            assertEquals("$text was read as maths", 0, maths.size)
            assertEquals(text, MarkdownText.plainText(text))
        }
    }

    @Test
    fun unclosedDollarIsLiteralText() {
        val line = MathNotation.segments("the price is \$5 and rising")
        assertEquals(1, line.parts.size)
        assertEquals("the price is \$5 and rising", (line.parts[0] as MathNotation.SegmentLine.Part.Text).text)
    }

    @Test
    fun displayFormulaIsMarkedAsSuch() {
        val line = MathNotation.segments("\$\$x^2\$\$")
        val math = line.parts.first() as MathNotation.SegmentLine.Part.Math
        assertTrue(math.segment.isDisplay)
    }

    @Test
    fun unescapedDollarReturnsAnIndex() {
        // 626e505: an index, not a range — and `\$` closes nothing.
        assertEquals(2, MathNotation.unescapedDollar("a \$b\$ c", from = 0, before = 8))
        assertNull(MathNotation.unescapedDollar("no dollar", from = 0, before = 9))
        assertNull(MathNotation.unescapedDollar("trail\\", from = 0, before = 6))
    }

    // MARK: - Integration with the markdown pipeline

    @Test
    fun formulaInParagraphSpeaksAsWord() {
        assertEquals("the rate is beta today", MarkdownText.plainText("the rate is \$\\beta\$ today"))
    }

    @Test
    fun displayFormulaRendersInTheBlocks() {
        val blocks = MarkdownText.blocks("before\n\n\$\$E = mc^2\$\$\n\nafter")
        val paragraph = blocks.filterIsInstance<MarkdownText.MarkdownBlock.Paragraph>()
            .firstOrNull { it.text.contains("mc") }
        assertNotNull(paragraph)
        // The reader sees the glyph form, never the LaTeX source.
        assertEquals("E = mc²", paragraph!!.text)
    }

    @Test
    fun speechNeverContainsTheDollarMarkers() {
        val spoken = MarkdownText.plainText("value \$x + y\$ here")
        assertFalse("the engine heard a dollar sign: $spoken", spoken.contains("$"))
        // The operators become WORDS.
        assertEquals("value x plus y here", spoken)
    }

    @Test
    fun rawDraftPathAlsoSpeaksMathsAsWords() {
        // The editing path speaks RAW text (the draft is not rendered yet).
        val spoken = MarkdownText.speechInline("value \$x + y\$ here")
        assertFalse("the engine heard a dollar sign: $spoken", spoken.contains("$"))
    }

    @Test
    fun speechTextExpandsFormulasInRawDrafts() {
        val spoken = SpeechText.forText("the rate is \$\\beta\$")
        assertFalse(spoken.contains("\\"))
        assertTrue(spoken.contains("beta"))
    }

    @Test
    fun formulaInsideAListItemSpeaksAsAWord() {
        assertEquals("the rate is beta", MarkdownText.plainText("- the rate is \$\\beta\$"))
    }

    @Test
    fun formulaInsideAQuoteSpeaksAsAWord() {
        assertEquals("the rate is beta", MarkdownText.plainText("> the rate is \$\\beta\$"))
    }

    @Test
    fun formulaInsideATableCellSpeaksAsAWord() {
        val markdown = "| term | value |\n| --- | --- |\n| rate | \$\\beta\$ |"
        assertEquals("term, value\nrate, beta", MarkdownText.plainText(markdown))
    }

    @Test
    fun plainNoteIsUnaffected() {
        val markdown = "Just a normal note with a \$ sign and 100% of my energy."
        assertEquals(markdown, MarkdownText.plainText(markdown))
    }

    @Test
    fun degreesRenderAndSpeak() {
        // 1eab53c: degrees render — `45^\circ` is a temperature, not composition.
        assertEquals("45°", MathNotation.formula("45^\\circ", isDisplay = false)?.display)
        assertEquals("45 degrees", MathNotation.formula("45^\\circ", isDisplay = false)?.spoken)
    }

    @Test
    fun textCommandStaysProse() {
        val formula = MathNotation.formula("\\text{if x > 5}", isDisplay = false)
        assertNotNull(formula)
        assertEquals("if x > 5", formula!!.display)
    }

    @Test
    fun latexSourceNeverLeaksIntoSpeech() {
        val spoken = MarkdownText.plainText("Euler: \$e^{i\\pi} + 1 = 0\$ indeed")
        assertFalse("LaTeX leaked: $spoken", spoken.contains("\\"))
        assertFalse("dollar leaked: $spoken", spoken.contains("$"))
    }
}
