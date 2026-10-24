package com.speechnotes.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.LogStore
import com.speechnotes.app.data.SpeechnotesViewModel
import kotlinx.coroutines.delay

/**
 * The session log viewer (LogStore's ring buffer). Polls the buffer so new
 * entries stream in, filters by level, and copies/shares the whole log —
 * the "no logs? really?" fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    var entries by remember { mutableStateOf(LogStore.snapshot()) }
    var filter by remember { mutableStateOf<LogStore.Level?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // The ring buffer isn't observable; a fast poll is cheap (≤400 entries)
    // and keeps the screen live while engines log chunk timings.
    LaunchedEffect(Unit) {
        while (true) {
            entries = LogStore.snapshot()
            delay(400)
        }
    }

    val visible = remember(entries, filter) {
        if (filter == null) entries else entries.filter { it.level == filter }
    }

    // Follow the tail unless the user scrolled up to inspect history.
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty() && listState.firstVisibleItemIndex >= visible.size - 3) {
            listState.animateScrollToItem(visible.size - 1)
        }
    }

    val text = remember(visible) { LogStore.exportText() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = { shareLogs(vm, text) }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Share logs")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (visible.isEmpty()) {
                Text(
                    "No log entries yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        FilterChip(
                            selected = filter == null,
                            onClick = { filter = null },
                            label = { Text("All") },
                        )
                        LogStore.Level.entries.forEach { level ->
                            FilterChip(
                                selected = filter == level,
                                onClick = { filter = if (filter == level) null else level },
                                label = { Text(level.name) },
                            )
                        }
                    }
                }
                items(visible) { entry ->
                    Text(
                        text = format(entry),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = levelColor(entry.level),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private fun format(entry: LogStore.Entry): String {
    val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(entry.timestamp))
    return "$ts ${entry.level.name.first()} ${entry.message}"
}

private fun levelColor(level: LogStore.Level): Color = when (level) {
    LogStore.Level.DEBUG -> Color(0xFF8794A8)
    LogStore.Level.INFO -> Color(0xFF1B6E3C)
    LogStore.Level.WARN -> Color(0xFF9A6700)
    LogStore.Level.ERROR -> Color(0xFFB3261E)
}

private fun shareLogs(vm: SpeechnotesViewModel, text: String) {
    val file = java.io.File(com.speechnotes.app.logic.AppFiles.exportsDir, "speechnotes-logs.txt")
    runCatching {
        file.parentFile?.mkdirs()
        file.writeText(text)
        vm.models.share(file)
    }
}
