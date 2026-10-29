package com.example

import com.example.core.common.FormatUtils
import com.example.core.common.VaultLogger
import com.example.core.network.TelegramApiException
import com.example.core.network.TelegramBotClient
import com.example.core.security.VaultCryptoEngine
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @Test
    fun aes256Gcm_fileEncryptionAndDecryption_roundtripAndWrongKeyRejection() {
        val tempDir = Files.createTempDirectory("nv_crypto_test").toFile()
        try {
            val plainFile = File(tempDir, "secret_document.txt")
            val originalContent = "NothingVault Confidential Payload // AES-256-GCM Test".repeat(64)
            plainFile.writeText(originalContent, Charsets.UTF_8)

            val encryptedFile = File(tempDir, "secret_document.txt.nvenc")
            val decryptedFile = File(tempDir, "secret_document_restored.txt")

            val metadata = VaultCryptoEngine.encryptFile(
                sourceFile = plainFile,
                targetEncryptedFile = encryptedFile,
                passphrase = "correct-horse-battery-staple"
            )

            assertTrue(encryptedFile.exists())
            assertTrue(metadata.saltHex.length == 32) // 16 bytes hex
            assertTrue(metadata.ivHex.length == 24)   // 12 bytes (96-bit) nonce hex

            // Decrypt with right key
            VaultCryptoEngine.decryptFile(
                encryptedFile = encryptedFile,
                targetPlainFile = decryptedFile,
                passphrase = "correct-horse-battery-staple"
            )
            assertEquals(originalContent, decryptedFile.readText(Charsets.UTF_8))

            // Decrypt with wrong key must fail GCM tag verification
            val wrongOut = File(tempDir, "wrong.txt")
            var threwAuthException = false
            try {
                VaultCryptoEngine.decryptFile(
                    encryptedFile = encryptedFile,
                    targetPlainFile = wrongOut,
                    passphrase = "wrong-passphrase-attempt"
                )
            } catch (_: Exception) {
                threwAuthException = true
            }
            assertTrue("Decryption with wrong passphrase must fail GCM authentication", threwAuthException)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun aes256Gcm_byteArrayEncryption_usesUniqueNoncePerInvocation() {
        val payload = "Manifest JSON payload".toByteArray(Charsets.UTF_8)
        val pass = "vault-master-key-2026"
        val enc1 = VaultCryptoEngine.encryptBytes(payload, pass)
        val enc2 = VaultCryptoEngine.encryptBytes(payload, pass)

        // Two encryptions of identical plaintext must differ due to random salt + 96-bit IV
        assertFalse(enc1.contentEquals(enc2))

        assertArrayEquals(payload, VaultCryptoEngine.decryptBytes(enc1, pass))
        assertArrayEquals(payload, VaultCryptoEngine.decryptBytes(enc2, pass))
    }

    @Test
    fun vaultLogger_redactsBotTokensAndApiUrls() {
        val rawToken = "7123456789:AAH_xYz9876543210abcdefghijklmnop"
        val logLine = "Request failed for https://api.telegram.org/bot$rawToken/sendDocument with token=$rawToken"
        val redacted = VaultLogger.redact(logLine)

        assertFalse("Redacted log must not contain raw bot token", redacted.contains(rawToken))
        assertTrue(redacted.contains("REDACTED"))

        val masked = VaultLogger.maskTokenForDisplay(rawToken)
        assertTrue(masked.startsWith("7123456789:AAH"))
        assertFalse(masked.contains("9876543210"))
    }

    @Test
    fun telegramBotClient_parsesGetMeFloodControlAndPermissionsErrors() = runTest {
        val validToken = "123456789:AAH_valid_botfather_token_secret_12345"

        val mockInterceptor = Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val (code, bodyJson) = when {
                path.endsWith("/getMe") && path.contains(validToken) -> {
                    200 to """{"ok":true,"result":{"id":123456789,"is_bot":true,"first_name":"NothingVaultBot","username":"nothing_vault_bot","can_join_groups":true}}"""
                }
                path.endsWith("/getUpdates") -> {
                    200 to """{"ok":true,"result":[{"update_id":1,"channel_post":{"chat":{"id":-10099887766,"title":"Vault Archive Channel","type":"channel"}}}]}"""
                }
                path.endsWith("/sendMessage") -> {
                    429 to """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 7","parameters":{"retry_after":7}}"""
                }
                path.endsWith("/getChat") -> {
                    403 to """{"ok":false,"error_code":403,"description":"Forbidden: bot is not a member of the channel chat"}"""
                }
                else -> {
                    401 to """{"ok":false,"error_code":401,"description":"Unauthorized"}"""
                }
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Mock")
                .body(bodyJson.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = TelegramBotClient(
            httpClient = OkHttpClient.Builder().addInterceptor(mockInterceptor).build()
        )

        // 1. Invalid token format rejected locally
        val badFormatResult = client.getMe("not-a-valid-token")
        assertTrue(badFormatResult.isFailure)

        // 2. Valid token getMe parsed
        val meResult = client.getMe(validToken)
        assertTrue(meResult.isSuccess)
        val bot = meResult.getOrNull()
        assertNotNull(bot)
        assertEquals("nothing_vault_bot", bot?.username)

        // 3. Discover accessible chats from getUpdates
        val updatesResult = client.discoverAccessibleChats(validToken)
        assertTrue(updatesResult.isSuccess)
        assertEquals("-10099887766", updatesResult.getOrThrow().first().chatId)

        // 4. Flood control 429 retry_after parsed
        val pingResult = client.sendTestPingMessage(validToken, "-10099887766", "Ping")
        assertTrue(pingResult.isFailure)
        val rateEx = pingResult.exceptionOrNull() as TelegramApiException
        assertTrue(rateEx.isRateLimited)
        assertEquals(7, rateEx.retryAfterSeconds)

        // 5. Permission denied 403 parsed
        val chatResult = client.verifyDestinationChat(validToken, "-10099887766")
        assertTrue(chatResult.isFailure)
        val permEx = chatResult.exceptionOrNull() as TelegramApiException
        assertTrue(permEx.isPermissionDenied)
    }

    @Test
    fun formatUtils_computesSha256AndDetectsMimeTypes() {
        val hash = FormatUtils.computeSha256Bytes("nothingvault".toByteArray(Charsets.UTF_8))
        assertEquals(64, hash.length)
        assertEquals("application/pdf", FormatUtils.detectMimeType("report.pdf"))
        assertEquals("text/x-vcard", FormatUtils.detectMimeType("contacts.vcf"))
        assertEquals("1.00 MB", FormatUtils.formatBytes(1024L * 1024L))
    }
}
