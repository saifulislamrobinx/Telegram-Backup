package com.example.domain

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.core.common.RedactedLogEntry
import com.example.core.common.VaultLogger
import com.example.core.database.BackupCategory
import com.example.core.database.BackupManifestEntity
import com.example.core.database.SelectedSourceItemEntity
import com.example.core.database.TelegramDestinationEntity
import com.example.core.database.TransferTaskEntity
import com.example.core.network.DiscoveredChatCandidate
import com.example.core.security.BotConnectionState
import com.example.core.security.VaultCryptoEngine
import com.example.data.EngineProgressSnapshot
import com.example.data.RestoreResult
import com.example.data.SyncScheduler
import com.example.data.VaultSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VaultViewModel(
    private val appContext: Context,
    private val container: AppContainer
) : ViewModel() {

    private val dao = container.database.vaultDao()
    private val prefs = container.preferencesRepository
    private val credentials = container.credentialStore
    private val telegram = container.telegramClient
    private val exporter = container.contentExporter
    private val transferEngine = container.transferEngine

    val settings: StateFlow<VaultSettingsState> = prefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VaultSettingsState()
    )

    val botState: StateFlow<BotConnectionState> = credentials.botConnectionState

    val hasEncryptionPassphrase: StateFlow<Boolean> = credentials.hasEncryptionPassphrase

    private val _encryptionFingerprint = MutableStateFlow(
        VaultCryptoEngine.computeKeyFingerprint(credentials.getEncryptionPassphrase())
    )
    val encryptionFingerprint: StateFlow<String> = _encryptionFingerprint.asStateFlow()

    val destinations: StateFlow<List<TelegramDestinationEntity>> = dao.observeDestinations().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val selectedSources: StateFlow<List<SelectedSourceItemEntity>> = dao.observeSelectedSources().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val transferTasks: StateFlow<List<TransferTaskEntity>> = dao.observeTransferTasks().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val manifests: StateFlow<List<BackupManifestEntity>> = dao.observeManifests().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val engineProgress: StateFlow<EngineProgressSnapshot> = transferEngine.progressSnapshot

    val recentLogs: StateFlow<List<RedactedLogEntry>> = VaultLogger.recentLogs

    private val _discoveredChats = MutableStateFlow<List<DiscoveredChatCandidate>>(emptyList())
    val discoveredChats: StateFlow<List<DiscoveredChatCandidate>> = _discoveredChats.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _restoreProgress = MutableStateFlow<Float?>(null)
    val restoreProgress: StateFlow<Float?> = _restoreProgress.asStateFlow()

    private val _lastRestoreResult = MutableStateFlow<RestoreResult?>(null)
    val lastRestoreResult: StateFlow<RestoreResult?> = _lastRestoreResult.asStateFlow()

    fun dismissStatusMessage() {
        _statusMessage.value = null
    }

    fun postStatusMessage(msg: String) {
        _statusMessage.value = VaultLogger.redact(msg)
    }

    // --- Onboarding & Consent ---
    fun completeOnboarding(privacyAccepted: Boolean) {
        viewModelScope.launch {
            prefs.completeOnboarding(privacyAccepted)
            VaultLogger.i("Onboarding", "Onboarding completed (privacyConsent=$privacyAccepted)")
        }
    }

    // --- Telegram Bot Connection ---
    fun validateAndSaveBotToken(rawToken: String, onSuccess: () -> Unit = {}) {
        val clean = rawToken.trim()
        if (clean.isEmpty()) {
            postStatusMessage("Enter a valid Telegram Bot API token from @BotFather.")
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val result = telegram.getMe(clean)
            _isBusy.value = false
            result.fold(
                onSuccess = { botUser ->
                    credentials.saveVerifiedBotToken(
                        rawToken = clean,
                        botId = botUser.id,
                        firstName = botUser.firstName,
                        username = botUser.username,
                        canJoinGroups = botUser.canJoinGroups
                    )
                    postStatusMessage("CONNECTED: ${botUser.firstName} (@${botUser.username})")
                    // Automatically attempt chat discovery once connected
                    discoverAccessibleDestinations()
                    onSuccess()
                },
                onFailure = { err ->
                    postStatusMessage("VALIDATION FAILED: ${err.message}")
                }
            )
        }
    }

    fun disconnectBot() {
        viewModelScope.launch {
            credentials.disconnectBot()
            _discoveredChats.value = emptyList()
            postStatusMessage("Telegram Bot disconnected and token removed from Android Keystore.")
        }
    }

    // --- Destination Discovery & Registry ---
    fun discoverAccessibleDestinations() {
        val token = credentials.getBotToken()
        if (token.isNullOrBlank()) {
            postStatusMessage("Connect your Telegram Bot token before scanning for chats.")
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val result = telegram.discoverAccessibleChats(token)
            _isBusy.value = false
            result.fold(
                onSuccess = { list ->
                    _discoveredChats.value = list
                    if (list.isEmpty()) {
                        postStatusMessage("No recent updates found. Send '/start' or a message to your bot in Telegram, or enter a Chat ID manually.")
                    } else {
                        postStatusMessage("Discovered ${list.size} accessible Telegram destination(s).")
                    }
                },
                onFailure = { err ->
                    postStatusMessage("Discovery error: ${err.message}")
                }
            )
        }
    }

    fun verifyAndSaveDestination(
        chatIdOrUsername: String,
        customTitle: String = "",
        notes: String = "",
        makeDefault: Boolean = false
    ) {
        val token = credentials.getBotToken()
        val target = chatIdOrUsername.trim()
        if (target.isEmpty()) {
            postStatusMessage("Enter a Chat ID (e.g. -100123456789 or 12345678) or @channelusername.")
            return
        }

        viewModelScope.launch {
            _isBusy.value = true
            val existingList = dao.getDestinationsList()
            val shouldBeDefault = makeDefault || existingList.isEmpty()

            if (!token.isNullOrBlank()) {
                val verifyResult = telegram.verifyDestinationChat(token, target)
                verifyResult.fold(
                    onSuccess = { candidate ->
                        val resolvedId = candidate.chatId.ifBlank { target }
                        val entity = TelegramDestinationEntity(
                            chatId = resolvedId,
                            title = customTitle.takeIf { it.isNotBlank() } ?: candidate.title,
                            username = candidate.username,
                            chatType = candidate.chatType,
                            isDefault = shouldBeDefault,
                            isVerified = true,
                            canSendDocuments = candidate.canSendDocuments,
                            lastTestedAt = System.currentTimeMillis(),
                            notes = notes
                        )
                        dao.upsertDestination(entity)
                        if (shouldBeDefault) {
                            dao.setDefaultDestination(resolvedId)
                        }
                        postStatusMessage("VERIFIED DESTINATION: ${entity.title} (${entity.chatId})")
                    },
                    onFailure = { err ->
                        // Allow saving unverified manual destination if user is configuring offline or before adding bot
                        val entity = TelegramDestinationEntity(
                            chatId = target,
                            title = customTitle.ifBlank { "Manual Target ($target)" },
                            username = target.removePrefix("@").takeIf { target.startsWith("@") } ?: "",
                            chatType = if (target.startsWith("-100")) "CHANNEL/SUPERGROUP" else "PRIVATE/GROUP",
                            isDefault = shouldBeDefault,
                            isVerified = false,
                            canSendDocuments = true,
                            lastTestedAt = 0L,
                            notes = notes
                        )
                        dao.upsertDestination(entity)
                        if (shouldBeDefault) {
                            dao.setDefaultDestination(target)
                        }
                        postStatusMessage("Saved destination '$target' (Unverified: ${err.message})")
                    }
                )
            } else {
                val entity = TelegramDestinationEntity(
                    chatId = target,
                    title = customTitle.ifBlank { "Saved Target ($target)" },
                    username = target.removePrefix("@").takeIf { target.startsWith("@") } ?: "",
                    chatType = if (target.startsWith("-100")) "CHANNEL/SUPERGROUP" else "PRIVATE/GROUP",
                    isDefault = shouldBeDefault,
                    isVerified = false,
                    canSendDocuments = true,
                    lastTestedAt = 0L,
                    notes = notes
                )
                dao.upsertDestination(entity)
                if (shouldBeDefault) {
                    dao.setDefaultDestination(target)
                }
                postStatusMessage("Saved destination '${entity.title}'. Connect bot token to verify permissions.")
            }
            _isBusy.value = false
        }
    }

    fun saveDiscoveredCandidate(candidate: DiscoveredChatCandidate, makeDefault: Boolean) {
        viewModelScope.launch {
            val existing = dao.getDestinationsList()
            val isDefault = makeDefault || existing.isEmpty()
            val entity = TelegramDestinationEntity(
                chatId = candidate.chatId,
                title = candidate.title,
                username = candidate.username,
                chatType = candidate.chatType,
                isDefault = isDefault,
                isVerified = true,
                canSendDocuments = candidate.canSendDocuments,
                lastTestedAt = System.currentTimeMillis()
            )
            dao.upsertDestination(entity)
            if (isDefault) {
                dao.setDefaultDestination(candidate.chatId)
            }
            postStatusMessage("Added '${candidate.title}' to saved destinations.")
        }
    }

    fun setDefaultDestination(chatId: String) {
        viewModelScope.launch {
            dao.setDefaultDestination(chatId)
            postStatusMessage("Updated global default backup destination.")
        }
    }

    fun setJobDestinationOverride(chatId: String) {
        viewModelScope.launch {
            prefs.setJobDestinationOverride(chatId)
            if (chatId.isBlank()) {
                postStatusMessage("Using global default destination for next backup job.")
            } else {
                postStatusMessage("Selected per-job destination override ($chatId). Global default unchanged.")
            }
        }
    }

    fun sendTestPingToDestination(destination: TelegramDestinationEntity) {
        val token = credentials.getBotToken()
        if (token.isNullOrBlank()) {
            postStatusMessage("Connect your Telegram Bot token first.")
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val msg = "[NothingVault // TELEMETRY PING]\n" +
                "Target: ${destination.title} (${destination.chatId})\n" +
                "Status: WRITE PERMISSION VERIFIED\n" +
                "Timestamp: ${VaultLogger.formatTimestamp(System.currentTimeMillis())}"
            val result = telegram.sendTestPingMessage(token, destination.chatId, msg)
            _isBusy.value = false
            result.fold(
                onSuccess = { msgId ->
                    dao.upsertDestination(
                        destination.copy(
                            isVerified = true,
                            canSendDocuments = true,
                            lastTestedAt = System.currentTimeMillis()
                        )
                    )
                    postStatusMessage("TEST PASSED: Sent verification message #$msgId to ${destination.title}.")
                },
                onFailure = { err ->
                    postStatusMessage("TEST FAILED for ${destination.title}: ${err.message}")
                }
            )
        }
    }

    fun deleteDestination(chatId: String) {
        viewModelScope.launch {
            dao.deleteDestination(chatId)
            val remaining = dao.getDestinationsList()
            if (remaining.isNotEmpty() && remaining.none { it.isDefault }) {
                dao.setDefaultDestination(remaining.first().chatId)
            }
            postStatusMessage("Removed destination $chatId.")
        }
    }

    // --- Backup Categories & File Selection ---
    fun toggleCategory(category: BackupCategory, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setCategoryEnabled(category, enabled)
            postStatusMessage("${category.displayName}: ${if (enabled) "ENABLED" else "DISABLED"}")
        }
    }

    fun addSelectedFiles(uris: List<Uri>, category: BackupCategory) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isBusy.value = true
            var addedCount = 0
            for (uri in uris) {
                val entity = exporter.inspectAndBuildSourceEntity(uri, category, isFolderTree = false)
                if (entity != null) {
                    dao.insertSelectedSource(entity)
                    addedCount++
                }
            }
            prefs.setCategoryEnabled(category, true)
            _isBusy.value = false
            postStatusMessage("Added $addedCount item(s) to ${category.displayName}.")
        }
    }

    fun addAuthorizedFolder(treeUri: Uri, category: BackupCategory) {
        viewModelScope.launch {
            _isBusy.value = true
            val entity = exporter.inspectAndBuildSourceEntity(treeUri, category, isFolderTree = true)
            if (entity != null) {
                dao.insertSelectedSource(entity)
                prefs.setCategoryEnabled(category, true)
                postStatusMessage("Linked folder '${entity.displayName}' to ${category.displayName}.")
            } else {
                postStatusMessage("Could not inspect selected folder.")
            }
            _isBusy.value = false
        }
    }

    fun exportAndQueueContactsNow() {
        viewModelScope.launch {
            _isBusy.value = true
            val s = prefs.getSnapshot()
            val res = exporter.exportContactsSnapshot(
                format = s.contactExportFormat,
                excludePhones = s.excludeContactPhones,
                excludeEmails = s.excludeContactEmails,
                excludeOrg = s.excludeContactOrg,
                excludeNotes = s.excludeContactNotes
            )
            _isBusy.value = false
            res.fold(
                onSuccess = { entity ->
                    dao.deleteSourcesByCategory(BackupCategory.CONTACTS.name)
                    dao.insertSelectedSource(entity)
                    prefs.setCategoryEnabled(BackupCategory.CONTACTS, true)
                    postStatusMessage("Exported contacts snapshot: ${entity.displayName}")
                },
                onFailure = { err ->
                    postStatusMessage("Contacts export failed: ${err.message}")
                }
            )
        }
    }

    fun exportAndQueueCalendarNow() {
        viewModelScope.launch {
            _isBusy.value = true
            val res = exporter.exportCalendarSnapshot()
            _isBusy.value = false
            res.fold(
                onSuccess = { entity ->
                    dao.deleteSourcesByCategory(BackupCategory.CALENDAR.name)
                    dao.insertSelectedSource(entity)
                    prefs.setCategoryEnabled(BackupCategory.CALENDAR, true)
                    postStatusMessage("Exported calendar snapshot: ${entity.displayName}")
                },
                onFailure = { err ->
                    postStatusMessage("Calendar export failed: ${err.message}")
                }
            )
        }
    }

    fun updateContactExportSettings(
        format: String,
        excludePhones: Boolean,
        excludeEmails: Boolean,
        excludeOrg: Boolean,
        excludeNotes: Boolean
    ) {
        viewModelScope.launch {
            prefs.updateContactExportOptions(
                format = format,
                excludePhones = excludePhones,
                excludeEmails = excludeEmails,
                excludeOrg = excludeOrg,
                excludeNotes = excludeNotes
            )
            postStatusMessage("Updated Contacts export privacy filter ($format).")
        }
    }

    fun removeSelectedSource(id: Long) {
        viewModelScope.launch {
            dao.deleteSelectedSource(id)
        }
    }

    fun clearSourcesForCategory(category: BackupCategory) {
        viewModelScope.launch {
            dao.deleteSourcesByCategory(category.name)
            postStatusMessage("Cleared selected sources in ${category.displayName}.")
        }
    }

    // --- Encryption & Security ---
    fun saveMasterEncryptionPassphrase(passphrase: String) {
        val clean = passphrase.trim()
        if (clean.length < 8) {
            postStatusMessage("Master passphrase must be at least 8 characters for AES-256-GCM security.")
            return
        }
        viewModelScope.launch {
            credentials.saveEncryptionPassphrase(clean)
            _encryptionFingerprint.value = VaultCryptoEngine.computeKeyFingerprint(clean)
            val s = prefs.getSnapshot()
            prefs.updateEncryptionConfig(
                encryptionEnabled = true,
                encryptManifest = s.encryptManifestEnabled,
                splitOversized = s.splitOversizedFiles
            )
            postStatusMessage("AES-256-GCM key saved. Fingerprint: ${_encryptionFingerprint.value}")
        }
    }

    fun clearMasterEncryptionPassphrase() {
        viewModelScope.launch {
            credentials.clearEncryptionPassphrase()
            _encryptionFingerprint.value = "NONE"
            val s = prefs.getSnapshot()
            prefs.updateEncryptionConfig(
                encryptionEnabled = false,
                encryptManifest = s.encryptManifestEnabled,
                splitOversized = s.splitOversizedFiles
            )
            postStatusMessage("Removed AES-256-GCM passphrase and disabled local encryption.")
        }
    }

    fun updateEncryptionConfig(
        encryptionEnabled: Boolean,
        encryptManifest: Boolean,
        splitOversized: Boolean
    ) {
        viewModelScope.launch {
            if (encryptionEnabled && credentials.getEncryptionPassphrase().isBlank()) {
                postStatusMessage("Set a master passphrase before enabling AES-256-GCM encryption.")
                return@launch
            }
            prefs.updateEncryptionConfig(encryptionEnabled, encryptManifest, splitOversized)
        }
    }

    // --- Background Sync & Transfer Controls ---
    fun updateSyncSchedule(
        autoSyncEnabled: Boolean,
        intervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        concurrentUploads: Int,
        skipDuplicates: Boolean
    ) {
        viewModelScope.launch {
            val nextRunAt = SyncScheduler.scheduleOrCancelPeriodicSync(
                context = appContext,
                enabled = autoSyncEnabled,
                intervalHours = intervalHours,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly
            )
            prefs.updateSyncScheduleConfig(
                autoSyncEnabled = autoSyncEnabled,
                intervalHours = intervalHours,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly,
                concurrentUploads = concurrentUploads,
                skipDuplicates = skipDuplicates,
                nextScheduledAt = nextRunAt
            )
            postStatusMessage(
                if (autoSyncEnabled) {
                    "Scheduled background sync every ${intervalHours}h."
                } else {
                    "Background sync disabled."
                }
            )
        }
    }

    fun startBackupNow(explicitDestinationChatId: String? = null, forceDuplicates: Boolean = false) {
        transferEngine.triggerBackupNow(
            explicitDestinationChatId = explicitDestinationChatId,
            forceUploadDuplicates = forceDuplicates,
            onResult = { res ->
                res.fold(
                    onSuccess = { uploadedCount ->
                        postStatusMessage("Backup cycle completed ($uploadedCount file(s) uploaded to Telegram).")
                    },
                    onFailure = { err ->
                        postStatusMessage("Backup error: ${err.message}")
                    }
                )
            }
        )
    }

    fun triggerWorkManagerSyncNow() {
        viewModelScope.launch {
            val s = prefs.getSnapshot()
            SyncScheduler.triggerImmediateWorkManagerSync(
                context = appContext,
                wifiOnly = s.wifiOnly,
                chargingOnly = s.chargingOnly
            )
            startBackupNow()
        }
    }

    fun pauseTransfers() {
        transferEngine.pauseActiveTransfers()
        postStatusMessage("Paused active transfers.")
    }

    fun cancelSingleTransfer(taskId: String) {
        transferEngine.cancelSingleTask(taskId)
    }

    fun cancelAllTransfers() {
        transferEngine.cancelAllActiveTransfers()
        SyncScheduler.stopAllBackgroundSync(appContext)
        postStatusMessage("Cancelled all active transfers.")
    }

    fun retryFailedTransfers() {
        transferEngine.retryFailedAndPausedTasks()
        postStatusMessage("Retrying failed/paused transfers with exponential backoff.")
    }

    fun clearFinishedTransferHistory() {
        viewModelScope.launch {
            dao.clearFinishedTasks()
            postStatusMessage("Cleared completed/failed transfer records.")
        }
    }

    fun clearAllLocalHistory() {
        viewModelScope.launch {
            dao.clearAllTasks()
            dao.clearAllManifests()
            VaultLogger.clearLogs()
            postStatusMessage("Cleared all local transfer history, manifests, and diagnostic logs.")
        }
    }

    // --- Backup Manifest Restoration ---
    fun restoreFromManifest(
        manifest: BackupManifestEntity,
        passphraseInput: String = "",
        targetUri: Uri? = null
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            _restoreProgress.value = 0.05f
            val result = transferEngine.restoreFromManifest(
                manifest = manifest,
                passphraseOverride = passphraseInput,
                targetDocumentUri = targetUri,
                onProgress = { fraction ->
                    _restoreProgress.value = fraction
                }
            )
            _isBusy.value = false
            _restoreProgress.value = null
            result.fold(
                onSuccess = { restoreInfo ->
                    _lastRestoreResult.value = restoreInfo
                    postStatusMessage(
                        "RESTORED & SHA-256 VERIFIED: ${restoreInfo.restoredFileName}"
                    )
                },
                onFailure = { err ->
                    postStatusMessage("RESTORE FAILED: ${err.message}")
                }
            )
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            prefs.setThemeMode(mode)
        }
    }

    fun exportConfigurationToUri(targetUri: Uri) {
        viewModelScope.launch {
            try {
                val defaultDest = dao.getDefaultDestination()?.chatId
                val json = prefs.exportConfigurationJson(defaultDest)
                appContext.contentResolver.openOutputStream(targetUri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                postStatusMessage("Exported NothingVault configuration JSON (secrets redacted).")
            } catch (e: Exception) {
                postStatusMessage("Failed exporting configuration: ${e.message}")
            }
        }
    }

    companion object {
        fun provideFactory(appContext: Context, container: AppContainer): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VaultViewModel(appContext.applicationContext, container) as T
                }
            }
        }
    }
}
