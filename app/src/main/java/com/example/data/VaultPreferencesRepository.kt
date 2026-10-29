package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.database.BackupCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nothing_vault_preferences"
)

data class VaultSettingsState(
    val onboardingCompleted: Boolean = false,
    val privacyConsentAccepted: Boolean = false,
    val enabledCategories: Set<String> = setOf(
        BackupCategory.PHOTOS.name,
        BackupCategory.VIDEOS.name,
        BackupCategory.DOCUMENTS.name
    ),
    val encryptionEnabled: Boolean = false,
    val encryptManifestEnabled: Boolean = true,
    val splitOversizedFiles: Boolean = true,
    val autoSyncEnabled: Boolean = false,
    val syncIntervalHours: Int = 12,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val concurrentUploads: Int = 2,
    val skipDuplicates: Boolean = true,
    val contactExportFormat: String = "VCF", // VCF or JSON
    val excludeContactPhones: Boolean = false,
    val excludeContactEmails: Boolean = false,
    val excludeContactOrg: Boolean = false,
    val excludeContactNotes: Boolean = true,
    val jobDestinationOverrideChatId: String = "", // Empty means use global default destination
    val lastSuccessfulSyncAt: Long = 0L,
    val nextScheduledSyncAt: Long = 0L,
    val themeMode: String = "DARK" // DARK, LIGHT, SYSTEM
)

class VaultPreferencesRepository(private val context: Context) {

    val settingsFlow: Flow<VaultSettingsState> = context.vaultDataStore.data.map { prefs ->
        VaultSettingsState(
            onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
            privacyConsentAccepted = prefs[KEY_PRIVACY_CONSENT] ?: false,
            enabledCategories = prefs[KEY_ENABLED_CATEGORIES] ?: setOf(
                BackupCategory.PHOTOS.name,
                BackupCategory.VIDEOS.name,
                BackupCategory.DOCUMENTS.name
            ),
            encryptionEnabled = prefs[KEY_ENCRYPTION_ENABLED] ?: false,
            encryptManifestEnabled = prefs[KEY_ENCRYPT_MANIFEST] ?: true,
            splitOversizedFiles = prefs[KEY_SPLIT_OVERSIZED] ?: true,
            autoSyncEnabled = prefs[KEY_AUTO_SYNC_ENABLED] ?: false,
            syncIntervalHours = (prefs[KEY_SYNC_INTERVAL_HOURS] ?: 12).coerceIn(1, 168),
            wifiOnly = prefs[KEY_WIFI_ONLY] ?: true,
            chargingOnly = prefs[KEY_CHARGING_ONLY] ?: false,
            concurrentUploads = (prefs[KEY_CONCURRENT_UPLOADS] ?: 2).coerceIn(1, 4),
            skipDuplicates = prefs[KEY_SKIP_DUPLICATES] ?: true,
            contactExportFormat = prefs[KEY_CONTACT_FORMAT] ?: "VCF",
            excludeContactPhones = prefs[KEY_EXCLUDE_PHONES] ?: false,
            excludeContactEmails = prefs[KEY_EXCLUDE_EMAILS] ?: false,
            excludeContactOrg = prefs[KEY_EXCLUDE_ORG] ?: false,
            excludeContactNotes = prefs[KEY_EXCLUDE_NOTES] ?: true,
            jobDestinationOverrideChatId = prefs[KEY_JOB_DEST_OVERRIDE] ?: "",
            lastSuccessfulSyncAt = prefs[KEY_LAST_SYNC_AT] ?: 0L,
            nextScheduledSyncAt = prefs[KEY_NEXT_SYNC_AT] ?: 0L,
            themeMode = prefs[KEY_THEME_MODE] ?: "DARK"
        )
    }

    suspend fun getSnapshot(): VaultSettingsState = settingsFlow.first()

