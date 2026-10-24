package com.speechnotes.app.logic

/**
 * Joplin-compatible JEX IMPORT — the mirror of [JexExport]. A `.jex` file is
 * a plain (uncompressed) tar of `<id>.md` entries whose trailing metadata
 * block carries the id/type/parent/timestamps, plus
 * `resources/<resource-id>.<ext>` binaries. Kotlin port of SpeechLogic's
 * JexImport.swift; independent reimplementation from the public format docs.
 *
 * What this parser accepts (deliberately lenient — JEX files in the wild
 * come from Joplin itself, from this app's export, and from other tools):
 *  - ustar AND GNU "old" tar headers, plus pax extended headers (skipped).
 *  - metadata blocks with `type_` ANYWHERE in the trailing block (Joplin
 *    writes it first; this app's exporter writes it last) and `key: value`
 *    lines; unknown keys are ignored.
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
                                title = metadata.fields["title"].orEmpty(),
                                body = metadata.body ?: "",
                                notebookId = metadata.fields["parent_id"],
                                createdAt = metadata.date("created_time"),
                                updatedAt = metadata.date("updated_time"),
                                resources = resourceTargets(metadata.body ?: ""),
                            )
                        )
                    }
                }
                2 -> {
                    val title = text.substringBefore("\n").trim()
                    notebooks.add(
                        ImportedNotebook(
                            id = metadata.fields["id"].orEmpty(),
                            title = title,
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

    /** `![alt](../resources/<id>.<ext>)` is what Joplin writes; also raw `:/<id>` links. */
    private fun resourceTargets(body: String): Map<String, String> {
        if (!body.contains("resources/") && !body.contains("](")) return emptyMap()
        val found = LinkedHashMap<String, String>()
        val regex = Regex("""(?:\.\./)?resources/([0-9a-fA-F]{32})\.([A-Za-z0-9]+)""")
        for (match in regex.findAll(body)) found[match.groupValues[1]] = match.value
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

    /** The trailing `key: value` block that ends with `type_: N`. */
    private class MetadataBlock private constructor(
        val type: Int,
        val fields: Map<String, String>,
        val body: String?,
    ) {
        companion object {
            fun parse(entry: String): MetadataBlock? {
                // Anchor on the LAST `type_:` line and walk back to the blank
                // line that begins the block. The search runs over everything
                // up to the END of the type_ line, so it finds the separator
                // whether type_ comes first (Joplin) or last (this app's
                // exporter). A resource metadata file has NO body — its block
                // starts at the top with no blank line; that is valid too.
                val typeIndex = entry.lastIndexOf("\ntype_: ")
                if (typeIndex < 0) return null
                val typeLineEnd = entry.indexOf('\n', typeIndex + 1).let { if (it < 0) entry.length else it }
                val headAndType = entry.substring(0, typeLineEnd)
                val blank = headAndType.lastIndexOf("\n\n")
                return if (blank >= 0) {
                    parseBlock(entry.substring(blank + 2), entry.substring(0, blank))
                } else {
                    parseBlock(entry, "")
                }
            }

            private fun parseBlock(block: String, body: String): MetadataBlock? {
                val fields = HashMap<String, String>()
                var type = 0
                for (line in block.lineSequence()) {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (key.isEmpty()) continue
                    if (key == "type_") type = value.toIntOrNull() ?: 0
                    else fields[key] = value
                }
                if (type == 0) return null
                return MetadataBlock(type, fields, body)
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
