package com.speechnotes.app.data

import com.speechnotes.app.logic.PomodoroConfig
import com.speechnotes.app.logic.PomodoroEvent
import com.speechnotes.app.logic.PomodoroMode
import com.speechnotes.app.logic.PomodoroPhase
import com.speechnotes.app.logic.PomodoroSession
import com.speechnotes.app.logic.nowEpochSeconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.util.Calendar

/**
 * One day of folded focus time — the Pomodoro section's own "today" line.
 * Deliberately NOT a StatsStore entry: reading/listening charts split by
 * kind, and timer minutes are neither. (PomodoroStore.swift parity.)
 */
@Serializable
data class PomodoroDayRecord(
    val dayKey: String,
    val focusSeconds: Double,
    val blocks: Int,
) {
    val focusMinutes: Int get() = (focusSeconds / 60).let { Math.round(it).toInt() }
}

/** The persisted Pomodoro state: last-used config, the running session (if
 *  any), and the day-folded focus history. One small JSON file — the
 *  reading-goals pattern. File name and field names match the iOS store. */
@Serializable
private data class PomodoroStateFile(
    var version: Int = 1,
    var config: PomodoroConfig,
    var session: PomodoroSession? = null,
    var history: List<PomodoroDayRecord> = emptyList(),
)

/** How the banner should be tinted — a UI-free key the overlay maps to a
 *  Compose color (prep=orange, focus=green, rest=blue, neutral=gray). */
enum class PomodoroTint { ORANGE, GREEN, BLUE, GRAY }

/**
 * The gentle top-of-app bubble the Pomodoro section raises (user ask:
 * "not a punch in my face — gentle, pleasing, useful"). Pure data — the
 * overlay in the UI renders it; actions map back through the kind.
 * (PomodoroBanner.swift parity; SF Symbols names became emoji.)
 */
data class PomodoroBanner(
    val id: Long,
    val kind: Kind,
    val icon: String,
    val tint: PomodoroTint,
    val title: String,
    val subtitle: String? = null,
    /** Optional primary action label ("Claim now"). The handler is looked
     *  up from the kind by the overlay — banners stay plain data. */
    val actionTitle: String? = null,
    /** null = stays until dismissed (still gentle: small, top, tappable-away). */
    val autoDismissAfterSec: Double? = null,
) {
    enum class Kind { COUNTDOWN, BLOCK_COMPLETE, RESUME_FOCUS, SESSION_COMPLETE, CLAIM_AVAILABLE, BREAK_CLAIMED }
}

/**
 * Owns the Pomodoro timer end-to-end: the persisted session, the day log,
 * and the banner. A singleton like StatsStore — the stats card, the config
 * sheet and the root banner overlay must all see the same state.
 *
 * Kill-safety: the session is a value whose every boundary is a wall-clock
 * instant, so it is persisted on each transition and reconciled from the
 * clock alone on relaunch.
 *
 * Concurrency: one mutex serializes every engine operation; mutations work
 * on a deep copy of the session and publish by swap, so UI collectors always
 * hold an immutable snapshot. File writes run off the main thread.
 */
object PomodoroStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _config = MutableStateFlow(PomodoroConfig())
    val config: StateFlow<PomodoroConfig> get() = _config

    private val _session = MutableStateFlow<PomodoroSession?>(null)
    val session: StateFlow<PomodoroSession?> get() = _session

    private val _history = MutableStateFlow<List<PomodoroDayRecord>>(emptyList())
    val history: StateFlow<List<PomodoroDayRecord>> get() = _history

    private val _banner = MutableStateFlow<PomodoroBanner?>(null)
    val banner: StateFlow<PomodoroBanner?> get() = _banner

    /** "1 minute left" fires once per phase — keyed by step index + phase. */
    private var countdownAnnouncedKey: String? = null
    /** Flowmodoro's claim hint fires once per focus stint — keyed by the
     *  stint's start instant. */
    private var claimAnnouncedForStint: Double? = null

    private var bannerDismissJob: kotlinx.coroutines.Job? = null
    private var nextBannerId: Long = 1
    private lateinit var file: File

    /** [baseDir] is the app's filesDir in production, a temp dir in tests. */
    fun init(baseDir: File) {
        file = File(baseDir, "pomodoro-state.json")
        val loaded = runCatching {
            if (file.isFile && file.length() > 0) {
                Persistence.json.decodeFromString(
                    PomodoroStateFile.serializer(), file.readText(),
                )
            } else null
        }.getOrNull() ?: PomodoroStateFile(config = PomodoroConfig())

        _config.value = loaded.config
        _history.value = loaded.history
        val restored = loaded.session ?: return
        // Reconcile: phases that elapsed while the app was dead fold into
        // history without toasts or haptics. But the resume moments still
        // deserve the gentle banner: reopening the app after a break ran
        // out IS the "ease back in" moment.
        scope.launch {
            mutex.withLock {
                val events = restored.advanceIfDue(nowEpochSeconds())
                val finished = restored.phase == PomodoroPhase.DONE
                foldLocked(events)
                _session.value = if (finished) null else restored
                if (events.isNotEmpty()) persistLocked()
                when {
                    finished -> showBannerLocked(sessionCompleteBannerLocked())
                    events.any { it.completedPhase == PomodoroPhase.REST } ->
                        showBannerLocked(resumeBanner(fromBreak = true))
                }
            }
        }
    }

    fun init(context: android.content.Context) = init(context.filesDir)

    /** A private deep copy: mutations happen on snapshots and publish by
     *  swap, so UI collectors always hold an immutable snapshot. */
    private fun PomodoroSession.snapshot(): PomodoroSession = copy(
        config = config.copy(
            classicFocusMinutes = config.classicFocusMinutes.toMutableList(),
            classicBreakMinutes = config.classicBreakMinutes.toMutableList(),
        ),
        steps = steps.map { it.copy() },
    )

    // MARK: Starting / stopping

    /** Starts (or replaces) the running session with the given config. The
     *  config is saved as the default so the next editor open pre-fills it. */
    fun start(newConfig: PomodoroConfig, now: Double = nowEpochSeconds()) {
        scope.launch {
            mutex.withLock {
                _session.value?.let { old ->
                    old.snapshot().stop(now)?.let { foldLocked(listOf(it)) }
                }
                _config.value = newConfig
                countdownAnnouncedKey = null
                claimAnnouncedForStint = null
                val fresh = PomodoroSession.start(newConfig, now, Calendar.getInstance())
                _session.value = fresh
                persistLocked()
                // Prep is short; the card picks the banner up on its next
                // render — but raise it here so the overlay shows it even
                // off the Stats tab.
                if (newConfig.prepSeconds > 0) {
                    showBannerLocked(
                        PomodoroBanner(
                            id = nextBannerIdLocked(),
                            kind = PomodoroBanner.Kind.COUNTDOWN,
                            icon = "👋",
                            tint = tintFor(PomodoroPhase.PREP),
                            title = "Warming up",
                            subtitle = "${Math.max(1, Math.round(newConfig.prepSeconds / 60.0).toInt())} min of prep, then focus begins",
                            autoDismissAfterSec = 8.0,
                        ),
                    )
                }
            }
        }
    }

    fun stop(now: Double = nowEpochSeconds()) {
        scope.launch {
            mutex.withLock {
                val s = _session.value?.snapshot() ?: return@withLock
                s.stop(now)?.let { foldLocked(listOf(it)) }
                _session.value = null
                dismissBannerLocked()
                persistLocked()
            }
        }
    }

    fun pause(now: Double = nowEpochSeconds()) {
        scope.launch {
            mutex.withLock {
                val s = _session.value?.snapshot() ?: return@withLock
                if (s.pause(now)) {
                    _session.value = s
                    persistLocked()
                }
            }
        }
    }

    fun resume(now: Double = nowEpochSeconds()) {
        scope.launch {
            mutex.withLock {
                val s = _session.value?.snapshot() ?: return@withLock
                if (s.resume(now)) {
                    _session.value = s
                    persistLocked()
                }
            }
        }
    }

    /** Skip the current phase (structured keeps its absolute window; classic
     *  slides; flow skips a rest). The engine's advance after the skip emits
     *  the partial-focus event, so history stays honest. */
    fun skipPhase(now: Double = nowEpochSeconds()) {
        scope.launch {
            mutex.withLock {
                val s = _session.value?.snapshot() ?: return@withLock
                if (!s.skipPhase(now)) return@withLock
                advanceLocked(s, now)
            }
        }
    }

    fun claimBreak(now: Double = nowEpochSeconds()) {
        scope.launch {
            mutex.withLock {
                val s = _session.value?.snapshot() ?: return@withLock
                val earned = s.flowEarnedBreak(now)
                if (!s.claimBreak(now)) return@withLock
                _session.value = s
                claimAnnouncedForStint = null
                persistLocked()
                showBannerLocked(
                    PomodoroBanner(
                        id = nextBannerIdLocked(),
                        kind = PomodoroBanner.Kind.BREAK_CLAIMED,
                        icon = "☕",
                        tint = tintFor(PomodoroPhase.REST),
                        title = PomodoroFormat.breakLength(earned),
                        subtitle = "To yourself — the pot starts refilling after",
                        autoDismissAfterSec = 8.0,
                    ),
                )
            }
        }
    }

    // MARK: Ticking

    /** The one-second heartbeat. Cheap when nothing is due (a guard and an
     *  instant compare); does real work only on transitions, where it folds
     *  history and raises the gentle banner. Driven by the root banner
     *  overlay so phase changes are seen from any tab, not just Stats. */
    fun tick(now: Double = nowEpochSeconds(), announce: Boolean = true) {
        scope.launch {
            mutex.withLock {
                val s = _session.value?.snapshot() ?: return@withLock
                if (!s.isRunning || s.isPaused) return@withLock
                advanceLocked(s, now, announce)
            }
        }
    }

    private suspend fun advanceLocked(s: PomodoroSession, now: Double, announce: Boolean = true) {
        val events = s.advanceIfDue(now)
        if (events.isEmpty()) {
            if (announce && s.isRunning) {
                announceCountdownIfNeeded(s, now)
                announceClaimIfNeeded(s, now)
            }
            return
        }

        val finished = s.phase == PomodoroPhase.DONE
        foldLocked(events)
        countdownAnnouncedKey = null
        _session.value = if (finished) null else s
        if (finished) {
            if (announce) showBannerLocked(sessionCompleteBannerLocked())
        } else if (announce) {
            announceTransitionLocked(events)
        }
        persistLocked()
    }

    // MARK: Banners

    /** The phase that just began gets the gentle headline — "break's over"
     *  after a rest, a quiet "ready to focus" after prep. */
    private suspend fun announceTransitionLocked(events: List<PomodoroEvent>) {
        val s = _session.value ?: return
        if (!s.isRunning) return
        when (s.phase) {
            PomodoroPhase.FOCUS -> {
                val fromBreak = events.any { it.completedPhase == PomodoroPhase.REST }
                showBannerLocked(resumeBanner(fromBreak))
            }
            PomodoroPhase.REST -> {
                val restSeconds = s.secondsRemaining(nowEpochSeconds()) ?: 0.0
                showBannerLocked(
                    PomodoroBanner(
                        id = nextBannerIdLocked(),
                        kind = PomodoroBanner.Kind.BLOCK_COMPLETE,
                        icon = "✅",
                        tint = tintFor(PomodoroPhase.REST),
                        title = "Nice work — block done",
                        subtitle = "${PomodoroFormat.breakLength(restSeconds)} starting now — stretch, sip, breathe",
                        autoDismissAfterSec = 8.0,
                    ),
                )
            }
            PomodoroPhase.PREP, PomodoroPhase.DONE -> Unit
        }
    }

    /** The "ease back in" card — shared by live transitions and the
     *  relaunch reconcile so the copy can't drift. */
    private fun resumeBanner(fromBreak: Boolean): PomodoroBanner = PomodoroBanner(
        id = nextBannerId,
        kind = PomodoroBanner.Kind.RESUME_FOCUS,
        icon = if (fromBreak) "📖" else "🧠",
        tint = tintFor(PomodoroPhase.FOCUS),
        title = if (fromBreak) "Break's over" else "Ready to focus",
        subtitle = if (fromBreak) {
            "Ease back into your reading — no rush"
        } else {
            "Your prep time is up — ease into the first block"
        },
        autoDismissAfterSec = if (fromBreak) null else 7.0,
    )

    /** The "last minutes" nudge — one per phase, at the 60-second mark. */
    private suspend fun announceCountdownIfNeeded(s: PomodoroSession, now: Double) {
        val end = s.phaseEnd ?: return // open-ended flow focus never counts down
        val remaining = end - now
        if (remaining <= 0 || remaining > 60) return
        val key = "${s.stepIndex}-${s.phase}-${end.toLong()}"
        if (countdownAnnouncedKey == key) return
        countdownAnnouncedKey = key
        val subtitle = when (s.phase) {
            PomodoroPhase.PREP -> "Focus begins in under a minute"
            PomodoroPhase.FOCUS -> "Finish the thought — a break is close"
            PomodoroPhase.REST -> "Ease back toward your reading when ready"
            PomodoroPhase.DONE -> return
        }
        showBannerLockedSuspend(
            PomodoroBanner(
                    id = nextBannerIdLocked(),
                    kind = PomodoroBanner.Kind.COUNTDOWN,
                    icon = "⏳",
                    tint = tintFor(s.phase),
                    title = "About a minute left",
                    subtitle = subtitle,
                    autoDismissAfterSec = 10.0,
            ),
        )
    }

    /** Flowmodoro: the pot crossed 3 banked break minutes — offer the claim
     *  once per stint, softly, with the claim one tap away. */
    private suspend fun announceClaimIfNeeded(s: PomodoroSession, now: Double) {
        if (s.config.mode != com.speechnotes.app.logic.PomodoroMode.FLOWMODORO) return
        if (s.phase != PomodoroPhase.FOCUS) return
        val stint = s.flowStintStart ?: return
        if (claimAnnouncedForStint == stint) return
        if (!s.flowCanClaim(now)) return
        claimAnnouncedForStint = stint
        showBannerLockedSuspend(
            PomodoroBanner(
                    id = nextBannerIdLocked(),
                    kind = PomodoroBanner.Kind.CLAIM_AVAILABLE,
                    icon = "☕",
                    tint = tintFor(PomodoroPhase.REST),
                    title = "${PomodoroFormat.breakLength(s.flowEarnedBreak(now))} banked",
                    subtitle = "Claim a break whenever you like — it keeps growing",
                    actionTitle = "Claim now",
            ),
        )
    }

    private fun sessionCompleteBannerLocked(): PomodoroBanner {
        val record = todayRecordLocked()
        val blocks = record.blocks
        val minutes = PomodoroFormat.minutes(record.focusSeconds)
        return PomodoroBanner(
            id = nextBannerIdLocked(),
            kind = PomodoroBanner.Kind.SESSION_COMPLETE,
            icon = "🎉",
            tint = tintFor(PomodoroPhase.FOCUS),
            title = "Pomodoro complete",
            subtitle = if (blocks > 0) {
                "$blocks block${if (blocks == 1) "" else "s"} · $minutes focused — well done today"
            } else "Well done today",
            autoDismissAfterSec = 12.0,
        )
    }

    private suspend fun showBannerLockedSuspend(banner: PomodoroBanner) = showBannerLocked(banner)

    private suspend fun showBannerLocked(banner: PomodoroBanner) {
        bannerDismissJob?.cancel()
        bannerDismissJob = null
        nextBannerId += 1
        _banner.value = banner
        val after = banner.autoDismissAfterSec ?: return
        bannerDismissJob = scope.launch {
            delay((after * 1000).toLong())
            mutex.withLock { _banner.value = null }
        }
    }

    fun dismissBanner() {
        scope.launch {
            mutex.withLock { dismissBannerLocked() }
        }
    }

    private fun dismissBannerLocked() {
        bannerDismissJob?.cancel()
        bannerDismissJob = null
        _banner.value = null
    }

    // MARK: History

    private fun foldLocked(events: List<PomodoroEvent>) {
        val focus = events.filter { it.completedPhase == PomodoroPhase.FOCUS && it.seconds > 0.5 }
        if (focus.isEmpty()) return
        val key = StatsStore.dayKey(LocalDate.now())
        val existing = _history.value.firstOrNull { it.dayKey == key }
        val record = existing ?: PomodoroDayRecord(dayKey = key, focusSeconds = 0.0, blocks = 0)
        var seconds = record.focusSeconds
        var blocks = record.blocks
        for (event in focus) {
            seconds += event.seconds
            blocks += 1
        }
        val updated = _history.value.filter { it.dayKey != key }
        _history.value = listOf(record.copy(focusSeconds = seconds, blocks = blocks)) + updated
        if (_history.value.size > 90) {
            _history.value = _history.value.take(90)
        }
    }

    fun todayRecord(now: Double = nowEpochSeconds()): PomodoroDayRecord {
        val key = StatsStore.dayKey(LocalDate.now())
        return _history.value.firstOrNull { it.dayKey == key }
            ?: PomodoroDayRecord(dayKey = key, focusSeconds = 0.0, blocks = 0)
    }

    private fun todayRecordLocked(): PomodoroDayRecord = todayRecord()

    // MARK: Persistence

    private suspend fun persistLocked() {
        if (!::file.isInitialized) return
        val state = PomodoroStateFile(
            config = _config.value,
            session = _session.value,
            history = _history.value,
        )
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(Persistence.json.encodeToString(PomodoroStateFile.serializer(), state))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            LogStore.error("PomodoroStore persist failed: ${e.message}")
        }
    }

    // MARK: - Small shared helpers

    private fun nextBannerIdLocked(): Long = nextBannerId

    /** Phase colors shared by the card, the glass and the banner — one
     *  source so "break is blue" everywhere. */
    fun tintFor(phase: PomodoroPhase): PomodoroTint = when (phase) {
        PomodoroPhase.PREP -> PomodoroTint.ORANGE
        PomodoroPhase.FOCUS -> PomodoroTint.GREEN
        PomodoroPhase.REST -> PomodoroTint.BLUE
        PomodoroPhase.DONE -> PomodoroTint.GRAY
    }
}

/** Human copy for durations that are often fractional ("36 s", "2.4 min"). */
object PomodoroFormat {
    fun breakLength(seconds: Double): String {
        if (seconds < 60) return "${Math.max(1, Math.round(seconds).toInt())} s of break"
        val minutes = seconds / 60
        if (minutes >= 10) return "${Math.round(minutes).toInt()} min of break"
        return "%.1f min of break".format(minutes)
    }

    fun minutes(seconds: Double): String {
        val mins = Math.round(seconds / 60).toInt()
        return if (mins >= 60) "${mins / 60} h ${mins % 60} m" else "$mins min"
    }

    /** Countdown text; rounds up so the timer never shows 0:00 early. */
    fun countdown(seconds: Double): String {
        val total = Math.max(0.0, seconds).let { Math.ceil(it).toLong() }
        return if (total >= 3600) {
            "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
        } else {
            "%02d:%02d".format(total / 60, total % 60)
        }
    }
}
