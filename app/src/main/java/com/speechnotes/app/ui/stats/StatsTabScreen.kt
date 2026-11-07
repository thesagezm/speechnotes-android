package com.speechnotes.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.data.StatsStore
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.model.Book
import com.speechnotes.app.model.GoalMath
import com.speechnotes.app.model.GoalProgress
import com.speechnotes.app.model.ReadingGoal
import com.speechnotes.app.ui.settings.SettingsCard
import com.speechnotes.app.ui.settings.formatHours
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Stats TAB (iOS StatsTabView parity) — promoted out of Settings. Goals
 * live at the top regardless of data, then a Today hero (large hours
 * headline + streak chip), three range header cards, a Week/Month/Year
 * picker driving grouped reading-vs-listening bars, the Mon-first 18-week
 * heatmap and the top-8 Subjects card with per-book progress. The pollers
 * (60 s stats reload, 5 s goals refresh) only run while the tab is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsTabScreen(vm: SpeechnotesViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Stats") })
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            StatsBody(vm)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private enum class StatsRange(val label: String, val daysBack: Int) {
    WEEK("Week", 7),
    MONTH("Month", 30),
    YEAR("Year", 365),
}

/** One chart bucket: a day or month with both kinds, minutes for the bars. */
private data class Bucket(
    val label: String,
    val readingMinutes: Double,
    val listeningMinutes: Double,
)

@Composable
private fun StatsBody(vm: SpeechnotesViewModel) {
    // Reload whenever this tab recomposes after a resume: keys on the
    // current minute so a long-open tab refreshes too.
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60_000)
        reloadKey++
        while (true) {
            kotlinx.coroutines.delay(60_000)
            reloadKey++
        }
    }

    var range by remember { mutableStateOf(StatsRange.WEEK) }
    var today by remember { mutableDoubleStateOf(0.0) }
    var streak by remember { mutableIntStateOf(0) }
    var hasAnyData by remember { mutableStateOf(false) }
    var rangeTotal by remember { mutableDoubleStateOf(0.0) }
    var bestDay by remember { mutableDoubleStateOf(0.0) }
    var buckets by remember { mutableStateOf<List<Bucket>>(emptyList()) }
    var heatmap by remember { mutableStateOf<Map<String, StatsStore.DayTotals>>(emptyMap()) }
    var subjects by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }

    LaunchedEffect(reloadKey, range) {
        val store = StatsStore
        today = store.totals(daysBack = 1)
        streak = store.streak()
        hasAnyData = store.activeDays() > 0
        // One window covers the charts (≤ 1 year) and the heatmap (18 weeks).
        val days = store.dayTotals(daysBack = 366)
        heatmap = days
        rangeTotal = days.entries
            .filter { it.key >= LocalDate.now().minusDays((range.daysBack - 1).toLong()).format(dayKeyFormat) }
            .sumOf { it.value.total }
        bestDay = days.values.maxOfOrNull { it.total } ?: 0.0
        buckets = when (range) {
            StatsRange.WEEK -> dayBuckets(7, days)
            StatsRange.MONTH -> dayBuckets(30, days)
            StatsRange.YEAR -> monthBuckets(days)
        }
        subjects = store.subjectTotals(daysBack = range.daysBack)
            .entries.sortedByDescending { it.value }.take(8)
            .map { it.key to it.value }
        // E1c: refresh goal completion off the books' live positions.
        vm.goals.refreshCompletion { goal ->
            vm.books.book(goal.bookId)?.let { GoalMath.fraction(it) }
        }
    }

    // Goals first (iOS parity: a fresh install can set a goal before the
    // charts have anything to chart), then the Pomodoro section directly
    // under it (iOS StatsTabView's PomodoroCard placement).
    SettingsCard("Reading goals") {
        GoalsSection(vm)
    }

    Spacer(Modifier.height(12.dp))
    SettingsCard("Pomodoro") {
        PomodoroCard()
    }

    if (!hasAnyData) {
        SettingsCard("Stats") {
            EmptyStats()
        }
        return
    }

    // Today hero: the day's number is the headline of the tab, with the
    // streak riding beside it instead of hiding in a small card.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Today",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                formatHours(today),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        StreakChip(streak)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeaderCard("${range.label} total", formatHours(rangeTotal), Modifier.weight(1f))
        HeaderCard("Best day", formatHours(bestDay), Modifier.weight(1f))
    }

    Spacer(Modifier.height(12.dp))
    SettingsCard("Activity") {
        RangePicker(range) { range = it }
        Spacer(Modifier.height(14.dp))
        ActivityBars(buckets)
        Spacer(Modifier.height(8.dp))
        LegendRow()
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Text("Last 18 weeks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        StatsHeatmap(heatmap)
        Spacer(Modifier.height(6.dp))
    }

    Spacer(Modifier.height(12.dp))
    SettingsCard("Subjects") {
        SubjectsCard(vm, subjects)
    }
}

