package com.example.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.core.common.FormatUtils
import com.example.core.common.VaultLogger
import com.example.core.database.BackupCategory
import com.example.core.database.SelectedSourceItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Handles reading user-authorized files via Photo Picker & Storage Access Framework,
 * computing SHA-256 content hashes, scanning SAF folder trees, and exporting
 * opt-in Contacts (VCF / JSON) and Calendar events when runtime permissions are granted.
 */
class DeviceContentExporter(private val context: Context) {

    suspend fun inspectAndBuildSourceEntity(
        uri: Uri,
        category: BackupCategory,
        isFolderTree: Boolean = false
    ): SelectedSourceItemEntity? = withContext(Dispatchers.IO) {
        try {
            // Take persistable read permission when offered by SAF
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // Photo picker or temporary grants might not support persistable permission
            }

            if (isFolderTree) {
                val treeDoc = DocumentFile.fromTreeUri(context, uri)
                val folderName = treeDoc?.name ?: uri.lastPathSegment ?: "Authorized Folder"
                val childFiles = treeDoc?.listFiles()?.filter { it.isFile } ?: emptyList()
                val totalBytes = childFiles.sumOf { it.length() }
                return@withContext SelectedSourceItemEntity(
                    uriString = uri.toString(),
                    displayName = "$folderName (${childFiles.size} files)",
                    category = category.name,
                    mimeType = "vnd.android.document/directory",
                    sizeBytes = totalBytes,
                    durationMs = 0L,
                    sha256Hash = FormatUtils.computeSha256Bytes("${uri}_${childFiles.size}_$totalBytes".toByteArray()),
                    isFolderTree = true
                )
            }

            var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "vault_item_${System.currentTimeMillis()}"
            var sizeBytes = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }

            val resolverMime = context.contentResolver.getType(uri)
            val mimeType = FormatUtils.detectMimeType(displayName, resolverMime)

            var durationMs = 0L
            if (category == BackupCategory.VIDEOS || category == BackupCategory.AUDIO ||
                mimeType.startsWith("video/") || mimeType.startsWith("audio/")
            ) {
                durationMs = extractMediaDurationMs(uri)
            }

            val sha256 = context.contentResolver.openInputStream(uri)?.use { stream ->
                FormatUtils.computeSha256(stream)
            } ?: ""

