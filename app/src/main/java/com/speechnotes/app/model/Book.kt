package com.speechnotes.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Book manifest — the persisted shape of an imported book. JSON field names
 * match the iOS app.
 */
@Serializable
data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val author: String? = null,
    val format: String = FORMAT_EPUB,
    /** Chapter labels from the spine / outline / headings / page ranges. */
    val chapters: List<BookChapter> = emptyList(),
    /** TOC rows (label, chapter index) for the reader's sidebar. */
    val toc: List<BookTocEntry> = emptyList(),
    /** 1-based last-opened chapter. */
    val position: Int = 1,
    /** Fraction within the chapter (EPUB CFI-backed on iOS; plain here). */
    val positionFraction: Double = 0.0,
    val hasCover: Boolean = false,
    val importError: String? = null,
    /** PDF: where pdfChapters came from (outline/headings/pages). */
    val pdfChapterSource: String? = null,
    /** Audiobook: per-file chapter list, when any. */
    val audioChapters: List<AudioChapter> = emptyList(),
    /** EPUB reader state. */
    val theme: String = "white",
    val fontSize: Int = 100,
    /** Last CFI (EPUB) — restored on reopen. */
    val cfi: String? = null,
    /**
     * epub: spine (chapter) count, snapshotted at import. iOS's
     * Book.spineCount — the shelf card's "N ch" meta and the reader's
     * chapter-stepper bounds read it, because the reader's own spine total
     * only arrives after the first relocation.
     */
    val spineCount: Int? = null,
    /**
     * Recycle bin (iOS Book.deletedAt): epoch millis when soft-deleted, null
     * while on the shelf. The book directory stays on disk for
     * RECYCLE_RETENTION_DAYS, so a mis-tap on a 3 GB audiobook is
     * recoverable; purge() is the one real removal.
     */
    val deletedAt: Long? = null,
) {
    companion object {
        const val FORMAT_EPUB = "epub"
        const val FORMAT_PDF = "pdf"
        const val FORMAT_AUDIO = "audio"

        const val RECYCLE_RETENTION_DAYS = 30
    }

    val isDeleted: Boolean get() = deletedAt != null

    val recycleDaysRemaining: Int?
        get() {
            val binned = deletedAt ?: return null
            val days = ((System.currentTimeMillis() - binned) / 86_400_000L).toInt()
            return maxOf(0, RECYCLE_RETENTION_DAYS - days)
        }

    val formatLabel: String
        get() = when (format) {
            FORMAT_EPUB -> "EPUB"
            FORMAT_PDF -> "PDF"
            FORMAT_AUDIO -> "Audiobook"
            else -> "Book"
        }
}

/** One speech unit of a book. */
@Serializable
data class BookChapter(
    val label: String,
    /** EPUB: spine href. PDF: 0-based start page. Audio: 0-based chapter index. */
    val href: String? = null,
    val startPage: Int = 0,
    val endPage: Int = 0,
)

/** TOC row for the reader sidebar. */
@Serializable
data class BookTocEntry(val label: String, val chapterIndex: Int)

/** Audiobook chapter from chpl/ID3 metadata. */
@Serializable
data class AudioChapter(val title: String, val startMs: Long, val endMs: Long)
