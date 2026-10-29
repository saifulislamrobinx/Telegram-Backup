package com.example.core.network

data class TelegramBotUser(
    val id: Long,
    val isBot: Boolean,
    val firstName: String,
    val username: String,
    val canJoinGroups: Boolean = true,
    val canReadAllGroupMessages: Boolean = false
)

data class DiscoveredChatCandidate(
    val chatId: String,
    val title: String,
    val username: String,
    val chatType: String, // PRIVATE, GROUP, SUPERGROUP, CHANNEL
    val canSendDocuments: Boolean = true
)

data class TelegramUploadResult(
    val messageId: Long,
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val chatId: String
)

data class TelegramFileDownloadInfo(
    val fileId: String,
    val filePath: String,
    val fileSize: Long
)

class TelegramApiException(
    val errorCode: Int,
    override val message: String,
    val retryAfterSeconds: Int? = null,
    val isNetworkFailure: Boolean = false
) : Exception(message) {
    val isRateLimited: Boolean get() = errorCode == 429 && (retryAfterSeconds ?: 0) > 0
    val isUnauthorizedToken: Boolean get() = errorCode == 401 || errorCode == 404
    val isPermissionDenied: Boolean get() = errorCode == 403 || message.contains("not enough rights", ignoreCase = true)
}
