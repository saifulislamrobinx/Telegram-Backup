package com.example.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BackupCategory(
    val code: String,
    val displayName: String,
    val description: String,
    val permissionNote: String
) {
    PHOTOS(
        code = "IMG",
        displayName = "Photos & Images",
        description = "Full-resolution images via Android Photo Picker or authorized SAF folders. SHA-256 deduplicated.",
        permissionNote = "Zero-permission Photo Picker & Storage Access Framework"
    ),
    VIDEOS(
        code = "VID",
        displayName = "Videos",
        description = "User-selected clips and authorized video folders with duration & size validation.",
        permissionNote = "Zero-permission Photo Picker & Storage Access Framework"
    ),
    DOCUMENTS(
        code = "DOC",
        displayName = "Documents & Archives",
        description = "PDFs, text, office files, ZIP archives, and authorized document directories.",
        permissionNote = "Zero-permission Storage Access Framework"
    ),
    AUDIO(
        code = "AUD",
        displayName = "Audio & Voice Notes",
        description = "User-selected music and voice recordings. Never captures call audio or protected streams.",
        permissionNote = "Zero-permission Storage Access Framework"
    ),
    CONTACTS(
        code = "CNT",
        displayName = "Contacts Export",
        description = "Exports device contacts into structured VCF 3.0 or JSON with customizable field exclusions.",
        permissionNote = "Requires runtime READ_CONTACTS permission & explicit opt-in"
    ),
    CALENDAR(
        code = "CAL",
        displayName = "Calendar Events",
        description = "Exports authorized device calendar events into structured ICS/JSON snapshots.",
        permissionNote = "Requires runtime READ_CALENDAR permission & explicit opt-in"
    ),
    APP_EXPORTS(
        code = "APP",
        displayName = "App-Specific Exports",
        description = "User-accessible app backup archives. Respects Android application sandboxing.",
        permissionNote = "Storage Access Framework (Android sandboxing prevents reading private internal app storage)"
    )
}

enum class TransferStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    SKIPPED,
    FAILED,
    PAUSED,
    CANCELLED
}

@Entity(tableName = "telegram_destinations")
data class TelegramDestinationEntity(
    @PrimaryKey
    val chatId: String, // e.g. "-1001234567890", "123456789", or "@channelusername"
    val title: String,
    val username: String = "",
    val chatType: String = "PRIVATE", // PRIVATE, GROUP, SUPERGROUP, CHANNEL
    val isDefault: Boolean = false,
    val isVerified: Boolean = false,
    val canSendDocuments: Boolean = true,
    val lastTestedAt: Long = 0L,
    val notes: String = ""
)

@Entity(
    tableName = "selected_sources",
    indices = [Index(value = ["uriString"], unique = true), Index(value = ["sha256Hash"])]
)
data class SelectedSourceItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val uriString: String,
    val displayName: String,
    val category: String, // BackupCategory.name
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long = 0L,
    val sha256Hash: String = "",
    val isFolderTree: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSyncedHash: String = "",
    val lastSyncedAt: Long = 0L
)

@Entity(
    tableName = "transfer_tasks",
    indices = [Index(value = ["jobId"]), Index(value = ["status"]), Index(value = ["sha256Hash"])]
)
data class TransferTaskEntity(
    @PrimaryKey
    val taskId: String,
    val jobId: String,
    val sourceItemId: Long,
    val uriString: String,
    val fileName: String,
    val category: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Hash: String,
    val destinationChatId: String,
    val destinationTitle: String,
    val encrypted: Boolean,
    val status: String, // TransferStatus.name
    val progressPercent: Int = 0,
    val bytesTransferred: Long = 0L,
    val retryCount: Int = 0,
    val errorMessage: String = "",
    val telegramMessageId: Long = 0L,
    val telegramFileId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "backup_manifests",
    indices = [Index(value = ["backupId"]), Index(value = ["contentSha256"]), Index(value = ["destinationChatId"])]
)
data class BackupManifestEntity(
    @PrimaryKey(autoGenerate = true)
    val manifestId: Long = 0L,
    val backupId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val destinationChatId: String,
    val destinationTitle: String,
    val originalFileName: String,
    val uploadedFileName: String,
    val fileSize: Long,
    val mimeType: String,
    val contentSha256: String,
    val isEncrypted: Boolean,
    val encryptionSaltHex: String = "",
    val encryptionIvHex: String = "",
    val telegramMessageId: Long = 0L,
    val telegramFileId: String = "",
    val category: String,
    val uploadStatus: String = "COMPLETED",
    val localRestorePath: String = ""
)
