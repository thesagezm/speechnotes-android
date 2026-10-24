package com.speechnotes.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.speechnotes.app.ui.SpeechnotesApp

/**
 * Single-activity host. Handles the two inbound entry points the iOS app has:
 * `speechnotes://import?text=…` links and opening a text/PDF/EPUB/audio file
 * from another app. The pending intake is exposed through [intakeFlow] so the
 * composition can consume it once a ViewModel exists.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            SpeechnotesApp(intakeFlow = intakeEvents)
        }
        handleIntent(intent)
    }

    /** The playback notification needs POST_NOTIFICATIONS granted on 33+. */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001,
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Scene-phase parity: leaving the app persists the playback bookmark so
     * reopening resumes mid-note (the old behavior lost the position whenever
     * the system killed the process between the last pause and the next
     * bookmark save).
     */
    override fun onPause() {
        super.onPause()
        runCatching { playbackViewModel?.player?.onBackground() }
    }

    override fun onResume() {
        super.onResume()
        // The Readest zombie-listener rule: a cached process can lose its
        // listening socket, so every return to foreground probes the BookDrop
        // port with a loopback connect and rebuilds it if dead.
        runCatching { com.speechnotes.app.data.BookDropRegistry.receiver?.probeAndHeal() }
    }

    /** The ViewModel hosting the active player (set once the composition runs). */
    private var playbackViewModel: com.speechnotes.app.data.SpeechnotesViewModel? = null

    fun attachPlayer(vm: com.speechnotes.app.data.SpeechnotesViewModel) {
        playbackViewModel = vm
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val vm = intakeEvents
        when (intent?.action) {
            android.content.Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme?.lowercase() == "speechnotes") {
                    val text = data.getQueryParameter("text")
                    if (!text.isNullOrBlank()) vm.value = Intake.ImportText(text)
                } else {
                    vm.value = Intake.OpenUri(data)
                }
            }
            android.content.Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
                when {
                    !text.isNullOrBlank() -> vm.value = Intake.ImportText(text)
                    stream != null -> vm.value = Intake.OpenUri(stream)
                }
            }
        }
    }

    /** What an inbound intent asked the app to do. */
    sealed interface Intake {
        data class ImportText(val text: String) : Intake
        data class OpenUri(val uri: Uri) : Intake
    }

    companion object {
        /** The most recent inbound intent, consumed by the UI. */
        val intakeEvents = kotlinx.coroutines.flow.MutableStateFlow<Intake?>(null)

        /** Clears the intake so a rotation doesn't re-import it. */
        fun consumeIntake() {
            intakeEvents.value = null
        }
    }
}
