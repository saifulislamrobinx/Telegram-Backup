package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.NothingVaultApplication
import com.example.R
import com.example.core.common.VaultLogger
import com.example.core.network.TelegramApiException
import java.util.concurrent.TimeUnit

/**
 * WorkManager CoroutineWorker for scheduled and user-initiated background synchronization.
 * Promotes to a foreground dataSync notification when actively transferring files,
 * and recovers unfinished jobs across process restarts.
 */
class NothingVaultSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NothingVaultApplication ?: return Result.failure()
        val container = app.container
        val token = container.credentialStore.getBotToken()
        if (token.isNullOrBlank()) {
            VaultLogger.w("SyncWorker", "Skipping background sync: bot token not configured")
            return Result.success()
        }

        try {
            setForeground(createForegroundInfo(0, 0, "Preparing vault synchronization..."))
        } catch (_: Exception) {
            // In background battery-restricted contexts or tests where foreground promotion is disallowed
        }

        // First recover any unfinished QUEUED or RUNNING tasks from process death
        val pendingBefore = container.database.vaultDao().getPendingOrRunningTasks()
        val syncResult = if (pendingBefore.isNotEmpty()) {
            VaultLogger.i("SyncWorker", "Recovering ${pendingBefore.size} unfinished transfer task(s)")
            container.transferEngine.processQueuedTasks(
                jobId = "RECOVER",
                onForegroundProgress = { done, total, currentName ->
                    try {
                        setForeground(createForegroundInfo(done, total, currentName))
                    } catch (_: Exception) {
                    }
                }
            )
        } else {
            container.transferEngine.enqueueAndExecuteBackup(
                onForegroundProgress = { done, total, currentName ->
                    try {
                        setForeground(createForegroundInfo(done, total, currentName))
                    } catch (_: Exception) {
                    }
                }
            )
        }

        return syncResult.fold(
            onSuccess = { count ->
                VaultLogger.i("SyncWorker", "Background sync worker finished ($count uploaded)")
                Result.success()
            },
            onFailure = { err ->
                if (err is TelegramApiException && (err.isNetworkFailure || err.isRateLimited)) {
                    VaultLogger.w("SyncWorker", "Transient error in worker, scheduling backoff retry")
                    Result.retry()
                } else {
                    VaultLogger.w("SyncWorker", "Sync worker completed with notice: ${err.message}")
                    Result.success()
                }
            }
        )
    }

    private fun createForegroundInfo(completed: Int, total: Int, currentFile: String): ForegroundInfo {
        ensureNotificationChannel(applicationContext)
        val title = if (total > 0) {
            "NothingVault Sync ($completed/$total)"
        } else {
            "NothingVault Background Sync"
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(currentFile)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), completed.coerceAtLeast(0), total <= 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "nothing_vault_sync_channel"
        const val NOTIFICATION_ID = 4091
        const val PERIODIC_WORK_NAME = "nothing_vault_periodic_sync"
        const val ONETIME_WORK_NAME = "nothing_vault_manual_sync"

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }
}

object SyncScheduler {

    fun scheduleOrCancelPeriodicSync(
        context: Context,
        enabled: Boolean,
        intervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean
    ): Long {
        val workManager = try {
            WorkManager.getInstance(context)
        } catch (_: Exception) {
            return 0L
        }

        if (!enabled) {
            workManager.cancelUniqueWork(NothingVaultSyncWorker.PERIODIC_WORK_NAME)
            VaultLogger.i("SyncScheduler", "Cancelled periodic background synchronization")
            return 0L
        }

        val safeHours = intervalHours.coerceAtLeast(1).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(chargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<NothingVaultSyncWorker>(
            safeHours,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            NothingVaultSyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )

        val nextRunAt = System.currentTimeMillis() + (safeHours * 3600_000L)
        VaultLogger.i(
            "SyncScheduler",
            "Scheduled periodic sync every ${safeHours}h (wifiOnly=$wifiOnly, chargingOnly=$chargingOnly)"
        )
        return nextRunAt
    }

    fun triggerImmediateWorkManagerSync(
        context: Context,
        wifiOnly: Boolean,
        chargingOnly: Boolean
    ) {
        val workManager = try {
            WorkManager.getInstance(context)
        } catch (_: Exception) {
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(chargingOnly)
            .build()

        val oneTime = OneTimeWorkRequestBuilder<NothingVaultSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            NothingVaultSyncWorker.ONETIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            oneTime
        )
        VaultLogger.i("SyncScheduler", "Enqueued immediate WorkManager synchronization")
    }

    fun stopAllBackgroundSync(context: Context) {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(NothingVaultSyncWorker.PERIODIC_WORK_NAME)
            wm.cancelUniqueWork(NothingVaultSyncWorker.ONETIME_WORK_NAME)
        } catch (_: Exception) {
        }
    }
}
