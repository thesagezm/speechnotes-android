package com.speechnotes.app.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The reading/listening time store. Ported from StatsStore.swift
 * (speechnotes-ios): one JSON file, per-local-day/per-subject/per-kind rows
 * folded in place (a shelf of books over a year stays a few thousand tiny
 * rows, not a session log). One fold lands every ~30 s per surface, so the
 * whole-file atomic rewrite load is trivial; a killed app loses one fold.
 */
object StatsStore {

    /** What the tracked time was spent on: visually reading or listening. */
    enum class Kind { READING, LISTENING }

    @Serializable
    data class Entry(
        val day: String,        // "yyyy-MM-dd", LOCAL day
        val kind: String,       // "reading" | "listening"
        val subjectId: String,
        val seconds: Double,
    )

    @Serializable
    data class DayTotals(val reading: Double = 0.0, val listening: Double = 0.0) {
        val total: Double get() = reading + listening
    }

    private val mutex = Mutex()
    private var entries: MutableList<Entry> = mutableListOf()
    private lateinit var file: File
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** [baseDir] is the app's filesDir in production, a temp dir in tests. */
    fun init(baseDir: File) {
        file = File(baseDir, "stats.json")
        entries = runCatching {
            if (file.isFile && file.length() > 0) {
                Persistence.json.decodeFromString(
                    ListSerializer(Entry.serializer()), file.readText(),
                ).toMutableList()
            } else mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    fun init(context: Context) = init(File(context.filesDir, ""))

    fun dayKey(date: LocalDate): String = date.format(dayFormat)

    fun todayKey(): String = dayKey(LocalDate.now())

    /** Folds [seconds] into (today, kind, subjectId). No-op for junk values. */
    suspend fun record(subjectId: String, kind: Kind, seconds: Double) = mutex.withLock {
        if (seconds <= 0 || subjectId.isEmpty()) return@withLock
        val day = todayKey()
        val kindKey = kind.name.lowercase()
        val index = entries.indexOfFirst { it.day == day && it.kind == kindKey && it.subjectId == subjectId }
        if (index >= 0) entries[index] = entries[index].copy(seconds = entries[index].seconds + seconds)
        else entries.add(Entry(day, kindKey, subjectId, seconds))
        saveLocked()
    }

    /** Per-local-day totals over the last [daysBack] days (inclusive of today). */
    suspend fun dayTotals(daysBack: Int): Map<String, DayTotals> = mutex.withLock {
        val cutoff = LocalDate.now().minusDays((daysBack - 1).coerceAtLeast(0).toLong())
        val out = HashMap<String, DayTotals>()
        for (e in entries) {
            val day = runCatching { LocalDate.parse(e.day, dayFormat) }.getOrNull() ?: continue
            if (day.isBefore(cutoff)) continue
            val totals = out[e.day] ?: DayTotals()
            out[e.day] = if (e.kind == "listening") {
                totals.copy(listening = totals.listening + e.seconds)
            } else {
                totals.copy(reading = totals.reading + e.seconds)
            }
        }
        out
    }

    suspend fun totals(daysBack: Int, kind: Kind? = null): Double {
        return dayTotals(daysBack).values.sumOf { day ->
            when (kind) {
                Kind.READING -> day.reading
                Kind.LISTENING -> day.listening
                null -> day.total
            }
        }
    }

    /** Seconds per subject within the window — the per-book/per-note cards. */
    suspend fun subjectTotals(daysBack: Int, kind: Kind? = null): Map<String, Double> = mutex.withLock {
        val cutoff = LocalDate.now().minusDays((daysBack - 1).coerceAtLeast(0).toLong())
        val out = HashMap<String, Double>()
        for (e in entries) {
            if (kind != null && e.kind != kind.name.lowercase()) continue
            val day = runCatching { LocalDate.parse(e.day, dayFormat) }.getOrNull() ?: continue
            if (day.isBefore(cutoff)) continue
            out[e.subjectId] = (out[e.subjectId] ?: 0.0) + e.seconds
        }
        out
    }

    /**
     * Consecutive local days (ending today, or yesterday when today hasn't
     * earned its minute yet) with at least [minimumSeconds] of combined
     * time. Zero when neither day qualifies.
     */
    suspend fun streak(minimumSeconds: Double = 60.0): Int = mutex.withLock {
        val perDay = HashMap<String, Double>()
        for (e in entries) perDay[e.day] = (perDay[e.day] ?: 0.0) + e.seconds
        val today = LocalDate.now()
        fun counts(daysBack: Int): Boolean =
            (perDay[dayKey(today.minusDays(daysBack.toLong()))] ?: 0.0) >= minimumSeconds
        var cursor = if (counts(0)) 0 else if (counts(1)) 1 else return 0
        var streak = 0
        while (counts(cursor)) {
            streak++
            cursor++
        }
        streak
    }

    /** Distinct days with any recorded time — the "days read" counter. */
    suspend fun activeDays(): Int = mutex.withLock {
        entries.map { it.day }.toSet().size
    }

    private fun saveLocked() {
        entries.removeAll { it.seconds <= 0.001 }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Persistence.json.encodeToString(ListSerializer(Entry.serializer()), entries.toList()))
        }
    }
}
