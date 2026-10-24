package com.speechnotes.app.data

/**
 * In-memory log ring buffer. Ported from LogStore.swift (speechnotes-ios):
 * crash-persistent enough for debugging, shareable via the Logs screen.
 */
object LogStore {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(val timestamp: Long, val level: Level, val message: String)

    private val entries = ArrayDeque<Entry>(512)

    fun debug(message: String) = add(Level.DEBUG, message)
    fun info(message: String) = add(Level.INFO, message)
    fun warn(message: String) = add(Level.WARN, message)
    fun error(message: String) = add(Level.ERROR, message)

    private fun add(level: Level, message: String) {
        synchronized(entries) {
            entries.addLast(Entry(System.currentTimeMillis(), level, message))
            while (entries.size > 400) entries.removeFirst()
        }
        // Mirror to logcat so `adb logcat` shows engine + store activity.
        val tag = "Speechnotes"
        when (level) {
            Level.DEBUG -> android.util.Log.d(tag, message)
            Level.INFO -> android.util.Log.i(tag, message)
            Level.WARN -> android.util.Log.w(tag, message)
            Level.ERROR -> android.util.Log.e(tag, message)
        }
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun exportText(): String = snapshot().joinToString("\n") { entry ->
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(entry.timestamp))
        "[$ts] [${entry.level}] ${entry.message}"
    }
}
