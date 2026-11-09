package com.speechnotes.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.speech.SpeechPlayer
import com.speechnotes.app.speech.SpeechState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The global mini-player: a bar pinned above the navigation bar while speech
 * is active, minimizable to a floating progress-ring bubble, with the
 * sleep-timer menu. Tapping the bar jumps to the speaking note or book.
 * Ported from MiniPlayerBar.swift + GlobalMiniPlayerOverlay.swift.
 *
 * Dock priority (GlobalMiniPlayerOverlay.activeBar): ONE bar owns the dock —
 * an active audiobook first (its whole point is surviving the reader), then
 * the speaking-note bar. Surfaces with their own transport set
 * [SpeechnotesViewModel.miniPlayerSuppressed] while on top so the two never
 * stack.
 */
@Composable
fun MiniPlayerOverlay(
    vm: SpeechnotesViewModel,
    onJumpToNote: () -> Unit,
    onJumpToBook: () -> Unit,
    onJumpToExports: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val exportTapJumper: (() -> Unit)? = if (onJumpToExports == {}) null else onJumpToExports
    val state by vm.player.state.collectAsState()
    val progress by vm.player.progress.collectAsState()
    val title by vm.player.nowPlayingTitle.collectAsState()
    val sleepTimer by vm.player.sleepTimer.collectAsState()
    val bookId by vm.player.nowPlayingBookId.collectAsState()
    val collapsed by vm.miniPlayerCollapsedFlow.collectAsState()
    val rate by vm.player.rateMultiplier.collectAsState()
    val suppressed by vm.miniPlayerSuppressedFlow.collectAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Audiobook (file playback) dock ownership.
    val audioBook by vm.audioBooks.activeBook.collectAsState()
    val audioPlaying by vm.audioBooks.isPlaying.collectAsState()
    val audioChapter by vm.audioBooks.chapterTitle.collectAsState()
    val audioElapsed by vm.audioBooks.elapsedMs.collectAsState()
    val audioDuration by vm.audioBooks.durationMs.collectAsState()

    val wavURL by vm.wavPlayer.playingURL.collectAsState()
    val wavPaused by vm.wavPlayer.isPaused.collectAsState()
    val wavProgress by vm.wavPlayer.progress.collectAsState()
    val wavTime by vm.wavPlayer.currentTime.collectAsState()
    val wavDuration by vm.wavPlayer.duration.collectAsState()
    val wavRate by vm.wavPlayer.rate.collectAsState()

    // ONE bar owns the dock: audiobook first, then a playing export, then the
    // speaking-note bar (GlobalMiniPlayerOverlay.activeBar parity).
    //
    // Suppression is honoured ONLY for surfaces that genuinely provide their
    // own transport. It used to be blanket-honoured, and the note editor sets
    // it for the WHOLE editor session (not just while speaking) — so tapping
    // play from the editor's own transport produced NO bar anywhere, on any
    // tab. Suppress only once the editor/reader is actually showing its
    // transport for a live session.
    val exportBarVisible = wavURL != null
    val bookBarVisible = audioBook != null && wavURL == null && !suppressed
    val noteBarVisible = state.isActive && !vm.player.isAuditioning &&
        audioBook == null && wavURL == null && !suppressed

    val prefs = remember {
        vm.context.getSharedPreferences("speechnotes", android.content.Context.MODE_PRIVATE)
    }

    Box(modifier = modifier) {
        // Expanded bars keep their bottom-docked Column; the collapsed
        // bubbles float anywhere and live in [EdgeSnappingBubble] (only one
        // dock owner is visible at a time).
        Column(Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(
                visible = exportBarVisible && !collapsed,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                val url = wavURL ?: return@AnimatedVisibility
                ExportMiniBar(
                    name = url.nameWithoutExtension,
                    playing = !wavPaused,
                    elapsedMs = (wavTime * 1000).toLong(),
                    durationMs = (wavDuration * 1000).toLong(),
                    progress = wavProgress,
                    rate = wavRate,
                    onTogglePlay = { vm.wavPlayer.togglePlay() },
                    onSkip = { vm.wavPlayer.skip(it) },
                    onStop = { vm.wavPlayer.stop() },
                    onMinimize = { vm.miniPlayerCollapsed = true },
                    onTap = { exportTapJumper?.invoke() },
                )
            }
            AnimatedVisibility(
                visible = bookBarVisible && !collapsed,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                val book = audioBook ?: return@AnimatedVisibility
                AudioBookMiniBar(
                    book = book,
                    chapterTitle = audioChapter,
                    playing = audioPlaying,
                    elapsedMs = audioElapsed,
                    durationMs = audioDuration,
                    onTogglePlay = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        vm.audioBooks.togglePlay()
                    },
                    onSkip = { vm.audioBooks.seekByMs(it) },
                    onStop = { vm.audioBooks.stop() },
                    onMinimize = { vm.miniPlayerCollapsed = true },
                    onTap = onJumpToBook,
                )
            }
            AnimatedVisibility(
                visible = noteBarVisible && !collapsed,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                MiniPlayerBar(
                    state = state,
                    progress = progress,
                    title = title ?: "Speaking",
                    subtitle = vm.player.currentVoiceDescription,
                    sleepTimer = sleepTimer,
                    isBook = bookId != null,
                    rate = rate,
                    onTogglePlay = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        vm.player.toggle()
                    },
                    onStop = { vm.player.stop() },
                    onSleepTimer = { vm.player.setSleepTimer(it) },
                    onRate = { vm.player.setRateMultiplier(it) },
                    onMinimize = { vm.miniPlayerCollapsed = true },
                    onTap = { if (bookId != null) onJumpToBook() else onJumpToNote() },
                )
            }
        }

        if (collapsed) {
            // Expand taps land within one frame and buzz (FEEL).
            fun expand() {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                vm.miniPlayerCollapsed = false
            }
            if (exportBarVisible) {
                EdgeSnappingBubble(prefs) { ExportBubble(onExpand = ::expand) }
            } else if (bookBarVisible) {
                EdgeSnappingBubble(prefs) {
                    AudioBookBubble(playing = audioPlaying, onExpand = ::expand)
                }
            } else if (noteBarVisible) {
                EdgeSnappingBubble(prefs) {
                    MiniPlayerBubble(state = state, progress = progress, onExpand = ::expand)
                }
            }
        }
    }
}

