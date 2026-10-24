package com.speechnotes.app.data

import com.speechnotes.app.speech.AudioBookPlayer
import com.speechnotes.app.speech.SpeechPlayer
import com.speechnotes.app.speech.SpeechState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Folds wall-clock time into [StatsStore] rows: every fold interval the
 * elapsed slice is recorded and the clock resets, so a killed app loses at
 * most one slice. Ported from StatsCenter.swift (speechnotes-ios) — one
 * recorder per surface, listening derived from the two players' published
 * state with zero hooks inside the playback engines.
 *
 * Listening attribution: SpeechPlayer speaks BOTH notes and read-aloud
 * books; the book id wins when present, else the note id. AudioBookPlayer
 * plays audiobook files.
 */
class StatsCenter(
    private val scope: CoroutineScope,
    private val player: SpeechPlayer,
    private val audioBooks: AudioBookPlayer,
) {
    /** Cap per fold: bounds the jump when the process is frozen between folds. */
    private val ttsCapMs = 120_000L
    private val audiobookCapMs = 300_000L

    private var ttsSubject: String? = null
    private var ttsStartedAt: Long = 0L
    private var ttsJob: Job? = null

    private var audioSubject: String? = null
    private var audioStartedAt: Long = 0L
    private var audioJob: Job? = null

    /** Called once after both players exist; drives the slots off the flows. */
    fun attach() {
        scope.launch {
            kotlinx.coroutines.flow.combine(
                player.state, player.nowPlayingNoteId, player.nowPlayingBookId,
            ) { state, noteId, bookId -> Triple(state, noteId, bookId) }
                .collect { (state, noteId, bookId) ->
                    val subject = if (state == SpeechState.SPEAKING) (bookId ?: noteId) else null
                    syncTts(subject)
                }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(
                audioBooks.isPlaying, audioBooks.activeBook,
            ) { playing, book -> if (playing) book?.id else null }
                .collect { subject -> syncAudiobook(subject) }
        }
    }

    private fun syncTts(subject: String?) {
        if (subject == ttsSubject) return
        ttsJob?.cancel(); ttsJob = null
        flushTts(ttsCapMs)
        ttsSubject = subject
        ttsStartedAt = if (subject != null) System.currentTimeMillis() else 0L
        if (subject != null) {
            ttsJob = scope.launch {
                while (true) {
                    delay(15_000)
                    flushTts(ttsCapMs)
                }
            }
        }
    }

    private fun syncAudiobook(subject: String?) {
        if (subject == audioSubject) return
        audioJob?.cancel(); audioJob = null
        flushAudio()
        audioSubject = subject
        audioStartedAt = if (subject != null) System.currentTimeMillis() else 0L
        if (subject != null) {
            audioJob = scope.launch {
                while (true) {
                    delay(15_000)
                    flushAudio()
                }
            }
        }
    }

    private fun flushTts(capMs: Long) {
        val subject = ttsSubject ?: return
        if (ttsStartedAt <= 0) return
        val elapsed = System.currentTimeMillis() - ttsStartedAt
        ttsStartedAt = System.currentTimeMillis()
        if (elapsed < 1_000) return
        val seconds = (elapsed.coerceAtMost(capMs)) / 1000.0
        scope.launch { StatsStore.record(subject, StatsStore.Kind.LISTENING, seconds) }
    }

    private fun flushAudio() {
        val subject = audioSubject ?: return
        if (audioStartedAt <= 0) return
        val elapsed = System.currentTimeMillis() - audioStartedAt
        audioStartedAt = System.currentTimeMillis()
        if (elapsed < 1_000) return
        val seconds = (elapsed.coerceAtMost(audiobookCapMs)) / 1000.0
        scope.launch { StatsStore.record(subject, StatsStore.Kind.LISTENING, seconds) }
    }
}

/**
 * Reading-time recorder for the visually-read surfaces (epub/PDF readers).
 * begin() is idempotent per subject; a subject switch ends the old run
 * first. Ported from StatsRecorder.swift's reading half.
 */
class ReadingRecorder(private val scope: CoroutineScope) {
    private var subject: String? = null
    private var startedAt: Long = 0L
    private var job: Job? = null

    fun begin(subjectId: String) {
        if (subjectId == subject && job != null) return
        end()
        subject = subjectId
        startedAt = System.currentTimeMillis()
        job = scope.launch {
            while (true) {
                delay(30_000)
                flush(120_000L)
            }
        }
    }

    fun end() {
        flush(3_600_000L)
        job?.cancel()
        job = null
        subject = null
        startedAt = 0L
    }

    private fun flush(capMs: Long) {
        val current = subject ?: return
        if (startedAt <= 0) return
        val elapsed = System.currentTimeMillis() - startedAt
        startedAt = System.currentTimeMillis()
        if (elapsed < 1_000) return
        val seconds = elapsed.coerceAtMost(capMs) / 1000.0
        scope.launch { StatsStore.record(current, StatsStore.Kind.READING, seconds) }
    }
}
