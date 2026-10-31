package com.speechnotes.app.logic

/**
 * Joplin-compatible JEX IMPORT — the mirror of [JexExport]. A `.jex` file is
 * a plain (uncompressed) tar of `<id>.md` entries laid out the way Joplin's
 * `BaseItem.serialize` writes them — `title\n\nbody\n\nprops`, with `type_`
 * as the last prop and no trailing newline — plus
 * `resources/<resource-id>.<ext>` binaries. Kotlin port of SpeechLogic's
 * JexImport.swift (v1.8.1); independent reimplementation from the public
 * format docs.
 *
 * What this parser accepts (deliberately lenient — JEX files in the wild
 * come from Joplin itself, from this app's export, and from other tools):
 *  - ustar AND GNU "old" tar headers, plus pax extended headers (skipped).
 *  - the canonical `title\n\nbody\n\nprops` layout AND this app's older
 *    exports (body verbatim, title as a `title:` prop, trailing newline)
 *    — a present `title:` prop marks the old layout.
 *  - metadata blocks read with Joplin's `unserialize` semantics: props
 *    scanned from the END up to the first blank line; unknown keys are
 *    ignored.
 *  - type_ 1 (note), 2 (folder), 4 (resource metadata). Everything else is
 *    skipped.
 */
object JexImport {

    data class ImportedNote(
        val id: String,
        val title: String,
        val body: String,
        val notebookId: String?,
        val createdAt: Long,
        val updatedAt: Long,
        /** resource-id → the exact `../resources/<id>.<ext>` markdown target found in the body. */
        val resources: Map<String, String>,
    )

    data class ImportedNotebook(
        val id: String,
        val title: String,
        val parentId: String?,
        val createdAt: Long,
    )

    data class ImportedResource(
        val id: String,
        val mime: String,
        val fileExtension: String,
        val data: ByteArray,
    )

    data class Archive(
        val notebooks: List<ImportedNotebook>,
        val notes: List<ImportedNote>,
        val resources: List<ImportedResource>,
    )

    class ImportError(message: String) : Exception(message)

