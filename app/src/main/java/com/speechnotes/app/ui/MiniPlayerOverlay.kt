package com.speechnotes.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.speech.SpeechPlayer
import com.speechnotes.app.speech.SpeechState

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

    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = exportBarVisible,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val url = wavURL ?: return@AnimatedVisibility
            if (collapsed) {
                ExportBubble(onExpand = { vm.miniPlayerCollapsed = false })
            } else {
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
        }
        AnimatedVisibility(
            visible = bookBarVisible,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val book = audioBook ?: return@AnimatedVisibility
            if (collapsed) {
                AudioBookBubble(
                    playing = audioPlaying,
                    onExpand = { vm.miniPlayerCollapsed = false },
                )
            } else {
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
        }
        AnimatedVisibility(
            visible = noteBarVisible,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            if (collapsed) {
                MiniPlayerBubble(
                    state = state,
                    progress = progress,
                    onExpand = { vm.miniPlayerCollapsed = false },
                )
            } else {
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
    }
}

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
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
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
                Icon(Icons.Default.Replay10, contentDescription = "Back 15 seconds", modifier = Modifier.size(18.dp))
            }
            FilledIconButton(onClick = onTogglePlay) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
            IconButton(onClick = { onSkip(15_000L) }) {
                Icon(Icons.Default.Forward10, contentDescription = "Forward 15 seconds", modifier = Modifier.size(18.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Icon(Icons.Default.Replay10, contentDescription = "Back 15 seconds", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { onSkip(15.0) }) {
                    Icon(Icons.Default.Forward10, contentDescription = "Forward 15 seconds", modifier = Modifier.size(18.dp))
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
