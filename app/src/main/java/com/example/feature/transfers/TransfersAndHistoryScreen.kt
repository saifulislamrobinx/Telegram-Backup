package com.example.feature.transfers

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.common.FormatUtils
import com.example.core.common.RedactedLogEntry
import com.example.core.common.VaultLogger
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
import com.example.data.EngineProgressSnapshot
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily

@Composable
fun TransfersAndHistoryScreen(
    tasks: List<TransferTaskEntity>,
    engineProgress: EngineProgressSnapshot,
    recentLogs: List<RedactedLogEntry>,
    onStartBackupNow: () -> Unit,
    onPauseAll: () -> Unit,
    onCancelAll: () -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryFailed: () -> Unit,
    onClearFinished: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf("ALL") }
    var showLogs by rememberSaveable { mutableStateOf(false) }

    val filterOptions = listOf("ALL", "RUNNING", "QUEUED", "COMPLETED", "SKIPPED", "FAILED", "CANCELLED")

    val filteredTasks = tasks.filter { task ->
        val matchesStatus = statusFilter == "ALL" || task.status == statusFilter
        val matchesQuery = searchQuery.isBlank() ||
            task.fileName.contains(searchQuery, ignoreCase = true) ||
            task.jobId.contains(searchQuery, ignoreCase = true) ||
            task.category.contains(searchQuery, ignoreCase = true) ||
            task.destinationTitle.contains(searchQuery, ignoreCase = true)
        matchesStatus && matchesQuery
    }

    val failedOrPausedCount = tasks.count {
        it.status == TransferStatus.FAILED.name || it.status == TransferStatus.PAUSED.name
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("transfers_history_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(
            indexCode = "01",
            title = "ACTIVE TRANSFERS & QUEUE",
            subtitle = "Real-time Telegram upload engine with flood-control & exponential backoff"
        )

        // Overall Engine Progress Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (engineProgress.activeJobId.isNotBlank()) {
                        "ENGINE // ${engineProgress.activeJobId}"
                    } else {
                        "ENGINE // STANDBY"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                NothingStatusPill(
                    label = if (engineProgress.isRunning) "TRANSFERRING" else "IDLE",
                    tone = if (engineProgress.isRunning) StatusTone.ACTIVE_RED else StatusTone.NEUTRAL
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            DotMatrixProgressBar(
                progress = engineProgress.overallProgressFraction,
                activeColor = if (engineProgress.isRunning) NothingRed else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = engineProgress.currentStatusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = SpaceMonoFontFamily
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (engineProgress.isRunning) {
                    NothingOutlinedButton(
                        text = "PAUSE",
                        onClick = onPauseAll,
                        modifier = Modifier.weight(1f),
                        testTag = "pause_transfers_button"
                    )
                    NothingPrimaryButton(
                        text = "CANCEL ALL",
                        onClick = onCancelAll,
                        accentRed = true,
                        modifier = Modifier.weight(1f),
                        testTag = "cancel_transfers_button"
                    )
                } else {
                    NothingPrimaryButton(
                        text = "BACKUP NOW",
                        onClick = onStartBackupNow,
                        accentRed = true,
                        modifier = Modifier.weight(1f),
                        testTag = "queue_backup_now_button"
                    )
                    if (failedOrPausedCount > 0) {
                        NothingOutlinedButton(
                            text = "RETRY ($failedOrPausedCount)",
                            onClick = onRetryFailed,
                            modifier = Modifier.weight(1f),
                            testTag = "retry_failed_button"
                        )
                    }
                }
            }
        }

        // Search & Filter Controls
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "02",
                title = "BACKUP HISTORY (${filteredTasks.size})",
                subtitle = "Filter by status or search filenames, job IDs, and destinations"
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("SEARCH FILES / JOB ID / TARGET", fontFamily = SpaceMonoFontFamily) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_search_input")
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filterOptions.forEach { option ->
                    FilterChip(
                        selected = statusFilter == option,
                        onClick = { statusFilter = option },
                        label = {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = SpaceMonoFontFamily
                            )
                        },
                        modifier = Modifier.testTag("filter_chip_${option.lowercase()}")
                    )
                }
            }

            if (tasks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                NothingOutlinedButton(
                    text = "CLEAR FINISHED HISTORY",
                    onClick = onClearFinished,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "clear_finished_history_button"
                )
            }
        }

        // Task Items List
        if (filteredTasks.isEmpty()) {
            NothingCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "NO TRANSFER RECORDS MATCHING FILTER",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select files in SOURCES and tap BACKUP NOW to start uploading to your configured Telegram destination.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            filteredTasks.forEach { task ->
                TransferTaskItemCard(
                    task = task,
                    onCancelTask = { onCancelTask(task.taskId) }
                )
            }
        }

        // Redacted Diagnostic Telemetry Logs Section
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "REDACTED TELEMETRY LOGS (${recentLogs.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "All bot tokens & keys are automatically stripped",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                NothingOutlinedButton(
                    text = if (showLogs) "HIDE" else "VIEW LOGS",
                    onClick = { showLogs = !showLogs },
                    testTag = "toggle_logs_button"
                )
            }

            if (showLogs) {
                Spacer(modifier = Modifier.height(10.dp))
                DottedDivider()
                Spacer(modifier = Modifier.height(8.dp))
                if (recentLogs.isEmpty()) {
                    Text(
                        text = "No diagnostic log events recorded in this session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    recentLogs.take(25).forEach { entry ->
                        Text(
                            text = "[${entry.level}] ${entry.tag}: ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.level == "ERROR") NothingRed else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = SpaceMonoFontFamily,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferTaskItemCard(
    task: TransferTaskEntity,
    onCancelTask: () -> Unit
) {
    val tone = when (task.status) {
        TransferStatus.COMPLETED.name -> StatusTone.VERIFIED_GREEN
        TransferStatus.RUNNING.name -> StatusTone.ACTIVE_RED
        TransferStatus.FAILED.name -> StatusTone.ACTIVE_RED
        TransferStatus.SKIPPED.name, TransferStatus.PAUSED.name -> StatusTone.WARNING_AMBER
        else -> StatusTone.NEUTRAL
    }

    NothingCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${task.jobId} • ${task.category} -> ${task.destinationTitle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = SpaceMonoFontFamily
                )
            }
            NothingStatusPill(label = task.status, tone = tone)
        }

        Spacer(modifier = Modifier.height(8.dp))
        DotMatrixProgressBar(
            progress = (task.progressPercent / 100f).coerceIn(0f, 1f),
            segments = 18
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${FormatUtils.formatBytes(task.bytesTransferred)} / ${FormatUtils.formatBytes(task.sizeBytes)} (${task.progressPercent}%)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (task.encrypted) "AES-256-GCM" else "PLAINTEXT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (task.telegramMessageId > 0L) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "TELEGRAM MSG #${task.telegramMessageId} • SHA256:${task.sha256Hash.take(12)} • ${VaultLogger.formatTimestamp(task.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (task.errorMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "INFO/ERR: ${task.errorMessage} (Retries: ${task.retryCount})",
                style = MaterialTheme.typography.bodySmall,
                color = if (task.status == TransferStatus.FAILED.name) NothingRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (task.status == TransferStatus.RUNNING.name || task.status == TransferStatus.QUEUED.name) {
            Spacer(modifier = Modifier.height(8.dp))
            NothingOutlinedButton(
                text = "CANCEL THIS FILE",
                onClick = onCancelTask,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
