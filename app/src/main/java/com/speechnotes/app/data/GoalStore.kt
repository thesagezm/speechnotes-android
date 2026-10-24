package com.speechnotes.app.data

import com.speechnotes.app.model.ReadingGoal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Persists reading goals as one small JSON file (ReadingGoalsView.swift's
 * GoalStore parity — a handful of goals never justify a database). Thread
 * safety: one mutex around the list; every caller path is short.
 */
class GoalStore(private val baseDir: File) {

    private val mutex = Mutex()
    private var goals: MutableList<ReadingGoal> = mutableListOf()
    private lateinit var file: File

    fun init() {
        file = File(baseDir, "reading-goals.json")
        goals = runCatching {
            if (file.isFile && file.length() > 0) {
                Persistence.json.decodeFromString(
                    ListSerializer(ReadingGoal.serializer()), file.readText(),
                ).toMutableList()
            } else mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    suspend fun all(): List<ReadingGoal> = mutex.withLock { goals.sortedByDescending { it.createdAt } }

    suspend fun add(book: com.speechnotes.app.model.Book, start: Long, deadline: Long) = mutex.withLock {
        goals.add(ReadingGoal.create(book, start, deadline))
        persistLocked()
    }

    suspend fun remove(goalId: String) = mutex.withLock {
        goals.removeAll { it.id == goalId }
        persistLocked()
    }

    /** Edits an existing goal's schedule in place; re-arms a completed goal. */
    suspend fun update(goalId: String, start: Long, deadline: Long) = mutex.withLock {
        val index = goals.indexOfFirst { it.id == goalId }
        if (index < 0) return@withLock
        val goal = goals[index]
        goals[index] = goal.copy(startDate = start, deadline = deadline, completedAt = null)
        persistLocked()
    }

    /** Marks goals complete once their book's live progress crosses the line. */
    suspend fun refreshCompletion(progressOf: (ReadingGoal) -> Double?) = mutex.withLock {
        var changed = false
        for (index in goals.indices) {
            val goal = goals[index]
            if (goal.completedAt != null) continue
            val fraction = progressOf(goal) ?: continue
            if (fraction >= 0.999) {
                goals[index] = goal.copy(completedAt = System.currentTimeMillis())
                changed = true
            }
        }
        if (changed) persistLocked()
    }

    private fun persistLocked() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Persistence.json.encodeToString(ListSerializer(ReadingGoal.serializer()), goals.toList()))
        }
    }
}
