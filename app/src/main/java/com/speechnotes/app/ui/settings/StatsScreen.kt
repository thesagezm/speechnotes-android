package com.speechnotes.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.data.StatsStore
import com.speechnotes.app.model.Book
import com.speechnotes.app.model.GoalMath
import com.speechnotes.app.model.GoalProgress
import com.speechnotes.app.model.ReadingGoal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Stats & goals subpage — the pollers (60 s stats reload, 5 s goals refresh)
 * only run while this page is open, instead of for the lifetime of the
 * Settings tab as the old flat screen did.
 */
@Composable
fun StatsScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    SettingsSubpage(title = "Stats & goals", onBack = onBack) {
        StatsCard(vm)
    }
}

@Composable
private fun StatsCard(vm: SpeechnotesViewModel) {
    // Reload whenever this card recomposes after a resume: keys on the
    // current minute so an open settings screen refreshes too.
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60_000)
        reloadKey++
        while (true) {
            kotlinx.coroutines.delay(60_000)
            reloadKey++
        }
    }
    var today by remember { mutableDoubleStateOf(0.0) }
    var streak by remember { mutableIntStateOf(0) }
    var weekTotal by remember { mutableDoubleStateOf(0.0) }
    var monthTotal by remember { mutableDoubleStateOf(0.0) }
    var readingTotal by remember { mutableDoubleStateOf(0.0) }
    var listeningTotal by remember { mutableDoubleStateOf(0.0) }
    var heatmap by remember { mutableStateOf<Map<String, StatsStore.DayTotals>>(emptyMap()) }

    LaunchedEffect(reloadKey) {
        val store = StatsStore
        today = store.totals(daysBack = 1)
        streak = store.streak()
        weekTotal = store.totals(daysBack = 7)
        monthTotal = store.totals(daysBack = 30)
        readingTotal = store.totals(daysBack = 30, kind = StatsStore.Kind.READING)
        listeningTotal = store.totals(daysBack = 30, kind = StatsStore.Kind.LISTENING)
        heatmap = store.dayTotals(daysBack = 18 * 7)
        // E1c: refresh goal completion off the books' live positions.
        vm.goals.refreshCompletion { goal ->
            vm.books.book(goal.bookId)?.let { GoalMath.fraction(it) }
        }
    }

    SettingsCard("Stats") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatHeadline("${formatHours(today)}", "today")
            StatHeadline("$streak", if (streak == 1) "day streak" else "day streak")
            StatHeadline("${formatHours(weekTotal)}", "this week")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Last 30 days · ${formatHours(monthTotal)} (read ${formatHours(readingTotal)} · listened ${formatHours(listeningTotal)})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        StatsHeatmap(heatmap)
        Spacer(Modifier.height(10.dp))
        GoalsSection(vm)
    }
}

