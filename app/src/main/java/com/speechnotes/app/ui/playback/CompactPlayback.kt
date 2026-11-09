package com.speechnotes.app.ui.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.SpeechnotesViewModel

/**
 * The app-wide "the book transport is collapsed" preference — the iOS
 * `@AppStorage("bookBarMinimized")` key, on the SAME SharedPreferences file
 * as every other iOS-shared key.
 *
 * One key for all three book readers (EPUB, PDF, audiobook), exactly like
 * iOS: the reader always opens the way it was left, and collapsing a book
 * transport in one reader leaves the others collapsed too.
 */
const val BOOK_BAR_MINIMIZED_KEY = "bookBarMinimized"

/** The SharedPreferences file the app's iOS-shared keys live in. */
private const val PREFS_NAME = "speechnotes"

/**
 * Reads and observes the collapsed-book-transport preference.
 *
 * The three readers are separate composables that can be alive at the same
 * time (the reader stays on the back stack while another surface is shown),
 * so a change made in one must reach the others: the SharedPreferences
 * change listener keeps every live reader in step, and the value is written
 * back through [setBookBarMinimized].
 */
@Composable
fun rememberBookBarMinimized(vm: SpeechnotesViewModel): MutableState<Boolean> {
    val prefs = remember {
        vm.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    val state = remember { mutableStateOf(prefs.getBoolean(BOOK_BAR_MINIMIZED_KEY, false)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == BOOK_BAR_MINIMIZED_KEY) {
                state.value = prefs.getBoolean(BOOK_BAR_MINIMIZED_KEY, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** Collapses or expands the book transport for every book reader. */
fun setBookBarMinimized(vm: SpeechnotesViewModel, value: Boolean) {
    vm.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOK_BAR_MINIMIZED_KEY, value)
        .apply()
}

/**
 * Portrait counterpart to PlaybackRail's minimized capsule (iOS
 * MinimizedBookTransport / PlayerControlsBar.minimizedPill): one slim pill
 * with play, progress, optional page/chapter stepping and an expand chevron.
 * The reading surface keeps every other row of space.
 */
@Composable
fun MinimizedPlaybackPill(
    isPlaying: Boolean,
    isGenerating: Boolean,
    isPlayEnabled: Boolean,
    progressText: String?,
    onTogglePlay: () -> Unit,
    onExpand: () -> Unit,
    onStepBack: (() -> Unit)? = null,
    onStepForward: (() -> Unit)? = null,
    stepBackEnabled: Boolean = true,
    stepForwardEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onTogglePlay,
                enabled = isPlayEnabled,
                modifier = Modifier.size(32.dp),
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isGenerating) "Stop" else if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (onStepBack != null) {
                IconButton(
                    onClick = onStepBack,
                    enabled = stepBackEnabled,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
                }
            }
            if (!progressText.isNullOrBlank()) {
                Text(
                    progressText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            if (onStepForward != null) {
                IconButton(
                    onClick = onStepForward,
                    enabled = stepForwardEnabled,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
                }
            }
            IconButton(onClick = onExpand) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Expand playback controls")
            }
        }
    }
}
