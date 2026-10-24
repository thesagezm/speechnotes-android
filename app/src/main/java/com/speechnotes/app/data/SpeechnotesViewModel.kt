package com.speechnotes.app.data

import android.content.Context
import android.content.SharedPreferences
import com.speechnotes.app.model.Book
import com.speechnotes.app.model.BookChapter
import com.speechnotes.app.model.BookTocEntry
import com.speechnotes.app.model.Note
import com.speechnotes.app.model.Notebook
import com.speechnotes.app.logic.DocumentBook
import com.speechnotes.app.logic.DocumentEpubConverter
import com.speechnotes.app.logic.DocumentParseError
import com.speechnotes.app.speech.EngineKind
import com.speechnotes.app.speech.ModelFiles
import com.speechnotes.app.speech.SpeechPlayer
import com.speechnotes.app.ui.theme.AccentChoice
import com.speechnotes.app.ui.theme.AppearanceMode
import com.speechnotes.app.ui.theme.ReaderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app's single ViewModel: owns every store, the player, and the theme
 * state. Ported conceptually from the iOS app's environment objects
 * (NotesStore / SpeechPlayer / AppTheme / ModelManager / BooksStore) —
 * one observable surface for the whole UI.
 */
class SpeechnotesViewModel(application: android.app.Application) : androidx.lifecycle.ViewModel() {

    /** App-wide scope: UI-visible background work (imports, downloads). */
    val appScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    /** OkHttp instance for user-initiated downloads (image-from-URL). */
    val httpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    val context: Context = application.applicationContext
    val notes = NotesStore(appScope)
    val notebooks = NotebooksStore()
    val bookmarks = BookmarkStore()
    val books = BooksStore()
    val models = ModelManager(application)

    /** Exported-audio list + the shared WAV player (StorageSettings parity). */
    val exports = ExportsStore()
    val wavPlayer = WavPlayer(application)
    val player = SpeechPlayer(application, appScope, bookmarks)

    /** App-level audiobook file player — playback survives navigation. */
    val audioBooks = com.speechnotes.app.speech.AudioBookPlayer(application, appScope) { bookId, chapterIndex, offsetMs ->
        saveAudioPosition(bookId, chapterIndex, offsetMs)
    }

    /** Reading-time recorder for the book readers (one per surface). */
    val readingRecorder = ReadingRecorder(appScope)

    private val statsCenter = StatsCenter(appScope, player, audioBooks)

    /** E1c: reading goals (ReadingGoalsView.swift parity). */
    val goals = GoalStore(File(application.filesDir, ""))

    init {
        // The playback service routes lock-screen commands to whoever is
        // actually playing (audiobook first, TTS fall-through).
        com.speechnotes.app.playback.PlaybackRegistry.speechPlayer = player
        com.speechnotes.app.playback.PlaybackRegistry.audioBooks = audioBooks
        player.onRemoteChapterSkip = { delta ->
            val book = narratingBook
            val next = _narratingChapter.value + delta
            if (book != null && next in book.chapters.indices) speakBookFrom(book, next)
        }
        // E1: reading/listening time tracking — listening derives from the
        // players' state (StatsCenter.swift parity), reading from the readers.
        // Store init + attach now happen on the IO loader in the second init
        // block (same relative order: init before attach).
        // E2: BookDrop landed files route into the same importers the share
        // intents use, with a history entry per outcome.
        com.speechnotes.app.data.BookDropRegistry.registerImporter { file ->
            val ext = file.extension.lowercase()
            if (ext == "jex") {
                importJex(android.net.Uri.fromFile(file))
            } else {
                importBook(android.net.Uri.fromFile(file))
            }
        }
    }

    fun addGoal(book: Book, start: Long, deadline: Long) {
        appScope.launch { goals.add(book, start, deadline) }
    }

    fun removeGoal(goalId: String) {
        appScope.launch { goals.remove(goalId) }
    }

    fun updateGoal(goalId: String, start: Long, deadline: Long) {
        appScope.launch { goals.update(goalId, start, deadline) }
    }

    val engineKind: StateFlow<EngineKind> get() = player.engineKind

    // MARK: - Theme

    private val _themeState = MutableStateFlow(com.speechnotes.app.ui.theme.ThemeState())
    val themeState: StateFlow<com.speechnotes.app.ui.theme.ThemeState> = _themeState.asStateFlow()

    /**
     * The EPUB reader's appearance, held separately from ThemeState: it is
     * pushed into the reader webview as one JSON command (and rides the
     * shell URL on load), so it needs the FULL struct, not loose floats.
     */
    private val _readerAppearance = MutableStateFlow(com.speechnotes.app.model.ReaderAppearance())
    val readerAppearance: StateFlow<com.speechnotes.app.model.ReaderAppearance> = _readerAppearance.asStateFlow()

    private val prefs: SharedPreferences = application.getSharedPreferences("speechnotes", Context.MODE_PRIVATE)

    // MARK: - Preferences (observable)

    // Every setting is a StateFlow mirrored to prefs. Plain prefs-backed vars
    // never told Compose anything changed — the "toggles feel unresponsive"
    // bug (a flipped Switch rendered nothing until some other recomposition
    // happened to run).

    private val _renderMarkdown = MutableStateFlow(prefs.getBoolean(Prefs.RENDER_MARKDOWN, false))
    val renderMarkdownFlow: StateFlow<Boolean> = _renderMarkdown.asStateFlow()
    var renderMarkdown: Boolean
        get() = _renderMarkdown.value
        set(value) {
            _renderMarkdown.value = value
            prefs.edit().putBoolean(Prefs.RENDER_MARKDOWN, value).apply()
        }

    private val _readAlongEnabled = MutableStateFlow(prefs.getBoolean(Prefs.READ_ALONG_ENABLED, true))
    val readAlongEnabledFlow: StateFlow<Boolean> = _readAlongEnabled.asStateFlow()
    var readAlongEnabled: Boolean
        get() = _readAlongEnabled.value
        set(value) {
            _readAlongEnabled.value = value
            prefs.edit().putBoolean(Prefs.READ_ALONG_ENABLED, value).apply()
        }

    private val _notesSortOrder = MutableStateFlow(prefs.getString(Prefs.NOTES_SORT_ORDER, "edited") ?: "edited")
    val notesSortOrderFlow: StateFlow<String> = _notesSortOrder.asStateFlow()
    var notesSortOrder: String
        get() = _notesSortOrder.value
        set(value) {
            _notesSortOrder.value = value
            prefs.edit().putString(Prefs.NOTES_SORT_ORDER, value).apply()
        }

    private val _activeNotebookScope = MutableStateFlow(prefs.getString(Prefs.ACTIVE_NOTEBOOK_SCOPE, "all") ?: "all")
    val activeNotebookScopeFlow: StateFlow<String> = _activeNotebookScope.asStateFlow()
    var activeNotebookScope: String
        get() = _activeNotebookScope.value
        set(value) {
            _activeNotebookScope.value = value
            prefs.edit().putString(Prefs.ACTIVE_NOTEBOOK_SCOPE, value).apply()
        }

    private val _hasOnboarded = MutableStateFlow(prefs.getBoolean("hasOnboarded", false))
    val hasOnboarded: StateFlow<Boolean> = _hasOnboarded.asStateFlow()

    fun setOnboarded(done: Boolean) {
        prefs.edit().putBoolean("hasOnboarded", done).apply()
        _hasOnboarded.value = done
    }

    var miniPlayerCollapsed: Boolean
        get() = _miniPlayerCollapsed.value
        set(value) {
            _miniPlayerCollapsed.value = value
            prefs.edit().putBoolean(Prefs.MINI_PLAYER_COLLAPSED, value).apply()
        }

    private val _miniPlayerCollapsed = MutableStateFlow(prefs.getBoolean(Prefs.MINI_PLAYER_COLLAPSED, false))
    val miniPlayerCollapsedFlow: StateFlow<Boolean> = _miniPlayerCollapsed.asStateFlow()

    // Mini-player suppression (GlobalMiniPlayerOverlay.miniPlayerSuppressed
    // parity): a surface with its own transport (the note editor's controls,
    // the book readers) sets this while it's on top so the global bar never
    // stacks on top of it.
    private val _miniPlayerSuppressed = MutableStateFlow(false)
    val miniPlayerSuppressedFlow: StateFlow<Boolean> = _miniPlayerSuppressed.asStateFlow()

