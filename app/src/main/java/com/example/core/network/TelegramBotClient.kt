package com.example.core.network

import com.example.core.common.VaultLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Official Telegram Bot API Client over HTTPS.
 * - Uses OkHttp with redacted logging.
 * - Parses HTTP 429 flood-control `parameters.retry_after`.
 * - Enforces documented 50 MB standard Bot API upload ceiling before streaming.
 * - Supports getMe, getUpdates, getChat, sendMessage, sendDocument, getFile, and file download.
 */
class TelegramBotClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseApiUrl: String = "https://api.telegram.org"
) {

    fun isValidTokenFormat(token: String): Boolean {
        return TOKEN_FORMAT_REGEX.matches(token.trim())
    }

    suspend fun getMe(rawToken: String): Result<TelegramBotUser> = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        if (!isValidTokenFormat(token)) {
            return@withContext Result.failure(
                TelegramApiException(
                    errorCode = 400,
                    message = "Invalid Telegram Bot token format. Expected <bot_id>:<alphanumeric_secret> from @BotFather."
                )
            )
        }

        val url = "$baseApiUrl/bot$token/getMe"
        val request = Request.Builder().url(url).get().build()
        executeJsonRequest(request).mapCatching { root ->
            val res = root.getJSONObject("result")
            TelegramBotUser(
                id = res.optLong("id", 0L),
                isBot = res.optBoolean("is_bot", true),
                firstName = res.optString("first_name", "Telegram Bot"),
                username = res.optString("username", ""),
                canJoinGroups = res.optBoolean("can_join_groups", true),
                canReadAllGroupMessages = res.optBoolean("can_read_all_group_messages", false)
            )
        }
    }

    /**
     * Discovers eligible chats from recent Bot API `getUpdates` payloads.
     * Because Telegram Bot API does not provide an endpoint to list all historical chats,
     * this inspects recent messages, channel posts, and chat member updates sent to the bot.
     */
    suspend fun discoverAccessibleChats(rawToken: String): Result<List<DiscoveredChatCandidate>> =
        withContext(Dispatchers.IO) {
            val token = rawToken.trim()
            val url = "$baseApiUrl/bot$token/getUpdates?limit=100"
            val request = Request.Builder().url(url).get().build()

            executeJsonRequest(request).mapCatching { root ->
                val resultArray = root.optJSONArray("result") ?: return@mapCatching emptyList()
                val discoveredById = linkedMapOf<String, DiscoveredChatCandidate>()

                for (i in 0 until resultArray.length()) {
                    val updateObj = resultArray.optJSONObject(i) ?: continue
                    val chatObj = updateObj.optJSONObject("message")?.optJSONObject("chat")
                        ?: updateObj.optJSONObject("edited_message")?.optJSONObject("chat")
                        ?: updateObj.optJSONObject("channel_post")?.optJSONObject("chat")
                        ?: updateObj.optJSONObject("my_chat_member")?.optJSONObject("chat")
                        ?: continue

                    val candidate = parseChatObject(chatObj)
                    if (candidate.chatId.isNotBlank()) {
                        discoveredById[candidate.chatId] = candidate
                    }
                }
                VaultLogger.i("TelegramAPI", "Discovered ${discoveredById.size} accessible chat(s) from getUpdates")
                discoveredById.values.toList()
            }
        }

    /**
     * Verifies whether the bot can access a specific chat ID or @channelusername
     * using `getChat`.
     */
    suspend fun verifyDestinationChat(
        rawToken: String,
        chatIdOrUsername: String
    ): Result<DiscoveredChatCandidate> = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        val target = chatIdOrUsername.trim()
        if (target.isEmpty()) {
            return@withContext Result.failure(
                TelegramApiException(400, "Chat ID or @username must not be empty.")
            )
        }

        val formBody = FormBody.Builder()
            .add("chat_id", target)
            .build()
        val request = Request.Builder()
            .url("$baseApiUrl/bot$token/getChat")
            .post(formBody)
            .build()

        executeJsonRequest(request).mapCatching { root ->
            val chatObj = root.getJSONObject("result")
            val parsed = parseChatObject(chatObj)
            val permissionsObj = chatObj.optJSONObject("permissions")
            val canSendDocs = permissionsObj?.optBoolean("can_send_documents", true) ?: true
            parsed.copy(canSendDocuments = canSendDocs)
        }
    }

    /**
     * Sends a verification message to confirm the bot has write/send permission in the destination.
     */
    suspend fun sendTestPingMessage(
        rawToken: String,
        chatId: String,
        customMessage: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        val formBody = FormBody.Builder()
            .add("chat_id", chatId.trim())
            .add("text", customMessage)
            .build()

        val request = Request.Builder()
            .url("$baseApiUrl/bot$token/sendMessage")
            .post(formBody)
            .build()

        executeJsonRequest(request).mapCatching { root ->
            val result = root.getJSONObject("result")
            result.optLong("message_id", 0L)
        }
    }

    /**
     * Uploads a file to the specified Telegram chat using `sendDocument` (which preserves original
     * bytes, filename, and hashes without re-compression).
     */
    suspend fun uploadDocument(
        rawToken: String,
        chatId: String,
        file: File,
        uploadFileName: String,
        mimeType: String,
        caption: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<TelegramUploadResult> = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext Result.failure(
                TelegramApiException(400, "Local source file does not exist: ${file.name}")
            )
        }
        val length = file.length()
        if (length > MAX_BOT_UPLOAD_BYTES) {
            return@withContext Result.failure(
                TelegramApiException(
                    errorCode = 413,
                    message = "File '${file.name}' (${length / (1024 * 1024)} MB) exceeds standard Telegram Bot API 50 MB upload limit. Enable multi-part encrypted archive splitting or exclude files > 50 MB."
                )
            )
        }

        val token = rawToken.trim()
        val progressBody = ProgressRequestBody(
            file = file,
            mimeType = mimeType,
            isCancelled = isCancelled,
            onProgress = onProgress
        )

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.trim())
            .addFormDataPart("caption", caption.take(1000))
            .addFormDataPart("disable_content_type_detection", "true")
            .addFormDataPart("document", uploadFileName, progressBody)
            .build()

        val request = Request.Builder()
            .url("$baseApiUrl/bot$token/sendDocument")
            .post(multipartBody)
            .build()

        executeJsonRequest(request).mapCatching { root ->
            val res = root.getJSONObject("result")
            val messageId = res.optLong("message_id", 0L)
            val docObj = res.optJSONObject("document")
                ?: res.optJSONObject("video")
                ?: res.optJSONObject("audio")
            val fileId = docObj?.optString("file_id", "") ?: ""
            val returnedSize = docObj?.optLong("file_size", length) ?: length

            TelegramUploadResult(
                messageId = messageId,
                fileId = fileId,
                fileName = uploadFileName,
                fileSize = returnedSize,
                chatId = chatId.trim()
            )
        }
    }

    /**
     * Resolves a Telegram `file_id` via `getFile` and downloads the binary payload
     * to `destinationFile` for backup restoration.
     */
    suspend fun downloadFileById(
        rawToken: String,
        fileId: String,
        destinationFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        if (fileId.isBlank()) {
            return@withContext Result.failure(
                TelegramApiException(400, "Missing Telegram file_id in manifest.")
            )
        }

        // Step 1: Call getFile to obtain file_path
        val getFileBody = FormBody.Builder().add("file_id", fileId.trim()).build()
        val getFileReq = Request.Builder()
            .url("$baseApiUrl/bot$token/getFile")
            .post(getFileBody)
            .build()

        val fileInfoResult = executeJsonRequest(getFileReq).mapCatching { root ->
            val res = root.getJSONObject("result")
            TelegramFileDownloadInfo(
                fileId = res.optString("file_id", fileId),
                filePath = res.optString("file_path", ""),
                fileSize = res.optLong("file_size", 0L)
            )
        }

        val fileInfo = fileInfoResult.getOrElse { return@withContext Result.failure(it) }
        if (fileInfo.filePath.isBlank()) {
            return@withContext Result.failure(
                TelegramApiException(404, "Telegram getFile did not return a valid file_path (files > 20 MB may exceed standard Bot API download limit).")
            )
        }

        // Step 2: Stream file from /file/bot<token>/<file_path>
        val downloadUrl = "$baseApiUrl/file/bot$token/${fileInfo.filePath}"
        val downloadReq = Request.Builder().url(downloadUrl).get().build()

        try {
            httpClient.newCall(downloadReq).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        TelegramApiException(
                            errorCode = response.code,
                            message = "Failed to download file from Telegram (HTTP ${response.code})"
                        )
                    )
                }
                val body = response.body ?: return@withContext Result.failure(
                    TelegramApiException(500, "Empty response body when downloading file from Telegram")
                )
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: fileInfo.fileSize
                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesReadTotal = 0L
                        var read = input.read(buffer)
                        while (read != -1) {
                            output.write(buffer, 0, read)
                            bytesReadTotal += read
                            if (totalBytes > 0) {
                                onProgress(bytesReadTotal, totalBytes)
                            }
                            read = input.read(buffer)
                        }
                    }
                }
            }
            Result.success(destinationFile)
        } catch (e: IOException) {
            VaultLogger.e("TelegramAPI", "Network error while downloading file_id=$fileId", e)
            Result.failure(
                TelegramApiException(
                    errorCode = -1,
                    message = "Network failure during download: ${VaultLogger.redact(e.message ?: "IO Error")}",
                    isNetworkFailure = true
                )
            )
        }
    }

    private fun parseChatObject(chatObj: JSONObject): DiscoveredChatCandidate {
        val rawId = chatObj.opt("id")?.toString() ?: ""
        val rawType = chatObj.optString("type", "private").uppercase(Locale.US)
        val title = when {
            chatObj.has("title") && chatObj.optString("title").isNotBlank() ->
                chatObj.optString("title")
            chatObj.has("first_name") -> {
                val first = chatObj.optString("first_name", "")
                val last = chatObj.optString("last_name", "")
                "$first $last".trim().ifBlank { "Private Chat ($rawId)" }
            }
            chatObj.has("username") -> "@${chatObj.optString("username")}"
            else -> "Chat $rawId"
        }
        val username = chatObj.optString("username", "")
        return DiscoveredChatCandidate(
            chatId = rawId,
            title = title,
            username = username,
            chatType = rawType
        )
    }

    private fun executeJsonRequest(request: Request): Result<JSONObject> {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string() ?: "{}"
                val json = try {
                    JSONObject(rawBody)
                } catch (_: Exception) {
                    JSONObject()
                }
                val isOk = json.optBoolean("ok", false)
                if (response.isSuccessful && isOk) {
                    Result.success(json)
                } else {
                    val errorCode = json.optInt("error_code", response.code)
                    val description = json.optString(
                        "description",
                        "Telegram API error (HTTP ${response.code})"
                    )
                    val retryAfter = json.optJSONObject("parameters")?.optInt("retry_after", 0)
                        ?.takeIf { it > 0 }
                    val exception = TelegramApiException(
                        errorCode = errorCode,
                        message = VaultLogger.redact(description),
                        retryAfterSeconds = retryAfter
                    )
                    VaultLogger.w(
                        "TelegramAPI",
                        "API error code=$errorCode retryAfter=${retryAfter ?: "none"} desc=${exception.message}"
                    )
                    Result.failure(exception)
                }
            }
        } catch (e: IOException) {
            val safeError = VaultLogger.redact(e.message ?: "Network unreachable")
            VaultLogger.e("TelegramAPI", "IO exception calling Telegram API: $safeError")
            Result.failure(
                TelegramApiException(
                    errorCode = -1,
                    message = "Network error: $safeError",
                    isNetworkFailure = true
                )
            )
        }
    }

    companion object {
        const val MAX_BOT_UPLOAD_BYTES: Long = 50L * 1024L * 1024L // 50 MB official Bot API limit
        const val ARCHIVE_PART_SIZE_BYTES: Long = 45L * 1024L * 1024L // 45 MB safe chunk size
        private val TOKEN_FORMAT_REGEX = Regex("""^\d{6,14}:[A-Za-z0-9_-]{25,60}$""")

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
