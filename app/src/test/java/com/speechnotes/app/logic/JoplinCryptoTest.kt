package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JoplinCryptoTest — the parity contract for the encryption vault.
 *
 * The fixed envelopes below were sealed by an INDEPENDENT implementation of
 * Joplin's format (Python: hashlib.pbkdf2_hmac + cryptography.AESGCM) using
 * the same construction Joplin/iOS use — they prove this port reads bytes
 * that were not written by itself.
 */
class JoplinCryptoTest {

    private val masterHex = (0 until 256).joinToString("") { "%02x".format(it) }
    private val keyId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"

    // Python-sealed StringV1 over "Speechnotes interop —Здравствуйте 🌍 ok",
    // master key = 00…ff, data key = PBKDF2-HMAC-SHA512(masterKeyHex, salt, 3).
    private val interopStringEnvelope =
        "JED010000220aa1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d60000d7" +
            "{\"salt\":\"stnu0+TbhnvEUUmOO9cV4dNDBfiFoKAZa/nITTacVIg=\"," +
            "\"iv\":\"AAECAwQFBgcICQoL\"," +
            "\"ct\":\"aZ6th0FMOs0cKsOPEF2DA0Rlx6o61GnvChZkR90wZPKhsiBqt57THPz6WVxKW5fBHX8Jb+89W2tpCVFtyzDUjtZfXXpAz19c6HGIw3iboc/YzZh/k7KSIY0LWPA99w==\"}"

    // Python-sealed KeyV1: the same master key wrapped with "correct horse
    // battery", 220 000 PBKDF2-HMAC-SHA512 iterations.
    private val interopKeyEnvelope =
        "JED0100002208a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d60001c3" +
            "{\"salt\":\"stnu0+TbhnvEUUmOO9cV4dNDBfiFoKAZa/nITTacVIg=\"," +
            "\"iv\":\"AAECAwQFBgcICQoL\"," +
            "\"ct\":\"dFGxmKomN8Qln0jLUbfo+NteYdlD7SW9A9z6zzcJrQuF4OVg24bKe9BKpxhSKxp7kjLCk/+lE83WOGtoB/aOqifLoHXQpud86iP8ORX6ec8oFELuIgkbPn9b80QhVEpayi7vLLO9HUHkQOtPVeNnEG1bfqykKQ7Edlgmw82A9+IGmm8wRHKRFKbKQPgtpkKHfOxFjhaAd5IzUNqpWKgoTJonSLZwo+dr7XA5VnekhjvwlgLA1aMSXScKzKFidfr1VoCTyZFZIV3kV73kbS+TcaMNGc0Uo6sCZtN+415xVcY2aFQwtSeNnl1XO8L3k5PdxAaofomkASvowmYjuROH+U02yoyOzyeUdWeAaaMPntg=\"}"

    // Format-legal two-chunk StringV1 of the same text (each chunk identical,
    // so the halves concatenate back to the original bytes ×2).
    private val interopTwoChunkEnvelope =
        "JED010000220aa1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d60000d7" +
            "{\"salt\":\"stnu0+TbhnvEUUmOO9cV4dNDBfiFoKAZa/nITTacVIg=\"," +
            "\"iv\":\"AAECAwQFBgcICQoL\"," +
            "\"ct\":\"aZ6th0FMOs0cKsOPEF2DA0Rlx6o61GnvChZkR90wZPKhsiBqt57THPz6WVxKW5fBHX8Jb+89W2tpCVFtyzDUjtZfXXpAz19c6HGIw3iboc/YzZh/k7KSIY0LWPA99w==\"}" +
            "0000d7" +
            "{\"salt\":\"stnu0+TbhnvEUUmOO9cV4dNDBfiFoKAZa/nITTacVIg=\"," +
            "\"iv\":\"AAECAwQFBgcICQoL\"," +
            "\"ct\":\"aZ6th0FMOs0cKsOPEF2DA0Rlx6o61GnvChZkR90wZPKhsiBqt57THPz6WVxKW5fBHX8Jb+89W2tpCVFtyzDUjtZfXXpAz19c6HGIw3iboc/YzZh/k7KSIY0LWPA99w==\"}"

    @Test
    fun `decrypts an independently sealed StringV1 envelope`() {
        val text = JoplinCrypto.decryptString(interopStringEnvelope, masterHex)
        assertEquals("Speechnotes interop —Здравствуйте 🌍 ok", text)
    }

