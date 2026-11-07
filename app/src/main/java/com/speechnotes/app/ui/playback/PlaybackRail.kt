package com.speechnotes.app.ui.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.SpeechnotesViewModel

/**
 * The landscape playback rail (iOS PlaybackRail.swift port): a panel pinned
 * to the trailing edge of the reading surfaces, with the reading surface
 * kept to its leading side. In landscape the screen is short, so a bottom
 * bar eats the reading height — the rail mirrors the portrait
 * PlayerControlsBar feature-for-feature instead: progress readout, chapter
 * stepper (where the surface has one), voice chip, 52dp play cluster,
 * stop/read-along, and a normal horizontal rate slider. It collapses to a
 * slim capsule (the "landscapeRailMinimized" pref — the iOS key name, the
 * cross-platform preference contract) and the reading surface re-flows to
 * the freed width.
 *
 * The rail holds no playback knowledge: every surface fills in the action
 * closures for its own player (note TTS, chapter narration, audiobook).
 */

/** Window-width based landscape test — MainActivity does not recreate on rotation. */
@Composable
fun isLandscape(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp > config.screenHeightDp
}

/** The expanded rail column reserves this much width (iOS idealWidth 150). */
private val RailPanelWidth = 150.dp
/** The collapsed capsule's column footprint, padded so it never kisses the bezel. */
private val RailCapsuleWidth = 66.dp

