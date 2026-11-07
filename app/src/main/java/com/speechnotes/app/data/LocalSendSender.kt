package com.speechnotes.app.data

import android.content.Context
import com.speechnotes.app.logic.LocalSendDevice
import com.speechnotes.app.logic.LocalSendFileMeta
import com.speechnotes.app.logic.LocalSendPrepareResponse
import com.speechnotes.app.logic.BookDropCore
import com.speechnotes.app.logic.LocalSendPrepareUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One peer discovered on the LAN. */
data class Peer(
    val fingerprint: String,
    val alias: String,
    val deviceModel: String?,
    val deviceType: String?,
    val host: String,
    val port: Int,
) {
    val id: String get() = fingerprint

    val displayKind: String
        get() = when (deviceType) {
            "desktop" -> "Computer"
            "mobile" -> "Phone or tablet"
            "web" -> "Browser"
            "headless" -> "Device"
            "server" -> "Server"
            else -> "Device"
        }
}

/** One file to hand over. The file must stay readable for the whole transfer. */
data class SendItem(
    val file: File,
    val fileName: String,
    val fileType: String?,
)

/** How much of the file currently on the wire has been sent. */
data class SendTransferProgress(
    val fileName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val fraction: Double
        get() = if (bytesTotal > 0) minOf(bytesDone.toDouble() / bytesTotal, 1.0) else 0.0
}

/**
 * The sender half of the LocalSend protocol v2.2: find peers on the LAN,
 * ask permission, push files.
 *
 * Ported from LocalSendSender.swift (speechnotes-ios v1.8.1). The iOS
 * "stuck on Sending…" lesson is structural here: the body is handed to
 * OkHttp as a file-backed RequestBody with an explicit Content-Length —
 * the equivalent of iOS's upload(for:fromFile:) — never as a stream the
 * client cannot pump. The 120 s READ timeout is the inactivity bound: OkHttp
 * restarts the clock whenever bytes move, so a slow-but-moving transfer
 * completes while a frozen one fails in two minutes with a stall message.
 *
 * Divergence from iOS: no UIApplication background task (Android keeps the
 * process alive while the app is foregrounded, which is the only window the
 * send sheet runs in).
 */
class LocalSendSender(private val context: Context) {

    sealed class Phase {
        data object Idle : Phase()
        data object Scanning : Phase()
        data object Sending : Phase()
        data class Completed(val message: String) : Phase()
        data class Failed(val message: String) : Phase()
    }

