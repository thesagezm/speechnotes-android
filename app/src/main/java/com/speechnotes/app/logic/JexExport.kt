package com.speechnotes.app.logic

import java.io.ByteArrayOutputStream

/**
 * Joplin-compatible JEX export. A `.jex` file is a plain (uncompressed) tar
 * containing `<id>.md` files whose trailing metadata block Joplin parses on
 * import. Ported from JexExport.swift (speechnotes-ios).
 *
 * Entry catalog Joplin consumes:
 *   <note-id>.md     — note body + metadata + `type_: 1`
 *   <folder-id>.md   — notebook title line + metadata + `type_: 2`
 *   <resource-id>.md — empty body + metadata (id, title=filename, mime) + `type_: 4`
 *   resources/<resource-id>.<ext> — the binary blob
 */
object JexExport {

    data class NotePayload(
        val id: String,            // 32 lowercase hex
        val title: String,
        val markdownBody: String,
        val notebookId: String?,   // parent notebook id, nil = root
        val createdAt: Long,       // epoch millis
        val updatedAt: Long,
        val images: List<ImagePayload> = emptyList(),
    )

    data class NotebookPayload(
        val id: String,
        val title: String,
        val parentId: String?,
        val createdAt: Long,
    )

    data class ImagePayload(
        val id: String,
        val fileExtension: String,
        val mimeType: String,
        val data: ByteArray,
    )

    // MARK: - Tar writer

    private class TarWriter {
        private val out = ByteArrayOutputStream()

        fun append(name: String, data: ByteArray, mode: Int = 0x1A4 /* 0644 */) {
            val header = ByteArray(512)
            fun writeString(s: String, at: Int, max: Int) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                val count = minOf(bytes.size, max)
                System.arraycopy(bytes, 0, header, at, count)
            }
            fun writeOctal(value: Long, at: Int, width: Int) {
                val s = java.lang.Long.toOctalString(value)
                val padded = "0".repeat(maxOf(0, width - 1 - s.length)) + s
                writeString(padded, at, width - 1)
            }
            writeString(name, 0, 100)
            writeOctal(mode.toLong(), 100, 8)
            writeOctal(0, 108, 8)   // uid
            writeOctal(0, 116, 8)   // gid
            writeOctal(data.size.toLong(), 124, 12)
            writeOctal(System.currentTimeMillis() / 1000, 136, 12)
            writeString("        ", 148, 8) // chksum placeholder: 8 spaces
            header[156] = '0'.code.toByte() // type: regular file
            writeString("ustar", 257, 6)
            var checksum = 0
            for (b in header) checksum += (b.toInt() and 0xFF)
            writeOctal(checksum.toLong(), 148, 8)
            out.write(header)
            out.write(data)
            val pad = (512 - (data.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }

        fun finish(): ByteArray {
            out.write(ByteArray(1024)) // end-of-archive: two zero blocks
            return out.toByteArray()
        }
    }

    // MARK: - Metadata

    private fun iso(epochMillis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMillis)
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
    }

    /** Trailing metadata block Joplin's importer anchors on; `type_` LAST. */
    private fun metadataBlock(fields: List<Pair<String, String>>, type: Int): String {
        val lines = fields.map { "${it.first}: ${it.second}" }
        return "\n\n" + (lines + "type_: $type").joinToString("\n") + "\n"
    }

    private fun noteEntry(note: NotePayload): Pair<String, ByteArray> {
        val fields = ArrayList<Pair<String, String>>()
        fields.add("id" to note.id)
        if (note.notebookId != null) fields.add("parent_id" to note.notebookId)
        fields.add("created_time" to iso(note.createdAt))
        fields.add("updated_time" to iso(note.updatedAt))
        fields.add("markup_language" to "1")
        // Joplin's importer reads the note title from metadata; without a
        // title field every note re-titles itself from its first body line
        // on the Joplin side, and an Android→Joplin→Android round-trip loses
        // the explicit title entirely.
        if (note.title.isNotBlank()) fields.add("title" to note.title)
        val body = note.markdownBody + metadataBlock(fields, 1)
        return "${note.id}.md" to body.toByteArray(Charsets.UTF_8)
    }

    private fun notebookEntry(nb: NotebookPayload): Pair<String, ByteArray> {
        val fields = ArrayList<Pair<String, String>>()
        fields.add("id" to nb.id)
        if (nb.parentId != null) fields.add("parent_id" to nb.parentId)
        fields.add("created_time" to iso(nb.createdAt))
        val body = nb.title + metadataBlock(fields, 2)
        return "${nb.id}.md" to body.toByteArray(Charsets.UTF_8)
    }

    private fun resourceMetadataEntry(image: ImagePayload): Pair<String, ByteArray> {
        val fields = listOf(
            "id" to image.id,
            "title" to "${image.id}.${image.fileExtension}",
            "mime" to image.mimeType,
            "filename" to "",
            "created_time" to iso(System.currentTimeMillis()),
        )
        val body = metadataBlock(fields, 4)
        return "${image.id}.md" to body.toByteArray(Charsets.UTF_8)
    }

    // MARK: - Public API

    /**
     * Builds the .jex payload. Notes referencing notebook ids not in
     * [notebooks] are dropped (a dangling parent_id makes Joplin's importer
     * complain).
     */
    fun buildArchive(notes: List<NotePayload>, notebooks: List<NotebookPayload>): ByteArray {
        val tar = TarWriter()
        val notebookIds = notebooks.map { it.id }.toSet()
        for (nb in notebooks) {
            val entry = notebookEntry(nb)
            tar.append(entry.first, entry.second)
        }
        val emittedResources = HashSet<String>()
        for (note in notes) {
            if (note.notebookId != null && note.notebookId !in notebookIds) continue
            val entry = noteEntry(note)
            tar.append(entry.first, entry.second)
            for (image in note.images) {
                if (!emittedResources.add(image.id)) continue
                val meta = resourceMetadataEntry(image)
                tar.append(meta.first, meta.second)
                tar.append("resources/${image.id}.${image.fileExtension}", image.data)
            }
        }
        return tar.finish()
    }

    /** Joplin ids are 32 lowercase hex; a UUID's dashes stripped satisfies it. */
    fun joplinId(uuid: String): String = uuid.replace("-", "").lowercase()
}
