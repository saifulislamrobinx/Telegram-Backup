package com.example.core.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RedactedLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,
    val tag: String,
    val message: String
)

/**
 * Structured logger that strictly redacts Telegram Bot tokens, encryption keys,
 * and authorization secrets before writing to logcat or in-memory diagnostic history.
 */
object VaultLogger {
    // Matches Telegram Bot tokens like 123456789:AAH_xYz1234567890abcdefghijklmnop
    private val BOT_TOKEN_REGEX = Regex("""\b\d{6,14}:[A-Za-z0-9_-]{25,60}\b""")
    private val BOT_URL_REGEX = Regex("""https://api\.telegram\.org/(?:file/)?bot[^/\s]+""")
    private val HEX_KEY_REGEX = Regex("""(?i)\b(key|passphrase|secret|token)\s*[:=]\s*([^\s,;]+)""")

    private val _recentLogs = MutableStateFlow<List<RedactedLogEntry>>(emptyList())
    val recentLogs: StateFlow<List<RedactedLogEntry>> = _recentLogs.asStateFlow()

    fun redact(raw: String): String {
        var sanitized = BOT_TOKEN_REGEX.replace(raw) { matchResult ->
            val token = matchResult.value
            val prefix = token.take(4)
            val suffix = token.takeLast(3)
            "${prefix}***REDACTED_TOKEN***${suffix}"
        }
        sanitized = BOT_URL_REGEX.replace(sanitized, "https://api.telegram.org/bot[REDACTED]")
        sanitized = HEX_KEY_REGEX.replace(sanitized) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        return sanitized
    }

    fun maskTokenForDisplay(token: String): String {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return "NOT CONFIGURED"
        val colonIndex = trimmed.indexOf(':')
        return if (colonIndex > 2 && trimmed.length > colonIndex + 6) {
            val botId = trimmed.substring(0, colonIndex)
            val secretPart = trimmed.substring(colonIndex + 1)
            "$botId:${secretPart.take(3)}••••••••${secretPart.takeLast(3)}"
        } else if (trimmed.length > 8) {
            "${trimmed.take(3)}••••••••${trimmed.takeLast(3)}"
        } else {
            "••••••••"
        }
    }

    fun i(tag: String, message: String) {
        val safeMsg = redact(message)
        try {
            Log.i("NV_$tag", safeMsg)
        } catch (_: Throwable) {
            // Ignored in pure JVM unit tests
        }
        appendEntry("INFO", tag, safeMsg)
    }

    fun w(tag: String, message: String) {
        val safeMsg = redact(message)
        try {
            Log.w("NV_$tag", safeMsg)
        } catch (_: Throwable) {
        }
        appendEntry("WARN", tag, safeMsg)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val errorSuffix = throwable?.message?.let { " | ${redact(it)}" } ?: ""
        val safeMsg = redact(message) + errorSuffix
        try {
            Log.e("NV_$tag", safeMsg)
        } catch (_: Throwable) {
        }
        appendEntry("ERROR", tag, safeMsg)
    }

    fun clearLogs() {
        _recentLogs.value = emptyList()
    }

    private fun appendEntry(level: String, tag: String, safeMessage: String) {
        val entry = RedactedLogEntry(
            level = level,
            tag = tag,
            message = safeMessage
        )
        val current = _recentLogs.value
        _recentLogs.value = (listOf(entry) + current).take(200)
    }

    fun formatTimestamp(epochMillis: Long): String {
        if (epochMillis <= 0L) return "NEVER"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(epochMillis))
    }
}
