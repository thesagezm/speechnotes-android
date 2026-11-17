package com.speechnotes.app.logic

/**
 * A minimal XML pull parser — the subset the FictionBook reader and the
 * HTMLZ metadata walk need. Ported from speechnotes-linux-rust
 * `crates/sn-doc/src/xml.rs` (itself the stand-in for quick-xml there).
 *
 * What this adds on top of "split tags":
 *
 * * **Entity references** — `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;` and
 *   the numeric forms (`&#8217;`, `&#x2019;`) are decoded in text *and* in
 *   attribute values, plus the HTML5 named set books actually carry. An
 *   unknown named entity keeps its whole `&name;` token.
 * * **Namespaces are kept as given** — element names report verbatim
 *   (`dc:title`), callers match on the local part.
 * * **Raw-text elements** — `<script>`/`<style>` bodies are one verbatim
 *   event; entities NOT decoded, because the body is code.
 */
object BookXml {

    /** One event from the parser. */
    sealed class Event {
        /** An element opened. `name` is the tag as written, namespace prefix included. */
        class Start(val name: String, val attrs: List<Attr>) : Event()

        /** The element named by the most recent [Start] closed. */
        class End(val name: String) : Event()

        /** An element with no children: `<br/>`. */
        class Empty(val name: String, val attrs: List<Attr>) : Event()

        /** Character data, entities decoded. */
        class Text(val text: String) : Event()

        /** The body of a raw-text element (`<script>`, `<style>`), verbatim. */
        class Raw(val name: String, val text: String) : Event()
    }

    /** One attribute, with its value entity-decoded. */
    class Attr(val name: String, val value: String) {
        companion object {
            /** The attribute's value, or `null` when absent. */
            fun get(attrs: List<Attr>, name: String): String? =
                attrs.firstOrNull { it.name == name }?.value
        }
    }

    /** Thrown on a malformed document, naming the line. */
    class XmlError(val line: Int, message: String) : Exception("line $line: $message")

    /** Elements whose content is raw text — a `<` inside them is not markup.
     *  Only the **unprefixed** names: in a namespaced document `style:style`
     *  is an ordinary element. */
    private val RAW_TEXT_ELEMENTS = setOf("script", "style")

    /** A pull parser over one XML document. */
    class Parser(private val src: String) {
        private var pos = 0
        private var line = 1
        private var finished = false

        /** The element whose raw text is being skipped, if any. */
        private var rawUntil: Pair<String, Int>? = null

        /** The next event, or `null` at end of input. */
        fun nextEvent(): Event? {
            if (finished) return null
            rawUntil?.let { (name, depth) -> return readRawText(name, depth) }
            while (true) {
                if (pos >= src.length) {
                    finished = true
                    return null
                }
                if (src[pos] == '<') {
                    val (event, end) = readMarkup()
                    pos = end
                    if (event != null) return event
                } else {
                    // Character data up to the next '<'.
                    val start = pos
                    val next = src.indexOf('<', pos).let { if (it < 0) src.length else it }
                    val raw = src.substring(start, next)
                    val text = decodeEntities(raw) ?: throw XmlError(line, "invalid entity in character data")
                    pos = next
                    line += raw.count { it == '\n' }
                    // Whitespace-only nodes (indentation between tags) are
                    // not text to any consumer here.
                    if (!text.isBlank()) return Event.Text(text)
                }
            }
        }

