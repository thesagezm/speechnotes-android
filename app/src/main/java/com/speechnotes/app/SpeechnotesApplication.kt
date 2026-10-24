package com.speechnotes.app

import android.app.Application
import com.speechnotes.app.data.BookDropRegistry
import com.speechnotes.app.data.LocalSendReceiver
import com.speechnotes.app.logic.AppFiles

/**
 * App singleton — initializes the private-file layout before any store runs.
 */
class SpeechnotesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppFiles.init(filesDir, this)
        // The haptics pref was only read back when Settings was open, so the
        // toggle silently reset to on after every relaunch.
        com.speechnotes.app.ui.theme.Haptics.enabled =
            getSharedPreferences("speechnotes", MODE_PRIVATE).getBoolean("hapticsEnabled", true)
        // Debug builds: the EPUB reader's WebView is inspectable over
        // chrome://inspect — a blank reader is undebuggable from outside.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        // BookDrop receiver: create the singleton, wire the router into the
        // import pipelines, and start when the persisted toggle says so
        // (LocalSendReceiver.applyPersistedEnabledState parity).
        val receiver = LocalSendReceiver(this)
        receiver.router = { file -> BookDropRegistry.routeLanded(file) }
        BookDropRegistry.receiver = receiver
        receiver.applyPersistedEnabledState()
    }
}
