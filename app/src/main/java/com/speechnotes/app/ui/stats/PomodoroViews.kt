package com.speechnotes.app.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speechnotes.app.data.PomodoroBanner
import com.speechnotes.app.logic.PomodoroConfig
import com.speechnotes.app.data.PomodoroFormat
import com.speechnotes.app.logic.PomodoroMode
import com.speechnotes.app.logic.PomodoroPhase
import com.speechnotes.app.data.PomodoroStore
import com.speechnotes.app.data.PomodoroTint
import com.speechnotes.app.logic.nowEpochSeconds
import com.speechnotes.app.ui.theme.Haptics
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * The Stats tab's Pomodoro section, ported from PomodoroViews.swift
 * (speechnotes-ios v1.8.x, commits 8cc4e4c + 484e564 + e07841f). Sits
 * directly under Goals. Idle, it reads like the goals empty state; running,
 * it shows the glass fill, the countdown and the controls. Customization
 * lives on the config sheet (PomodoroConfigSheet); this card is the
 * always-visible viewer.
 */
@Composable
fun PomodoroCard() {
    val context = LocalContext.current
    val session by PomodoroStore.session.collectAsState()
    val today by PomodoroStore.history.collectAsState()
    var showingConfig by remember { mutableStateOf(false) }

    // The card picks a running session up when it appears (iOS onAppear
    // tick parity) — e.g. a session started from the banner elsewhere.
    LaunchedEffect(Unit) { PomodoroStore.tick() }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pomodoro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(TintBlue)
                    .clickable {
                        Haptics.tap(context)
                        showingConfig = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = "Set up the pomodoro timer",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        val running = session?.let { if (it.isRunning) it else null }
        if (running != null) {
            PomodoroRunView(storeSession = running)
        } else {
            PomodoroIdleView(onSetup = {
                Haptics.tap(context)
                showingConfig = true
            })
        }

        PomodoroTodayLine(today.firstOrNull { it.dayKey == todayKeyToday() })
    }

    if (showingConfig) {
        PomodoroConfigSheet(onDismiss = { showingConfig = false })
    }
}

private fun todayKeyToday(): String =
    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

/** The idle state — goals-empty-state flavored, with a "Set up" affordance. */
@Composable
private fun PomodoroIdleView(onSetup: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TintBlue.copy(alpha = 0.08f))
            .clickable(onClick = onSetup)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⏱️", fontSize = 22.sp)
        Column(Modifier.weight(1f)) {
            Text(
                "No timer running",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Structured, Flowmodoro or classic — set it up and it counts down right here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "›",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The tiny "today" line under the section — proof the timer feeds something. */
@Composable
private fun PomodoroTodayLine(record: com.speechnotes.app.data.PomodoroDayRecord?) {
    if (record == null || (record.blocks == 0 && record.focusSeconds <= 0.0)) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("🔥", fontSize = 11.sp)
        Text(
            "Today · ${record.blocks} block${if (record.blocks == 1) "" else "s"} · " +
                PomodoroFormat.minutes(record.focusSeconds) + " focused",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Running state

/**
 * Always read the session THROUGH the store state (the [PomodoroStore] flow
 * re-publishes on every transition) and re-render off a local 4 Hz clock —
 * a card rendering its construction-time snapshot can go stale across a
 * phase transition ("fill stuck at full until the tab was refreshed").
 */
@Composable
private fun PomodoroRunView(storeSession: com.speechnotes.app.logic.PomodoroSession) {
    val context = LocalContext.current
    val sessionFlow by PomodoroStore.session.collectAsState()
    // The one live copy; the snapshot only covers the instant between a
    // completion and the parent swapping this card for the idle view.
    val session = sessionFlow ?: storeSession

    var nowSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            nowSec = (nowEpochSeconds() * 1000).toLong()
        }
    }
    val now = nowSec / 1000.0

    var confirmingStop by remember { mutableStateOf(false) }
    val tint = tintOf(PomodoroStore.tintFor(session.phase))

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        timerCard(session, now, tint)
        caption(session)
        controls(session, now, tint) {
            Haptics.press(context)
            confirmingStop = true
        }
    }

    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text("End this pomodoro?") },
            text = { Text("Focus time so far stays in today's count.") },
            confirmButton = {
                TextButton(onClick = {
                    Haptics.press(context)
                    PomodoroStore.stop()
                    confirmingStop = false
                }) { Text("End timer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingStop = false }) { Text("Keep going") }
            },
        )
    }
}

