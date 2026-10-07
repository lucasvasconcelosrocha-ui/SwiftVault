package com.swiftvault.backup

import android.app.Application
import androidx.work.*
import com.swiftvault.backup.data.database.AppDatabaseHelper
import com.swiftvault.backup.data.model.LogLevel
import com.swiftvault.backup.engine.SecurityManager
import com.swiftvault.backup.worker.CloudSyncWorker
import com.swiftvault.backup.worker.ScheduledBackupWorker
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SwiftVaultApplication : Application() {

    private val applicationScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Configure LibSU shell settings
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )

        // Initialize SQLite Database and hardware keys
        applicationScope.launch {
            val db = AppDatabaseHelper.getInstance(this@SwiftVaultApplication)
            db.insertLog(LogLevel.INFO, "System", "SwiftVault Backup iniciado com sucesso no Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")

            try {
                SecurityManager.getOrCreateHardwareKey()
            } catch (e: Exception) {
                db.insertLog(LogLevel.WARN, "Security", "KeyStore de hardware não disponível: ${e.message}")
            }
        }

        // Setup background tasks
        setupBackgroundWorkers()
    }

    private fun setupBackgroundWorkers() {
        val workManager = WorkManager.getInstance(this)

        // Periodic Cloud Sync
        val syncConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWorkRequest = PeriodicWorkRequestBuilder<CloudSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(syncConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "SwiftVaultCloudSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncWorkRequest
        )

        // Daily scheduled backup
        val backupConstraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val backupWorkRequest = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(backupConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "SwiftVaultDailyBackup",
            ExistingPeriodicWorkPolicy.KEEP,
            backupWorkRequest
        )
    }
}
