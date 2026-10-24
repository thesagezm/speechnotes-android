package com.speechnotes.app.logic

import java.io.File

/**
 * The app's private-file layout. Ported from the iOS app's
 * FileManager paths (Documents/…). Everything lives under the app's
 * filesDir so the app needs no storage permissions.
 */
object AppFiles {

    lateinit var root: File

    /** Application context for asset reads (the G2P dictionaries live in assets). */
    var context: android.content.Context? = null

    fun init(filesDir: File, appContext: android.content.Context? = null) {
        context = appContext ?: context
        root = filesDir
        notesImagesDir.mkdirs()
        booksDir.mkdirs()
        exportsDir.mkdirs()
        bookDropIncomingDir.mkdirs()
        kokoroDir.mkdirs()
        supertonicDir.mkdirs()
        supertonicOnnxDir.mkdirs()
        supertonicStylesDir.mkdirs()
    }

    val notesImagesDir: File get() = File(root, "note-images")
    val booksDir: File get() = File(root, "Books")
    val exportsDir: File get() = File(root, "Exports")
    /** Where BookDrop uploads spill + land before the router moves them. */
    val bookDropIncomingDir: File get() = File(root, "BookDrop/incoming")

    /** Per-model directories. */
    val kokoroDir: File get() = File(root, "KokoroOnnx")
    val supertonicDir: File get() = File(root, "Supertonic")
    val supertonicOnnxDir: File get() = File(root, "Supertonic/onnx")
    val supertonicStylesDir: File get() = File(root, "Supertonic/voice_styles")

    fun notesFile() = File(root, "notes.json")
    fun notesBackupFile() = File(root, "notes.backup.json")
    /** The passphrase-wrapped master key (Joplin KeyV1), itself wrapped under the Keystore. */
    fun vaultKeyFile() = File(root, "vault.key")
    fun notebooksFile() = File(root, "notebooks.json")
    fun notebooksBackupFile() = File(root, "notebooks.backup.json")
    fun bookmarksFile() = File(root, "bookmarks.json")

    fun bookDirectory(id: String): File {
        val dir = File(booksDir, id)
        dir.mkdirs()
        return dir
    }

    fun bookOriginalFile(id: String, ext: String): File = File(bookDirectory(id), "original.$ext")
    fun bookCoverFile(id: String): File = File(bookDirectory(id), "cover.jpg")
    fun bookManifestFile(id: String): File = File(bookDirectory(id), "manifest.json")
    fun bookSpeechTextFile(id: String, chapterIndex: Int): File =
        File(File(bookDirectory(id), "text"), "%04d.txt".format(chapterIndex))
    fun bookSpeechOffsetsFile(id: String, chapterIndex: Int): File =
        File(File(bookDirectory(id), "text"), "%04d.pages.json".format(chapterIndex))

    fun bookExists(id: String): Boolean = File(booksDir, id).exists()

    fun bookIds(): List<String> =
        booksDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

    fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun booksDirectorySize(): Long = directorySize(booksDir)

    /** Atomic write: temp file + rename, so a crash never truncates. */
    fun atomicWrite(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