@Composable
private fun StreakChip(streak: Int) {
    val color = if (streak > 0) Color(0xFFC9A227) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (streak > 0) "$streak day${if (streak == 1) "" else "s"}" else "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptyStats() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Insights,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("Nothing to chart yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Read a book or listen to a note — your time shows up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RangePicker(range: StatsRange, onSelect: (StatsRange) -> Unit) {
    val haptics = LocalHapticFeedback.current
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        StatsRange.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = range == option,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onSelect(option)
                },
                shape = SegmentedButtonDefaults.itemShape(index, StatsRange.entries.size),
                label = { Text(option.label) },
            )
        }
    }
}

@Composable
private fun HeaderCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Day buckets for the last [count] days, oldest first (week/month chart). */
private fun dayBuckets(count: Int, days: Map<String, StatsStore.DayTotals>): List<Bucket> {
    val today = LocalDate.now()
    return (count - 1 downTo 0).map { offset ->
        val day = today.minusDays(offset.toLong())
        val totals = days[dayKeyFormat.format(day)] ?: StatsStore.DayTotals()
        Bucket(
            label = day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            readingMinutes = totals.reading / 60.0,
            listeningMinutes = totals.listening / 60.0,
        )
    }
}

/** Month buckets for the last 12 months, oldest first (year chart). */
private fun monthBuckets(days: Map<String, StatsStore.DayTotals>): List<Bucket> {
    val now = LocalDate.now()
    return (11 downTo 0).map { offset ->
        val month = now.minusMonths(offset.toLong())
        var reading = 0.0
        var listening = 0.0
        for ((key, totals) in days) {
            val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
            if (day.year == month.year && day.month == month.month) {
                reading += totals.reading
                listening += totals.listening
            }
        }
        Bucket(
            label = month.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            readingMinutes = reading / 60.0,
            listeningMinutes = listening / 60.0,
        )
    }
}

/**
 * Grouped bars (iOS: reading = accent, listening = accent 45%), one group
 * per bucket. Few buckets render in place; 30 month-day buckets scroll
 * horizontally (iOS chartScrollableAxes parity). Zero-value kinds draw no
 * bar, like Swift Charts.
 */
@Composable
private fun ActivityBars(buckets: List<Bucket>) {
    if (buckets.isEmpty()) return
    val maxMinutes = buckets.maxOf { maxOf(it.readingMinutes, it.listeningMinutes) }
        .coerceAtLeast(1.0)
    val barMaxHeight = 120.dp
    // >12 groups (the 30-day month) scrolls like the iOS month chart;
    // week (7) and year (12) fit in place.
    val scroll = buckets.size > 12
    val primary = MaterialTheme.colorScheme.primary
    val listeningColor = primary.copy(alpha = 0.45f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    @Composable
    fun barFor(minutes: Double, color: Color) {
        val h = if (minutes <= 0.0) 0.dp
        else (minutes / maxMinutes * barMaxHeight.value).dp.coerceAtLeast(3.dp)
        Box(
            Modifier
                .width(9.dp)
                .height(h)
                .background(color, RoundedCornerShape(2.dp)),
        )
    }

    @Composable
    fun bucketColumn(bucket: Bucket, showLabel: Boolean, labelWidth: Int) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(labelWidth.dp)) {
            Box(Modifier.height(barMaxHeight), contentAlignment = Alignment.BottomStart) {
                Row(verticalAlignment = Alignment.Bottom) {
                    barFor(bucket.readingMinutes, primary)
                    Spacer(Modifier.width(3.dp))
                    barFor(bucket.listeningMinutes, listeningColor)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                bucket.label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                maxLines = 1,
                modifier = Modifier.alpha(if (showLabel) 1f else 0f),
            )
        }
    }

    if (scroll) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            buckets.forEachIndexed { index, bucket ->
                // In the scrolling 30-day row, only today's group is labeled.
                bucketColumn(bucket, showLabel = index == buckets.lastIndex, labelWidth = 22)
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            buckets.forEach { bucket -> bucketColumn(bucket, showLabel = true, labelWidth = 24) }
        }
    }
}