/**
 * The user-movable floating bubble (iOS GlobalMiniPlayerOverlay
 * EdgeSnappingBubble, v1.7.2): the bubble's CENTER lives in a normalized
 * (0…1, 0…1) spot persisted in the shared prefs under the exact iOS key
 * names "miniBubbleX"/"miniBubbleY", so the chosen corner survives relaunch
 * (the keys are the cross-platform preference contract). Dragging moves it
 * freely; releasing snaps it to the nearest of the four edges, keeping the
 * along-edge coordinate. Tap vs drag: the bubble content stays clickable —
 * the drag detector only claims the touch past the system slop, so a tap
 * never turns into a micro-drag.
 */
@Composable
private fun EdgeSnappingBubble(
    prefs: android.content.SharedPreferences,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val footprint = 72.dp // 56dp bubble + its 16dp breathing padding
        var storedX by remember { mutableFloatStateOf(prefs.getFloat(BUBBLE_X_KEY, 1f)) }
        var storedY by remember { mutableFloatStateOf(prefs.getFloat(BUBBLE_Y_KEY, 1f)) }

        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val footprintPx = with(density) { footprint.toPx() }
        val radiusPx = footprintPx / 2f
        val marginPx = with(density) { 12.dp.toPx() }
        // The bubble parks above the bottom dock zone (iOS bottomReserve:
        // the tab bar) so it never covers a surface's own controls.
        val bottomReservePx = with(density) { 64.dp.toPx() }
        val minX = marginPx + radiusPx
        val maxX = (widthPx - marginPx - radiusPx).coerceAtLeast(minX)
        val minY = marginPx + radiusPx
        val maxY = (heightPx - bottomReservePx - marginPx - radiusPx).coerceAtLeast(minY)
        val rangeX = maxX - minX
        val rangeY = maxY - minY

        val position = remember {
            Animatable(Offset(minX + rangeX * storedX, minY + rangeY * storedY), Offset.VectorConverter)
        }
        var dragging by remember { mutableStateOf(false) }

        // Container size changed (rotation, window resize): re-place the
        // bubble at its stored normalized spot unless a drag owns it.
        LaunchedEffect(widthPx, heightPx) {
            if (!dragging) {
                position.snapTo(Offset(minX + rangeX * storedX, minY + rangeY * storedY))
            }
        }

        Box(
            Modifier
                .offset {
                    IntOffset(
                        (position.value.x - footprintPx / 2).roundToInt(),
                        (position.value.y - footprintPx / 2).roundToInt(),
                    )
                }
                .size(footprint)
                .pointerInput(minX, maxX, minY, maxY, rangeX, rangeY) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // The Animatable's current value is
                            // authoritative while dragging — accumulate the
                            // per-event delta onto it and clamp to bounds.
                            scope.launch {
                                position.snapTo(
                                    Offset(
                                        (position.value.x + dragAmount.x).coerceIn(minX, maxX),
                                        (position.value.y + dragAmount.y).coerceIn(minY, maxY),
                                    ),
                                )
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            val x = position.value.x
                            val y = position.value.y
                            // Snap to the nearest edge; the other coordinate
                            // keeps its (clamped) place along that edge.
                            val toLeft = x - minX
                            val toRight = maxX - x
                            val toTop = y - minY
                            val toBottom = maxY - y
                            val nearest = minOf(toLeft, toRight, toTop, toBottom)
                            val snappedX = when (nearest) {
                                toLeft -> minX
                                toRight -> maxX
                                else -> x
                            }
                            val snappedY = when (nearest) {
                                toTop -> minY
                                toBottom -> maxY
                                else -> y
                            }
                            // Persist first, outside the animation: the
                            // stored spot and the animated value then agree,
                            // so the settle is the only visible motion.
                            storedX = if (rangeX > 0) ((snappedX - minX) / rangeX) else 1f
                            storedY = if (rangeY > 0) ((snappedY - minY) / rangeY) else 1f
                            prefs.edit()
                                .putFloat(BUBBLE_X_KEY, storedX)
                                .putFloat(BUBBLE_Y_KEY, storedY)
                                .apply()
                            // Critically damped glide to the edge — a bouncy
                            // spring here is what made the iOS drop read as
                            // a rubber band.
                            scope.launch {
                                position.animateTo(
                                    Offset(snappedX, snappedY),
                                    tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                )
                            }
                        },
                        onDragCancel = { dragging = false },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private const val BUBBLE_X_KEY = "miniBubbleX"
private const val BUBBLE_Y_KEY = "miniBubbleY"

/** Audiobook mini bar: cover thumb, title/chapter, ±15 s, transport. */
@Composable
private fun AudioBookMiniBar(
    book: com.speechnotes.app.model.Book,
    chapterTitle: String?,
    playing: Boolean,
    elapsedMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSkip: (Long) -> Unit,
    onStop: () -> Unit,
    onMinimize: () -> Unit,
    onTap: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                // The WHOLE bar is the jump target: iOS puts
                // `.contentShape(Rectangle()).onTapGesture` on the bar body
                // (AudioBookMiniPlayerBar.swift), so a tap in the free space
                // next to the title opens the book. The transport buttons
                // below are children and keep their own clicks.
                .clickable(onClick = onTap)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (book.hasCover) {
                AsyncImage(
                    model = AppFiles.bookCoverFile(book.id),
                    contentDescription = book.title,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(onClick = onTap),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onTap),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = (chapterTitle ?: "Audiobook") +
                        if (durationMs > 0) " · ${formatClockMini(elapsedMs)} / ${formatClockMini(durationMs)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onSkip(-15_000L) }) {
                com.speechnotes.app.ui.playback.SkipSecondsIcon(seconds = 15, forward = false)
            }
            FilledIconButton(onClick = onTogglePlay) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
            IconButton(onClick = { onSkip(15_000L) }) {
                com.speechnotes.app.ui.playback.SkipSecondsIcon(seconds = 15, forward = true)
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop playback")
            }
            IconButton(onClick = onMinimize) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize player")
            }
        }
    }
}