        /** Read markup starting at [pos] (which points at `<`). Returns the
         *  event (null for declaration/comment/doctype/PI) and the new position. */
        private fun readMarkup(): Pair<Event?, Int> {
            val rest = src.substring(pos)
            if (rest.startsWith("<!--")) {
                val end = rest.indexOf("-->").let { if (it < 0) throw XmlError(line, "unterminated comment") else it }
                // `end` is an index into `rest` (the prefix included) — the
                // Rust reference adds the prefix length because its `end` is
                // an index into the string AFTER the prefix. Same for the
                // CDATA and PI branches below.
                val consumed = pos + end + 3
                line += src.substring(pos, consumed).count { it == '\n' }
                return null to consumed
            }
            if (rest.startsWith("<![CDATA[")) {
                val end = rest.indexOf("]]>").let { if (it < 0) throw XmlError(line, "unterminated CDATA") else it }
                val text = rest.substring(9, end)
                val consumed = pos + end + 3
                line += src.substring(pos, consumed).count { it == '\n' }
                return Event.Text(text) to consumed
            }
            if (rest.startsWith("<?")) {
                val end = rest.indexOf("?>").let { if (it < 0) throw XmlError(line, "unterminated processing instruction") else it }
                val consumed = pos + end + 2
                line += src.substring(pos, consumed).count { it == '\n' }
                return null to consumed
            }
            if (rest.startsWith("<!")) {
                // A doctype or similar; skip to the matching '>', bracket-counting
                // the internal subset.
                var depth = 0
                var i = pos + 2
                while (i < src.length) {
                    when (src[i]) {
                        '[' -> depth++
                        ']' -> depth = maxOf(0, depth - 1)
                        '>' -> if (depth == 0) {
                            val consumed = i + 1
                            line += src.substring(pos, consumed).count { it == '\n' }
                            return null to consumed
                        }
                    }
                    i++
                }
                throw XmlError(line, "unterminated declaration")
            }

            // An element: <name attrs>, </name>, or <name attrs/>.
            val end = rest.indexOf('>').let { if (it < 0) throw XmlError(line, "unterminated tag") else it }
            val inner = rest.substring(1, end)
            val consumed = pos + end + 1
            line += src.substring(pos, consumed).count { it == '\n' }
            if (inner.startsWith("/")) {
                val name = inner.substring(1).trim()
                if (name.isEmpty()) throw XmlError(line, "empty end tag")
                return Event.End(name) to consumed
            }
            val (head, empty) = if (inner.endsWith("/")) inner.substring(0, inner.length - 1) to true else inner to false
            val (name, attrs) = parseAttrs(head)
            if (name.isEmpty()) throw XmlError(line, "empty tag name")
            if (!empty && !name.contains(':') && name in RAW_TEXT_ELEMENTS) {
                rawUntil = name to 1
            }
            return (if (empty) Event.Empty(name, attrs) else Event.Start(name, attrs)) to consumed
        }

        /** Consume a raw-text element's body up to its matching close tag. */
        private fun readRawText(name: String, depthIn: Int): Event? {
            var depth = depthIn
            rawUntil = null
            val close = "</$name"
            val closeFolded = close.lowercase()
            val open = "<$name"
            val openFolded = open.lowercase()
            val bodyStart = pos
            var search = pos
            while (true) {
                val found = src.indexOf('<', search)
                if (found < 0) {
                    // No close tag: the document is truncated. Take the rest
                    // as the body rather than erroring.
                    line += src.substring(bodyStart).count { it == '\n' }
                    val body = src.substring(bodyStart)
                    pos = src.length
                    finished = true
                    return if (body.isNotEmpty()) Event.Raw(name, body) else null
                }
                val restLower = src.substring(found).lowercase()
                if (restLower.startsWith(closeFolded)) {
                    depth -= 1
                    if (depth == 0) {
                        val body = src.substring(bodyStart, found)
                        val gt = src.indexOf('>', found)
                        val end = if (gt >= 0) gt + 1 else src.length
                        line += src.substring(bodyStart, end).count { it == '\n' }
                        pos = end
                        return if (body.isNotEmpty()) Event.Raw(name, body) else null
                    }
                    search = found + 1
                } else if (restLower.startsWith(openFolded)) {
                    depth += 1
                    search = found + 1
                } else {
                    search = found + 1
                }
            }
        }

        /** Split `name attr="v" attr2='v2'` into the name and decoded attributes. */
        private fun parseAttrs(head: String): Pair<String, List<Attr>> {
            var nameEnd = head.length
            for ((i, c) in head.withIndex()) {
                if (c.isWhitespace()) {
                    nameEnd = i
                    break
                }
            }
            val name = head.substring(0, nameEnd)
            val attrs = ArrayList<Attr>()
            var rest = head.substring(nameEnd)
            while (true) {
                rest = rest.trimStart()
                if (rest.isEmpty()) return name to attrs
                val eq = rest.indexOf('=').let { if (it < 0) throw XmlError(line, "attribute without a value") else it }
                val attrName = rest.substring(0, eq).trim()
                if (attrName.isEmpty() || attrName.any { it.isWhitespace() }) {
                    throw XmlError(line, "malformed attribute name")
                }
                rest = rest.substring(eq + 1).trimStart()
                val quote = rest.firstOrNull()?.takeIf { it == '"' || it == '\'' }
                    ?: throw XmlError(line, "unquoted attribute value")
                val body = rest.substring(1)
                val close = body.indexOf(quote).let { if (it < 0) throw XmlError(line, "unterminated attribute value") else it }
                val raw = body.substring(0, close)
                val value = decodeEntities(raw) ?: throw XmlError(line, "invalid entity in attribute value")
                attrs.add(Attr(attrName, value))
                rest = body.substring(close + 1)
            }
        }
    }

