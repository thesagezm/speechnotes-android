package com.speechnotes.app.ui.readalong

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.speech.SpeechState


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
    // Auto-scroll: keep the sounding paragraph near the top.
    LaunchedEffect(range?.first) {
        val r = range ?: return@LaunchedEffect
        val target = paragraphs.indexOfFirst { (start, line) ->
            r.first >= start && r.first < start + line.length + 1
        }
        if (target > 0) {
            // Approximate paragraph height (text + spacing) — good enough to
            // keep the highlight in view for a reader.
            val approxLine = 28
            scrollState.animateScrollTo((target * approxLine * 2).coerceIn(0, scrollState.maxValue))
        }
    }
    Column(Modifier.fillMaxSize()) {
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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy((12 * themeState.readerBlockSpacing).dp),
        ) {
            val scale = themeState.previewTextScale
            for ((start, line) in paragraphs) {
                val annotated = annotated(line, start, range)
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = (17 * scale).sp,
                    lineHeight = (26 * scale * themeState.readerLineSpacing.toFloat()).sp,
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