    var miniPlayerSuppressed: Boolean
        get() = _miniPlayerSuppressed.value
        set(value) { _miniPlayerSuppressed.value = value }

    // MARK: - Toast

    data class Toast(val id: Long, val message: String, val actionTitle: String? = null, val action: (() -> Unit)? = null)

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    private var toastJob: kotlinx.coroutines.Job? = null

    fun showToast(message: String, actionTitle: String? = null, action: (() -> Unit)? = null) {
        toastJob?.cancel()
        _toast.value = Toast(System.currentTimeMillis(), message, actionTitle, action)
        toastJob = appScope.launch {
            kotlinx.coroutines.delay(2500)
            _toast.value = null
        }
    }

    fun dismissToast() {
        toastJob?.cancel()
        _toast.value = null
    }

    // MARK: - Lifecycle

    // Declared BEFORE the init block that (indirectly) uses them: property
    // initializers run in declaration order, so a coroutine launched from
    // init could otherwise see these still-null and crash the app at launch.
    /** Bumped whenever a cover file appears or changes, so the shelf re-reads. */
    private val _bookCoverRevision = MutableStateFlow(0L)
    val bookCoverRevision: StateFlow<Long> = _bookCoverRevision.asStateFlow()

    private var coverJob: kotlinx.coroutines.Job? = null

    init {
        com.speechnotes.app.logic.AppFiles.init(application.filesDir, application)
        // Everything disk-bound leaves the main thread: this constructor runs
        // during the FIRST composition, and decoding notes.json + walking every
        // book manifest + stat-ing multi-hundred-MB model files before the
        // first frame was a large slice of the 1.7–2.2 s cold start. The UI
        // observes each store's StateFlow, so lists fill in when ready.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(application)
            StatsStore.init(application)
            statsCenter.attach()
            goals.init()
            notes.load()
            notebooks.load()
            bookmarks.load()
            books.refresh()
            models.refreshStates()
            exports.refresh()
        }
        models.onReady = {
            player.refreshEngineAvailability()
            // A fresh model download also shouldn't pay the load on first play.
            player.prewarmActiveEngine()
        }
        loadTheme()

        // Book chapter chain: one chapter per speak call, auto-advance on
        // natural completion (port of BookPlaybackController.swift).
        player.onNaturalFinish = {
            val book = narratingBook
            if (book != null) {
                val next = _narratingChapter.value + 1
                if (next < book.chapters.size) {
                    speakBookFrom(book, next)
                } else {
                    showToast("Finished \"${book.title.take(40)}\"")
                    narratingBook = null
                    _narratingChapter.value = -1
                }
            }
        }

        // Pre-warm the active neural engine (ONNX session + G2P) in the
        // background so the FIRST play doesn't pay it — the "time to first
        // audio is so long" fix. No-op for the system engine.
        appScope.launch(kotlinx.coroutines.Dispatchers.Default) { player.prewarmActiveEngine() }

        // Covers only ever generated at import time — books imported before
        // that (or whose render failed) show the plain gradient card forever.
        // One quiet backfill pass at startup fixes the whole shelf; BooksScreen
        // re-runs ensureBookCovers() if it is opened while one isn't done.
        ensureBookCovers()

