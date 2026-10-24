package com.speechnotes.app.logic

/**
 * Stable, process-independent hashing. Ported from TextHash.swift
 * (speechnotes-ios): FNV-1a 64-bit, used to detect whether the text a
 * bookmark was taken from is still the text in the note.
 */
object TextHash {
    fun of(text: String): Long {
        var hash = 0xcbf29ce484222325UL.toLong()
        for (c in text) {
            hash = hash xor c.code.toLong()
            hash *= 0x100000001b3UL.toLong()
        }
        return hash
    }
}
