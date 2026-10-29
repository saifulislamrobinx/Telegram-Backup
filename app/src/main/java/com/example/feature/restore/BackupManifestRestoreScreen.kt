package com.example.feature.restore

import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.common.FormatUtils
import com.example.core.common.VaultLogger
import com.example.core.database.BackupManifestEntity
import com.example.core.designsystem.DotMatrixProgressBar
import com.example.core.designsystem.DottedDivider
import com.example.core.designsystem.NothingCard
import com.example.core.designsystem.NothingOutlinedButton
import com.example.core.designsystem.NothingPrimaryButton
import com.example.core.designsystem.NothingStatusPill
import com.example.core.designsystem.SectionHeader
import com.example.core.designsystem.StatusTone
import com.example.data.RestoreResult
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily

@Composable
fun BackupManifestRestoreScreen(
    manifests: List<BackupManifestEntity>,
    hasSavedPassphrase: Boolean,
    isBusy: Boolean,
    restoreProgress: Float?,
    lastRestoreResult: RestoreResult?,
    onRestoreManifest: (manifest: BackupManifestEntity, passphrase: String, targetUri: Uri?) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedManifestForRestore by remember { mutableStateOf<BackupManifestEntity?>(null) }
    var passphraseInput by rememberSaveable { mutableStateOf("") }

    // SAF CreateDocument launcher so user can pick custom output location on restore
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { targetUri ->
        val m = selectedManifestForRestore
        if (m != null && targetUri != null) {
            onRestoreManifest(m, passphraseInput, targetUri)
            selectedManifestForRestore = null
        }
    }

    if (selectedManifestForRestore != null) {
        val m = selectedManifestForRestore!!
        AlertDialog(
            onDismissRequest = { selectedManifestForRestore = null },
            title = {
                Text(
                    text = "RESTORE & VERIFY INTEGRITY",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "File: ${m.originalFileName}\n" +
                            "Size: ${FormatUtils.formatBytes(m.fileSize)}\n" +
                            "SHA-256: ${m.contentSha256.take(20)}...\n" +
                            "Encryption: ${if (m.isEncrypted) "AES-256-GCM" else "Plaintext"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = SpaceMonoFontFamily
                    )

                    if (m.isEncrypted) {
                        OutlinedTextField(
                            value = passphraseInput,
                            onValueChange = { passphraseInput = it },
                            label = {
                                Text(
                                    if (hasSavedPassphrase) "PASSPHRASE (OPTIONAL OVERRIDE)" else "ENTER AES-256-GCM PASSPHRASE",
                                    fontFamily = SpaceMonoFontFamily
                                )
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("restore_passphrase_input")
                        )
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            onRestoreManifest(m, passphraseInput, null)
                            selectedManifestForRestore = null
                        },
                        modifier = Modifier.testTag("confirm_restore_internal_button")
                    ) {
                        Text("RESTORE TO VAULT STORAGE", color = NothingRed, fontFamily = SpaceMonoFontFamily)
                    }
                    TextButton(
                        onClick = {
                            createDocumentLauncher.launch(m.originalFileName)
                        }
                    ) {
                        Text("CHOOSE SAF FOLDER LOCATION...", fontFamily = SpaceMonoFontFamily)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedManifestForRestore = null }) {
                    Text("CANCEL", fontFamily = SpaceMonoFontFamily)
                }
            }
        )
    }

    val filtered = manifests.filter { m ->
        searchQuery.isBlank() ||
            m.originalFileName.contains(searchQuery, ignoreCase = true) ||
            m.backupId.contains(searchQuery, ignoreCase = true) ||
            m.category.contains(searchQuery, ignoreCase = true) ||
            m.contentSha256.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("restore_manifest_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(
            indexCode = "01",
            title = "BACKUP MANIFEST & RESTORE",
            subtitle = "Download backed-up files from Telegram, decrypt AES-256-GCM, and verify SHA-256 integrity"
        )

        if (restoreProgress != null) {
            NothingCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "DOWNLOADING & VERIFYING SHA-256 INTEGRITY...",
                    style = MaterialTheme.typography.titleMedium,
                    color = NothingRed
                )
                Spacer(modifier = Modifier.height(8.dp))
                DotMatrixProgressBar(progress = restoreProgress, activeColor = NothingRed)
            }
        }

        if (lastRestoreResult != null) {
            NothingCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LAST RESTORE VERIFIED",
                        style = MaterialTheme.typography.titleMedium
                    )
                    NothingStatusPill(
                        label = if (lastRestoreResult.sha256Verified) "SHA-256 MATCH" else "HASH MISMATCH",
                        tone = if (lastRestoreResult.sha256Verified) StatusTone.VERIFIED_GREEN else StatusTone.ACTIVE_RED
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "File: ${lastRestoreResult.restoredFileName} (${FormatUtils.formatBytes(lastRestoreResult.bytesRestored)})\nSaved to: ${lastRestoreResult.outputLocation}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = SpaceMonoFontFamily
                )
            }
        }

        NothingCard(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("SEARCH MANIFEST BY FILENAME / SHA-256 / JOB ID", fontFamily = SpaceMonoFontFamily) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manifest_search_input")
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "NOTE: Restoration downloads files directly via Telegram Bot API getFile using the recorded file_id. Deleted Telegram messages or files > 20 MB (Bot API download ceiling) cannot be fetched via standard Bot API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (filtered.isEmpty()) {
            NothingCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "NO BACKUP MANIFEST RECORDS FOUND",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Every file uploaded to Telegram by NothingVault is indexed here with its SHA-256 content hash, encryption metadata, and Telegram file_id for verified restoration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            filtered.forEach { item ->
                NothingCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manifest_item_${item.manifestId}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.originalFileName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${item.backupId} • ${item.category} • ${FormatUtils.formatBytes(item.fileSize)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = SpaceMonoFontFamily
                            )
                        }
                        NothingStatusPill(
                            label = if (item.isEncrypted) "AES-256-GCM" else "PLAINTEXT",
                            tone = if (item.isEncrypted) StatusTone.VERIFIED_GREEN else StatusTone.NEUTRAL
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    DottedDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "TARGET: ${item.destinationTitle} (${item.destinationChatId})\n" +
                            "SHA-256: ${item.contentSha256}\n" +
                            "TELEGRAM MSG: #${item.telegramMessageId} • ${VaultLogger.formatTimestamp(item.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SpaceMonoFontFamily
                    )

                    if (item.localRestorePath.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "RESTORED AT: ${item.localRestorePath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    NothingPrimaryButton(
                        text = if (isBusy) "WORKING..." else "DOWNLOAD, DECRYPT & RESTORE",
                        onClick = { selectedManifestForRestore = item },
                        enabled = !isBusy && item.telegramFileId.isNotBlank(),
                        accentRed = true,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "restore_button_${item.manifestId}"
                    )
                }
            }
        }
    }
}