        // Deep-link / file-open intent text arrives through a SharedFlow.
    }

    // MARK: - Book narration chain (port of BookPlaybackController.swift)

    /** The book whose chapter chain is armed; null when no book is on the player. */
    private var narratingBook: Book? = null

    private val _narratingChapter = MutableStateFlow(-1)
    val narratingChapter: StateFlow<Int> = _narratingChapter.asStateFlow()

    /** True when THIS book+chapter is the live narration — drives reader play-state. */
    fun isNarrating(book: Book, chapterIndex: Int): Boolean =
        player.nowPlayingBookId.value == book.id &&
            _narratingChapter.value == chapterIndex &&
            player.state.value.isActive

    /** True while any chapter of this book is on the player. */
    fun isBookNarrating(book: Book): Boolean =
        player.nowPlayingBookId.value == book.id && player.state.value.isActive

    /**
     * The reader's play control: when this book is already the live speech,
     * toggle pause/resume of the ONGOING session (never re-speak the viewed
     * chapter and silently abandon auto-advance); otherwise start the chain.
     *
     * [fraction] (EPUB: chapter-local scroll fraction) and [page] (PDF: the
     * page the reader shows) pin the start to the spot the user is LOOKING
     * at — without them, narration began at the chapter's resume bookmark
     * and sounded like "it starts from wherever it wants".
     */
    fun toggleBookChapter(book: Book, chapterIndex: Int, fraction: Double = 0.0, page: Int = -1) {
        if (isBookNarrating(book)) {
            player.toggle()
            return
        }
        narratingBook = book
        appScope.launch {
            var index = chapterIndex.coerceAtLeast(0)
            var pinnedOffset: Int? = null
            while (index < book.chapters.size) {
                val text = chapterText(book, index)
                if (!text.isNullOrBlank()) {
                    if (pinnedOffset == null) pinnedOffset = resolveStartOffset(book, index, text, fraction, page)
                    _narratingChapter.value = index
                    player.playBookChapter(
                        text = text,
                        bookId = book.id,
                        chapterIndex = index,
                        title = book.chapters.getOrNull(index)?.label ?: book.title,
                        startCharOffset = pinnedOffset.coerceIn(0, maxOf(0, text.length - 1)),
                    )
                    return@launch
                }
                LogStore.warn("BookPlayback: chapter ${index + 1}/${book.chapters.size} of \"${book.title}\" has no speech text — skipping")
                index += 1
            }
            LogStore.warn("BookPlayback: no speakable chapters from $chapterIndex in \"${book.title}\"")
            narratingBook = null
            _narratingChapter.value = -1
        }
    }

    /** Char offset for "start where I am": PDF page offset, else EPUB fraction. */
    private suspend fun resolveStartOffset(book: Book, chapterIndex: Int, text: String, fraction: Double, page: Int): Int {
        val approx = when {
            book.format == Book.FORMAT_PDF && page >= 0 -> {
                val chapter = book.chapters.getOrNull(chapterIndex)
                pdfPageOffset(book, chapterIndex, page)
                    // Scanned (OCR'd) chapters have no page map — interpolate
                    // by the page's position inside the chapter's range.
                    ?: chapter?.takeIf { it.endPage > it.startPage }?.let {
                        ((page - it.startPage).coerceAtLeast(0).toDouble() /
                            (it.endPage - it.startPage + 1) * text.length).toInt()
                    } ?: 0
            }
            fraction > 0.0 -> (fraction * text.length).toInt()
            else -> 0
        }
        if (approx <= 0) return 0
        // Snap into a sentence start so speech never opens mid-word.
        return com.speechnotes.app.logic.SentenceChunker.resumeOffset(text, approx.coerceAtMost(text.length - 1))
    }

    /**
     * Where [page]'s text begins inside chapter [chapterIndex]'s speech text
     * (PDF page-sync, from the offsets file written by [chapterText]).
     */
    private suspend fun pdfPageOffset(book: Book, chapterIndex: Int, page: Int): Int? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = com.speechnotes.app.logic.AppFiles.bookSpeechOffsetsFile(book.id, chapterIndex)
            val rows: List<Pair<Int, Int>>? = runCatching {
                if (file.isFile && file.length() > 0) {
                    val arr = org.json.JSONArray(file.readText())
                    List(arr.length()) { i ->
                        val obj = arr.getJSONObject(i)
                        obj.getInt("page") to obj.getInt("offset")
                    }
                } else null
            }.getOrNull()
            if (rows != null) {
                return@withContext rows.lastOrNull { it.first <= page }?.second
            }
            // No cache yet (the chapter text predates offsets): extract once
            // and persist, so the next play from a page is instant.
            val original = com.speechnotes.app.logic.AppFiles.bookOriginalFile(book.id, originalExtension(book))
            if (!original.isFile) return@withContext null
            runCatching {
                val source = PdfPageSource(original)
                val pdfChapters = book.chapters.map {
                    com.speechnotes.app.logic.PdfText.PdfChapter(it.label, it.startPage, it.endPage)
                }
                val result = com.speechnotes.app.logic.PdfText.chapterText(source, pdfChapters, chapterIndex) ?: return@withContext null
                file.parentFile?.mkdirs()
                file.writeText(org.json.JSONArray().apply {
                    result.second.forEach { (p, o) ->
                        put(org.json.JSONObject().put("page", p).put("offset", o))
                    }
                }.toString())
                result.second.lastOrNull { it.page <= page }?.utf16Offset
            }.getOrNull()
        }

    /** Speaks from [startIndex] onward, skipping chapters with no extractable text. */
    private fun speakBookFrom(book: Book, startIndex: Int) {
        appScope.launch {
            var index = startIndex.coerceAtLeast(0)
            while (index < book.chapters.size) {
                val text = chapterText(book, index)
                if (!text.isNullOrBlank()) {
                    _narratingChapter.value = index
                    player.playBookChapter(
                        text = text,
                        bookId = book.id,
                        chapterIndex = index,
                        title = book.chapters.getOrNull(index)?.label ?: book.title,
                    )
                    return@launch
                }
                LogStore.warn("BookPlayback: chapter ${index + 1}/${book.chapters.size} of \"${book.title}\" has no speech text — skipping")
                index += 1
            }
            LogStore.warn("BookPlayback: no speakable chapters from $startIndex in \"${book.title}\"")
            narratingBook = null
            _narratingChapter.value = -1
        }
    }

    private fun loadTheme() {
        _themeState.value = com.speechnotes.app.ui.theme.ThemeState(
            accent = AccentChoice.fromKey(prefs.getString("accentColorChoice", "SYSTEM") ?: "SYSTEM"),
            appearance = when (prefs.getString("appAppearance", "system")) {
                "light" -> AppearanceMode.LIGHT
                "dark" -> AppearanceMode.DARK
                else -> AppearanceMode.SYSTEM
            },
            previewTextScale = prefs.getFloat("previewTextScale", 1.0f),
            readerLineSpacing = prefs.getFloat("readerLineSpacing", 1.0f).toDouble(),
            readerBlockSpacing = prefs.getFloat("readerBlockSpacing", 1.0f).toDouble(),
            readerTableSpacing = prefs.getFloat("readerTableSpacing", 1.0f).toDouble(),
            readerTheme = when (prefs.getString("readerTheme", "white")) {
                "sepia" -> ReaderTheme.SEPIA
                "dark" -> ReaderTheme.DARK
                "trueblack" -> ReaderTheme.TRUE_BLACK
                else -> ReaderTheme.WHITE
            },
        )
        loadReaderAppearance()
    }

    /**
     * ReaderAppearance.load() parity. Booleans that default true are read
     * back through an "unset" marker so a missing key means TRUE (a fresh
     * install turns pages by tap) rather than false.
     */
    private fun loadReaderAppearance() {
        val d = com.speechnotes.app.model.ReaderAppearance()
        val p = prefs
        _readerAppearance.value = d.copy(
            theme = p.getString(Prefs.BOOK_READER_THEME, d.theme) ?: d.theme,
            fontSize = p.getInt(Prefs.BOOK_READER_FONT_SIZE, d.fontSize).takeIf { it > 0 } ?: d.fontSize,
            flow = p.getString(Prefs.BOOK_READER_FLOW, d.flow) ?: d.flow,
            font = p.getString(Prefs.BOOK_READER_FONT, d.font) ?: d.font,
            lineHeight = p.getFloat(Prefs.BOOK_READER_LINE_HEIGHT, d.lineHeight.toFloat()).toDouble()
                .takeIf { it > 0 } ?: d.lineHeight,
            paraSpacing = p.getFloat(Prefs.BOOK_READER_PARA_SPACING, d.paraSpacing.toFloat()).toDouble(),
            letterSpacing = p.getFloat(Prefs.BOOK_READER_LETTER_SPACING, d.letterSpacing.toFloat()).toDouble(),
            margin = p.getInt(Prefs.BOOK_READER_MARGIN, d.margin),
            respectStyles = p.getBoolean(Prefs.BOOK_READER_RESPECT_STYLES, d.respectStyles),
            autoScroll = p.getBoolean(Prefs.BOOK_READER_AUTO_SCROLL, d.autoScroll),
            autoScrollSpeed = p.getFloat(Prefs.BOOK_READER_AUTO_SCROLL_SPEED, d.autoScrollSpeed.toFloat())
                .toDouble().takeIf { it > 0 } ?: d.autoScrollSpeed,
            tapTurn = defaultTrue(p, Prefs.BOOK_READER_TAP_TURN, d.tapTurn),
            swipeTurn = defaultTrue(p, Prefs.BOOK_READER_SWIPE_TURN, d.swipeTurn),
            tapInverted = p.getBoolean(Prefs.BOOK_READER_TAP_INVERTED, d.tapInverted),
        )
    }

    /** Reads a tri-state boolean pref: unset → [fallback] (iOS `object(forKey:)`). */
    private fun defaultTrue(p: SharedPreferences, key: String, fallback: Boolean): Boolean =
        when (p.getString(key, null)) {
            "1" -> true
            "0" -> false
            else -> fallback
        }

    /** Writes one reader-appearance field and persists the whole struct. */
    private fun updateReaderAppearance(update: (com.speechnotes.app.model.ReaderAppearance) -> com.speechnotes.app.model.ReaderAppearance) {
        val next = update(_readerAppearance.value)
        _readerAppearance.value = next
        prefs.edit()
            .putString(Prefs.BOOK_READER_THEME, next.theme)
            .putInt(Prefs.BOOK_READER_FONT_SIZE, next.fontSize)
            .putString(Prefs.BOOK_READER_FLOW, next.flow)
            .putString(Prefs.BOOK_READER_FONT, next.font)
            .putFloat(Prefs.BOOK_READER_LINE_HEIGHT, next.lineHeight.toFloat())
            .putFloat(Prefs.BOOK_READER_PARA_SPACING, next.paraSpacing.toFloat())
            .putFloat(Prefs.BOOK_READER_LETTER_SPACING, next.letterSpacing.toFloat())
            .putInt(Prefs.BOOK_READER_MARGIN, next.margin)
            .putBoolean(Prefs.BOOK_READER_RESPECT_STYLES, next.respectStyles)
            .putBoolean(Prefs.BOOK_READER_AUTO_SCROLL, next.autoScroll)
            .putFloat(Prefs.BOOK_READER_AUTO_SCROLL_SPEED, next.autoScrollSpeed.toFloat())
            .putString(Prefs.BOOK_READER_TAP_TURN, if (next.tapTurn) "1" else "0")
            .putString(Prefs.BOOK_READER_SWIPE_TURN, if (next.swipeTurn) "1" else "0")
            .putBoolean(Prefs.BOOK_READER_TAP_INVERTED, next.tapInverted)
            .apply()
    }

    fun setReaderFlow(flow: String) = updateReaderAppearance { it.copy(flow = flow) }
    fun setReaderFontSize(percent: Int) = updateReaderAppearance { it.copy(fontSize = percent) }
    fun setReaderFont(font: String) = updateReaderAppearance { it.copy(font = font) }
    fun setReaderLineHeight(value: Double) = updateReaderAppearance { it.copy(lineHeight = value) }
    fun setReaderParaSpacing(value: Double) = updateReaderAppearance { it.copy(paraSpacing = value) }
    fun setReaderLetterSpacing(value: Double) = updateReaderAppearance { it.copy(letterSpacing = value) }
    fun setReaderMargin(value: Int) = updateReaderAppearance { it.copy(margin = value) }
    fun setReaderRespectStyles(value: Boolean) = updateReaderAppearance { it.copy(respectStyles = value) }
    fun setReaderAutoScroll(value: Boolean) = updateReaderAppearance { it.copy(autoScroll = value) }
    fun setReaderAutoScrollSpeed(value: Double) = updateReaderAppearance { it.copy(autoScrollSpeed = value) }
    fun setReaderTapTurn(value: Boolean) = updateReaderAppearance { it.copy(tapTurn = value) }
    fun setReaderSwipeTurn(value: Boolean) = updateReaderAppearance { it.copy(swipeTurn = value) }
    fun setReaderTapInverted(value: Boolean) = updateReaderAppearance { it.copy(tapInverted = value) }

    fun setAccent(choice: AccentChoice) {
        prefs.edit().putString("accentColorChoice", choice.name).apply()
        _themeState.value = _themeState.value.copy(accent = choice)
    }

    fun setAppearance(mode: AppearanceMode) {
        prefs.edit().putString("appAppearance", mode.name.lowercase()).apply()
        _themeState.value = _themeState.value.copy(appearance = mode)
    }

    fun setPreviewTextScale(scale: Float) {
        prefs.edit().putFloat("previewTextScale", scale).apply()
        _themeState.value = _themeState.value.copy(previewTextScale = scale)
    }

    fun setReaderTheme(theme: ReaderTheme) {
        prefs.edit().putString("readerTheme", theme.name.lowercase()).apply()
        _themeState.value = _themeState.value.copy(readerTheme = theme)
        // The EPUB reader's own theme rides ReaderAppearance (light/sepia/
        // dark/trueBlack) — keep the two in sync so the shelf's segmented
        // control and the reader's appearance sheet agree.
        val jsTheme = when (theme) {
            ReaderTheme.SEPIA -> "sepia"
            ReaderTheme.DARK, ReaderTheme.TRUE_BLACK -> "dark"
            else -> "light"
        }
        updateReaderAppearance { it.copy(theme = jsTheme) }
    }

    /** Reader spacing multipliers; null keeps the current value. */
    fun setReaderSpacing(
        line: Double? = null,
        block: Double? = null,
        table: Double? = null,
    ) {
        val current = _themeState.value
        val next = current.copy(
            readerLineSpacing = line ?: current.readerLineSpacing,
            readerBlockSpacing = block ?: current.readerBlockSpacing,
            readerTableSpacing = table ?: current.readerTableSpacing,
        )
        prefs.edit()
            .putFloat("readerLineSpacing", next.readerLineSpacing.toFloat())
            .putFloat("readerBlockSpacing", next.readerBlockSpacing.toFloat())
            .putFloat("readerTableSpacing", next.readerTableSpacing.toFloat())
            .apply()
        _themeState.value = next
    }

    fun resetReaderSpacing() = setReaderSpacing(1.0, 1.0, 1.0)

    // MARK: - Notes + notebooks (UI actions)

    fun createNote(notebookId: String? = null): Note = notes.createNote(notebookId)

    fun searchNotes(query: String, scope: String, sort: String): List<Note> {
        var list = notes.notes.value
        when (scope) {
            "unfiled" -> list = list.filter { it.notebookId == null }
            else -> if (scope.startsWith("nb-")) {
                val id = scope.removePrefix("nb-")
                list = list.filter { it.notebookId == id }
            }
        }
        if (query.isNotBlank()) {
            val needle = query.lowercase()
            list = list.filter { it.title.lowercase().contains(needle) || it.text.lowercase().contains(needle) }
        }
        list = when (sort) {
            "created" -> list.sortedByDescending { it.createdAt }
            "title" -> list.sortedBy { it.title.lowercase() }
            else -> list.sortedByDescending { it.updatedAt }
        }
        return list
    }

    fun deleteNoteWithUndo(noteId: String) {
        val note = notes.note(noteId) ?: return
        val title = note.title
        notes.delete(noteId)
        showToast("Deleted \"${title.take(32)}\"", "Undo") { notes.recover(noteId) }
    }

    fun importClipboard(): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        return addNoteFromText(text)
    }

    fun addNoteFromText(text: String?): Boolean {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            showToast("That text has nothing to read aloud.")
            return false
        }
        val cleaned = com.speechnotes.app.logic.SpeechText.forText(trimmed)
        if (cleaned.isBlank()) {
            showToast("That text has nothing to read aloud.")
            return false
        }
        addNote(cleaned)
        return true
    }

    fun addNote(text: String) {
        val note = notes.createNote(notebookId = currentScopeNotebookId())
        notes.update(note.copy(text = text))
    }

    private fun currentScopeNotebookId(): String? {
        val scope = activeNotebookScope
        return if (scope.startsWith("nb-")) scope.removePrefix("nb-") else null
    }

    // MARK: - Books

    /** The file's extension resolved from its display name or mime type. */
    fun extensionOf(uri: android.net.Uri): String {
        val name = ModelManagerImportHelper.queryName(context, uri) ?: uri.lastPathSegment ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) return ext
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        return when {
            mime.contains("epub") -> "epub"
            mime.contains("pdf") -> "pdf"
            mime.contains("audio") || mime.contains("mp4") -> "m4b"
            else -> ""
        }
    }

    fun importBook(uri: android.net.Uri) {
        // Resolve the real filename from the content resolver: a MEDIASTORE
        // content URI's last path segment is a bare id ("19"), not a name.
        val name = ModelManagerImportHelper.queryName(context, uri)
            ?: uri.lastPathSegment
            ?: "book"
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty {
            // Still nothing? Fall back to the declared mime type.
            val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
            when {
                mime.contains("epub") -> "epub"
                mime.contains("pdf") -> "pdf"
                mime.contains("audio") || mime.contains("mp4") -> "m4b"
                else -> ""
            }
        }
        val known = ext == "epub" || ext == "pdf" || ext == "m4b" || ext == "m4a" ||
            ext == "mp4" || ext == "mp3" || DocumentBook.canNormalize(ext)
        if (!known) {
            showToast("Unsupported file type: $name")
            return
        }
        val book = books.beginImport()
        val target = com.speechnotes.app.logic.AppFiles.bookOriginalFile(book.id, ext)
        // Copy + parse off the main thread: a 40 MB PDF or EPUB blocks the
        // UI for seconds otherwise (the "import then nothing" bug).
        appScope.launch {
            val copied = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)
                    if (input == null) {
                        LogStore.error("BookImport: openInputStream returned null for $uri")
                        return@runCatching false
                    }
                    input.use { stream ->
                        target.outputStream().use { out -> stream.copyTo(out) }
                    }
                    target.length() > 0
                }.getOrElse {
                    LogStore.error("BookImport: copy failed for $uri — ${it::class.simpleName}: ${it.message}")
                    false
                }
            }
            if (!copied) {
                books.endImport(null, "Could not read that file.")
                showToast("Could not read that file.")
                return@launch
            }
            when (ext) {
                "epub" -> importEpub(book, target)
                // The display name rides along: the copy is always named
                // original.<ext>, and nameWithoutExtension of THAT is
                // "original" — the title every imported PDF wore.
                "pdf" -> importPdf(book, target, name.substringBeforeLast('.'))
                else -> if (DocumentBook.canNormalize(ext)) {
                    importDocument(book, target, ext, name.substringBeforeLast('.'))
                } else {
                    importAudio(book, target, ext)
                }
            }
        }
    }

    private suspend fun importEpub(book: Book, file: File) {
        // (error, manifest) — a non-null error means the book is on the shelf
        // but unreadable, which is exactly what iOS's per-book importError
        // says on the shelf card instead of a toast that scrolls away.
        var failure: String? = null
        val parsed = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val zip = com.speechnotes.app.logic.ZipReader.parse(file.readBytes())
                if (zip == null) {
                    failure = "This EPUB is malformed — chapters and speech may be unavailable."
                    null
                } else {
                    val info = com.speechnotes.app.logic.EpubParser.parse(zip)
                    if (info.spine.isEmpty()) {
                        failure = "This EPUB has no readable chapters — speech may be unavailable."
                    } else {
                        // Cover extraction rides along inside the same IO block —
                        // the cover bytes come straight out of the archive.
                        val coverWritten = info.coverPath?.let { path ->
                            zip.data(path)?.let { bytes ->
                                runCatching {
                                    com.speechnotes.app.logic.AppFiles.bookCoverFile(book.id).writeBytes(bytes)
                                }.isSuccess
                            }
                        } ?: false
                        if (!coverWritten) {
                            LogStore.info("BookCovers: EPUB ${file.name} has no extractable cover — using a typographic one")
                            syntheticCover(book.id, info.title ?: file.nameWithoutExtension)
                        }
                    }
                    info
                }
            }.getOrNull()
        }
        if (parsed == null) {
            cleanupFailedImport(book)
            val message = failure ?: "Could not read that EPUB."
            books.endImport(null, message)
            showToast(message)
            return
        }
        val manifest = book.copy(
            title = parsed.title ?: file.nameWithoutExtension,
            author = parsed.creator,
            format = Book.FORMAT_EPUB,
            hasCover = com.speechnotes.app.logic.AppFiles.bookCoverFile(book.id).isFile,
            chapters = parsed.spine.mapIndexed { i, href -> BookChapter(label = "Chapter ${i + 1}", href = href) },
            spineCount = parsed.spine.size.takeIf { it > 0 },
            toc = parsed.toc.map { entry ->
                val index = parsed.spine.indexOfFirst { it == entry.href }
                BookTocEntry(entry.label, if (index >= 0) index else 0)
            },
            importError = failure,
        )
        books.endImport(manifest)
        showToast(failure ?: "Imported ${manifest.chapters.size} chapters")
    }

    private suspend fun importPdf(book: Book, file: File, displayName: String) {
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val source = PdfPageSource(file)
                val chapters = com.speechnotes.app.logic.PdfText.resolveChapters(source)
                if (!renderPdfCover(book.id, file)) {
                    LogStore.warn("BookCovers: PDF render produced nothing for ${file.name} — using a typographic cover")
                    syntheticCover(book.id, file.nameWithoutExtension)
                }
                chapters
            }.getOrNull()
        }
        if (result == null) {
            cleanupFailedImport(book)
            books.endImport(null, "Could not read that PDF.")
            showToast("Could not read that PDF.")
            return
        }
        val (chapters, sourceKind) = result
        val manifest = book.copy(
            title = displayName.ifBlank { file.nameWithoutExtension },
            format = Book.FORMAT_PDF,
            hasCover = com.speechnotes.app.logic.AppFiles.bookCoverFile(book.id).isFile,
            chapters = chapters.map { BookChapter(label = it.label, startPage = it.startPage, endPage = it.endPage) },
            pdfChapterSource = sourceKind,
        )
        books.endImport(manifest)
        showToast("Imported ${manifest.chapters.size} chapters")
    }

    private suspend fun importAudio(book: Book, file: File, ext: String) {
        // Chapter metadata + duration + embedded art come from the platform
        // extractor plus the ported chpl/ID3-CHAP parser (m4b-tool and
        // ffmpeg M4Bs carry a real chapter TRACK, which chpl-only parsers
        // miss).
        var meta = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                AudioMeta(
                    durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    cover = retriever.embeddedPicture,
                    title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE),
                    author = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST),
                )
            } catch (e: Exception) {
                AudioMeta()
            } finally {
                runCatching { retriever.release() }
            }
        }

        val cover = meta.cover
        if (cover != null && cover.isNotEmpty()) {
            runCatching { com.speechnotes.app.logic.AppFiles.bookCoverFile(book.id).writeBytes(cover) }
        } else {
            LogStore.info("BookCovers: ${file.name} carries no embedded artwork — using a typographic cover")
            syntheticCover(book.id, meta.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension)
        }

        val chapters = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // Head slice: chapter boxes (moov) sit near the file start.
                // 8 MB clipped real chapter tracks whose chunk offsets point
                // past it (D5); 64 MB still never reads a 900 MB book but
                // covers every moov we've seen. The walker itself validates
                // candidates now, so a bigger slice only ADDS evidence.
                val cap = 64 * 1024 * 1024
                val slice = file.inputStream().use { input ->
                    val buf = ByteArray(cap)
                    var read = 0
                    while (read < cap) {
                        val n = input.read(buf, read, cap - read)
                        if (n < 0) break
                        read += n
                    }
                    buf.copyOf(read)
                }
                if (ext == "mp3") com.speechnotes.app.logic.AudiobookChapters.chaptersFromID3(slice)
                else com.speechnotes.app.logic.AudiobookChapters.chaptersFromMP4(slice, meta.durationMs / 1000.0)
            }.getOrDefault(emptyList())
        }

        val audioChapters = chapters.map {
            com.speechnotes.app.model.AudioChapter(
                title = it.title,
                startMs = (it.startSeconds * 1000).toLong(),
                endMs = (it.endSeconds * 1000).toLong(),
            )
        }
        // Decodability probe (D7): MediaPlayer can reject a file the
        // importer accepted, and the failure then surfaces as "duration 0 +
        // no chapters + a tap on play that does nothing". Probe the real
        // decode path NOW; a failure marks the book importError so the
        // shelf card shows it.
        var decodeError: String? = null
        if (meta.durationMs <= 0L) {
            val probed = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val player = android.media.MediaPlayer()
                    try {
                        player.setDataSource(file.absolutePath)
                        player.prepare()
                        val ok = player.duration > 0
                        ok to player.duration.toLong()
                    } finally {
                        runCatching { player.release() }
                    }
                }.getOrElse { false to 0L }
            }
            if (!probed.first) {
                decodeError = "This audiobook can't be decoded on this device — playback will fail."
                LogStore.warn("AudioImport: MediaPlayer probe failed for ${file.name}")
            } else if (probed.second > 0L) {
                // Retriever missed the duration; the decoder knows better.
                meta = meta.copy(durationMs = probed.second)
            }
        }

        val manifest = book.copy(
            title = meta.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            author = meta.author?.takeIf { it.isNotBlank() },
            format = Book.FORMAT_AUDIO,
            hasCover = com.speechnotes.app.logic.AppFiles.bookCoverFile(book.id).isFile,
            chapters = audioChapters.mapIndexed { i, ch ->
                BookChapter(label = ch.title.ifBlank { "Chapter ${i + 1}" })
            },
            audioChapters = audioChapters,
            importError = decodeError,
        )
        books.endImport(manifest)
        showToast(
            if (audioChapters.isNotEmpty()) "Imported ${audioChapters.size} chapters"
            else "Imported audiobook (${formatDuration(meta.durationMs)})"
        )
    }

    private data class AudioMeta(
        val durationMs: Long = 0L,
        val cover: ByteArray? = null,
        val title: String? = null,
        val author: String? = null,
    )

    private fun cleanupFailedImport(book: Book) {
        runCatching { com.speechnotes.app.logic.AppFiles.bookDirectory(book.id).deleteRecursively() }
    }

    /**
     * Office documents as books: docx/odt/pptx/odp → parse → emit a real
     * EPUB → re-enter the normal EPUB import path, so the reader, the TOC
     * and the TTS spine pipeline never learn the difference. iOS parity
     * (DocumentBook.swift + DocumentEpubConverter.swift).
     */
    private suspend fun importDocument(book: Book, source: File, ext: String, displayName: String) {
        var failure: String? = null
        val parseResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { DocumentBook.parse(ext, source.readBytes()) }
                .getOrElse { error ->
                    LogStore.error("DocImport: $ext parse failed — ${error::class.simpleName}: ${error.message}")
                    when (error) {
                        is DocumentParseError.MissingEntry ->
                            failure = "That $ext file is missing ${error.message.orEmpty()}"
                        is DocumentParseError.Malformed ->
                            failure = "That $ext file is malformed — ${error.message.orEmpty()}"
                        else -> failure = null
                    }
                    null
                }
        }
        if (parseResult == null) {
            cleanupFailedImport(book)
            val message = failure ?: "Could not read that $ext file."
            books.endImport(null, message)
            showToast(message)
            return
        }
        if (parseResult.chapters.isEmpty() || parseResult.chapters.all { it.blocks.isEmpty() && it.title == null }) {
            cleanupFailedImport(book)
            books.endImport(null, "That $ext file has no readable text.")
            showToast("That $ext file has no readable text.")
            return
        }

        // The EPUB lands as the book's original.<ext> replacement: the reader,
        // cover pass and speech pipeline all consume the epub path only.
        val epubFile = com.speechnotes.app.logic.AppFiles.bookOriginalFile(book.id, "epub")
        val written = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = DocumentEpubConverter.epubData(
                    chapters = parseResult.chapters,
                    title = displayName.ifBlank { source.nameWithoutExtension },
                    author = null,
                    identifier = java.util.UUID.randomUUID().toString(),
                    images = parseResult.images,
                    cover = parseResult.cover,
                )
                epubFile.parentFile?.mkdirs()
                epubFile.writeBytes(bytes)
                true
            }.getOrElse {
                LogStore.error("DocImport: $ext → EPUB failed — ${it::class.simpleName}: ${it.message}")
                false
            }
        }
        if (!written) {
            cleanupFailedImport(book)
            books.endImport(null, "Could not convert that $ext file.")
            showToast("Could not convert that $ext file.")
            return
        }
        // The original stays for provenance; the parsed EPUB becomes the book.
        runCatching { source.delete() }
        importEpub(book, epubFile)
    }

    /** Re-runs the cover pass if one isn't already in flight. */
    fun ensureBookCovers() {
        if (coverJob?.isActive == true) return
        coverJob = appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            backfillBookCovers()
            _bookCoverRevision.value = _bookCoverRevision.value + 1
        }
    }

    /** Quiet startup pass: give every coverless book a thumbnail. Books
     * imported before cover generation existed (or whose render failed)
     * otherwise keep the plain gradient card forever. */
    private suspend fun backfillBookCovers() {
        var generated = 0
        var failed = 0
        for (book in books.books.value) {
            // Trust the FILE, not the manifest flag: a cover written by an
            // older build (or by a backfill interrupted mid-write) left
            // hasCover=false while cover.jpg sat right there, and the shelf
            // then showed a gradient card for a book that did have artwork.
            val existing = com.speechnotes.app.logic.AppFiles.bookCoverFile(book.id)
            if (existing.isFile && existing.length() > 0) {
                if (!book.hasCover) {
                    generated++
                    books.save(book.copy(hasCover = true))
                }
                continue
            }
            val original = com.speechnotes.app.logic.AppFiles.bookOriginalFile(book.id, originalExtension(book))
            if (!original.isFile) {
                LogStore.warn("BookCovers: \"${book.title.take(40)}\" has no original.${originalExtension(book)} file to render from")
                failed++
                continue
            }
            val ok = when (book.format) {
                Book.FORMAT_PDF -> renderPdfCover(book.id, original)
                Book.FORMAT_AUDIO -> extractAudioCover(book.id, original)
                Book.FORMAT_EPUB -> extractEpubCover(book.id, original)
                else -> false
            }
            if (ok) {
                generated++
                books.save(book.copy(hasCover = true))
            } else {
                // Nothing extractable (audio without embedded art). Draw a
                // typographic cover instead of leaving a bare gradient block:
                // the shelf should never look empty for a real book.
                if (syntheticCover(book.id, book.title)) {
                    generated++
                    books.save(book.copy(hasCover = true))
                } else {
                    LogStore.warn("BookCovers: no cover for \"${book.title.take(40)}\" (${book.format})")
                    failed++
                }
            }
        }
        if (generated > 0 || failed > 0) {
            LogStore.info("BookCovers: backfill done — $generated cover(s) ready, $failed still coverless")
        }
    }

    /** Embedded artwork for an audiobook file, if the container carries any. */
    private fun extractAudioCover(bookId: String, file: File): Boolean {
        val target = com.speechnotes.app.logic.AppFiles.bookCoverFile(bookId)
        if (target.isFile && target.length() > 0) return true
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val art = retriever.embeddedPicture
            if (art != null && art.isNotEmpty()) {
                target.writeBytes(art)
                true
            } else false
        } catch (e: Exception) {
            LogStore.warn("BookCovers: embedded art failed for ${file.name}: ${e.message}")
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** The EPUB's declared cover image, via the same parser the import used. */
    private fun extractEpubCover(bookId: String, file: File): Boolean {
        val target = com.speechnotes.app.logic.AppFiles.bookCoverFile(bookId)
        if (target.isFile && target.length() > 0) return true
        return runCatching {
            val zip = com.speechnotes.app.logic.ZipReader.parse(file.readBytes()) ?: return@runCatching false
            val parsed = com.speechnotes.app.logic.EpubParser.parse(zip) ?: return@runCatching false
            val bytes = parsed.coverPath?.let { zip.data(it) } ?: return@runCatching false
            target.writeBytes(bytes)
            true
        }.getOrElse {
            LogStore.warn("BookCovers: EPUB cover extract failed for ${file.name}: ${it.message}")
            false
        }
    }

    /**
     * Typographic stand-in written to cover.jpg when a book carries no
     * extractable artwork (most audiobooks). The shelf card then still shows a
     * real image with the title on it instead of an empty gradient block —
     * the "no thumbnails in Books" complaint.
     */
    private fun syntheticCover(bookId: String, title: String): Boolean = runCatching {
        val target = com.speechnotes.app.logic.AppFiles.bookCoverFile(bookId)
        if (target.isFile && target.length() > 0) return@runCatching true
        val width = 600
        val height = 900
        val bitmap = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val hue = ((bookId.hashCode().toLong() and 0x7fffffff) % 360).toFloat()
        val top = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.72f))
        val bottom = android.graphics.Color.HSVToColor(floatArrayOf((hue + 42f) % 360f, 0.55f, 0.42f))
        canvas.drawPaint(
            android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(), top, bottom,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            },
        )
        val words = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            // ~13 characters per line at this canvas size reads comfortably.
            if (candidate.length > 13 && line.isNotEmpty()) {
                lines.add(line.toString())
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        val shown = lines.take(4).toMutableList()
        if (lines.size > 4) shown[3] = shown[3].take(10) + "…"

        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 54f
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(12f, 0f, 4f, 0x55000000)
        }
        val blockHeight = 66f * shown.size
        var baseline = (height - blockHeight) / 2f + 48f
        for (text in shown) {
            canvas.drawText(text, width / 2f, baseline, textPaint)
            baseline += 66f
        }
        target.parentFile?.mkdirs()
        java.io.FileOutputStream(target).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        true
    }.getOrElse {
        LogStore.warn("BookCovers: synthetic cover failed for \"$title\": ${it.message}")
        false
    }

    private fun renderPdfCover(bookId: String, file: File): Boolean {
        val target = com.speechnotes.app.logic.AppFiles.bookCoverFile(bookId)
        if (target.isFile && target.length() > 0) return true
        // PDFBox first (already initialized), platform PdfRenderer as fallback —
        // pdfbox-android can't decode some image codecs (e.g. JPEG2000) that
        // medical textbooks use, and the old silent runCatching hid that.
        val pdfboxOk = runCatching {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
                val bitmap = com.tom_roush.pdfbox.rendering.PDFRenderer(doc).renderImage(0, 0.6f)
                java.io.FileOutputStream(target).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                }
                bitmap.recycle()
            }
            true
        }.onFailure {
            LogStore.warn("BookCovers: pdfbox render failed for ${file.name}: ${it.message}")
        }.getOrDefault(false)
        if (pdfboxOk) return true
        return runCatching {
            android.os.ParcelFileDescriptor.open(
                file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { pfd ->
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                try {
                    val page = renderer.openPage(0)
                    try {
                        val scale = 2
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            page.width * scale, page.height * scale,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        java.io.FileOutputStream(target).use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                        }
                        bitmap.recycle()
                    } finally {
                        page.close()
                    }
                } finally {
                    renderer.close()
                }
            }
            true
        }.onFailure {
            LogStore.warn("BookCovers: platform render failed for ${file.name}: ${it.message}")
        }.getOrDefault(false)
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60000
        val hours = minutes / 60
        return if (hours > 0) "$hours h ${minutes % 60} min" else "$minutes min"
    }

    /** Extracts one chapter's speech text (EPUB from the zip, PDF via PdfText). */
    suspend fun chapterText(book: Book, chapterIndex: Int): String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (book.format) {
            Book.FORMAT_EPUB -> {
                val cached = com.speechnotes.app.logic.AppFiles.bookSpeechTextFile(book.id, chapterIndex)
                if (cached.isFile && cached.length() > 0) return@withContext cached.readText()
                val chapter = book.chapters.getOrNull(chapterIndex) ?: return@withContext null
                val original = com.speechnotes.app.logic.AppFiles.bookOriginalFile(book.id, originalExtension(book))
                val zip = com.speechnotes.app.logic.ZipReader.parse(original.readBytes()) ?: return@withContext null
                val text = zip.text(chapter.href ?: return@withContext null)?.let { com.speechnotes.app.logic.XhtmlText.plainText(it) }
                if (text.isNullOrBlank()) null else {
                    runCatching { cached.parentFile?.mkdirs(); cached.writeText(text) }
                    text
                }
            }
            Book.FORMAT_PDF -> {
                val cached = com.speechnotes.app.logic.AppFiles.bookSpeechTextFile(book.id, chapterIndex)
                if (cached.isFile && cached.length() > 0) return@withContext cached.readText()
                val original = com.speechnotes.app.logic.AppFiles.bookOriginalFile(book.id, originalExtension(book))
                val source = PdfPageSource(original)
                val pdfChapters = book.chapters.map { com.speechnotes.app.logic.PdfText.PdfChapter(it.label, it.startPage, it.endPage) }
                val result = com.speechnotes.app.logic.PdfText.chapterText(source, pdfChapters, chapterIndex)
                val extracted = result?.first
                if (!extracted.isNullOrBlank()) {
                    // Persist the page map alongside the text — "start
                    // reading from the page I'm on" reads it back later.
                    runCatching {
                        com.speechnotes.app.logic.AppFiles.bookSpeechOffsetsFile(book.id, chapterIndex)
                            .parentFile?.mkdirs()
                        com.speechnotes.app.logic.AppFiles.bookSpeechOffsetsFile(book.id, chapterIndex)
                            .writeText(org.json.JSONArray().apply {
                                result.second.forEach { (p, o) ->
                                    put(org.json.JSONObject().put("page", p).put("offset", o))
                                }
                            }.toString())
                    }
                    runCatching { cached.parentFile?.mkdirs(); cached.writeText(extracted) }
                    return@withContext extracted
                }
                // Scanned PDF: no embedded text layer → OCR the chapter's
                // pages with ML Kit and cache the result (iOS OCR parity).
                val chapter = book.chapters.getOrNull(chapterIndex) ?: return@withContext null
                val ocr = ocrChapterPages(original, chapter.startPage, chapter.endPage)
                if (!ocr.isNullOrBlank()) {
                    runCatching { cached.parentFile?.mkdirs(); cached.writeText(ocr) }
                    ocr
                } else null
            }
            else -> null
        }
    }

    /** The on-disk extension of a book's original file (m4b/m4a/mp4/mp3 share the audio folder). */
    /**
     * ML Kit text recognition over a PDF page range. Renders each page to a
     * bitmap (PdfBox) and runs the recognizer synchronously — the caller is
     * already on the IO dispatcher, and chapters are a handful of pages.
     */
    private suspend fun ocrChapterPages(file: File, startPage: Int, endPage: Int): String? {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val textRecognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.Builder().build(),
                )
                val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(file)
                val recognitions = ArrayList<kotlinx.coroutines.Deferred<com.google.mlkit.vision.text.Text>>()
                for (page in startPage..endPage) {
                    if (page !in 0 until doc.numberOfPages) continue
                    val bitmap = runCatching {
                        // iOS Vision parity: 2× render (the 1.6 the old code
                        // used starves ML Kit on small body text), and the
                        // result is fed to the recognizer as-is — no
                        // language-correction pass on either platform.
                        com.tom_roush.pdfbox.rendering.PDFRenderer(doc).renderImage(page, 2.0f)
                    }.getOrNull() ?: continue
                    val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
                    // ML Kit exposes a Task API; bridge it with a CompletableDeferred.
                    val deferred = kotlinx.coroutines.CompletableDeferred<com.google.mlkit.vision.text.Text>()
                    textRecognizer.process(image)
                        .addOnSuccessListener { deferred.complete(it) }
                        .addOnFailureListener { deferred.completeExceptionally(it) }
                    recognitions.add(deferred)
                }
                val sb = StringBuilder()
                recognitions.forEach { deferred ->
                    runCatching { deferred.await() }.getOrNull()?.let { sb.append(it.text).append("\n") }
                }
                doc.close()
                val text = sb.toString().trim()
                if (text.isBlank()) null else text
            }.getOrNull()
        }
    }

    private fun originalExtension(book: Book): String {
        val dir = com.speechnotes.app.logic.AppFiles.bookDirectory(book.id)
        val original = dir.listFiles()?.firstOrNull { it.name.startsWith("original.") } ?: return book.format
        return original.name.removePrefix("original.")
    }

    fun deleteBook(book: Book) {
        // Deleting a playing book must not leave a ghost session (the same
        // guard the iOS shelf applies: stop TTS narration and the audiobook
        // player first). Soft-delete: the directory stays recoverable in the
        // books recycle bin for Book.RECYCLE_RETENTION_DAYS.
        if (isBookNarrating(book)) player.stop()
        audioBooks.stopIfPlaying(book)
        books.delete(book)
        showToast("Deleted \"${book.title.take(32)}\" — recoverable in Recently Deleted")
    }

    fun recoverBook(bookId: String) {
        books.recover(bookId)
        showToast("Restored")
    }

    fun purgeBook(book: Book) {
        if (isBookNarrating(book)) player.stop()
        audioBooks.stopIfPlaying(book)
        books.purge(book)
        showToast("Deleted for good")
    }

    fun emptyBookRecycleBin() {
        // A binned audiobook may still be the active player book (bin entry
        // rows have no stop button); drop it before the files disappear.
        val active = audioBooks.activeBook.value ?: return
        if (books.deletedBooks.value.any { it.id == active.id }) audioBooks.stop()
        books.emptyRecycleBin()
        showToast("Recycle bin emptied")
    }

    // MARK: - Reader positions

    /** EPUB/PDF position: 1-based chapter/page, chapter fraction, epub.js CFI. */
    fun saveBookPosition(bookId: String, position: Int, fraction: Double, cfi: String?) {
        val book = books.book(bookId) ?: return
        books.save(book.copy(position = position, positionFraction = fraction, cfi = cfi))
    }

    fun saveBookFont(bookId: String, fontSize: Int) {
        val book = books.book(bookId) ?: return
        books.save(book.copy(fontSize = fontSize))
    }

    private fun audioKey(bookId: String) = "audio-$bookId"

    /** Saved audiobook chapter to reopen (0-based); persisted per book. */
    fun savedAudioChapter(book: Book): Int {
        val index = prefs.getInt(audioKey(book.id) + "-chapter", 0)
        return index.coerceIn(0, maxOf(0, book.audioChapters.size - 1))
    }

    fun savedAudioOffsetMs(book: Book): Long = prefs.getLong(audioKey(book.id) + "-offset", 0L)

    fun saveAudioPosition(bookId: String, chapterIndex: Int, offsetMs: Long) {
        prefs.edit()
            .putInt(audioKey(bookId) + "-chapter", chapterIndex)
            .putLong(audioKey(bookId) + "-offset", offsetMs.coerceAtLeast(0))
            .apply()
    }

    /** Speaks a book chapter (note-style entry point for the shelf). */
    fun playChapter(book: Book, chapterIndex: Int) {
        val safeIndex = chapterIndex.coerceIn(0, maxOf(0, book.chapters.size - 1))
        appScope.launch {
            val text = chapterText(book, safeIndex)
            if (text.isNullOrBlank()) {
                showToast("No text to read in this chapter yet.")
                return@launch
            }
            player.playBookChapter(
                text = text,
                bookId = book.id,
                chapterIndex = safeIndex,
                title = book.chapters.getOrNull(safeIndex)?.label ?: book.title,
            )
        }
    }

    /**
     * Imports a Joplin `.jex` archive: notes (`<id>.md` + trailing metadata),
     * notebooks (`type_: 2`) and their resources are mapped back into this
     * app's model; resource links are rewritten to `speechnotes://note-image`
     * targets. Ids the app exported are restored verbatim (dashes
     * reconstituted), so an export→import round-trip keeps its identity.
     */
    fun importJex(uri: android.net.Uri) {
        appScope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching null
                    com.speechnotes.app.logic.JexImport.parse(bytes)
                }.getOrElse { error ->
                    LogStore.error("JexImport failed: ${error.message}")
                    null
                }
            }
            if (result == null) {
                showToast("Could not read that .jex file.")
                return@launch
            }

            // Notebooks: restore exported ids when possible, merge by name.
            val notebookIdMap = HashMap<String, String>() // joplin id → app id
            var notebooksAdded = 0
            result.notebooks.forEach { imported ->
                val restoredId = com.speechnotes.app.logic.JexImport.uuid(fromJoplinId = imported.id)
                val existingByRestoredId = restoredId?.let { id -> notebooks.notebooks.value.firstOrNull { it.id == id } }
                val appNotebook = when {
                    existingByRestoredId != null -> existingByRestoredId
                    notebooks.notebooks.value.any { it.name.equals(imported.title, ignoreCase = true) } ->
                        notebooks.notebooks.value.first { it.name.equals(imported.title, ignoreCase = true) }
                    else -> {
                        val created = restoredId?.let { notebooks.addExisting(it, imported.title) }
                            ?: notebooks.create(imported.title)
                        notebooksAdded++
                        created
                    }
                }
                if (appNotebook != null) notebookIdMap[imported.id] = appNotebook.id
            }

            // Notes: store referenced resources first so the rewritten
            // markdown points at files that exist.
            var notesImported = 0
            var imagesImported = 0
            result.notes.forEach { imported ->
                val restoredId = com.speechnotes.app.logic.JexImport.uuid(fromJoplinId = imported.id)
                val noteId = restoredId ?: java.util.UUID.randomUUID().toString()
                var body = imported.body
                imported.resources.forEach { (resourceId, markdownTarget) ->
                    val resource = result.resources.firstOrNull { it.id == resourceId } ?: return@forEach
                    val target = com.speechnotes.app.logic.NoteImageStore.store(
                        resource.data, resource.fileExtension, noteId,
                    )
                    if (target != null) {
                        body = body.replace(markdownTarget, target)
                        imagesImported++
                    }
                }
                val notebookId = imported.notebookId?.let { notebookIdMap[it] }
                val existing = notes.note(noteId)
                if (existing != null) {
                    notes.update(existing.copy(text = body, notebookId = notebookId))
                } else {
                    // E6: restore the exported title and timestamps — the
                    // imported note keeps its identity, not a fresh "now".
                    notes.importNote(
                        noteId, body, notebookId,
                        explicitTitle = imported.title.takeIf { it.isNotBlank() },
                        createdAt = imported.createdAt.takeIf { it > 0 },
                        updatedAt = imported.updatedAt.takeIf { it > 0 },
                    )
                }
                notesImported++
            }
            notes.flushNow()
            val parts = mutableListOf<String>()
            parts.add("$notesImported notes")
            if (notebooksAdded > 0) parts.add("$notebooksAdded notebooks")
            if (imagesImported > 0) parts.add("$imagesImported images")
            showToast("Imported ${parts.joinToString(", ")}")
        }
    }

    // MARK: - Downloads

    fun setEngineKind(kind: EngineKind) {
        // SpeechPlayer owns the pref + engine rebuild (and stops playback);
        // duplicating any of that here desyncs the two.
        player.setEngineKind(kind)
    }

    fun engineKindValue(): EngineKind = player.engineKind.value

    fun downloadModel(engine: EngineKind) = models.startDownload(engine, appScope)

    fun isModelReady(engine: EngineKind): Boolean = models.isReadyCached(engine)

    fun refreshStorageUsage() = models.refreshStorageUsage(appScope)

    // MARK: - Lifecycle hooks

    override fun onCleared() {
        super.onCleared()
        player.shutdown()
        notes.flushNow()
    }

    companion object {
        val Factory: androidx.lifecycle.ViewModelProvider.Factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val app = extras[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return SpeechnotesViewModel(app as android.app.Application) as T
            }
        }
    }
}

