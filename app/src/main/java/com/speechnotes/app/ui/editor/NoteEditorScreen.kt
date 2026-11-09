package com.speechnotes.app.ui.editor
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.background

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speechnotes.app.data.SpeechnotesViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.speechnotes.app.logic.MarkdownFormatActions
import com.speechnotes.app.logic.MarkdownSlashMenu
import com.speechnotes.app.logic.NotePdfLayout
import com.speechnotes.app.logic.SpeechText
import com.speechnotes.app.model.Note
import com.speechnotes.app.speech.SpeechState
import com.speechnotes.app.ui.markdown.MarkdownStyledText
import com.speechnotes.app.ui.readalong.ReadAlongScreen
import com.speechnotes.app.ui.voice.VoicePickerSheet
import java.io.File

/**
 * Selection-aware formatting bar pinned above the player controls while
 * editing a markdown note (port of MarkdownFormattingBar.swift). The passed
 * [draft] is the committed text; every button mutates it, then hands the
 * full (text, caret, selection) triple back so the caller's draft state
 * updates in one shot — no partially-updated intermediate states.
 * SELECTION CONVENTION: [selection] is an IntRange whose `last` is the
 * EXCLUSIVE end — that is what Compose's `TextRange.end` is, and that is
 * what the editor hands over. The old code read it as an INCLUSIVE end in
 * every selection-reading path, which (a) duplicated the character right
 * after the selection, (b) replaced a selection that touched the end of the
 * note with the placeholder word, and (c) CRASHED the list/quote buttons on
 * that same selection — `substring` past the end of the text.
 *
 * LAYOUT: the actions WRAP onto a second row instead of scrolling
 * sideways. iOS uses a horizontal ScrollView, but on a 360dp phone that
 * hides eight of the thirteen actions behind a scroll nobody discovers —
 * the user's "the formatting toolbar has to fit narrow screens". Wrapping
 * keeps every action reachable, and on a wide screen it still lays out as
 * a single row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkdownFormattingBar(
    draft: String,
    caretOffset: Int,
    selection: IntRange?,
    enabled: Boolean = true,
    onInsertImage: () -> Unit,
    onEdit: (newText: String, caret: Int, newSelection: IntRange?) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    /** Every action ticks once (iOS `Haptics.tap()`) — feedback must land
     *  on the tap, not after the text has been rewritten. */
    fun tap(action: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        action()
    }

    /**
     * Commit one action's result in ONE edit (iOS's single-mutate contract;
     * see [com.speechnotes.app.logic.MarkdownFormatActions] for why the
     * selection convention matters).
     */
    fun commit(result: MarkdownFormatActions.Result) {
        onEdit(result.text, result.caret, result.selection)
    }

    fun mutate(range: IntRange, replacement: String) =
        commit(MarkdownFormatActions.mutate(draft, range, replacement))

    /** Wrap the selection (or insert a placeholder) with the markers. */
    fun wrap(prefix: String, suffix: String, placeholder: String) =
        commit(MarkdownFormatActions.wrap(draft, caretOffset, selection, prefix, suffix, placeholder))

    /** Toggle the heading marker on the caret's line. */
    fun applyHeading(level: Int) = commit(MarkdownFormatActions.applyHeading(draft, caretOffset, level))

    /** Toggle the marker on every line of the selection, or on the caret's line. */
    fun applyList(marker: String) =
        commit(MarkdownFormatActions.applyList(draft, caretOffset, selection, marker))

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FormatButton(if (MarkdownFormatActions.isWrapped(draft, selection, "**")) "off" else "on", 'B', "Bold", { tap { wrap("**", "**", "bold") } }, enabled)
        FormatButton(if (MarkdownFormatActions.isWrapped(draft, selection, "*")) "off" else "on", 'I', "Italic", { tap { wrap("*", "*", "italic") } }, enabled)
        FormatButton(if (MarkdownFormatActions.isWrapped(draft, selection, "~~")) "off" else "on", 'S', "Strikethrough", { tap { wrap("~~", "~~", "strike") } }, enabled)
        FormatDivider()
        FormatTextButton("H1", enabled) { tap { applyHeading(1) } }
        FormatTextButton("H2", enabled) { tap { applyHeading(2) } }
        FormatDivider()
        FormatIconButton(Icons.Filled.FormatListBulleted, "Bulleted list", enabled) { tap { applyList("- ") } }
        FormatIconButton(Icons.Filled.FormatListNumbered, "Numbered list", enabled) { tap { applyList("1. ") } }
        FormatIconButton(Icons.Filled.FormatQuote, "Block quote", enabled) { tap { applyList("> ") } }
        FormatDivider()
        FormatIconButton(Icons.Filled.Code, "Inline code", enabled) { tap { wrap("`", "`", "code") } }
        FormatIconButton(Icons.Filled.DataObject, "Code block", enabled) {
            tap {
                val body = MarkdownFormatActions.selectedText(draft, selection).ifBlank { "code" }
                val range = selection ?: (caretOffset..caretOffset)
                mutate(range, "\n```\n$body\n```\n")
            }
        }
        FormatIconButton(Icons.Filled.HorizontalRule, "Horizontal rule", enabled) {
            tap { mutate(caretOffset..caretOffset, "\n---\n") }
        }
        FormatDivider()
        FormatIconButton(Icons.Filled.Link, "Insert link", enabled) {
            tap {
                val range = selection ?: (caretOffset..caretOffset)
                val label = MarkdownFormatActions.selectedText(draft, selection).ifBlank { "label" }
                mutate(range, "[$label](https://)")
            }
        }
        FormatIconButton(Icons.Filled.Image, "Insert image", enabled) { tap { onInsertImage() } }
    }
}

/** True when the selected text is already wrapped by [marker] on both ends. */
private fun selectedTextWrapped(text: String, selection: IntRange?, marker: String): Boolean {
    val sel = selection ?: return false
    if (sel.first < marker.length || sel.last + marker.length > text.length) return false
    return text.substring(sel.first - marker.length, sel.first) == marker &&
        text.substring(sel.last, sel.last + marker.length) == marker
}

