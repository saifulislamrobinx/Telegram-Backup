package com.example.feature.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.FormatUtils
import com.example.core.database.BackupManifestEntity
import com.example.core.database.SelectedSourceItemEntity
import com.example.core.database.TelegramDestinationEntity
import com.example.core.database.TransferStatus
import com.example.core.database.TransferTaskEntity
import com.example.core.designsystem.DotMatrixProgressBar
import com.example.core.designsystem.DottedDivider
import com.example.core.designsystem.NothingCard
import com.example.core.designsystem.NothingOutlinedButton
import com.example.core.designsystem.NothingPrimaryButton
import com.example.core.designsystem.NothingStatusPill
import com.example.core.designsystem.SectionHeader
import com.example.core.designsystem.StatusTone
import com.example.core.designsystem.TelemetryMetricTile
import com.example.core.security.BotConnectionState
import com.example.data.EngineProgressSnapshot
import com.example.data.VaultSettingsState
import com.example.ui.theme.NothingBlack
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily

@Composable
fun DashboardScreen(
    settings: VaultSettingsState,
    botState: BotConnectionState,
    destinations: List<TelegramDestinationEntity>,
    selectedSources: List<SelectedSourceItemEntity>,
    transferTasks: List<TransferTaskEntity>,
    manifests: List<BackupManifestEntity>,
    engineProgress: EngineProgressSnapshot,
    onBackupNow: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenBotConnection: () -> Unit,
    onOpenDestinations: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackupConfig: () -> Unit = {}
) {
    val enabledSources = selectedSources.filter { settings.enabledCategories.contains(it.category) }
    val totalSelectedCount = enabledSources.size
    val totalEstimatedBytes = enabledSources.sumOf { it.sizeBytes }

    val defaultDestination = destinations.firstOrNull { it.isDefault } ?: destinations.firstOrNull()
    val effectiveDestination = if (settings.jobDestinationOverrideChatId.isNotBlank()) {
        destinations.firstOrNull { it.chatId == settings.jobDestinationOverrideChatId } ?: defaultDestination
    } else {
        defaultDestination
    }

    val runningOrQueuedCount = transferTasks.count {
        it.status == TransferStatus.RUNNING.name || it.status == TransferStatus.QUEUED.name
    }
    val failedCount = transferTasks.count { it.status == TransferStatus.FAILED.name }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("dashboard_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "NOTHING // VAULT",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "PRIVATE TELEGRAM BACKUP SYSTEM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NothingStatusPill(
                label = if (botState.isConnected) "BOT // @${botState.botUsername}" else "BOT OFFLINE",
                tone = if (botState.isConnected) StatusTone.VERIFIED_GREEN else StatusTone.ACTIVE_RED,
                modifier = Modifier.testTag("dashboard_bot_status_pill")
            )
        }

        // Hero Schematic Banner
        NothingCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 0.dp,
            onClick = onOpenBotConnection
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_vault_hero_banner_1793236596065),
                    contentDescription = "NothingVault Telemetry Schematic",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    NothingBlack.copy(alpha = 0.35f),
                                    NothingBlack.copy(alpha = 0.88f)
                                )
                            )
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NothingStatusPill(
                                label = if (settings.encryptionEnabled) "AES-256-GCM ARMED" else "PLAINTEXT MODE",
                                tone = if (settings.encryptionEnabled) StatusTone.VERIFIED_GREEN else StatusTone.WARNING_AMBER
                            )
                            Text(
                                text = "TAP TO CONFIGURE BOT ->",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontFamily = SpaceMonoFontFamily
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (botState.isConnected) {
                                "LINKED: ${botState.botFirstName.uppercase()} // TARGET: ${effectiveDestination?.title ?: "UNSET"}"
                            } else {
                                "NO TELEGRAM BOT LINKED // TAP HERE TO CONNECT TOKEN"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // 2x2 Telemetry Metrics Grid
        SectionHeader(
            indexCode = "01",
            title = "VAULT TELEMETRY",
            subtitle = "${settings.enabledCategories.size} categories enabled • ${manifests.size} files indexed in manifest"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryMetricTile(
                label = "SELECTED FILES",
                value = totalSelectedCount.toString(),
                subValue = "${selectedSources.count { it.isFolderTree }} folder(s) linked",
                accentRed = totalSelectedCount > 0,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_selected_files")
            )
            TelemetryMetricTile(
                label = "EST. BACKUP SIZE",
                value = FormatUtils.formatBytes(totalEstimatedBytes),
                subValue = if (settings.skipDuplicates) "SHA-256 DEDUP ON" else "FULL UPLOAD",
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_backup_size")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryMetricTile(
                label = "LAST SUCCESS SYNC",
                value = FormatUtils.formatShortTimestamp(settings.lastSuccessfulSyncAt),
                subValue = "${manifests.size} total backed up",
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_last_sync")
            )
            TelemetryMetricTile(
                label = "NEXT SCHEDULED",
                value = if (settings.autoSyncEnabled) {
                    FormatUtils.formatShortTimestamp(settings.nextScheduledSyncAt)
                } else {
                    "MANUAL ONLY"
                },
                subValue = if (settings.autoSyncEnabled) {
                    "Every ${settings.syncIntervalHours}h (${if (settings.wifiOnly) "Wi-Fi" else "Any Net"})"
                } else {
                    "Auto-sync disabled"
                },
                accentRed = settings.autoSyncEnabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_next_sync")
            )
        }

        // Active Upload Progress Card
        NothingCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenTransfers
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TRANSFER ENGINE STATUS",
                    style = MaterialTheme.typography.titleMedium
                )
                NothingStatusPill(
                    label = when {
                        engineProgress.isRunning -> "ACTIVE (${engineProgress.completedTasksInRun}/${engineProgress.totalTasksInRun})"
                        failedCount > 0 -> "$failedCount FAILED"
                        else -> "IDLE"
                    },
                    tone = when {
                        engineProgress.isRunning -> StatusTone.ACTIVE_RED
                        failedCount > 0 -> StatusTone.WARNING_AMBER
                        else -> StatusTone.NEUTRAL
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            DotMatrixProgressBar(
                progress = if (engineProgress.isRunning) {
                    engineProgress.overallProgressFraction
                } else if (manifests.isNotEmpty()) {
                    1f
                } else {
                    0f
                },
                activeColor = if (engineProgress.isRunning) NothingRed else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = engineProgress.currentStatusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "QUEUE: $runningOrQueuedCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Quick Actions Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "02",
                title = "QUICK OPERATIONS",
                subtitle = "Target: ${effectiveDestination?.let { "${it.title} (${it.chatId})" } ?: "No destination configured"}"
            )
            Spacer(modifier = Modifier.height(12.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NothingPrimaryButton(
                    text = "BACKUP NOW",
                    onClick = onBackupNow,
                    accentRed = true,
                    modifier = Modifier.weight(1f),
                    testTag = "quick_backup_now_button"
                )
                NothingOutlinedButton(
                    text = "SYNC NOW",
                    onClick = onSyncNow,
                    modifier = Modifier.weight(1f),
                    testTag = "quick_sync_now_button"
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NothingOutlinedButton(
                    text = "SELECT FILES",
                    onClick = onOpenCategories,
                    modifier = Modifier.weight(1f),
                    testTag = "quick_select_files_button"
                )
                NothingOutlinedButton(
                    text = "TARGETS",
                    onClick = onOpenDestinations,
                    modifier = Modifier.weight(1f),
                    testTag = "quick_destinations_button"
                )
                NothingOutlinedButton(
                    text = "CONFIG",
                    onClick = onOpenBackupConfig,
                    modifier = Modifier.weight(1f),
                    testTag = "quick_backup_config_button"
                )
            }
        }
    }
}
