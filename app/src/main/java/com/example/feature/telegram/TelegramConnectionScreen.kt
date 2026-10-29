package com.example.feature.telegram

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.common.VaultLogger
import com.example.core.designsystem.DottedDivider
import com.example.core.designsystem.NothingCard
import com.example.core.designsystem.NothingOutlinedButton
import com.example.core.designsystem.NothingPrimaryButton
import com.example.core.designsystem.NothingStatusPill
import com.example.core.designsystem.SectionHeader
import com.example.core.designsystem.StatusTone
import com.example.core.security.BotConnectionState
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily

@Composable
fun TelegramConnectionScreen(
    botState: BotConnectionState,
    isBusy: Boolean,
    onValidateAndSaveToken: (String) -> Unit,
    onDisconnectBot: () -> Unit,
    onNavigateToDestinations: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    var tokenInput by rememberSaveable { mutableStateOf("") }
    var showRawToken by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("telegram_connection_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("telegram_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back"
                    )
                }
                Text(
                    text = "TELEGRAM BOT API",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            NothingStatusPill(
                label = if (botState.isConnected) "ONLINE // @${botState.botUsername}" else "DISCONNECTED",
                tone = if (botState.isConnected) StatusTone.VERIFIED_GREEN else StatusTone.ACTIVE_RED
            )
        }

        // Current Bot Identity Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "01",
                title = "BOT IDENTITY & STATUS",
                subtitle = "Verified via official Telegram Bot API getMe endpoint"
            )
            Spacer(modifier = Modifier.height(12.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(12.dp))

            if (botState.isConnected) {
                MetricRow("STATUS", "CONNECTED & KEYSTORE-ENCRYPTED")
                MetricRow("BOT NAME", botState.botFirstName)
                MetricRow("USERNAME", "@${botState.botUsername}")
                MetricRow("BOT ID", botState.botId.toString())
                MetricRow("MASKED TOKEN", botState.maskedToken)
                MetricRow(
                    "LAST VERIFIED",
                    VaultLogger.formatTimestamp(botState.lastValidatedAt)
                )

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NothingOutlinedButton(
                        text = "DISCONNECT BOT",
                        onClick = onDisconnectBot,
                        modifier = Modifier.weight(1f),
                        testTag = "disconnect_bot_button"
                    )
                    NothingPrimaryButton(
                        text = "MANAGE TARGETS",
                        onClick = onNavigateToDestinations,
                        modifier = Modifier.weight(1f),
                        testTag = "goto_destinations_button"
                    )
                }
            } else {
                Text(
                    text = "NO BOT CONNECTED",
                    style = MaterialTheme.typography.titleMedium,
                    color = NothingRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Enter your personal Telegram Bot API token below. Your token is encrypted with an Android Keystore AES-256-GCM key and is never logged or shared.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Token Input & Validation Card
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "02",
                title = if (botState.isConnected) "REPLACE BOT TOKEN" else "CONNECT BOT TOKEN",
                subtitle = "Format: 123456789:AAH_your_botfather_token_secret"
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("TELEGRAM BOT API TOKEN", fontFamily = SpaceMonoFontFamily) },
                placeholder = { Text("123456789:AAH...", fontFamily = SpaceMonoFontFamily) },
                singleLine = true,
                visualTransformation = if (showRawToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { showRawToken = !showRawToken },
                        modifier = Modifier.testTag("toggle_token_visibility")
                    ) {
                        Icon(
                            imageVector = if (showRawToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showRawToken) "Hide token" else "Show token"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bot_token_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            NothingPrimaryButton(
                text = if (isBusy) "VALIDATING VIA GETME..." else "VALIDATE & CONNECT BOT",
                onClick = {
                    onValidateAndSaveToken(tokenInput)
                    tokenInput = ""
                },
                enabled = !isBusy && tokenInput.isNotBlank(),
                accentRed = true,
                modifier = Modifier.fillMaxWidth(),
                testTag = "validate_bot_button"
            )
        }

        // Setup Instructions & Security Scope Notice
        NothingCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                indexCode = "03",
                title = "BOTFATHER SETUP & SCOPE LIMITS",
                subtitle = "How Telegram Bot permissions work"
            )
            Spacer(modifier = Modifier.height(10.dp))
            DottedDivider()
            Spacer(modifier = Modifier.height(10.dp))

            val steps = listOf(
                "1. Open Telegram and message @BotFather -> send /newbot to generate your dedicated backup bot and API token.",
                "2. For a Private Chat backup: Open a chat with your new bot and press 'Start' (or send any message) so the bot is authorized to message you.",
                "3. For a Private Group or Channel vault: Add your bot as an Administrator with 'Post Messages' / 'Send Media' permissions, then send a message in the group/channel.",
                "4. Scope Limitation: A Bot API token cannot list your personal Telegram contacts or read arbitrary private chats. NothingVault only accesses destinations where your bot is explicitly authorized."
            )
            steps.forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = SpaceMonoFontFamily
        )
    }
}
