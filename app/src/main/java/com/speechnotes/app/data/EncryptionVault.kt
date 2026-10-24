package com.speechnotes.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.speechnotes.app.logic.AppFiles
import com.speechnotes.app.logic.JoplinCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The vault that holds this device's Joplin-compatible encryption keys.
 * Android port of the iOS `EncryptionVault.swift` + `JoplinVaultKeyMaterial`.
 *
 * TWO layers, exactly as Joplin does it:
 *
 *  * The **master key** is 256 random bytes; the user's passphrase wraps it
 *    (Joplin's KeyV1, `JoplinCrypto.encryptMasterKey`) and the wrapped copy
 *    is what gets stored. The passphrase itself is NEVER stored.
 *  * The **master key id** is the 32-hex id in every encrypted envelope.
 *
 * iOS keeps the wrapped key in the keychain. The Android equivalent is
 * DOUBLE wrapping: the KeyV1 envelope lives in `filesDir/vault.key`, whose
 * bytes are encrypted under an Android Keystore AES-256-GCM key
 * (`speechnotes_vault`) that never leaves the secure hardware. That is the
 * same `ThisDeviceOnly` property — a backup restored to another device
 * cannot unwrap the file, because its Keystore does not hold the wrapping
 * key. A wiped Keystore therefore bricks the vault exactly like a lost
 * keychain item does; the file stays sealed (never quarantined) and the
 * notes list shows the locked state.
 *
 * The expensive paths (`create`/`unlock`, 220 000 PBKDF2 iterations ≈ 1–2 s)
 * are suspend and hop to Dispatchers.Default, so no caller can run them on
 * the main thread. `encryptText`/`decryptText` are plain AES and cheap.
 */
object EncryptionVault {

    /** One vault's key material (iOS `JoplinVaultKeyMaterial.Material`). */
    data class Material(
        /** The master key: 256 random bytes as 512 hex characters. In memory only. */
        val master: String,
        /** The 32-hex id that appears in every envelope sealed with this key. */
        val id: String,
        /** The passphrase-wrapped master key (Joplin's KeyV1). */
        val wrapped: String,
    )

    sealed class VaultError(message: String) : Exception(message) {
        object PassphraseTooShort : VaultError("Use a passphrase of at least 8 characters.")
        object NoKeyOnDevice : VaultError("No encryption key on this device.")
        object WrongPassphrase : VaultError("That passphrase doesn't match.")
        object Locked : VaultError("The vault is locked.")
        /** The Keystore could not open `vault.key` — the wrapping key is gone. */
        class KeyUnavailable(cause: Throwable) :
            VaultError("The encryption key is unusable on this device (Keystore: ${cause.message}).")
    }

    private const val KEYSTORE_ALIAS = "speechnotes_vault"

    const val MIN_PASSPHRASE_LENGTH = 8

    /** Whether this device holds an encryption key. */
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    /** Whether a passphrase has been entered in THIS session. */
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /** The decrypted master key, in memory only, for as long as the vault is unlocked. */
    @Volatile
    private var material: Material? = null

    val keyId: String? get() = material?.id

    /** The key material, or null when the vault is locked. A value, so it can cross to a worker thread. */
    val keyMaterial: Material? get() = material.takeIf { _isUnlocked.value }

    /** Called once at app start, before any store loads. */
    fun init() {
        _isEnabled.value = runCatching { AppFiles.vaultKeyFile().exists() }.getOrDefault(false)
        if (!_isEnabled.value) material = null
    }

    // MARK: - Public API

    /** Generates a fresh master key, wraps it with the passphrase and activates the vault. */
    suspend fun create(passphrase: String): Material = withContext(Dispatchers.Default) {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) throw VaultError.PassphraseTooShort
        val master = JoplinCrypto.makeMasterKey()
        val id = JoplinCrypto.makeMasterKeyId()
        val wrapped = JoplinCrypto.encryptMasterKey(master, passphrase, id)
        val fresh = Material(master, id, wrapped)
        persist(fresh.wrapped)
        material = fresh
        _isEnabled.value = true
        _isUnlocked.value = true
        fresh
    }

    /** Unwraps the stored master key with the passphrase; the key stays in memory until `lock()`. */
    suspend fun unlock(passphrase: String): Material = withContext(Dispatchers.Default) {
        val envelope = storedEnvelope() ?: throw VaultError.NoKeyOnDevice
        val master = try {
            JoplinCrypto.decryptMasterKey(envelope, passphrase)
        } catch (e: JoplinCrypto.CryptoError.BadPassword) {
            throw VaultError.WrongPassphrase
        }
        val unwrapped = Material(master, JoplinCrypto.masterKeyId(envelope) ?: "", envelope)
        material = unwrapped
        _isUnlocked.value = true
        unwrapped
    }

    /** Locks the vault: the in-memory key is dropped. The sealed file stays as it is. */
    fun lock() {
        material = null
        _isUnlocked.value = false
    }

    /**
     * Turns encryption off: removes the key and drops the in-memory key.
     * The caller MUST have rewritten the notes file as plain JSON first
     * (`NotesStore.resealWithCurrentVault(forcePlain = true)`) — once the
     * key is gone there is no way back to a sealed file.
     */
    fun disable() {
        runCatching { AppFiles.vaultKeyFile().delete() }
            .onFailure { LogStore.warn("EncryptionVault: could not delete vault.key: $it") }
        material = null
        _isEnabled.value = false
        _isUnlocked.value = false
    }

    /** Encrypts note-library text with the current master key; throws when locked. */
    fun encryptText(text: String): String {
        val key = keyMaterial ?: throw VaultError.Locked
        return JoplinCrypto.encryptString(text, key.master, key.id)
    }

    /** Decrypts note-library text; throws when locked or the envelope is foreign. */
    fun decryptText(envelope: String): String {
        val key = keyMaterial ?: throw VaultError.Locked
        return JoplinCrypto.decryptString(envelope, key.master)
    }

    /**
     * The passphrase-wrapped master key (Joplin's KeyV1 envelope) as stored
     * under the Keystore, or null when there is none / the Keystore lost it.
     */
    fun storedEnvelope(): String? {
        val stored = runCatching {
            AppFiles.vaultKeyFile().takeIf { it.exists() }?.readText()
        }.getOrNull() ?: return null
        if (stored.isBlank()) return null
        return try {
            String(keystoreUnwrap(stored), Charsets.UTF_8)
        } catch (e: Exception) {
            // The Keystore key is gone (app restored from a backup onto
            // another device, or a provider change): the envelope is there
            // but unreachable — the ThisDeviceOnly tradeoff.
            LogStore.warn("EncryptionVault: vault.key cannot be unwrapped (${e.javaClass.simpleName})")
            null
        }
    }

    // MARK: - Keystore wrapping

    /** Encrypts the KeyV1 envelope under the hardware-backed Keystore key and writes `vault.key`. */
    private fun persist(envelope: String) {
        val wrapped = keystoreWrap(envelope.toByteArray(Charsets.UTF_8))
        AppFiles.atomicWrite(AppFiles.vaultKeyFile(), wrapped)
    }

    private fun keystoreWrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val sealed = cipher.doFinal(plain)
        // Stored as base64(iv || ciphertext || tag) — the layout Java's GCM produces.
        return Base64.getEncoder().encodeToString(cipher.iv + sealed)
    }

    private fun keystoreUnwrap(stored: String): ByteArray {
        val all = Base64.getDecoder().decode(stored)
        if (all.size <= 12) throw VaultError.NoKeyOnDevice
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return cipher.doFinal(all.copyOfRange(12, all.size))
    }

    /** The device-wrapped AES-256-GCM key, generated on first use. StrongBox is off on purpose. */
    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
