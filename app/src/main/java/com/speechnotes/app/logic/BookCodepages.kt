package com.speechnotes.app.logic

import java.nio.charset.Charset

/**
 * The single-byte codepages the book formats arrive in. Ported from
 * speechnotes-linux-rust `crates/sn-doc/src/codepages.rs`.
 *
 * A `.fb2` declares its encoding in its XML declaration; an `.rtf` names it
 * in `\ansicpg`; a `.mobi` states it in its header. All of them are
 * one-byte encodings whose high half is a table.
 *
 * DIVERGENCE from the Rust reference, deliberate and recorded: the Rust file
 * carries 128-character hand-typed tables per codepage; here the platform's
 * own charsets answer instead (`Charset.forName` — ICU on Android, the
 * extended charsets bundle on the desktop JVM). The behavior is the one the
 * Rust tables pin: ASCII passes through, high bytes map by the codepage's
 * own table, and a byte with no meaning in its codepage keeps its Latin-1
 * glyph rather than becoming a replacement character. The letters the Rust
 * tests pin (cp1251 "привет"/"ё"/"Ё", cp1252 ’ “ ” —, cp1250 ł ż, KOI8-R П,
 * Turkish ı ş) are pinned again in BookCodepagesTest against THIS decode.
 */
object BookCodepages {

    private val byAlias: Map<String, String> = mapOf(
        "windows-1250" to "windows-1250",
        "windows-1251" to "windows-1251",
        "windows-1252" to "windows-1252",
        "windows-1253" to "windows-1253",
        "windows-1254" to "windows-1254",
        "windows-1255" to "windows-1255",
        "windows-1256" to "windows-1256",
        "windows-1257" to "windows-1257",
        "windows-1258" to "windows-1258",
        "koi8-r" to "KOI8-R",
    )

    private fun charsetFor(alias: String): Charset? =
        runCatching { Charset.forName(alias) }.getOrNull()

    /** The direct decoders the parsers call on a KNOWN codepage (a
     *  header field, not a name). Unknown-name lookup goes through
     *  [decodeNamed]. */
    fun decodeCp1250(data: ByteArray): String = decode("windows-1250", data)
    fun decodeCp1251(data: ByteArray): String = decode("windows-1251", data)
    fun decodeCp1252(data: ByteArray): String = decode("windows-1252", data)

    private fun decode(alias: String, data: ByteArray): String =
        charsetFor(alias)?.let { String(data, it) } ?: String(data, Charsets.ISO_8859_1)

    /** Decode bytes in the codepage an XML declaration or an RTF control
     *  word names. `null` when the name is one this object does not know —
     *  the caller then falls back to UTF-8, which never fails. */
    fun decodeNamed(name: String, data: ByteArray): String? {
        // 65001 is how an RTF \ansicpg names UTF-8.
        val key = name.trim().lowercase().replace('_', '-').replace(' ', '-')
        return when (key) {
            "utf-8", "utf8", "65001" -> String(data, Charsets.UTF_8)
            "iso-8859-1", "iso8859-1", "latin1", "latin-1", "iso-latin1" -> decodeLatin1(data)
            "cp1250", "1250" -> decodeNamed("windows-1250", data)
            "cp1251", "1251" -> decodeNamed("windows-1251", data)
            "cp1252", "1252" -> decodeNamed("windows-1252", data)
            "cp1253", "1253" -> decodeNamed("windows-1253", data)
            "cp1254", "1254" -> decodeNamed("windows-1254", data)
            "cp1255", "1255" -> decodeNamed("windows-1255", data)
            "cp1256", "1256" -> decodeNamed("windows-1256", data)
            "cp1257", "1257" -> decodeNamed("windows-1257", data)
            "cp1258", "1258" -> decodeNamed("windows-1258", data)
            "koi8r", "koi8" -> decodeNamed("koi8-r", data)
            else -> byAlias[key]?.let { alias ->
                charsetFor(alias)?.let { charset -> String(data, charset) }
            }
        }
    }

    private fun decodeLatin1(data: ByteArray): String {
        val out = StringBuilder(data.size)
        for (b in data) out.append((b.toInt() and 0xFF).toChar())
        return out.toString()
    }
}
