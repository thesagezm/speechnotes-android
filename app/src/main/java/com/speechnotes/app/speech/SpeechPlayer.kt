package com.speechnotes.app.speech

import android.content.Context
import com.speechnotes.app.data.BookmarkStore
import com.speechnotes.app.data.LogStore
import com.speechnotes.app.data.Prefs
import com.speechnotes.app.logic.SentenceChunker
import com.speechnotes.app.logic.TextHash
import com.speechnotes.app.model.Note
import com.speechnotes.app.model.VoiceCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * UI-facing wrapper around the active speech engine. Ported from
 * SpeechPlayer.swift (speechnotes-ios): engine selection, voice/speed
 * persistence, progress/state, read-along coordinates, resume bookmarks,
 * WAV export, sleep timer, and the audition mechanism the voice picker
 * uses.
 */
class SpeechPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bookmarks: BookmarkStore,
) {

    // MARK: - Published state

    private val _state = MutableStateFlow(SpeechState.IDLE)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<Double?>(null)
    val progress: StateFlow<Double?> = _progress.asStateFlow()

    private val _rateMultiplier = MutableStateFlow(1.0)
    val rateMultiplier: StateFlow<Double> = _rateMultiplier.asStateFlow()

    private val _engineKind = MutableStateFlow(EngineKind.SYSTEM)
    val engineKind: StateFlow<EngineKind> = _engineKind.asStateFlow()

    private val _voice = MutableStateFlow("am_eric")
    val voice: StateFlow<String> = _voice.asStateFlow()

    private val _supertonicVoice = MutableStateFlow("M1")
    val supertonicVoice: StateFlow<String> = _supertonicVoice.asStateFlow()

    private val _supertonicLang = MutableStateFlow("en")
    val supertonicLang: StateFlow<String> = _supertonicLang.asStateFlow()

    private val _systemVoice = MutableStateFlow<String?>(null)
    val systemVoice: StateFlow<String?> = _systemVoice.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _shareFile = MutableStateFlow<File?>(null)
    val shareFile: StateFlow<File?> = _shareFile.asStateFlow()

    private val _nowPlayingTitle = MutableStateFlow<String?>(null)
    val nowPlayingTitle: StateFlow<String?> = _nowPlayingTitle.asStateFlow()

    private val _nowPlayingNoteId = MutableStateFlow<String?>(null)
    val nowPlayingNoteId: StateFlow<String?> = _nowPlayingNoteId.asStateFlow()

    private val _nowPlayingBookId = MutableStateFlow<String?>(null)
    val nowPlayingBookId: StateFlow<String?> = _nowPlayingBookId.asStateFlow()

    private val _auditioningVoice = MutableStateFlow<String?>(null)
    val auditioningVoice: StateFlow<String?> = _auditioningVoice.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleepTimer.asStateFlow()

    // MARK: - Read-along

    private val _activeSpeechText = MutableStateFlow<String?>(null)
    val activeSpeechText: StateFlow<String?> = _activeSpeechText.asStateFlow()

    private val _readAlongRange = MutableStateFlow<IntRange?>(null)
    val readAlongRange: StateFlow<IntRange?> = _readAlongRange.asStateFlow()

    private var readAlongPieces: List<Pair<Int, Int>> = emptyList()

    /** Highest engine-reported char count for the current utterance (progress
     * can be null/coalesced when the pause lands between ticks). */
    @Volatile private var playedCharsHighWater = 0
    private var engineSpeechOffset = 0

    val readAlongActive: Boolean
        get() = _activeSpeechText.value != null && _state.value.isActive

    // MARK: - Sleep timer

    sealed class SleepTimer {
        data object Off : SleepTimer()
        data class Minutes(val minutes: Int) : SleepTimer()
        data object EndOfChapter : SleepTimer()
    }

    private var sleepTimerJob: Job? = null

    // MARK: - Engines

    private var systemEngine: SystemTtsEngine? = null
    private var kokoroEngine: KokoroEngine? = null
    private var supertonicEngine: SupertonicEngine? = null
    private var activeEngine: SpeechEngine? = null

    /** True when a neural engine is selected but its model isn't downloaded. */
    @Volatile
    var usingSystemFallback: Boolean = false
        private set

    /** Current playback identity — note or book chapter. */
    private class PlaybackRef(
        val noteId: String?,
        val bookId: String?,
        val chapterIndex: Int?,
        val title: String,
    )

    private var playing: PlaybackRef? = null

    /** Fired once on natural completion of the current chapter. */
    var onNaturalFinish: (() -> Unit)? = null

    /** The text handed to the engine for the current playback. */
    private var spokenText: String = ""

    init {
        val prefs = Prefs.prefs(context)
        _rateMultiplier.value = prefs.getFloat(Prefs.RATE_MULTIPLIER, 1.0f).toDouble()
        _engineKind.value = EngineKind.fromKey(prefs.getString(Prefs.ENGINE_KIND, "system") ?: "system")
        _voice.value = prefs.getString(Prefs.VOICE, "am_eric") ?: "am_eric"
        _supertonicVoice.value = prefs.getString(Prefs.SUPERTONIC_VOICE, "M1") ?: "M1"
        _supertonicLang.value = prefs.getString(Prefs.SUPERTONIC_LANG, "en") ?: "en"
        _systemVoice.value = prefs.getString(Prefs.SYSTEM_VOICE, null)
        rebuildEngine()
    }

    // MARK: - Audio focus (AudioSessionSetup.swift parity)

    private val audioManager =
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager

    private var focusRequest: android.media.AudioFocusRequest? = null

    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS ->
                if (_state.value.isActive) stop()
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                if (_state.value == SpeechState.SPEAKING) pause()
            // GAIN: the user taps play to resume (same as the iOS app).
        }
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        runCatching {
            val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            manager.requestAudioFocus(request)
            focusRequest = request
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
        focusRequest = null
    }

    // MARK: - Engine construction

    private fun rebuildEngine() {
        val kind = _engineKind.value
        activeEngine?.stop()
        // When a neural engine is selected but its model isn't downloaded, the
        // system engine is the ACTIVE engine — so playback still works and the
        // voice description says so.
        usingSystemFallback = when (kind) {
            EngineKind.SYSTEM -> false
            EngineKind.KOKORO, EngineKind.KOKORO_SMALL -> !ModelFiles.isValid(kind)
            EngineKind.SUPERTONIC -> !ModelFiles.isValid(EngineKind.SUPERTONIC)
        }
        activeEngine = when (kind) {
            EngineKind.SYSTEM -> system()
            EngineKind.KOKORO, EngineKind.KOKORO_SMALL ->
                if (usingSystemFallback) system() else kokoroEngineFor(kind)
            EngineKind.SUPERTONIC ->
                if (usingSystemFallback) system() else supertonic()
        }
        activeEngine?.speed = _rateMultiplier.value.toFloat()
        LogStore.info("SpeechPlayer: engine = ${kind.key} (active=${activeEngine?.name}, fallback=$usingSystemFallback)")
        // Every rebuild re-warms the newly active engine so engine switches
        // don't re-pay the model load on the first play either.
        if (!usingSystemFallback) runCatching { activeEngine?.prewarm() }
    }

    private fun system(): SpeechEngine =
        systemEngine ?: SystemTtsEngine(context).also {
            it.voiceIdentifier = _systemVoice.value
            observe(it)
            systemEngine = it
        }

    private fun supertonic(): SpeechEngine =
        supertonicEngine ?: SupertonicEngine().also {
            observe(it)
            supertonicEngine = it
        }.also {
            it.voice = _supertonicVoice.value
            it.lang = _supertonicLang.value
        }

    private fun kokoroEngineFor(kind: EngineKind): KokoroEngine =
        kokoroEngine?.takeIf { it.engineKind == kind }
            ?: KokoroEngine(kind).also {
                kokoroEngine = it
                it.voice = _voice.value
                // Without observe() the engine's state/progress/finished
                // callbacks never reach the player: UI stayed IDLE while the
                // engine spoke — no mini player, no pause toggle, and extra
                // taps restarted generation from scratch.
                observe(it)
            }

    private fun observe(engine: SpeechEngine) {
        engine.onStateChanged = { newState ->
            // Player-level transitions are the diagnostic trail: engine logs
            // alone can't distinguish "engine busy" from "wiring dead" (the
            // Kokoro observe() bug read as engine GENERATING while the player
            // stayed IDLE forever — no mini player, no pause toggle).
            LogStore.info("SpeechPlayer: state ${_state.value} → $newState")
            scope.launch { _state.value = newState }
            // Elapsed bank for the lock screen (NowPlayingCenter parity):
            // wall-clock accumulation of time actually spent SPEAKING.
            when (newState) {
                SpeechState.SPEAKING -> playStartedAt = System.currentTimeMillis()
                else -> {
                    if (playStartedAt > 0) {
                        bankedElapsedMs += System.currentTimeMillis() - playStartedAt
                        playStartedAt = 0
                    }
                }
            }
            // Background audio + lock screen: while any session is active the
            // mediaPlayback foreground service carries it.
            if (newState.isActive) {
                com.speechnotes.app.playback.SpeechPlaybackService.start(context)
            }
            if (newState == SpeechState.IDLE) {
                _progress.value = null
                armIdleUnload()
            } else {
                idleUnloadJob?.cancel()
            }
        }
        engine.onProgress = { p -> scope.launch { _progress.value = p } }
        engine.onPlayedChars = { chars -> updateReadAlong(chars) }
        engine.onFinished = { onFinish() }
    }

    // MARK: - Lock screen / remote commands (NowPlayingCenter parity)

    private var playStartedAt = 0L
    private var bankedElapsedMs = 0L

    /**
     * Idle engine unload (iOS SpeechPlayer.swift:694,717 parity): neural
     * engines hold 300–400 MB of resident ONNX sessions; a session left
     * loaded after playback ends invites the system to kill the whole
     * process. Five minutes after the state goes IDLE the sessions are
     * dropped — the next play reloads from disk and re-pays only the load
     * latency (~2–3 s), which a user who just tapped play accepts.
     */
    private var idleUnloadJob: Job? = null

    private fun armIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch {
            delay(5 * 60 * 1000L)
            // Re-check: a play tap during the wait restarted playback.
            if (_state.value.isActive) return@launch
            runCatching { kokoroEngine?.unloadModel() }
            runCatching { supertonicEngine?.unloadModel() }
        }
    }

    /** Seconds of audio actually spoken (banked across pause/resume). */
    val elapsedSeconds: Double
        get() {
            val live = if (_state.value == SpeechState.SPEAKING && playStartedAt > 0) {
                System.currentTimeMillis() - playStartedAt
            } else 0L
            return (bankedElapsedMs + live) / 1000.0
        }

    /** Rough total duration derived from progress; 0 until progress advances. */
    val estimatedTotalSeconds: Double
        get() {
            val p = _progress.value ?: return 0.0
            if (p < 0.05) return 0.0
            return elapsedSeconds / p
        }

    /** Chapter skip from the lock screen / headset (book narration only). */
    var onRemoteChapterSkip: ((Int) -> Unit)? = null

    fun remoteToggle() = toggle()

    fun remoteChapterSkip(delta: Int) {
        onRemoteChapterSkip?.invoke(delta)
    }

    private fun onFinish() {
        val ref = playing
        // Clear the session BEFORE the callback so a handler that starts the
        // next chapter (book auto-advance) sets up a fresh state that this
        // function then can't clobber.
        endReadAlong()
        clearPlaying()
        if (ref?.bookId != null) {
            if (_sleepTimer.value == SleepTimer.EndOfChapter) {
                setSleepTimer(SleepTimer.Off)
                stop()
                return
            }
            onNaturalFinish?.invoke()
        }
    }

    private fun clearPlaying() {
        playing = null
        _nowPlayingTitle.value = null
        _nowPlayingNoteId.value = null
        _nowPlayingBookId.value = null
    }

    // MARK: - Settings mutators

    fun setEngineKind(kind: EngineKind) {
        if (kind == _engineKind.value) return
        _engineKind.value = kind
        Prefs.prefs(context).edit().putString(Prefs.ENGINE_KIND, kind.key).apply()
        stop()
        rebuildEngine()
    }

    fun setVoice(codename: String, engine: EngineKind = _engineKind.value) {
        when (engine) {
            EngineKind.SUPERTONIC -> _supertonicVoice.value = codename
            else -> _voice.value = codename
        }
        val key = if (engine == EngineKind.SUPERTONIC) Prefs.SUPERTONIC_VOICE else Prefs.VOICE
        Prefs.prefs(context).edit().putString(key, codename).apply()
        rememberRecentVoice(engine, codename)
        if (kindUsesKokoro(engine)) kokoroEngine?.voice = codename
        if (engine == EngineKind.SUPERTONIC) supertonicEngine?.voice = codename
    }

    /**
     * Recents, per engine family (iOS VoicePickerSheet parity): the last
     * five voices the user actually picked, newest first. Separate lists so
     * a Supertonic style id never collides with a Kokoro codename.
     */
    private fun rememberRecentVoice(engine: EngineKind, codename: String) {
        val key = when (engine) {
            EngineKind.SUPERTONIC -> "recentSupertonicVoices"
            EngineKind.KOKORO_SMALL -> "recentKokoroSmallVoices"
            else -> "recentKokoroVoices"
        }
        val prefs = Prefs.prefs(context)
        val current = prefs.getStringSet(key, emptySet()).orEmpty()
        // Newest first: prepend the fresh pick, cap at 5, store as a set
        // (order lives in the list we rebuild on read).
        val updated = LinkedHashSet(current)
        updated.add(codename)
        val capped = LinkedHashSet(updated.toList().takeLast(5))
        prefs.edit().putStringSet(key, capped).apply()
    }

    /** Recent voice ids for [engine], newest first (read by the picker). */
    fun recentVoices(engine: EngineKind): List<String> {
        val key = when (engine) {
            EngineKind.SUPERTONIC -> "recentSupertonicVoices"
            EngineKind.KOKORO_SMALL -> "recentKokoroSmallVoices"
            else -> "recentKokoroVoices"
        }
        return Prefs.prefs(context).getStringSet(key, emptySet()).orEmpty().toList()
    }

    private fun kindUsesKokoro(engine: EngineKind) =
        engine == EngineKind.KOKORO || engine == EngineKind.KOKORO_SMALL

    fun setSupertonicLang(code: String) {
        _supertonicLang.value = code
        Prefs.prefs(context).edit().putString(Prefs.SUPERTONIC_LANG, code).apply()
        supertonicEngine?.lang = code
    }

    fun setSystemVoice(identifier: String?) {
        _systemVoice.value = identifier
        Prefs.prefs(context).edit().apply {
            if (identifier == null) remove(Prefs.SYSTEM_VOICE)
            else putString(Prefs.SYSTEM_VOICE, identifier)
        }
        systemEngine?.voiceIdentifier = identifier
    }

    fun setRateMultiplier(rate: Double) {
        _rateMultiplier.value = rate.coerceIn(0.5, 2.0)
        Prefs.prefs(context).edit().putFloat(Prefs.RATE_MULTIPLIER, _rateMultiplier.value.toFloat()).apply()
        activeEngine?.speed = _rateMultiplier.value.toFloat()
    }

    /** Rebuilds the engine after a model download completes. */
    fun refreshEngineAvailability() {
        rebuildEngine()
    }