/** E1c: the goals list — derived progress, status pill, editor sheet. */
@Composable
private fun GoalsSection(vm: SpeechnotesViewModel) {
    val goals by produceState<List<ReadingGoal>>(emptyList(), vm) {
        while (true) {
            value = vm.goals.all()
            kotlinx.coroutines.delay(5_000)
        }
    }
    var showEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ReadingGoal?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Goals", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { showEditor = true }) { Text("Add") }
    }
    if (goals.isEmpty()) {
        Text(
            "Pick a book, choose a finish date, and watch the bar fill.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            goals.forEach { goal ->
                val book = vm.books.book(goal.bookId)
                val progress = GoalMath.progress(goal, book)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editTarget = goal },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                goal.bookTitle,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${((progress.fraction ?: 0.0) * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (progress.fraction ?: 0.0).toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text(
                                when (progress.status) {
                                    GoalProgress.Status.DONE -> "🎉 Done"
                                    GoalProgress.Status.AHEAD -> "🔥 Ahead"
                                    GoalProgress.Status.ON_TRACK -> "On track"
                                    GoalProgress.Status.BEHIND -> "Behind"
                                    GoalProgress.Status.OVERDUE -> "Overdue"
                                    GoalProgress.Status.NO_BOOK -> "Book removed"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (progress.status) {
                                    GoalProgress.Status.DONE -> Color(0xFFC9A227)
                                    GoalProgress.Status.AHEAD -> Color(0xFF2E7D32)
                                    GoalProgress.Status.OVERDUE -> MaterialTheme.colorScheme.error
                                    GoalProgress.Status.BEHIND -> Color(0xFFB26A00)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                progress.unitLabel ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (progress.daysLeft <= 0) " · now" else " · ${progress.daysLeft}d left",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        GoalEditorSheet(
            vm = vm,
            onDismiss = { showEditor = false },
        )
    }
    editTarget?.let { target ->
        GoalEditorSheet(
            vm = vm,
            existing = target,
            onDismiss = { editTarget = null },
            onDelete = {
                vm.removeGoal(target.id)
                editTarget = null
            },
        )
    }
}

/** Create/edit sheet: book picker (create only) + start/deadline dates. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditorSheet(
    vm: SpeechnotesViewModel,
    existing: ReadingGoal? = null,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val books by vm.books.books.collectAsState()
    var selectedBook by remember { mutableStateOf<Book?>(null) }
    var startDate by remember {
        mutableLongStateOf(System.currentTimeMillis())
    }
    var deadline by remember {
        mutableLongStateOf(System.currentTimeMillis() + 30L * 86_400_000L)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                if (existing == null) "New Reading Goal" else "Edit Schedule",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            if (existing == null) {
                Text("Book", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(books, key = { it.id }) { book ->
                        ListItem(
                            headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                RadioButton(
                                    selected = selectedBook?.id == book.id,
                                    onClick = { selectedBook = book },
                                )
                            },
                            modifier = Modifier.clickable { selectedBook = book },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Start reading: ${java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(startDate))}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = ((startDate - System.currentTimeMillis()).coerceAtLeast(0L)).toFloat() / (30f * 86_400_000f),
                onValueChange = {
                    startDate = System.currentTimeMillis() + (it * 30f * 86_400_000f).toLong()
                },
            )
            Text("Finish by: ${java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(deadline))}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = ((deadline - startDate).coerceAtLeast(86_400_000L)).toFloat() / (365f * 86_400_000f),
                onValueChange = {
                    deadline = startDate + maxOf(86_400_000L, (it * 365f * 86_400_000f).toLong())
                },
            )
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                onDelete?.let { delete ->
                    TextButton(onClick = delete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                Button(
                    onClick = {
                        if (existing != null) {
                            vm.updateGoal(existing.id, startDate, deadline)
                        } else {
                            selectedBook?.let { vm.addGoal(it, startDate, deadline) }
                        }
                        onDismiss()
                    },
                    enabled = existing != null || selectedBook != null,
                ) { Text(if (existing == null) "Create Goal" else "Save") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatHeadline(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** GitHub-style heatmap: 18 weeks × 7 day cells, reading+listening shaded. */
@Composable
private fun StatsHeatmap(data: Map<String, StatsStore.DayTotals>) {
    val today = LocalDate.now()
    val cells = ArrayList<Pair<LocalDate, Double>>(18 * 7)
    val start = today.minusDays(((18 * 7) - 1).toLong())
    for (i in 0 until 18 * 7) {
        val day = start.plusDays(i.toLong())
        cells.add(day to (data[DateTimeFormatter.ofPattern("yyyy-MM-dd").format(day)]?.total ?: 0.0))
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (week in 0 until 18) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (dayOfWeek in 0 until 7) {
                    val (day, seconds) = cells[week * 7 + dayOfWeek]
                    val future = day.isAfter(today)
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(
                                when {
                                    future -> Color.Transparent
                                    seconds <= 0 -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    seconds < 300 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    seconds < 1800 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    seconds < 3600 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}