            if (sizeBytes <= 0L) {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0) sizeBytes = afd.length
                }
            }

            SelectedSourceItemEntity(
                uriString = uri.toString(),
                displayName = displayName,
                category = category.name,
                mimeType = mimeType,
                sizeBytes = sizeBytes.coerceAtLeast(0L),
                durationMs = durationMs,
                sha256Hash = sha256,
                isFolderTree = false
            )
        } catch (e: Exception) {
            VaultLogger.e("ContentExporter", "Failed to inspect URI for category=${category.name}", e)
            null
        }
    }

    /**
     * Expands a SAF folder tree URI into individual child file items so newly added or modified
     * files in an authorized folder are automatically synchronized.
     */
    suspend fun expandFolderTreeChildren(
        folderItem: SelectedSourceItemEntity
    ): List<SelectedSourceItemEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<SelectedSourceItemEntity>()
        try {
            val treeUri = Uri.parse(folderItem.uriString)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            val files = rootDoc.listFiles().filter { it.isFile && it.canRead() }
            for (doc in files) {
                val childUri = doc.uri
                val name = doc.name ?: "file_${System.currentTimeMillis()}"
                val mime = FormatUtils.detectMimeType(name, doc.type)
                val size = doc.length()
                val sha256 = context.contentResolver.openInputStream(childUri)?.use {
                    FormatUtils.computeSha256(it)
                } ?: ""
                result.add(
                    SelectedSourceItemEntity(
                        id = folderItem.id,
                        uriString = childUri.toString(),
                        displayName = name,
                        category = folderItem.category,
                        mimeType = mime,
                        sizeBytes = size,
                        sha256Hash = sha256,
                        isFolderTree = false
                    )
                )
            }
        } catch (e: Exception) {
            VaultLogger.e("ContentExporter", "Failed expanding SAF folder tree ${folderItem.displayName}", e)
        }
        result
    }

    /**
     * Copies a content:// or file:// URI into a temporary staging file in cacheDir
     * for encryption and upload, returning the staged File and its freshly verified SHA-256 hash.
     */
    suspend fun stageUriToCacheFile(uriString: String, fileName: String): Pair<File, String>? =
        withContext(Dispatchers.IO) {
            try {
                val stagingDir = File(context.cacheDir, "vault_staging").apply { mkdirs() }
                val safeName = fileName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
                val targetFile = File(stagingDir, "${System.nanoTime()}_$safeName")

                if (uriString.startsWith("file://")) {
                    val srcFile = File(Uri.parse(uriString).path ?: return@withContext null)
                    if (!srcFile.exists()) return@withContext null
                    srcFile.copyTo(targetFile, overwrite = true)
                } else {
                    val uri = Uri.parse(uriString)
                    val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
                    input.use { inStream ->
                        FileOutputStream(targetFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
                val sha256 = FileInputStream(targetFile).use { FormatUtils.computeSha256(it) }
                Pair(targetFile, sha256)
            } catch (e: Exception) {
                VaultLogger.e("ContentExporter", "Failed staging URI for $fileName", e)
                null
            }
        }

    /**
     * Splits a large file (> 50 MB) into safe chunks (<= 45 MB each) so they can be uploaded
     * via the standard Telegram Bot API when user-approved archive splitting is enabled.
     */
    suspend fun splitFileIntoChunks(
        sourceFile: File,
        originalFileName: String,
        chunkSizeBytes: Long
    ): List<File> = withContext(Dispatchers.IO) {
        val parts = mutableListOf<File>()
        val stagingDir = File(context.cacheDir, "vault_chunks").apply { mkdirs() }
        val buffer = ByteArray(64 * 1024)

        FileInputStream(sourceFile).use { input ->
            var partIndex = 1
            var bytesRemaining = sourceFile.length()
            while (bytesRemaining > 0) {
                val partName = String.format("%s.nvpart%02d", originalFileName, partIndex)
                val partFile = File(stagingDir, partName)
                var partWritten = 0L
                FileOutputStream(partFile).use { output ->
                    while (partWritten < chunkSizeBytes && bytesRemaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), chunkSizeBytes - partWritten, bytesRemaining).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        partWritten += read
                        bytesRemaining -= read
                    }
                }
                parts.add(partFile)
                partIndex++
            }
        }
        parts
    }

    /**
     * Exports device contacts into a structured VCF 3.0 or JSON file in app storage
     * ONLY when READ_CONTACTS permission is granted. Respects field exclusion settings.
     */
    suspend fun exportContactsSnapshot(
        format: String, // "VCF" or "JSON"
        excludePhones: Boolean,
        excludeEmails: Boolean,
        excludeOrg: Boolean,
        excludeNotes: Boolean
    ): Result<SelectedSourceItemEntity> = withContext(Dispatchers.IO) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return@withContext Result.failure(
                SecurityException("READ_CONTACTS permission is required to export contacts.")
            )
        }

        try {
            val resolver = context.contentResolver
            data class ContactRecord(
                val id: String,
                val displayName: String,
                val phones: MutableList<String> = mutableListOf(),
                val emails: MutableList<String> = mutableListOf(),
                var organization: String = "",
                var note: String = ""
            )

            val contactsMap = linkedMapOf<String, ContactRecord>()

            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getString(idIdx) ?: continue else continue
                    val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null) ?: "Unnamed Contact"
                    contactsMap[id] = ContactRecord(id = id, displayName = name)
                }
            }

            if (!excludePhones && contactsMap.isNotEmpty()) {
                resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null,
                    null,
                    null
                )?.use { pCursor ->
                    val cIdIdx = pCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val numIdx = pCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (pCursor.moveToNext()) {
                        val cId = if (cIdIdx >= 0) pCursor.getString(cIdIdx) else null
                        val num = if (numIdx >= 0) pCursor.getString(numIdx) else null
                        if (cId != null && !num.isNullOrBlank()) {
                            contactsMap[cId]?.phones?.add(num.trim())
                        }
                    }
                }
            }

            if (!excludeEmails && contactsMap.isNotEmpty()) {
                resolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Email.ADDRESS
                    ),
                    null,
                    null,
                    null
                )?.use { eCursor ->
                    val cIdIdx = eCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                    val addrIdx = eCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                    while (eCursor.moveToNext()) {
                        val cId = if (cIdIdx >= 0) eCursor.getString(cIdIdx) else null
                        val email = if (addrIdx >= 0) eCursor.getString(addrIdx) else null
                        if (cId != null && !email.isNullOrBlank()) {
                            contactsMap[cId]?.emails?.add(email.trim())
                        }
                    }
                }
            }

            val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
            val isJson = format.equals("JSON", ignoreCase = true)
            val ext = if (isJson) "json" else "vcf"
            val fileName = "nothingvault_contacts_export.$ext"
            val outFile = File(exportDir, fileName)

            if (isJson) {
                val arr = JSONArray()
                for (record in contactsMap.values) {
                    val obj = JSONObject().apply {
                        put("name", record.displayName)
                        if (!excludePhones) put("phones", JSONArray(record.phones.distinct()))
                        if (!excludeEmails) put("emails", JSONArray(record.emails.distinct()))
                        if (!excludeOrg && record.organization.isNotBlank()) put("organization", record.organization)
                        if (!excludeNotes && record.note.isNotBlank()) put("note", record.note)
                    }
                    arr.put(obj)
                }
                val wrapper = JSONObject().apply {
                    put("schema", "NothingVault.Contacts.v1")
                    put("count", contactsMap.size)
                    put("excludedFields", JSONArray().apply {
                        if (excludePhones) put("phones")
                        if (excludeEmails) put("emails")
                        if (excludeOrg) put("organization")
                        if (excludeNotes) put("notes")
                    })
                    put("contacts", arr)
                }
                outFile.writeText(wrapper.toString(2), Charsets.UTF_8)
            } else {
                val sb = StringBuilder()
                for (record in contactsMap.values) {
                    sb.appendLine("BEGIN:VCARD")
                    sb.appendLine("VERSION:3.0")
                    sb.appendLine("FN:${record.displayName}")
                    if (!excludePhones) {
                        record.phones.distinct().forEach { phone ->
                            sb.appendLine("TEL;TYPE=CELL:$phone")
                        }
                    }
                    if (!excludeEmails) {
                        record.emails.distinct().forEach { email ->
                            sb.appendLine("EMAIL;TYPE=INTERNET:$email")
                        }
                    }
                    sb.appendLine("END:VCARD")
                }
                if (sb.isEmpty()) {
                    sb.appendLine("BEGIN:VCARD\nVERSION:3.0\nFN:NothingVault Empty Contacts Snapshot\nEND:VCARD")
                }
                outFile.writeText(sb.toString(), Charsets.UTF_8)
            }

            val sha256 = FileInputStream(outFile).use { FormatUtils.computeSha256(it) }
            val entity = SelectedSourceItemEntity(
                uriString = Uri.fromFile(outFile).toString(),
                displayName = "$fileName (${contactsMap.size} contacts)",
                category = BackupCategory.CONTACTS.name,
                mimeType = if (isJson) "application/json" else "text/x-vcard",
                sizeBytes = outFile.length(),
                sha256Hash = sha256,
                isFolderTree = false
            )
            VaultLogger.i("ContentExporter", "Exported ${contactsMap.size} contacts to $fileName")
            Result.success(entity)
        } catch (e: Exception) {
            VaultLogger.e("ContentExporter", "Contacts export failed", e)
            Result.failure(e)
        }
    }

    /**
     * Exports authorized Calendar events into a structured JSON/ICS snapshot
     * ONLY when READ_CALENDAR permission is granted.
     */
    suspend fun exportCalendarSnapshot(): Result<SelectedSourceItemEntity> = withContext(Dispatchers.IO) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return@withContext Result.failure(
                SecurityException("READ_CALENDAR permission is required to export calendar events.")
            )
        }

        try {
            val eventsArray = JSONArray()
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY
                ),
                null,
                null,
                "${CalendarContract.Events.DTSTART} DESC"
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val locIdx = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val allDayIdx = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)

                var count = 0
                while (cursor.moveToNext() && count < 1000) {
                    val obj = JSONObject().apply {
                        put("title", (if (titleIdx >= 0) cursor.getString(titleIdx) else null) ?: "Untitled Event")
                        put("description", (if (descIdx >= 0) cursor.getString(descIdx) else null) ?: "")
                        put("location", (if (locIdx >= 0) cursor.getString(locIdx) else null) ?: "")
                        put("dtStart", if (startIdx >= 0) cursor.getLong(startIdx) else 0L)
                        put("dtEnd", if (endIdx >= 0) cursor.getLong(endIdx) else 0L)
                        put("allDay", if (allDayIdx >= 0) cursor.getInt(allDayIdx) == 1 else false)
                    }
                    eventsArray.put(obj)
                    count++
                }
            }

            val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
            val fileName = "nothingvault_calendar_export.json"
            val outFile = File(exportDir, fileName)
            val root = JSONObject().apply {
                put("schema", "NothingVault.Calendar.v1")
                put("eventCount", eventsArray.length())
                put("events", eventsArray)
            }
            outFile.writeText(root.toString(2), Charsets.UTF_8)

            val sha256 = FileInputStream(outFile).use { FormatUtils.computeSha256(it) }
            val entity = SelectedSourceItemEntity(
                uriString = Uri.fromFile(outFile).toString(),
                displayName = "$fileName (${eventsArray.length()} events)",
                category = BackupCategory.CALENDAR.name,
                mimeType = "application/json",
                sizeBytes = outFile.length(),
                sha256Hash = sha256,
                isFolderTree = false
            )
            VaultLogger.i("ContentExporter", "Exported ${eventsArray.length()} calendar events to $fileName")
            Result.success(entity)
        } catch (e: Exception) {
            VaultLogger.e("ContentExporter", "Calendar export failed", e)
            Result.failure(e)
        }
    }

    private fun extractMediaDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durStr?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