/**
         * Warms the active engine off-thread (ONNX sessions, G2P). Called at app
         * start and after a model download so the first Play skips the model-load
         * stall — the "time to first audio" complaint.
         */
        fun prewarmActiveEngine() {
            if (usingSystemFallback) return
            runCatching { activeEngine?.prewarm() }
        }

        // One engine-level retry on a fresh play. Kokoro/Supertonic
        // load 30–400 MB of ONNX on first use; on a low-memory device that
        // load is the single most likely thing to be OOM-killed, and once the
        // process dies the log's last line is a bare "chunk 0 failed", which
        // reads as a model problem rather than a kill.
        @Volatile
        var lastEngineFailure: String? = null
            private set

        fun noteEngineFailure(message: String) {
            lastEngineFailure = message
            LogStore.error("SpeechPlayer: engine failure — $message")
        }

    // MARK: - Playback

    /**
     * Play/pause the note's text. [note] feeds the mini-player's title and
     * jump-to-note tap; omitting it plays anonymous text.
     */
    fun togglePlay(text: String, note: Note? = null) {
        when (_state.value) {
            SpeechState.SPEAKING -> { pause(); return }
            SpeechState.PAUSED -> { resume(); return }
            // A tap while GENERATING stops, it does not fall on the floor. The
            // buttons used to be disabled for the whole model-load window,
            // which read as a dead control.
            SpeechState.GENERATING -> { stop(); return }
            SpeechState.IDLE -> Unit
        }
        speak(text, noteId = note?.id, bookId = null, chapterIndex = null, title = note?.title ?: "Speaking")
    }

    /**
     * Book-chapter entry point: bookId + chapterIndex drive bookmark keys.
     * [startCharOffset] > 0 starts mid-chapter (the reader's visible spot)
     * and is honored VERBATIM — the chapter's resume bookmark is ignored,
     * otherwise pressing play on page 50 always resurfaced wherever the
     * previous session happened to stop.
     */
    fun playBookChapter(
        text: String,
        bookId: String,
        chapterIndex: Int,
        title: String,
        startCharOffset: Int = 0,
    ) {
        val actualText = if (startCharOffset > 0 && startCharOffset < text.length) {
            text.substring(startCharOffset)
        } else text
        speak(
            actualText,
            noteId = null,
            bookId = bookId,
            chapterIndex = chapterIndex,
            title = title,
            baseOffset = if (startCharOffset > 0) startCharOffset else 0,
            honorBookmark = startCharOffset <= 0,
        )
    }

    private fun speak(
        text: String,
        noteId: String?,
        bookId: String?,
        chapterIndex: Int?,
        title: String,
        baseOffset: Int = 0,
        honorBookmark: Boolean = true,
    ) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        // A failed play is retried on a fresh tap rather than left permanently
        // broken: the common cause is the OS killing the process during the
        // (huge) one-time model load, and the engine re-loads fine next time.
        lastEngineFailure = null
        stop()

        playing = PlaybackRef(noteId, bookId, chapterIndex, title)
        _nowPlayingTitle.value = title
        _nowPlayingNoteId.value = noteId
        _nowPlayingBookId.value = bookId

        spokenText = cleaned
        engineSpeechOffset = baseOffset

        // New session: the lock-screen elapsed time restarts.
        playStartedAt = 0
        bankedElapsedMs = 0
        playedCharsHighWater = 0

        val pieces = SentenceChunker.sentencePieces(cleaned)
        beginReadAlong(cleaned, pieces)

        // Resume bookmark: sentence-snapped restart when the text is unchanged.
        // A forced baseOffset (reader-initiated play from the visible spot)
        // overrides the bookmark — that offset IS the resume point the user
        // is looking at.
        val resumeOffset = if (honorBookmark) bookmarkOffset(noteId, bookId, chapterIndex, cleaned) else 0
        val toSpeak = if (baseOffset > 0) {
            cleaned
        } else if (resumeOffset > 0 && resumeOffset < cleaned.length) cleaned.substring(resumeOffset) else cleaned
        engineSpeechOffset = baseOffset + if (baseOffset > 0) 0 else resumeOffset

        // Publish GENERATING *synchronously*, before handing off to the
        // engine. Every engine reaches GENERING asynchronously (Kokoro and
        // Supertonic do it from the generate queue once the model is loaded,
        // the system TTS waits for its own onStart) — with a multi-second ONNX
        // load in front, the play button stayed a static PlayArrow for
        // seconds after the tap and the whole session was unreachable: no
        // mini-player (it gates on state.isActive) and no Stop button. The tap
        // that finally landed is what the user heard as "the button only
        // registered later".
        _state.value = SpeechState.GENERATING
        activeEngine?.speak(toSpeak, _rateMultiplier.value)
        requestFocus()
        if (_auditioningVoice.value != null) stopAudition()
    }

    /** True when a resume bookmark exists for this note (iOS hasResumeOption parity). */
    fun hasResumeBookmark(noteId: String?): Boolean {
        if (noteId == null) return false
        val bookmark = bookmarks.forNoteRaw(noteId) ?: return false
        return bookmark.savedAt > System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
    }

    /** Drops the resume bookmark so the next play starts from sentence one. */
    fun clearResumeBookmark(noteId: String?) {
        if (noteId == null) return
        bookmarks.remove("note:$noteId")
    }

    private fun bookmarkOffset(noteId: String?, bookId: String?, chapterIndex: Int?, text: String): Int {
        val hash = TextHash.of(text)
        val bookmark = when {
            noteId != null -> bookmarks.forNote(noteId, hash, text.length)
            bookId != null && chapterIndex != null -> bookmarks.forBookChapter(bookId, chapterIndex, hash)
            else -> null
        } ?: return 0
        return SentenceChunker.resumeOffset(text, bookmark.charsDone)
    }

    fun pause() {
        if (_state.value != SpeechState.SPEAKING) return
        persistBookmark()
        activeEngine?.pause()
    }

    fun resume() {
        if (_state.value != SpeechState.PAUSED) return
        activeEngine?.resume()
    }

    fun stop() {
        persistBookmark()
        activeEngine?.stop()
        stopAudition()
        endReadAlong()
        abandonFocus()
        _progress.value = null
        clearPlaying()
        // The engines only report IDLE from an asynchronous callback, and the
        // system TTS's never arrives at all when no TTS engine is installed
        // (`tts.stop()` fails on "not bound"). Without this the player stayed
        // GENERATING forever: no Stop worked, the mini player never came down,
        // and the next Play tap was treated as a cancel.
        if (_state.value != SpeechState.IDLE) {
            LogStore.info("SpeechPlayer: forcing state $_state → IDLE after stop()")
            _state.value = SpeechState.IDLE
        }
    }

    /** Called when the app goes to the background: save + resume bookmarks. */
    fun onBackground() {
        persistBookmark()
        LogStore.info("SpeechPlayer: backgrounded at ${engineSpeechOffset + playedCharsHighWater} chars (state=$_state)")
    }

    /** Called when the app returns to the foreground. */
    fun onForeground() {
        // Playback resumes on tap; nothing to restore automatically.
    }

    private fun persistBookmark() {
        val ref = playing ?: return
        LogStore.info("SpeechPlayer: persistBookmark chars=${engineSpeechOffset + playedCharsHighWater} progress=${_progress.value} textLen=${spokenText.length} state=${_state.value}")
        // Prefer the engine's char reports — progress (the coalesced 300ms
        // signal) is often null when the pause lands between ticks, which
        // silently dropped the bookmark.
        // Position source, in order: engine char reports (neural engines
        // report per chunk), progress fraction, then a wall-clock estimate
        // (~14.5 chars/sec) for engines that report neither — the system TTS
        // can pause with no range callback ever having fired, and losing the
        // bookmark there means the user always restarts from sentence one.
        val charsDone = engineSpeechOffset +
            if (playedCharsHighWater > 0) playedCharsHighWater
            else _progress.value?.let { (it * spokenText.length).toInt() }
                ?: (elapsedSeconds * 14.5).toInt().coerceAtMost(spokenText.length)
        if (charsDone <= 0) return
        bookmarks.save(
            noteId = ref.noteId,
            bookId = ref.bookId,
            chapterIndex = ref.chapterIndex,
            charsDone = charsDone,
            textLength = spokenText.length,
            textHash = TextHash.of(spokenText),
        )
    }

    // MARK: - Read-along

    private fun beginReadAlong(text: String, pieces: List<Pair<Int, Int>>) {
        _activeSpeechText.value = text
        _readAlongRange.value = null
        readAlongPieces = pieces
    }

    private fun updateReadAlong(engineCharsDone: Int) {
        if (engineCharsDone > playedCharsHighWater) playedCharsHighWater = engineCharsDone
        if (readAlongPieces.isEmpty()) return
        val fullChar = engineSpeechOffset + engineCharsDone
        val newRange = readAlongPieces.lastOrNull { fullChar >= it.first }?.let { it.first until it.second }
            ?: readAlongPieces.firstOrNull()?.let { it.first until it.second }
        if (_readAlongRange.value != newRange) _readAlongRange.value = newRange
    }

    private fun endReadAlong() {
        _activeSpeechText.value = null
        _readAlongRange.value = null
        readAlongPieces = emptyList()
    }

    // MARK: - Sleep timer

    fun setSleepTimer(timer: SleepTimer) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = timer
        when (timer) {
            is SleepTimer.Minutes -> {
                sleepTimerJob = scope.launch {
                    delay(timer.minutes * 60_000L)
                    _sleepTimer.value = SleepTimer.Off
                    stop()
                }
            }
            SleepTimer.EndOfChapter, SleepTimer.Off -> Unit
        }
    }

    // MARK: - Audition (voice picker)

    private val auditionText = VoiceCatalog.auditionText("")

    fun audition(voiceCodename: String) {
        // Don't interrupt an active playback with an audition (as on iOS).
        if (_state.value != SpeechState.IDLE) return
        val engine = _engineKind.value
        stopAudition()
        _auditioningVoice.value = voiceCodename
        val text = auditionText.replace("I'm .", "I'm ${VoiceCatalog.shortName(voiceCodename, engine.key)}.")
        val savedVoice = if (engine == EngineKind.SUPERTONIC) _supertonicVoice.value else _voice.value
        setVoice(voiceCodename, engine)
        // Speak the audition WITHOUT binding to a note (no bookmark).
        playing = null
        spokenText = text
        engineSpeechOffset = 0
        beginReadAlong(text, SentenceChunker.sentencePieces(text))
        activeEngine?.speak(text, _rateMultiplier.value)
        requestFocus()
        // Restore the previous voice when the audition stops naturally. The
        // watcher waits for the state to LEAVE idle first — engines reach
        // IDLE→GENERATING asynchronously, and polling IDLE immediately would
        // cancel the audition before a single sample is scheduled (the
        // "audition does nothing" bug).
        scope.launch {
            var leftIdle = _state.value != SpeechState.IDLE
            var waitedMs = 0L
            while (_auditioningVoice.value != null) {
                delay(200)
                waitedMs += 200
                if (!leftIdle) {
                    if (_state.value != SpeechState.IDLE) leftIdle = true
                    else if (waitedMs > 8000) break // engine never started (chunk failures)
                } else if (_state.value == SpeechState.IDLE) {
                    stopAudition()
                    setVoice(savedVoice, engine)
                    break
                }
                if (waitedMs > 120_000) break
            }
        }
    }

    fun stopAudition() {
        _auditioningVoice.value = null
    }

    fun cancelAuditionRestore() {
        // Selecting a voice explicitly ends the audition and keeps the choice.
        _auditioningVoice.value = null
    }

    val isAuditioning: Boolean get() = _auditioningVoice.value != null

    // MARK: - Description

    val currentVoiceDescription: String
        get() = when (_engineKind.value) {
            EngineKind.SYSTEM -> "System voice"
            EngineKind.KOKORO, EngineKind.KOKORO_SMALL ->
                if (usingSystemFallback) "System voice (model missing)"
                else VoiceCatalog.subtitle(_voice.value, _engineKind.value.key)
            EngineKind.SUPERTONIC ->
                if (usingSystemFallback) "System voice (model missing)"
                else VoiceCatalog.subtitle(_supertonicVoice.value, "supertonic")
        }

    /**
     * Play/pause from the mini-player: pause while speaking, resume when
     * paused, and replay the last spoken text from its bookmark otherwise.
     */
    fun toggle() {
        when (_state.value) {
            SpeechState.SPEAKING -> pause()
            SpeechState.GENERATING -> stop()
            SpeechState.PAUSED -> resume()
            else -> if (spokenText.isNotBlank()) {
                speak(
                    spokenText,
                    noteId = _nowPlayingNoteId.value,
                    bookId = _nowPlayingBookId.value,
                    chapterIndex = playing?.chapterIndex,
                    title = _nowPlayingTitle.value ?: "Speaking",
                )
            }
        }
    }

    val showMiniPlayer: Boolean
        get() = _state.value.isActive && !isAuditioning

    // MARK: - WAV export

    fun export(text: String, title: String) {
        if (_engineKind.value.isNeural && usingSystemFallback) {
            _exportState.value = ExportState.Failed("The model isn't downloaded — pick another engine.")
            return
        }
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        val engine = activeEngine ?: return
        _exportState.value = ExportState.Running(0.0)
        val output = File(com.speechnotes.app.logic.AppFiles.exportsDir, "${safeFilename(title)}.wav")
        when (engine) {
            is KokoroEngine -> scope.launch {
                engine.core.exportWav(cleaned, output, { p ->
                    _exportState.value = ExportState.Running(p)
                }, { file ->
                    if (file != null) {
                        _shareFile.value = file
                        _exportState.value = ExportState.Idle
                    } else {
                        _exportState.value = ExportState.Failed("Export produced no audio.")
                    }
                })
            }
            is SupertonicEngine -> scope.launch {
                engine.core.exportWav(cleaned, output, { p ->
                    _exportState.value = ExportState.Running(p)
                }, { file ->
                    if (file != null) {
                        _shareFile.value = file
                        _exportState.value = ExportState.Idle
                    } else {
                        _exportState.value = ExportState.Failed("Export produced no audio.")
                    }
                })
            }
            else -> _exportState.value = ExportState.Failed("The system voice can't export audio — pick a neural engine.")
        }
    }

    fun dismissExport() {
        _shareFile.value = null
        _exportState.value = ExportState.Idle
    }

    private fun safeFilename(text: String): String {
        val cleaned = text.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return if (cleaned.isEmpty()) "note" else cleaned
    }

    fun shutdown() {
        stop()
        systemEngine?.shutdown()
        kokoroEngine?.release()
        supertonicEngine?.release()
    }
}
