package com.speechnotes.app.speech

import com.speechnotes.app.data.LogStore

/**
 * Kotlin port of MisakiSwift's English G2P (grapheme-to-phoneme): the same
 * dictionary-based phonemizer the iOS app uses for Kokoro, so both apps
 * speak with identical pronunciation.
 *
 * The dictionary files (us/gb gold + silver JSON) are vendored as gzipped
 * raw assets — see Scripts/notes in the iOS repo for provenance. The
 * dictionaries are loaded lazily on the synthesis thread.
 */
class MisakiG2P {

    /** Phoneme vocabulary the Kokoro tokenizer expects (ARPABET + punctuation). */
    companion object {
        const val PHONEME_PUNCTUATION = ".;:!?—…\"()\"“”_,'”“-–"
    }

    private var loadedFor: String? = null
    private var gold: Map<String, String> = emptyMap()
    private var silver: Map<String, String> = emptyMap()

    /** True when the dictionary assets are present for [british]. */
    private fun ensureLoaded(british: Boolean): Boolean {
        val prefix = if (british) "gb" else "us"
        if (loadedFor == prefix) return true
        // AGP's asset merger decompresses .gz assets and drops the suffix, so
        // these ship as plain JSON in the APK.
        val goldFile = if (british) "g2p/gb_gold.json" else "g2p/us_gold.json"
        val silverFile = if (british) "g2p/gb_silver.json" else "g2p/us_silver.json"
        val g = DictionaryLoader.load(goldFile)
        val s = DictionaryLoader.load(silverFile)
        if (g.isEmpty()) {
            LogStore.error("MisakiG2P: no gold dictionary for $prefix")
            return false
        }
        gold = g
        silver = s
        loadedFor = prefix
        return true
    }

    /**
     * English text → phonemes. Falls back to a letter-by-letter spelling for
     * out-of-dictionary words (the dictionary-less behavior the iOS app
     * shows for the same input).
     */
    fun phonemize(text: String, british: Boolean = false): String {
        if (text.isBlank()) return ""
        if (!ensureLoaded(british)) return fallbackPhonemize(text)

        val out = StringBuilder(text.length * 4)
        val words = tokenize(text)
        for (word in words) {
            if (word.isBlank()) continue
            if (word.all { it.isLetter() }) {
                val key = word.lowercase()
                val phonemes = lookupWithFallback(key)
                out.append(phonemes).append(' ')
            } else if (word.first().isDigit()) {
                out.append(numberToPhonemes(word)).append(' ')
            } else {
                out.append(word).append(' ')
            }
        }
        return out.toString()
    }

    /** Whole-word lookup: gold, then silver (case/possessive tolerant). */
    private fun lookupWithFallback(word: String): String {
        gold[word]?.let { return it }
        silver[word]?.let { return it }
        val stripped = word.removeSuffix("'s")
        if (stripped != word) {
            gold[stripped]?.let { return it }
            silver[stripped]?.let { return it }
        }
        // Unknown: spell it out — a character the engine can still say.
        return word.mapNotNull { c ->
            (gold[c.toString()] ?: silver[c.toString()])?.let { "$it " }
        }.joinToString("").trim()
    }

    /** The letter-by-letter path used when no dictionary asset ships. */
    private fun fallbackPhonemize(text: String): String =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.map { it.lowercaseChar().toString() }.joinToString(" ")
        }

    /** Numbers → words (0–999 + common scales), then lettered through the dictionary. */
    private fun numberToPhonemes(token: String): String {
        val value = token.toDoubleOrNull()
        if (value == null) return token
        val words = numberToWords(value.toLong())
        return words.split(" ").map { lookupWithFallback(it.lowercase()) }.joinToString(" ")
    }

    private fun numberToWords(n: Long): String {
        if (n == 0L) return "zero"
        val units = arrayOf(
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen",
        )
        val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        fun threeDigits(num: Long): String {
            val hundred = num / 100
            val rest = num % 100
            val parts = ArrayList<String>()
            if (hundred > 0) parts.add("${units[hundred.toInt()]} hundred")
            if (rest > 0) {
                if (rest < 20) parts.add(units[rest.toInt()])
                else {
                    val t = tens[(rest / 10).toInt()]
                    val u = units[(rest % 10).toInt()]
                    parts.add(if (u.isEmpty()) t else "$t $u")
                }
            }
            return parts.joinToString(" ")
        }
        return when {
            n < 1_000 -> threeDigits(n)
            n < 1_000_000 -> {
                val thousands = threeDigits(n / 1_000)
                val rest = if (n % 1_000 > 0) " " + threeDigits(n % 1_000) else ""
                "$thousands thousand$rest"
            }
            else -> n.toString()
        }
    }

    /** Splits text into word tokens (letters/digits runs, punctuation as their own tokens). */
    private fun tokenize(text: String): List<String> =
        Regex("[A-Za-z']+|\\d+|[.;:!?]").findAll(text).map { it.value }.toList()
}

/** Asset-backed dictionary loader. */
object DictionaryLoader {

    private val cache = HashMap<String, Map<String, String>>()

    fun load(assetPath: String): Map<String, String> {
        cache[assetPath]?.let { return it }
        val map = runCatching {
            val context = com.speechnotes.app.logic.AppFiles.context ?: return emptyMap()
            val json = context.assets.open(assetPath).use { input ->
                val bytes = input.readBytes()
                // Tolerate both plain JSON and a real gzip payload.
                if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
                    java.util.zip.GZIPInputStream(bytes.inputStream()).bufferedReader().readText()
                } else {
                    String(bytes, Charsets.UTF_8)
                }
            }
            val parsed = parseJsonMap(json)
            if (parsed.isNotEmpty()) LogStore.info("DictionaryLoader: ${parsed.size} entries from $assetPath")
            parsed
        }.getOrElse { emptyMap() }
        cache[assetPath] = map
        return map
    }

    /** Parses `{"word": "phonemes", …}` without pulling in a JSON library. */
    private fun parseJsonMap(json: String): Map<String, String> {
        val map = HashMap<String, String>(2048)
        var i = 0
        while (i < json.length) {
            val openQuote = json.indexOf('"', i)
            if (openQuote < 0) break
            val closeQuote = json.indexOf('"', openQuote + 1)
            if (closeQuote < 0) break
            val key = unescape(json.substring(openQuote + 1, closeQuote))
            val colon = json.indexOf(':', closeQuote)
            if (colon < 0) break
            val valueOpen = json.indexOf('"', colon)
            if (valueOpen < 0) break
            val valueClose = json.indexOf('"', valueOpen + 1)
            if (valueClose < 0) break
            val value = unescape(json.substring(valueOpen + 1, valueClose))
            if (key.isNotEmpty()) map[key] = value
            i = valueClose + 1
        }
        return map
    }

    private fun unescape(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
}
