package com.speechnotes.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.speech.SpeechState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Media playback foreground service: the lock-screen / notification surface
 * for every playback type (port of NowPlayingCenter.swift).
 *
 * Two jobs, same as on iOS:
 *  1. Remote commands (play / pause / toggle / stop / chapter skip) are
 *     routed to the active player — an audiobook consumes them while a book
 *     is loaded, everything else falls through to SpeechPlayer (the same
 *     router-with-fallthrough pattern NowPlayingCenter.audioBookHandler
 *     uses).
 *  2. Publishing now-playing metadata keeps the foreground-service contract
 *     honest, which is what keeps the process — and the audio — alive while
 *     the app is backgrounded.
 *
 * The service owns NO playback: SpeechPlayer and AudioBookPlayer keep their
 * engines; this class only mirrors their state out and routes commands in.
 */
class SpeechPlaybackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 41

        /** Started from user-visible play actions only (foreground-start rules). */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, SpeechPlaybackService::class.java),
                )
            }
        }
    }

    private lateinit var session: MediaSessionCompat
    private var sessionReleased = false
    private var coverForBookId: String? = null
    private var coverBitmap: Bitmap? = null

    private val audioBooks get() = PlaybackRegistry.audioBooks
    private val speech get() = PlaybackRegistry.speechPlayer

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService<NotificationManager>()
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Now playing"
                    setShowBadge(false)
                },
            )
        }

        session = MediaSessionCompat(this, "Speechnotes").apply {
            setCallback(routerCallback)
            isActive = true
        }
        // startForeground is mandatory within seconds of startForegroundService.
        showNotification(
            title = audioBooks?.activeBook?.value?.title
                ?: speech?.nowPlayingTitle?.value ?: "Speechnotes",
            subtitle = audioBooks?.chapterTitle?.value ?: "Starting playback…",
            playing = false,
            cover = null,
            withSkips = false,
        )
        observePlayers()
    }

    /** The router: audiobook first, TTS fall-through — NowPlayingCenter parity. */
    private val bookIsActive: Boolean
        get() = audioBooks?.activeBook?.value != null

    private val routerCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = route(Command.Play)
        override fun onPause() = route(Command.Pause)
        override fun onSkipToNext() = route(Command.Next)
        override fun onSkipToPrevious() = route(Command.Previous)
        override fun onStop() = route(Command.Stop)
        override fun onSeekTo(pos: Long) {
            if (bookIsActive) audioBooks?.seekToMs(pos)
        }
    }

    private enum class Command { Toggle, Play, Pause, Next, Previous, Stop }

    private fun route(command: Command) {
        if (bookIsActive) {
            val books = audioBooks ?: return
            when (command) {
                Command.Toggle -> books.togglePlay()
                Command.Play -> books.resume()
                Command.Pause -> books.pause()
                Command.Stop -> books.stop()
                Command.Next -> books.stepChapter(1)
                Command.Previous -> books.stepChapter(-1)
            }
            return
        }
        val player = speech ?: return
        when (command) {
            Command.Toggle -> player.remoteToggle()
            Command.Play -> player.remoteToggle()
            Command.Pause -> player.pause()
            Command.Stop -> player.stop()
            Command.Next -> player.remoteChapterSkip(1)
            Command.Previous -> player.remoteChapterSkip(-1)
        }
    }

    /**
     * Mirrors both players into the session + notification. One collector
     * per flow; the publish itself is idempotent and cheap (a metadata map +
     * a PlaybackStateCompat). When NOTHING is active the service tears
     * itself down — no ghost notification.
     */
    private fun observePlayers() {
        val speechPlayer = speech
        if (speechPlayer != null) {
            speechPlayer.state.onEach { publish() }.launchIn(serviceScope)
            speechPlayer.nowPlayingTitle.onEach { publish() }.launchIn(serviceScope)
            speechPlayer.progress.onEach { publish() }.launchIn(serviceScope)
            speechPlayer.rateMultiplier.onEach { publish() }.launchIn(serviceScope)
            speechPlayer.nowPlayingBookId.onEach { publish() }.launchIn(serviceScope)
        }
        val books = audioBooks
        if (books != null) {
            books.activeBook.onEach { publish() }.launchIn(serviceScope)
            books.isPlaying.onEach { publish() }.launchIn(serviceScope)
            books.elapsedMs.onEach { publish() }.launchIn(serviceScope)
            books.durationMs.onEach { publish() }.launchIn(serviceScope)
        }
    }

    private fun publish() {
        if (sessionReleased) return
        val book = audioBooks?.activeBook?.value
        val speechActive = speech?.state?.value?.isActive == true

        if (book == null && !speechActive) {
            // Nothing playing anywhere (TTS finished, no audiobook loaded):
            // drop the surface and stop. A PAUSED session counts as active —
            // the user expects the controls to stay.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (book != null) {
            val books = audioBooks ?: return
            val playing = books.isPlaying.value
            val elapsed = books.elapsedMs.value
            val duration = books.durationMs.value
            sessionSetState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                elapsed,
                if (playing) books.speed.value.toFloat() else 0f,
            )
            val cover = coverFor(book.id)
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, book.title)
                    .putString(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        books.chapterTitle.value ?: book.author ?: "Audiobook",
                    )
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Speechnotes")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover)
                    .build(),
            )
            showNotification(
                title = book.title,
                subtitle = books.chapterTitle.value ?: book.author ?: "Audiobook",
                playing = playing,
                cover = cover,
                withSkips = book.audioChapters.isNotEmpty(),
            )
        } else {
            val player = speech ?: return
            val state = player.state.value
            val playing = state == SpeechState.SPEAKING
            val title = player.nowPlayingTitle.value ?: "Speaking"
            val total = player.estimatedTotalSeconds
            sessionSetState(
                when (state) {
                    SpeechState.SPEAKING -> PlaybackStateCompat.STATE_PLAYING
                    SpeechState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
                    SpeechState.GENERATING -> PlaybackStateCompat.STATE_BUFFERING
                    SpeechState.IDLE -> PlaybackStateCompat.STATE_STOPPED
                },
                (player.elapsedSeconds * 1000).toLong(),
                if (playing) player.rateMultiplier.value.toFloat() else 0f,
            )
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, player.currentVoiceDescription)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Speechnotes")
                    .putLong(
                        MediaMetadataCompat.METADATA_KEY_DURATION,
                        (total * 1000).toLong().coerceAtLeast(0L),
                    )
                    .build(),
            )
            showNotification(
                title = title,
                subtitle = player.currentVoiceDescription,
                playing = playing,
                cover = null,
                withSkips = player.nowPlayingBookId.value != null,
            )
        }
    }

    private fun sessionSetState(state: Int, positionMs: Long, speed: Float) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(state, positionMs, speed)
                .build(),
        )
    }

    private fun showNotification(
        title: String,
        subtitle: String,
        playing: Boolean,
        cover: Bitmap?,
        withSkips: Boolean,
    ) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent().setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_previous, "Previous",
                commandPendingIntent(Command.Previous, 1),
            )
            .addAction(toggleIcon, if (playing) "Pause" else "Play", commandPendingIntent(Command.Toggle, 2))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", commandPendingIntent(Command.Stop, 3))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        cover?.let { builder.setLargeIcon(it) }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun commandPendingIntent(command: Command, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, SpeechPlaybackService::class.java).setAction(command.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun coverFor(bookId: String): Bitmap? {
        if (coverForBookId != bookId) {
            coverBitmap = runCatching {
                BitmapFactory.decodeFile(AppFiles.bookCoverFile(bookId).absolutePath)
            }.getOrNull()
            coverForBookId = bookId
        }
        return coverBitmap
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Action intents come from the notification buttons.
        when (intent?.action) {
            Command.Toggle.name -> route(Command.Toggle)
            Command.Play.name -> route(Command.Play)
            Command.Pause.name -> route(Command.Pause)
            Command.Stop.name -> route(Command.Stop)
            Command.Next.name -> route(Command.Next)
            Command.Previous.name -> route(Command.Previous)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionReleased = true
        serviceScope.cancel()
        runCatching { session.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