@Composable
fun PlaybackRail(
    vm: SpeechnotesViewModel,
    progress: Double?,
    isGenerating: Boolean,
    isSpeaking: Boolean,
    isPlayEnabled: Boolean,
    onTogglePlay: () -> Unit,
    onStop: (() -> Unit)? = null,
    voiceLabel: String? = null,
    onChangeVoice: (() -> Unit)? = null,
    readAlongOn: Boolean? = null,
    onToggleReadAlong: (() -> Unit)? = null,
    stepLabel: String? = null,
    onStepBack: (() -> Unit)? = null,
    onStepForward: (() -> Unit)? = null,
    stepBackEnabled: Boolean = true,
    stepForwardEnabled: Boolean = true,
    rate: Double,
    onRateChange: (Double) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // The collapse state lives in prefs (the shared iOS key) with a
    // recomposition mirror; hosts that need the freed width read the same
    // pref through railMinimized(vm).
    val minimized = remember { mutableStateOf(railMinimized(vm)) }
    fun toggleMinimized() {
        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
        val to = !minimized.value
        vm.context.getSharedPreferences("speechnotes", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("landscapeRailMinimized", to).apply()
        minimized.value = to
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .width(if (minimized.value) RailCapsuleWidth else RailPanelWidth),
    ) {
        if (minimized.value) {
            MinimizedCapsule(
                progress = progress,
                playIcon = railPlayIcon(isGenerating, isSpeaking),
                isPlayEnabled = isPlayEnabled,
                onTogglePlay = onTogglePlay,
                onExpand = ::toggleMinimized,
            )
        } else {
            FullPanel(
                progress = progress,
                isGenerating = isGenerating,
                isSpeaking = isSpeaking,
                isPlayEnabled = isPlayEnabled,
                onTogglePlay = onTogglePlay,
                onStop = onStop,
                voiceLabel = voiceLabel,
                onChangeVoice = onChangeVoice,
                readAlongOn = readAlongOn,
                onToggleReadAlong = onToggleReadAlong,
                stepLabel = stepLabel,
                onStepBack = onStepBack,
                onStepForward = onStepForward,
                stepBackEnabled = stepBackEnabled,
                stepForwardEnabled = stepForwardEnabled,
                rate = rate,
                onRateChange = onRateChange,
                onMinimize = ::toggleMinimized,
            )
        }
    }
}

/** Reads the shared collapse pref — hosts use it to re-flow their width. */
fun railMinimized(vm: SpeechnotesViewModel): Boolean =
    vm.context.getSharedPreferences("speechnotes", android.content.Context.MODE_PRIVATE)
        .getBoolean("landscapeRailMinimized", false)

/**
 * A ±N-seconds skip glyph. The icon set ships numbered variants only for
 * 5/10/30 — the audiobook surface skips 15 s, so the arrow loop carries the
 * number as a centered label instead of showing a glyph that lies about the
 * seek distance.
 */
@Composable
internal fun SkipSecondsIcon(seconds: Int, forward: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            if (forward) Icons.Default.Forward else Icons.Default.Replay,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Text(
            "$seconds",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The play glyph rule is the portrait bar's: a function of the player state
 * alone — speaking → Pause, everything else → Play. Generation shows a
 * spinner in the panel (tap cancels); the capsule degrades to the same rule.
 */
private fun railPlayIcon(isGenerating: Boolean, isSpeaking: Boolean): ImageVector = when {
    isGenerating -> Icons.Default.Stop
    isSpeaking -> Icons.Default.Pause
    else -> Icons.Default.PlayArrow
}

/** The expanded panel: top zone (minimize + stepper + voice), centered play cluster, rate slider. */
@Composable
private fun FullPanel(
    progress: Double?,
    isGenerating: Boolean,
    isSpeaking: Boolean,
    isPlayEnabled: Boolean,
    onTogglePlay: () -> Unit,
    onStop: (() -> Unit)?,
    voiceLabel: String?,
    onChangeVoice: (() -> Unit)?,
    readAlongOn: Boolean?,
    onToggleReadAlong: (() -> Unit)?,
    stepLabel: String?,
    onStepBack: (() -> Unit)?,
    onStepForward: (() -> Unit)?,
    stepBackEnabled: Boolean,
    stepForwardEnabled: Boolean,
    rate: Double,
    onRateChange: (Double) -> Unit,
    onMinimize: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Minimize — the portrait bar's chevron affordance, pointing off
        // the trailing edge. Top zone never moves.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Minimize playback rail",
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onMinimize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Landscape prev/next stepper for the surface's unit (chapter or
        // page) — the portrait band is gone in this orientation, so the
        // rail is the one place they live.
        if (stepLabel != null && onStepBack != null && onStepForward != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStepBack()
                }, enabled = stepBackEnabled) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
                }
                Text(
                    stepLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStepForward()
                }, enabled = stepForwardEnabled) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
                }
            }
        }
        if (voiceLabel != null && onChangeVoice != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onChangeVoice()
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        voiceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // The play cluster is always optically centered in the leftover
        // space regardless of what else the panel shows.
        FilledIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onTogglePlay()
            },
            enabled = isPlayEnabled,
            modifier = Modifier.size(52.dp),
        ) {
            if (isGenerating) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    railPlayIcon(isGenerating, isSpeaking),
                    contentDescription = if (isSpeaking) "Pause" else "Play",
                )
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onStop != null) {
                IconButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStop()
                }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            }
            if (readAlongOn != null && onToggleReadAlong != null) {
                IconButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onToggleReadAlong()
                }) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = if (readAlongOn) "Read-along on" else "Read-along off",
                        tint = if (readAlongOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("%.2f×".format(rate), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Slider(
            value = rate.toFloat().coerceIn(0.5f, 2f),
            onValueChange = { onRateChange(it.toDouble()) },
            valueRange = 0.5f..2f,
        )
    }
}

/** The collapsed rail: progress-ring capsule with the play glyph, expand chevron beneath. */
@Composable
private fun MinimizedCapsule(
    progress: Double?,
    playIcon: ImageVector,
    isPlayEnabled: Boolean,
    onTogglePlay: () -> Unit,
    onExpand: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.size(46.dp),
                )
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onTogglePlay()
            }, enabled = isPlayEnabled) {
                Icon(playIcon, contentDescription = if (playIcon == Icons.Default.Pause) "Pause" else "Play")
            }
        }
        Spacer(Modifier.height(8.dp))
        Icon(
            Icons.Default.ChevronLeft,
            contentDescription = "Expand playback rail",
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onExpand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
