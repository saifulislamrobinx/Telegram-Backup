package com.example.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.designsystem.DotMatrixBanner
import com.example.core.designsystem.DottedDivider
import com.example.core.designsystem.NothingCard
import com.example.core.designsystem.NothingOutlinedButton
import com.example.core.designsystem.NothingPrimaryButton
import com.example.core.designsystem.NothingStatusPill
import com.example.core.designsystem.StatusTone
import com.example.ui.theme.NothingRed
import com.example.ui.theme.SpaceMonoFontFamily
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NothingSplashScreen(
    onSplashFinished: () -> Unit
) {
    LaunchedEffect(Unit) {
        delay(1100L)
        onSplashFinished()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "splash_ring")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )

    val primaryColor = MaterialTheme.colorScheme.onBackground
    val mutedColor = MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(modifier = Modifier.size(148.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2.2f
                val innerRadius = outerRadius * 0.65f
                val totalDots = 36

                for (i in 0 until totalDots) {
                    val deg = (i * (360f / totalDots)) + angle
                    val rad = Math.toRadians(deg.toDouble())
                    val x = center.x + (outerRadius * cos(rad)).toFloat()
                    val y = center.y + (outerRadius * sin(rad)).toFloat()
                    val isRedMarker = i == 0
                    drawCircle(
                        color = if (isRedMarker) NothingRed else primaryColor.copy(alpha = if (i % 2 == 0) 0.9f else 0.35f),
                        radius = if (isRedMarker) 4.5.dp.toPx() else 2.2.dp.toPx(),
                        center = Offset(x, y)
                    )
                }

                for (i in 0 until 24) {
                    val deg = (i * (360f / 24f)) - (angle * 0.5f)
                    val rad = Math.toRadians(deg.toDouble())
                    val x = center.x + (innerRadius * cos(rad)).toFloat()
                    val y = center.y + (innerRadius * sin(rad)).toFloat()
                    drawCircle(
                        color = mutedColor,
                        radius = 1.6.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "NOTHING // VAULT",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            NothingStatusPill(
                label = "TELEGRAM BACKUP ENGINE // VER 1.0",
                tone = StatusTone.ACTIVE_RED
            )
        }
    }
}

private data class OnboardingStepSpec(
    val stepCode: String,
    val title: String,
    val subtitle: String,
    val bullets: List<String>
)

@Composable
fun OnboardingScreen(
    onCompleteOnboarding: (privacyAccepted: Boolean) -> Unit
) {
    val steps = remember {
        listOf(
            OnboardingStepSpec(
                stepCode = "01 // ARCHITECTURE",
                title = "SELF-SOVEREIGN TELEGRAM BACKUP",
                subtitle = "Connect your own Telegram Bot API token (@BotFather) to synchronize files directly from your Android device to an authorized Telegram chat, group, or channel.",
                bullets = listOf(
                    "Uses official Telegram Bot API methods (getMe, getUpdates, getChat, sendDocument, getFile).",
                    "Bot tokens only access chats where you explicitly message the bot or add it as an administrator.",
                    "Maintains a local Room manifest index with SHA-256 content hashes to prevent duplicate uploads."
                )
            ),
            OnboardingStepSpec(
                stepCode = "02 // ZERO-TRUST CRYPTO",
                title = "LOCAL AES-256-GCM ENCRYPTION",
                subtitle = "Standard Telegram Bot chats are NOT end-to-end encrypted by Telegram. NothingVault protects your sensitive data by encrypting files on-device before upload.",
                bullets = listOf(
                    "Authenticated AES-256-GCM with unique 96-bit nonces and PBKDF2-HMAC-SHA256 key derivation.",
                    "Bot tokens and master passphrases are encrypted via hardware-backed Android Keystore.",
                    "Your encryption key is never uploaded alongside your backup files. Keep your passphrase safe."
                )
            ),
            OnboardingStepSpec(
                stepCode = "03 // SCOPED PRIVACY",
                title = "LEAST-PRIVILEGE PERMISSIONS",
                subtitle = "NothingVault uses zero-permission Android Photo Picker and Storage Access Framework (SAF) for photos, videos, audio, and documents.",
                bullets = listOf(
                    "Contacts and Calendar exports are strictly opt-in and only request permission when enabled.",
                    "No analytics, no hidden telemetry, and automatic token redaction across all diagnostic logs.",
                    "Background sync uses Android WorkManager respecting Wi-Fi, charging, and OS battery constraints."
                )
            )
        )
    }

    var currentStep by remember { mutableIntStateOf(0) }
    var consentChecked by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
            .testTag("onboarding_screen"),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NOTHING // VAULT",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                NothingStatusPill(
                    label = "STEP ${currentStep + 1} OF ${steps.size}",
                    tone = StatusTone.ACTIVE_RED
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            DotMatrixBanner(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                animatePulse = false
            )
            Spacer(modifier = Modifier.height(20.dp))

            AnimatedContent(
                targetState = currentStep,
                label = "onboarding_step_transition"
            ) { idx ->
                val spec = steps[idx]
                NothingCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 20.dp
                ) {
                    Text(
                        text = spec.stepCode,
                        style = MaterialTheme.typography.labelMedium,
                        color = NothingRed,
                        fontFamily = SpaceMonoFontFamily
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = spec.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = spec.subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DottedDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    spec.bullets.forEach { point ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(NothingRed)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = point,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy & Security Model Consent Card
            NothingCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { consentChecked = !consentChecked }
                    .testTag("onboarding_consent_card"),
                contentPadding = 14.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = consentChecked,
                        onCheckedChange = { consentChecked = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = NothingRed,
                            checkmarkColor = Color.White
                        ),
                        modifier = Modifier.testTag("onboarding_consent_checkbox")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "INFORMED PRIVACY & TELEGRAM CONSENT",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "I understand that files uploaded to Telegram are governed by Telegram's cloud storage policy unless local AES-256-GCM encryption is enabled prior to upload.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (currentStep > 0) {
                NothingOutlinedButton(
                    text = "BACK",
                    onClick = { currentStep-- },
                    modifier = Modifier.weight(1f),
                    testTag = "onboarding_back_button"
                )
            } else {
                NothingOutlinedButton(
                    text = "SKIP TO VAULT",
                    onClick = { onCompleteOnboarding(consentChecked) },
                    enabled = consentChecked,
                    modifier = Modifier.weight(1f),
                    testTag = "onboarding_skip_button"
                )
            }

            if (currentStep < steps.lastIndex) {
                NothingPrimaryButton(
                    text = "NEXT // 0${currentStep + 2}",
                    onClick = { currentStep++ },
                    modifier = Modifier.weight(1f),
                    testTag = "onboarding_next_button"
                )
            } else {
                NothingPrimaryButton(
                    text = "INITIALIZE VAULT",
                    onClick = { onCompleteOnboarding(consentChecked) },
                    enabled = consentChecked,
                    accentRed = true,
                    modifier = Modifier.weight(1f),
                    testTag = "onboarding_complete_button"
                )
            }
        }
    }
}
