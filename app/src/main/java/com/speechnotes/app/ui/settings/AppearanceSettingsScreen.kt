package com.speechnotes.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.ui.theme.AccentChoice
import com.speechnotes.app.ui.theme.AccentSwatch
import com.speechnotes.app.ui.theme.AppearanceMode
import com.speechnotes.app.ui.theme.ReaderTheme
import com.speechnotes.app.ui.theme.ThemeState

/** Appearance subpage: accent, light/dark, reader theme, text scale, spacing. */
@Composable
fun AppearanceSettingsScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    SettingsSubpage(title = "Appearance", onBack = onBack) {
        AccentRow(vm)
        AppearanceRow(vm)
        ReaderThemeRow(vm)
        TextScaleRow(vm)
        SpacingSliders(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccentRow(vm: SpeechnotesViewModel) {
    val theme by vm.themeState.collectAsState()
    var show by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Accent color", fontWeight = FontWeight.Medium) },
        supportingContent = { Text(theme.accent.label) },
        trailingContent = {
            Surface(
                shape = CircleShape,
                color = accentPreviewColor(theme),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(28.dp),
            ) {}
        },
        modifier = Modifier.clickable { show = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )

    if (show) {
        // A bottom sheet (not an AlertDialog): the swatch grid scrolls with
        // the sheet's own gesture, so all 13 choices are reachable.
        ModalBottomSheet(onDismissRequest = { show = false }) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Accent color", style = MaterialTheme.typography.titleLarge)
                Text(
                    "System follows your wallpaper; the others re-tint the whole app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 28.dp),
                ) {
                    item(span = { GridItemSpan(3) }) {
                        AccentSwatchCell(
                            label = "System",
                            selected = theme.accent == AccentChoice.SYSTEM,
                            color = null,
                        ) { vm.setAccent(AccentChoice.SYSTEM); show = false }
                    }
                    items(AccentChoice.entries.filter { it != AccentChoice.SYSTEM }) { choice ->
                        AccentSwatchCell(
                            label = choice.label,
                            selected = theme.accent == choice,
                            color = AccentSwatch[choice],
                        ) { vm.setAccent(choice); show = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentSwatchCell(label: String, selected: Boolean, color: Color?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .then(
                    if (color != null) Modifier.background(color, CircleShape)
                    else Modifier.background(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color(0xFF0B57D0), Color(0xFF7C3AED), Color(0xFFDB2777),
                                Color(0xFFEA580C), Color(0xFF16A34A), Color(0xFF0891B2), Color(0xFF0B57D0),
                            )
                        ),
                        shape = CircleShape,
                    )
                )
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    shape = CircleShape,
                )
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun accentPreviewColor(theme: ThemeState): Color = AccentSwatch[theme.accent]
    ?: MaterialTheme.colorScheme.primary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceRow(vm: SpeechnotesViewModel) {
    val theme by vm.themeState.collectAsState()
    ListItem(
        headlineContent = { Text("Appearance", fontWeight = FontWeight.Medium) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                AppearanceMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = theme.appearance == mode,
                        onClick = { vm.setAppearance(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppearanceMode.entries.size),
                        label = {
                            Text(
                                when (mode) {
                                    AppearanceMode.SYSTEM -> "System"
                                    AppearanceMode.LIGHT -> "Light"
                                    AppearanceMode.DARK -> "Dark"
                                }
                            )
                        },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun TextScaleRow(vm: SpeechnotesViewModel) {
    val theme by vm.themeState.collectAsState()
    ListItem(
        headlineContent = { Text("Reading text scale · ${Math.round(theme.previewTextScale * 100)}%", fontWeight = FontWeight.Medium) },
        supportingContent = {
            Slider(
                value = theme.previewTextScale,
                onValueChange = { vm.setPreviewTextScale(it) },
                valueRange = 0.75f..1.5f,
                steps = 14,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderThemeRow(vm: SpeechnotesViewModel) {
    val theme by vm.themeState.collectAsState()
    ListItem(
        headlineContent = { Text("Reader theme", fontWeight = FontWeight.Medium) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                ReaderTheme.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = theme.readerTheme == mode,
                        onClick = { vm.setReaderTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ReaderTheme.entries.size),
                        label = {
                            // Every non-sepia/dark value used to collapse to
                            // "White" — TRUE_BLACK was unreachable as a label.
                            Text(
                                when (mode) {
                                    ReaderTheme.SEPIA -> "Sepia"
                                    ReaderTheme.DARK -> "Dark"
                                    ReaderTheme.TRUE_BLACK -> "True black"
                                    ReaderTheme.WHITE -> "White"
                                }
                            )
                        },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SpacingSliders(vm: SpeechnotesViewModel) {
    val theme by vm.themeState.collectAsState()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("Line spacing · ${Math.round(theme.readerLineSpacing * 100)}%", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = theme.readerLineSpacing.toFloat(),
            onValueChange = { vm.setReaderSpacing(line = it.toDouble()) },
            valueRange = 0.8f..1.6f,
            steps = 7,
        )
        Text("Between blocks · ${Math.round(theme.readerBlockSpacing * 100)}%", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = theme.readerBlockSpacing.toFloat(),
            onValueChange = { vm.setReaderSpacing(block = it.toDouble()) },
            valueRange = 0.8f..1.6f,
            steps = 7,
        )
        Text("Tables · ${Math.round(theme.readerTableSpacing * 100)}%", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = theme.readerTableSpacing.toFloat(),
            onValueChange = { vm.setReaderSpacing(table = it.toDouble()) },
            valueRange = 0.8f..2.0f,
            steps = 11,
        )
        TextButton(onClick = { vm.resetReaderSpacing() }) { Text("Reset spacing") }
    }
}