    @Test
    fun `decrypts an independently sealed KeyV1 envelope to the master key`() {
        val master = JoplinCrypto.decryptMasterKey(interopKeyEnvelope, "correct horse battery")
        assertEquals(masterHex, master)
    }

    @Test
    fun `wrong passphrase on KeyV1 throws BadPassword`() {
        val error = runCatching {
            JoplinCrypto.decryptMasterKey(interopKeyEnvelope, "wrong passphrase entirely")
        }.exceptionOrNull()
        assertTrue(error is JoplinCrypto.CryptoError.BadPassword)
    }

    @Test
    fun `wrong master key on StringV1 throws BadPassword`() {
        val other = JoplinCrypto.makeMasterKey()
        val error = runCatching {
            JoplinCrypto.decryptString(interopStringEnvelope, other)
        }.exceptionOrNull()
        assertTrue(error is JoplinCrypto.CryptoError.BadPassword)
    }

    @Test
    fun `reads a multi-chunk StringV1 envelope by concatenating chunks`() {
        val text = JoplinCrypto.decryptString(interopTwoChunkEnvelope, masterHex)
        val expected = "Speechnotes interop —Здравствуйте 🌍 ok"
        assertEquals(expected + expected, text)
    }

    @Test
    fun `round-trips StringV1 including astral-plane text`() {
        val text = "Round trip: Türkçe, 日本語, 😀 (astral), tab\t newline\n"
        val envelope = JoplinCrypto.encryptString(text, masterHex, keyId)
        assertEquals(text, JoplinCrypto.decryptString(envelope, masterHex))
    }

    @Test
    fun `round-trips KeyV1 wrap and unwrap`() {
        val wrapped = JoplinCrypto.encryptMasterKey(masterHex, "pass-phrase-8", keyId)
        assertEquals(masterHex, JoplinCrypto.decryptMasterKey(wrapped, "pass-phrase-8"))
    }

    @Test
    fun `each seal produces a fresh salt and IV`() {
        val a = JoplinCrypto.encryptString("same text", masterHex, keyId)
        val b = JoplinCrypto.encryptString("same text", masterHex, keyId)
        assertTrue(a != b)
        assertEquals("same text", JoplinCrypto.decryptString(a, masterHex))
        assertEquals("same text", JoplinCrypto.decryptString(b, masterHex))
    }

    @Test
    fun `header reports the method and master key id`() {
        val (method, id, chunksStart) = JoplinCrypto.decodeHeader(interopStringEnvelope)
        assertEquals(JoplinCrypto.Method.STRING_V1, method)
        assertEquals(keyId, id)
        assertEquals(interopStringEnvelope.length, chunksStart + 0x0d7 + 6)
        assertEquals(keyId, JoplinCrypto.masterKeyId(interopStringEnvelope))
        assertNull(JoplinCrypto.masterKeyId("not an envelope"))
    }

    @Test
    fun `isEncrypted detects envelopes only`() {
        assertTrue(JoplinCrypto.isEncrypted(interopStringEnvelope))
        assertTrue(JoplinCrypto.isEncrypted("JED02…"))
        assertFalse(JoplinCrypto.isEncrypted("[{\"id\":\"note\"}]"))
    }

    @Test
    fun `malformed envelopes throw MalformedEnvelope`() {
        val badPrefix = interopStringEnvelope.replace("JED01", "JED99")
        val badLength = "JED01zzzzzz0a" + "0".repeat(32)
        listOf(badPrefix, badLength).forEach { envelope ->
            val error = runCatching { JoplinCrypto.decryptString(envelope, masterHex) }.exceptionOrNull()
            assertTrue(
                "expected MalformedEnvelope for $envelope, got $error",
                error is JoplinCrypto.CryptoError.MalformedEnvelope || error is JoplinCrypto.CryptoError.UnsupportedMethod,
            )
        }
    }

    @Test
    fun `master key and id have Joplin's shapes`() {
        val master = JoplinCrypto.makeMasterKey()
        assertEquals(512, master.length)
        assertEquals(master, master.lowercase())
        val id = JoplinCrypto.makeMasterKeyId()
        assertEquals(32, id.length)
        // uuid v4 layout: version nibble 4, variant high bits 10.
        assertEquals('4', id[12])
        assertTrue(id[16] in '8'..'b')
    }
}