    /** Batch progress: which file index is on the wire of how many. */
    data class Progress(val done: Int, val total: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Set when the destination answered 401: it demands an access code. */
    private val _needsAccessCode = MutableStateFlow(false)
    val needsAccessCode: StateFlow<Boolean> = _needsAccessCode.asStateFlow()

    private val _progress = MutableStateFlow(Progress(0, 0))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _transferProgress = MutableStateFlow<SendTransferProgress?>(null)
    val transferProgress: StateFlow<SendTransferProgress?> = _transferProgress.asStateFlow()

    /** A LAN protocol: with no local IPv4 there is nothing to find. */
    val canSend: Boolean
        get() = localInterfaces().isNotEmpty()

    private var scanJob: Job? = null
    private var sendJob: Job? = null

    @Volatile
    private var cancelled = false

    @Volatile
    private var currentCall: Call? = null

    /** Throttle for the per-write progress callbacks: steps, not every write. */
    @Volatile
    private var lastReportedUploadBytes = 0L

    // MARK: - HTTP clients

    /** Discovery: many short probes, so the dispatcher must not queue them. */
    private val probeClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher()
        dispatcher.maxRequests = 512
        dispatcher.maxRequestsPerHost = 512
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(800, TimeUnit.MILLISECONDS)
            .writeTimeout(800, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Transfers: the 120 s read timeout IS the stall detector. */
    private val uploadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Prepare-upload is a small JSON POST — 20 s like iOS. */
    private val prepareClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // MARK: - Identity (the receiver's fingerprint — a peer must not see us twice)

    private fun fingerprint(): String {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        prefs.getString(Prefs.BOOK_DROP_FINGERPRINT, null)?.let { return it }
        val fresh = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString()
        prefs.edit().putString(Prefs.BOOK_DROP_FINGERPRINT, fresh).apply()
        return fresh
    }

    private fun deviceDTO(): LocalSendDevice = LocalSendDevice(
        alias = android.os.Build.MODEL.ifBlank { "Android" },
        version = "2.2",
        deviceModel = android.os.Build.MODEL,
        deviceType = "mobile",
        fingerprint = fingerprint(),
        port = 0,
        protocolField = "http",
        download = false,
    )

    // MARK: - Discovery

    /**
     * Scans the local networks' /24 for peers. Cheap: 254 short POSTs per
     * subnet, capped at 48 in flight, so a normal LAN answers in about a
     * second.
     */
    fun scan() {
        val subnets = lanSubnets()
        if (subnets.isEmpty()) {
            finishFailed("No local network connection")
            return
        }
        scanJob?.cancel()
        _peers.value = emptyList()
        _lastError.value = null
        _phase.value = Phase.Scanning
        scanning = true
        scanJob = scope.launch {
            try {
                val found = probeSubnets(subnets)
                _peers.value = found
                _phase.value = Phase.Idle
            } finally {
                scanning = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_phase.value == Phase.Scanning) _phase.value = Phase.Idle
    }

    private suspend fun probeSubnets(subnets: List<String>): List<Peer> {
        val found = ArrayList<Peer>()
        for (subnet in subnets) {
            if (scanJob?.isCancelled == true) break
            found.addAll(probeSubnet(subnet))
        }
        return found
    }

    private suspend fun probeSubnet(subnet: String): List<Peer> = withContext(Dispatchers.IO) {
        val concurrency = 48
        val gate = Semaphore(concurrency)
        val hosts = (1..254).map { "$subnet.$it" }
        val workers = ArrayList<Job>()
        val results = java.util.Collections.synchronizedList(ArrayList<Peer?>())
        for (host in hosts) {
            workers += scope.launch {
                gate.acquire()
                try {
                    results.add(probe(host))
                } finally {
                    gate.release()
                }
            }
        }
        workers.forEach { it.join() }
        results.filterNotNull()
    }

    private fun probe(host: String): Peer? {
        for (port in PEER_PORTS) {
            if (scanCancelled()) return null
            val body = BookDropCore.json.encodeToString(deviceDTO())
                .toRequestBody("application/json".toMediaType())
            val request = try {
                Request.Builder()
                    .url("http://$host:$port/api/localsend/v2/register")
                    .post(body)
                    .build()
            } catch (e: IllegalArgumentException) {
                return null
            }
            try {
                probeClient.newCall(request).execute().use { response ->
                    val payload = response.body?.string() ?: return null
                    if (!response.isSuccessful) continue
                    val device = runCatching {
                        BookDropCore.json.decodeFromString(LocalSendDevice.serializer(), payload)
                    }.getOrNull() ?: continue
                    if (device.fingerprint == fingerprint()) continue
                    // The DTO's own port is where the peer actually serves:
                    // 53317 was only the door we knocked on.
                    return Peer(
                        fingerprint = device.fingerprint,
                        alias = device.alias,
                        deviceModel = device.deviceModel,
                        deviceType = device.deviceType,
                        host = host,
                        port = if (device.port > 0) device.port else port,
                    )
                }
            } catch (e: SocketTimeoutException) {
                // Silent host: no point knocking on the other doors of a
                // house that isn't there.
                return null
            } catch (e: IOException) {
                // Refused/closed port → the host is alive; try its next port.
                continue
            }
        }
        return null
    }

    @Volatile
    private var scanning = false

    private fun scanCancelled(): Boolean = !scanning || scanJob?.isCancelled == true

    // MARK: - Send

    /** Clears published state when a fresh send screen opens. */
    fun reset() {
        _phase.value = Phase.Idle
        _lastError.value = null
        _needsAccessCode.value = false
    }

    /**
     * Sends every file in one session: prepare-upload, then one POST
     * /upload per file, echoing the token prepare returned.
     */
    fun send(files: List<SendItem>, to: Peer, pin: String? = null) {
        if (files.isEmpty()) return
        scanJob?.cancel()
        cancelled = false
        _needsAccessCode.value = false
        _progress.value = Progress(0, files.size)
        _phase.value = Phase.Sending
        sendJob = scope.launch {
            try {
                sendFiles(files, to, pin?.trim()?.takeIf { it.isNotEmpty() })
            } catch (e: CancellationException) {
                _phase.value = Phase.Idle
            } finally {
                _transferProgress.value = null
            }
        }
    }

    fun cancelSend() {
        cancelled = true
        sendJob?.cancel()
        sendJob = null
        currentCall?.cancel()
        if (_phase.value == Phase.Sending) _phase.value = Phase.Idle
    }

    private suspend fun sendFiles(items: List<SendItem>, peer: Peer, pin: String?) {
        val metas = items.map { item ->
            val id = UUID.randomUUID().toString()
            Triple(id, item, LocalSendFileMeta(
                id = id,
                fileName = item.fileName,
                size = item.file.length(),
                fileType = item.fileType,
                sha256 = null,
                preview = null,
                metadata = null,
            ))
        }
        val prepareBody = LocalSendPrepareUpload(
            info = deviceDTO(),
            pin = pin,
            files = metas.associate { it.first to it.third },
        )
        val prepared = try {
            val payload = BookDropCore.json.encodeToString(prepareBody)
                .toRequestBody("application/json".toMediaType())
            val url = buildString {
                append("http://").append(peer.host).append(':').append(peer.port)
                append("/api/localsend/v2/prepare-upload")
                if (pin != null) append("?pin=").append(java.net.URLEncoder.encode(pin, "UTF-8"))
            }
            val request = Request.Builder().url(url).post(payload).build()
            val call = prepareClient.newCall(request)
            currentCall = call
            call.execute().use { response ->
                when {
                    response.isSuccessful -> BookDropCore.json.decodeFromString(
                        LocalSendPrepareResponse.serializer(),
                        response.body?.string() ?: throw IOException("empty prepare response"),
                    )
                    response.code == 401 -> {
                        _needsAccessCode.value = true
                        finishFailed("Access code needed or wrong for ${peer.alias}")
                        return
                    }
                    response.code == 403 -> {
                        finishFailed("${peer.alias} declined the transfer")
                        return
                    }
                    response.code == 409 -> {
                        finishFailed("${peer.alias} is busy with another transfer")
                        return
                    }
                    else -> {
                        finishFailed("${peer.alias} refused (HTTP ${response.code})")
                        return
                    }
                }
            }
        } catch (e: IOException) {
            finishFailed("Could not reach ${peer.alias}")
            return
        } finally {
            currentCall = null
        }

        for ((index, entry) in metas.withIndex()) {
            if (cancelled) {
                _phase.value = Phase.Idle
                return
            }
            val token = prepared.files[entry.first]
            if (token == null) {
                finishFailed("${peer.alias} gave no token for ${entry.second.fileName}")
                return
            }
            upload(entry.second, id = entry.first, token = token, sessionId = prepared.sessionId, to = peer, pin = pin)
            if (cancelled) {
                _phase.value = Phase.Idle
                return
            }
            _progress.value = Progress(index + 1, items.size)
        }
        _phase.value = Phase.Completed(
            if (items.size == 1) "Sent ${items[0].fileName} to ${peer.alias}"
            else "Sent ${items.size} files to ${peer.alias}",
        )
    }

    /**
     * One POST /upload, body = the file's bytes with an explicit
     * Content-Length — the "supported file path" iOS learned to ride after
     * its pre-opened stream sent nothing. 128 KB progress steps.
     */
    private suspend fun upload(item: SendItem, id: String, token: String, sessionId: String, to: Peer, pin: String?) {
        // Seed the bar with the file's name and size before the first
        // counting callback arrives.
        _transferProgress.value = SendTransferProgress(item.fileName, 0, item.file.length())
        lastReportedUploadBytes = 0

        val url = buildString {
            append("http://").append(to.host).append(':').append(to.port)
            append("/api/localsend/v2/upload")
            append("?sessionId=").append(java.net.URLEncoder.encode(sessionId, "UTF-8"))
            append("&fileId=").append(java.net.URLEncoder.encode(id, "UTF-8"))
            append("&token=").append(java.net.URLEncoder.encode(token, "UTF-8"))
            if (pin != null) append("&pin=").append(java.net.URLEncoder.encode(pin, "UTF-8"))
        }
        val fileBody: RequestBody = CountingRequestBody(item.file, "application/octet-stream".toMediaType()) { sent, _ ->
            noteUploadProgress(sent)
        }
        val request = Request.Builder().url(url).post(fileBody).build()
        try {
            val call = uploadClient.newCall(request)
            currentCall = call
            call.execute().use { response ->
                when {
                    response.isSuccessful -> Unit
                    response.code == 401 -> {
                        _needsAccessCode.value = true
                        finishFailed("Access code needed or wrong for ${to.alias}")
                    }
                    response.code == 403 || response.code == 409 ->
                        finishFailed("${to.alias} rejected ${item.fileName} (HTTP ${response.code})")
                    else -> finishFailed("${item.fileName} failed (HTTP ${response.code})")
                }
            }
        } catch (e: SocketTimeoutException) {
            finishFailed(
                "The transfer stalled — ${to.alias} stopped accepting data. " +
                    "Keep this screen open and try again.",
            )
        } catch (e: IOException) {
            if (!cancelled) finishFailed("${item.fileName} failed: ${e.message ?: "connection error"}")
        } finally {
            currentCall = null
            _transferProgress.value = null
        }
    }

    /** Counting callback from the request body — throttled to ~128 KB steps. */
    private fun noteUploadProgress(sent: Long) {
        val current = _transferProgress.value ?: return
        val total = current.bytesTotal
        if (sent != total && sent - lastReportedUploadBytes < (1L shl 17)) return
        lastReportedUploadBytes = sent
        _transferProgress.value = SendTransferProgress(current.fileName, sent, total)
    }

    private fun finishFailed(message: String) {
        _lastError.value = message
        _phase.value = Phase.Failed(message)
    }

    // MARK: - Network context

    /**
     * Every non-loopback, non-link-local IPv4 address on this device.
     * `InetAddress.address` bytes are SIGNED — they must be masked to 0…255
     * or the subnet string reads "-64.-88.-16" and every probe fails with
     * UnknownHostException.
     */
    private class IPv4Interface(val name: String, val octets: ByteArray) {
        val subnet: String
            get() = octets.take(3).joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    private fun localInterfaces(): List<IPv4Interface> {
        val out = ArrayList<IPv4Interface>()
        for (nice in NetworkInterface.getNetworkInterfaces() ?: return emptyList()) {
            for (address in nice.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    out.add(IPv4Interface(nice.name, address.address))
                }
            }
        }
        return out
    }

    /**
     * The subnets to scan, best first. Simplified from the iOS ranking:
     * Wi-Fi (wlan) and hotspot/tether interfaces rank above everything,
     * and cellular data (rmnet/ccmni) plus VPN tunnels are skipped — a
     * carrier /24 is never a peer set.
     */
    private fun lanSubnets(): List<String> {
        val ranked = localInterfaces()
            .filter { iface ->
                val name = iface.name
                !(name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("tun") || name.startsWith("utun"))
            }
            .sortedByDescending { iface ->
                when {
                    iface.name.startsWith("wlan") -> 100
                    iface.name.startsWith("eth") || iface.name.startsWith("ap") ||
                        iface.name.startsWith("swlan") || iface.name.startsWith("bridge") -> 80
                    else -> 50
                }
            }
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (iface in ranked) {
            if (seen.add(iface.subnet)) out.add(iface.subnet)
            if (out.size >= 3) break
        }
        return out
    }

    companion object {
        /** The LocalSend default range, in probe order. */
        val PEER_PORTS = (53317..53327).toList()
    }
}

/**
 * A request body that counts the bytes as OkHttp writes them to the wire.
 * The file is copied in 64 KB chunks — a single writeTo is one socket
 * write, and progress would jump 0→100%.
 */
private class CountingRequestBody(
    private val file: File,
    private val mediaType: okhttp3.MediaType,
    private val onProgress: (bytesSent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = mediaType
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: okio.BufferedSink) {
        val total = contentLength()
        var done = 0L
        file.source().buffer().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, CHUNK)
                if (read < 0L) break
                sink.write(buffer, read)
                done += read
                onProgress(done, total)
            }
        }
    }

    companion object {
        private const val CHUNK = 64L * 1024
    }
}
