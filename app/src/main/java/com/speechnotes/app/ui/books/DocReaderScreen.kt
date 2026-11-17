package com.speechnotes.app.ui.books

import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.speechnotes.app.data.LogStore
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.model.Book
import com.speechnotes.app.speech.SpeechState

/**
 * The headless doc formats' own reader — mobi/azw3/fb2/rtf/html/txt, the
 * speechnotes-linux-rust BookDoc design (crates/sn-store books.rs
 * ServedBookDoc): every one of those files reduces to one parsed BookDoc and
 * the reader serves its chapters as virtual `chapter-N` pages and its images
 * under `img/<name>`. NOTHING becomes EPUB — no epub.js, no converted copy
 * on disk; the file stays `original.<ext>`.
 *
 * Mechanically: a WebView loads the small `index-doc-android.html` shell
 * from the assets handler; a custom WebViewAssetLoader PathHandler at
 * `/docs/<bookId>/` answers every request from the ViewModel's
 * servedDocOrNull cache (synchronous — shouldInterceptRequest runs on a
 * WebView thread); `docreader.js` fetches `{bookPath}chapter-N`, mounts it
 * with a `<base href>` so the chapters' relative `img/NNNNNN` srcs resolve
 * to the same handler, applies the SAME readerAppearance JSON protocol as
 * reader.js (the Rust scheme mechanism — background !important on the html
 * element, body transparent — and the same typography controls on #content),
 * and reports scroll-fraction relocations through the shared
 * AndroidReaderBridge shape.
 *
 * Recorded divergence from the linux reader: scrolled flow only (linux
 * paginates bookdocs via foliate-js; column pagination is a follow-up), so
 * the appearance sheet hides its Page-view switch and the paginated
 * page-turn rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocReader(vm: SpeechnotesViewModel, book: Book) {
    val appearance by vm.readerAppearance.collectAsState()
    var showToc by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }
    // Landscape moves the transport to the trailing rail (iOS PlaybackRail),
    // the same shape the EPUB reader wears.
    val landscape = com.speechnotes.app.ui.playback.isLandscape()
    val playProgress by vm.player.progress.collectAsState()
    val playRate by vm.player.rateMultiplier.collectAsState()
    val playState by vm.player.state.collectAsState()
    val nowPlayingBookId by vm.player.nowPlayingBookId.collectAsState()
    val narratingThis = nowPlayingBookId == book.id && playState.isActive
    var currentChapter by remember(book.id) { mutableStateOf(book.position - 1) }
    var chromeHidden by remember(book.id) { mutableStateOf(false) }
    // The doc's chapter total is known at import (the manifest IS the parse's
    // own order) — no spine discovery delay like the epub reader's.
    val spineCount = book.spineCount ?: book.chapters.size
    // iOS bookBarMinimized parity: the portrait transport collapses to the
    // shared pill, the chapter bar goes with it.
    val barMinimized = com.speechnotes.app.ui.playback.rememberBookBarMinimized(vm)

    // E1: reading-time tracking while THIS reader is on screen.
    DisposableEffect(book.id) {
        vm.readingRecorder.begin(book.id)
        onDispose { vm.readingRecorder.end() }
    }

    // Warm the served-doc cache BEFORE the shell loads — the WebView's
    // request handler answers from the cache only (it has no coroutine).
    // The shell's own fetch retries cover the (rare) eviction during the
    // load, so the warm-up here is not a hard gate.
    LaunchedEffect(book.id) { vm.servedDoc(book.id) }

    val messageFlow = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(Unit) {
        messageFlow.collect { json ->
            val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return@collect
            when (obj.optString("type")) {
                "relocated" -> {
                    val index = obj.optInt("index", 0)
                    val fraction = obj.optDouble("fraction", 0.0)
                    currentChapter = index
                    vm.saveBookPosition(book.id, index + 1, fraction, null)
                }
                "chromeTap" -> chromeHidden = !chromeHidden
                "error" -> {
                    LogStore.error("DocReader: ${obj.optString("message")}")
                    vm.showToast("Document reader error — see Logs")
                }
            }
        }
    }

    // Live appearance push: the same one merged JSON command, debounced.
    LaunchedEffect(appearance) {
        webViewRef.value?.let { view ->
            kotlinx.coroutines.delay(150)
            view.evaluateJavascript(appearance.jsCommand(), null)
        }
    }
    // A fresh webview resumes auto-scroll when the persisted toggle says so.
    LaunchedEffect(appearance.autoScroll, appearance.autoScrollSpeed) {
        if (appearance.autoScroll) {
            webViewRef.value?.let { view ->
                kotlinx.coroutines.delay(400)
                view.evaluateJavascript(appearance.autoScrollCommand(), null)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AnimatedVisibility(
                visible = !chromeHidden && !barMinimized.value,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                EpubChapterBar(
                    chapterIndex = currentChapter,
                    totalChapters = spineCount,
                    label = currentChapterLabel(book, currentChapter, spineCount),
                    onPrev = { goDocChapter(webViewRef, currentChapter - 1) },
                    onNext = { goDocChapter(webViewRef, currentChapter + 1) },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !chromeHidden,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                if (barMinimized.value) {
                    com.speechnotes.app.ui.playback.MinimizedPlaybackPill(
                        isPlaying = narratingThis && playState == SpeechState.SPEAKING,
                        isGenerating = narratingThis && playState == SpeechState.GENERATING,
                        isPlayEnabled = true,
                        progressText = compactProgressLabel(
                            playProgress,
                            narratingThis && playState == SpeechState.GENERATING,
                        ),
                        onTogglePlay = {
                            if (narratingThis) vm.player.toggle()
                            else vm.toggleBookChapter(book, currentChapter, fraction = book.positionFraction)
                        },
                        onExpand = { com.speechnotes.app.ui.playback.setBookBarMinimized(vm, false) },
                        onStepBack = { goDocChapter(webViewRef, currentChapter - 1) },
                        onStepForward = { goDocChapter(webViewRef, currentChapter + 1) },
                        stepBackEnabled = currentChapter > 0,
                        stepForwardEnabled = currentChapter < spineCount - 1,
                    )
                } else {
                    EpubReaderBottomBar(
                        vm = vm,
                        book = book,
                        currentChapter = currentChapter,
                        playControlsVisible = !landscape,
                        onMinimize = { com.speechnotes.app.ui.playback.setBookBarMinimized(vm, true) },
                        onShowToc = { showToc = true },
                        onShowAppearance = { showAppearance = true },
                        onFontDown = { vm.setReaderFontSize((appearance.fontSize - 10).coerceAtLeast(70)) },
                        onFontUp = { vm.setReaderFontSize((appearance.fontSize + 10).coerceAtMost(200)) },
                    )
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        val bookId = book.id
                        val docBase = "/docs/$bookId/"
                        // The doc reader's virtual filesystem: every fetch
                        // the shell makes lands here, answered from the
                        // ViewModel's served-doc cache — the same shape the
                        // linux reader's scheme handler serves.
                        val docHandler = object : WebViewAssetLoader.PathHandler {
                            private fun notFound() = WebResourceResponse(
                                "text/plain", "utf-8", 404, "Not found",
                                mapOf(), java.io.ByteArrayInputStream(ByteArray(0)),
                            )

                            // This androidx.webkit's PathHandler hands over the
                            // raw path string (the newer Request-carrying
                            // signature is not in this build).
                            override fun handle(path: String): WebResourceResponse? {
                                val name = path.removePrefix(docBase)
                                if (name.isEmpty() || name.contains("..")) return null
                                val served = vm.servedDocOrNull(bookId) ?: return notFound()
                                val bytes = served.read(name) ?: return notFound()
                                val mime = served.mimeFor(name)
                                return WebResourceResponse(
                                    mime,
                                    if (mime.startsWith("text/")) "utf-8" else null,
                                    java.io.ByteArrayInputStream(bytes),
                                )
                            }
                        }
                        val assetLoader = WebViewAssetLoader.Builder()
                            .setDomain("appassets.androidplatform.net")
                            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                            .addPathHandler(docBase, docHandler)
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
                                    view.evaluateJavascript(
                                        "window.readerTheme && window.readerTheme('${appearance.theme}');",
                                        null,
                                    )
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(
                                    consoleMessage: android.webkit.ConsoleMessage,
                                ): Boolean {
                                    LogStore.info(
                                        "DocReader JS ${consoleMessage?.messageLevel()}: ${consoleMessage?.message()} " +
                                            "(${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})"
                                    )
                                    return true
                                }
                            }
                            addJavascriptInterface(
                                ReaderBridge { json -> messageFlow.value = json },
                                "AndroidReaderBridge",
                            )
                            webViewRef.value = this
                            val chapterParam = (book.position - 1).coerceAtLeast(0)
                            val fractionParam = if (book.positionFraction > 0.0) {
                                "&fraction=" + book.positionFraction.toString()
                            } else ""
                            val query = appearance.queryItems().entries.joinToString("&") { (k, v) ->
                                "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
                            }
                            loadUrl(
                                "https://appassets.androidplatform.net/assets/index-doc-android.html" +
                                    "?bookPath=" + java.net.URLEncoder.encode(docBase, "UTF-8") +
                                    "&chapter=$chapterParam$fractionParam&$query"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (landscape) {
                com.speechnotes.app.ui.playback.PlaybackRail(
                    vm = vm,
                    progress = playProgress,
                    isGenerating = playState == SpeechState.GENERATING,
                    isSpeaking = playState == SpeechState.SPEAKING,
                    isPlayEnabled = true,
                    onTogglePlay = {
                        if (narratingThis) vm.player.toggle()
                        else vm.toggleBookChapter(book, currentChapter, fraction = book.positionFraction)
                    },
                    onStop = if (narratingThis && playState != SpeechState.GENERATING) {
                        { vm.player.stop() }
                    } else null,
                    voiceLabel = vm.player.currentVoiceDescription,
                    onChangeVoice = { showVoicePicker = true },
                    stepLabel = "Ch ${currentChapter + 1}${if (spineCount > 0) "/$spineCount" else ""}",
                    onStepBack = { goDocChapter(webViewRef, currentChapter - 1) },
                    onStepForward = { goDocChapter(webViewRef, currentChapter + 1) },
                    stepBackEnabled = currentChapter > 0,
                    stepForwardEnabled = currentChapter < spineCount - 1,
                    rate = playRate,
                    onRateChange = { vm.player.setRateMultiplier(it) },
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
                // The doc's own contents (a MOBI's CNCX, an FB2's sections)
                // with depth indentation; importBookDoc derived flat rows
                // when the format carries none.
                LazyColumn(Modifier.padding(bottom = 24.dp)) {
                    itemsIndexed(book.toc, key = { i, _ -> "m$i" }) { _, entry ->
                        ListItem(
                            headlineContent = { Text(entry.label) },
                            modifier = Modifier
                                .padding(start = (entry.depth * 16).dp)
                                .clickable {
                                    showToc = false
                                    currentChapter = entry.chapterIndex
                                    vm.saveBookPosition(book.id, entry.chapterIndex + 1, 0.0, null)
                                    goDocChapter(webViewRef, entry.chapterIndex)
                                    // R3 (iOS 583acdb): a Contents jump during
                                    // read-along MOVES the speech.
                                    if (vm.isBookNarrating(book)) {
                                        vm.narrateFromSpot(book, entry.chapterIndex)
                                    }
                                },
                        )
                    }
                }
            }
        }

        if (showAppearance) {
            // Scrolled flow only — no Page-view switch, no paginated page-turn
            // rows (the recorded divergence from the linux foliate reader).
            ReaderAppearanceSheet(
                appearance = appearance,
                showPagination = false,
                onDismiss = { showAppearance = false },
                onTheme = { jsTheme ->
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
                onFlow = { /* scrolled only — no UI reaches this */ },
                onFont = { vm.setReaderFont(it) },
                onLineHeight = { vm.setReaderLineHeight(it) },
                onParaSpacing = { vm.setReaderParaSpacing(it) },
                onLetterSpacing = { vm.setReaderLetterSpacing(it) },
                onMargin = { vm.setReaderMargin(it) },
                onRespectStyles = { vm.setReaderRespectStyles(it) },
                onAutoScroll = { vm.setReaderAutoScroll(it) },
                onAutoScrollSpeed = { vm.setReaderAutoScrollSpeed(it) },
                onTapTurn = { /* paginated only */ },
                onSwipeTurn = { /* paginated only */ },
                onTapInverted = { /* paginated only */ },
            )
        }

        if (showVoicePicker) {
            com.speechnotes.app.ui.voice.VoicePickerSheet(vm = vm, onDismiss = { showVoicePicker = false })
        }
    }
}

internal fun goDocChapter(webViewRef: androidx.compose.runtime.MutableState<WebView?>, index: Int) {
    webViewRef.value?.evaluateJavascript(
        "window.docGoChapter && window.docGoChapter($index);", null,
    )
}