    /** Parses a whole .jex (tar) payload; individual bad entries are skipped. */
    fun parse(data: ByteArray): Archive {
        val notebooks = ArrayList<ImportedNotebook>()
        val notes = ArrayList<ImportedNote>()
        val resourceBytes = HashMap<String, ByteArray>()
        val resourceMeta = HashMap<String, ResourceMeta>()
        var sawEntry = false

        var cursor = 0
        while (cursor + 512 <= data.size) {
            // Two consecutive zero blocks end the archive; one zero block is
            // the last entry's padding — both mean "done".
            if ((0 until 512).all { data[cursor + it] == 0.toByte() }) break
            sawEntry = true

            val entry = TarEntry(data, cursor)
            cursor += 512
            val size = entry.size
            if (cursor + size > data.size) throw ImportError("The .jex archive is truncated.")
            val payload = if (size > 0) data.copyOfRange(cursor, cursor + size) else ByteArray(0)
            cursor += paddedLength(size)

            if (entry.kind != EntryKind.REGULAR) continue
            val name = entry.name
            if (name.startsWith("resources/")) {
                val rest = name.removePrefix("resources/")
                // Tar-safety: resource names become part of stored file
                // names downstream — reject anything that escapes the
                // resources/ namespace (nested paths, traversal segments).
                val safe = rest.isNotEmpty() && !rest.contains("..") &&
                    !rest.contains('/') && !rest.contains('\\')
                if (safe) {
                    val id = rest.substringBeforeLast('.')
                    val ext = rest.substringAfterLast('.', "")
                    if (id.isNotEmpty()) {
                        resourceBytes[id] = payload
                        val existing = resourceMeta[id]
                        resourceMeta[id] = ResourceMeta(
                            mime = existing?.mime ?: "",
                            ext = when {
                                existing?.ext?.isNotEmpty() == true -> existing.ext
                                ext.isNotEmpty() -> ext
                                else -> ""
                            },
                        )
                    }
                }
                continue
            }
            val text = String(payload, Charsets.UTF_8)
            val metadata = MetadataBlock.parse(text) ?: continue
            when (metadata.type) {
                1 -> {
                    val id = metadata.fields["id"]
                    if (!id.isNullOrEmpty()) {
                        notes.add(
                            ImportedNote(
                                id = id,
                                title = metadata.title,
                                body = metadata.bodyText,
                                notebookId = metadata.fields["parent_id"],
                                createdAt = metadata.date("created_time"),
                                updatedAt = metadata.date("updated_time"),
                                resources = resourceTargets(metadata.bodyText),
                            )
                        )
                    }
                }
                2 -> {
                    notebooks.add(
                        ImportedNotebook(
                            id = metadata.fields["id"].orEmpty(),
                            title = if (metadata.title.isNotEmpty()) metadata.title
                            else metadata.fields["title"].orEmpty(),
                            parentId = metadata.fields["parent_id"],
                            createdAt = metadata.date("created_time"),
                        )
                    )
                }
                4 -> {
                    val id = metadata.fields["id"]
                    if (!id.isNullOrEmpty()) {
                        val mime = metadata.fields["mime"].orEmpty()
                        val existing = resourceMeta[id]
                        resourceMeta[id] = when {
                            existing != null && mime.isNotEmpty() -> existing.copy(mime = mime)
                            existing != null -> existing
                            else -> ResourceMeta(mime = mime, ext = "")
                        }
                    }
                }
            }
        }

        if (!sawEntry) throw ImportError("Not a Joplin .jex archive.")
        val resources = ArrayList<ImportedResource>()
        for ((id, bytes) in resourceBytes) {
            val meta = resourceMeta[id] ?: continue
            var ext = meta.ext
            if (ext.isEmpty()) ext = fileExtension(meta.mime) ?: continue
            resources.add(ImportedResource(id, meta.mime, ext, bytes))
        }
        if (notes.isEmpty() && notebooks.isEmpty() && resources.isEmpty()) {
            throw ImportError("The .jex archive is empty.")
        }
        return Archive(notebooks, notes, resources)
    }

    private data class ResourceMeta(val mime: String, val ext: String)

    // MARK: - Per-type extraction

    /**
     * `![alt](../resources/<id>.<ext>)` is what Joplin writes; this app's
     * own exports keep the body's `speechnotes://note-image/<hash>.<ext>`
     * links, whose resource id is the leading 32 hex of the (SHA-256)
     * content hash — matching those too means a round-trip re-stores the
     * bytes under the note even when the note gets a fresh id, and legacy
     * short-hash archives (the old FNV names) map by their full hash.
     */
    private fun resourceTargets(body: String): Map<String, String> {
        if (!body.contains("resources/") && !body.contains("](")) return emptyMap()
        val found = LinkedHashMap<String, String>()
        val joplin = Regex("""(?:\.\./)?resources/([0-9a-fA-F]{32})\.([A-Za-z0-9]+)""")
        for (match in joplin.findAll(body)) found[match.groupValues[1]] = match.value
        val local = Regex("""speechnotes://note-image/([0-9a-fA-F]{8,64})\.([A-Za-z0-9]+)""")
        for (match in local.findAll(body)) {
            val hash = match.groupValues[1].lowercase()
            val id = if (hash.length >= 32) hash.take(32) else hash
            if (!found.containsKey(id)) found[id] = match.value
        }
        return found
    }

