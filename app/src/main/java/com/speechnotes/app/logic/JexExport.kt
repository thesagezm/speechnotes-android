package com.speechnotes.app.logic

import java.io.ByteArrayOutputStream

/**
 * Joplin-compatible JEX export. A `.jex` file is a plain (uncompressed) tar
 * containing `<id>.md` files laid out the way Joplin's own
 * `BaseItem.serialize` writes them — `title\n\nbody\n\nprops`, with `type_`
 * as the last prop and no trailing newline. Ported from JexExport.swift
 * (speechnotes-ios v1.8.1).
 *
 * Entry catalog Joplin consumes:
 *   <note-id>.md     — title line + body + metadata + `type_: 1`
 *   <folder-id>.md   — notebook title line + metadata + `type_: 2`
 *   <resource-id>.md — filename title line + metadata (id, mime,
 *                      file_extension) + `type_: 4`
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
            // "ustar\0" + "00" — the exact 8 bytes node-tar (Joplin's
            // extractor) matches to treat a header as ustar (iOS v1.8.1
            // parity; a bare "ustar" left it a v7 header).
            writeString("00", 263, 2)
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

    private val isoFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)

    private fun iso(epochMillis: Long): String = isoFormatter.format(java.time.Instant.ofEpochMilli(epochMillis))

    /**
     * Trailing metadata block, mirroring Joplin's serializer: props joined
     * by single newlines, `type_` last, and NO trailing newline. Joplin's
     * `unserialize` scans lines from the END and stops at the first blank
     * line — one trailing newline and it reads zero props, which aborts the
     * entire import (iOS v1.8.1 parity).
     */
    private fun metadataBlock(fields: List<Pair<String, String>>, type: Int): String =
        (fields + ("type_" to type.toString())).joinToString("\n") { "${it.first}: ${it.second}" }

    /**
     * File layout mirrors Joplin's `BaseItem.serialize`:
     * `title\n\nbody\n\nprops`. The title rides the FIRST body line
     * (Joplin's parser takes it from there and overwrites any `title:`
     * prop), and the props block carries `user_created_time` /
     * `user_updated_time` so Joplin keeps the note's own timestamps
     * (iOS v1.8.1 parity).
     */
    private fun noteEntry(note: NotePayload): Pair<String, ByteArray> {
        val fields = ArrayList<Pair<String, String>>()
        fields.add("id" to note.id)
        note.notebookId?.let { fields.add("parent_id" to it) }
        fields.add("created_time" to iso(note.createdAt))
        fields.add("updated_time" to iso(note.updatedAt))
        fields.add("user_created_time" to iso(note.createdAt))
        fields.add("user_updated_time" to iso(note.updatedAt))
        val props = metadataBlock(fields, 1)
        val parts = ArrayList<String>()
        parts.add(note.title.trim())
        val body = note.markdownBody.trimEnd()
        if (body.isNotEmpty()) parts.add(body)
        parts.add(props)
        return "${note.id}.md" to parts.joinToString("\n\n").toByteArray(Charsets.UTF_8)
    }

    private fun notebookEntry(nb: NotebookPayload): Pair<String, ByteArray> {
        val fields = ArrayList<Pair<String, String>>()
        fields.add("id" to nb.id)
        nb.parentId?.let { fields.add("parent_id" to it) }
        fields.add("created_time" to iso(nb.createdAt))
        // Folders carry no updated timestamp of their own; Joplin's folder
        // records want one, and created_time is the honest value we have.
        fields.add("updated_time" to iso(nb.createdAt))
        val body = nb.title.trim() + "\n\n" + metadataBlock(fields, 2)
        return "${nb.id}.md" to body.toByteArray(Charsets.UTF_8)
    }

    /** A resource's title line is its `id.ext` filename — what Joplin's
     *  `Resource.filename` resolves to and what the importer matches the
     *  binary in `resources/` against. */
    private fun resourceMetadataEntry(image: ImagePayload): Pair<String, ByteArray> {
        val now = iso(System.currentTimeMillis())
        val fields = listOf(
            "id" to image.id,
            "mime" to image.mimeType,
            "file_extension" to image.fileExtension,
            "filename" to "",
            "created_time" to now,
            "updated_time" to now,
        )
        val body = "${image.id}.${image.fileExtension}" + "\n\n" + metadataBlock(fields, 4)
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
