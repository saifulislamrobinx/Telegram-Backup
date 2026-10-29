package com.example.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.database.TelegramDestinationEntity
import com.example.core.designsystem.DottedDivider
import com.example.core.designsystem.NothingCard
import com.example.core.designsystem.NothingOutlinedButton
import com.example.core.designsystem.NothingPrimaryButton
import com.example.core.designsystem.NothingStatusPill
import com.example.core.designsystem.SectionHeader
import com.example.core.designsystem.StatusTone
import com.example.core.security.BotConnectionState
import com.example.data.VaultSettingsState
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily

@Composable
fun SettingsAndSecurityScreen(
    settings: VaultSettingsState,
    botState: BotConnectionState,
    destinations: List<TelegramDestinationEntity>,
    hasEncryptionPassphrase: Boolean,
    encryptionFingerprint: String,
    onOpenBotConnection: () -> Unit,
    onOpenDestinations: () -> Unit,
    onSavePassphrase: (String) -> Unit,
    onClearPassphrase: () -> Unit,
    onUpdateEncryptionConfig: (enabled: Boolean, encryptManifest: Boolean, splitOversized: Boolean) -> Unit,
    onUpdateSyncSchedule: (
        autoSync: Boolean,
        intervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        concurrency: Int,
        skipDuplicates: Boolean
    ) -> Unit,
    onSetThemeMode: (String) -> Unit,
    onExportConfigToUri: (Uri) -> Unit,
    onClearAllHistory: () -> Unit
) {
    val context = LocalContext.current
    var passphraseInput by rememberSaveable { mutableStateOf("") }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            onExportConfigToUri(uri)
        }
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("CLEAR LOCAL VAULT HISTORY?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "This clears local transfer history, local manifest index records, and diagnostic logs on this device. It does NOT delete files already uploaded to your Telegram chat.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAllHistory()
                        showClearHistoryDialog = false
                    },
                    modifier = Modifier.testTag("confirm_clear_all_history_button")
                ) {
                    Text("CLEAR LOCAL RECORDS", color = NothingRed, fontFamily = SpaceMonoFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("CANCEL", fontFamily = SpaceMonoFontFamily)
                }
            }
        )
    }

    if (showPrivacyPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicyDialog = false },
            title = { Text("PRIVACY & TELEGRAM SECURITY MODEL", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "1. NO TELEMETRY OR ANALYTICS: NothingVault collects zero analytics, crash telemetry, or advertising identifiers. Your files and tokens never touch any third-party server other than official api.telegram.org.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "2. TELEGRAM BOT STORAGE MODEL: Telegram Bot chats, groups, and channels do NOT provide end-to-end encryption (Secret Chats are unavailable to Bots). Files uploaded without local encryption are stored under Telegram's standard cloud security model.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "3. LOCAL AES-256-GCM ENCRYPTION: When enabled, NothingVault encrypts every file on your device before upload using AES-256-GCM (PBKDF2-HMAC-SHA256 key derivation, 16-byte random salt, 96-bit unique nonce, 128-bit authentication tag). Your key is stored only in Android Keystore and never uploaded.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "4. KEY RECOVERY WARNING: Because NothingVault is zero-knowledge, if you lose your AES-256-GCM master passphrase and wipe your device, encrypted .nvenc backups in Telegram cannot be decrypted by anyone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NothingRed
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyPolicyDialog = false }) {
                    Text("CLOSE", fontFamily = SpaceMonoFontFamily)
                }
            }
        )
    }

    val defaultDestination = destinations.firstOrNull { it.isDefault } ?: destinations.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("settings_security_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(
            indexCode = "01",
            title = "BACKUP CONFIGURATION & SECURITY",
            subtitle = "Hardware-backed Keystore, AES-256-GCM encryption, and WorkManager scheduling"
        )

        // Bot & Default Destination Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("TELEGRAM CONNECTION", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (botState.isConnected) {
                            "@${botState.botUsername} (${botState.maskedToken})"
                        } else {
                            "No Bot Token Configured"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SpaceMonoFontFamily
                    )
                }
                NothingOutlinedButton(
                    text = if (botState.isConnected) "MANAGE BOT" else "CONNECT",
                    onClick = onOpenBotConnection,
                    testTag = "settings_manage_bot_button"
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("DEFAULT DESTINATION", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = defaultDestination?.let { "${it.title} (${it.chatId})" } ?: "Not configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SpaceMonoFontFamily
                    )
                }
                NothingOutlinedButton(
                    text = "TARGETS",
                    onClick = onOpenDestinations
                )
            }
        }

        // AES-256-GCM Encryption & Key Recovery Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LOCAL AES-256-GCM ENCRYPTION", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Key Fingerprint: $encryptionFingerprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SpaceMonoFontFamily
                    )
                }
                Switch(
                    checked = settings.encryptionEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateEncryptionConfig(
                            enabled,
                            settings.encryptManifestEnabled,
                            settings.splitOversizedFiles
                        )
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = NothingRed
                    ),
                    modifier = Modifier.testTag("switch_encryption_enabled")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = passphraseInput,
                onValueChange = { passphraseInput = it },
                label = {
                    Text(
                        if (hasEncryptionPassphrase) "REPLACE MASTER PASSPHRASE (MIN 8 CHARS)" else "SET MASTER PASSPHRASE (MIN 8 CHARS)",
                        fontFamily = SpaceMonoFontFamily
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("encryption_passphrase_input")
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NothingPrimaryButton(
                    text = "SAVE PASSPHRASE",
                    onClick = {
                        onSavePassphrase(passphraseInput)
                        passphraseInput = ""
                    },
                    enabled = passphraseInput.trim().length >= 8,
                    accentRed = true,
                    modifier = Modifier.weight(1f),
                    testTag = "save_passphrase_button"
                )
                if (hasEncryptionPassphrase) {
                    NothingOutlinedButton(
                        text = "REMOVE KEY",
                        onClick = onClearPassphrase,
                        modifier = Modifier.weight(1f),
                        testTag = "clear_passphrase_button"
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.encryptManifestEnabled,
                    onCheckedChange = {
                        onUpdateEncryptionConfig(
                            settings.encryptionEnabled,
                            it,
                            settings.splitOversizedFiles
                        )
                    }
                )
                Text(
                    text = "Encrypt uploaded backup manifest (.json.nvenc)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.splitOversizedFiles,
                    onCheckedChange = {
                        onUpdateEncryptionConfig(
                            settings.encryptionEnabled,
                            settings.encryptManifestEnabled,
                            it
                        )
                    }
                )
                Text(
                    text = "Split files > 50 MB into 45 MB multi-part archives (.nvpart)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "RECOVERY NOTE: Store your master passphrase in a password manager. Encryption keys are separated from backup files and never sent to Telegram.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Background Synchronization & Scheduling Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AUTOMATIC BACKGROUND SYNC", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Scheduled via Android WorkManager",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoSyncEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateSyncSchedule(
                            enabled,
                            settings.syncIntervalHours,
                            settings.wifiOnly,
                            settings.chargingOnly,
                            settings.concurrentUploads,
                            settings.skipDuplicates
                        )
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = NothingRed
                    ),
                    modifier = Modifier.testTag("switch_auto_sync")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "SYNC INTERVAL:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(6, 12, 24, 48).forEach { hrs ->
                    val selected = settings.syncIntervalHours == hrs
                    NothingOutlinedButton(
                        text = if (selected) "[${hrs}H]" else "${hrs}H",
                        onClick = {
                            onUpdateSyncSchedule(
                                settings.autoSyncEnabled,
                                hrs,
                                settings.wifiOnly,
                                settings.chargingOnly,
                                settings.concurrentUploads,
                                settings.skipDuplicates
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "CONCURRENT UPLOADS:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(1, 2, 3, 4).forEach { limit ->
                    val selected = settings.concurrentUploads == limit
                    NothingOutlinedButton(
                        text = if (selected) "[$limit]" else "$limit",
                        onClick = {
                            onUpdateSyncSchedule(
                                settings.autoSyncEnabled,
                                settings.syncIntervalHours,
                                settings.wifiOnly,
                                settings.chargingOnly,
                                limit,
                                settings.skipDuplicates
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.wifiOnly,
                    onCheckedChange = {
                        onUpdateSyncSchedule(
                            settings.autoSyncEnabled,
                            settings.syncIntervalHours,
                            it,
                            settings.chargingOnly,
                            settings.concurrentUploads,
                            settings.skipDuplicates
                        )
                    }
                )
                Text("Wi-Fi / Unmetered network only", style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.chargingOnly,
                    onCheckedChange = {
                        onUpdateSyncSchedule(
                            settings.autoSyncEnabled,
                            settings.syncIntervalHours,
                            settings.wifiOnly,
                            it,
                            settings.concurrentUploads,
                            settings.skipDuplicates
                        )
                    }
                )
                Text("Charging only", style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.skipDuplicates,
                    onCheckedChange = {
                        onUpdateSyncSchedule(
                            settings.autoSyncEnabled,
                            settings.syncIntervalHours,
                            settings.wifiOnly,
                            settings.chargingOnly,
                            settings.concurrentUploads,
                            it
                        )
                    }
                )
                Text("Prevent duplicate uploads via SHA-256 index", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "OS EXECUTION NOTE: Android Doze mode and battery optimization may defer periodic background execution to maintenance windows.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Theme & Permissions Management Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "02",
                title = "APPEARANCE & PERMISSIONS",
                subtitle = "Monochrome theme and scoped Android runtime permissions"
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("DARK", "LIGHT", "SYSTEM").forEach { mode ->
                    NothingOutlinedButton(
                        text = if (settings.themeMode == mode) "[$mode]" else mode,
                        onClick = { onSetThemeMode(mode) },
                        modifier = Modifier.weight(1f),
                        testTag = "theme_mode_${mode.lowercase()}"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(10.dp))

            val hasContacts = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            val hasCalendar = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
            val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            PermissionStatusRow("MEDIA & DOCUMENTS", "PHOTO PICKER & SAF (ZERO-PERMISSION)", true)
            PermissionStatusRow("CONTACTS EXPORT", if (hasContacts) "GRANTED" else "NOT GRANTED", hasContacts)
            PermissionStatusRow("CALENDAR EXPORT", if (hasCalendar) "GRANTED" else "NOT GRANTED", hasCalendar)
            PermissionStatusRow("TRANSFER NOTIFICATIONS", if (hasNotifications) "GRANTED" else "OPTIONAL", hasNotifications)

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifications) {
                    NothingOutlinedButton(
                        text = "ALLOW NOTIFICATIONS",
                        onClick = {
                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                NothingOutlinedButton(
                    text = "OS PERMISSION SETTINGS",
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Data Management & Privacy Policy Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "03",
                title = "PRIVACY & DATA GOVERNANCE",
                subtitle = "Export redacted config, inspect security policy, or clear local history"
            )
            Spacer(modifier = Modifier.height(12.dp))

            NothingOutlinedButton(
                text = "VIEW PRIVACY & SECURITY DISCLOSURE",
                onClick = { showPrivacyPolicyDialog = true },
                modifier = Modifier.fillMaxWidth(),
                testTag = "view_privacy_policy_button"
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NothingOutlinedButton(
                    text = "EXPORT CONFIG JSON",
                    onClick = {
                        exportConfigLauncher.launch("nothingvault_config.json")
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "export_config_button"
                )
                NothingPrimaryButton(
                    text = "CLEAR HISTORY",
                    onClick = { showClearHistoryDialog = true },
                    accentRed = true,
                    modifier = Modifier.weight(1f),
                    testTag = "clear_all_history_button"
                )
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, statusText: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NothingStatusPill(
            label = statusText,
            tone = if (granted) StatusTone.VERIFIED_GREEN else StatusTone.NEUTRAL
        )
    }
}
