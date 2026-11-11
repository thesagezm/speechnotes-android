package com.speechnotes.app.model

import com.speechnotes.app.logic.SentenceChunker
import kotlinx.serialization.Serializable

/**
 * Friendly voice metadata for the Kokoro 28-voice catalog and Supertonic's
 * 10 styles. Ported from VoiceCatalog.swift (speechnotes-ios).
 */
@Serializable
data class VoiceDescriptor(
    /** Engine codename — the persisted value (e.g. "af_heart", "F2"). */
    val id: String,
    val displayName: String,
    val accent: String?,
    val gender: String,
) {
    val subtitle: String
        get() = listOfNotNull(displayName, accent, gender.lowercase().takeIf { it.isNotEmpty() })
            .joinToString(" · ")
}

object VoiceCatalog {

    /** The 28 Kokoro voices, in the official voice-bank order. */
    val kokoroVoices: List<String> = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica",
        "af_kore", "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
        "am_adam", "am_echo", "am_eric", "am_fenfir", "am_liam",
        "am_michael", "am_onyx", "am_puck", "am_santa",
        "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
        "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
    )

    private val irregularNames = mapOf(
        "am_fenfir" to "Fenrir",
        "bf_lily" to "Lily",
        "bm_fable" to "Fable",
    )

    val kokoro: List<VoiceDescriptor> = kokoroVoices.map { codename ->
        val display = irregularNames[codename]
            ?: codename.drop(3).replaceFirstChar { it.uppercase() }
        VoiceDescriptor(
            id = codename,
            displayName = display,
            accent = if (codename.startsWith("a")) "American" else "British",
            gender = if (codename.contains("f_")) "Female" else "Male",
        )
    }

    /** Supertonic's 10 voice styles. */
    val supertonicVoiceIds: List<String> = listOf("M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5")

    val supertonic: List<VoiceDescriptor> = supertonicVoiceIds.map { id ->
        VoiceDescriptor(id = id, displayName = id, accent = null, gender = if (id.startsWith("M")) "Male" else "Female")
    }

    /**
     * The Phonon pack — the hub default voice plus every named en-us/en-gb
     * voice of gradium/phonon-voices (name/accent/gender from its metadata).
     * Ids are the hub voice_id and match iOS VoiceCatalog.phonon verbatim
     * (VoicePickerSheet.swift ported from the same source). The model always
     * speaks English regardless of a voice's design language.
     */
    val phononVoiceIds: List<String> = listOf(
        "default",
        // en-us
        "NbpkqMVS3CJeq2j8", "NIWIOv5vTi5eRMj8", "vjFyp2BcCyTlUmYb",
        "4SZHfMpw-p46Ywgs", "bXwrkHhURL1VFJdG", "nGzMHMPAWGaJFHOo",
        "LMDIYE0Oa377Qh6Z", "40QrMVgAxFiUnP9f", "0zLApsZfVLLNaEGl",
        "_6Aslh2DxfmnRLmP", "qIxlZ7Ei8KvWlyQH", "d9XCyhjo4JhkHF1d",
        // en-gb
        "6PWnV0Nq4wu7RVBT", "GgfEkEJtxZR7gnpy", "uem82D50GRv2Dwma",
        "y9bQqIDnyxwqK01t", "gDT1nz7Ie36ZhL-C", "m9MxpY6IUGXtWp76",
        "X6NceqF285Gr04gb", "YyKcxPG7iIgS3jsK", "zUCxzrOrugTSis03",
        "JOW0vchyswkQUMDS", "dME3IWyZBvmh1n1q", "Zn7g6NxLqXlJb7Qi",
        "gfP44RlHwr2kEwk7",
    )

    val phonon: List<VoiceDescriptor> = listOf(
        VoiceDescriptor(id = "default", displayName = "Default", accent = null, gender = "Neutral"),
        VoiceDescriptor(id = "NbpkqMVS3CJeq2j8", displayName = "Zoey", accent = "American", gender = "Female"),
        VoiceDescriptor(id = "NIWIOv5vTi5eRMj8", displayName = "Juniper", accent = "American", gender = "Female"),
        VoiceDescriptor(id = "vjFyp2BcCyTlUmYb", displayName = "Everly", accent = "American", gender = "Female"),
        VoiceDescriptor(id = "4SZHfMpw-p46Ywgs", displayName = "Harper", accent = "American", gender = "Female"),
        VoiceDescriptor(id = "bXwrkHhURL1VFJdG", displayName = "Hadley", accent = "American", gender = "Female"),
        VoiceDescriptor(id = "nGzMHMPAWGaJFHOo", displayName = "Delia", accent = "American", gender = "Female"),
        VoiceDescriptor(id = "LMDIYE0Oa377Qh6Z", displayName = "Easton", accent = "American", gender = "Male"),
        VoiceDescriptor(id = "40QrMVgAxFiUnP9f", displayName = "Micah", accent = "American", gender = "Male"),
        VoiceDescriptor(id = "0zLApsZfVLLNaEGl", displayName = "Trevor", accent = "American", gender = "Male"),
        VoiceDescriptor(id = "_6Aslh2DxfmnRLmP", displayName = "Russell", accent = "American", gender = "Male"),
        VoiceDescriptor(id = "qIxlZ7Ei8KvWlyQH", displayName = "Preston", accent = "American", gender = "Male"),
        VoiceDescriptor(id = "d9XCyhjo4JhkHF1d", displayName = "Miles", accent = "American", gender = "Male"),
        VoiceDescriptor(id = "6PWnV0Nq4wu7RVBT", displayName = "Maeve", accent = "British", gender = "Female"),
        VoiceDescriptor(id = "GgfEkEJtxZR7gnpy", displayName = "Freya", accent = "British", gender = "Female"),
        VoiceDescriptor(id = "uem82D50GRv2Dwma", displayName = "Pippa", accent = "British", gender = "Female"),
        VoiceDescriptor(id = "y9bQqIDnyxwqK01t", displayName = "Elodie-Rose", accent = "British", gender = "Female"),
        VoiceDescriptor(id = "gDT1nz7Ie36ZhL-C", displayName = "Imogen", accent = "British", gender = "Female"),
        VoiceDescriptor(id = "m9MxpY6IUGXtWp76", displayName = "Lottie", accent = "British", gender = "Female"),
        VoiceDescriptor(id = "gfP44RlHwr2kEwk7", displayName = "Bryony", accent = "British", gender = "Female"),
        VoiceDescriptor(id = "X6NceqF285Gr04gb", displayName = "Alfie", accent = "British", gender = "Male"),
        VoiceDescriptor(id = "YyKcxPG7iIgS3jsK", displayName = "Hugh", accent = "British", gender = "Male"),
        VoiceDescriptor(id = "zUCxzrOrugTSis03", displayName = "Edwin", accent = "British", gender = "Male"),
        VoiceDescriptor(id = "JOW0vchyswkQUMDS", displayName = "Spencer", accent = "British", gender = "Male"),
        VoiceDescriptor(id = "dME3IWyZBvmh1n1q", displayName = "Toby", accent = "British", gender = "Male"),
        VoiceDescriptor(id = "Zn7g6NxLqXlJb7Qi", displayName = "Teddy", accent = "British", gender = "Male"),
    )


    /** 31 languages → display names. */
    val supertonicLanguages: Map<String, String> = linkedMapOf(
        "en" to "English", "ko" to "Korean", "ja" to "Japanese", "ar" to "Arabic",
        "bg" to "Bulgarian", "cs" to "Czech", "da" to "Danish", "de" to "German",
        "el" to "Greek", "es" to "Spanish", "et" to "Estonian", "fi" to "Finnish",
        "fr" to "French", "hi" to "Hindi", "hr" to "Croatian", "hu" to "Hungarian",
        "id" to "Indonesian", "it" to "Italian", "lt" to "Lithuanian", "lv" to "Latvian",
        "nl" to "Dutch", "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian",
        "ru" to "Russian", "sk" to "Slovak", "sl" to "Slovenian", "sv" to "Swedish",
        "tr" to "Turkish", "uk" to "Ukrainian", "vi" to "Vietnamese", "na" to "Neutral",
    )

    fun languageName(code: String): String = supertonicLanguages[code] ?: code

    /** The sentence a voice audition speaks (mentions the voice's own name). */
    fun auditionText(name: String): String =
        "Hi, I'm $name. The quick brown fox jumps over the lazy dog — and I run fully offline, right on this phone."

    /** Descriptors for an engine: kokoro for kokoro/kokoroSmall, phonon for
     *  phonon, supertonic for supertonic. */
    fun descriptors(engine: String): List<VoiceDescriptor> = when (engine) {
        "supertonic" -> supertonic
        "phonon" -> phonon
        else -> kokoro
    }

    fun subtitle(codename: String, engine: String): String =
        descriptors(engine).firstOrNull { it.id == codename }?.subtitle ?: codename

    fun shortName(codename: String, engine: String): String =
        descriptors(engine).firstOrNull { it.id == codename }?.displayName ?: codename

    /** First sentence of text, for previews. */
    fun firstSentence(text: String): String =
        SentenceChunker.firstChunk(text)?.text?.trim() ?: text.trim()
}
