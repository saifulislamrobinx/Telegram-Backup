package com.example.feature.backup

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.common.FormatUtils
import com.example.core.database.BackupCategory
import com.example.core.database.SelectedSourceItemEntity
import com.example.core.designsystem.DottedDivider
import com.example.core.designsystem.NothingCard
import com.example.core.designsystem.NothingOutlinedButton
import com.example.core.designsystem.NothingPrimaryButton
import com.example.core.designsystem.NothingStatusPill
import com.example.core.designsystem.SectionHeader
import com.example.core.designsystem.StatusTone
import com.example.data.VaultSettingsState
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily

@Composable
fun BackupCategoriesScreen(
    settings: VaultSettingsState,
    selectedSources: List<SelectedSourceItemEntity>,
    isBusy: Boolean,
    onToggleCategory: (BackupCategory, Boolean) -> Unit,
    onAddFiles: (List<Uri>, BackupCategory) -> Unit,
    onAddFolder: (Uri, BackupCategory) -> Unit,
    onExportContacts: () -> Unit,
    onExportCalendar: () -> Unit,
    onUpdateContactOptions: (format: String, exPhone: Boolean, exEmail: Boolean, exOrg: Boolean, exNotes: Boolean) -> Unit,
    onRemoveSource: (Long) -> Unit,
    onClearCategory: (BackupCategory) -> Unit,
    onBackupNow: () -> Unit,
    onPostStatus: (String) -> Unit
) {
    val context = LocalContext.current
    var activeFolderCategory by remember { mutableStateOf(BackupCategory.PHOTOS) }

    // 1. Photo Picker Launcher (Images)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddFiles(uris, BackupCategory.PHOTOS)
        }
    }

    // 2. Photo Picker Launcher (Videos)
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddFiles(uris, BackupCategory.VIDEOS)
        }
    }

    // 3. SAF Multi-Document Picker (Documents)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddFiles(uris, BackupCategory.DOCUMENTS)
        }
    }

    // 4. SAF Multi-Document Picker (Audio)
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddFiles(uris, BackupCategory.AUDIO)
        }
    }

    // 5. SAF Multi-Document Picker (App Exports)
    val appExportPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddFiles(uris, BackupCategory.APP_EXPORTS)
        }
    }

    // 6. SAF Folder Tree Picker
    val folderTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            onAddFolder(treeUri, activeFolderCategory)
        }
    }

    // 7. Runtime Contacts Permission Launcher
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onExportContacts()
        } else {
            onPostStatus("READ_CONTACTS permission denied. Contacts backup remains disabled.")
        }
    }

    // 8. Runtime Calendar Permission Launcher
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onExportCalendar()
        } else {
            onPostStatus("READ_CALENDAR permission denied. Calendar backup remains disabled.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("backup_categories_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(
            indexCode = "01",
            title = "BACKUP CATEGORIES & SOURCES",
            subtitle = "Zero-permission Photo Picker & SAF folders. Opt-in Contacts & Calendar."
        )

        BackupCategory.entries.forEach { category ->
            val isEnabled = settings.enabledCategories.contains(category.name)
            val categoryItems = selectedSources.filter { it.category == category.name }
            val categorySize = categoryItems.sumOf { it.sizeBytes }

            NothingCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("category_card_${category.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NothingStatusPill(
                                label = category.code,
                                tone = if (isEnabled) StatusTone.ACTIVE_RED else StatusTone.NEUTRAL
                            )
                            Text(
                                text = category.displayName.uppercase(),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${categoryItems.size} source(s) • ${FormatUtils.formatBytes(categorySize)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = SpaceMonoFontFamily
                        )
                    }

                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { checked -> onToggleCategory(category, checked) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = NothingRed
                        ),
                        modifier = Modifier.testTag("switch_${category.name.lowercase()}")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SCOPE: ${category.permissionNote}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))
                DottedDivider()
                Spacer(modifier = Modifier.height(10.dp))

                // Category-Specific Selection Actions
                when (category) {
                    BackupCategory.PHOTOS -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NothingPrimaryButton(
                                text = "PHOTO PICKER",
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "pick_photos_button"
                            )
                            NothingOutlinedButton(
                                text = "LINK FOLDER",
                                onClick = {
                                    activeFolderCategory = BackupCategory.PHOTOS
                                    folderTreeLauncher.launch(null)
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "link_photos_folder_button"
                            )
                        }
                    }

                    BackupCategory.VIDEOS -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NothingPrimaryButton(
                                text = "PICK VIDEOS",
                                onClick = {
                                    videoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "pick_videos_button"
                            )
                            NothingOutlinedButton(
                                text = "VIDEO FOLDER",
                                onClick = {
                                    activeFolderCategory = BackupCategory.VIDEOS
                                    folderTreeLauncher.launch(null)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    BackupCategory.DOCUMENTS -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NothingPrimaryButton(
                                text = "SELECT FILES",
                                onClick = {
                                    documentPickerLauncher.launch(arrayOf("*/*"))
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "pick_documents_button"
                            )
                            NothingOutlinedButton(
                                text = "DOC FOLDER",
                                onClick = {
                                    activeFolderCategory = BackupCategory.DOCUMENTS
                                    folderTreeLauncher.launch(null)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    BackupCategory.AUDIO -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NothingPrimaryButton(
                                text = "SELECT AUDIO",
                                onClick = {
                                    audioPickerLauncher.launch(arrayOf("audio/*"))
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "pick_audio_button"
                            )
                            NothingOutlinedButton(
                                text = "AUDIO FOLDER",
                                onClick = {
                                    activeFolderCategory = BackupCategory.AUDIO
                                    folderTreeLauncher.launch(null)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    BackupCategory.CONTACTS -> {
                        ContactsConfigPanel(
                            settings = settings,
                            isBusy = isBusy,
                            onUpdateOptions = onUpdateContactOptions,
                            onRequestExport = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.READ_CONTACTS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    onExportContacts()
                                } else {
                                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                }
                            }
                        )
                    }

                    BackupCategory.CALENDAR -> {
                        NothingPrimaryButton(
                            text = if (isBusy) "EXPORTING..." else "AUTHORIZE & EXPORT CALENDAR SNAPSHOT",
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.READ_CALENDAR
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    onExportCalendar()
                                } else {
                                    calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "export_calendar_button"
                        )
                    }

                    BackupCategory.APP_EXPORTS -> {
                        NothingOutlinedButton(
                            text = "SELECT APP EXPORT / BACKUP FILE",
                            onClick = {
                                appExportPickerLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "pick_app_export_button"
                        )
                    }
                }

                // Selected items preview for this category
                if (categoryItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    DottedDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    categoryItems.take(5).forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                val durationPart = if (item.durationMs > 0L) {
                                    " • ${FormatUtils.formatDuration(item.durationMs)}"
                                } else {
                                    ""
                                }
                                val hashPart = if (item.sha256Hash.isNotBlank()) {
                                    " • SHA256:${item.sha256Hash.take(8)}"
                                } else {
                                    ""
                                }
                                Text(
                                    text = "${FormatUtils.formatBytes(item.sizeBytes)}$durationPart$hashPart",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = SpaceMonoFontFamily
                                )
                            }
                            IconButton(onClick = { onRemoveSource(item.id) }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Remove ${item.displayName}"
                                )
                            }
                        }
                    }

                    if (categoryItems.size > 5) {
                        Text(
                            text = "+ ${categoryItems.size - 5} more item(s) selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    NothingOutlinedButton(
                        text = "CLEAR ${category.code} LIST",
                        onClick = { onClearCategory(category) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Bottom Run Backup CTA
        NothingPrimaryButton(
            text = "RUN BACKUP FOR ENABLED CATEGORIES",
            onClick = onBackupNow,
            accentRed = true,
            modifier = Modifier.fillMaxWidth(),
            testTag = "categories_run_backup_button"
        )
    }
}

@Composable
private fun ContactsConfigPanel(
    settings: VaultSettingsState,
    isBusy: Boolean,
    onUpdateOptions: (format: String, exPhone: Boolean, exEmail: Boolean, exOrg: Boolean, exNotes: Boolean) -> Unit,
    onRequestExport: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NothingOutlinedButton(
                text = if (settings.contactExportFormat == "VCF") "[X] VCF vCard" else "[ ] VCF vCard",
                onClick = {
                    onUpdateOptions(
                        "VCF",
                        settings.excludeContactPhones,
                        settings.excludeContactEmails,
                        settings.excludeContactOrg,
                        settings.excludeContactNotes
                    )
                },
                modifier = Modifier.weight(1f)
            )
            NothingOutlinedButton(
                text = if (settings.contactExportFormat == "JSON") "[X] JSON" else "[ ] JSON",
                onClick = {
                    onUpdateOptions(
                        "JSON",
                        settings.excludeContactPhones,
                        settings.excludeContactEmails,
                        settings.excludeContactOrg,
                        settings.excludeContactNotes
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "FIELD EXCLUSIONS (PRIVACY FILTER):",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = settings.excludeContactPhones,
                onCheckedChange = {
                    onUpdateOptions(
                        settings.contactExportFormat,
                        it,
                        settings.excludeContactEmails,
                        settings.excludeContactOrg,
                        settings.excludeContactNotes
                    )
                }
            )
            Text("Exclude Phones", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))

            Checkbox(
                checked = settings.excludeContactEmails,
                onCheckedChange = {
                    onUpdateOptions(
                        settings.contactExportFormat,
                        settings.excludeContactPhones,
                        it,
                        settings.excludeContactOrg,
                        settings.excludeContactNotes
                    )
                }
            )
            Text("Exclude Emails", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(6.dp))
        NothingPrimaryButton(
            text = if (isBusy) "EXPORTING CONTACTS..." else "AUTHORIZE & EXPORT CONTACTS SNAPSHOT",
            onClick = onRequestExport,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
            testTag = "export_contacts_button"
        )
    }
}
