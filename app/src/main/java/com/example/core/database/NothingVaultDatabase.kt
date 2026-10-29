package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TelegramDestinationEntity::class,
        SelectedSourceItemEntity::class,
        TransferTaskEntity::class,
        BackupManifestEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NothingVaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: NothingVaultDatabase? = null

        fun getInstance(context: Context): NothingVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NothingVaultDatabase::class.java,
                    "nothing_vault.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun createInMemory(context: Context): NothingVaultDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                NothingVaultDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
