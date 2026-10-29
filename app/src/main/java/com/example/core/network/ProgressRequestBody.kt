package com.example.core.network

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Streaming OkHttp RequestBody that reports real-time byte upload progress
 * and honors cancellation signals during multipart uploads to Telegram Bot API.
 */
class ProgressRequestBody(
    private val file: File,
    private val mimeType: String,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? =
        mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val totalLength = file.length().coerceAtLeast(1L)
        val buffer = ByteArray(CHUNK_SIZE)
        var uploaded = 0L

        FileInputStream(file).use { inputStream ->
            var read = inputStream.read(buffer)
            while (read != -1) {
                if (isCancelled()) {
                    throw IOException("Upload cancelled by user")
                }
                sink.write(buffer, 0, read)
                uploaded += read
                onProgress(uploaded, totalLength)
                read = inputStream.read(buffer)
            }
        }
    }

    companion object {
        private const val CHUNK_SIZE = 8 * 1024
    }
}