/** PdfText page source backed by PdfBox-Android (outline + text + geometry). */
class PdfPageSource(private val file: File) : com.speechnotes.app.logic.PdfText.PageSource {

    private val document: com.tom_roush.pdfbox.pdmodel.PDDocument? =
        runCatching { com.tom_roush.pdfbox.pdmodel.PDDocument.load(file) }.getOrNull()

    override val pageCount: Int
        get() = runCatching { document?.numberOfPages ?: 0 }.getOrDefault(0)

    override fun outline(): List<Pair<String, Int>> {
        val doc = document ?: return emptyList()
        return runCatching {
            val result = ArrayList<Pair<String, Int>>()
            fun walk(node: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode?) {
                var current = node?.firstChild
                while (current != null) {
                    if (current is com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem) {
                        val title = current.title ?: ""
                        val dest = runCatching { current.destination }.getOrNull()
                        val page = (dest as? com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination)?.findPageNumber()
                        if (title.isNotBlank() && page != null && page >= 0) result.add(title to page)
                    }
                    walk(current)
                    current = current.nextSibling
                }
            }
            walk(doc.documentCatalog.documentOutline)
            result
        }.getOrDefault(emptyList())
    }

    /**
     * Real per-line geometry (x/y/height/width) recorded from the stripper's
     * writeString calls. Pdfbox fires one writeString per word (its word
     * separator flushes between them), so fragments are merged into rows:
     * a fragment continues the current line while its baseline y matches
     * and its x sits to the right of the previous fragment's right edge.
     * The fabricated geometry this replaces (every line x=0, height=12,
     * y=index*12) made both geometric detectors — the two-column gutter and
     * the heading-height tier — dead code.
     */
    override fun lines(page: Int): List<com.speechnotes.app.logic.PdfText.Line> {
        val doc = document ?: return emptyList()
        return runCatching {
            val stripper = object : com.tom_roush.pdfbox.text.PDFTextStripper() {
                val texts = ArrayList<String>()
                val baselines = ArrayList<Float>()
                val lefts = ArrayList<Float>()
                val heights = ArrayList<Float>()
                val widths = ArrayList<Float>()

                private fun rightEdge(index: Int): Float {
                    val left = lefts[index]
                    val width = widths[index]
                    return left + width
                }

                override fun writeString(
                    text: String,
                    positions: List<com.tom_roush.pdfbox.text.TextPosition>,
                ) {
                    if (text.isBlank() || positions.isEmpty()) return
                    val first = positions.first()
                    var fragmentWidth = 0f
                    for (pos in positions) fragmentWidth += pos.width
                    val last = texts.size - 1
                    val continuous = last >= 0 &&
                        kotlin.math.abs(first.y - baselines[last]) < 2f &&
                        first.x >= rightEdge(last) - 2f
                    if (continuous && last >= 0) {
                        texts[last] = texts[last] + text
                        widths[last] = widths[last] + fragmentWidth + (first.x - rightEdge(last)).coerceAtLeast(0f)
                    } else {
                        texts.add(text)
                        baselines.add(first.y)
                        lefts.add(first.x)
                        heights.add(first.heightDir)
                        widths.add(fragmentWidth)
                    }
                }
            }
            stripper.startPage = page + 1
            stripper.endPage = page + 1
            stripper.sortByPosition = true
            stripper.getText(doc)
            val rows = stripper.texts.indices.mapNotNull { i ->
                val text = stripper.texts[i].trim()
                if (text.isEmpty()) return@mapNotNull null
                val height = stripper.heights[i].toDouble().coerceAtLeast(1.0)
                val left = stripper.lefts[i].toDouble()
                com.speechnotes.app.logic.PdfText.Line(
                    text = text,
                    // PDF space is bottom-origin; report the baseline as-is —
                    // every consumer compares lines within one page, so the
                    // flip direction cancels out.
                    y = stripper.baselines[i].toDouble(),
                    height = height,
                    x = left,
                    width = stripper.widths[i].toDouble().coerceAtLeast(0.0),
                )
            }
            rows
        }.getOrDefault(emptyList())
    }

    override fun pageText(page: Int): String {
        val doc = document ?: return ""
        return runCatching {
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            stripper.startPage = page + 1
            stripper.endPage = page + 1
            stripper.getText(doc)
        }.getOrDefault("")
    }
}

/** Helper for querying a SAF uri's display name. */
object ModelManagerImportHelper {
    fun queryName(context: Context, uri: android.net.Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
}
