package com.swiftvault.backup.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.swiftvault.backup.data.database.AppDatabaseHelper
import com.swiftvault.backup.data.model.LogLevel
import com.swiftvault.backup.engine.BackupEngine
import kotlinx.coroutines.flow.last
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Worker executed periodically by WorkManager for scheduled automated backups.
 */
class ScheduledBackupWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dbHelper = AppDatabaseHelper.getInstance(appContext)
        dbHelper.insertLog(LogLevel.INFO, "ScheduledBackup", "Iniciando rotina agendada de backup automático...")

        return try {
            val backupEngine = BackupEngine(appContext)
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            val title = "Backup Automático - $dateStr"

            // Run automated backup of system configs, telephony and standard files
            val progress = backupEngine.performSelectiveBackup(
                backupTitle = title,
                includeSms = true,
                includeCalls = true,
                includeDns = true,
                includeWallpaper = true
            ).last()

            if (progress.error != null) {
                dbHelper.insertLog(LogLevel.ERROR, "ScheduledBackup", "Falha no backup agendado: ${progress.error}")
                Result.retry()
            } else {
                dbHelper.insertLog(LogLevel.INFO, "ScheduledBackup", "Backup agendado concluído com sucesso.")
                Result.success()
            }
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.ERROR, "ScheduledBackup", "Exceção no worker de backup agendado: ${e.message}")
            Result.failure()
        }
    }
}
