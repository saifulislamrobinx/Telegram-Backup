package com.example.feature.backup

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.common.FormatUtils
import com.example.core.database.BackupCategory
import com.example.core.database.SelectedSourceItemEntity
import com.example.core.database.TelegramDestinationEntity
import com.example.core.designsystem.DottedDivider
import com.example.core.designsystem.NothingCard
import com.example.core.designsystem.NothingOutlinedButton
import com.example.core.designsystem.NothingPrimaryButton
import com.example.core.designsystem.NothingStatusPill
import com.example.core.designsystem.SectionHeader
import com.example.core.designsystem.StatusTone
import com.example.data.VaultSettingsState
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily

@Composable
fun BackupConfigurationScreen(
    settings: VaultSettingsState,
    destinations: List<TelegramDestinationEntity>,
    selectedSources: List<SelectedSourceItemEntity>,
    hasEncryptionPassphrase: Boolean,
    encryptionFingerprint: String,
    isBusy: Boolean,
    onToggleCategory: (BackupCategory, Boolean) -> Unit,
    onSelectJobDestinationOverride: (String) -> Unit,
    onTestDestinationFileUpload: (TelegramDestinationEntity) -> Unit,
    onUpdateEncryptionConfig: (enabled: Boolean, encryptManifest: Boolean, splitOversized: Boolean) -> Unit,
    onUpdateSyncSchedule: (
        autoSync: Boolean,
        intervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        concurrency: Int,
        skipDuplicates: Boolean
    ) -> Unit,
    onLaunchBackupNow: (explicitDestinationChatId: String?, forceDuplicates: Boolean) -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenDestinations: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    var forceReuploadDuplicates by rememberSaveable { mutableStateOf(false) }

    val defaultDestination = destinations.firstOrNull { it.isDefault } ?: destinations.firstOrNull()
    val effectiveDestination = if (settings.jobDestinationOverrideChatId.isNotBlank()) {
        destinations.firstOrNull { it.chatId == settings.jobDestinationOverrideChatId } ?: defaultDestination
    } else {
        defaultDestination
    }

    val enabledSourceItems = selectedSources.filter { settings.enabledCategories.contains(it.category) }
    val totalSize = enabledSourceItems.sumOf { it.sizeBytes }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("backup_configuration_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with Back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("backup_config_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back"
                    )
                }
                Text(
                    text = "BACKUP CONFIGURATION",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            NothingStatusPill(
                label = "${enabledSourceItems.size} FILES // ${FormatUtils.formatBytes(totalSize)}",
                tone = if (enabledSourceItems.isNotEmpty()) StatusTone.ACTIVE_RED else StatusTone.NEUTRAL
            )
        }

        // 1. Selected Categories Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "01",
                title = "INCLUDED BACKUP CATEGORIES",
                subtitle = "Select which categories are included in this backup profile"
            )
            Spacer(modifier = Modifier.height(10.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(8.dp))

            BackupCategory.entries.forEach { category ->
                val enabled = settings.enabledCategories.contains(category.name)
                val count = selectedSources.count { it.category == category.name }
                val bytes = selectedSources.filter { it.category == category.name }.sumOf { it.sizeBytes }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "[${category.code}] ${category.displayName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "$count selected • ${FormatUtils.formatBytes(bytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = SpaceMonoFontFamily
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { onToggleCategory(category, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = NothingRed
                        )
                    )
                }
            }
        }

        // 2. Destination Selection for Job vs Default
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "02",
                title = "DESTINATION TARGET",
                subtitle = "Default target is selected automatically; overriding for a job preserves the global default"
            )
            Spacer(modifier = Modifier.height(10.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(8.dp))

            if (destinations.isEmpty()) {
                Text(
                    text = "No Telegram destinations configured yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                NothingOutlinedButton(
                    text = "CONFIGURE DESTINATIONS",
                    onClick = onOpenDestinations,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = settings.jobDestinationOverrideChatId.isBlank(),
                        onClick = { onSelectJobDestinationOverride("") }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "USE GLOBAL DEFAULT (${defaultDestination?.title ?: "None"})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "ID: ${defaultDestination?.chatId ?: "N/A"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = SpaceMonoFontFamily
                        )
                    }
                }

                destinations.filter { !it.isDefault }.forEach { dest ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = settings.jobDestinationOverrideChatId == dest.chatId,
                            onClick = { onSelectJobDestinationOverride(dest.chatId) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "OVERRIDE FOR JOB: ${dest.title}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "ID: ${dest.chatId} (${dest.chatType})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = SpaceMonoFontFamily
                            )
                        }
                    }
                }

                if (effectiveDestination != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    NothingOutlinedButton(
                        text = if (isBusy) "TESTING..." else "VERIFY & SEND TEST FILE TO ${effectiveDestination.title}",
                        onClick = { onTestDestinationFileUpload(effectiveDestination) },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "config_test_file_upload_button"
                    )
                }
            }
        }

        // 3. Encryption & Archive Options
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "03",
                title = "ENCRYPTION & ARCHIVE OPTIONS",
                subtitle = "Authenticated AES-256-GCM with unique 96-bit nonces"
            )
            Spacer(modifier = Modifier.height(10.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ENCRYPT FILES BEFORE UPLOAD", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (hasEncryptionPassphrase) {
                            "Key Fingerprint: $encryptionFingerprint"
                        } else {
                            "No master passphrase configured yet"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SpaceMonoFontFamily
                    )
                }
                Switch(
                    checked = settings.encryptionEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !hasEncryptionPassphrase) {
                            onOpenSecuritySettings()
                        } else {
                            onUpdateEncryptionConfig(
                                enabled,
                                settings.encryptManifestEnabled,
                                settings.splitOversizedFiles
                            )
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = NothingRed
                    )
                )
            }

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
                Text("Encrypt JSON backup manifest (.nvenc)", style = MaterialTheme.typography.bodySmall)
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
                Text("Split oversized files (> 50 MB) into 45 MB parts", style = MaterialTheme.typography.bodySmall)
            }
        }

        // 4. Network, Battery & Scheduling Preferences
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "04",
                title = "CONSTRAINTS & SCHEDULING",
                subtitle = "WorkManager background synchronization rules"
            )
            Spacer(modifier = Modifier.height(10.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SCHEDULED BACKGROUND SYNC", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = settings.autoSyncEnabled,
                    onCheckedChange = {
                        onUpdateSyncSchedule(
                            it,
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
                    )
                )
            }

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
                    checked = forceReuploadDuplicates,
                    onCheckedChange = { forceReuploadDuplicates = it }
                )
                Text("Force re-upload even if SHA-256 already backed up", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Launch Backup Button
        NothingPrimaryButton(
            text = "START CONFIGURED BACKUP JOB",
            onClick = {
                onLaunchBackupNow(
                    settings.jobDestinationOverrideChatId.takeIf { it.isNotBlank() },
                    forceReuploadDuplicates
                )
            },
            accentRed = true,
            modifier = Modifier.fillMaxWidth(),
            testTag = "launch_configured_backup_button"
        )
    }
}