    private fun fileExtension(mime: String): String? = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> null
    }

    // MARK: - Tar entry

    private enum class EntryKind { REGULAR, DIRECTORY, OTHER }

    private class TarEntry(data: ByteArray, offset: Int) {
        val name: String
        val kind: EntryKind
        val size: Int

        init {
            fun cString(from: Int, to: Int): String {
                var end = from
                while (end < to && data[end] != 0.toByte()) end++
                return String(data, from, end - from, Charsets.ISO_8859_1).trim(' ')
            }
            val magic = cString(offset + 257, offset + 263)
            val normalized = magic.trim('\u0000', ' ')
            if (normalized.isNotEmpty() && !normalized.startsWith("ustar")) {
                throw ImportError("Not a Joplin .jex archive.")
            }
            val sizeField = cString(offset + 124, offset + 136)
            val parsed = sizeField.toLongOrNull(8) ?: -1L
            if (parsed < 0 || parsed > Int.MAX_VALUE) throw ImportError("The .jex archive is truncated.")
            size = parsed.toInt()
            name = cString(offset, offset + 100)
            val typeByte = data[offset + 156].toInt() and 0xFF
            kind = when (typeByte) {
                '0'.code, 0 -> EntryKind.REGULAR
                '5'.code -> EntryKind.DIRECTORY
                else -> EntryKind.OTHER // pax/gnu extended headers: payload skipped
            }
        }
    }

    /** 512-alignment: pad any payload length to the next block boundary. */
    private fun paddedLength(raw: Int): Int = (raw + 511) and 511.inv()

    // MARK: - ID mapping

    /**
     * JexExport.joplinId() strips a UUID's dashes and lowercases it. This
     * reverses that for ids this app exported (so a round-trip keeps its
     * identity); a foreign Joplin id that is not a UUID reconstitutes to
     * null and the caller assigns a fresh one.
     */
    fun uuid(fromJoplinId: String): String? {
        val hex = fromJoplinId.lowercase()
        if (hex.length != 32 || hex.any { it !in "0123456789abcdef" }) return null
        val builder = StringBuilder(36)
        hex.forEachIndexed { index, c ->
            if (index == 8 || index == 12 || index == 16 || index == 20) builder.append('-')
            builder.append(c)
        }
        return builder.toString()
    }

    // MARK: - Metadata block

    /**
     * The trailing `key: value` block that ends with `type_: N`, read with
     * Joplin's `BaseItem.unserialize` semantics: scan lines from the END
     * collecting `key: value` props until the first blank line; the lines
     * above it are the body part whose FIRST line is the title (the parser
     * splices title + blank away). A present `title:` prop marks this app's
     * OLD export layout (body verbatim, title from the prop) — Joplin's own
     * serializer never writes `title:` as a prop. Tolerates trailing blank
     * lines, which only old exports end files with.
     */
    private class MetadataBlock private constructor(
        val type: Int,
        val fields: Map<String, String>,
        val title: String,
        val bodyText: String,
    ) {
        companion object {
            fun parse(entry: String): MetadataBlock? {
                val lines = entry.split('\n').toMutableList()
                while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
                if (lines.isEmpty()) return null

                val fields = HashMap<String, String>()
                var type = 0
                var bodyEnd = -1 // exclusive end of the body part
                var index = lines.size - 1
                while (index >= 0) {
                    val line = lines[index].trim()
                    if (line.isEmpty()) {
                        bodyEnd = index
                        break
                    }
                    val colon = line.indexOf(':')
                    if (colon <= 0) return null
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (key == "type_") type = value.toIntOrNull() ?: 0
                    else fields[key] = value
                    index--
                }
                if (type == 0) return null

                val bodyLines = if (bodyEnd >= 0) lines.subList(0, bodyEnd) else emptyList()
                val legacyTitle = fields["title"]
                return if (!legacyTitle.isNullOrEmpty()) {
                    MetadataBlock(type, fields, legacyTitle, bodyLines.joinToString("\n"))
                } else {
                    val title = bodyLines.firstOrNull()?.trim() ?: ""
                    // The splice removes the title line AND the blank
                    // separator; with neither left (title-only file) the
                    // body is empty.
                    val body = if (bodyLines.size > 2) bodyLines.subList(2, bodyLines.size).joinToString("\n") else ""
                    MetadataBlock(type, fields, title, body)
                }
            }
        }

        fun date(key: String): Long {
            return fields[key]?.let { parseISO(it) } ?: System.currentTimeMillis()
        }
    }

    /** ISO instant (with or without fraction) or Joplin's ms-since-epoch form. */
    fun parseISO(raw: String): Long? {
        runCatching { return java.time.Instant.parse(raw).toEpochMilli() }
        raw.toLongOrNull()?.let { if (it > 1_000_000_000_000) return it }
        return null
    }
}
