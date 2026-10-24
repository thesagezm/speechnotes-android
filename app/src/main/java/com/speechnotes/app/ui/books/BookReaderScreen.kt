package com.speechnotes.app.ui.books

import android.media.MediaPlayer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import com.speechnotes.app.speech.SpeechState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import coil3.compose.AsyncImage
import com.speechnotes.app.data.LogStore
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The book reading surfaces, one per format (mirrors the iOS BookWebView /
 * BookPDFReaderView / BookAudioReaderView trio):
 *
 *  - EPUB → epub.js in a WebView served through WebViewAssetLoader (assets +
 *    the stored original file under one https origin, so epub.js's fetch
 *    works), paginated flow with tap-zone/swipe page turns, TOC + theme +
 *    font controls, and "listen" starting AT the visible spot.
 *  - PDF → lazily rendered page bitmaps (PdfBox) with a jump slider, and
 *    "listen" starting at the visible page.
 *  - Audio → its own MediaPlayer player: chapter track, seek, resume —
 *    deliberately NOT routed through SpeechPlayer, same as the iOS app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(vm: SpeechnotesViewModel, bookId: String, onBack: () -> Unit) {
    val books by vm.books.books.collectAsState()
    val book = books.firstOrNull { it.id == bookId } ?: run {
        // Deleted while open (or an unknown id) — just leave.
        androidx.compose.runtime.LaunchedEffect(bookId) { onBack() }
        return
    }

    // Every reader carries its own transport — the global mini-player must
    // not stack on top of it (miniPlayerSuppressed parity). Releasing on
    // dispose brings the bar (and the lock screen) back.
    DisposableEffect(bookId) {
        vm.miniPlayerSuppressed = true
        onDispose { vm.miniPlayerSuppressed = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        book.author?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (book.format) {
                Book.FORMAT_EPUB -> EpubReader(vm, book)
                Book.FORMAT_PDF -> PdfReader(vm, book)
                else -> AudioReader(vm, book)
            }
        }
    }
}

// MARK: - EPUB

