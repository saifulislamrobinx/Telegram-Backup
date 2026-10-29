package com.example

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.BackupCategory
import com.example.core.database.NothingVaultDatabase
import com.example.core.database.SelectedSourceItemEntity
import com.example.core.database.TelegramDestinationEntity
import com.example.core.database.TransferStatus
import com.example.core.network.TelegramBotClient
import com.example.core.security.SecureCredentialStore
import com.example.data.DeviceContentExporter
import com.example.data.TransferEngine
import com.example.data.VaultPreferencesRepository
import com.example.domain.VaultViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun readAppNameFromContext_matchesNothingVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("NothingVault", appName)
    }

    @Test
    fun destinationRegistry_changingJobOverrideDoesNotMutateGlobalDefault() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = NothingVaultDatabase.createInMemory(context)
        val dao = db.vaultDao()
        val prefs = VaultPreferencesRepository(context)
        val creds = SecureCredentialStore(context)
        val engine = TransferEngine(
            context = context,
            vaultDao = dao,
            preferencesRepository = prefs,
            credentialStore = creds,
            telegramClient = TelegramBotClient(),
            contentExporter = DeviceContentExporter(context)
        )

        dao.upsertDestination(
            TelegramDestinationEntity(
                chatId = "-100111",
                title = "Default Vault Channel",
                isDefault = true,
                isVerified = true
            )
        )
        dao.upsertDestination(
            TelegramDestinationEntity(
                chatId = "-100222",
                title = "Secondary Job Channel",
                isDefault = false,
                isVerified = true
            )
        )

        // Initially resolves to default
        assertEquals("-100111", engine.resolveTargetDestination()?.chatId)

        // Setting per-job override resolves to secondary without changing default in Room
        prefs.setJobDestinationOverride("-100222")
        assertEquals("-100222", engine.resolveTargetDestination()?.chatId)
        assertEquals("-100111", dao.getDefaultDestination()?.chatId)

        // Clearing override returns to global default
        prefs.setJobDestinationOverride("")
        assertEquals("-100111", engine.resolveTargetDestination()?.chatId)
        db.close()
    }

    @Test
    fun transferEngine_encryptedUploadDeduplicationAndVerifiedRestoreRoundtrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = NothingVaultDatabase.createInMemory(context)
        val dao = db.vaultDao()
        val prefs = VaultPreferencesRepository(context)
        val creds = SecureCredentialStore(context)

        val uploadedPayloads = mutableMapOf<String, ByteArray>()

        // Mock Telegram server that stores uploaded multipart document bytes and serves them on download
        val mockTelegramInterceptor = Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            when {
                path.endsWith("/sendDocument") -> {
                    val buffer = okio.Buffer()
                    req.body?.writeTo(buffer)
                    val rawMultipart = buffer.readByteArray()
                    // Extract NVLT1 payload from multipart body if present
                    val magic = "NVLT1".toByteArray(Charsets.US_ASCII)
                    val startIdx = indexOfSubArray(rawMultipart, magic)
                    if (startIdx >= 0) {
                        // Find trailing boundary "\r\n--"
                        val boundaryMarker = "\r\n--".toByteArray(Charsets.US_ASCII)
                        val endIdx = indexOfSubArray(rawMultipart, boundaryMarker, startIdx)
                        if (endIdx > startIdx) {
                            uploadedPayloads["file_enc_1"] = rawMultipart.copyOfRange(startIdx, endIdx)
                        }
                    }
                    val json = """{"ok":true,"result":{"message_id":501,"document":{"file_id":"file_enc_1","file_size":256}}}"""
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                path.endsWith("/getFile") -> {
                    val json = """{"ok":true,"result":{"file_id":"file_enc_1","file_path":"documents/file_enc_1.nvenc","file_size":256}}"""
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                path.contains("/file/bot") -> {
                    val bytes = uploadedPayloads["file_enc_1"] ?: ByteArray(0)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
                else -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"ok":true,"result":{}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
        }

        val mockTelegramClient = TelegramBotClient(
            httpClient = OkHttpClient.Builder().addInterceptor(mockTelegramInterceptor).build()
        )

        creds.saveVerifiedBotToken(
            rawToken = "123456789:AAH_test_bot_token_1234567890abcdef",
            botId = 123456789L,
            firstName = "VaultBot",
            username = "vault_bot",
            canJoinGroups = true
        )
        creds.saveEncryptionPassphrase("super-secret-passphrase")
        prefs.updateEncryptionConfig(encryptionEnabled = true, encryptManifest = false, splitOversized = true)

        dao.upsertDestination(
            TelegramDestinationEntity(
                chatId = "-100555",
                title = "Encrypted Vault",
                isDefault = true,
                isVerified = true
            )
        )

        val sampleFile = File(context.cacheDir, "vault_sample_note.txt")
        val originalText = "Confidential NothingVault backup content for E2E restore verification!"
        sampleFile.writeText(originalText, Charsets.UTF_8)

        val exporter = DeviceContentExporter(context)
        val sourceEntity = SelectedSourceItemEntity(
            uriString = Uri.fromFile(sampleFile).toString(),
            displayName = "vault_sample_note.txt",
            category = BackupCategory.DOCUMENTS.name,
            mimeType = "text/plain",
            sizeBytes = sampleFile.length()
        )
        dao.insertSelectedSource(sourceEntity)

        val engine = TransferEngine(
            context = context,
            vaultDao = dao,
            preferencesRepository = prefs,
            credentialStore = creds,
            telegramClient = mockTelegramClient,
            contentExporter = exporter
        )

        // 1. Execute first encrypted backup
        val firstBackupRes = engine.enqueueAndExecuteBackup()
        assertTrue(firstBackupRes.isSuccess)
        assertEquals(1, firstBackupRes.getOrThrow())

        val manifests = dao.getAllManifests()
        assertEquals(1, manifests.size)
        assertTrue(manifests.first().isEncrypted)

        // 2. Execute second backup of identical unchanged file -> should be SKIPPED due to SHA-256 deduplication
        val secondBackupRes = engine.enqueueAndExecuteBackup()
        assertTrue(secondBackupRes.isSuccess)
        assertEquals(0, secondBackupRes.getOrThrow())
        val tasks = dao.getAllTransferTasks()
        assertTrue(tasks.any { it.status == TransferStatus.SKIPPED.name })

        // 3. Restore and decrypt from manifest -> verify SHA-256 integrity
        val restoreRes = engine.restoreFromManifest(manifests.first())
        assertTrue("Encrypted backup restore must succeed and verify SHA-256", restoreRes.isSuccess)
        assertTrue(restoreRes.getOrThrow().sha256Verified)

        val restoredFile = File(restoreRes.getOrThrow().outputLocation)
        assertEquals(originalText, restoredFile.readText(Charsets.UTF_8))

        sampleFile.delete()
        restoredFile.delete()
        db.close()
    }

    @Test
    fun composeUi_onboardingToDashboardAndTabNavigation_worksCorrectly() {
        val app = ApplicationProvider.getApplicationContext<NothingVaultApplication>()
        val viewModel = VaultViewModel(app, app.container)

        composeTestRule.setContent {
            NothingVaultRoot(
                viewModel = viewModel,
                skipSplashForTest = true
            )
        }
        composeTestRule.waitForIdle()

        // Complete Onboarding
        composeTestRule.onNodeWithTag("onboarding_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("onboarding_skip_button").performClick()
        composeTestRule.waitForIdle()

        // Verify Dashboard renders
        composeTestRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()

        // Navigate to SOURCES (Backup Categories)
        composeTestRule.onNodeWithTag("nav_tab_categories").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backup_categories_screen").assertIsDisplayed()

        // Navigate to TARGETS (Destination Manager)
        composeTestRule.onNodeWithTag("nav_tab_destinations").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("destination_manager_screen").assertIsDisplayed()

        // Navigate to QUEUE (Active Transfers & History)
        composeTestRule.onNodeWithTag("nav_tab_transfers").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("transfers_history_screen").assertIsDisplayed()

        // Navigate to RESTORE (Manifest & Restore)
        composeTestRule.onNodeWithTag("nav_tab_restore").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("restore_manifest_screen").assertIsDisplayed()

        // Navigate to SYSTEM (Settings & Security)
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_security_screen").assertIsDisplayed()
    }

    private fun indexOfSubArray(haystack: ByteArray, needle: ByteArray, fromIndex: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in fromIndex..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