@Composable
private fun AudioBookBubble(
    playing: Boolean,
    onExpand: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(16.dp)
            .size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp).clickable(onClick = onExpand),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Player",
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    state: SpeechState,
    progress: Double?,
    title: String,
    subtitle: String,
    sleepTimer: SpeechPlayer.SleepTimer,
    isBook: Boolean,
    rate: Double,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onSleepTimer: (SpeechPlayer.SleepTimer) -> Unit,
    onRate: (Double) -> Unit,
    onMinimize: () -> Unit,
    onTap: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (progress != null && state == SpeechState.SPEAKING) {
                LinearProgressIndicator(
                    progress = { progress.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier
                    // The free space of the bar is the jump target (iOS
                    // MiniPlayerBar.swift: the bar body carries the tap
                    // gesture). The title keeps its own clickable so the
                    // gesture reads as "open this" there too.
                    .clickable(onClick = onTap)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val speaking = state == SpeechState.SPEAKING
                val generating = state == SpeechState.GENERATING
                // While GENERATING the tap CANCELS (SpeechPlayer.togglePlay
                // stops), so the control stays live and the icon says Stop —
                // a disabled button during the multi-second model load read as
                // "the tap never registered".
                Button(onClick = onTogglePlay) {
                    when {
                        generating -> {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                        speaking -> {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pause")
                        }
                        else -> {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play")
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop playback")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onTap),
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SleepTimerButton(sleepTimer, onSleepTimer)
                SpeedChip(rate, onRate)
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize player")
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerBubble(
    state: SpeechState,
    progress: Double?,
    onExpand: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(16.dp)
            .size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp).clickable(onClick = onExpand),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress.toFloat() },
                        modifier = Modifier.size(52.dp),
                    )
                }
                Icon(
                    imageVector = if (state == SpeechState.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = "Player",
                )
            }
        }
    }
}

@Composable
private fun SleepTimerButton(current: SpeechPlayer.SleepTimer, onSelect: (SpeechPlayer.SleepTimer) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreHoriz, contentDescription = "Sleep timer")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(5, 15, 30, 60).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("$minutes minutes") },
                    onClick = {
                        onSelect(SpeechPlayer.SleepTimer.Minutes(minutes))
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("End of chapter") },
                onClick = {
                    onSelect(SpeechPlayer.SleepTimer.EndOfChapter)
                    expanded = false
                },
            )
            if (current != SpeechPlayer.SleepTimer.Off) {
                DropdownMenuItem(
                    text = { Text("Cancel sleep timer") },
                    onClick = {
                        onSelect(SpeechPlayer.SleepTimer.Off)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Compact speed chip (BookPlayerBar.swift parity): presets 0.5×–2×. */
@Composable
private fun SpeedChip(rate: Double, onSelect: (Double) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(String.format("%.2f×", rate), style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0).forEach { speed ->
                DropdownMenuItem(
                    text = { Text(String.format("%.2f×", speed) + if (speed == rate) "  ✓" else "") },
                    onClick = {
                        onSelect(speed)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun formatClockMini(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}


/** Export (WAV playback) mini bar: name, clock, ±15 s, transport. */
@Composable
private fun ExportMiniBar(
    name: String,
    playing: Boolean,
    elapsedMs: Long,
    durationMs: Long,
    progress: Double?,
    rate: Double,
    onTogglePlay: () -> Unit,
    onSkip: (Double) -> Unit,
    onStop: () -> Unit,
    onMinimize: () -> Unit,
    onTap: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (progress != null && playing) {
                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier
                    // Same as the other bars: the free space jumps to the
                    // exported-audio list (iOS ExportMiniPlayerBar.swift).
                    .clickable(onClick = onTap)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onTogglePlay) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "Pause" else "Play")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop playback")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onTap),
                    )
                    Text(
                        "${formatClockMini(elapsedMs)} / ${formatClockMini(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onSkip(-15.0) }) {
                    com.speechnotes.app.ui.playback.SkipSecondsIcon(seconds = 15, forward = false)
                }
                IconButton(onClick = { onSkip(15.0) }) {
                    com.speechnotes.app.ui.playback.SkipSecondsIcon(seconds = 15, forward = true)
                }
                Text(
                    "%.2f×".format(rate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize player")
                }
            }
        }
    }
}

@Composable
private fun ExportBubble(onExpand: () -> Unit) {
    Box(
        modifier = Modifier.padding(16.dp).size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp).clickable(onClick = onExpand),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Player")
            }
        }
    }
}
