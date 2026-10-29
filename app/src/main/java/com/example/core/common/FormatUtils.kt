package com.example.core.common

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.MimeTypeMap
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val unit = 1024.0
        val exp = (ln(bytes.toDouble()) / ln(unit)).toInt().coerceIn(0, 4)
        val pre = arrayOf("B", "KB", "MB", "GB", "TB")[exp]
        val value = bytes / unit.pow(exp.toDouble())
        return if (exp == 0) {
            "$bytes B"
        } else {
            String.format(Locale.US, "%.2f %s", value, pre)
        }
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return ""
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatShortTimestamp(epochMillis: Long): String {
        if (epochMillis <= 0L) return "NEVER"
        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        return sdf.format(Date(epochMillis)).uppercase(Locale.US)
    }

    fun computeSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read = inputStream.read(buffer)
        while (read > 0) {
            digest.update(buffer, 0, read)
            read = inputStream.read(buffer)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeSha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun detectMimeType(fileName: String, fallbackMime: String? = null): String {
        if (!fallbackMime.isNullOrBlank() && fallbackMime != "application/octet-stream") {
            return fallbackMime
        }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isNotEmpty()) {
            val mapped = try {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            } catch (_: Throwable) {
                null
            }
            if (!mapped.isNullOrBlank()) return mapped
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg", "oga" -> "audio/ogg"
                "m4a" -> "audio/mp4"
                "pdf" -> "application/pdf"
                "json" -> "application/json"
                "vcf" -> "text/x-vcard"
                "ics" -> "text/calendar"
                "txt", "md", "log" -> "text/plain"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
        }
        return fallbackMime ?: "application/octet-stream"
    }

    fun triggerSubtleHaptic(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (_: Throwable) {
            // Ignore if vibration permission or hardware unavailable
        }
    }
}