/** The running display — a full-width rounded rectangle whose fill grows
 *  with the phase (device feedback: a circular ring read "round" and sat
 *  off-center; a rectangle that spans the card centers itself). */
@Composable
private fun timerCard(
    session: com.speechnotes.app.logic.PomodoroSession,
    now: Double,
    tint: Color,
) {
    val progress = session.phaseProgress(now)
    val paused = session.isPaused

    Box(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
    ) {
        // The fill.
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.toFloat().coerceIn(0.001f, 1f))
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.5f), tint))),
        )
        // The juice: bubbles rising through the fill — enough to read as
        // liquid, not enough to become an aquarium. Hidden while the glass
        // is effectively empty.
        if (progress > 0.04) {
            JuiceBubbles(paused = paused, modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                centerTime(session, now),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            phasePill(session)
        }
    }
}

/**
 * The juice: four bubble streams, each on its own period, column, sway and
 * phase so the surface never reads as a loop. Frame-clock note: the card's
 * 250 ms clock steps the countdown, and feeding THAT clock here moved the
 * bubbles in jumps — the actual "not smooth" report on iOS. The frame-nanos
 * loop below schedules per-frame (and only while composed); everything is
 * deterministic off the frame time — no animation state to stutter when the
 * card re-renders, nothing to clean up. `paused` stops the frame loop, so a
 * paused session freezes the bubbles exactly where they are.
 */
@Composable
private fun JuiceBubbles(paused: Boolean, modifier: Modifier = Modifier) {
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(paused) {
        if (!paused) {
            while (true) withFrameNanos { frameNanos = it }
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (h < 24.dp.toPx() || w < 12.dp.toPx()) return@Canvas
        val t = frameNanos / 1_000_000_000.0
        // (period, phase, radiusDp, xFrac, swayDp, cycles) — iOS constants.
        val streams = listOf(
            listOf(5.2, 0.00, 4.3f, 0.30f, 3.0f, 1.6),
            listOf(7.1, 0.38, 2.8f, 0.55f, 2.2f, 2.2),
            listOf(6.1, 0.62, 3.5f, 0.72f, 2.6f, 1.3),
            listOf(8.4, 0.80, 2.3f, 0.44f, 1.8f, 2.6),
        )
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
            for (s in streams) {
                val period = s[0] as Double
                val phase = s[1] as Double
                val radius = (s[2] as Float).dp.toPx()
                val xFrac = s[3] as Float
                val sway = (s[4] as Float).dp.toPx()
                val cycles = s[5] as Double
                var lap = (t / period) + phase
                lap -= Math.floor(lap)
                // Fade envelope: invisible at departure and arrival.
                val alpha = Math.sin(lap * Math.PI) * 0.5
                if (alpha <= 0.02) continue
                val margin = radius * 3
                // Smoothstep ease: the bubble eases out of the depth, glides
                // through the middle and settles at the surface.
                val eased = lap * lap * (3 - 2 * lap)
                val y = h - margin - (eased * (h - margin * 2)).toFloat()
                val x = w * xFrac + (Math.sin((lap + phase) * Math.PI * cycles) * sway).toFloat()
                paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                canvas.nativeCanvas.drawCircle(x, y, radius, paint)
            }
        }
    }
}

/** Fixed phases count down; flowmodoro's open focus counts up (the pot). */
private fun centerTime(session: com.speechnotes.app.logic.PomodoroSession, now: Double): String {
    if (session.config.mode == PomodoroMode.FLOWMODORO && session.phase == PomodoroPhase.FOCUS) {
        return PomodoroFormat.countdown(session.flowAccruedFocus(now))
    }
    return PomodoroFormat.countdown(session.secondsRemaining(now) ?: 0.0)
}

