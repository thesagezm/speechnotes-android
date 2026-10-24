package com.speechnotes.app.data

import com.speechnotes.app.logic.AppFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** One exported audio file, with what Storage shows about it. */
data class ExportedAudio(
    val url: File,
    val name: String,
    val bytes: Long,
    val createdAt: Long,
    /** Seconds; computed lazily on first play to keep refreshes cheap. */
    val durationSeconds: Double? = null,
)

/**
 * Exported-audio listing — port of ExportsStore.swift. Reads the exports
 * directory; the player sees it through [com.speechnotes.app.speech.WavPlayer].
 */
class ExportsStore {

    private val _exports = MutableStateFlow<List<ExportedAudio>>(emptyList())
    val exports: StateFlow<List<ExportedAudio>> = _exports.asStateFlow()

    val totalBytes: Long
        get() = _exports.value.sumOf { it.bytes }

    /** Re-reads the exports directory (called on appear + pull-to-refresh). */
    fun refresh() {
        val dir = AppFiles.exportsDir
        val list = dir.listFiles { f -> f.isFile && f.extension.lowercase() == "wav" }
            ?.map { file ->
                ExportedAudio(
                    url = file,
                    name = file.nameWithoutExtension,
                    bytes = file.length(),
                    createdAt = file.lastModified(),
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
        _exports.value = list
    }

    fun delete(item: ExportedAudio) {
        runCatching { item.url.delete() }
        refresh()
    }

    fun deleteAll(): Long {
        val freed = totalBytes
        AppFiles.exportsDir.listFiles()?.forEach { f ->
            if (f.isFile && f.extension.lowercase() == "wav") runCatching { f.delete() }
        }
        refresh()
        return freed
    }

    companion object {
        /** Directory size helper (StorageSettings parity). */
        fun directorySize(dir: File): Long =
            if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        /**
         * Partial downloads / caches are "temporary": a half-written .part
         * file, a .tmp write, and the .jex archive are all safe to drop.
         * Exported WAVs and book files are NOT — user content.
         */
        fun clearTemporaryFiles(): Long {
            var freed = 0L
            AppFiles.exportsDir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val ext = f.extension.lowercase()
                val isTemp = ext == "part" || ext == "tmp" || f.name.endsWith(".jex") || f.name.endsWith(".pdf") ||
                    f.name.endsWith(".md") || f.name.endsWith(".txt")
                if (isTemp) {
                    freed += f.length()
                    runCatching { f.delete() }
                }
            }
            return freed
        }
    }
}

/**
 * Plays one exported WAV with full transport (scrub, ±15 s, 0.5–2×) and a
 * published playhead the Storage row and the global mini-player share —
 * WavPlayer.swift parity. ONE instance, owned by the ViewModel, so the
 * lock screen, Storage and the mini-bar see the same playhead.
 */
class WavPlayer(private val context: android.content.Context) {

    private val _playingURL = MutableStateFlow<File?>(null)
    val playingURL: StateFlow<File?> = _playingURL.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _progress = MutableStateFlow<Double?>(null)
    val progress: StateFlow<Double?> = _progress.asStateFlow()

    private val _currentTime = MutableStateFlow(0.0)
    val currentTime: StateFlow<Double> = _currentTime.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    private val _rate = MutableStateFlow(1.0)
    val rate: StateFlow<Double> = _rate.asStateFlow()

    /** Set by Storage while its own transport is on screen (avoids stacking). */
    @Volatile
    var miniPlayerSuppressed = false

    val showMiniPlayer: Boolean
        get() = _playingURL.value != null && !miniPlayerSuppressed

    private var player: android.media.MediaPlayer? = null
    private var ticker: Job? = null

    /** duration cache: name → seconds. */
    private val durationCache = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun play(file: File) {
        if (_playingURL.value == file && player != null) {
            togglePlay()
            return
        }
        stopInternal()
        val mp = android.media.MediaPlayer()
        runCatching {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { prepared ->
                _duration.value = prepared.duration / 1000.0
                durationCache[file.name] = _duration.value
                applyRate(prepared)
                prepared.start()
                _isPaused.value = false
                startTicker()
                com.speechnotes.app.playback.SpeechPlaybackService.start(context)
            }
            mp.setOnCompletionListener {
                _isPaused.value = false
                _progress.value = null
                _currentTime.value = 0.0
                stopInternal()
            }
            mp.prepareAsync()
            player = mp
            _playingURL.value = file
        }.onFailure {
            runCatching { mp.release() }
            player = null
            _playingURL.value = null
        }
    }

    fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) {
            runCatching { mp.pause() }
            _isPaused.value = true
        } else {
            applyRate(mp)
            runCatching { mp.start() }
            _isPaused.value = false
            startTicker()
        }
    }

    fun pause() {
        val mp = player ?: return
        runCatching { mp.pause() }
        _isPaused.value = true
    }

    fun stop() {
        stopInternal()
        _playingURL.value = null
    }

    fun seek(toFraction: Double) {
        val mp = player ?: return
        val target = (toFraction * _duration.value * 1000).toInt().coerceIn(0, (mp.duration).coerceAtLeast(0))
        runCatching { mp.seekTo(target) }
        _currentTime.value = target / 1000.0
        _progress.value = if (_duration.value > 0) _currentTime.value / _duration.value else null
    }

    fun skip(bySeconds: Double) {
        seek((_currentTime.value + bySeconds) / (_duration.value.takeIf { it > 0 } ?: 1.0))
    }

    fun setRate(value: Double) {
        _rate.value = value.coerceIn(0.5, 2.0)
        player?.let { applyRate(it) }
    }

    private fun applyRate(mp: android.media.MediaPlayer) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(_rate.value.toFloat()) }
    }

    private fun stopInternal() {
        ticker?.cancel()
        ticker = null
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        _isPaused.value = false
        _progress.value = null
        _currentTime.value = 0.0
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = CoroutineScope(Dispatchers.Main).launch {
            while (player?.isPlaying == true) {
                val mp = player ?: break
                runCatching {
                    _currentTime.value = mp.currentPosition / 1000.0
                    _progress.value = if (_duration.value > 0) _currentTime.value / _duration.value else null
                }
                delay(200)
            }
        }
    }

    fun shutdown() {
        stopInternal()
        _playingURL.value = null
    }
}