private class ReaderBridge(val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun post(json: String) = onMessage(json)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpubReader(vm: SpeechnotesViewModel, book: Book) {
    val context = LocalContext.current
    val appearance by vm.readerAppearance.collectAsState()
    var showToc by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    // Fallback TOC (label + href) from epub.js, used only when the manifest
    // has no saved toc rows.
    var hrefToc by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var currentChapter by remember(book.id) { mutableStateOf(book.position - 1) }
    var chromeHidden by remember(book.id) { mutableStateOf(false) }
    // Total spine items, reported by reader.js on relocation. Drives the
    // chapter-stepper's disabled ends and the progress label.
    var spineCount by remember(book.id) { mutableStateOf(book.spineCount ?: 0) }
    // epub.js fixes flow at rendition creation, so a flow switch rebuilds the
    // shell webview (iOS reloadToken parity). The veil covers the rebuild.
    var reloadToken by remember(book.id) { mutableStateOf(0) }
    var lastCfi by remember(book.id) { mutableStateOf(book.cfi) }

    // E1: reading-time tracking while THIS reader is on screen
    // (BookReaderView.swift's readingRecorder parity).
    DisposableEffect(book.id) {
        vm.readingRecorder.begin(book.id)
        onDispose { vm.readingRecorder.end() }
    }

    val messageFlow = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(Unit) {
        // Reader.js messages are handled on a JS thread; hop through state.
        messageFlow.collect { json ->
            val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return@collect
            when (obj.optString("type")) {
                "relocated" -> {
                    val index = obj.optInt("index", 0)
                    val fraction = obj.optDouble("fraction", 0.0)
                    val cfi: String? = obj.optString("cfi").takeIf { it != "null" && it.isNotBlank() }
                    val total = obj.optInt("total", 0)
                    currentChapter = index
                    if (total > 0) spineCount = total
                    // One relocation fresher than the manifest value — exactly
                    // what a flow-switch reload needs to land back in place.
                    if (cfi != null) lastCfi = cfi
                    vm.saveBookPosition(book.id, index + 1, fraction, cfi)
                }
                "toc" -> {
                    val items = ArrayList<Pair<String, String>>()
                    val arr = obj.optJSONArray("items") ?: return@collect
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val href = item.optString("href").takeIf { it.isNotBlank() } ?: continue
                        items.add(item.optString("label") to href)
                    }
                    hrefToc = items
                }
                "chromeTap" -> chromeHidden = !chromeHidden
                "pageTurn" -> {
                    val fn = if (obj.optString("dir") == "prev") "readerPrev" else "readerNext"
                    webViewRef.value?.evaluateJavascript("window.$fn && window.$fn();", null)
                }
                "error" -> {
                    val message = obj.optString("message")
                    LogStore.error("EpubReader: ${message}")
                    vm.showToast("EPUB reader error — see Logs")
                }
            }
        }
    }

    // Live appearance push: one merged JSON command, debounced so a slider
    // drag doesn't fire a bridge call per step (iOS applyAppearanceDebounced).
    LaunchedEffect(appearance) {
        webViewRef.value?.let { view ->
            kotlinx.coroutines.delay(150)
            view.evaluateJavascript(appearance.jsCommand(), null)
        }
    }

    // A fresh webview (first open or flow-switch reload) resumes auto-scroll
    // when the persisted toggle says so — the JS loop never survives the
    // reload itself. Paginated flow has nothing to scroll.
    LaunchedEffect(reloadToken) {
        if (appearance.autoScroll && !appearance.flowIsPaginated) {
            webViewRef.value?.let { view ->
                kotlinx.coroutines.delay(400)
                view.evaluateJavascript(appearance.autoScrollCommand(), null)
            }
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = !chromeHidden,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                EpubChapterBar(
                    chapterIndex = currentChapter,
                    totalChapters = spineCount,
                    label = currentChapterLabel(book, currentChapter, spineCount),
                    onPrev = { goChapter(webViewRef, currentChapter - 1) },
                    onNext = { goChapter(webViewRef, currentChapter + 1) },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !chromeHidden,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                EpubReaderBottomBar(
                    vm = vm,
                    book = book,
                    currentChapter = currentChapter,
                    onShowToc = { showToc = true },
                    onShowAppearance = { showAppearance = true },
                    onFontDown = { vm.setReaderFontSize((appearance.fontSize - 10).coerceAtLeast(70)) },
                    onFontUp = { vm.setReaderFontSize((appearance.fontSize + 10).coerceAtMost(200)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // Keyed on the flow token: a flow switch drops the old WebView and
            // builds a fresh one whose URL carries the new flow, exactly like
            // iOS's .id(reloadToken) on BookWebView.
            key(reloadToken) {
                AndroidView(
                    factory = { ctx ->
                    val assetLoader = WebViewAssetLoader.Builder()
                        .setDomain("appassets.androidplatform.net")
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                        .addPathHandler("/books/", WebViewAssetLoader.InternalStoragePathHandler(ctx, AppFiles.booksDir))
                        .build()
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                            ) = assetLoader.shouldInterceptRequest(request.url)

                            override fun onPageFinished(view: WebView, url: String) {
                                // Theme must follow the app; reader.js applies the
                                // query param at load, this keeps it in sync.
                                view.evaluateJavascript(
                                    "window.readerTheme && window.readerTheme('${appearance.theme}');",
                                    null,
                                )
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                                // The reader's JS errors must land somewhere reachable —
                                // a blank reader with nothing in Logs was undebuggable.
                                LogStore.info(
                                    "EpubReader JS ${consoleMessage?.messageLevel()}: ${consoleMessage?.message()} " +
                                        "(${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})"
                                )
                                return true
                            }
                        }
                        addJavascriptInterface(ReaderBridge { json -> messageFlow.value = json }, "AndroidReaderBridge")
                        webViewRef.value = this
                        val chapterParam = (book.position - 1).coerceAtLeast(0)
                        // lastCfi, not book.cfi: one relocation fresher, so a
                        // flow switch lands back where the reader was.
                        val cfiSource = lastCfi ?: book.cfi
                        val cfiParam = cfiSource?.let { "&cfi=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
                        val extension = AppFiles.bookDirectory(book.id).listFiles()
                            ?.firstOrNull { it.name.startsWith("original.") }?.name?.removePrefix("original.") ?: "epub"
                        val query = appearance.queryItems().entries.joinToString("&") { (k, v) ->
                            "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
                        }
                        loadUrl(
                            "https://appassets.androidplatform.net/assets/index-android.html" +
                                "?bookPath=/books/${book.id}/original.$extension&chapter=$chapterParam&$query$cfiParam"
                        )
                    }
                },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (showToc) {
            ModalBottomSheet(onDismissRequest = { showToc = false }) {
                Text(
                    "Contents",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                // The manifest's toc rows (label + SPINE index, resolved at
                // import) are the source of truth. The epub.js nav list is a
                // fallback for books whose manifest predates the toc field.
                LazyColumn(Modifier.padding(bottom = 24.dp)) {
                    if (book.toc.isNotEmpty()) {
                        itemsIndexed(book.toc, key = { i, _ -> "m$i" }) { _, entry ->
                            ListItem(
                                headlineContent = { Text(entry.label) },
                                modifier = Modifier.clickable {
                                    showToc = false
                                    currentChapter = entry.chapterIndex
                                    vm.saveBookPosition(book.id, entry.chapterIndex + 1, 0.0, null)
                                    webViewRef.value?.evaluateJavascript(
                                        "window.readerGoChapter && window.readerGoChapter(${entry.chapterIndex});", null,
                                    )
                                },
                            )
                        }
                    } else {
                        itemsIndexed(hrefToc, key = { i, _ -> "h$i" }) { _, (label, href) ->
                            ListItem(
                                headlineContent = { Text(label) },
                                modifier = Modifier.clickable {
                                    showToc = false
                                    val encoded = java.net.URLEncoder.encode(href, "UTF-8")
                                    webViewRef.value?.evaluateJavascript(
                                        "window.readerGoToHref && window.readerGoToHref(\"$encoded\");", null,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showAppearance) {
            ReaderAppearanceSheet(
                appearance = appearance,
                onDismiss = { showAppearance = false },
                onTheme = { jsTheme ->
                    // The shelf's segmented control writes readerTheme; keep
                    // both paths writing the same value.
                    vm.setReaderTheme(
                        when (jsTheme) {
                            "sepia" -> com.speechnotes.app.ui.theme.ReaderTheme.SEPIA
                            "dark" -> com.speechnotes.app.ui.theme.ReaderTheme.DARK
                            "trueBlack" -> com.speechnotes.app.ui.theme.ReaderTheme.TRUE_BLACK
                            else -> com.speechnotes.app.ui.theme.ReaderTheme.WHITE
                        }
                    )
                },
                onFontSize = { vm.setReaderFontSize(it) },
                onFlow = { flow ->
                    val changed = flow != appearance.flow
                    vm.setReaderFlow(flow)
                    if (changed) reloadToken++
                },
                onFont = { vm.setReaderFont(it) },
                onLineHeight = { vm.setReaderLineHeight(it) },
                onParaSpacing = { vm.setReaderParaSpacing(it) },
                onLetterSpacing = { vm.setReaderLetterSpacing(it) },
                onMargin = { vm.setReaderMargin(it) },
                onRespectStyles = { vm.setReaderRespectStyles(it) },
                onAutoScroll = { vm.setReaderAutoScroll(it) },
                onAutoScrollSpeed = { vm.setReaderAutoScrollSpeed(it) },
                onTapTurn = { vm.setReaderTapTurn(it) },
                onSwipeTurn = { vm.setReaderSwipeTurn(it) },
                onTapInverted = { vm.setReaderTapInverted(it) },
            )
        }
    }
}

private fun goChapter(webViewRef: androidx.compose.runtime.MutableState<WebView?>, index: Int) {
    webViewRef.value?.evaluateJavascript(
        "window.readerGoChapter && window.readerGoChapter($index);", null,
    )
}

/** "Chapter 3 of 41 · 12%", iOS chapterBar parity. */
private fun currentChapterLabel(book: Book, index: Int, total: Int): String {
    val label = book.toc.firstOrNull { it.chapterIndex == index }?.label
        ?: book.chapters.getOrNull(index)?.label
    return when {
        total > 0 && label != null -> "$label · ${index + 1}/$total"
        total > 0 -> "Chapter ${index + 1} of $total"
        label != null -> label
        else -> "Chapter ${index + 1}"
    }
}

/**
 * Chapter stepper above the reader (iOS chapterBar). epub.js crosses spine
 * boundaries on its own in paginated flow, but the explicit arrows are how
 * iOS navigates and they read at a glance.
 */
@Composable
private fun EpubChapterBar(
    chapterIndex: Int,
    totalChapters: Int,
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev, enabled = chapterIndex > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous chapter")
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onNext,
                enabled = totalChapters <= 0 || chapterIndex < totalChapters - 1,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next chapter")
            }
        }
    }
}

/**
 * The reader's transport row. The listen button mirrors the SpeechPlayer
 * state for THIS book — pause/resume/stop while narrating, play otherwise —
 * instead of a static Play icon that silently re-narrated the chapter.
 */
@Composable
private fun EpubReaderBottomBar(
    vm: SpeechnotesViewModel,
    book: Book,
    currentChapter: Int,
    onShowToc: () -> Unit,
    onShowAppearance: () -> Unit,
    onFontDown: () -> Unit,
    onFontUp: () -> Unit,
) {
    val playState by vm.player.state.collectAsState()
    // Compose-visible narration state: both flows are collected here, so the
    // icon flips the moment the engine starts — the old isBookNarrating()
    // call read the StateFlows OUTSIDE composition and the button froze on
    // PlayArrow (the "play/pause doesn't change" report).
    val nowPlayingBookId by vm.player.nowPlayingBookId.collectAsState()
    val narratingThis = nowPlayingBookId == book.id && playState.isActive

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onShowToc) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents")
            }
            IconButton(onClick = onShowAppearance) {
                Icon(Icons.Default.FormatSize, contentDescription = "Appearance")
            }
            IconButton(onClick = onFontDown) { Text("A-", style = MaterialTheme.typography.titleSmall) }
            IconButton(onClick = onFontUp) { Text("A+", style = MaterialTheme.typography.titleSmall) }
            FilledIconButton(onClick = {
                if (narratingThis) vm.player.toggle()
                else vm.toggleBookChapter(book, currentChapter, fraction = book.positionFraction)
            }) {
                Icon(
                    when {
                        narratingThis && playState == SpeechState.SPEAKING -> Icons.Default.Pause
                        narratingThis -> Icons.Default.Stop
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = when {
                        narratingThis && playState == SpeechState.SPEAKING -> "Pause narration"
                        narratingThis -> "Cancel narration"
                        else -> "Listen from here"
                    },
                )
            }
            if (narratingThis && playState != SpeechState.GENERATING) {
                IconButton(onClick = { vm.player.stop() }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop narration")
                }
            }
        }
    }
}

/**
 * The reader's appearance sheet — the whole iOS appearanceForm: theme (incl.
 * True Black), page view (scroll/pages), page-turn switches, typography
 * (font, size, line height, paragraph/letter spacing, respect book styling)
 * and margins. Every row writes straight through the ViewModel; the sheet
 * itself only reads the shared state, so there is no local copy to sync.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReaderAppearanceSheet(
    appearance: com.speechnotes.app.model.ReaderAppearance,
    onDismiss: () -> Unit,
    onTheme: (String) -> Unit,
    onFontSize: (Int) -> Unit,
    onFlow: (String) -> Unit,
    onFont: (String) -> Unit,
    onLineHeight: (Double) -> Unit,
    onParaSpacing: (Double) -> Unit,
    onLetterSpacing: (Double) -> Unit,
    onMargin: (Int) -> Unit,
    onRespectStyles: (Boolean) -> Unit,
    onAutoScroll: (Boolean) -> Unit,
    onAutoScrollSpeed: (Double) -> Unit,
    onTapTurn: (Boolean) -> Unit,
    onSwipeTurn: (Boolean) -> Unit,
    onTapInverted: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Light" to "light", "Sepia" to "sepia", "Dark" to "dark", "True Black" to "trueBlack")
                    .forEachIndexed { index, (label, key) ->
                        SegmentedButton(
                            selected = appearance.theme == key,
                            onClick = { onTheme(key) },
                            shape = SegmentedButtonDefaults.itemShape(index, 4),
                            label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
            }

            Spacer(Modifier.height(16.dp))
            Text("Page view", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !appearance.flowIsPaginated,
                    onClick = { onFlow("scrolled") },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text("Scroll") },
                )
                SegmentedButton(
                    selected = appearance.flowIsPaginated,
                    onClick = { onFlow("paginated") },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text("Pages") },
                )
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                label = "Auto-scroll",
                checked = appearance.autoScroll,
                enabled = !appearance.flowIsPaginated,
                onChange = onAutoScroll,
            )
            if (appearance.autoScroll && !appearance.flowIsPaginated) {
                SliderRow(
                    label = "Speed · ${appearance.autoScrollSpeed.toInt()} px/s",
                    value = appearance.autoScrollSpeed.toFloat(),
                    range = 10f..200f,
                    steps = 37,
                    onChange = { onAutoScrollSpeed(it.toDouble()) },
                )
            }
            Text(
                if (appearance.flowIsPaginated) {
                    "Auto-scroll works in Scroll mode."
                } else {
                    "Auto-scroll turns the pages for you and advances to the next chapter at the bottom."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("Page turning", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                label = "Tap page edges to turn",
                checked = appearance.tapTurn,
                enabled = appearance.flowIsPaginated,
                onChange = onTapTurn,
            )
            if (appearance.tapTurn && appearance.flowIsPaginated) {
                SwitchRow(
                    label = "Swap left and right",
                    checked = appearance.tapInverted,
                    enabled = true,
                    onChange = onTapInverted,
                )
            }
            SwitchRow(
                label = "Swipe to turn",
                checked = appearance.swipeTurn,
                enabled = appearance.flowIsPaginated,
                onChange = onSwipeTurn,
            )
            Text(
                if (appearance.flowIsPaginated) {
                    "Tap the left or right third of the page to turn it; the middle toggles the bars. Scroll mode turns pages by swiping."
                } else {
                    "Tap zones and page-turn swipes apply in Pages mode."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("Typography", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("Font", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Book" to "book", "Serif" to "serif", "Sans" to "sans", "Mono" to "mono")
                    .forEachIndexed { index, (label, key) ->
                        SegmentedButton(
                            selected = appearance.font == key,
                            onClick = { onFont(key) },
                            shape = SegmentedButtonDefaults.itemShape(index, 4),
                            label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
            }
            SliderRow(
                label = "Text size · ${appearance.fontSize}%",
                value = appearance.fontSize.toFloat(),
                range = 70f..200f,
                steps = 12,
                onChange = { onFontSize(it.toInt()) },
            )
            SliderRow(
                label = "Line height · ${"%.1f".format(appearance.lineHeight)}×",
                value = appearance.lineHeight.toFloat(),
                range = 1.2f..2.4f,
                steps = 11,
                onChange = { onLineHeight(it.toDouble()) },
            )
            SliderRow(
                label = "Paragraph spacing · ${"%.1f".format(appearance.paraSpacing)} em",
                value = appearance.paraSpacing.toFloat(),
                range = 0f..2f,
                steps = 19,
                onChange = { onParaSpacing(it.toDouble()) },
            )
            SliderRow(
                label = "Letter spacing · ${"%.1f".format(appearance.letterSpacing)} pt",
                value = appearance.letterSpacing.toFloat(),
                range = 0f..3f,
                steps = 5,
                onChange = { onLetterSpacing(it.toDouble()) },
            )
            SwitchRow(
                label = "Respect book styling",
                checked = appearance.respectStyles,
                enabled = true,
                onChange = onRespectStyles,
            )
            Text(
                if (appearance.respectStyles) {
                    "The publisher's own fonts and spacing are kept; only colors apply."
                } else {
                    "Applies to the book pages."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("Margins", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            SliderRow(
                label = "Page margin · ${appearance.margin} px",
                value = appearance.margin.toFloat(),
                range = 0f..48f,
                steps = 11,
                enabled = !appearance.flowIsPaginated,
                onChange = { onMargin(it.toInt()) },
            )
            Text(
                if (appearance.flowIsPaginated) "Margins follow the book in Pages mode." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

// MARK: - PDF

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun PdfReader(vm: SpeechnotesViewModel, book: Book) {
    val context = LocalContext.current
    var pageCount by remember(book.id) { mutableStateOf(0) }
    var pageIndex by remember(book.id) { mutableStateOf((book.position - 1).coerceAtLeast(0)) }
    val playState by vm.player.state.collectAsState()
    val nowPlayingBookId by vm.player.nowPlayingBookId.collectAsState()
    val narratingThis = nowPlayingBookId == book.id && playState.isActive
    val bitmaps = remember(book.id) { mutableStateMapOf<Int, android.graphics.Bitmap>() }
    // PDDocument.load OFF the main thread (P0-5): parsing a large or scanned
    // PDF can take seconds — this used to run inside remember{} synchronously,
    // freezing the whole app the moment the reader opened. The render area
    // shows its spinner until the first bitmap lands.
    var pdf by remember(book.id) {
        mutableStateOf<com.tom_roush.pdfbox.pdmodel.PDDocument?>(null)
    }

    LaunchedEffect(book.id) {
        val doc = withContext(Dispatchers.IO) {
            runCatching {
                val file = AppFiles.bookDirectory(book.id).listFiles()?.firstOrNull { it.name.startsWith("original.") }
                file?.let { com.tom_roush.pdfbox.pdmodel.PDDocument.load(it) }
            }.getOrNull()
        }
        pdf = doc
    }

    DisposableEffect(book.id) {
        onDispose { runCatching { pdf?.close() } }
    }

    LaunchedEffect(pdf) {
        val doc = pdf ?: return@LaunchedEffect
        pageCount = withContext(Dispatchers.IO) { doc.numberOfPages }
    }

    // Render the visible page (plus one ahead) off the main thread.
    LaunchedEffect(pageIndex, pageCount) {        val doc = pdf ?: return@LaunchedEffect
        for (page in listOf(pageIndex, pageIndex + 1)) {
            if (page !in bitmaps && page in 0 until pageCount) {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val w = context.resources.displayMetrics.widthPixels
                        val scale = (w / 612f).coerceIn(1f, 2.2f)
                        com.tom_roush.pdfbox.rendering.PDFRenderer(doc).renderImage(page, scale)
                    }.getOrNull()
                }
                if (bitmap != null) {
                    if (bitmaps.size > 6) {
                        bitmaps.keys.firstOrNull { it < page - 1 }?.let { stale ->
                            bitmaps.remove(stale)?.recycle()
                        }
                    }
                    bitmaps[page] = bitmap
                }
            }
        }
        vm.saveBookPosition(book.id, pageIndex + 1, 0.0, null)
    }

    // Outline navigation (iOS BookPDFReaderView parity): the PDF's outline
    // tree with DEPTH (C4: collapsible subtrees), current entry highlighted,
    // scroll-to-current, chapters fallback for outline-less documents.
    var showOutline by remember(book.id) { mutableStateOf(false) }

    // E1: reading-time tracking while the PDF reader is on screen.
    DisposableEffect(book.id) {
        vm.readingRecorder.begin(book.id)
        onDispose { vm.readingRecorder.end() }
    }
    val outlineRows = remember(book.id, pdf) {
        val doc = pdf ?: return@remember emptyList<Triple<String, Int, Int>>()
        runCatching {
            // (label, page, depth) — depth drives the indent and the fold.
            val result = ArrayList<Triple<String, Int, Int>>()
            fun walk(node: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode?, depth: Int) {
                var current = node?.firstChild
                while (current != null) {
                    if (current is com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem) {
                        val title = current.title ?: "Untitled"
                        val dest = runCatching { current.destination }.getOrNull()
                        val page = (dest as? com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination)?.findPageNumber()
                        if (page != null && page >= 0) result.add(Triple(title, page, depth))
                        walk(current, depth + 1)
                    } else {
                        walk(current, depth)
                    }
                    current = current.nextSibling
                }
            }
            walk(doc.documentCatalog.documentOutline, 0)
            if (result.isEmpty()) {
                // Fallback: the resolved chapters, navigated by start page.
                book.chapters.forEach { chapter ->
                    result.add(Triple(chapter.label, chapter.startPage, 0))
                }
            }
            result
        }.getOrDefault(emptyList())
    }
    val currentOutlineId = outlineRows.indexOfLast { it.second <= pageIndex }

    // C3: pinch-zoom + pan around the page bitmap (iOS fit-width parity):
    // transformable scale/offset clamped 1×–4×, double-tap resets.
    var zoom by remember(book.id) { mutableStateOf(1f) }
    var zoomOffset by remember(book.id) { mutableStateOf(Offset.Zero) }

    // C4: collapsible subtrees — collapsed set holds indices whose CHILDREN
    // are hidden; a row is hidden when any ANCESTOR index is collapsed.
    val collapsedOutlineRows = remember(book.id) { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }
    val visibleOutlineRows = if (collapsedOutlineRows.isEmpty()) outlineRows.withIndex().toList()
    else outlineRows.withIndex().filter { (index, _) ->
        var hidden = false
        for (a in 0 until index) {
            if (collapsedOutlineRows.containsKey(a) &&
                outlineRows[a].third < outlineRows[index].third
            ) {
                // a is an ancestor candidate: hidden if every row between a
                // and index is at least as deep as a's child depth.
                var allDeeper = true
                for (m in a + 1..index) {
                    if (outlineRows[m].third <= outlineRows[a].third) {
                        allDeeper = false
                        break
                    }
                }
                if (allDeeper) {
                    hidden = true
                    break
                }
            }
        }
        !hidden
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            val transformState = androidx.compose.foundation.gestures.TransformableState { zoomChange, panChange, _ ->
                zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                zoomOffset += panChange
                // At 1× snap the pan home so the page always starts framed;
                // above 1× the user pans freely.
                if (zoom <= 1.01f) zoomOffset = Offset.Zero
            }

            bitmaps[pageIndex]?.let { bitmap ->
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(pageIndex) {
                            detectTapGestures(onDoubleTap = {
                                zoom = 1f
                                zoomOffset = Offset.Zero
                            })
                        }
                        .transformable(transformState)
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = zoomOffset.x,
                            translationY = zoomOffset.y,
                        ),
                )
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        }
        // Two rows on purpose. Everything shared one row before — contents,
        // prev, page label, a weighted Slider, play, next — and at 360 dp the
        // fixed 48 dp icon buttons plus the label left the Slider at its own
        // minimum, so the LAST children (play, next-page) were measured off
        // the right edge and never drawn. That is why page turning was only
        // reachable through the slider.
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Slider(
                value = if (pageCount > 1) pageIndex.toFloat() / (pageCount - 1) else 0f,
                onValueChange = {
                    pageIndex = (it * (pageCount - 1).coerceAtLeast(0)).toInt()
                        .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showOutline = true }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents")
                }
                FilledTonalIconButton(
                    onClick = { if (pageIndex > 0) pageIndex-- },
                    enabled = pageIndex > 0,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                }
                Text(
                    "Page ${pageIndex + 1} of $pageCount",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    // Weighted, not min-width: a fixed 104dp label plus six
                    // 48dp buttons overflowed 360dp screens when the Stop
                    // button joined during narration and pushed it (and the
                    // next buttons) off the right edge.
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                FilledTonalIconButton(
                    onClick = { if (pageIndex < pageCount - 1) pageIndex++ },
                    enabled = pageIndex < pageCount - 1,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
                }
                FilledIconButton(onClick = {
                    // Pause/resume the ongoing narration; start the chain for
                    // the chapter containing the CURRENT page — the text pick
                    // is pinned to that page (SpeechPlayer honors the offset),
                    // not to wherever the chapter's bookmark last landed.
                    if (narratingThis) vm.player.toggle()
                    else {
                        val chapter = book.chapters.indexOfFirst { pageIndex in it.startPage..it.endPage }
                        vm.toggleBookChapter(book, if (chapter >= 0) chapter else 0, page = pageIndex)
                    }
                }) {
                    Icon(
                        when {
                            narratingThis && playState == SpeechState.SPEAKING -> Icons.Default.Pause
                            narratingThis -> Icons.Default.Stop
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = when {
                            narratingThis && playState == SpeechState.SPEAKING -> "Pause narration"
                            narratingThis -> "Cancel narration"
                            else -> "Listen from this page"
                        },
                    )
                }
                // The thin in-button spinner was near-invisible on device; the
                // Stop glyph during GENERATING (tap = cancel) reads clearly.
                if (narratingThis && playState != SpeechState.GENERATING) {
                    IconButton(onClick = { vm.player.stop() }) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop narration")
                    }
                }
            }
        }
    }

    if (showOutline) {
        ModalBottomSheet(onDismissRequest = { showOutline = false }) {
            Text(
                "Contents",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                itemsIndexed(visibleOutlineRows, key = { _, (index, _) -> index }) { _, (index, row) ->
                    val (label, page, depth) = row
                    val hasChildren = index + 1 < outlineRows.size && outlineRows[index + 1].third > depth
                    val collapsed = collapsedOutlineRows.containsKey(index)
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width((12 * depth).dp))
                                if (hasChildren) {
                                    Icon(
                                        if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = if (collapsed) "Expand" else "Collapse",
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable {
                                                if (collapsed) collapsedOutlineRows.remove(index)
                                                else collapsedOutlineRows[index] = true
                                            },
                                    )
                                } else {
                                    Spacer(Modifier.width(18.dp))
                                }
                                Text(
                                    label,
                                    fontWeight = if (index == currentOutlineId) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (index == currentOutlineId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        supportingContent = { Text("Page ${page + 1}") },
                        modifier = Modifier.clickable {
                            pageIndex = page
                            vm.saveBookPosition(book.id, page + 1, 0.0, null)
                            showOutline = false
                        },
                    )
                }
            }
        }
    }
}

// MARK: - Audio

/**
 * The audiobook reader BINDS to the app-level [com.speechnotes.app.speech.AudioBookPlayer]
 * — it owns no MediaPlayer. Playback lives in the ViewModel scope, so audio
 * survives leaving the reader (tab switches, back to the shelf), the mini
 * bar and the lock screen take over when it's closed, and position
 * persistence/auto-advance run app-wide.
 */
@Composable
private fun AudioReader(vm: SpeechnotesViewModel, book: Book) {
    val audio = vm.audioBooks
    val activeBook by audio.activeBook.collectAsState()
    val playing by audio.isPlaying.collectAsState()
    val liveChapterIndex by audio.chapterIndex.collectAsState()
    val liveChapterTitle by audio.chapterTitle.collectAsState()
    val elapsedMs by audio.elapsedMs.collectAsState()
    val durationMs by audio.durationMs.collectAsState()
    val speed by audio.speed.collectAsState()
    val chapters = book.audioChapters
    val listState = rememberLazyListState()
    val isActive = activeBook?.id == book.id

    // When THIS book is the live player, follow it; otherwise show the saved
    // position as the resume thumb.
    val chapterIndex = if (isActive) liveChapterIndex else vm.savedAudioChapter(book)
    val positionMs = if (isActive) elapsedMs else 0L
    val shownDuration = if (isActive) durationMs else 0L

    fun startPlayback(index: Int, offsetMs: Long) = audio.play(book, index, offsetMs)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (book.hasCover) {
                    AsyncImage(
                        model = AppFiles.bookCoverFile(book.id),
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxWidth(0.55f),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    (if (isActive) liveChapterTitle else null)
                        ?: chapters.getOrNull(chapterIndex)?.title ?: book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = if (shownDuration > 0) (positionMs.toFloat() / shownDuration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction ->
                        if (shownDuration > 0) {
                            audio.seekToMs((fraction * shownDuration).toLong())
                        }
                    },
                    enabled = isActive,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatClock(shownDuration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (isActive) audio.seekByMs(-15_000L) else startPlayback(chapterIndex, 0) }) {
                        Icon(Icons.Default.Replay10, contentDescription = "Back 15 seconds")
                    }
                    IconButton(onClick = {
                        val prev = (chapterIndex - 1).coerceAtLeast(0)
                        startPlayback(prev, 0)
                    }) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter") }
                    FilledIconButton(onClick = {
                        if (isActive) audio.togglePlay()
                        else startPlayback(chapterIndex, vm.savedAudioOffsetMs(book))
                    }) {
                        Icon(
                            if (isActive && playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isActive && playing) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = {
                        val next = (chapterIndex + 1).coerceAtMost((chapters.size - 1).coerceAtLeast(0))
                        startPlayback(next, 0)
                    }) { Icon(Icons.Default.SkipNext, contentDescription = "Next chapter") }
                    IconButton(onClick = { if (isActive) audio.seekByMs(15_000L) }) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 15 seconds")
                    }
                    Box {
                        var sleepMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { sleepMenu = true }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Sleep timer")
                        }
                        DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                            listOf(0, 5, 15, 30, 60).forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text(if (minutes == 0) "Sleep timer off" else "Sleep in $minutes min") },
                                    onClick = {
                                        if (minutes == 0) audio.clearSleepTimer()
                                        else audio.setSleepTimerMs(minutes * 60_000L)
                                        sleepMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Speed", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = speed,
                        onValueChange = { audio.setSpeed(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 29,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text("%.2f×".format(speed), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (chapters.isEmpty()) "No chapter metadata in this file" else "Chapters · ${chapters.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(chapters) { index, chapter ->
                val active = index == chapterIndex
                ListItem(
                    headlineContent = {
                        Text(
                            chapter.title,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    supportingContent = { Text(formatClock(chapter.endMs - chapter.startMs)) },
                    leadingContent = {
                        Icon(
                            if (active && isActive && playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play chapter ${index + 1}",
                            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { startPlayback(index, 0) },
                )
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
