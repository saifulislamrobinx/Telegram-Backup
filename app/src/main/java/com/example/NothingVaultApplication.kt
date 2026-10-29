package com.example

import android.app.Application
import com.example.core.database.NothingVaultDatabase
import com.example.core.network.TelegramBotClient
import com.example.core.security.SecureCredentialStore
import com.example.data.DeviceContentExporter
import com.example.data.NothingVaultSyncWorker
import com.example.data.TransferEngine
import com.example.data.VaultPreferencesRepository

class AppContainer(application: Application) {
    val database: NothingVaultDatabase by lazy {
        NothingVaultDatabase.getInstance(application)
    }
    val credentialStore: SecureCredentialStore by lazy {
        SecureCredentialStore(application)
    }
    val preferencesRepository: VaultPreferencesRepository by lazy {
        VaultPreferencesRepository(application)
    }
    val telegramClient: TelegramBotClient by lazy {
        TelegramBotClient()
    }
    val contentExporter: DeviceContentExporter by lazy {
        DeviceContentExporter(application)
    }
    val transferEngine: TransferEngine by lazy {
        TransferEngine(
            context = application,
            vaultDao = database.vaultDao(),
            preferencesRepository = preferencesRepository,
            credentialStore = credentialStore,
            telegramClient = telegramClient,
            contentExporter = contentExporter
        )
    }
}

class NothingVaultApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NothingVaultSyncWorker.ensureNotificationChannel(this)
    }
}
