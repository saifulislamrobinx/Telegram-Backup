package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.domain.VaultViewModel
import com.example.feature.backup.BackupCategoriesScreen
import com.example.feature.dashboard.DashboardScreen
import com.example.feature.destinations.DestinationManagerScreen
import com.example.feature.onboarding.NothingSplashScreen
import com.example.feature.onboarding.OnboardingScreen
import com.example.feature.restore.BackupManifestRestoreScreen
import com.example.feature.settings.SettingsAndSecurityScreen
import com.example.feature.telegram.TelegramConnectionScreen
import com.example.feature.transfers.TransfersAndHistoryScreen
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingVaultTheme
import com.example.ui.theme.SpaceMonoFontFamily

enum class VaultNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD("dashboard", "VAULT", Icons.Default.Dashboard),
    CATEGORIES("categories", "SOURCES", Icons.Default.FolderCopy),
    DESTINATIONS("destinations", "TARGETS", Icons.Default.CloudQueue),
    TRANSFERS("transfers", "QUEUE", Icons.Default.SwapVert),
    RESTORE("restore", "RESTORE", Icons.Default.Restore),
    SETTINGS("settings", "SYSTEM", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as NothingVaultApplication
        setContent {
            val vaultViewModel: VaultViewModel = viewModel(
                factory = VaultViewModel.provideFactory(applicationContext, app.container)
            )
            NothingVaultRoot(viewModel = vaultViewModel)
        }
    }
}