@Composable
private fun LegendRow() {
    val primary = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendDot("Reading", primary)
        LegendDot("Listening", primary.copy(alpha = 0.45f))
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 18-week heatmap, Mon-first columns, oldest week first, future cells
 * empty (iOS heatmapWeeks parity — the old grid was 126 sequential days,
 * so weeks straddled columns). Levels match the iOS thresholds.
 */
@Composable
private fun StatsHeatmap(data: Map<String, StatsStore.DayTotals>) {
    val today = LocalDate.now()
    val weekdayOffset = (today.dayOfWeek.value + 6) % 7 // Mon = 0
    val currentWeekStart = today.minusDays(weekdayOffset.toLong())
    val firstWeekStart = currentWeekStart.minusDays((17 * 7).toLong())
    val primary = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val futureColor = Color.Transparent
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (week in 0 until 18) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (dayOfWeek in 0 until 7) {
                    val day = firstWeekStart.plusDays((week * 7 + dayOfWeek).toLong())
                    val future = day.isAfter(today)
                    val minutes = (data[dayKeyFormat.format(day)]?.total ?: 0.0) / 60.0
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(
                                when {
                                    future -> futureColor
                                    minutes < 1 -> emptyColor
                                    minutes < 15 -> primary.copy(alpha = 0.25f)
                                    minutes < 30 -> primary.copy(alpha = 0.45f)
                                    minutes < 60 -> primary.copy(alpha = 0.7f)
                                    else -> primary
                                },
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

/** Top subjects by time in the selected range, with per-book progress (iOS subjectsCard parity). */
@Composable
private fun SubjectsCard(vm: SpeechnotesViewModel, subjects: List<Pair<String, Double>>) {
    val books by vm.books.books.collectAsState()
    val notes by vm.notes.notes.collectAsState()
    if (subjects.isEmpty()) {
        Text(
            "Nothing in this range yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        return
    }
    Column {
        subjects.forEachIndexed { index, (subjectId, seconds) ->
            if (index > 0) {
                androidx.compose.material3.HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            val book = books.firstOrNull { it.id == subjectId }
            val note = notes.firstOrNull { it.id == subjectId }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                SubjectLead(book)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        book?.title ?: note?.title ?: "Removed item",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            book != null -> book.author?.ifBlank { "Book" } ?: "Book"
                            note != null -> "Note"
                            else -> "Deleted"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    // Books with position data get a progress capsule; notes
                    // and audiobooks (no chapter fraction) don't (iOS parity).
                    if (book != null) {
                        GoalMath.fraction(book)?.let { fraction ->
                            LinearProgressIndicator(
                                progress = { fraction.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                }
                Text(
                    formatHours(seconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SubjectLead(book: Book?) {
    if (book == null) {
        Icon(
            Icons.Default.Notes,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp),
        )
        return
    }
    // Look for the cover on disk, not the manifest flag — same rule as the
    // shelf (a cover written after the manifest must still show).
    val cover = remember(book.id) { AppFiles.bookCoverFile(book.id) }
    if (cover.isFile) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            modifier = Modifier.size(width = 26.dp, height = 38.dp).background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(4.dp),
            ),
        )
    } else {
        Icon(
            Icons.Default.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp),
        )
    }
}

private val dayKeyFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

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
