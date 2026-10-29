package com.example.data

import android.content.Context
import android.net.Uri
import com.example.core.common.FormatUtils
import com.example.core.common.VaultLogger
import com.example.core.database.BackupCategory
import com.example.core.database.BackupManifestEntity
import com.example.core.database.SelectedSourceItemEntity
import com.example.core.database.TelegramDestinationEntity
import com.example.core.database.TransferStatus
import com.example.core.database.TransferTaskEntity
import com.example.core.database.VaultDao
import com.example.core.network.TelegramApiException
import com.example.core.network.TelegramBotClient
import com.example.core.security.SecureCredentialStore
import com.example.core.security.VaultCryptoEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class EngineProgressSnapshot(
    val isRunning: Boolean = false,
    val activeJobId: String = "",
    val totalTasksInRun: Int = 0,
    val completedTasksInRun: Int = 0,
    val overallProgressFraction: Float = 0f,
    val currentStatusLine: String = "IDLE // READY"
)

data class RestoreResult(
    val manifestId: Long,
    val restoredFileName: String,
    val outputLocation: String,
    val sha256Verified: Boolean,
    val bytesRestored: Long
)

class TransferEngine(
    private val context: Context,
    private val vaultDao: VaultDao,
    private val preferencesRepository: VaultPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val telegramClient: TelegramBotClient,
    private val contentExporter: DeviceContentExporter
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val cancelledTaskIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var pauseRequested: Boolean = false

    @Volatile
    private var cancelAllRequested: Boolean = false

    private val _progressSnapshot = MutableStateFlow(EngineProgressSnapshot())
    val progressSnapshot: StateFlow<EngineProgressSnapshot> = _progressSnapshot.asStateFlow()

    /**
     * Resolves the effective destination for a backup job:
     * 1. Explicit destinationChatId passed to the call
     * 2. Per-job override stored in preferences (if non-empty)
     * 3. Global default destination in Room
     */
    suspend fun resolveTargetDestination(explicitChatId: String? = null): TelegramDestinationEntity? {
        if (!explicitChatId.isNullOrBlank()) {
            vaultDao.getDestinationById(explicitChatId)?.let { return it }
        }
        val settings = preferencesRepository.getSnapshot()
        if (settings.jobDestinationOverrideChatId.isNotBlank()) {
            vaultDao.getDestinationById(settings.jobDestinationOverrideChatId)?.let { return it }
        }
        return vaultDao.getDefaultDestination() ?: vaultDao.getDestinationsList().firstOrNull()
    }

    /**
     * Enqueues all eligible files from enabled categories into `transfer_tasks` and starts
     * asynchronous execution.
     */
    fun triggerBackupNow(
        explicitDestinationChatId: String? = null,
        forceUploadDuplicates: Boolean = false,
        onResult: (Result<Int>) -> Unit = {}
    ) {
        engineScope.launch {
            val result = enqueueAndExecuteBackup(
                explicitDestinationChatId = explicitDestinationChatId,
                forceUploadDuplicates = forceUploadDuplicates
            )
            onResult(result)
        }
    }

    suspend fun enqueueAndExecuteBackup(
        explicitDestinationChatId: String? = null,
        forceUploadDuplicates: Boolean = false,
        onForegroundProgress: (suspend (completed: Int, total: Int, currentFile: String) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        val token = credentialStore.getBotToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Telegram Bot token is not configured. Connect your bot first.")
            )
        }

        val destination = resolveTargetDestination(explicitDestinationChatId)
            ?: return@withContext Result.failure(
                IllegalStateException("No Telegram destination configured. Add and select a destination first.")
            )

        val settings = preferencesRepository.getSnapshot()
        if (settings.encryptionEnabled && credentialStore.getEncryptionPassphrase().isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Encryption is enabled, but no AES-256-GCM passphrase is set in Security settings.")
            )
        }

        // Refresh Contacts / Calendar snapshots if those categories are enabled and permitted
        if (settings.enabledCategories.contains(BackupCategory.CONTACTS.name)) {
            contentExporter.exportContactsSnapshot(
                format = settings.contactExportFormat,
                excludePhones = settings.excludeContactPhones,
                excludeEmails = settings.excludeContactEmails,
                excludeOrg = settings.excludeContactOrg,
                excludeNotes = settings.excludeContactNotes
            ).onSuccess { contactEntity ->
                vaultDao.deleteSourcesByCategory(BackupCategory.CONTACTS.name)
                vaultDao.insertSelectedSource(contactEntity)
            }
        }

        if (settings.enabledCategories.contains(BackupCategory.CALENDAR.name)) {
            contentExporter.exportCalendarSnapshot().onSuccess { calEntity ->
                vaultDao.deleteSourcesByCategory(BackupCategory.CALENDAR.name)
                vaultDao.insertSelectedSource(calEntity)
            }
        }

        val allSources = vaultDao.getAllSelectedSources()
            .filter { settings.enabledCategories.contains(it.category) }

        if (allSources.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No files selected in enabled backup categories. Select files in SOURCES first.")
            )
        }

        // Expand any SAF folder trees into concrete file items
        val expandedSources = mutableListOf<SelectedSourceItemEntity>()
        for (source in allSources) {
            if (source.isFolderTree) {
                expandedSources.addAll(contentExporter.expandFolderTreeChildren(source))
            } else {
                expandedSources.add(source)
            }
        }

        if (expandedSources.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Selected folders contain no readable files.")
            )
        }

        val jobId = "JOB-${UUID.randomUUID().toString().take(8).uppercase()}"
        val now = System.currentTimeMillis()
        val tasksToInsert = expandedSources.mapIndexed { idx, src ->
            TransferTaskEntity(
                taskId = "${jobId}-T${idx + 1}-${System.nanoTime().toString().takeLast(4)}",
                jobId = jobId,
                sourceItemId = src.id,
                uriString = src.uriString,
                fileName = src.displayName,
                category = src.category,
                mimeType = src.mimeType,
                sizeBytes = src.sizeBytes,
                sha256Hash = src.sha256Hash,
                destinationChatId = destination.chatId,
                destinationTitle = destination.title,
                encrypted = settings.encryptionEnabled,
                status = TransferStatus.QUEUED.name,
                progressPercent = 0,
                bytesTransferred = 0L,
                createdAt = now + idx,
                updatedAt = now + idx
            )
        }

        vaultDao.upsertTransferTasks(tasksToInsert)
        VaultLogger.i("TransferEngine", "Enqueued ${tasksToInsert.size} transfer task(s) for $jobId -> ${destination.title}")

        processQueuedTasks(
            jobId = jobId,
            forceUploadDuplicates = forceUploadDuplicates,
            onForegroundProgress = onForegroundProgress
        )
    }

    suspend fun processQueuedTasks(
        jobId: String = "RESUME",
        forceUploadDuplicates: Boolean = false,
        onForegroundProgress: (suspend (completed: Int, total: Int, currentFile: String) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runMutex.withLock {
            pauseRequested = false
            cancelAllRequested = false
            cancelledTaskIds.clear()

            val token = credentialStore.getBotToken()
                ?: return@withLock Result.failure(IllegalStateException("Missing Bot token"))
            val settings = preferencesRepository.getSnapshot()
            val passphrase = credentialStore.getEncryptionPassphrase()

            val pendingTasks = vaultDao.getPendingOrRunningTasks()
            if (pendingTasks.isEmpty()) {
                _progressSnapshot.value = EngineProgressSnapshot()
                return@withLock Result.success(0)
            }

            val totalCount = pendingTasks.size
            var finishedCount = 0
            val completedManifestsInJob = mutableListOf<BackupManifestEntity>()
            val manifestMutex = Mutex()

            _progressSnapshot.value = EngineProgressSnapshot(
                isRunning = true,
                activeJobId = jobId,
                totalTasksInRun = totalCount,
                completedTasksInRun = 0,
                overallProgressFraction = 0f,
                currentStatusLine = "SYNCHRONIZING $totalCount FILE(S)..."
            )

            val semaphore = Semaphore(settings.concurrentUploads.coerceIn(1, 4))

            coroutineScope {
                val jobs = pendingTasks.map { task ->
                    async {
                        semaphore.withPermit {
                            if (cancelAllRequested || cancelledTaskIds.contains(task.taskId)) {
                                vaultDao.markTaskFailedOrSkipped(
                                    taskId = task.taskId,
                                    status = TransferStatus.CANCELLED.name,
                                    retryCount = task.retryCount,
                                    errorMessage = "Cancelled by user"
                                )
                                return@withPermit
                            }
                            if (pauseRequested) {
                                vaultDao.markTaskFailedOrSkipped(
                                    taskId = task.taskId,
                                    status = TransferStatus.PAUSED.name,
                                    retryCount = task.retryCount,
                                    errorMessage = "Paused by user"
                                )
                                return@withPermit
                            }

                            onForegroundProgress?.invoke(finishedCount, totalCount, task.fileName)

                            val manifestOrNull = executeSingleTask(
                                task = task,
                                botToken = token,
                                passphrase = passphrase,
                                skipDuplicates = settings.skipDuplicates && !forceUploadDuplicates,
                                splitOversized = settings.splitOversizedFiles
                            )

                            manifestMutex.withLock {
                                finishedCount++
                                if (manifestOrNull != null) {
                                    completedManifestsInJob.add(manifestOrNull)
                                }
                                val fraction = finishedCount.toFloat() / totalCount.coerceAtLeast(1)
                                _progressSnapshot.value = _progressSnapshot.value.copy(
                                    completedTasksInRun = finishedCount,
                                    overallProgressFraction = fraction,
                                    currentStatusLine = "PROCESSED $finishedCount / $totalCount // ${task.fileName}"
                                )
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }

            // Upload structured Backup Manifest JSON (or encrypted manifest) if at least 1 file uploaded
            if (completedManifestsInJob.isNotEmpty() && !cancelAllRequested && !pauseRequested) {
                uploadBackupManifestSummary(
                    jobId = jobId,
                    manifests = completedManifestsInJob,
                    botToken = token,
                    encryptManifest = settings.encryptionEnabled && settings.encryptManifestEnabled,
                    passphrase = passphrase
                )
                val now = System.currentTimeMillis()
                val nextAt = if (settings.autoSyncEnabled) {
                    now + settings.syncIntervalHours * 3600_000L
                } else {
                    0L
                }
                preferencesRepository.recordSuccessfulSync(now, nextAt)
            }

            _progressSnapshot.value = EngineProgressSnapshot(
                isRunning = false,
                activeJobId = jobId,
                totalTasksInRun = totalCount,
                completedTasksInRun = finishedCount,
                overallProgressFraction = 1f,
                currentStatusLine = "SYNC COMPLETE // ${completedManifestsInJob.size} UPLOADED"
            )
            Result.success(completedManifestsInJob.size)
        }
    }

    private suspend fun executeSingleTask(
        task: TransferTaskEntity,
        botToken: String,
        passphrase: String,
        skipDuplicates: Boolean,
        splitOversized: Boolean
    ): BackupManifestEntity? {
        var stagedFile: File? = null
        var encryptedFile: File? = null
        val chunkFiles = mutableListOf<File>()

        try {
            vaultDao.updateTaskProgress(
                taskId = task.taskId,
                status = TransferStatus.RUNNING.name,
                progressPercent = 2,
                bytesTransferred = 0L
            )

            // Stage URI to local cache and compute/verify SHA-256
            val stagedPair = contentExporter.stageUriToCacheFile(task.uriString, task.fileName)
            if (stagedPair == null) {
                vaultDao.markTaskFailedOrSkipped(
                    taskId = task.taskId,
                    status = TransferStatus.FAILED.name,
                    retryCount = task.retryCount,
                    errorMessage = "Source file inaccessible or permission revoked"
                )
                return null
            }

            stagedFile = stagedPair.first
            val contentSha256 = stagedPair.second.ifBlank { task.sha256Hash }
            val originalSize = stagedFile.length()

            // Duplicate check via SHA-256 content hash
            if (skipDuplicates && contentSha256.isNotBlank()) {
                val existing = vaultDao.findExistingManifestByHash(
                    sha256 = contentSha256,
                    destinationChatId = task.destinationChatId,
                    isEncrypted = task.encrypted
                )
                if (existing != null) {
                    VaultLogger.i("TransferEngine", "Skipping unchanged duplicate file ${task.fileName} (SHA-256=${contentSha256.take(10)}...)")
                    vaultDao.markTaskFailedOrSkipped(
                        taskId = task.taskId,
                        status = TransferStatus.SKIPPED.name,
                        retryCount = task.retryCount,
                        errorMessage = "Skipped: identical SHA-256 already backed up (msg #${existing.telegramMessageId})"
                    )
                    return null
                }
            }

            // Optional local AES-256-GCM encryption
            var uploadTargetFile: File = stagedFile
            var uploadFileName = task.fileName
            var uploadMime = task.mimeType
            var saltHex = ""
            var ivHex = ""

            if (task.encrypted) {
                if (passphrase.isBlank()) {
                    vaultDao.markTaskFailedOrSkipped(
                        taskId = task.taskId,
                        status = TransferStatus.FAILED.name,
                        retryCount = task.retryCount,
                        errorMessage = "Encryption enabled but master passphrase is missing"
                    )
                    return null
                }
                val encTarget = File(context.cacheDir, "${stagedFile.name}.nvenc")
                val encMeta = VaultCryptoEngine.encryptFile(
                    sourceFile = stagedFile,
                    targetEncryptedFile = encTarget,
                    passphrase = passphrase
                )
                encryptedFile = encTarget
                uploadTargetFile = encTarget
                uploadFileName = "${task.fileName}.nvenc"
                uploadMime = "application/octet-stream"
                saltHex = encMeta.saltHex
                ivHex = encMeta.ivHex
            }

            // Validate against Telegram Bot API 50 MB limit
            if (uploadTargetFile.length() > TelegramBotClient.MAX_BOT_UPLOAD_BYTES) {
                if (!splitOversized) {
                    vaultDao.markTaskFailedOrSkipped(
                        taskId = task.taskId,
                        status = TransferStatus.FAILED.name,
                        retryCount = task.retryCount,
                        errorMessage = "Exceeds 50 MB Telegram Bot API limit (${FormatUtils.formatBytes(uploadTargetFile.length())}). Enable multi-part archive splitting in Config."
                    )
                    return null
                }
                // Split into 45 MB parts and upload sequentially
                val parts = contentExporter.splitFileIntoChunks(
                    sourceFile = uploadTargetFile,
                    originalFileName = uploadFileName,
                    chunkSizeBytes = TelegramBotClient.ARCHIVE_PART_SIZE_BYTES
                )
                chunkFiles.addAll(parts)
                var lastUploadResult = uploadWithRetryAndFloodControl(
                    task = task,
                    botToken = botToken,
                    fileToUpload = parts.first(),
                    uploadFileName = parts.first().name,
                    mimeType = "application/octet-stream",
                    caption = "[NothingVault] ${task.jobId} | Part 1/${parts.size} | ${task.fileName}"
                ).getOrElse { err ->
                    vaultDao.markTaskFailedOrSkipped(
                        taskId = task.taskId,
                        status = TransferStatus.FAILED.name,
                        retryCount = task.retryCount + 1,
                        errorMessage = err.message ?: "Part 1 upload failed"
                    )
                    return null
                }

                for (i in 1 until parts.size) {
                    val part = parts[i]
                    lastUploadResult = uploadWithRetryAndFloodControl(
                        task = task,
                        botToken = botToken,
                        fileToUpload = part,
                        uploadFileName = part.name,
                        mimeType = "application/octet-stream",
                        caption = "[NothingVault] ${task.jobId} | Part ${i + 1}/${parts.size} | ${task.fileName}"
                    ).getOrElse { err ->
                        vaultDao.markTaskFailedOrSkipped(
                            taskId = task.taskId,
                            status = TransferStatus.FAILED.name,
                            retryCount = task.retryCount + 1,
                            errorMessage = err.message ?: "Part ${i + 1} upload failed"
                        )
                        return null
                    }
                }

                return recordSuccessAndManifest(
                    task = task,
                    uploadedFileName = "${uploadFileName} (${parts.size} parts)",
                    originalSize = originalSize,
                    contentSha256 = contentSha256,
                    saltHex = saltHex,
                    ivHex = ivHex,
                    messageId = lastUploadResult.messageId,
                    fileId = lastUploadResult.fileId
                )
            }

            // Standard single-file upload with retry & flood-control handling
            val caption = buildString {
                append("[NothingVault] ")
                append(task.category)
                append(" // ")
                append(task.fileName)
                append("\nSHA256: ")
                append(contentSha256.take(16))
                if (task.encrypted) append(" [AES-256-GCM]")
            }

            val uploadRes = uploadWithRetryAndFloodControl(
                task = task,
                botToken = botToken,
                fileToUpload = uploadTargetFile,
                uploadFileName = uploadFileName,
                mimeType = uploadMime,
                caption = caption
            ).getOrElse { err ->
                val isCancelled = err.message?.contains("cancelled", ignoreCase = true) == true
                vaultDao.markTaskFailedOrSkipped(
                    taskId = task.taskId,
                    status = if (isCancelled) TransferStatus.CANCELLED.name else TransferStatus.FAILED.name,
                    retryCount = task.retryCount + 1,
                    errorMessage = VaultLogger.redact(err.message ?: "Upload failed")
                )
                return null
            }

            return recordSuccessAndManifest(
                task = task,
                uploadedFileName = uploadFileName,
                originalSize = originalSize,
                contentSha256 = contentSha256,
                saltHex = saltHex,
                ivHex = ivHex,
                messageId = uploadRes.messageId,
                fileId = uploadRes.fileId
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            VaultLogger.e("TransferEngine", "Unexpected error on task ${task.taskId}", e)
            vaultDao.markTaskFailedOrSkipped(
                taskId = task.taskId,
                status = TransferStatus.FAILED.name,
                retryCount = task.retryCount + 1,
                errorMessage = VaultLogger.redact(e.message ?: "Unexpected transfer error")
            )
            return null
        } finally {
            stagedFile?.delete()
            encryptedFile?.delete()
            chunkFiles.forEach { it.delete() }
        }
    }

    private suspend fun uploadWithRetryAndFloodControl(
        task: TransferTaskEntity,
        botToken: String,
        fileToUpload: File,
        uploadFileName: String,
        mimeType: String,
        caption: String
    ) = coroutineScope {
        var attempt = 0
        val maxAttempts = 3
        var lastResult = telegramClient.uploadDocument(
            rawToken = botToken,
            chatId = task.destinationChatId,
            file = fileToUpload,
            uploadFileName = uploadFileName,
            mimeType = mimeType,
            caption = caption,
            isCancelled = { cancelAllRequested || pauseRequested || cancelledTaskIds.contains(task.taskId) },
            onProgress = { written, total ->
                val pct = ((written * 100L) / total.coerceAtLeast(1L)).toInt().coerceIn(1, 99)
                engineScope.launch {
                    vaultDao.updateTaskProgress(
                        taskId = task.taskId,
                        status = TransferStatus.RUNNING.name,
                        progressPercent = pct,
                        bytesTransferred = written
                    )
                }
            }
        )

        while (lastResult.isFailure && attempt < maxAttempts - 1) {
            val ex = lastResult.exceptionOrNull()
            if (cancelAllRequested || pauseRequested || cancelledTaskIds.contains(task.taskId)) {
                break
            }
            if (ex is TelegramApiException) {
                if (ex.isUnauthorizedToken || ex.isPermissionDenied || ex.errorCode == 413) {
                    // Non-retryable authentication, permission, or file-size error
                    break
                }
                val waitMillis = if (ex.isRateLimited) {
                    ((ex.retryAfterSeconds ?: 3) + 1) * 1000L
                } else {
                    (1L shl attempt) * 1500L // Exponential backoff: 1.5s, 3.0s
                }
                VaultLogger.w(
                    "TransferEngine",
                    "Retrying ${task.fileName} after ${waitMillis}ms (attempt ${attempt + 2}/$maxAttempts)"
                )
                delay(waitMillis)
            } else {
                delay((1L shl attempt) * 1500L)
            }

            attempt++
            lastResult = telegramClient.uploadDocument(
                rawToken = botToken,
                chatId = task.destinationChatId,
                file = fileToUpload,
                uploadFileName = uploadFileName,
                mimeType = mimeType,
                caption = caption,
                isCancelled = { cancelAllRequested || pauseRequested || cancelledTaskIds.contains(task.taskId) },
                onProgress = { written, total ->
                    val pct = ((written * 100L) / total.coerceAtLeast(1L)).toInt().coerceIn(1, 99)
                    engineScope.launch {
                        vaultDao.updateTaskProgress(
                            taskId = task.taskId,
                            status = TransferStatus.RUNNING.name,
                            progressPercent = pct,
                            bytesTransferred = written
                        )
                    }
                }
            )
        }
        lastResult
    }

    private suspend fun recordSuccessAndManifest(
        task: TransferTaskEntity,
        uploadedFileName: String,
        originalSize: Long,
        contentSha256: String,
        saltHex: String,
        ivHex: String,
        messageId: Long,
        fileId: String
    ): BackupManifestEntity {
        val now = System.currentTimeMillis()
        vaultDao.markTaskCompleted(
            taskId = task.taskId,
            status = TransferStatus.COMPLETED.name,
            messageId = messageId,
            fileId = fileId,
            updatedAt = now
        )
        if (task.sourceItemId > 0L) {
            vaultDao.markSourceSynced(task.sourceItemId, contentSha256, now)
        }
        val manifest = BackupManifestEntity(
            backupId = task.jobId,
            timestamp = now,
            destinationChatId = task.destinationChatId,
            destinationTitle = task.destinationTitle,
            originalFileName = task.fileName,
            uploadedFileName = uploadedFileName,
            fileSize = originalSize,
            mimeType = task.mimeType,
            contentSha256 = contentSha256,
            isEncrypted = task.encrypted,
            encryptionSaltHex = saltHex,
            encryptionIvHex = ivHex,
            telegramMessageId = messageId,
            telegramFileId = fileId,
            category = task.category,
            uploadStatus = "COMPLETED"
        )
        val id = vaultDao.insertManifest(manifest)
        return manifest.copy(manifestId = id)
    }

    private suspend fun uploadBackupManifestSummary(
        jobId: String,
        manifests: List<BackupManifestEntity>,
        botToken: String,
        encryptManifest: Boolean,
        passphrase: String
    ) {
        try {
            val destChatId = manifests.first().destinationChatId
            val arr = JSONArray()
            manifests.forEach { m ->
                arr.put(
                    JSONObject().apply {
                        put("backupId", m.backupId)
                        put("timestamp", m.timestamp)
                        put("destinationChatId", m.destinationChatId)
                        put("originalFileName", m.originalFileName)
                        put("uploadedFileName", m.uploadedFileName)
                        put("fileSize", m.fileSize)
                        put("mimeType", m.mimeType)
                        put("contentSha256", m.contentSha256)
                        put("isEncrypted", m.isEncrypted)
                        put("telegramMessageId", m.telegramMessageId)
                        put("telegramFileId", m.telegramFileId)
                        put("category", m.category)
                        put("uploadStatus", m.uploadStatus)
                    }
                )
            }
            val manifestJson = JSONObject().apply {
                put("schema", "NothingVault.Manifest.v1")
                put("backupId", jobId)
                put("generatedAt", System.currentTimeMillis())
                put("itemCount", manifests.size)
                put("items", arr)
            }.toString(2)

            val tempDir = File(context.cacheDir, "manifests").apply { mkdirs() }
            val plainManifestFile = File(tempDir, "manifest_$jobId.json")
            plainManifestFile.writeText(manifestJson, Charsets.UTF_8)

            val fileToSend: File
            val nameToSend: String
            if (encryptManifest && passphrase.isNotBlank()) {
                val encFile = File(tempDir, "manifest_$jobId.json.nvenc")
                VaultCryptoEngine.encryptFile(plainManifestFile, encFile, passphrase)
                fileToSend = encFile
                nameToSend = encFile.name
            } else {
                fileToSend = plainManifestFile
                nameToSend = plainManifestFile.name
            }

            telegramClient.uploadDocument(
                rawToken = botToken,
                chatId = destChatId,
                file = fileToSend,
                uploadFileName = nameToSend,
                mimeType = "application/json",
                caption = "[NothingVault Manifest] $jobId // ${manifests.size} item(s)"
            )
            plainManifestFile.delete()
            if (fileToSend != plainManifestFile) fileToSend.delete()
        } catch (e: Exception) {
            VaultLogger.w("TransferEngine", "Could not upload remote manifest summary: ${e.message}")
        }
    }

    fun pauseActiveTransfers() {
        pauseRequested = true
        _progressSnapshot.value = _progressSnapshot.value.copy(
            isRunning = false,
            currentStatusLine = "PAUSED BY USER"
        )
    }

    fun cancelSingleTask(taskId: String) {
        cancelledTaskIds.add(taskId)
        engineScope.launch {
            val task = vaultDao.getTransferTaskById(taskId)
            if (task != null) {
                vaultDao.markTaskFailedOrSkipped(
                    taskId = taskId,
                    status = TransferStatus.CANCELLED.name,
                    retryCount = task.retryCount,
                    errorMessage = "Cancelled by user"
                )
            }
        }
    }

    fun cancelAllActiveTransfers() {
        cancelAllRequested = true
        engineScope.launch {
            vaultDao.cancelAllActiveTasks()
            _progressSnapshot.value = EngineProgressSnapshot(
                isRunning = false,
                currentStatusLine = "ALL ACTIVE TRANSFERS CANCELLED"
            )
        }
    }

    fun retryFailedAndPausedTasks() {
        engineScope.launch {
            val all = vaultDao.getAllTransferTasks()
            val retryable = all.filter {
                it.status == TransferStatus.FAILED.name || it.status == TransferStatus.PAUSED.name
            }
            if (retryable.isEmpty()) return@launch
            val now = System.currentTimeMillis()
            val reset = retryable.map {
                it.copy(
                    status = TransferStatus.QUEUED.name,
                    progressPercent = 0,
                    bytesTransferred = 0L,
                    errorMessage = "",
                    updatedAt = now
                )
            }
            vaultDao.upsertTransferTasks(reset)
            processQueuedTasks(jobId = "RETRY-${now.toString().takeLast(4)}")
        }
    }

    /**
     * Downloads a backed-up file from Telegram using its recorded `telegramFileId`,
     * decrypts it with AES-256-GCM if `manifest.isEncrypted` is true,
     * verifies the plaintext SHA-256 hash against `manifest.contentSha256`,
     * and writes it to `targetDocumentUri` (if selected by user via SAF) or app restore storage.
     */
    suspend fun restoreFromManifest(
        manifest: BackupManifestEntity,
        passphraseOverride: String? = null,
        targetDocumentUri: Uri? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<RestoreResult> = withContext(Dispatchers.IO) {
        val token = credentialStore.getBotToken()
            ?: return@withContext Result.failure(
                IllegalStateException("Telegram Bot token is not connected.")
            )

        if (manifest.telegramFileId.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Manifest entry does not have a downloadable Telegram file_id.")
            )
        }

        val tempDir = File(context.cacheDir, "vault_restore_tmp").apply { mkdirs() }
        val downloadedTemp = File(tempDir, "dl_${System.nanoTime()}_${manifest.uploadedFileName}")

        try {
            val dlResult = telegramClient.downloadFileById(
                rawToken = token,
                fileId = manifest.telegramFileId,
                destinationFile = downloadedTemp,
                onProgress = { read, total ->
                    onProgress((read.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 0.85f))
                }
            )
            dlResult.getOrElse { return@withContext Result.failure(it) }

            val restoreDir = File(context.filesDir, "NothingVault_Restored").apply { mkdirs() }
            val safeFileName = manifest.originalFileName.replace(Regex("""[^a-zA-Z0-9._ -]"""), "_")
            val finalLocalFile = File(restoreDir, safeFileName)

            if (manifest.isEncrypted) {
                val keyToUse = passphraseOverride?.takeIf { it.isNotBlank() }
                    ?: credentialStore.getEncryptionPassphrase()
                if (keyToUse.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("This backup is encrypted with AES-256-GCM. Enter your decryption passphrase to restore.")
                    )
                }
                VaultCryptoEngine.decryptFile(
                    encryptedFile = downloadedTemp,
                    targetPlainFile = finalLocalFile,
                    passphrase = keyToUse
                )
            } else {
                downloadedTemp.copyTo(finalLocalFile, overwrite = true)
            }

            // Verify SHA-256 integrity
            val restoredSha256 = FileInputStream(finalLocalFile).use { FormatUtils.computeSha256(it) }
            val integrityMatch = manifest.contentSha256.isBlank() ||
                restoredSha256.equals(manifest.contentSha256, ignoreCase = true)

            if (!integrityMatch) {
                finalLocalFile.delete()
                return@withContext Result.failure(
                    SecurityException("SHA-256 integrity check failed! Expected ${manifest.contentSha256.take(12)}..., got ${restoredSha256.take(12)}...")
                )
            }

            var outputLocationDesc = finalLocalFile.absolutePath
            if (targetDocumentUri != null) {
                context.contentResolver.openOutputStream(targetDocumentUri)?.use { outStream ->
                    FileInputStream(finalLocalFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
                outputLocationDesc = targetDocumentUri.toString()
            }

            onProgress(1.0f)
            vaultDao.updateManifestRestorePath(manifest.manifestId, outputLocationDesc)
            VaultLogger.i("Restore", "Restored and verified ${manifest.originalFileName} (SHA-256 OK)")

            Result.success(
                RestoreResult(
                    manifestId = manifest.manifestId,
                    restoredFileName = manifest.originalFileName,
                    outputLocation = outputLocationDesc,
                    sha256Verified = integrityMatch,
                    bytesRestored = finalLocalFile.length()
                )
            )
        } catch (e: Exception) {
            VaultLogger.e("Restore", "Failed restoring ${manifest.originalFileName}", e)
            Result.failure(e)
        } finally {
            downloadedTemp.delete()
        }
    }
}