    suspend fun completeOnboarding(privacyAccepted: Boolean) {
        context.vaultDataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = true
            prefs[KEY_PRIVACY_CONSENT] = privacyAccepted
        }
    }

    suspend fun setCategoryEnabled(category: BackupCategory, enabled: Boolean) {
        context.vaultDataStore.edit { prefs ->
            val current = prefs[KEY_ENABLED_CATEGORIES]?.toMutableSet() ?: mutableSetOf(
                BackupCategory.PHOTOS.name,
                BackupCategory.VIDEOS.name,
                BackupCategory.DOCUMENTS.name
            )
            if (enabled) {
                current.add(category.name)
            } else {
                current.remove(category.name)
            }
            prefs[KEY_ENABLED_CATEGORIES] = current
        }
    }

    suspend fun updateEncryptionConfig(
        encryptionEnabled: Boolean,
        encryptManifest: Boolean,
        splitOversized: Boolean
    ) {
        context.vaultDataStore.edit { prefs ->
            prefs[KEY_ENCRYPTION_ENABLED] = encryptionEnabled
            prefs[KEY_ENCRYPT_MANIFEST] = encryptManifest
            prefs[KEY_SPLIT_OVERSIZED] = splitOversized
        }
    }

    suspend fun updateSyncScheduleConfig(
        autoSyncEnabled: Boolean,
        intervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        concurrentUploads: Int,
        skipDuplicates: Boolean,
        nextScheduledAt: Long
    ) {
        context.vaultDataStore.edit { prefs ->
            prefs[KEY_AUTO_SYNC_ENABLED] = autoSyncEnabled
            prefs[KEY_SYNC_INTERVAL_HOURS] = intervalHours.coerceIn(1, 168)
            prefs[KEY_WIFI_ONLY] = wifiOnly
            prefs[KEY_CHARGING_ONLY] = chargingOnly
            prefs[KEY_CONCURRENT_UPLOADS] = concurrentUploads.coerceIn(1, 4)
            prefs[KEY_SKIP_DUPLICATES] = skipDuplicates
            prefs[KEY_NEXT_SYNC_AT] = nextScheduledAt
        }
    }

    suspend fun updateContactExportOptions(
        format: String,
        excludePhones: Boolean,
        excludeEmails: Boolean,
        excludeOrg: Boolean,
        excludeNotes: Boolean
    ) {
        context.vaultDataStore.edit { prefs ->
            prefs[KEY_CONTACT_FORMAT] = format
            prefs[KEY_EXCLUDE_PHONES] = excludePhones
            prefs[KEY_EXCLUDE_EMAILS] = excludeEmails
            prefs[KEY_EXCLUDE_ORG] = excludeOrg
            prefs[KEY_EXCLUDE_NOTES] = excludeNotes
        }
    }

    /**
     * Sets a destination override for an individual backup job without mutating
     * the global default destination in Room.
     */
    suspend fun setJobDestinationOverride(chatId: String) {
        context.vaultDataStore.edit { prefs ->
            prefs[KEY_JOB_DEST_OVERRIDE] = chatId
        }
    }

    suspend fun recordSuccessfulSync(timestamp: Long, nextSyncTimestamp: Long) {
        context.vaultDataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC_AT] = timestamp
            if (nextSyncTimestamp > 0L) {
                prefs[KEY_NEXT_SYNC_AT] = nextSyncTimestamp
            }
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.vaultDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
    }

    suspend fun exportConfigurationJson(defaultDestChatId: String?): String {
        val state = getSnapshot()
        val root = JSONObject().apply {
            put("app", "NothingVault")
            put("schemaVersion", 1)
            put("exportedAt", System.currentTimeMillis())
            put("defaultDestinationChatId", defaultDestChatId ?: "")
            put("enabledCategories", JSONArray(state.enabledCategories.toList()))
            put("encryptionEnabled", state.encryptionEnabled)
            put("encryptManifestEnabled", state.encryptManifestEnabled)
            put("splitOversizedFiles", state.splitOversizedFiles)
            put("autoSyncEnabled", state.autoSyncEnabled)
            put("syncIntervalHours", state.syncIntervalHours)
            put("wifiOnly", state.wifiOnly)
            put("chargingOnly", state.chargingOnly)
            put("concurrentUploads", state.concurrentUploads)
            put("skipDuplicates", state.skipDuplicates)
            put("contactExportFormat", state.contactExportFormat)
            put("themeMode", state.themeMode)
        }
        return root.toString(2)
    }

    companion object {
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_PRIVACY_CONSENT = booleanPreferencesKey("privacy_consent_accepted")
        private val KEY_ENABLED_CATEGORIES = stringSetPreferencesKey("enabled_categories")
        private val KEY_ENCRYPTION_ENABLED = booleanPreferencesKey("encryption_enabled")
        private val KEY_ENCRYPT_MANIFEST = booleanPreferencesKey("encrypt_manifest_enabled")
        private val KEY_SPLIT_OVERSIZED = booleanPreferencesKey("split_oversized_files")
        private val KEY_AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        private val KEY_SYNC_INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val KEY_CHARGING_ONLY = booleanPreferencesKey("charging_only")
        private val KEY_CONCURRENT_UPLOADS = intPreferencesKey("concurrent_uploads")
        private val KEY_SKIP_DUPLICATES = booleanPreferencesKey("skip_duplicates")
        private val KEY_CONTACT_FORMAT = stringPreferencesKey("contact_export_format")
        private val KEY_EXCLUDE_PHONES = booleanPreferencesKey("exclude_contact_phones")
        private val KEY_EXCLUDE_EMAILS = booleanPreferencesKey("exclude_contact_emails")
        private val KEY_EXCLUDE_ORG = booleanPreferencesKey("exclude_contact_org")
        private val KEY_EXCLUDE_NOTES = booleanPreferencesKey("exclude_contact_notes")
        private val KEY_JOB_DEST_OVERRIDE = stringPreferencesKey("job_destination_override")
        private val KEY_LAST_SYNC_AT = longPreferencesKey("last_successful_sync_at")
        private val KEY_NEXT_SYNC_AT = longPreferencesKey("next_scheduled_sync_at")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
