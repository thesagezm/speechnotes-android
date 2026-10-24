package com.speechnotes.app.logic

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Joplin's end-to-end encryption format, ported verbatim from the iOS
 * `JoplinCrypto.swift` (which is itself an independent implementation of
 * Joplin's on-disk format, Joplin v3.2+ EncryptionService). A note sealed
 * here decrypts in Joplin and in the iOS app, and vice versa.
 *
 * The two operations that matter:
 *
 *  * **KeyV1** (method 8) wraps the MASTER KEY with the user's passphrase:
 *    PBKDF2-HMAC-SHA512(passphrase, salt, 220 000 iterations) → AES-256-GCM
 *    over the master key's 256 raw bytes. The 220 000 count is Joplin's
 *    (OWASP-suggested) value and the reason a passphrase unlock is slow on
 *    purpose.
 *  * **StringV1** (method 10) encrypts text with a DATA KEY derived from the
 *    master key: PBKDF2-HMAC-SHA512(masterKeyHex, salt, 3 iterations) →
 *    AES-256-GCM over the UTF-16LE bytes of the text, in 65 536-code-unit
 *    chunks.
 *
 * Both wrap their result in the same envelope:
 *
 *     JED01 <6 hex: metadata length> <metadata> <chunks…>
 *     metadata = <2 hex: method> <32 hex: master key id>
 *     chunk    = <6 hex: length> {"salt":…,"iv":…,"ct":…}
 *
 * The chunk JSON holds the base64 salt (SHA-256 of the session nonce), the
 * 96-bit IV and the ciphertext-with-auth-tag — so an envelope is
 * self-contained and decrypts later, on another platform, with only the
 * master key.
 *
 * `javax.crypto` (JDK) only — no `android.*` here, per the logic/ rule.
 */
object JoplinCrypto {

    /** The encryption methods Joplin defines for this format. */
    enum class Method(val rawValue: Int) {
        /** Wraps a master key with a passphrase. */
        KEY_V1(8),
        /** Encrypts a file/resource payload (base64 plaintext) — accepted on read only. */
        FILE_V1(9),
        /** Encrypts a note's text (UTF-16LE plaintext). */
        STRING_V1(10);

        companion object {
            fun from(rawValue: Int): Method? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    sealed class CryptoError(message: String) : Exception(message) {
        object MalformedEnvelope : CryptoError("The encrypted data is malformed.")
        class UnsupportedMethod(method: Int) : CryptoError("Encryption method $method is not supported.")
        object BadPassword : CryptoError("Wrong passphrase.")
        object BadMasterKey : CryptoError("The master key is not valid.")
    }

    // MARK: - Constants (Joplin's own)

    /** PBKDF2 iteration count for the passphrase → master-key path. */
    const val KEY_V1_ITERATIONS = 220_000

    /** PBKDF2 iteration count for the master-key → data-key path. */
    const val STRING_V1_ITERATIONS = 3

    /** Master key length in bytes (Joplin generates 256). */
    const val MASTER_KEY_BYTES = 256

    /** Joplin's chunk size for StringV1, in UTF-16 code units. */
    const val STRING_V1_CHUNK_UNITS = 65_536

    /** The session nonce is 36 bytes: random (21) + timestamp (7) + counter (8). */
    const val NONCE_LENGTH = 36
    private const val NONCE_COUNTER_LENGTH = 8
    private const val NONCE_TIMESTAMP_LENGTH = 7

    private val random = SecureRandom()

    // MARK: - Public API

    /** A fresh master key: 256 random bytes as 512 lowercase hex characters. */
    fun makeMasterKey(): String = hex(randomBytes(MASTER_KEY_BYTES))

    /** A master key id in Joplin's shape: 32 lowercase hex characters (undashed uuid v4). */
    fun makeMasterKeyId(): String {
        val bytes = randomBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return hex(bytes)
    }

    /** Wraps a master key with a passphrase (Joplin's KeyV1). */
    fun encryptMasterKey(masterKeyHex: String, password: String, masterKeyId: String): String {
        val raw = hexBytes(masterKeyHex) ?: throw CryptoError.BadMasterKey
        if (raw.size != MASTER_KEY_BYTES) throw CryptoError.BadMasterKey
        return sealBytes(
            plaintext = raw,
            password = password.toByteArray(Charsets.UTF_8),
            method = Method.KEY_V1,
            masterKeyId = masterKeyId,
            iterations = KEY_V1_ITERATIONS,
            keyLength = 32,
        )
    }

    /** Unwraps a master key with a passphrase; returns the 512-hex master key. */
    fun decryptMasterKey(envelope: String, password: String): String =
        hex(open(envelope, password.toByteArray(Charsets.UTF_8), Method.KEY_V1, KEY_V1_ITERATIONS))

    /** Encrypts text with the master key (Joplin's StringV1). */
    fun encryptString(text: String, masterKeyHex: String, masterKeyId: String): String =
        sealBytes(
            plaintext = text.toByteArray(Charsets.UTF_16LE),
            password = masterKeyHex.toByteArray(Charsets.UTF_8),
            method = Method.STRING_V1,
            masterKeyId = masterKeyId,
            iterations = STRING_V1_ITERATIONS,
            keyLength = 32,
        )

    /** Decrypts text from an envelope with the master key. */
    fun decryptString(envelope: String, masterKeyHex: String): String {
        val opened = open(envelope, masterKeyHex.toByteArray(Charsets.UTF_8), Method.STRING_V1, STRING_V1_ITERATIONS)
        return String(opened, Charsets.UTF_16LE)
    }

    /** True when the text carries Joplin's encrypted-data header. */
    fun isEncrypted(text: String): Boolean = text.startsWith("JED01") || text.startsWith("JED02")

    /** The master key id an envelope was sealed with, read from the header alone. */
    fun masterKeyId(envelope: String): String? =
        runCatching { decodeHeader(envelope).second }.getOrNull()

    // MARK: - Envelope

    /** `JED01` + 6 hex digits of metadata length + method(2 hex) + key id(32 hex). */
    internal fun encodeHeader(method: Method, masterKeyId: String): String {
        val metadata = "%02x".format(method.rawValue) + masterKeyId
        return "JED01" + "%06x".format(metadata.length) + metadata
    }

    /** → (method, masterKeyId, chunksStart) */
    internal fun decodeHeader(text: String): Triple<Method, String, Int> {
        val identifier = text.take(5)
        if (identifier != "JED01" && identifier != "JED02") throw CryptoError.MalformedEnvelope
        val lengthHex = text.drop(5).take(6)
        val length = lengthHex.toIntOrNull(16) ?: throw CryptoError.MalformedEnvelope
        if (length <= 0) throw CryptoError.MalformedEnvelope
        val metadataStart = 11
        val metadata = text.drop(metadataStart).take(length)
        if (metadata.length != length || metadata.length < 34) throw CryptoError.MalformedEnvelope
        val methodValue = metadata.take(2).toIntOrNull(16) ?: throw CryptoError.MalformedEnvelope
        val method = Method.from(methodValue) ?: throw CryptoError.UnsupportedMethod(methodValue)
        val masterKeyId = metadata.drop(2)
        if (masterKeyId.length != 32) throw CryptoError.MalformedEnvelope
        return Triple(method, masterKeyId, metadataStart + length)
    }

    // MARK: - Seal / open

    private fun sealBytes(
        plaintext: ByteArray,
        password: ByteArray,
        method: Method,
        masterKeyId: String,
        iterations: Int,
        keyLength: Int,
    ): String {
        val nonce = makeNonce()
        val salt = MessageDigest.getInstance("SHA-256").digest(nonce)
        val key = deriveKey(password, salt, iterations, keyLength)
        // KeyV1 wraps the whole key in one chunk; StringV1 splits on
        // 65 536 UTF-16 code units (= 131 072 bytes), Joplin's chunking.
        val chunkBytes = when (method) {
            Method.STRING_V1 -> STRING_V1_CHUNK_UNITS * 2
            else -> maxOf(plaintext.size, 1)
        }

        val out = StringBuilder(encodeHeader(method, masterKeyId))
        var cursor = 0
        while (cursor < plaintext.size) {
            val end = minOf(cursor + chunkBytes, plaintext.size)
            val chunk = plaintext.copyOfRange(cursor, end)
            cursor = end
            val iv = randomBytes(12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            // Java's GCM output is ciphertext || tag — the same layout
            // CryptoKit's sealed box produces once its parts are joined.
            val sealed = cipher.doFinal(chunk)
            val json = chunkJson(salt, iv, sealed)
            out.append("%06x".format(json.toByteArray(Charsets.UTF_8).size))
            out.append(json)
        }
        return out.toString()
    }

    private fun open(envelope: String, password: ByteArray, method: Method, iterations: Int): ByteArray {
        val (headerMethod, _, chunksStart) = decodeHeader(envelope)
        if (headerMethod != method) throw CryptoError.UnsupportedMethod(headerMethod.rawValue)
        val bytes = envelope.toByteArray(Charsets.UTF_8)
        val output = java.io.ByteArrayOutputStream(minOf(bytes.size, 1 shl 20))
        var cursor = chunksStart
        while (cursor < bytes.size) {
            if (cursor + 6 > bytes.size) throw CryptoError.MalformedEnvelope
            val lengthHex = String(bytes, cursor, 6, Charsets.UTF_8)
            val length = lengthHex.toIntOrNull(16) ?: throw CryptoError.MalformedEnvelope
            cursor += 6
            if (cursor + length > bytes.size) throw CryptoError.MalformedEnvelope
            val json = String(bytes, cursor, length, Charsets.UTF_8)
            cursor += length
            output.write(decryptChunk(json, password, iterations))
        }
        return output.toByteArray()
    }

    /** One chunk: `{"salt":…,"iv":…,"ct":…}` → plaintext bytes. */
    private fun decryptChunk(json: String, password: ByteArray, iterations: Int): ByteArray {
        val salt: ByteArray
        val iv: ByteArray
        val ciphertext: ByteArray
        try {
            // The chunk JSON holds three base64 strings in Joplin's fixed key
            // order — base64 cannot contain quotes or escapes, so the fields
            // are extracted directly (no JSON parser needed here).
            salt = Base64.getMimeDecoder().decode(chunkField(json, "salt"))
            iv = Base64.getMimeDecoder().decode(chunkField(json, "iv"))
            ciphertext = Base64.getMimeDecoder().decode(chunkField(json, "ct"))
        } catch (e: Exception) {
            throw CryptoError.MalformedEnvelope
        }
        val key = deriveKey(password, salt, iterations, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        // Java expects the tag appended to the ciphertext — the envelope has it.
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw CryptoError.BadPassword
        }
    }

    /** The value of `"key":"…"` inside a chunk JSON object. */
    private fun chunkField(json: String, key: String): String {
        val marker = "\"$key\":\""
        val start = json.indexOf(marker)
        if (start < 0) throw CryptoError.MalformedEnvelope
        val valueStart = start + marker.length
        val end = json.indexOf('"', valueStart)
        if (end < 0) throw CryptoError.MalformedEnvelope
        return json.substring(valueStart, end)
    }

    /** The chunk JSON, in Joplin's key order (salt, iv, ct). */
    private fun chunkJson(salt: ByteArray, iv: ByteArray, ciphertext: ByteArray): String {
        val b64 = { bytes: ByteArray -> Base64.getEncoder().encodeToString(bytes) }
        return "{\"salt\":\"${b64(salt)}\",\"iv\":\"${b64(iv)}\",\"ct\":\"${b64(ciphertext)}\"}"
    }

    // MARK: - Key derivation

    /**
     * PBKDF2-HMAC-SHA512 via the platform's SecretKeyFactory (OpenSSL-backed
     * on device — the 220 000-iteration path stays in C, off the JVM heap).
     *
     * PBEKeySpec carries the password as chars, which the factory encodes
     * as UTF-8 — so the chars are produced by decoding the password bytes
     * as UTF-8, making the bytes round-trip exactly (the iOS side hashes
     * the raw UTF-8 bytes; Joplin's `crypto.pbkdf2` does the same).
     */
    fun deriveKey(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(String(password, Charsets.UTF_8).toCharArray(), salt, iterations, keyLength * 8)
        return factory.generateSecret(spec).encoded
    }

    // MARK: - Nonce

    /**
     * Joplin's session nonce: 21 random bytes, a 7-byte millisecond
     * timestamp (4 low bytes little-endian, then 3 high bytes) and an
     * 8-byte zero counter. The salt is its SHA-256, so each envelope's data
     * key is unique even under one master key.
     */
    internal fun makeNonce(): ByteArray {
        val nonce = randomBytes(NONCE_LENGTH)
        val timestamp = System.currentTimeMillis().toULong()
        val timestampStart = NONCE_LENGTH - NONCE_COUNTER_LENGTH - NONCE_TIMESTAMP_LENGTH
        var low = timestamp
        for (index in 0 until 4) {
            nonce[timestampStart + index] = (low and 0xFFuL).toByte()
            low = low shr 8
        }
        var high = timestamp shr 32
        for (index in 4 until NONCE_TIMESTAMP_LENGTH) {
            nonce[timestampStart + index] = (high and 0xFFuL).toByte()
            high = high shr 8
        }
        for (index in (NONCE_LENGTH - NONCE_COUNTER_LENGTH) until NONCE_LENGTH) {
            nonce[index] = 0
        }
        return nonce
    }

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    // MARK: - Hex

    internal fun hexBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val byte = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = byte.toByte()
        }
        return out
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