@Composable
fun NothingVaultRoot(
    viewModel: VaultViewModel,
    skipSplashForTest: Boolean = false
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val botState by viewModel.botState.collectAsStateWithLifecycle()
    val destinations by viewModel.destinations.collectAsStateWithLifecycle()
    val discoveredChats by viewModel.discoveredChats.collectAsStateWithLifecycle()
    val selectedSources by viewModel.selectedSources.collectAsStateWithLifecycle()
    val transferTasks by viewModel.transferTasks.collectAsStateWithLifecycle()
    val manifests by viewModel.manifests.collectAsStateWithLifecycle()
    val engineProgress by viewModel.engineProgress.collectAsStateWithLifecycle()
    val recentLogs by viewModel.recentLogs.collectAsStateWithLifecycle()
    val hasPassphrase by viewModel.hasEncryptionPassphrase.collectAsStateWithLifecycle()
    val keyFingerprint by viewModel.encryptionFingerprint.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val restoreProgress by viewModel.restoreProgress.collectAsStateWithLifecycle()
    val lastRestoreResult by viewModel.lastRestoreResult.collectAsStateWithLifecycle()

    val isDarkTheme = when (settings.themeMode) {
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDarkTheme()
        else -> true
    }

    var splashCompleted by rememberSaveable { mutableStateOf(skipSplashForTest) }
    var onboardingCompletedInSession by rememberSaveable { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf(VaultNavTab.DASHBOARD) }
    var showTelegramConnectionScreen by rememberSaveable { mutableStateOf(false) }

    NothingVaultTheme(darkTheme = isDarkTheme) {
        if (!splashCompleted) {
            NothingSplashScreen(onSplashFinished = { splashCompleted = true })
            return@NothingVaultTheme
        }

        if (!settings.onboardingCompleted && !onboardingCompletedInSession) {
            OnboardingScreen(
                onCompleteOnboarding = { accepted ->
                    onboardingCompletedInSession = true
                    viewModel.completeOnboarding(accepted)
                }
            )
            return@NothingVaultTheme
        }

        // Handle system back from sub-tabs to return to DASHBOARD
        if (!showTelegramConnectionScreen && currentTab != VaultNavTab.DASHBOARD) {
            BackHandler {
                currentTab = VaultNavTab.DASHBOARD
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val isExpandedScreen = maxWidth >= 640.dp

            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (!isExpandedScreen && !showTelegramConnectionScreen) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            VaultNavTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = SpaceMonoFontFamily
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.White,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = NothingRed
                                    ),
                                    modifier = Modifier.testTag("nav_tab_${tab.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isExpandedScreen && !showTelegramConnectionScreen) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            VaultNavTab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("nav_rail_${tab.route}")
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Status Banner Feedback
                        AnimatedVisibility(visible = statusMessage != null) {
                            statusMessage?.let { msg ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .border(
                                            width = 1.dp,
                                            color = NothingRed,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { viewModel.dismissStatusMessage() }
                                        .padding(12.dp)
                                        .testTag("status_feedback_banner")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = msg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontFamily = SpaceMonoFontFamily,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "[X]",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NothingRed,
                                            fontFamily = SpaceMonoFontFamily
                                        )
                                    }
                                }
                            }
                        }

                        // Active Screen Content
                        Box(modifier = Modifier.weight(1f)) {
                            if (showTelegramConnectionScreen) {
                                TelegramConnectionScreen(
                                    botState = botState,
                                    isBusy = isBusy,
                                    onValidateAndSaveToken = { token ->
                                        viewModel.validateAndSaveBotToken(token)
                                    },
                                    onDisconnectBot = { viewModel.disconnectBot() },
                                    onNavigateToDestinations = {
                                        showTelegramConnectionScreen = false
                                        currentTab = VaultNavTab.DESTINATIONS
                                    },
                                    onBack = { showTelegramConnectionScreen = false }
                                )
                            } else {
                                when (currentTab) {
                                    VaultNavTab.DASHBOARD -> DashboardScreen(
                                        settings = settings,
                                        botState = botState,
                                        destinations = destinations,
                                        selectedSources = selectedSources,
                                        transferTasks = transferTasks,
                                        manifests = manifests,
                                        engineProgress = engineProgress,
                                        onBackupNow = {
                                            viewModel.startBackupNow()
                                            currentTab = VaultNavTab.TRANSFERS
                                        },
                                        onSyncNow = {
                                            viewModel.triggerWorkManagerSyncNow()
                                            currentTab = VaultNavTab.TRANSFERS
                                        },
                                        onOpenBotConnection = { showTelegramConnectionScreen = true },
                                        onOpenDestinations = { currentTab = VaultNavTab.DESTINATIONS },
                                        onOpenCategories = { currentTab = VaultNavTab.CATEGORIES },
                                        onOpenTransfers = { currentTab = VaultNavTab.TRANSFERS },
                                        onOpenSettings = { currentTab = VaultNavTab.SETTINGS }
                                    )

                                    VaultNavTab.CATEGORIES -> BackupCategoriesScreen(
                                        settings = settings,
                                        selectedSources = selectedSources,
                                        isBusy = isBusy,
                                        onToggleCategory = { cat, enabled ->
                                            viewModel.toggleCategory(cat, enabled)
                                        },
                                        onAddFiles = { uris, cat ->
                                            viewModel.addSelectedFiles(uris, cat)
                                        },
                                        onAddFolder = { uri, cat ->
                                            viewModel.addAuthorizedFolder(uri, cat)
                                        },
                                        onExportContacts = { viewModel.exportAndQueueContactsNow() },
                                        onExportCalendar = { viewModel.exportAndQueueCalendarNow() },
                                        onUpdateContactOptions = { fmt, exPh, exEm, exOrg, exNotes ->
                                            viewModel.updateContactExportSettings(fmt, exPh, exEm, exOrg, exNotes)
                                        },
                                        onRemoveSource = { id -> viewModel.removeSelectedSource(id) },
                                        onClearCategory = { cat -> viewModel.clearSourcesForCategory(cat) },
                                        onBackupNow = {
                                            viewModel.startBackupNow()
                                            currentTab = VaultNavTab.TRANSFERS
                                        },
                                        onPostStatus = { msg -> viewModel.postStatusMessage(msg) }
                                    )

                                    VaultNavTab.DESTINATIONS -> DestinationManagerScreen(
                                        botState = botState,
                                        destinations = destinations,
                                        discoveredCandidates = discoveredChats,
                                        jobOverrideChatId = settings.jobDestinationOverrideChatId,
                                        isBusy = isBusy,
                                        onDiscoverFromUpdates = { viewModel.discoverAccessibleDestinations() },
                                        onSaveManualDestination = { chatId, title, notes, makeDef ->
                                            viewModel.verifyAndSaveDestination(chatId, title, notes, makeDef)
                                        },
                                        onSaveDiscoveredCandidate = { cand, makeDef ->
                                            viewModel.saveDiscoveredCandidate(cand, makeDef)
                                        },
                                        onSetDefaultDestination = { chatId ->
                                            viewModel.setDefaultDestination(chatId)
                                        },
                                        onSetJobOverrideDestination = { chatId ->
                                            viewModel.setJobDestinationOverride(chatId)
                                        },
                                        onSendTestPing = { dest ->
                                            viewModel.sendTestPingToDestination(dest)
                                        },
                                        onDeleteDestination = { chatId ->
                                            viewModel.deleteDestination(chatId)
                                        },
                                        onOpenBotConnection = { showTelegramConnectionScreen = true }
                                    )

                                    VaultNavTab.TRANSFERS -> TransfersAndHistoryScreen(
                                        tasks = transferTasks,
                                        engineProgress = engineProgress,
                                        recentLogs = recentLogs,
                                        onStartBackupNow = { viewModel.startBackupNow() },
                                        onPauseAll = { viewModel.pauseTransfers() },
                                        onCancelAll = { viewModel.cancelAllTransfers() },
                                        onCancelTask = { taskId -> viewModel.cancelSingleTransfer(taskId) },
                                        onRetryFailed = { viewModel.retryFailedTransfers() },
                                        onClearFinished = { viewModel.clearFinishedTransferHistory() }
                                    )

                                    VaultNavTab.RESTORE -> BackupManifestRestoreScreen(
                                        manifests = manifests,
                                        hasSavedPassphrase = hasPassphrase,
                                        isBusy = isBusy,
                                        restoreProgress = restoreProgress,
                                        lastRestoreResult = lastRestoreResult,
                                        onRestoreManifest = { manifest, passphrase, targetUri ->
                                            viewModel.restoreFromManifest(manifest, passphrase, targetUri)
                                        }
                                    )

                                    VaultNavTab.SETTINGS -> SettingsAndSecurityScreen(
                                        settings = settings,
                                        botState = botState,
                                        destinations = destinations,
                                        hasEncryptionPassphrase = hasPassphrase,
                                        encryptionFingerprint = keyFingerprint,
                                        onOpenBotConnection = { showTelegramConnectionScreen = true },
                                        onOpenDestinations = { currentTab = VaultNavTab.DESTINATIONS },
                                        onSavePassphrase = { pass ->
                                            viewModel.saveMasterEncryptionPassphrase(pass)
                                        },
                                        onClearPassphrase = {
                                            viewModel.clearMasterEncryptionPassphrase()
                                        },
                                        onUpdateEncryptionConfig = { en, encMan, split ->
                                            viewModel.updateEncryptionConfig(en, encMan, split)
                                        },
                                        onUpdateSyncSchedule = { auto, hrs, wifi, chg, conc, dedup ->
                                            viewModel.updateSyncSchedule(auto, hrs, wifi, chg, conc, dedup)
                                        },
                                        onSetThemeMode = { mode -> viewModel.setThemeMode(mode) },
                                        onExportConfigToUri = { uri ->
                                            viewModel.exportConfigurationToUri(uri)
                                        },
                                        onClearAllHistory = { viewModel.clearAllLocalHistory() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
