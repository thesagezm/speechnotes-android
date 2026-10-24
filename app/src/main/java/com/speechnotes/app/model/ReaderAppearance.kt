package com.speechnotes.app.model

/**
 * The EPUB reader's appearance state, persisted under the `bookReader*`
 * preference family. Ported from ReaderAppearance.swift (speechnotes-ios) —
 * one struct, not loose parameters: the shell URL query and the live JS
 * bridge both need the FULL current state on every reload/appearance change.
 *
 * The JSON field names double as the keys reader.js's
 * `readerAppearance({...})` command consumes — don't rename casually.
 */
data class ReaderAppearance(
    /** light | sepia | dark | trueBlack */
    val theme: String = "light",
    /** Percent, 70...200. */
    val fontSize: Int = 100,
    /** scrolled | paginated */
    val flow: String = "scrolled",
    /** book | serif | sans | mono ("book" = leave the publisher's font alone) */
    val font: String = "book",
    /** 1.2...2.5 */
    val lineHeight: Double = 1.6,
    /** em added below paragraphs, 0...2 */
    val paraSpacing: Double = 0.0,
    /** px, 0...3 */
    val letterSpacing: Double = 0.0,
    /** px padding around the page, 0...48. Scrolled flow only — epub.js
     *  computes paginated column widths from the container, and padding
     *  there breaks the column math. */
    val margin: Int = 16,
    /** When true only theme colors are applied; the book's own CSS keeps
     *  typography control (Anx's "use book styles" switch). */
    val respectStyles: Boolean = false,
    val autoScroll: Boolean = false,
    /** px/s, 10...200 */
    val autoScrollSpeed: Double = 40.0,
    /** Tap zones and swipe only act in paginated flow — scrolled flow is
     *  native scrolling. Both default ON. */
    val tapTurn: Boolean = true,
    val swipeTurn: Boolean = true,
    /** Swap left/right tap zones (RTL readers). */
    val tapInverted: Boolean = false,
) {
    val flowIsPaginated: Boolean get() = flow == "paginated"

    /**
     * Query items for the shell URL. The initial state rides the URL because
     * applying it after the fact races the rendition's creation (the same
     * reason theme/fontSize have always ridden the URL).
     */
    fun queryItems(): Map<String, String> = mapOf(
        "theme" to theme,
        "fontSize" to fontSize.toString(),
        "flow" to flow,
        "font" to font,
        "lineHeight" to lineHeight.toString(),
        "paraSpacing" to paraSpacing.toString(),
        "letterSpacing" to letterSpacing.toString(),
        "padding" to margin.toString(),
        "respectStyles" to if (respectStyles) "1" else "0",
        "tapTurn" to if (tapTurn) "1" else "0",
        "swipeTurn" to if (swipeTurn) "1" else "0",
        "tapInv" to if (tapInverted) "1" else "0",
    )

    /**
     * The live-apply JS command: `readerAppearance({...})`. Built by hand
     * rather than with org.json: the JVM unit tests would hit the stubbed
     * android.jar JSONObject ("not mocked"), and the string is trivially
     * valid because every value here is a number or a known enum string.
     */
    fun jsCommand(): String {
        val body = buildString {
            append("{")
            append("\"theme\":\"").append(theme).append("\"")
            append(",\"fontSize\":").append(fontSize)
            append(",\"flow\":\"").append(flow).append("\"")
            append(",\"font\":\"").append(font).append("\"")
            append(",\"lineHeight\":").append(lineHeight)
            append(",\"paraSpacing\":").append(paraSpacing)
            append(",\"letterSpacing\":").append(letterSpacing)
            append(",\"margin\":").append(margin)
            append(",\"respectStyles\":").append(respectStyles)
            append(",\"autoScroll\":").append(autoScroll)
            append(",\"autoScrollSpeed\":").append(autoScrollSpeed)
            append(",\"tapTurn\":").append(tapTurn)
            append(",\"swipeTurn\":").append(swipeTurn)
            append(",\"tapInverted\":").append(tapInverted)
            append("}")
        }
        return "window.readerAppearance && window.readerAppearance($body);"
    }

    /** Pushes the auto-scroll state (its own command, like iOS). */
    fun autoScrollCommand(): String =
        "window.readerAutoScroll && window.readerAutoScroll($autoScroll, $autoScrollSpeed);"

    fun autoScrollSpeedCommand(): String =
        "window.readerAutoScrollSpeed && window.readerAutoScrollSpeed($autoScrollSpeed);"
}
