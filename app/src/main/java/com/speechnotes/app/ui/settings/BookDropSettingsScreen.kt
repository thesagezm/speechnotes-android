package com.speechnotes.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.BookDropRegistry
import com.speechnotes.app.data.Prefs
import com.speechnotes.app.logic.BookDropRecord

/** BookDrop subpage: nearby-receiving toggle, auto-accept, transfer history. */
@Composable
fun BookDropSettingsScreen(onBack: () -> Unit) {
    SettingsSubpage(title = "BookDrop", onBack = onBack) {
        BookDropCard()
    }
}

@Composable
private fun BookDropCard() {
    val receiver = BookDropRegistry.receiver ?: return
    val context = LocalContext.current
    val isRunning by receiver.isRunning.collectAsState()
    val port by receiver.port.collectAsState()
    val lastError by receiver.lastError.collectAsState()
    val history by receiver.history.collectAsState()

    // The persisted toggle is the source of truth (iOS @AppStorage parity);
    // flips write through and start/stop the server immediately.
    var enabled by remember {
        mutableStateOf(
            context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getBoolean(Prefs.BOOK_DROP_ENABLED, false),
        )
    }
    var autoAccept by remember {
        mutableStateOf(
            context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getBoolean(Prefs.BOOK_DROP_AUTO_ACCEPT, true),
        )
    }

    fun setEnabled(to: Boolean) {
        enabled = to
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.BOOK_DROP_ENABLED, to).apply()
        if (to) receiver.start() else receiver.stop()
    }

    SettingsCard("BookDrop") {
        ToggleRow(
            title = "Nearby BookDrop",
            subtitle = if (isRunning) "Visible on this Wi-Fi · port $port" else "Receive books and notes from LocalSend on your network",
            checked = enabled,
            onCheckedChange = ::setEnabled,
        )
        if (enabled) {
            ToggleRow(
                title = "Accept transfers automatically",
                subtitle = "Off = the sender is refused until this is back on",
                checked = autoAccept,
                onCheckedChange = { to ->
                    autoAccept = to
                    receiver.autoAccept = to
                    context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(Prefs.BOOK_DROP_AUTO_ACCEPT, to).apply()
                },
            )
            lastError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Text(
                "Recent transfers",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val rows = history.take(5)
            if (rows.isEmpty()) {
                Text(
                    "Nothing yet. Send from LocalSend or Readest on a PC — books land on the shelf, .jex into notes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                rows.forEach { record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val (icon, tint) = when (record.outcome) {
                            is BookDropRecord.Outcome.Imported ->
                                Icons.Default.CheckCircle to Color(0xFF2E7D32)
                            is BookDropRecord.Outcome.Failed ->
                                Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
                            is BookDropRecord.Outcome.Rejected ->
                                Icons.Default.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(record.name, style = MaterialTheme.typography.bodyMedium)
                            val detail = when (val outcome = record.outcome) {
                                is BookDropRecord.Outcome.Imported -> outcome.detail
                                is BookDropRecord.Outcome.Failed -> outcome.detail
                                is BookDropRecord.Outcome.Rejected -> "Rejected"
                            }
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
