package com.example.feature.destinations

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.common.VaultLogger
import com.example.core.database.TelegramDestinationEntity
import com.example.core.designsystem.DottedDivider
import com.example.core.designsystem.NothingCard
import com.example.core.designsystem.NothingOutlinedButton
import com.example.core.designsystem.NothingPrimaryButton
import com.example.core.designsystem.NothingStatusPill
import com.example.core.designsystem.SectionHeader
import com.example.core.designsystem.StatusTone
import com.example.core.network.DiscoveredChatCandidate
import com.example.core.security.BotConnectionState
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily

@Composable
fun DestinationManagerScreen(
    botState: BotConnectionState,
    destinations: List<TelegramDestinationEntity>,
    discoveredCandidates: List<DiscoveredChatCandidate>,
    jobOverrideChatId: String,
    isBusy: Boolean,
    onDiscoverFromUpdates: () -> Unit,
    onSaveManualDestination: (chatId: String, title: String, notes: String, makeDefault: Boolean) -> Unit,
    onSaveDiscoveredCandidate: (DiscoveredChatCandidate, Boolean) -> Unit,
    onSetDefaultDestination: (String) -> Unit,
    onSetJobOverrideDestination: (String) -> Unit,
    onSendTestPing: (TelegramDestinationEntity) -> Unit,
    onSendTestFile: (TelegramDestinationEntity) -> Unit = {},
    onDeleteDestination: (String) -> Unit,
    onOpenBotConnection: () -> Unit
) {
    var manualChatId by rememberSaveable { mutableStateOf("") }
    var manualTitle by rememberSaveable { mutableStateOf("") }
    var manualNotes by rememberSaveable { mutableStateOf("") }
    var setAsDefaultOnCreate by rememberSaveable { mutableStateOf(true) }

    // User confirmation dialog before sending a test ping message or test file to a destination
    var destinationPendingTestPing by remember { mutableStateOf<TelegramDestinationEntity?>(null) }

    if (destinationPendingTestPing != null) {
        val target = destinationPendingTestPing!!
        AlertDialog(
            onDismissRequest = { destinationPendingTestPing = null },
            title = {
                Text(
                    text = "CONFIRM TEST TRANSMISSION",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "Verify permissions in '${target.title}' (${target.chatId}) by sending a small test message or a test probe document file using your connected bot?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            onSendTestPing(target)
                            destinationPendingTestPing = null
                        },
                        modifier = Modifier.testTag("confirm_test_ping_button")
                    ) {
                        Text("SEND TEST MESSAGE", color = NothingRed, fontFamily = SpaceMonoFontFamily)
                    }
                    TextButton(
                        onClick = {
                            onSendTestFile(target)
                            destinationPendingTestPing = null
                        },
                        modifier = Modifier.testTag("confirm_test_file_button")
                    ) {
                        Text("SEND TEST DOCUMENT FILE", fontFamily = SpaceMonoFontFamily)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { destinationPendingTestPing = null }) {
                    Text("CANCEL", fontFamily = SpaceMonoFontFamily)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("destination_manager_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(
            indexCode = "01",
            title = "TELEGRAM TARGET REGISTRY",
            subtitle = "Configure default and per-job backup destinations where your bot is authorized"
        )

        if (!botState.isConnected) {
            NothingCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "BOT TOKEN NOT CONNECTED",
                    style = MaterialTheme.typography.titleMedium,
                    color = NothingRed
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "You can pre-configure destination Chat IDs below, or connect your Telegram Bot token first to auto-discover chats and verify permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                NothingPrimaryButton(
                    text = "CONNECT TELEGRAM BOT",
                    onClick = onOpenBotConnection,
                    accentRed = true,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "dest_connect_bot_button"
                )
            }
        }

        // Saved Destinations List
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SAVED DESTINATIONS (${destinations.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                if (jobOverrideChatId.isNotBlank()) {
                    TextButton(onClick = { onSetJobOverrideDestination("") }) {
                        Text(
                            text = "CLEAR JOB OVERRIDE",
                            style = MaterialTheme.typography.labelSmall,
                            color = NothingRed
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(12.dp))

            if (destinations.isEmpty()) {
                Text(
                    text = "No saved destinations yet. Scan recent bot updates or enter a Chat ID / @channelusername below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                destinations.forEachIndexed { idx, dest ->
                    if (idx > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        DottedDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    DestinationItemCard(
                        destination = dest,
                        isJobOverride = dest.chatId == jobOverrideChatId,
                        isBusy = isBusy,
                        onSetDefault = { onSetDefaultDestination(dest.chatId) },
                        onSetForNextJob = {
                            if (dest.chatId == jobOverrideChatId) {
                                onSetJobOverrideDestination("")
                            } else {
                                onSetJobOverrideDestination(dest.chatId)
                            }
                        },
                        onRequestTestPing = { destinationPendingTestPing = dest },
                        onDelete = { onDeleteDestination(dest.chatId) }
                    )
                }
            }
        }

        // Auto-Discovery via Bot API getUpdates
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "02",
                title = "DISCOVER AUTHORIZED CHATS",
                subtitle = "Queries Bot API getUpdates for chats that recently messaged or added your bot"
            )
            Spacer(modifier = Modifier.height(12.dp))

            NothingOutlinedButton(
                text = if (isBusy) "SCANNING TELEGRAM UPDATES..." else "SCAN RECENT BOT UPDATES",
                onClick = onDiscoverFromUpdates,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                testTag = "discover_chats_button"
            )

            if (discoveredCandidates.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                DottedDivider()
                Spacer(modifier = Modifier.height(10.dp))
                discoveredCandidates.forEach { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = candidate.title,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "ID: ${candidate.chatId} // TYPE: ${candidate.chatType}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        NothingPrimaryButton(
                            text = "SAVE",
                            onClick = { onSaveDiscoveredCandidate(candidate, destinations.isEmpty()) }
                        )
                    }
                }
            }
        }

        // Manual Destination Configuration
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "03",
                title = "ADD MANUAL DESTINATION",
                subtitle = "Enter numeric Chat ID (e.g. -100192837465 or 123456789) or public @channelusername"
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = manualChatId,
                onValueChange = { manualChatId = it },
                label = { Text("CHAT ID OR @CHANNELUSERNAME", fontFamily = SpaceMonoFontFamily) },
                placeholder = { Text("-1001234567890 or @my_vault_channel", fontFamily = SpaceMonoFontFamily) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_chat_id_input")
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = manualTitle,
                onValueChange = { manualTitle = it },
                label = { Text("DISPLAY LABEL (OPTIONAL)", fontFamily = SpaceMonoFontFamily) },
                placeholder = { Text("Personal Vault Channel", fontFamily = SpaceMonoFontFamily) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_chat_title_input")
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = manualNotes,
                onValueChange = { manualNotes = it },
                label = { Text("CONFIGURATION NOTES (OPTIONAL)", fontFamily = SpaceMonoFontFamily) },
                placeholder = { Text("Encrypted cold storage channel", fontFamily = SpaceMonoFontFamily) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = setAsDefaultOnCreate,
                    onCheckedChange = { setAsDefaultOnCreate = it }
                )
                Text(
                    text = "Set as global default backup destination",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            NothingPrimaryButton(
                text = if (isBusy) "VERIFYING CHAT..." else "VERIFY & SAVE DESTINATION",
                onClick = {
                    onSaveManualDestination(manualChatId, manualTitle, manualNotes, setAsDefaultOnCreate)
                    manualChatId = ""
                    manualTitle = ""
                    manualNotes = ""
                },
                enabled = !isBusy && manualChatId.isNotBlank(),
                accentRed = true,
                modifier = Modifier.fillMaxWidth(),
                testTag = "save_destination_button"
            )
        }

        // Permissions Setup Guide
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "04",
                title = "GROUP & CHANNEL SETUP GUIDE",
                subtitle = "Ensuring your bot has upload permissions"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Private Chat: Open your bot in Telegram and tap Start. Send any text message, then tap 'SCAN RECENT BOT UPDATES' above.\n" +
                    "• Group / Supergroup: Add the bot to the group and ensure 'Send Files / Documents' is permitted.\n" +
                    "• Private Channel: Add the bot as a Channel Administrator with 'Post Messages' enabled. Private channel IDs start with -100.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DestinationItemCard(
    destination: TelegramDestinationEntity,
    isJobOverride: Boolean,
    isBusy: Boolean,
    onSetDefault: () -> Unit,
    onSetForNextJob: () -> Unit,
    onRequestTestPing: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = destination.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "ID: ${destination.chatId} // ${destination.chatType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = SpaceMonoFontFamily
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (destination.isDefault) {
                    NothingStatusPill(label = "DEFAULT", tone = StatusTone.ACTIVE_RED)
                }
                if (isJobOverride) {
                    NothingStatusPill(label = "JOB OVERRIDE", tone = StatusTone.WARNING_AMBER)
                }
                NothingStatusPill(
                    label = if (destination.isVerified) "VERIFIED" else "UNVERIFIED",
                    tone = if (destination.isVerified) StatusTone.VERIFIED_GREEN else StatusTone.NEUTRAL
                )
            }
        }

        if (destination.notes.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "NOTE: ${destination.notes}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "LAST TESTED: ${VaultLogger.formatTimestamp(destination.lastTestedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!destination.isDefault) {
                NothingOutlinedButton(
                    text = "SET DEFAULT",
                    onClick = onSetDefault,
                    modifier = Modifier.weight(1f)
                )
            }
            NothingOutlinedButton(
                text = if (isJobOverride) "CLEAR JOB TARGET" else "FOR 1 JOB ONLY",
                onClick = onSetForNextJob,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NothingPrimaryButton(
                text = "TEST CONNECTION",
                onClick = onRequestTestPing,
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                testTag = "test_dest_${destination.chatId}"
            )
            NothingOutlinedButton(
                text = "REMOVE",
                onClick = onDelete,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
