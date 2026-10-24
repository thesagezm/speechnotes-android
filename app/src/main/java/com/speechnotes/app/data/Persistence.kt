package com.speechnotes.app.data

import android.content.Context
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.logic.TextHash
import com.speechnotes.app.model.Note
import com.speechnotes.app.model.Notebook
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Shared JSON + persistence helpers mirroring the iOS stores: atomic writes,
 * rolling backups, corrupt-file quarantine + recovery from backup. Never
 * silently overwrites a corrupt file.
 */
object Persistence {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** Corrupt files are quarantined as `<name>.corrupt-<unixSeconds>` and recovered from backup when possible. */
    fun <T> load(
        file: File,
        backup: File?,
        empty: T,
        decode: (String) -> T?,
        onQuarantine: (String) -> Unit,
    ): T {
        val raw = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
        if (raw != null) {
            decode(raw)?.let { return it }
            onQuarantine(file.name)
            val stamp = System.currentTimeMillis() / 1000
            runCatching { file.renameTo(File(file.parentFile, "${file.name}.corrupt-$stamp")) }
        }
        if (backup != null) {
            val backupRaw = runCatching { backup.takeIf { it.exists() }?.readText() }.getOrNull()
            if (backupRaw != null) {
                decode(backupRaw)?.let { return it }
            }
        }
        return empty
    }

    /** Writes the value + the rolling backup mirror. */
    fun save(file: File, backup: File?, content: String) {
        AppFiles.atomicWrite(file, content)
        if (backup != null) runCatching { backup.writeText(content, Charsets.UTF_8) }
    }
}

/** Preference keys the app shares with the iOS version. */
object Prefs {
    const val NAME = "speechnotes"
    const val ACCENT = "accentColorChoice"
    const val APPEARANCE = "appAppearance"
    const val PREVIEW_TEXT_SCALE = "previewTextScale"
    const val RENDER_MARKDOWN = "renderMarkdown"
    const val READ_ALONG_ENABLED = "readAlongEnabled"
    const val NOTES_SORT_ORDER = "notesSortOrder"
    const val ACTIVE_NOTEBOOK_SCOPE = "activeNotebookScope"
    const val HAS_ONBOARDED = "hasOnboarded"
    const val ENGINE_KIND = "engineKind"
    const val VOICE = "voice"
    const val SUPERTONIC_VOICE = "supertonicVoice"
    const val SUPERTONIC_LANG = "supertonicLang"
    const val SUPERTONIC_QUALITY = "supertonicRenderQuality"
    const val SYSTEM_VOICE = "systemVoiceIdentifier"
    const val RATE_MULTIPLIER = "rateMultiplier"
    const val MINI_PLAYER_COLLAPSED = "miniPlayerCollapsed"

    // BookDrop / LocalSend receiver (bookDrop* keys match iOS's UserDefaults).
    const val BOOK_DROP_ENABLED = "bookDropEnabled"
    const val BOOK_DROP_AUTO_ACCEPT = "bookDropAutoAccept"
    const val BOOK_DROP_FINGERPRINT = "bookDropFingerprint"

    // Reader appearance (ReaderAppearance.swift parity). Booleans that
    // default to TRUE must be stored as "unset | true | false" — a plain
    // boolean reads false for a missing key, which is why iOS reads them
    // back with `object(forKey:) as? Bool`.
    const val BOOK_READER_THEME = "bookReaderTheme"
    const val BOOK_READER_FONT_SIZE = "bookReaderFontSize"
    const val BOOK_READER_FLOW = "bookReaderFlow"
    const val BOOK_READER_FONT = "bookReaderFont"
    const val BOOK_READER_LINE_HEIGHT = "bookReaderLineHeight"
    const val BOOK_READER_PARA_SPACING = "bookReaderParaSpacing"
    const val BOOK_READER_LETTER_SPACING = "bookReaderLetterSpacing"
    const val BOOK_READER_MARGIN = "bookReaderMargin"
    const val BOOK_READER_RESPECT_STYLES = "bookReaderRespectStyles"
    const val BOOK_READER_AUTO_SCROLL = "bookReaderAutoScroll"
    const val BOOK_READER_AUTO_SCROLL_SPEED = "bookReaderAutoScrollSpeed"
    const val BOOK_READER_TAP_TURN = "bookReaderTapTurn"
    const val BOOK_READER_SWIPE_TURN = "bookReaderSwipeTurn"
    const val BOOK_READER_TAP_INVERTED = "bookReaderTapInverted"

    fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