@Composable
private fun FormatButton(active: String, letter: Char, a11y: String, onClick: () -> Unit, enabled: Boolean) {
    val tint = if (active == "off") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active == "off") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(end = 2.dp),
    ) {
        TextButton(
            onClick = { onClick(); },
            enabled = enabled,
            modifier = Modifier.size(width = 34.dp, height = 34.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text(letter.toString(), color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FormatTextButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(end = 2.dp),
    ) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(width = 34.dp, height = 34.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FormatIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, a11y: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(end = 2.dp),
    ) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(width = 34.dp, height = 34.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Icon(icon, contentDescription = a11y, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun FormatDivider() {
    HorizontalDivider(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .padding(horizontal = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}


/**
 * The note editor. Mirrors NoteEditorView.swift: title field in the top bar,
 * debounced autosave, markdown preview toggle, formatting toolbar + slash
 * menu, and the bottom player controls (expressive ButtonGroup transport,
 * live speed slider, voice chip, read-along toggle).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    vm: SpeechnotesViewModel,
    noteId: String,
    onBack: () -> Unit,
) {
    /**
     * What the engine should hear. Round-5 parity: speech follows the note's
     * CURRENT view state — reading preview → clean rendered text, editing →
     * the raw draft. A single renderMarkdown switch meant editing raw text
     * still heard "hashtag hashtag heading".
     */
    fun computeSpeechText(source: String, render: Boolean): String =
        if (render) SpeechText.forText(com.speechnotes.app.logic.MarkdownText.plainText(source))
        else SpeechText.forText(source)

    val prefs = vm.context.getSharedPreferences("speechnotes", android.content.Context.MODE_PRIVATE)
    val notesFlow = vm.notes.notes.collectAsState()
    val note = notesFlow.value.firstOrNull { it.id == noteId }
    // Settings live in the ViewModel's StateFlows — snapshotting them into
    // local remember{} state ignored later changes (the Settings toggle
    // appeared dead from the editor's side).
    val renderMarkdown by vm.renderMarkdownFlow.collectAsState()
    val readAlongEnabled by vm.readAlongEnabledFlow.collectAsState()

    var draft by remember(noteId) { mutableStateOf(TextFieldValue(note?.text ?: "")) }
    var selectionUTF16 by remember(noteId) {
        mutableStateOf<IntRange?>(null).also { it.value = (note?.text ?: "").length..(note?.text ?: "").length }
    }
    var showPreview by remember(noteId) { mutableStateOf(vm.renderMarkdown) }
    // Speech source follows the view: reading preview → clean text, editing → raw draft.
    val rendersForSpeech: Boolean = renderMarkdown && showPreview
    val draftStats = remember(draft.text) {
        val words = draft.text.split(' ', '\n', '\t').count { it.isNotBlank() }
        if (words <= 0) "0 words"
        else "$words words · ~${kotlin.math.max(1, Math.round(words / 145.0))} min listen"
    }
    var titleDraft by remember(noteId) { mutableStateOf(note?.explicitTitle ?: "") }
    var loaded by remember(noteId) { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }
    // Immersive chrome (iOS immersiveBars.editor): while READING (preview or
    // read-along) a tap hides the top bar; another tap brings it back.
    // Editing never hides it — the back button and the ⋯ menu are the only
    // way out, so a hidden bar there strands the user.
    var immersiveChromeHidden by remember(noteId) { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showSlashMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Insert image at the caret (ImagePicker + MarkdownImageInserter parity):
    // bytes are cached under note-images/<noteId>/ and referenced from the
    // markdown as ![](speechnotes://note-image/<hash>.<ext>) — the preview
    // resolves it to a real thumbnail.

    /** Reusable helper for inserting markdown at the caret (image fragments). */
    fun insertMarkdownAtCaret(fragment: String) {
        val caret = (selectionUTF16?.first ?: draft.selection.start).coerceIn(0, draft.text.length)
        val (before, after) = draft.text.substring(0, caret) to draft.text.substring(caret)
        val insertion = (if (caret > 0 && !draft.text[caret - 1].isWhitespace()) "\n" else "") + "$fragment\n"
        draft = TextFieldValue(
            text = before + insertion + after,
            selection = androidx.compose.ui.text.TextRange(caret + insertion.length),
        )
        selectionUTF16 = (caret + insertion.length)..(caret + insertion.length)
    }

    val imageInsertLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.appScope.launch {
            val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    vm.context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            } ?: return@launch
            val ext = (vm.context.contentResolver.getType(uri) ?: "image/jpeg")
                .substringAfter('/')
                .substringBefore(';')
                .takeIf { it.length in 2..5 } ?: "jpg"
            val target = com.speechnotes.app.logic.NoteImageStore.store(bytes, ext, noteId)
            if (target == null) {
                vm.showToast("Couldn't attach that image.")
                return@launch
            }
            insertMarkdownAtCaret(com.speechnotes.app.logic.NoteImageStore.markdownFor(target))
            // R4: an image imported into a note is also READ — run ML Kit
            // text recognition over it and land the text under the image so
            // TTS reads it like any note (iOS ImportService parity).
            // Failures surface as a toast; nothing is swallowed silently.
            val recognized = runCatching { vm.recognizeImageText(bytes) }.getOrNull()
            if (recognized != null) {
                insertMarkdownAtCaret("\n$recognized")
                vm.showToast("Recognized text from the image.")
            } else {
                vm.showToast("No text recognized in that image.")
            }
        }
    }

    // iOS "Insert image" sheet parity: photo picker, clipboard paste, or a
    // one-shot download from a typed URL (stored locally like the picker's).
    var showImageSourceMenu by remember { mutableStateOf(false) }
    var showImageUrlPrompt by remember { mutableStateOf(false) }
    var imageUrlDraft by remember { mutableStateOf("") }

    if (showImageSourceMenu) {
        AlertDialog(
            onDismissRequest = { showImageSourceMenu = false },
            title = { Text("Insert image") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose where the image comes from.")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        showImageSourceMenu = false
                        imageUrlDraft = ""
                        showImageUrlPrompt = true
                    }) { Text("From URL…") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showImageSourceMenu = false
                    imageInsertLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Photos") }
            },
            dismissButton = {
                Button(onClick = {
                    showImageSourceMenu = false
                    val clipboard = vm.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                    val item = clip?.getItemAt(0)
                    val hasImage = clip != null &&
                        (clip.description.hasMimeType("image/*") || item?.uri != null)
                    if (!hasImage || item == null) {
                        vm.showToast("The clipboard has no image.")
                        return@Button
                    }
                    vm.appScope.launch {
                        val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val uri = item.uri
                                if (uri != null) {
                                    vm.context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                } else null
                            }.getOrNull()
                        } ?: return@launch
                        val target = com.speechnotes.app.logic.NoteImageStore.store(bytes, "png", noteId)
                        if (target == null) {
                            vm.showToast("Couldn't attach that image.")
                            return@launch
                        }
                        insertMarkdownAtCaret(com.speechnotes.app.logic.NoteImageStore.markdownFor(target))
                    }
                }) { Text("Clipboard") }
            },
        )
    }

    if (showImageUrlPrompt) {
        AlertDialog(
            onDismissRequest = { showImageUrlPrompt = false },
            title = { Text("Insert from URL") },
            text = {
                OutlinedTextField(
                    value = imageUrlDraft,
                    onValueChange = { imageUrlDraft = it },
                    singleLine = true,
                    placeholder = { Text("https://…") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showImageUrlPrompt = false
                        val url = imageUrlDraft.trim()
                        if (url.isBlank()) return@Button
                        vm.appScope.launch {
                            val stored = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    val request = okhttp3.Request.Builder().url(url).build()
                                    vm.httpClient.newCall(request).execute().use { response ->
                                        if (!response.isSuccessful) return@runCatching null
                                        response.body?.bytes()
                                    }
                                }.getOrNull()
                            }
                            if (stored == null) {
                                vm.showToast("Couldn't download that image.")
                                return@launch
                            }
                            val ext = url.substringAfterLast('.', "jpg").substringBefore('?').takeIf { it.length in 2..5 } ?: "jpg"
                            val target = com.speechnotes.app.logic.NoteImageStore.store(stored, ext, noteId)
                            if (target == null) {
                                vm.showToast("Couldn't attach that image.")
                                return@launch
                            }
                            insertMarkdownAtCaret(com.speechnotes.app.logic.NoteImageStore.markdownFor(target, alt = "image"))
                        }
                    },
                ) { Text("Insert") }
            },
            dismissButton = {
                Button(onClick = { showImageUrlPrompt = false }) { Text("Cancel") }
            },
        )
    }

    val playerState by vm.player.state.collectAsState()
    val exportState by vm.player.exportState.collectAsState()
    val exportShareFile by vm.player.shareFile.collectAsState()
    val isThisNotePlaying = vm.player.nowPlayingNoteId.collectAsState().value == noteId
    // Landscape moves the transport to the trailing rail (iOS PlaybackRail);
    // the bottom bar yields.
    val landscape = com.speechnotes.app.ui.playback.isLandscape()
    val playProgress by vm.player.progress.collectAsState()
    val playRate by vm.player.rateMultiplier.collectAsState()

    // WAV export feedback (iOS parity): a toast on failure, a share prompt
    // when the file lands. The flows existed but nothing observed them, so
    // the "Audio (WAV)" option appeared to do nothing.
    LaunchedEffect(exportState) {
        when (val s = exportState) {
            is com.speechnotes.app.speech.ExportState.Failed ->
                vm.showToast("WAV export failed: ${s.message}")
            else -> Unit
        }
    }
    LaunchedEffect(exportShareFile) {
        val file = exportShareFile ?: return@LaunchedEffect
        vm.models.share(file)
        vm.player.dismissExport()
    }
    // The editor's own transport is the player UI for THIS note while it's
    // speaking, so the global bar stands down — but ONLY then. Suppressing
    // for the whole editor session (as this used to) meant starting playback
    // from the editor produced no mini-player anywhere in the app; the editor
    // also has no mini-bar of its own, so the session was invisible the
    // moment the user left the note.
    val editorOwnsTransport = isThisNotePlaying && playerState.isActive
    DisposableEffect(noteId, editorOwnsTransport) {
        vm.miniPlayerSuppressed = editorOwnsTransport
        onDispose { vm.miniPlayerSuppressed = false }
    }
    val showReadAlong = readAlongEnabled && isThisNotePlaying &&
        (playerState == SpeechState.SPEAKING || playerState == SpeechState.PAUSED || playerState == SpeechState.GENERATING)
    val chromeHidden = immersiveChromeHidden && (showReadAlong || (renderMarkdown && showPreview))
    // Entering edit mode restores the chrome deterministically.
    if (!showReadAlong && !showPreview && immersiveChromeHidden) immersiveChromeHidden = false
    // Keep Screen On (iOS ScreenAwake parity): the editor holds the screen
    // only while it is READING (preview / read-along) — never in edit mode,
    // where the software keyboard already keeps it awake.
    if (com.speechnotes.app.logic.ScreenAwakePolicy.noteIsReadingSurface(
            renderMarkdown = renderMarkdown, showPreview = showPreview, readAlongShowing = showReadAlong,
        )
    ) {
        com.speechnotes.app.ui.playback.KeepScreenOnSurface(
            com.speechnotes.app.logic.ScreenAwakeSurface.NOTE_READER,
        )
    }

    LaunchedEffect(noteId) {
        if (!loaded && note != null) {
            draft = TextFieldValue(note.text)
            titleDraft = note.explicitTitle ?: ""
            loaded = true
        }
    }

    // Image caching: Automatic (the default) downloads the note's web images
    // on open so scrolling the preview never waits on the network; Manual
    // leaves it to display time. iOS prefetchImagesIfAutomatic parity.
    val imageCacheAutomatic = prefs.getBoolean("imageCacheAutomatic", true)
    if (imageCacheAutomatic && loaded) {
        LaunchedEffect(noteId, draft.text) {
            val markdown = draft.text
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.speechnotes.app.logic.MarkdownText.imageTokens(markdown)
                        .map { it.second }
                        .filter { it.startsWith("http") }
                        .forEach { url ->
                            runCatching {
                                vm.httpClient.newCall(okhttp3.Request.Builder().url(url).build())
                                    .execute().use { response ->
                                        if (response.isSuccessful) coil3.ImageLoader(vm.context)
                                    }
                            }
                        }
                }
            }
        }
    }

    // Debounced autosave (~400ms after typing stops), mirroring the iOS app.
    LaunchedEffect(draft.text, titleDraft) {
        if (!loaded) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        val existing = vm.notes.note(noteId) ?: return@LaunchedEffect
        vm.notes.update(
            existing.copy(
                text = draft.text,
                explicitTitle = titleDraft.takeIf { it.isNotBlank() },
            )
        )
    }

    DisposableEffect(noteId) {
        onDispose {
            // Flush on exit (mirrors the iOS editor's onDisappear save) —
            // and always: even a note that was never edited beyond the open
            // keeps its explicitly-set title if the user typed one.
            val existing = vm.notes.note(noteId)
            val trimmedTitle = titleDraft.trim()
            val explicitTitle = trimmedTitle.takeIf { it.isNotBlank() }
            if (existing != null && (existing.text != draft.text || existing.explicitTitle != explicitTitle)) {
                vm.notes.update(existing.copy(text = draft.text, explicitTitle = explicitTitle))
            }
            // A note created and abandoned within seconds (mis-tap on +)
            // must not litter the list as "Untitled note" forever — iOS
            // purges it (text AND title both empty, < 10 s old).
            if (existing != null &&
                System.currentTimeMillis() - existing.createdAt < 10_000L &&
                draft.text.isBlank() && titleDraft.isBlank()
            ) {
                vm.notes.purge(noteId)
                com.speechnotes.app.logic.NoteImageStore.removeAllImages(noteId)
            } else {
                // Drop cached images the markdown no longer references.
                com.speechnotes.app.logic.NoteImageStore.prune(noteId, draft.text)
            }
            vm.notes.flushNow()
        }
    }

    // Cached speech text: the full-document markdown scan runs off-main,
    // debounced ~300ms after typing stops. It used to be recomputed
    // synchronously in composition on EVERY keystroke — the typing-lag bug
    // the iOS port fixed the same way. Explicit actions (play, export) call
    // speechTextNow() so the engine always hears edits made in the last
    // 300ms.
    var cachedSpeechText by remember(noteId) { mutableStateOf("") }
    LaunchedEffect(draft.text, rendersForSpeech) {
        if (!loaded) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        val source = draft.text
        cachedSpeechText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { computeSpeechText(source, rendersForSpeech) }
    }

    if (showReadAlong) {
        ReadAlongScreen(
            vm = vm,
            text = (vm.player.activeSpeechText.collectAsState().value ?: cachedSpeechText).ifEmpty { cachedSpeechText },
            range = vm.player.readAlongRange.collectAsState().value,
            onExit = { vm.readAlongEnabled = false },
        )
        return
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            // Hand-built bar instead of TopAppBar. Both previous layouts
            // overlapped: the title-slot text field drew over six action
            // icons, and the fix (a fillMaxWidth Row inside the actions slot)
            // was placed by M3 at the bar's END edge — i.e. ON TOP of the
            // navigation icon, so the back arrow and the title collided. This
            // Row owns every child directly: the field takes weight(1f) and
            // nothing can collide at any width.
            if (!chromeHidden) Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(56.dp)
                        .padding(start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    // Title and stats share one flexible column. Keeping the
                    // stats in the Row as a fixed-width action squeezed the
                    // title to nothing on a phone-width window.
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = titleDraft,
                            onValueChange = { titleDraft = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                Box {
                                    if (titleDraft.isEmpty()) {
                                        Text(
                                            note?.title?.takeIf { it.isNotBlank() } ?: "Title",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        Text(
                            draftStats,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    // Render Markdown / Edit-Preview lives here, not only in
                    // Settings. Enabling the setting from this note enters
                    // the rendered preview immediately; the pencil returns
                    // to editing without changing the user's preference.
                    IconButton(
                        onClick = {
                            com.speechnotes.app.ui.theme.Haptics.tap(vm.context)
                            if (renderMarkdown) showPreview = !showPreview
                            else {
                                vm.renderMarkdown = true
                                showPreview = true
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            if (showPreview) Icons.Default.Edit else Icons.Default.Visibility,
                            contentDescription = when {
                                !renderMarkdown -> "Render markdown"
                                showPreview -> "Edit note"
                                else -> "Preview markdown"
                            },
                        )
                    }
                    // Speak: tap toggles play/pause; LONG-PRESS starts reading
                    // from the caret (R3 playFrom — the "start reading here"
                    // primitive; with a selection active the selection start is
                    // used). Sentence-snapped by the player, so the caret may
                    // sit mid-word.
                    val speechCanPlay = playerState != SpeechState.IDLE || cachedSpeechText.isNotBlank()
                    Box(
                        Modifier
                            .size(40.dp)
                            .combinedClickable(
                                enabled = speechCanPlay,
                                onClick = {
                                    vm.player.togglePlay(computeSpeechText(draft.text, rendersForSpeech), note)
                                },
                                onLongClick = {
                                    com.speechnotes.app.ui.theme.Haptics.tap(vm.context)
                                    val caret = selectionUTF16?.first
                                        ?: draft.selection.start.coerceAtMost(draft.text.length)
                                    vm.player.playFrom(
                                        computeSpeechText(draft.text, rendersForSpeech),
                                        caret,
                                        noteId = note?.id,
                                        noteTitle = note?.title,
                                    )
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The icon tracks the player state: a static
                        // PlayArrow read as "the tap didn't register" while
                        // the engine was still loading.
                        when (playerState) {
                            SpeechState.GENERATING ->
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            SpeechState.SPEAKING ->
                                Icon(Icons.Default.Pause, contentDescription = "Pause")
                            else ->
                                Icon(Icons.Default.PlayArrow, contentDescription = "Speak")
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text("Render markdown") },
                                leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                                trailingIcon = {
                                    Switch(checked = renderMarkdown, onCheckedChange = null)
                                },
                                onClick = {
                                    showOverflow = false
                                    val enabled = !renderMarkdown
                                    vm.renderMarkdown = enabled
                                    showPreview = enabled
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share…") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = { showOverflow = false; showShareMenu = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete note") },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { showOverflow = false; showDeleteConfirm = true },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                    if (renderMarkdown && !showPreview) {
                        MarkdownFormattingBar(
                            draft = draft.text,
                            caretOffset = selectionUTF16?.first ?: draft.selection.start.coerceAtMost(draft.text.length),
                            selection = selectionUTF16,
                            enabled = !showPreview,
                            onInsertImage = { showImageSourceMenu = true },
                            onEdit = { newText, newCaret, newSelection ->
                                // newSelection is an UTF-16 range with an
                                // EXCLUSIVE end (MarkdownFormatActions' own
                                // convention), so it maps to a TextRange
                                // VERBATIM. The old `it.last + 1` turned a
                                // collapsed range into a one-character
                                // selection: after every formatting tap the
                                // next keystroke replaced a character
                                // instead of typing at the caret.
                                val selection = newSelection
                                    ?: (newCaret.coerceIn(0, newText.length))..
                                        (newCaret.coerceIn(0, newText.length))
                                val textRange = androidx.compose.ui.text.TextRange(
                                    selection.first.coerceIn(0, newText.length),
                                    selection.last.coerceIn(0, newText.length),
                                )
                                draft = TextFieldValue(newText, selection = textRange)
                                selectionUTF16 = selection
                            },
                        )
                    }
                if (!landscape) {
                    PlayerControlsBar(
                        vm = vm,
                        speechTextProvider = { computeSpeechText(draft.text, rendersForSpeech) },
                        note = note,
                        readAlongEnabled = readAlongEnabled,
                        onReadAlongToggle = { vm.readAlongEnabled = !vm.readAlongEnabled },
                        onOpenVoicePicker = { showVoicePicker = true },
                        onRestartFromBeginning = { vm.player.clearResumeBookmark(noteId) },
                    )
                }
            }
        },
    ) { padding ->
        // Landscape: the reading body keeps the leading side and the playback
        // rail owns the trailing edge (iOS PlaybackRail layout).
        Row(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .then(
                        if (showReadAlong || (renderMarkdown && showPreview)) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { immersiveChromeHidden = !immersiveChromeHidden },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
            if (renderMarkdown && showPreview) {
                MarkdownPreview(
                    draft.text,
                    noteId,
                    blockSpacing = vm.themeState.collectAsState().value.readerBlockSpacing,
                    lineSpacing = vm.themeState.collectAsState().value.readerLineSpacing,
                )
            } else {
                EditorBody(
                    draft = draft,
                    onDraftChange = { newValue ->
                        draft = newValue
                        // Keep the UTF-16 caret/selection live for the
                        // formatting bar. (This used to be only the caret;
                        // the onChange handler needed for that lived in a
                        // UITextView ports — the Compose text field reports
                        // both here.)
                        selectionUTF16 = newValue.selection.start..newValue.selection.end
                    },
                    onSlashRequest = { showSlashMenu = !showSlashMenu },
                )
            }
            }
            if (landscape) {
                com.speechnotes.app.ui.playback.PlaybackRail(
                    vm = vm,
                    progress = playProgress,
                    isGenerating = playerState == SpeechState.GENERATING,
                    isSpeaking = playerState == SpeechState.SPEAKING,
                    isPlayEnabled = playerState != SpeechState.IDLE || cachedSpeechText.isNotBlank(),
                    onTogglePlay = { vm.player.togglePlay(computeSpeechText(draft.text, rendersForSpeech), note) },
                    onStop = { vm.player.stop() },
                    voiceLabel = vm.player.currentVoiceDescription,
                    onChangeVoice = { showVoicePicker = true },
                    readAlongOn = readAlongEnabled,
                    onToggleReadAlong = { vm.readAlongEnabled = !vm.readAlongEnabled },
                    rate = playRate,
                    onRateChange = { vm.player.setRateMultiplier(it) },
                )
            }
        }
    }

    if (showVoicePicker) {
        VoicePickerSheet(vm = vm, onDismiss = { showVoicePicker = false })
    }

    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this note?") },
            confirmButton = {
                Button(onClick = {
                    vm.player.stop()
                    vm.notes.delete(noteId)
                    vm.notes.flushNow()
                    showDeleteConfirm = false
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = {
                Button(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showShareMenu) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showShareMenu = false },
            title = { Text("Share as") },
            text = {
                Column {
                    ShareOption("Markdown") {
                        val stem = exportSafeName(note?.title?.ifBlank { "note" } ?: "note", 40)
                        val file = File(com.speechnotes.app.logic.AppFiles.exportsDir, "$stem.md")
                        file.parentFile?.mkdirs()
                        file.writeText(draft.text)
                        vm.models.share(file)
                        showShareMenu = false
                    }
                    ShareOption("Plain text") {
                        val stem = exportSafeName(note?.title?.ifBlank { "note" } ?: "note", 40)
                        val file = File(com.speechnotes.app.logic.AppFiles.exportsDir, "$stem.txt")
                        file.parentFile?.mkdirs()
                        file.writeText(com.speechnotes.app.logic.MarkdownText.plainText(draft.text))
                        vm.models.share(file)
                        showShareMenu = false
                    }
                    ShareOption("PDF") {
                        // Block-aware render (NotePdfLayout): headings, lists,
                        // quotes, code and tables keep their structure — a
                        // deliberate improvement over iOS's export, which
                        // flattens to plain text before printing.
                        vm.appScope.launch {
                            val title = note?.title?.ifBlank { "note" } ?: "note"
                            val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                renderNotePdf(title, draft.text)
                            }
                            if (file != null) vm.models.share(file) else vm.showToast("PDF export failed.")
                        }
                        showShareMenu = false
                    }
                    ShareOption("Audio (WAV)") {
                        vm.player.export(computeSpeechText(draft.text, rendersForSpeech), note?.title ?: "note")
                        showShareMenu = false
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Button(onClick = { showShareMenu = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * File-name stem for a note export: the title, stripped of anything that
 * is not safe in a path, capped, and never empty. Every export of a note
 * (markdown, text, PDF) uses this stem — a fixed "note.md" made each
 * export overwrite the previous one.
 */
private fun exportSafeName(title: String, maxLength: Int = 60): String =
    title.map { ch ->
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
    }.joinToString("").trim('_').take(maxLength).ifEmpty { "note" }

/**
 * Renders the note's markdown into a paged PDF in the exports directory,
 * named after the note (iOS safeFilename parity). [markdown] is the raw
 * draft; NotePdfLayout lays out the blocks and this adapter draws them.
 */
private fun renderNotePdf(title: String, markdown: String): File? = runCatching {
    val safeName = exportSafeName(title)
    val file = File(com.speechnotes.app.logic.AppFiles.exportsDir, "$safeName.pdf")
        .apply { parentFile?.mkdirs() }
    val blocks = com.speechnotes.app.logic.MarkdownText.blocks(markdown)

    // Real measurement: the wrapping decisions must match what's actually
    // drawn, so layout runs with a Paint-backed Measurer instead of the
    // pure default model the JVM tests use.
    val measurePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val measurer = com.speechnotes.app.logic.NotePdfLayout.Measurer { text, style ->
        measurePaint.textSize = style.size
        measurePaint.textSkewX = if (style.italic) -0.25f else 0f
        measurePaint.typeface = android.graphics.Typeface.create(
            when {
                style.monospace -> android.graphics.Typeface.MONOSPACE
                style.bold -> android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                else -> android.graphics.Typeface.SANS_SERIF
            },
            android.graphics.Typeface.NORMAL,
        )
        measurePaint.measureText(text)
    }
    val measuredOps = com.speechnotes.app.logic.NotePdfLayout.layout(title, blocks, measurer)

    val document = android.graphics.pdf.PdfDocument()
    var pageNumber = 0
    var page: android.graphics.pdf.PdfDocument.Page? = null

    /** Starts a page when none is open and returns the page to draw on.
     *  Returning it (instead of reading the nullable field back with `!!`)
     *  keeps every caller null-safe by construction. */
    fun ensurePage(): android.graphics.pdf.PdfDocument.Page {
        val open = page
        if (open != null) return open
        pageNumber += 1
        val started = document.startPage(
            android.graphics.pdf.PdfDocument.PageInfo
                .Builder(NotePdfLayout.PAGE_WIDTH.toInt(), NotePdfLayout.PAGE_HEIGHT.toInt(), pageNumber)
                .create(),
        )
        page = started
        return started
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    for (op in measuredOps) {
        when (op) {
            is NotePdfLayout.Op.PageBreak -> {
                page?.let { document.finishPage(it) }
                page = null
            }
            is NotePdfLayout.Op.Text -> {
                ensurePage()
                paint.textSize = op.style.size
                paint.isFakeBoldText = op.style.bold
                paint.textSkewX = if (op.style.italic) -0.25f else 0f
                paint.typeface = android.graphics.Typeface.create(
                    when {
                        op.style.monospace -> android.graphics.Typeface.MONOSPACE
                        op.style.bold -> android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                        else -> android.graphics.Typeface.SANS_SERIF
                    },
                    android.graphics.Typeface.NORMAL,
                )
                ensurePage().canvas.drawText(op.text, op.x, op.baselineY, paint)
            }
            is NotePdfLayout.Op.Rule -> {
                ensurePage().canvas.drawLine(op.x1, op.y, op.x2, op.y, paint.apply { strokeWidth = 1f })
            }
        }
    }
    page?.let { document.finishPage(it) }
    if (pageNumber == 0) document.finishPage(ensurePage())
    file.outputStream().use { out -> document.writeTo(out) }
    document.close()
    file
}.getOrNull()

@Composable
private fun ShareOption(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun EditorBody(
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    onSlashRequest: () -> Unit,
) {
    val caret = draft.selection.start.coerceIn(0, draft.text.length)
    val trigger = remember(draft.text, caret) { MarkdownSlashMenu.detect(draft.text, caret) }
    val matches = remember(trigger) {
        if (trigger == null) emptyList()
        else {
            val prefix = draft.text.substring(trigger.slashIndex + 1, trigger.cursorIndex)
            MarkdownSlashMenu.filter(prefix)
        }
    }

    Column {
        if (trigger != null && matches.isNotEmpty()) {
            SlashMenuOverlay(matches = matches) { command ->
                val result = MarkdownSlashMenu.apply(
                    command = command,
                    draft = draft.text,
                    trigger = trigger,
                    caret = caret,
                )
                onDraftChange(TextFieldValue(result.draft, selection = androidx.compose.ui.text.TextRange(result.caretUtf16)))
            }
            HorizontalDivider()
        }
        SelectionContainer {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text("Start writing, or type / for formatting") },
            )
        }
    }
}

@Composable
private fun SlashMenuOverlay(
    matches: List<com.speechnotes.app.logic.MarkdownSlashMenu.Command>,
    onPick: (com.speechnotes.app.logic.MarkdownSlashMenu.Command) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        matches.take(6).forEach { command ->
            androidx.compose.material3.AssistChip(
                onClick = { onPick(command) },
                label = { Text(command.label) },
            )
        }
    }
}

@Composable
private fun MarkdownPreview(markdown: String, noteId: String, blockSpacing: Double = 1.0, lineSpacing: Double = 1.0) {
    val blocks = remember(markdown) { com.speechnotes.app.logic.MarkdownText.blocks(markdown) }
    // Zoomable image viewer (ZoomableImageView parity): the tapped image
    // opens full-screen with pinch-to-zoom; other content dims behind it.
    var zoomed by remember { mutableStateOf<Any?>(null) }
    var zoomedLabel by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is com.speechnotes.app.logic.MarkdownText.MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge
                        2 -> MaterialTheme.typography.headlineMedium
                        else -> MaterialTheme.typography.headlineSmall
                    }
                    MarkdownStyledText.Text(
                        block.text,
                        style = style.copy(fontWeight = HeadingWeight(block.level)),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                is com.speechnotes.app.logic.MarkdownText.MarkdownBlock.Paragraph -> {
                    // Inline images inside a paragraph were silently dropped
                    // before: the block carried the whole line and only a
                    // standalone `![alt](url)` block ever became an Image.
                    MarkdownTextBlock(
                        text = block.text,
                        noteId = noteId,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.4f * lineSpacing.toFloat(),
                        ),
                        lineSpacing = lineSpacing,
                        onZoom = { model, label -> zoomed = model; zoomedLabel = label },
                    )
                    Spacer(Modifier.height((10 * blockSpacing).dp))
                }
                is com.speechnotes.app.logic.MarkdownText.MarkdownBlock.BulletList -> {
                    block.items.forEach { item ->
                        MarkdownTextBlock(
                            text = (if (item.isTask) (if (item.isDone) "☑ " else "☐ ") else "• ") + item.text,
                            noteId = noteId,
                            style = MaterialTheme.typography.bodyLarge,
                            lineSpacing = lineSpacing,
                            indentDp = (item.level * 16).dp,
                            onZoom = { model, label -> zoomed = model; zoomedLabel = label },
                        )
                    }
                    Spacer(Modifier.height((10 * blockSpacing).dp))
                }
                is com.speechnotes.app.logic.MarkdownText.MarkdownBlock.OrderedList -> {
                    block.items.forEachIndexed { i, item ->
                        MarkdownTextBlock(
                            text = "${i + 1}. " + (if (item.isTask) (if (item.isDone) "☑ " else "☐ ") else "") + item.text,
                            noteId = noteId,
                            style = MaterialTheme.typography.bodyLarge,
                            lineSpacing = lineSpacing,
                            indentDp = (item.level * 16).dp,
                            onZoom = { model, label -> zoomed = model; zoomedLabel = label },
                        )
                    }
                    Spacer(Modifier.height((10 * blockSpacing).dp))
                }
                is com.speechnotes.app.logic.MarkdownText.MarkdownBlock.Quote -> {
                    MarkdownTextBlock(
                        text = block.text,
                        noteId = noteId,
                        style = MaterialTheme.typography.bodyLarge,
                        lineSpacing = lineSpacing,
                        onZoom = { model, label -> zoomed = model; zoomedLabel = label },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(10.dp),
                    )
                    Spacer(Modifier.height((10 * blockSpacing).dp))
                }
                is com.speechnotes.app.logic.MarkdownText.MarkdownBlock.Code -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            block.language?.takeIf { it.isNotBlank() }?.let { lang ->
                                Text(
                                    lang,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            MarkdownStyledText.Text(
                                block.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height((10 * blockSpacing).dp))
                }
                is com.speechnotes.app.logic.MarkdownText.MarkdownBlock.Image -> {
                    NoteImage(
                        alt = block.alt,
                        url = block.url,
                        noteId = noteId,
                        onZoom = { model, label -> zoomed = model; zoomedLabel = label },
                    )
                    Spacer(Modifier.height((10 * blockSpacing).dp))
                }
                is com.speechnotes.app.logic.MarkdownText.MarkdownBlock.Table -> {
                    MarkdownTable(block.headers, block.rows)
                    Spacer(Modifier.height((10 * blockSpacing).dp))
                }
                is com.speechnotes.app.logic.MarkdownText.MarkdownBlock.Divider -> {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }

    zoomed?.let { model ->
        ZoomableImageViewer(model = model, label = zoomedLabel, onDismiss = { zoomed = null })
    }
}

/** Full-screen pinch-to-zoom image viewer with a dismiss scrim. */
@Composable
private fun ZoomableImageViewer(
    model: Any?,
    label: String,
    onDismiss: () -> Unit,
) {
    val painter = coil3.compose.rememberAsyncImagePainter(model)
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transformableState = androidx.compose.foundation.gestures.rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offset += panChange
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable(onClick = onDismiss)
            .transformable(
                state = transformableState,
                lockRotationOnZoomPan = false,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painter,
            contentDescription = label,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close image", tint = Color.White)
        }
    }
}

private fun HeadingWeight(level: Int) = when (level) {
    1 -> androidx.compose.ui.text.font.FontWeight.Bold
    else -> androidx.compose.ui.text.font.FontWeight.SemiBold
}

/**
 * Bottom-of-editor player controls: voice chip, expressive ButtonGroup
 * transport (play/pause/stop), progress, read-along toggle and speed slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControlsBar(
    vm: SpeechnotesViewModel,
    speechTextProvider: () -> String,
    note: Note?,
    readAlongEnabled: Boolean,
    onReadAlongToggle: () -> Unit,
    onOpenVoicePicker: () -> Unit,
    onRestartFromBeginning: () -> Unit = {},
) {
    val state by vm.player.state.collectAsState()
    val progress by vm.player.progress.collectAsState()
    val rate by vm.player.rateMultiplier.collectAsState()
    val voiceDescription = vm.player.currentVoiceDescription
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hasResume = vm.player.hasResumeBookmark(note?.id)
    val preferences = remember {
        vm.context.getSharedPreferences("speechnotes", android.content.Context.MODE_PRIVATE)
    }
    var minimized by remember(note?.id) {
        mutableStateOf(preferences.getBoolean("editorBarMinimized", false))
    }

    fun setMinimized(value: Boolean) {
        minimized = value
        preferences.edit().putBoolean("editorBarMinimized", value).apply()
    }

    if (minimized) {
        // iOS editorBarMinimized parity: the lower half of the editor comes
        // back while play state and progress stay one tap away.
        com.speechnotes.app.ui.playback.MinimizedPlaybackPill(
            isPlaying = state == SpeechState.SPEAKING,
            isGenerating = state == SpeechState.GENERATING,
            isPlayEnabled = true,
            progressText = progress?.let { "${(it * 100).toInt()}%" },
            onTogglePlay = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.player.togglePlay(speechTextProvider(), note)
            },
            onExpand = {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                setMinimized(false)
            },
        )
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        val progressValue = progress
        if (progressValue != null && state == SpeechState.SPEAKING) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progressValue.toFloat() },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Spacer(Modifier.height(4.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Weighted voice chip: long voice descriptions ellipsize instead
            // of pushing the transport beyond the right screen edge.
            androidx.compose.material3.AssistChip(
                onClick = onOpenVoicePicker,
                label = {
                    Text(
                        voiceDescription,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.weight(1f).height(36.dp),
            )
            Spacer(Modifier.width(4.dp))
            androidx.compose.material3.FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.player.togglePlay(speechTextProvider(), note)
                },
                modifier = Modifier.size(40.dp).semantics { contentDescription = playLabel(state) },
            ) {
                when (state) {
                    SpeechState.GENERATING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    SpeechState.SPEAKING -> Icon(Icons.Default.Pause, contentDescription = null)
                    SpeechState.PAUSED -> Icon(Icons.Default.PlayArrow, contentDescription = null)
                    SpeechState.IDLE -> Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
            }
            if (state == SpeechState.SPEAKING || state == SpeechState.PAUSED || state == SpeechState.GENERATING) {
                IconButton(
                    onClick = { vm.player.stop() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            }
            Spacer(Modifier.width(4.dp))
            androidx.compose.material3.FilledTonalButton(
                onClick = onReadAlongToggle,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(if (readAlongEnabled) "Read-along: on" else "Read-along: off", maxLines = 1)
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                setMinimized(true)
            }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize playback controls")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasResume) {
                IconButton(onClick = onRestartFromBeginning, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Restart from beginning", modifier = Modifier.size(18.dp))
                }
            }
            Text("Speed", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = rate.toFloat(),
                onValueChange = { vm.player.setRateMultiplier(it.toDouble()) },
                valueRange = 0.5f..2.0f,
                steps = 29,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                text = String.format("%.2f×", rate),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun playLabel(state: SpeechState): String = when (state) {
    SpeechState.GENERATING -> "Stop"
    SpeechState.SPEAKING -> "Pause"
    SpeechState.PAUSED -> "Resume"
    SpeechState.IDLE -> "Play"
}

/**
 * A run of markdown text with inline styling AND inline images.
 *
 * `MarkdownText.inlineRuns` splits a line into Text/Image/Link runs, but the
 * preview never called it — the UI consumed whole blocks, so an image
 * *inside* a paragraph (the common case: "see ![x](url) below") rendered as
 * literal markdown and was invisible as an image. This walks the runs, styles
 * the text fragments, and emits a real image for each Image run.
 */
@Composable
private fun MarkdownTextBlock(
    text: String,
    noteId: String,
    style: androidx.compose.ui.text.TextStyle,
    lineSpacing: Double = 1.0,
    indentDp: androidx.compose.ui.unit.Dp = 0.dp,
    onZoom: (model: Any?, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refs = remember(text) { com.speechnotes.app.logic.MarkdownText.linkReferences(text) }
    val runs = remember(text, refs) { com.speechnotes.app.logic.MarkdownText.inlineRuns(text, refs) }
    // Nothing to split: the common case, so keep it allocation-light.
    val onlyText = runs.all { it is com.speechnotes.app.logic.MarkdownText.InlineRun.Text }
    if (onlyText) {
        MarkdownStyledText.Text(
            text,
            style = style,
            modifier = modifier.padding(start = indentDp),
        )
        return
    }
    val bodyStyle = style.copy(
        lineHeight = style.fontSize * 1.4f * lineSpacing.toFloat(),
    )
    Column(modifier.padding(start = indentDp)) {
        for (run in runs) {
            when (run) {
                is com.speechnotes.app.logic.MarkdownText.InlineRun.Text ->
                    MarkdownStyledText.Text(run.text, style = bodyStyle)
                is com.speechnotes.app.logic.MarkdownText.InlineRun.Link ->
                    MarkdownStyledText.Text(run.label, style = bodyStyle.copy(color = MaterialTheme.colorScheme.primary))
                is com.speechnotes.app.logic.MarkdownText.InlineRun.Image ->
                    NoteImage(alt = run.alt, url = run.url, noteId = noteId, onZoom = onZoom)
            }
        }
    }
}

/**
 * One image from a note. Local `speechnotes://note-image/…` targets resolve
 * through NoteImageStore (the only thing that works offline); http(s) URLs are
 * fetched by the app's own downloader and cached locally, because Coil ships
 * without a network fetcher in this build — an http model silently rendered
 * blank, which is why images "weren't working".
 */
@Composable
private fun NoteImage(
    alt: String,
    url: String,
    noteId: String,
    onZoom: (model: Any?, label: String) -> Unit,
) {
    val local = remember(url, noteId) {
        com.speechnotes.app.logic.NoteImageStore.resolveLocalFile(url, noteId)
    }
    if (local != null) {
        coil3.compose.AsyncImage(
            model = local,
            contentDescription = alt.ifBlank { "Image" },
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onZoom(local, alt.ifBlank { "Image" }) },
        )
        return
    }
    if (url.startsWith("http", ignoreCase = true)) {
        RemoteNoteImage(alt, url, onZoom)
        return
    }
    // Nothing resolvable: show the alt text rather than a broken glyph.
    Text(
        if (alt.isNotBlank()) alt else "[image]",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** A remote image, downloaded through the app's OkHttp and cached on disk. */
@Composable
private fun RemoteNoteImage(alt: String, url: String, onZoom: (model: Any?, label: String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val file = remember(url) {
        val dir = java.io.File(
            com.speechnotes.app.logic.AppFiles.notesImagesDir, "_remote",
        )
        dir.mkdirs()
        val name = com.speechnotes.app.logic.TextHash.of(url).toString(16) + ".img"
        val target = java.io.File(dir, name)
        if (target.isFile && target.length() > 0) target else null
    }
    if (file != null) {
        coil3.compose.AsyncImage(
            model = file,
            contentDescription = alt.ifBlank { "Image" },
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onZoom(file, alt.ifBlank { "Image" }) },
        )
        return
    }
    // Kick off the fetch; the recomposition that follows the file appearing
    // swaps the placeholder for the real image.
    androidx.compose.runtime.LaunchedEffect(url) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching
                    val bytes = response.body?.bytes() ?: return@runCatching
                    val dir = java.io.File(com.speechnotes.app.logic.AppFiles.notesImagesDir, "_remote")
                    dir.mkdirs()
                    val name = com.speechnotes.app.logic.TextHash.of(url).toString(16) + ".img"
                    java.io.File(dir, name).writeBytes(bytes)
                }
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** A real grid for markdown tables, horizontally scrollable when wide. */
@Composable
private fun MarkdownTable(headers: List<String>, rows: List<List<String>>) {
    val horizontal = rememberScrollState()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .horizontalScroll(horizontal)
                .padding(8.dp),
        ) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                headers.forEach { cell ->
                    MarkdownStyledText.Text(
                        cell,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row {
                    headers.indices.forEach { i ->
                        MarkdownStyledText.Text(
                            row.getOrElse(i) { "" },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