@Composable
private fun phasePill(session: com.speechnotes.app.logic.PomodoroSession) {
    val tint = tintOf(PomodoroStore.tintFor(session.phase))
    Text(
        phaseLabel(session),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun phaseLabel(session: com.speechnotes.app.logic.PomodoroSession): String {
    if (session.isPaused) return "Paused"
    return when (session.phase) {
        PomodoroPhase.PREP -> "Preparing"
        PomodoroPhase.FOCUS -> {
            if (session.config.mode == PomodoroMode.FLOWMODORO) return "Focus"
            stepNumber(session, PomodoroPhase.FOCUS)?.let { "Focus ${it.first} of ${it.second}" } ?: "Focus"
        }
        PomodoroPhase.REST -> {
            if (session.config.mode == PomodoroMode.FLOWMODORO) return "Break"
            stepNumber(session, PomodoroPhase.REST)?.let { "Break ${it.first} of ${it.second}" } ?: "Break"
        }
        PomodoroPhase.DONE -> "Done"
    }
}

/** Which focus block / break is live (structured & classic only —
 *  flowmodoro has no numbered schedule). */
private fun stepNumber(
    session: com.speechnotes.app.logic.PomodoroSession,
    phase: PomodoroPhase,
): Pair<Int, Int>? {
    if (session.config.mode == PomodoroMode.FLOWMODORO || session.steps.isEmpty()) return null
    val total = session.steps.count { it.phase == phase }
    if (total == 0) return null
    val through = session.stepIndex.coerceAtMost(session.steps.size - 1)
    val n = (0..through).count { session.steps[it].phase == phase }
    return maxOf(1, n) to total
}

@Composable
private fun caption(session: com.speechnotes.app.logic.PomodoroSession) {
    val text = when {
        session.config.mode == PomodoroMode.FLOWMODORO ->
            "5 min of focus earns 1 min of break"
        session.config.mode == PomodoroMode.STRUCTURED && session.windowEnd != null -> {
            val end = java.time.Instant.ofEpochMilli((session.windowEnd!! * 1000).toLong())
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            "Ends $end · ${session.config.structuredBreakCount} breaks × ${session.config.structuredBreakSeconds / 60} min"
        }
        else -> {
            val focus = session.config.classicFocusMinutes.sum()
            "${session.config.classicFocusMinutes.size} blocks · ${session.config.classicBreakMinutes.size} breaks · " +
                PomodoroFormat.minutes(focus * 60.0) + " of focus"
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun controls(
    session: com.speechnotes.app.logic.PomodoroSession,
    now: Double,
    tint: Color,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pause / resume — always available while running.
        FilledTonalButton(onClick = {
            Haptics.tap(context)
            if (session.isPaused) PomodoroStore.resume() else PomodoroStore.pause()
        }) {
            Text(if (session.isPaused) "▶ Resume" else "⏸ Pause", fontSize = 13.sp, color = tint)
        }

        // Flowmodoro claim — appears once the pot crosses 3 banked break
        // minutes (15 focus minutes); disabled until then.
        if (session.config.mode == PomodoroMode.FLOWMODORO && session.phase == PomodoroPhase.FOCUS) {
            FilledTonalButton(
                onClick = {
                    Haptics.tap(context)
                    PomodoroStore.claimBreak()
                },
                enabled = session.flowCanClaim(now),
            ) {
                Text("☕ Claim break", fontSize = 13.sp, color = tint)
            }
        }

        // Skip — never during the mandatory prep; flowmodoro only skips a
        // rest (its focus has no "next" without a claim).
        val canSkip = !session.isPaused && session.phase != PomodoroPhase.PREP &&
            (session.config.mode != PomodoroMode.FLOWMODORO || session.phase == PomodoroPhase.REST)
        if (canSkip) {
            FilledTonalButton(onClick = {
                Haptics.tap(context)
                PomodoroStore.skipPhase()
            }) {
                Text("Skip ⏩", fontSize = 13.sp)
            }
        }

        FilledTonalButton(onClick = onStop) {
            Text("⏹ Stop", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

// MARK: - Config sheet

/**
 * The customization sheet. Everything the user asked to tune lives here:
 * mode, the mandatory 2–5 min prep, the structured window (duration or
 * finish time + break count/length), the flowmodoro rule (fixed), and
 * classic's per-block/per-break minute tuning. (PomodoroConfigView.swift.)
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PomodoroConfigSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val storeConfig by PomodoroStore.config.collectAsState()
    val storeSession by PomodoroStore.session.collectAsState()
    val draft = remember(storeConfig) {
        storeConfig.copy(
            classicFocusMinutes = storeConfig.classicFocusMinutes.toMutableList(),
            classicBreakMinutes = storeConfig.classicBreakMinutes.toMutableList(),
        )
    }
    var draftState by remember { mutableStateOf(draft) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("New Pomodoro", style = MaterialTheme.typography.titleLarge)

            // Mode.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = draftState.mode == mode,
                        onClick = { Haptics.tap(context); draftState = draftState.copy(mode = mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) { Text(label, fontSize = 13.sp) }
                }
            }
            Text(
                when (draftState.mode) {
                    PomodoroMode.STRUCTURED ->
                        "One window — a time limit or a finish time — with breaks spread evenly through it."
                    PomodoroMode.FLOWMODORO ->
                        "Focus as long as you like. Every 5 focus minutes bank 1 break minute; the claim unlocks once 3 break minutes are banked (15 focus minutes)."
                    PomodoroMode.CLASSIC ->
                        "Your own blocks and breaks, each with its own length — 25 then 30, 5 then 15, whatever fits."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Preparation (mandatory, 2–5 min, every mode, not skippable).
            Text("Preparation", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                (2..5).forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = prepMinutesOf(draftState) == m,
                        onClick = { Haptics.tap(context); draftState = draftState.copy(prepSeconds = m * 60) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                    ) { Text("$m min", fontSize = 13.sp) }
                }
            }
            Text(
                "Every timer opens with this short prepare window — settle in before focus starts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (draftState.mode) {
                PomodoroMode.STRUCTURED -> structuredSections(draftState) { draftState = it }
                PomodoroMode.FLOWMODORO -> flowmodoroSections()
                PomodoroMode.CLASSIC -> classicSections(draftState) { draftState = it }
            }

            // Start.
            val canStart = when (draftState.mode) {
                PomodoroMode.FLOWMODORO -> true
                PomodoroMode.CLASSIC -> draftState.classicFocusMinutes.isNotEmpty()
                PomodoroMode.STRUCTURED -> structuredSummary(draftState) != null
            }
            val summary = structuredSummary(draftState)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (canStart) TintBlue else TintBlue.copy(alpha = 0.4f))
                    .clickable(enabled = canStart) {
                        Haptics.success(context)
                        PomodoroStore.start(draftState)
                        onDismiss()
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Start Timer",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                if (storeSession?.isRunning == true) {
                    "Starting now replaces the timer that's running."
                } else {
                    summary ?: ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val modes = listOf(
    PomodoroMode.STRUCTURED to "Structured",
    PomodoroMode.FLOWMODORO to "Flowmodoro",
    PomodoroMode.CLASSIC to "Pomodoro",
)

private fun prepMinutesOf(config: PomodoroConfig): Int =
    (config.prepSeconds / 60).coerceIn(2, 5)

@Composable
private fun structuredSections(draft: PomodoroConfig, onChange: (PomodoroConfig) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ends", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(true to "Time limit", false to "Finish time").forEachIndexed { index, (byDuration, label) ->
                SegmentedButton(
                    selected = draft.structuredByDuration == byDuration,
                    onClick = {
                        Haptics.tap(context)
                        onChange(draft.copy(structuredByDuration = byDuration))
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                ) { Text(label, fontSize = 13.sp) }
            }
        }
        if (draft.structuredByDuration) {
            StepperRow(
                label = "Time limit",
                value = durationText(draft.structuredDurationSeconds),
                onStep = { delta ->
                    val next = (draft.structuredDurationSeconds / 60 + delta).coerceIn(10, 720) * 60
                    onChange(draft.copy(structuredDurationSeconds = next))
                },
            )
        } else {
            StepperRow(
                label = "Finish at",
                value = "%02d:%02d".format(draft.structuredFinishHour, draft.structuredFinishMinute),
                onMinus = {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, draft.structuredFinishHour)
                    cal.add(Calendar.HOUR_OF_DAY, -1)
                    onChange(draft.copy(structuredFinishHour = cal.get(Calendar.HOUR_OF_DAY)))
                },
                onPlus = {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, draft.structuredFinishHour)
                    cal.add(Calendar.HOUR_OF_DAY, 1)
                    onChange(draft.copy(structuredFinishHour = cal.get(Calendar.HOUR_OF_DAY)))
                },
            )
        }

        Text("Breaks", style = MaterialTheme.typography.titleSmall)
        StepperRow(
            label = "Breaks in between",
            value = "${draft.structuredBreakCount}",
            onMinus = { onChange(draft.copy(structuredBreakCount = (draft.structuredBreakCount - 1).coerceIn(0, 12))) },
            onPlus = { onChange(draft.copy(structuredBreakCount = (draft.structuredBreakCount + 1).coerceIn(0, 12))) },
        )
        if (draft.structuredBreakCount > 0) {
            StepperRow(
                label = "Each break",
                value = "${draft.structuredBreakSeconds / 60} min",
                onMinus = { onChange(draft.copy(structuredBreakSeconds = ((draft.structuredBreakSeconds / 60 - 1).coerceIn(1, 30)) * 60)) },
                onPlus = { onChange(draft.copy(structuredBreakSeconds = ((draft.structuredBreakSeconds / 60 + 1).coerceIn(1, 30)) * 60)) },
            )
        }
        val summary = structuredSummary(draft)
        Text(
            summary
                ?: "Not enough room for ${draft.structuredBreakCount} break${if (draft.structuredBreakCount == 1) "" else "s"} in this window — shorten them, drop some, or give the window more time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The layout preview, or null when the window can't hold the plan. */
private fun structuredSummary(draft: PomodoroConfig): String? {
    val cal = Calendar.getInstance()
    val end = draft.structuredWindowEnd(nowEpochSeconds(), cal)
    val window = end - nowEpochSeconds()
    val segment = draft.structuredSegmentSeconds(window) ?: return null
    val endsText = if (draft.structuredByDuration) {
        "in ${durationText(draft.structuredDurationSeconds)}"
    } else {
        "at " + java.time.Instant.ofEpochMilli((end * 1000).toLong())
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
    val segments = draft.structuredBreakCount + 1
    val parts = mutableListOf("≈${PomodoroFormat.minutes(segment.toDouble())} focus × $segments")
    if (draft.structuredBreakCount > 0) {
        parts.add("${draft.structuredBreakSeconds / 60} min break × ${draft.structuredBreakCount}")
    }
    parts.add("${draft.prepSeconds / 60} min prep")
    return parts.joinToString(" + ") + ", $endsText."
}

/** The rule IS the mode — displayed, not tuned. */
@Composable
private fun flowmodoroSections() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("The rule", style = MaterialTheme.typography.titleSmall)
        flowRow("🧠", TintGreen, "5 min of focus", "earns 1 min of break")
        flowRow("☕", TintBlue, "Claim at 3 break min", "15 focused minutes — then the pot shows up as a button, take it whenever")
        flowRow("🔁", TintOrange, "It keeps growing", "20 focused minutes banks a 4-minute break")
    }
}

@Composable
private fun flowRow(icon: String, tint: Color, title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            icon,
            fontSize = 14.sp,
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(tint.copy(alpha = 0.15f))
                .wrapContentSize(Alignment.Center),
        )
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun classicSections(draft: PomodoroConfig, onChange: (PomodoroConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Focus blocks", style = MaterialTheme.typography.titleSmall)
        StepperRow(
            label = "Blocks",
            value = "${draft.classicFocusMinutes.size}",
            onMinus = {
                val blocks = draft.classicFocusMinutes.toMutableList()
                if (blocks.size > 1) {
                    blocks.removeAt(blocks.size - 1)
                    val breaks = draft.classicBreakMinutes.toMutableList()
                    while (breaks.size > blocks.size) breaks.removeAt(breaks.size - 1)
                    onChange(draft.copy(classicFocusMinutes = blocks, classicBreakMinutes = breaks))
                }
            },
            onPlus = {
                val blocks = draft.classicFocusMinutes.toMutableList()
                if (blocks.size < 12) {
                    blocks.add(25)
                    onChange(draft.copy(classicFocusMinutes = blocks))
                }
            },
        )
        draft.classicFocusMinutes.forEachIndexed { i, minutes ->
            StepperRow(
                label = "Block ${i + 1}",
                value = "$minutes min",
                onMinus = {
                    val blocks = draft.classicFocusMinutes.toMutableList()
                    blocks[i] = (blocks[i] - 1).coerceIn(1, 180)
                    onChange(draft.copy(classicFocusMinutes = blocks))
                },
                onPlus = {
                    val blocks = draft.classicFocusMinutes.toMutableList()
                    blocks[i] = (blocks[i] + 1).coerceIn(1, 180)
                    onChange(draft.copy(classicFocusMinutes = blocks))
                },
            )
        }

        Text("Breaks", style = MaterialTheme.typography.titleSmall)
        StepperRow(
            label = "Breaks",
            value = "${draft.classicBreakMinutes.size}",
            onMinus = {
                val breaks = draft.classicBreakMinutes.toMutableList()
                if (breaks.isNotEmpty()) {
                    breaks.removeAt(breaks.size - 1)
                    onChange(draft.copy(classicBreakMinutes = breaks))
                }
            },
            onPlus = {
                val breaks = draft.classicBreakMinutes.toMutableList()
                if (breaks.size < draft.classicFocusMinutes.size) {
                    breaks.add(5)
                    onChange(draft.copy(classicBreakMinutes = breaks))
                }
            },
        )
        draft.classicBreakMinutes.forEachIndexed { i, minutes ->
            StepperRow(
                label = "Break ${i + 1}",
                value = "$minutes min",
                onMinus = {
                    val breaks = draft.classicBreakMinutes.toMutableList()
                    breaks[i] = (breaks[i] - 1).coerceIn(1, 60)
                    onChange(draft.copy(classicBreakMinutes = breaks))
                },
                onPlus = {
                    val breaks = draft.classicBreakMinutes.toMutableList()
                    breaks[i] = (breaks[i] + 1).coerceIn(1, 60)
                    onChange(draft.copy(classicBreakMinutes = breaks))
                },
            )
        }
        Text(
            "A break follows each block while there are breaks left — one after the final block is fine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A labeled ± stepper row (the iOS Stepper, regridded for a sheet). */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { Haptics.tap(context); onMinus() }) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease $label")
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { Haptics.tap(context); onPlus() }) {
            Icon(Icons.Default.Add, contentDescription = "Increase $label")
        }
    }
}

/** One-shot ± for the duration row (step 5 min, iOS Stepper parity). */
@Composable
private fun StepperRow(label: String, value: String, onStep: (Int) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { Haptics.tap(context); onStep(-5) }) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease $label")
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { Haptics.tap(context); onStep(5) }) {
            Icon(Icons.Default.Add, contentDescription = "Increase $label")
        }
    }
}

private fun durationText(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

// MARK: - Gentle top banner (app-wide)

/**
 * The root-window Pomodoro banner — a soft floating card under the status
 * bar, across every tab. Deliberately NOT an alert: thin material, a
 * hairline tint stroke, non-blocking, dismissible by tap. This surface also
 * owns the app-wide one-second tick (phase transitions must surface from
 * any tab, not just Stats) and the foreground reconcile after suspension —
 * the tick IS the reconcile (wall-clock math).
 */
@Composable
fun PomodoroBannerOverlay() {
    val context = LocalContext.current
    val banner by PomodoroStore.banner.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            PomodoroStore.tick()
        }
    }
    // Transitions speak with a haptic (iOS store-side Haptics.tap/success —
    // the Android store is UI-free, so the overlay speaks for it).
    LaunchedEffect(banner?.id) {
        when (banner?.kind) {
            PomodoroBanner.Kind.RESUME_FOCUS -> Haptics.tap(context)
            PomodoroBanner.Kind.BLOCK_COMPLETE, PomodoroBanner.Kind.SESSION_COMPLETE ->
                Haptics.success(context)
            else -> Unit
        }
    }

    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        banner?.let { PomodoroBannerCard(it) }
    }
}

@Composable
private fun PomodoroBannerCard(banner: PomodoroBanner) {
    val context = LocalContext.current
    val tint = tintOf(banner.tint)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f))
            .clickable { Haptics.tap(context); PomodoroStore.dismissBanner() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            banner.icon,
            fontSize = 18.sp,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f))
                .wrapContentSize(Alignment.Center),
        )
        Column(Modifier.weight(1f)) {
            Text(banner.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            banner.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        banner.actionTitle?.let { action ->
            if (banner.kind == PomodoroBanner.Kind.CLAIM_AVAILABLE) {
                TextButton(onClick = {
                    Haptics.tap(context)
                    PomodoroStore.claimBreak()
                }) {
                    Text(action, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tint)
                }
            }
        }
        IconButton(onClick = { Haptics.tap(context); PomodoroStore.dismissBanner() }) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Shared palette + imports helpers

private val TintOrange = Color(0xFFF59E0B)
private val TintGreen = Color(0xFF22A55B)
private val TintBlue = Color(0xFF2E7CF6)

private fun tintOf(tint: PomodoroTint): Color = when (tint) {
    PomodoroTint.ORANGE -> TintOrange
    PomodoroTint.GREEN -> TintGreen
    PomodoroTint.BLUE -> TintBlue
    PomodoroTint.GRAY -> Color(0xFF8E8E93)
}
