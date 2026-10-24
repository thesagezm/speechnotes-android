package com.speechnotes.app.logic

/**
 * EPUB chapter XHTML → speech text. Ported from XhtmlText.swift
 * (speechnotes-ios): named-entity pre-pass (a strict XML parser aborts on
 * the first `&nbsp;`), block-tag paragraph splitting, footnote/sup/ruby
 * scrubbing via an element stack, table cells comma-joined, images spoken
 * via alt text.
 */
object XhtmlText {

    val namedEntities: Map<String, String> = mapOf(
        "nbsp" to "\u00A0", "iexcl" to "¡", "cent" to "¢", "pound" to "£",
        "curren" to "¤", "yen" to "¥", "brvbar" to "¦", "sect" to "§",
        "uml" to "¨", "copy" to "©", "ordf" to "ª", "laquo" to "«",
        "not" to "¬", "shy" to "\u00AD", "reg" to "®", "macr" to "¯",
        "deg" to "°", "plusmn" to "±", "sup2" to "²", "sup3" to "³",
        "acute" to "´", "micro" to "µ", "para" to "¶", "middot" to "·",
        "cedil" to "¸", "sup1" to "¹", "ordm" to "º", "raquo" to "»",
        "frac14" to "¼", "frac12" to "½", "frac34" to "¾", "iquest" to "¿",
        "times" to "×", "divide" to "÷", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "sbquo" to "‚",
        "bdquo" to "„", "lsaquo" to "‹", "rsaquo" to "›", "bull" to "•",
        "dagger" to "†", "Dagger" to "‡", "permil" to "‰", "prime" to "′",
        "Prime" to "″", "euro" to "€", "trade" to "™",
        "aacute" to "á", "agrave" to "à", "acirc" to "â", "auml" to "ä",
        "aring" to "å", "aelig" to "æ", "ccedil" to "ç", "eacute" to "é",
        "egrave" to "è", "ecirc" to "ê", "euml" to "ë", "iacute" to "í",
        "igrave" to "ì", "icirc" to "î", "ntilde" to "ñ", "oacute" to "ó",
        "ograve" to "ò", "ocirc" to "ô", "ouml" to "ö", "oslash" to "ø",
        "uacute" to "ú", "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü",
        "szlig" to "ß", "OElig" to "Œ", "oelig" to "œ", "Scaron" to "Š",
        "scaron" to "š", "Yuml" to "Ÿ", "fnof" to "ƒ", "circ" to "ˆ",
        "tilde" to "˜", "ensp" to "\u2002", "emsp" to "\u2003",
        "thinsp" to "\u2009", "zwnj" to "\u200C", "zwj" to "\u200D",
        "lrm" to "\u200E", "rlm" to "\u200F",
    )

    private val xmlPredefined = setOf("amp", "lt", "gt", "quot", "apos")

