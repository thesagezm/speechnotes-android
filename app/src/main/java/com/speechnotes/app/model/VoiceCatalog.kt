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

    /** Descriptors for an engine: kokoro for kokoro/kokoroSmall, supertonic for supertonic. */
    fun descriptors(engine: String): List<VoiceDescriptor> = when (engine) {
        "supertonic" -> supertonic
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
