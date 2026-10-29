package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {

    // --- Telegram Destinations ---
    @Query("SELECT * FROM telegram_destinations ORDER BY isDefault DESC, lastTestedAt DESC")
    fun observeDestinations(): Flow<List<TelegramDestinationEntity>>

    @Query("SELECT * FROM telegram_destinations ORDER BY isDefault DESC, lastTestedAt DESC")
    suspend fun getDestinationsList(): List<TelegramDestinationEntity>

    @Query("SELECT * FROM telegram_destinations WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultDestination(): TelegramDestinationEntity?

    @Query("SELECT * FROM telegram_destinations WHERE chatId = :chatId LIMIT 1")
    suspend fun getDestinationById(chatId: String): TelegramDestinationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDestination(destination: TelegramDestinationEntity)

    @Query("UPDATE telegram_destinations SET isDefault = 0")
    suspend fun clearDefaultDestinations()

    @Query("UPDATE telegram_destinations SET isDefault = 1 WHERE chatId = :chatId")
    suspend fun markDestinationAsDefault(chatId: String)

    @Transaction
    suspend fun setDefaultDestination(chatId: String) {
        clearDefaultDestinations()
        markDestinationAsDefault(chatId)
    }

    @Query("DELETE FROM telegram_destinations WHERE chatId = :chatId")
    suspend fun deleteDestination(chatId: String)

    // --- Selected Source Items ---
    @Query("SELECT * FROM selected_sources ORDER BY addedAt DESC")
    fun observeSelectedSources(): Flow<List<SelectedSourceItemEntity>>

    @Query("SELECT * FROM selected_sources ORDER BY addedAt ASC")
    suspend fun getAllSelectedSources(): List<SelectedSourceItemEntity>

    @Query("SELECT * FROM selected_sources WHERE category = :category ORDER BY addedAt DESC")
    suspend fun getSelectedSourcesByCategory(category: String): List<SelectedSourceItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSelectedSource(item: SelectedSourceItemEntity): Long

    @Update
    suspend fun updateSelectedSource(item: SelectedSourceItemEntity)

    @Query("UPDATE selected_sources SET lastSyncedHash = :hash, lastSyncedAt = :timestamp WHERE id = :id")
    suspend fun markSourceSynced(id: Long, hash: String, timestamp: Long)

    @Query("DELETE FROM selected_sources WHERE id = :id")
    suspend fun deleteSelectedSource(id: Long)

    @Query("DELETE FROM selected_sources WHERE category = :category")
    suspend fun deleteSourcesByCategory(category: String)

    // --- Transfer Queue Tasks ---
    @Query("SELECT * FROM transfer_tasks ORDER BY createdAt DESC")
    fun observeTransferTasks(): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_tasks ORDER BY createdAt DESC")
    suspend fun getAllTransferTasks(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_tasks WHERE status IN ('QUEUED', 'RUNNING') ORDER BY createdAt ASC")
    suspend fun getPendingOrRunningTasks(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getTransferTaskById(taskId: String): TransferTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransferTask(task: TransferTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransferTasks(tasks: List<TransferTaskEntity>)

    @Query(
        """
        UPDATE transfer_tasks 
        SET status = :status, progressPercent = :progressPercent, bytesTransferred = :bytesTransferred, updatedAt = :updatedAt 
        WHERE taskId = :taskId
        """
    )
    suspend fun updateTaskProgress(
        taskId: String,
        status: String,
        progressPercent: Int,
        bytesTransferred: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE transfer_tasks 
        SET status = :status, progressPercent = 100, bytesTransferred = sizeBytes, 
            telegramMessageId = :messageId, telegramFileId = :fileId, errorMessage = '', updatedAt = :updatedAt
        WHERE taskId = :taskId
        """
    )
    suspend fun markTaskCompleted(
        taskId: String,
        status: String = TransferStatus.COMPLETED.name,
        messageId: Long,
        fileId: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE transfer_tasks 
        SET status = :status, retryCount = :retryCount, errorMessage = :errorMessage, updatedAt = :updatedAt
        WHERE taskId = :taskId
        """
    )
    suspend fun markTaskFailedOrSkipped(
        taskId: String,
        status: String,
        retryCount: Int,
        errorMessage: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE transfer_tasks SET status = 'CANCELLED', updatedAt = :now WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED')")
    suspend fun cancelAllActiveTasks(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM transfer_tasks WHERE status IN ('COMPLETED', 'SKIPPED', 'CANCELLED', 'FAILED')")
    suspend fun clearFinishedTasks()

    @Query("DELETE FROM transfer_tasks")
    suspend fun clearAllTasks()

    // --- Backup Manifests & Deduplication ---
    @Query("SELECT * FROM backup_manifests ORDER BY timestamp DESC")
    fun observeManifests(): Flow<List<BackupManifestEntity>>

    @Query("SELECT * FROM backup_manifests ORDER BY timestamp DESC")
    suspend fun getAllManifests(): List<BackupManifestEntity>

    @Query(
        """
        SELECT * FROM backup_manifests 
        WHERE contentSha256 = :sha256 
          AND destinationChatId = :destinationChatId 
          AND isEncrypted = :isEncrypted 
          AND uploadStatus = 'COMPLETED'
        LIMIT 1
        """
    )
    suspend fun findExistingManifestByHash(
        sha256: String,
        destinationChatId: String,
        isEncrypted: Boolean
    ): BackupManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManifest(manifest: BackupManifestEntity): Long

    @Query("UPDATE backup_manifests SET localRestorePath = :restorePath WHERE manifestId = :manifestId")
    suspend fun updateManifestRestorePath(manifestId: Long, restorePath: String)

    @Query("DELETE FROM backup_manifests")
    suspend fun clearAllManifests()
}
