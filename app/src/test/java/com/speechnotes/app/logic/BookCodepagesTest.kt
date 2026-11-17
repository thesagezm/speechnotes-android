package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/** The codepage pins from sn-doc codepages.rs's tests — here they pin the
 *  PLATFORM charsets against the letters the Rust tables carry. */
class BookCodepagesTest {

    @Test
    fun cyrillicCodepagesMapTheAlphabet() {
        // cp1251: 0xE0 = а, the alphabet's first letter, 0xC0 = А.
        assertEquals("привет", BookCodepages.decodeCp1251(byteArrayOf(0xEF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte())))
        assertEquals("Привет", BookCodepages.decodeCp1251(byteArrayOf(0xCF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte())))
        // ё at 0xB8, Ё at 0xA8 — the two letters a wrong table turns into
        // the wrong letter.
        assertEquals("ё", BookCodepages.decodeCp1251(byteArrayOf(0xB8.toByte())))
        assertEquals("Ё", BookCodepages.decodeCp1251(byteArrayOf(0xA8.toByte())))
    }

    @Test
    fun westernPunctuationSurvives() {
        // The 0x80–0x9F block is the punctuation RTF and Windows documents
        // carry: 0x92 ’ 0x93 “ 0x94 ” 0x97 — 0xA0 a no-break space.
        assertEquals("’", BookCodepages.decodeCp1252(byteArrayOf(0x92.toByte())))
        assertEquals("“”—", BookCodepages.decodeCp1252(byteArrayOf(0x93.toByte(), 0x94.toByte(), 0x97.toByte())))
        assertEquals(Char(0xA0), BookCodepages.decodeCp1252(byteArrayOf(0xA0.toByte())))
        // The five undefined positions keep their Latin-1 glyph rather
        // than becoming a replacement character.
        assertEquals(Char(0x81), BookCodepages.decodeCp1252(byteArrayOf(0x81.toByte())))
    }

    @Test
    fun centralEuropeanMapsItsAccentedLetters() {
        // ł (0xB3), ż (0xBF) — a Polish sentence's whole sound.
        assertEquals("ByBł", BookCodepages.decodeCp1250(byteArrayOf(0x42.toByte(), 0x79.toByte(), 0x42.toByte(), 0xB3.toByte())))
        assertEquals("żc", BookCodepages.decodeCp1250(byteArrayOf(0xBF.toByte(), 0x63.toByte())))
    }

    @Test
    fun greekAndTurkishLetters() {
        assertEquals("1", BookCodepages.decodeNamed("windows-1253", byteArrayOf(0x31)))
        assertEquals("α", BookCodepages.decodeNamed("windows-1253", byteArrayOf(0xE1.toByte())))
        assertEquals("ı", BookCodepages.decodeNamed("windows-1254", byteArrayOf(0xFD.toByte())))
        assertEquals("ş", BookCodepages.decodeNamed("windows-1254", byteArrayOf(0xFE.toByte())))
    }

    @Test
    fun koi8rIsTheOtherCyrillic() {
        // KOI8-R's letters sit AT OTHER BYTE POSITIONS than cp1251's:
        // 0xF0 = П here, 0xEF there.
        assertEquals("П", BookCodepages.decodeNamed("koi8-r", byteArrayOf(0xF0.toByte())))
        assertEquals("п", BookCodepages.decodeNamed("koi8-r", byteArrayOf(0xD0.toByte())))
    }

    @Test
    fun asciiPassesThroughEveryCodepage() {
        val ascii = "Chapter 7: the letter".toByteArray(Charsets.UTF_8)
        assertEquals("Chapter 7: the letter", BookCodepages.decodeNamed("windows-1250", ascii))
        assertEquals("Chapter 7: the letter", BookCodepages.decodeNamed("windows-1251", ascii))
        assertEquals("Chapter 7: the letter", BookCodepages.decodeNamed("windows-1252", ascii))
        assertEquals("Chapter 7: the letter", BookCodepages.decodeNamed("koi8-r", ascii))
    }

    @Test
    fun aNamedEncodingResolvesToItsDecoder() {
        val cyrillic = byteArrayOf(0xEF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte())
        assertEquals("привет", BookCodepages.decodeNamed("windows-1251", cyrillic))
        assertEquals("привет", BookCodepages.decodeNamed("cp1251", cyrillic))
        assertEquals("héllo", BookCodepages.decodeNamed("utf-8", "héllo".toByteArray(Charsets.UTF_8)))
        assertEquals("é", BookCodepages.decodeNamed("iso-8859-1", byteArrayOf(0xE9.toByte())))
        // An encoding this crate does not carry (UTF-16, EBCDIC) is null:
        // the caller falls back to UTF-8, which never fails.
        assertEquals(null, BookCodepages.decodeNamed("utf-16", byteArrayOf(1, 2, 3)))
        assertEquals(null, BookCodepages.decodeNamed("shift_jis", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun anUndefinedByteKeepsItsLatin1Glyph() {
        // Windows-1252's five undefined positions have no meaning; the
        // decode keeps the C1 control code rather than one replacement
        // character.
        assertEquals(Char(0x8D), BookCodepages.decodeCp1252(byteArrayOf(0x8D.toByte())))
        assertEquals(
            Char(0x81) + Char(0x8F) + Char(0x90) + Char(0x9D),
            BookCodepages.decodeCp1252(byteArrayOf(0x81.toByte(), 0x8F.toByte(), 0x90.toByte(), 0x9D.toByte()))
        )
    }
}
