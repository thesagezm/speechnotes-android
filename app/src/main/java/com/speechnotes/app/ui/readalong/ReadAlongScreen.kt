package com.speechnotes.app.ui.readalong

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.speech.SpeechState
import com.speechnotes.app.ui.theme.Haptics


/**
 * Dedicated read-along surface: renders the EXACT text the engine is
 * speaking with the currently-sounding sentence highlighted, and
 * auto-scrolls to it. Ported from ReadAlongView.swift (speechnotes-ios):
 * paragraph-per-`\n`-block rendering so a global UTF-16 highlight range can
 * be projected into each paragraph.
 */
@Composable
fun ReadAlongScreen(
    vm: SpeechnotesViewModel,
    text: String,
    range: IntRange?,
    onExit: () -> Unit,
) {
    val paragraphs = remember(text) {
        var start = 0
        text.split("\n").map { line ->
            val p = start to (start + line.length)
            start += line.length + 1
            p.first to line
        }
    }
    val scrollState = rememberScrollState()
    val state by vm.player.state.collectAsState()
    // Measured center-anchor auto-scroll (Unit RAL-1a, iOS ReadAlongView's
    // proxy.scrollTo(id, anchor: .center) parity). The old estimate —
    // paragraph index × a fixed 56 dp — undershot tall paragraphs, so the
    // sounding sentence landed below the viewport ("far at the bottom").
    // Each row reports its content-space geometry; on every sentence change
    // the containing paragraph is animated to the viewport CENTER.
    val geometry = remember(text) { mutableMapOf<Int, Pair<Int, Int>>() } // start → (top, height)
    var viewportHeight by remember { mutableStateOf(0) }
    LaunchedEffect(range?.first) {
        val r = range ?: return@LaunchedEffect
        // The sentence starts INSIDE a paragraph — find the containing one
        // (sentences are not paragraph boundaries).
        val containing = paragraphs.lastOrNull { (start, line) ->
            r.first >= start && r.first < start + line.length + 1
        } ?: return@LaunchedEffect
        if (containing.first == paragraphs.firstOrNull()?.first) {
            return@LaunchedEffect // iOS skips the first paragraph too
        }
        val (top, height) = geometry[containing.first] ?: return@LaunchedEffect
        if (viewportHeight <= 0) return@LaunchedEffect
        val target = (top + height / 2 - viewportHeight / 2).coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(target)
    }
    // Opaque background (iOS ReadAlongView's .background(systemBackground/black)
    // parity): over a book reader the surface covers the page — without it the
    // book's own text showed through and collided with the read-along rows.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Read-along",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(0.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                if (state == SpeechState.SPEAKING) vm.player.pause() else vm.player.resume()
            }) {
                // The pause control drew a CLOSE glyph while speaking — the
                // icon and its contentDescription disagreed, and the X read
                // as "this quits read-along" when it only paused.
                Icon(
                    if (state == SpeechState.SPEAKING) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state == SpeechState.SPEAKING) "Pause" else "Resume",
                )
            }
            IconButton(onClick = { vm.player.stop() }) {
                Icon(Icons.Default.Stop, contentDescription = "Stop reading")
            }
            // Exit keeps the session: speech continues and the mini-player
            // takes over — stopping was the old behavior and made X feel
            // destructive.
            IconButton(onClick = onExit) {
                Icon(Icons.Default.Close, contentDescription = "Exit read-along")
            }
        }
        val themeState = vm.themeState.collectAsState().value
        Column(
            Modifier
                .weight(1f)
                // The viewport size must be read BEFORE verticalScroll: a
                // scroll node's content is measured with unbounded height, so
                // an onSizeChanged placed after it reports the FULL CONTENT
                // height (14,280 px on the repro) as the "viewport" — every
                // centering target then computed negative and coerced to 0,
                // and the follow never scrolled past the first screenful
                // (RAL-1 open item, device-proven 2026-11-18).
                .onSizeChanged { viewportHeight = it.height }
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy((12 * themeState.readerBlockSpacing).dp),
        ) {
            val scale = themeState.previewTextScale
            val context = LocalContext.current
            for ((start, line) in paragraphs) {
                val annotated = annotated(line, start, range)
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = (17 * scale).sp,
                    lineHeight = (26 * scale * themeState.readerLineSpacing.toFloat()).sp,
                    // Tap-a-paragraph-to-speak (iOS ReadAlongView): the tap
                    // restarts the CURRENT session's text sentence-snapped at
                    // this paragraph, keeping the note/book identity — the
                    // book's auto-advance chain stays armed.
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            geometry[start] =
                                coords.positionInParent().y.toInt() to coords.size.height
                        }
                        .clickable {
                            Haptics.tap(context)
                            vm.player.playFromCurrent(start)
                        },
                )
            }
        }
    }
}
/** Projects the global highlight range into one paragraph's local offsets. */
private fun annotated(line: String, start: Int, range: IntRange?): AnnotatedString {
    if (range == null) return AnnotatedString(line)
    val end = start + line.length
    val lower = maxOf(range.first, start) - start
    val upper = minOf(range.last + 1, end) - start
    if (lower >= upper || lower < 0) return AnnotatedString(line)
    return buildAnnotatedString {
        append(line.substring(0, lower.coerceIn(0, line.length)))
        withStyle(SpanStyle(background = Highlight, fontWeight = FontWeight.Bold)) {
            append(line.substring(lower.coerceIn(0, line.length), upper.coerceIn(0, line.length)))
        }
        if (upper < line.length) append(line.substring(upper.coerceIn(0, line.length)))
    }
}
private val Highlight = Color(0x4D1ABCA1)