    /** Rewrites `&name;` references the XML parser would reject. */
    fun replacingNamedEntities(xhtml: String): String {
        if (!xhtml.contains('&')) return xhtml
        val out = StringBuilder(xhtml.length)
        var i = 0
        while (i < xhtml.length) {
            val c = xhtml[i]
            if (c == '&') {
                var j = i + 1
                val name = StringBuilder()
                var closed = false
                while (j < xhtml.length && name.length < 32) {
                    val cj = xhtml[j]
                    if (cj == ';') { closed = true; break }
                    if (!cj.isLetterOrDigit()) break
                    name.append(cj)
                    j++
                }
                if (closed) {
                    if (name.toString() in xmlPredefined) out.append("&").append(name).append(';')
                    else out.append(namedEntities[name.toString()] ?: "")
                    i = j + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Full-fidelity extraction: plain text PLUS whether the parse completed. */
    fun extract(xhtml: String): Pair<String, Boolean> {
        val prepared = replacingNamedEntities(xhtml)
        val delegate = Delegate()
        val completed = try {
            val factory = javax.xml.parsers.SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = delegate
            reader.parse(org.xml.sax.InputSource(java.io.StringReader(prepared)))
            true
        } catch (e: Exception) {
            false
        }
        return SpeechSanitizer.clean(delegate.paragraphText) to completed
    }

    fun plainText(xhtml: String): String = extract(xhtml).first

    // MARK: - SAX delegate

    private class Delegate : org.xml.sax.ContentHandler {
        private val paragraphs = ArrayList<String>()
        private val buffer = StringBuilder()
        private var skipDepth = 0
        private var cellDepth = 0
        private val openElements = ArrayList<Pair<String, Boolean>>() // name → scrubs
        private var scrubCount = 0

        val paragraphText: String
            get() {
                flush()
                return paragraphs.joinToString("\n\n")
            }

        private fun flush() {
            val text = Regex("\\s+").replace(buffer.toString(), " ").trim()
            if (text.isNotEmpty()) paragraphs.add(text)
            buffer.clear()
        }

        private fun handleStartElement(qName: String) {
            val name = qName.substringAfterLast(':')
            if (name in skipTags) { skipDepth++; return }
            if (name == "aside") {
                if (isFootnoteAside(currentAttributes)) {
                    openElements.add(name to true); scrubCount++; return
                }
            } else if (name == "sup" || name == "rt") {
                openElements.add(name to true); scrubCount++; return
            }
            if (name !in voidElements) openElements.add(name to false)
            if (skipDepth > 0 || scrubCount > 0) return
            when {
                name == "td" || name == "th" -> {
                    cellDepth++
                    val text = Regex("\\s+").replace(buffer.toString(), " ").trim()
                    if (text.isNotEmpty()) { buffer.clear(); buffer.append(text).append(", ") }
                }
                name == "br" -> {
                    if (cellDepth > 0) buffer.append(", ") else flush()
                }
                name in blockTags -> flush()
                name == "img" -> {
                    val alt = currentAttributes?.getValue("alt")
                    if (!alt.isNullOrEmpty()) buffer.append(" (image: $alt) ") else buffer.append(" ")
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch == null) return
            if (skipDepth > 0 || scrubCount > 0) return
            buffer.appendRange(ch, start, start + length)
        }

        private fun handleEndElement(qName: String) {
            val name = qName.substringAfterLast(':')
            if (name in skipTags) { skipDepth = maxOf(0, skipDepth - 1); return }
            val idx = openElements.indexOfLast { it.first == name }
            if (idx >= 0) {
                val popped = openElements.removeAt(idx)
                if (popped.second) scrubCount = maxOf(0, scrubCount - 1)
            }
            if (scrubCount > 0) return
            if (name == "td" || name == "th") cellDepth = maxOf(0, cellDepth - 1)
            if (skipDepth > 0) return
            if (name in blockTags || (name == "br" && cellDepth == 0)) flush()
        }

        // Current element attributes — captured at startElement.
        var currentAttributes: org.xml.sax.Attributes? = null

        override fun startPrefixMapping(prefix: String?, uri: String?) {}
        override fun endPrefixMapping(prefix: String?) {}
        override fun ignorableWhitespace(ch: CharArray?, start: Int, length: Int) {}
        override fun processingInstruction(target: String?, data: String?) {}
        override fun skippedEntity(name: String?) {}
        override fun setDocumentLocator(locator: org.xml.sax.Locator?) {}
        override fun startDocument() {}
        override fun endDocument() {}

        override fun startElement(uri: String?, localName: String?, qName: String?, atts: org.xml.sax.Attributes?) {
            currentAttributes = atts
            handleStartElement(qName ?: localName ?: "")
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            handleEndElement(qName ?: localName ?: "")
        }

        companion object {
            val blockTags = setOf(
                "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote",
                "div", "tr", "dd", "dt", "figcaption", "pre", "section", "article",
            )
            val skipTags = setOf("head", "style", "script")
            val voidElements = setOf(
                "br", "img", "hr", "meta", "link", "input", "area", "base",
                "col", "embed", "source", "track", "wbr",
            )
            val footnoteTypes = setOf(
                "footnote", "endnote", "rearnote", "marginnote", "note", "footnotes", "endnotes",
            )

            fun isFootnoteAside(atts: org.xml.sax.Attributes?): Boolean {
                if (atts == null) return false
                val type = (atts?.getValue("epub:type") ?: atts?.getValue("type") ?: "")
                return type.split(" ").any { it in footnoteTypes }
            }
        }
    }
}
