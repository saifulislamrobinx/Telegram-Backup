package com.example.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NothingVaultApplication
import com.example.core.common.VaultLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dedicated Foreground Service for user-initiated long-running Telegram file transfers.
 * Displays real-time progress notifications and provides an accessible "Stop / Cancel"
 * action directly from the system notification shade.
 */
class NothingVaultTransferService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NothingVaultSyncWorker.ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as? NothingVaultApplication
        if (intent?.action == ACTION_CANCEL_TRANSFERS) {
            VaultLogger.i("TransferService", "User triggered transfer cancellation from notification")
            app?.container?.transferEngine?.cancelAllActiveTransfers()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = buildTransferNotification(
            title = "NothingVault Transfer Active",
            statusLine = "Initializing Telegram upload engine...",
            progressPercent = 0,
            indeterminate = true
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SERVICE_NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            VaultLogger.w("TransferService", "Could not promote service to foreground: ${e.message}")
        }

        val explicitDest = intent?.getStringExtra(EXTRA_EXPLICIT_DESTINATION)
        val forceDuplicates = intent?.getBooleanExtra(EXTRA_FORCE_DUPLICATES, false) ?: false

        if (app != null) {
            observeEngineProgress(app)
            app.container.transferEngine.triggerBackupNow(
                explicitDestinationChatId = explicitDest,
                forceUploadDuplicates = forceDuplicates,
                onResult = {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            )
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun observeEngineProgress(app: NothingVaultApplication) {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            app.container.transferEngine.progressSnapshot.collectLatest { snapshot ->
                if (snapshot.isRunning) {
                    val pct = (snapshot.overallProgressFraction * 100).toInt().coerceIn(0, 100)
                    val notification = buildTransferNotification(
                        title = "NothingVault (${snapshot.completedTasksInRun}/${snapshot.totalTasksInRun})",
                        statusLine = snapshot.currentStatusLine,
                        progressPercent = pct,
                        indeterminate = snapshot.totalTasksInRun == 0
                    )
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.notify(SERVICE_NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun buildTransferNotification(
        title: String,
        statusLine: String,
        progressPercent: Int,
        indeterminate: Boolean
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, NothingVaultTransferService::class.java).apply {
            action = ACTION_CANCEL_TRANSFERS
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            101,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NothingVaultSyncWorker.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(statusLine)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent, indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "STOP SYNC",
                cancelPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val SERVICE_NOTIFICATION_ID = 4092
        const val ACTION_START_TRANSFERS = "com.example.action.START_TRANSFERS"
        const val ACTION_CANCEL_TRANSFERS = "com.example.action.CANCEL_TRANSFERS"
        const val EXTRA_EXPLICIT_DESTINATION = "extra_explicit_destination"
        const val EXTRA_FORCE_DUPLICATES = "extra_force_duplicates"

        fun startUserInitiatedBackup(
            context: Context,
            explicitDestinationChatId: String? = null,
            forceDuplicates: Boolean = false
        ): Boolean {
            return try {
                val intent = Intent(context, NothingVaultTransferService::class.java).apply {
                    action = ACTION_START_TRANSFERS
                    if (!explicitDestinationChatId.isNullOrBlank()) {
                        putExtra(EXTRA_EXPLICIT_DESTINATION, explicitDestinationChatId)
                    }
                    putExtra(EXTRA_FORCE_DUPLICATES, forceDuplicates)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
