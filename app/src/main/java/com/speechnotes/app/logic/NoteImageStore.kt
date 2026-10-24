package com.speechnotes.app.logic

import java.io.File

/**
 * Local image cache for note-embedded images. Ported from
 * NoteImageStore.swift (speechnotes-ios): images live in
 * `filesDir/note-images/<noteId>/<hash>.<ext>` and are referenced from
 * markdown as `speechnotes://note-image/<hash>.<ext>`.
 */
object NoteImageStore {

    const val SCHEME = "speechnotes://note-image/"

    private fun directoryFor(noteId: String?): File? {
        if (noteId.isNullOrBlank()) return null
        val dir = File(AppFiles.notesImagesDir, noteId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun parseLocalTarget(target: String): Pair<String, String>? {
        if (!target.startsWith(SCHEME)) return null
        val name = target.removePrefix(SCHEME).substringBefore('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(0, dot) to name.substring(dot + 1)
    }

    /** The markdown fragment that references [target]. */
    fun markdownFor(target: String, alt: String = "image"): String = "![$alt]($target)"

    /** Resolves a `speechnotes://note-image/…` target to its stored file. */
    fun resolveLocalFile(target: String, noteId: String?): File? {
        val dir = directoryFor(noteId) ?: return null
        val parsed = parseLocalTarget(target) ?: return null
        val file = File(dir, "${parsed.first}.${parsed.second}")
        return if (file.exists()) file else null
    }

    /** Stores raw image bytes and returns the markdown target. */
    fun store(data: ByteArray, ext: String, noteId: String?, alt: String = "image"): String? {
        val dir = directoryFor(noteId) ?: return null
        val hash = TextHash.of(String(data, Charsets.ISO_8859_1)).toString(16).padStart(8, '0')
        val file = File(dir, "$hash.$ext")
        file.writeBytes(data)
        return SCHEME + "$hash.$ext"
    }

    /** Every stored target for a note. */
    fun allTargets(noteId: String?): List<String> {
        val dir = directoryFor(noteId) ?: return emptyList()
        return dir.listFiles()?.map { SCHEME + it.name } ?: emptyList()
    }

    fun removeAllImages(noteId: String?) {
        val dir = directoryFor(noteId) ?: return
        dir.deleteRecursively()
    }

    fun footprint(noteId: String?): Long {
        val dir = directoryFor(noteId) ?: return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Prunes stored images the note no longer references; returns bytes freed. */
    fun prune(noteId: String?, markdown: String): Long {
        val dir = directoryFor(noteId) ?: return 0
        val referenced = MarkdownText.imageTokens(markdown).map { it.second }.toSet()
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            if ((SCHEME + file.name) !in referenced) {
                freed += file.length()
                file.delete()
            }
        }
        return freed
    }
}
