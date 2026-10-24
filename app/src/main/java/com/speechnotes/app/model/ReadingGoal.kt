package com.speechnotes.app.model

import kotlinx.serialization.Serializable

/**
 * One reading goal: a book, a start date, and a finish-by deadline. Ported
 * from ReadingGoalsView.swift (speechnotes-ios, E1c). The progress is
 * never stored — it derives live from the book's own position fields
 * (chapter index + fraction), so a goal advances exactly when the book
 * does, in whichever reader the user last opened.
 *
 * `bookTitle` is a snapshot so a goal whose book was deleted still renders
 * (with an honest "book removed" state) instead of vanishing.
 */
@Serializable
data class ReadingGoal(
    val id: String,
    val bookId: String,
    val bookTitle: String,
    val createdAt: Long,
    val startDate: Long,   // epoch millis (calendar-day semantics)
    val deadline: Long,    // epoch millis
    /** Set once the derived progress crosses the finish line. */
    val completedAt: Long? = null,
) {
    companion object {
        fun create(book: Book, start: Long, deadline: Long): ReadingGoal = ReadingGoal(
            id = java.util.UUID.randomUUID().toString(),
            bookId = book.id,
            bookTitle = book.title,
            createdAt = System.currentTimeMillis(),
            startDate = start,
            deadline = deadline,
        )
    }
}

/**
 * Everything a goal card renders, computed from the book's live state.
 * Calendar-day based: a deadline is a date, not an instant.
 */
data class GoalProgress(
    val fraction: Double?,
    /** "Chapter 12 of 30" / "Page 130 of 267" / "1:05:12 of 9:30:00". */
    val unitLabel: String?,
    val bookExists: Boolean,
    val daysLeft: Int,
    /** Linear expectation between start and deadline, clamped 0…1. */
    val expectedFraction: Double,
) {
    enum class Status { DONE, ON_TRACK, AHEAD, BEHIND, OVERDUE, NO_BOOK }

    val status: Status
        get() {
            val f = fraction ?: return if (bookExists) Status.ON_TRACK else Status.NO_BOOK
            if (f >= 0.999) return Status.DONE
            if (daysLeft <= 0) return Status.OVERDUE
            if (f >= expectedFraction + 0.1) return Status.AHEAD
            if (f >= expectedFraction - 0.05) return Status.ON_TRACK
            return Status.BEHIND
        }
}

object GoalMath {

    /** The book's completion fraction from its own position data. */
    fun fraction(book: Book): Double? {
        // Android stores position as a 1-based chapter index plus a
        // chapter-local fraction.
        val position = book.position
        if (position <= 0) return null
        val chapterIndex = position - 1
        return when (book.format) {
            Book.FORMAT_EPUB -> {
                val count = book.spineCount ?: (book.chapters.size.takeIf { it > 0 }) ?: return null
                if (count <= 0) null
                else ((chapterIndex + book.positionFraction).coerceAtLeast(0.0) / count).coerceIn(0.0, 1.0)
            }
            Book.FORMAT_PDF -> {
                val count = book.chapters.size.takeIf { it > 0 }
                    ?: book.chapters.maxOfOrNull { it.endPage + 1 }
                    ?: return null
                if (count <= 0) null
                else ((chapterIndex + book.positionFraction).coerceAtLeast(0.0) / count).coerceIn(0.0, 1.0)
            }
            Book.FORMAT_AUDIO -> {
                val chapters = book.audioChapters
                if (chapters.isEmpty()) return null
                val durationMs = (chapters.maxOfOrNull { it.endMs } ?: 0L)
                if (durationMs <= 0) return null
                val chapter = chapters.getOrNull(chapterIndex) ?: return null
                val span = maxOf(1L, chapter.endMs - chapter.startMs)
                val elapsedMs = chapter.startMs + book.positionFraction * span
                (elapsedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
            }
            else -> null
        }
    }

    /** Where in the book the position sits, in the format's own unit. */
    fun unitLabel(book: Book): String? {
        val position = book.position
        if (position <= 0) return null
        val chapterIndex = position - 1
        return when (book.format) {
            Book.FORMAT_EPUB -> {
                val count = book.spineCount ?: (book.chapters.size.takeIf { it > 0 }) ?: return null
                "Chapter ${minOf(position, count)} of $count"
            }
            Book.FORMAT_PDF -> {
                val count = book.chapters.maxOfOrNull { it.endPage + 1 } ?: book.chapters.size
                if (count <= 0) null else "Page ${minOf(position, count)} of $count"
            }
            Book.FORMAT_AUDIO -> {
                val chapters = book.audioChapters
                if (chapters.isEmpty()) return null
                val durationMs = chapters.maxOfOrNull { it.endMs } ?: return null
                val chapter = chapters.getOrNull(chapterIndex) ?: return null
                val span = maxOf(1L, chapter.endMs - chapter.startMs)
                val elapsedMs = chapter.startMs + book.positionFraction * span
                "${clock(elapsedMs / 1000.0)} of ${clock(durationMs / 1000.0)}"
            }
            else -> null
        }
    }

    fun progress(goal: ReadingGoal, book: Book?): GoalProgress {
        val today = startOfDay(System.currentTimeMillis())
        val start = startOfDay(goal.startDate)
        val deadline = startOfDay(goal.deadline)
        val totalDays = maxOf(1, ((deadline - start) / 86_400_000L).toInt())
        val elapsedDays = (((today - start) / 86_400_000L).toInt()).coerceAtLeast(0)
        val expected = (elapsedDays.toDouble() / totalDays).coerceIn(0.0, 1.0)
        val daysLeft = maxOf(0, ((deadline - today) / 86_400_000L).toInt())
        return GoalProgress(
            fraction = book?.let { fraction(it) },
            unitLabel = book?.let { unitLabel(it) },
            bookExists = book != null,
            daysLeft = daysLeft,
            expectedFraction = expected,
        )
    }

    private fun startOfDay(epochMs: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** H:MM:SS for the audio goal readout. */
    fun clock(seconds: Double): String {
        val total = seconds.toInt()
        return String.format("%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }
}
