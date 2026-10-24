package com.speechnotes.app.speech

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import com.speechnotes.app.data.LogStore
import com.speechnotes.app.model.Book
import com.speechnotes.app.playback.SpeechPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-level audiobook file player — the port of the iOS AudioBookPlayer
 * (round 3/4 rebuild): playback lives HERE, not in the reader composable, so
 * audio survives tab switches and navigation, the chapter ticker/auto-advance
 * and position persistence run app-wide, and the lock-screen surface gets
 * absolute elapsed/duration for its seek bar.
 *
 * One MediaPlayer for the whole file, prepared ASYNC once per book; chapter
 * stepping is seek-per-chapter against the parsed chapter table. The reader
 * only binds to the published state and sends commands.
 */
class AudioBookPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSavePosition: (bookId: String, chapterIndex: Int, offsetMs: Long) -> Unit,
) {

    private val _activeBook = MutableStateFlow<Book?>(null)
    val activeBook: StateFlow<Book?> = _activeBook.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex: StateFlow<Int> = _chapterIndex.asStateFlow()

    private val _chapterTitle = MutableStateFlow<String?>(null)
    val chapterTitle: StateFlow<String?> = _chapterTitle.asStateFlow()

    /** Absolute file position — what the lock screen and scrubber show. */
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    val hasActiveBook: Boolean get() = _activeBook.value != null

    private var mediaPlayer: MediaPlayer? = null
    private var prepared = false
    private var pendingSeekMs: Long? = null
    private var pendingStart = false
    private var ticker: Job? = null

    // MARK: - Audio focus (AudioSessionSetup parity)

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> if (_isPlaying.value) pause()
        }
    }

    private fun requestFocus() {
        runCatching {
            audioManager?.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonFocus() {
        runCatching { audioManager?.abandonAudioFocus(focusListener) }
    }

    // MARK: - Transport

    /** Starts (or moves) playback of [book] at chapter [index] + [offsetMs]. */
    fun play(book: Book, chapterIndex: Int, offsetMs: Long = 0L) {
        if (_activeBook.value?.id != book.id) {
            releasePlayer()
            _activeBook.value = book
        }
        startPlayback(book, chapterIndex.coerceAtLeast(0), offsetMs.coerceAtLeast(0))
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (!mp.isPlaying) return
        persistPosition()
        runCatching { mp.pause() }
        _isPlaying.value = false
        abandonFocus()
    }

    fun resume() {
        val book = _activeBook.value ?: return
        val mp = mediaPlayer
        if (mp != null && prepared && !mp.isPlaying) {
            applySpeed(mp)
            runCatching { mp.start() }
            _isPlaying.value = true
            requestFocus()
            startTicker()
        } else if (mp == null || !prepared) {
            // Cold resume from a killed player: replay from the saved spot.
            play(book, _chapterIndex.value, lastOffsetMs)
        }
    }

    fun togglePlay() {
        if (_isPlaying.value) pause() else resume()
    }

    /** Full stop AND unbind: the mini bar and lock surface drop the book. */
    fun stop() {
        persistPosition()
        releasePlayer()
        _isPlaying.value = false
        _activeBook.value = null
        _chapterTitle.value = null
    }

    fun stopIfPlaying(book: Book) {
        if (_activeBook.value?.id == book.id) stop()
    }

    fun stepChapter(delta: Int) {
        val book = _activeBook.value ?: return
        val target = (_chapterIndex.value + delta).coerceIn(0, book.audioChapters.size - 1)
        if (target != _chapterIndex.value) startPlayback(book, target, 0L)
    }

    /** Absolute-file seek (scrubber + ±15 s). The ticker re-resolves the chapter. */
    fun seekToMs(ms: Long) {
        val mp = mediaPlayer
        val target = if (_durationMs.value > 0) ms.coerceIn(0, _durationMs.value - 500) else ms
        _elapsedMs.value = target
        if (mp != null && prepared) {
            runCatching { mp.seekTo(target.toInt()) }
        } else {
            pendingSeekMs = target
        }
    }

    fun seekByMs(deltaMs: Long) {
        val base = if (mediaPlayer != null && prepared) _elapsedMs.value
        else lastOffsetMs + chapterStartMs(_activeBook.value ?: return, _chapterIndex.value)
        seekToMs(base + deltaMs)
    }

    /** Minutes-based sleep timer (used by the audio reader's toolbar menu). */
    private var sleepTimerJob: Job? = null

    fun setSleepTimerMs(ms: Long) {
        sleepTimerJob?.cancel()
        if (ms <= 0) { clearSleepTimer(); return }
        sleepTimerJob = scope.launch {
            delay(ms)
            pause()
            _activeBook.value?.let { onSavePosition(it.id, _chapterIndex.value, lastOffsetMs) }
        }
    }

    fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    fun setSpeed(value: Float) {
        _speed.value = value.coerceIn(0.5f, 2.0f)
        mediaPlayer?.let { applySpeed(it) }
    }

    fun shutdown() {
        sleepTimerJob?.cancel()
        releasePlayer()
    }

    // MARK: - Internals

    /** Last persisted within-chapter offset, for cold resumes. */
    private var lastOffsetMs = 0L

    private fun chapterStartMs(book: Book, index: Int): Long =
        book.audioChapters.getOrNull(index)?.startMs ?: 0L

    private fun originalFile(book: Book): File? =
        com.speechnotes.app.logic.AppFiles.bookDirectory(book.id).listFiles()
            ?.firstOrNull { it.name.startsWith("original.") }

    private fun applySpeed(mp: MediaPlayer) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(_speed.value) }
            .onFailure { LogStore.warn("AudioBookPlayer: speed ${_speed.value}x rejected: ${it.message}") }
    }

    private fun startPlayback(book: Book, index: Int, offsetMs: Long) {
        val target = offsetMs + chapterStartMs(book, index)
        _chapterIndex.value = index
        _chapterTitle.value = chapterLabel(book, index)
        lastOffsetMs = offsetMs
        onSavePosition(book.id, index, offsetMs)
        requestFocus()
        // The service must start from a user-visible interaction — play taps
        // land here, cold resumes from the lock screen re-enter via resume().
        SpeechPlaybackService.start(context)
        val mp = mediaPlayer
        if (mp != null && prepared) {
            runCatching { mp.seekTo(target.toInt()) }
            applySpeed(mp)
            runCatching { mp.start() }
            _isPlaying.value = true
            startTicker()
        } else {
            // First play (or still preparing): remember where to go; onPrepared
            // seeks + starts.
            pendingSeekMs = target
            pendingStart = true
            _elapsedMs.value = target
            ensurePlayer(book)
        }
    }

    private fun chapterLabel(book: Book, index: Int): String? =
        book.audioChapters.getOrNull(index)?.title?.takeIf { it.isNotBlank() }
            ?: "Chapter ${index + 1}".takeIf { book.audioChapters.isNotEmpty() }

    /**
     * One MediaPlayer per book, prepared ASYNC once. A prepare() per tap
     * re-scanned a multi-hour m4b on the main thread — the "toggle doesn't
     * respond" bug; the reader used to own this logic.
     */
    private fun ensurePlayer(book: Book) {
        if (mediaPlayer != null) return
        val file = originalFile(book) ?: run {
            LogStore.error("AudioBookPlayer: no original file for \"${book.title}\"")
            return
        }
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { p ->
                prepared = true
                _durationMs.value = p.duration.toLong()
                pendingSeekMs?.let { runCatching { p.seekTo(it.toInt()) }; pendingSeekMs = null }
                if (pendingStart) {
                    pendingStart = false
                    applySpeed(p)
                    runCatching { p.start() }
                    _isPlaying.value = true
                    startTicker()
                }
            }
            mp.setOnCompletionListener {
                // Natural chapter end: advance, or finish the book.
                val bookNow = _activeBook.value
                val next = _chapterIndex.value + 1
                if (bookNow != null && next < bookNow.audioChapters.size) {
                    startPlayback(bookNow, next, 0L)
                } else {
                    _isPlaying.value = false
                    _elapsedMs.value = _durationMs.value
                    bookNow?.let { onSavePosition(it.id, _chapterIndex.value, 0) }
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                LogStore.error("AudioBookPlayer: MediaPlayer error what=$what extra=$extra")
                prepared = false
                _isPlaying.value = false
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        }.onFailure {
            LogStore.error("AudioBookPlayer: setup failed — ${it.message}")
            runCatching { mp.release() }
            mediaPlayer = null
        }
    }

    /**
     * The 500 ms ticker: publishes the playhead, re-resolves the chapter on
     * absolute seeks, and persists the position every ~10 s (plus every
     * pause) so a process kill loses at most a few seconds.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var persistAccumulator = 0
            while (_isPlaying.value) {
                val mp = mediaPlayer
                if (mp != null && prepared) {
                    runCatching {
                        val pos = mp.currentPosition.toLong()
                        _elapsedMs.value = pos
                        val book = _activeBook.value
                        if (book != null) {
                            val current = book.audioChapters.indexOfLast { pos >= it.startMs }
                            if (current >= 0 && current != _chapterIndex.value) {
                                _chapterIndex.value = current
                                _chapterTitle.value = chapterLabel(book, current)
                                lastOffsetMs = 0
                                onSavePosition(book.id, current, 0)
                                persistAccumulator = 0
                            } else {
                                persistAccumulator++
                                if (persistAccumulator >= 20) { // ~10 s
                                    persistAccumulator = 0
                                    persistPosition()
                                }
                            }
                        }
                    }
                }
                delay(500)
            }
        }
    }

    private fun persistPosition() {
        val book = _activeBook.value ?: return
        val mp = mediaPlayer
        val offset = if (mp != null && prepared) {
            runCatching { mp.currentPosition.toLong() - chapterStartMs(book, _chapterIndex.value) }
                .getOrDefault(lastOffsetMs)
        } else lastOffsetMs
        lastOffsetMs = offset.coerceAtLeast(0)
        onSavePosition(book.id, _chapterIndex.value, lastOffsetMs)
    }

    private fun releasePlayer() {
        ticker?.cancel()
        ticker = null
        mediaPlayer?.let { mp ->
            runCatching { if (mp.isPlaying) persistPosition() }
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        mediaPlayer = null
        prepared = false
        pendingStart = false
        pendingSeekMs = null
        abandonFocus()
    }
}
