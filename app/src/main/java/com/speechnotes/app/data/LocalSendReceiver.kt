package com.speechnotes.app.data

import android.content.Context
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.logic.BookDropCore
import com.speechnotes.app.logic.BookDropRecord
import com.speechnotes.app.logic.LocalSendDevice
import com.speechnotes.app.logic.LocalSendHTTPServer
import com.speechnotes.app.logic.LocalSendRequest
import com.speechnotes.app.logic.LocalSendResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * The BookDrop receiver: a LocalSend protocol v2.2 peer that ACCEPTS incoming
 * transfers (the LocalSend desktop app and Readest's "Nearby BookDrop" both
 * speak it). Receive-only — LocalSend senders find peers by a /24 unicast
 * scan hitting POST /register, so being up on the LAN is all that's needed.
 *
 * Layers: [LocalSendHTTPServer] (sockets) → this class (routing + identity +
 * lifecycle) → [BookDropCore] (protocol state machine, JVM-tested) → the
 * [router] closure, which lands files into the import pipelines.
 *
 * Ported from LocalSendReceiver.swift (speechnotes-ios). The Readest
 * zombie-listener lesson is wired from day one: every return to foreground
 * probes the listener with a loopback connect and rebuilds it if the OS
 * reclaimed the socket while the process was cached (stop FIRST, then start —
 * a start over a dead listener returns the dead one).
 */
class LocalSendReceiver(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val server = LocalSendHTTPServer()
    private val core = BookDropCore(
        device = LocalSendDevice(
            alias = android.os.Build.MODEL.ifBlank { "Android" },
            version = "2.2",
            deviceModel = android.os.Build.MODEL,
            deviceType = "mobile",
            fingerprint = fingerprint(),
            port = 53317,
            protocolField = "http",
            download = false,
        ),
        sessionsRoot = AppFiles.bookDropIncomingDir,
    )

    /** Routes a landed file into the import pipeline. Set by the app at startup. */
    var router: ((File) -> Unit)? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _history = MutableStateFlow<List<BookDropRecord>>(emptyList())
    val history: StateFlow<List<BookDropRecord>> = _history.asStateFlow()

    init {
        server.handler = { request -> handle(request) }
        server.bodySpillDir = AppFiles.bookDropIncomingDir
    }

    // MARK: - Identity (persisted)

    private fun prefs() = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    private fun fingerprint(): String {
        val prefs = prefs()
        val existing = prefs.getString(Prefs.BOOK_DROP_FINGERPRINT, null)
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString()
        prefs.edit().putString(Prefs.BOOK_DROP_FINGERPRINT, fresh).apply()
        return fresh
    }

    fun isEnabledInDefaults(): Boolean = prefs().getBoolean(Prefs.BOOK_DROP_ENABLED, false)

    fun applyPersistedEnabledState() {
        if (isEnabledInDefaults()) start()
    }

    var autoAccept: Boolean
        get() = core.autoAccept
        set(value) {
            core.autoAccept = value
            prefs().edit().putBoolean(Prefs.BOOK_DROP_AUTO_ACCEPT, value).apply()
        }

    // MARK: - Lifecycle

    fun start() {
        if (_isRunning.value) return
        _lastError.value = null
        core.autoAccept = prefs().getBoolean(Prefs.BOOK_DROP_AUTO_ACCEPT, true)
        val bound = server.start()
        if (bound < 0) {
            _isRunning.value = false
            _lastError.value = "Ports 53317–53327 are all in use"
            return
        }
        core.device = core.device.copy(port = bound)
        _isRunning.value = true
        _port.value = bound
    }

    fun stop() {
        server.stop()
        _isRunning.value = false
        _port.value = 0
    }

    /** The didBecomeActive rule: probe, and stop-then-start on a dead listener. */
    fun probeAndHeal() {
        if (!isEnabledInDefaults()) return
        scope.launch {
            val alive = server.verifyAlive()
            if (alive) {
                _isRunning.value = true
            } else {
                stop()
                if (isEnabledInDefaults()) start()
            }
        }
    }

    // MARK: - Request routing

    private fun handle(request: LocalSendRequest): LocalSendResponse {
        // Session expiry is lazy: swept on the next protocol call after the
        // 5-minute mark rather than by a timer task (the iOS version arms a
        // Task per session; the sweep is behaviorally identical and needs no
        // scheduler).
        core.sweepExpiredSessions()
        return when (request.path) {
            "/api/localsend/v2/register" -> core.registerOrInfo()
            "/api/localsend/v2/info" -> core.registerOrInfo()
            "/api/localsend/v2/prepare-upload" -> {
                val body = request.bodySource as? LocalSendRequest.Body.Memory
                    ?: return LocalSendResponse.badRequest()
                core.prepareUpload(String(body.data), request.remoteHost)
            }
            "/api/localsend/v2/upload" -> {
                val body = when (val source = request.bodySource) {
                    is LocalSendRequest.Body.Memory -> BookDropCore.UploadBody.Memory(source.data)
                    is LocalSendRequest.Body.File -> BookDropCore.UploadBody.File(source.file)
                }
                val response = core.upload(
                    sessionId = request.queryValue("sessionId"),
                    fileId = request.queryValue("fileId"),
                    token = request.queryValue("token"),
                    remoteHost = request.remoteHost,
                    body = body,
                )
                if (response.status == 200) routeLanded(request.queryValue("sessionId"))
                response
            }
            "/api/localsend/v2/cancel" -> core.cancel(request.queryValue("sessionId"))
            else -> LocalSendResponse.notFound()
        }
    }

    /**
     * All files landed → hand each to the router, then sweep the session dir
     * after a grace beat (rejected leftovers, failed imports).
     */
    private fun routeLanded(sessionId: String?) {
        if (sessionId == null) return
        val landed = core.landedFiles(sessionId)
        if (landed.isEmpty()) return
        val router = router ?: return
        scope.launch {
            val dir = landed.firstOrNull()?.file?.parentFile
            for (landedFile in landed) {
                router(landedFile.file)
            }
            // The router consumes what it handles; sweep the rest later.
            kotlinx.coroutines.delay(30_000)
            dir?.let { runCatching { it.deleteRecursively() } }
        }
    }

    /** Called by the router after a file reached its destination. */
    fun reportImport(name: String, size: Long, outcome: BookDropRecord.Outcome) {
        core.reportImport(name, size, outcome)
        _history.value = core.currentHistory()
    }

    fun refreshHistory() {
        _history.value = core.currentHistory()
    }
}
