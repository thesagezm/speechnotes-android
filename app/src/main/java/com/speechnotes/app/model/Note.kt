package com.speechnotes.app.model

import com.speechnotes.app.logic.MarkdownText
import com.speechnotes.app.logic.SentenceChunker
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A note. JSON field names match the iOS app so data can be copied across
 * platforms later.
 */
@Serializable
data class Note(
    val id: String = UUID.randomUUID().toString(),
    /// User-set title. null (or blank) → title derives from the first
    /// sentence of `text`.
    val explicitTitle: String? = null,
    val text: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /// Recycle bin: null = active note; set = sitting in Recently Deleted,
    /// stamped with the moment it was binned (epoch millis).
    val deletedAt: Long? = null,
    /// Notebook this note is filed into; null = Unfiled.
    val notebookId: String? = null,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
) {
    companion object {
        const val RECYCLE_RETENTION_DAYS = 30

        // Compiled once: these getters run per list row and per search
        // keystroke — a fresh Regex per call was pure allocation churn.
        private val WHITESPACE = Regex("\\s+")
        private val NEWLINE_FOLD = Regex("\\s*\n\\s*")
    }

    /**
     * Title for display: explicit when set, otherwise the first SENTENCE of
     * the note (a one-line paragraph with three sentences still titles by
     * its opening sentence). Windowed so list rendering stays cheap.
     */
    val title: String
        get() {
            val explicit = explicitTitle?.trim()
            if (!explicit.isNullOrEmpty()) return explicit.take(120)
            val window = text.take(300)
            val firstSentence = SentenceChunker.firstChunk(window)?.text ?: window
            val trimmed = firstSentence.trim()
            return if (trimmed.isEmpty()) "Untitled note" else trimmed.take(60)
        }

    val wordCount: Int
        get() = text.split(WHITESPACE).count { it.isNotEmpty() }

    /** ~145 wpm. */
    val estimatedListenMinutes: Int?
        get() {
            if (wordCount == 0) return null
            return maxOf(1, Math.round(wordCount / 145.0).toInt())
        }

    val isDeleted: Boolean get() = deletedAt != null

    /** Days left before this binned note is auto-purged (null while active). */
    val recycleDaysRemaining: Int?
        get() {
            val bin = deletedAt ?: return null
            val days = ((System.currentTimeMillis() - bin) / 86_400_000L).toInt()
            return maxOf(0, RECYCLE_RETENTION_DAYS - days)
        }

    /** ~120-char preview for list rows. */
    val preview: String
        get() = text.trimStart('\n', ' ', '\t').replace(NEWLINE_FOLD, "  ").take(120)

    /** Whether the render-markdown path should strip syntax for speech. */
    fun speechText(renderMarkdown: Boolean): String =
        if (renderMarkdown) MarkdownText.plainText(text) else text
}
