package com.speechnotes.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NoEncryption
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.speechnotes.app.data.EncryptionVault
import com.speechnotes.app.data.SpeechnotesViewModel
import kotlinx.coroutines.launch

/**
 * Settings → Encryption: the passphrase gate over the notes library. Port of
 * the iOS EncryptionSettingsView — three states, one screen, no hidden modes:
 *
 *  * **off** — the notes file is plain JSON. The user sets a passphrase and
 *    encryption begins; the library is sealed in place.
 *  * **locked** — the file is sealed and no key is in memory. The notes list
 *    shows a locked state; unlocking re-reads the notes from disk.
 *  * **unlocked** — notes read and write normally, sealed at rest. Lock again
 *    (key dropped from memory) or turn encryption off (file rewritten as
 *    plain JSON first, then the key deleted).
 *
 * The passphrase is never stored. Only the passphrase-wrapped master key
 * lives on the device, itself wrapped under the Android Keystore — the same
 * construction Joplin uses, so a backup restored onto another device cannot
 * be opened there.
 */
@Composable
fun EncryptionSettingsScreen(vm: SpeechnotesViewModel, onBack: () -> Unit) {
    val enabled by vm.vault.isEnabled.collectAsState()
    val unlocked by vm.vault.isUnlocked.collectAsState()
    val fileSealed by vm.notes.fileIsSealed.collectAsState()

    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var showDisableConfirm by remember { mutableStateOf(false) }
    // True while the enable/unlock path is deriving the key — the passphrase
    // KDF is 220 000 PBKDF2 iterations by design, so this is not instant.
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun friendlyError(error: Throwable): String = (error as? EncryptionVault.VaultError)?.message
        ?: error.message
        ?: "Encryption failed."

    SettingsSubpage(title = "Encryption", onBack = onBack) {
        val vaultState = when {
            !enabled -> "off"
            unlocked -> "unlocked"
            else -> "locked"
        }
        SettingsCard("Status") {
            ListItem(
                leadingContent = {
                    Icon(
                        if (enabled) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = {
                    Text(
                        if (fileSealed) "Notes are encrypted at rest" else "Notes are stored in a plain file",
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        when (vaultState) {
                            "off" -> "Anyone with the device unlocked, or a backup restored elsewhere, can read your notes."
                            "locked" -> "Unlock with your passphrase to read and edit your notes."
                            else ->
                                if (fileSealed) {
                                    "The file on disk is sealed; notes stay readable while the app stays unlocked."
                                } else {
                                    "Sealing the next write — the library will be encrypted at rest shortly."
                                }
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Text(
            "Encryption uses the same format as Joplin: PBKDF2-HMAC-SHA512 (220 000 iterations) to wrap a 256-byte master key with your passphrase, then AES-256-GCM for the note text. A note encrypted here can be decrypted in Joplin, and one encrypted there decrypts here. The passphrase is never stored — if you forget it, the notes cannot be recovered.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )

        when (vaultState) {
            "off" -> {
                SettingsCard("Set a passphrase") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text("Confirm passphrase") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                working = true
                                scope.launch {
                                    val result = runCatching { vm.encryptionEnable(passphrase) }
                                    working = false
                                    passphrase = ""
                                    confirmation = ""
                                    result.fold(
                                        onSuccess = { count ->
                                            message = "Encryption is on. $count note(s) are now sealed at rest."
                                        },
                                        onFailure = { message = friendlyError(it) },
                                    )
                                }
                            },
                            enabled = !working &&
                                passphrase.length >= EncryptionVault.MIN_PASSPHRASE_LENGTH &&
                                passphrase == confirmation,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (working) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Turn on encryption")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "At least 8 characters. This runs 220 000 key-derivation iterations by design, so it takes a moment — a slow unlock is what makes a stolen file useless.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            "locked" -> {
                SettingsCard("Unlock your notes") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                working = true
                                scope.launch {
                                    val result = runCatching { vm.encryptionUnlock(passphrase) }
                                    working = false
                                    passphrase = ""
                                    result.fold(
                                        onSuccess = { count ->
                                            if (count == null) {
                                                message = "The notes file could not be opened with that key."
                                            }
                                        },
                                        onFailure = { message = friendlyError(it) },
                                    )
                                }
                            },
                            enabled = !working && passphrase.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (working) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Unlock")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your notes are on the device but encrypted. Unlock to read them; the key stays in memory until you lock again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> {
                SettingsCard("Unlocked") {
                    EncryptionActionRow(
                        icon = Icons.Default.Lock,
                        title = "Lock now",
                        subtitle = "Drop the key from memory; notes stay sealed on disk.",
                        onClick = {
                            vm.encryptionLock()
                            message = "Locked. The key is out of memory; unlock again to read your notes."
                        },
                    )
                    EncryptionActionRow(
                        icon = Icons.Default.NoEncryption,
                        title = "Turn off encryption",
                        subtitle = "Rewrite the notes file unencrypted and delete the key from this device.",
                        onClick = { showDisableConfirm = true },
                    )
                }
                Text(
                    "Locking drops the key from memory — the notes stay sealed on disk and the app shows nothing until unlocked. Turning encryption off rewrites the notes file unencrypted and deletes the key from this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Encryption") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    if (showDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            title = { Text("Turn off encryption?") },
            text = {
                Text(
                    "Your notes are rewritten as a normal file and the key is deleted from this device. The passphrase is not needed again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableConfirm = false
                        working = true
                        scope.launch {
                            val result = runCatching { vm.encryptionDisable() }
                            working = false
                            result.fold(
                                onSuccess = {
                                    message = "Encryption is off. Your notes are stored as a normal file."
                                },
                                onFailure = {
                                    message = "Could not turn encryption off: ${friendlyError(it)}. Your notes are untouched."
                                },
                            )
                        }
                    },
                ) { Text("Turn off", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EncryptionActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