    /**
     * Decode the five named entities, the numeric character references and
     * the HTML5 named set XHTML books actually use. `null` only for a
     * numeric reference that names no character — a bare `&` in prose and
     * an unknown named entity are literal text, not an error.
     */
    fun decodeEntities(s: String): String? {
        if (!s.contains('&')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i)
            if (semi < 0) {
                // A bare `&` with no terminator is literal text.
                out.append('&')
                i++
                continue
            }
            val entity = s.substring(i + 1, semi)
            if (entity.startsWith("#")) {
                val num = entity.substring(1)
                val code = if (num.length > 1 && (num[0] == 'x' || num[0] == 'X')) {
                    num.substring(1).toIntOrNull(16)
                } else {
                    num.toIntOrNull()
                }
                val ch = code?.toCodePointOrNull() ?: return null
                out.append(ch)
                i = semi + 1
                continue
            }
            val ch = namedEntity(entity)
            if (ch != null) {
                out.append(ch)
                i = semi + 1
            } else {
                // Unknown named entity: keep it literally.
                out.append('&').append(entity).append(';')
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun Int.toCodePointOrNull(): String? {
        if (this < 0) return null
        if (!Character.isValidCodePoint(this)) return null
        if (this in 0xD800..0xDFFF) return null
        return String(Character.toChars(this))
    }

    /** The HTML5 named entities XHTML books actually use, beyond XML's five. */
    private fun namedEntity(entity: String): String? = when (entity) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        "hellip" -> "…"
        "mdash" -> "—"
        "ndash" -> "–"
        "lsquo" -> "‘"
        "rsquo" -> "’"
        "ldquo" -> "“"
        "rdquo" -> "”"
        "bull" -> "•"
        "dagger" -> "†"
        "permil" -> "‰"
        "lsaquo" -> "‹"
        "rsaquo" -> "›"
        "oline" -> "‾"
        "frasl" -> "/"
        "euro" -> "€"
        "trade" -> "™"
        "copy" -> "©"
        "reg" -> "®"
        "deg" -> "°"
        "plusmn" -> "±"
        "times" -> "×"
        "divide" -> "÷"
        "frac12" -> "½"
        "frac14" -> "¼"
        "frac34" -> "¾"
        "sup2" -> "²"
        "sup3" -> "³"
        "micro" -> "µ"
        "para" -> "¶"
        "sect" -> "§"
        "middot" -> "·"
        "cent" -> "¢"
        "pound" -> "£"
        "yen" -> "¥"
        "curren" -> "¤"
        "iexcl" -> "¡"
        "iquest" -> "¿"
        "laquo" -> "«"
        "raquo" -> "»"
        "szlig" -> "ß"
        "agrave" -> "à"
        "aacute" -> "á"
        "acirc" -> "â"
        "atilde" -> "ã"
        "auml" -> "ä"
        "aring" -> "å"
        "aelig" -> "æ"
        "ccedil" -> "ç"
        "egrave" -> "è"
        "eacute" -> "é"
        "ecirc" -> "ê"
        "euml" -> "ë"
        "igrave" -> "ì"
        "iacute" -> "í"
        "icirc" -> "î"
        "iuml" -> "ï"
        "ntilde" -> "ñ"
        "ograve" -> "ò"
        "oacute" -> "ó"
        "ocirc" -> "ô"
        "otilde" -> "õ"
        "ouml" -> "ö"
        "oslash" -> "ø"
        "ugrave" -> "ù"
        "uacute" -> "ú"
        "ucirc" -> "û"
        "uuml" -> "ü"
        "yacute" -> "ý"
        "yuml" -> "ÿ"
        "Agrave" -> "À"
        "Aacute" -> "Á"
        "Acirc" -> "Â"
        "Atilde" -> "Ã"
        "Auml" -> "Ä"
        "Aring" -> "Å"
        "AElig" -> "Æ"
        "Ccedil" -> "Ç"
        "Egrave" -> "È"
        "Eacute" -> "É"
        "Ecirc" -> "Ê"
        "Euml" -> "Ë"
        "Igrave" -> "Ì"
        "Iacute" -> "Í"
        "Icirc" -> "Î"
        "Iuml" -> "Ï"
        "Ntilde" -> "Ñ"
        "Ograve" -> "Ò"
        "Oacute" -> "Ó"
        "Ocirc" -> "Ô"
        "Otilde" -> "Õ"
        "Ouml" -> "Ö"
        "Oslash" -> "Ø"
        "Ugrave" -> "Ù"
        "Uacute" -> "Ú"
        "Ucirc" -> "Û"
        "Uuml" -> "Ü"
        "Yacute" -> "Ý"
        else -> null
    }
}
