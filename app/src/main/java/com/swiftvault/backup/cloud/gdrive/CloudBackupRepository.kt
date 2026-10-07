package com.swiftvault.backup.cloud.gdrive

import android.content.Context
import com.swiftvault.backup.data.database.AppDatabaseHelper
import com.swiftvault.backup.data.model.BackupRecord
import com.swiftvault.backup.data.model.BackupStatus
import com.swiftvault.backup.data.model.CloudProviderType
import com.swiftvault.backup.data.model.LogLevel
import com.swiftvault.backup.data.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CloudBackupRepository(
    private val context: Context,
    val oAuthManager: OAuthManager,
    val driveFileManager: DriveFileManager,
    val uploadManager: UploadManager,
    val downloadManager: DownloadManager,
    val integrityManager: IntegrityManager
) {
    private val dbHelper = AppDatabaseHelper.getInstance(context)

    /**
     * Fetch list of all existing backups stored on user's Google Drive
     */
    suspend fun fetchRemoteBackups(): Result<List<DriveFileMetadata>> = withContext(Dispatchers.IO) {
        driveFileManager.listRemoteBackups()
    }

    fun pauseUpload() = uploadManager.pause()
    fun resumeUpload() = uploadManager.resume()
    fun cancelUpload() = uploadManager.cancel()

    /**
     * Upload local .svb backup to Google Drive with Resumable Upload and rigorous integrity check
     */
    suspend fun uploadLocalBackup(
        backupRecord: BackupRecord,
        onProgress: (UploadProgressInfo) -> Unit
    ): Result<DriveFileMetadata> = withContext(Dispatchers.IO) {
        val localFile = File(backupRecord.filePath)
        if (!localFile.exists()) {
            val err = "Arquivo local não encontrado: ${backupRecord.filePath}"
            onProgress(UploadProgressInfo("Falha", 0, 0, 0f, 0f, 0, isFinished = true, error = err))
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        // 1. Check storage quota
        onProgress(UploadProgressInfo("Preparando backup...", 0, localFile.length(), 0.02f, 0f, 0))
        val quotaResult = driveFileManager.getStorageQuota()
        if (quotaResult.isSuccess) {
            val quota = quotaResult.getOrThrow()
            if (quota.isStorageFull || (quota.limitBytes - quota.usageBytes) < localFile.length()) {
                val err = InsufficientStorageException("Não há espaço suficiente no Google Drive para concluir este backup (${(localFile.length() / (1024 * 1024))} MB necessários).")
                dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", err.message ?: "")
                onProgress(UploadProgressInfo("Falha", 0, localFile.length(), 0f, 0f, 0, isFinished = true, error = err.message))
                return@withContext Result.failure(err)
            }
        }

        // 2. Ensure folder hierarchy in Google Drive
        onProgress(UploadProgressInfo("Conectando ao Google Drive...", 0, localFile.length(), 0.05f, 0f, 0))
        val hierarchyResult = driveFileManager.ensureBackupHierarchy()
        if (hierarchyResult.isFailure) {
            val ex = hierarchyResult.exceptionOrNull()
            val is401 = ex is GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
            val friendlyError = if (is401) {
                "A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.\nCódigo técnico: HTTP 401"
            } else {
                ex?.message ?: "Falha ao acessar pasta no Google Drive."
            }
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", friendlyError)
            onProgress(UploadProgressInfo("Falha", 0, localFile.length(), 0f, 0f, 0, isFinished = true, error = friendlyError))
            return@withContext Result.failure(ex ?: Exception(friendlyError))
        }
        val hierarchy = hierarchyResult.getOrNull()
        val targetFolderId = hierarchy?.rootFolderId ?: hierarchy?.backupsFolderId

        // 3. Resumable Upload
        val appProps = mapOf(
            "backupId" to backupRecord.id,
            "title" to backupRecord.title,
            "version" to "1.0",
            "isEncrypted" to backupRecord.isEncrypted.toString(),
            "sha256" to backupRecord.checksumSha256
        )

        dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.UPLOADING)

        val uploadResult = uploadManager.executeResumableUpload(
            localFile = localFile,
            targetFolderId = targetFolderId,
            backupMetadata = appProps,
            onProgress = onProgress
        )

        if (uploadResult.isFailure) {
            val ex = uploadResult.exceptionOrNull()
            dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.FAILED)
            onProgress(
                UploadProgressInfo(
                    stage = "Falha no envio",
                    bytesUploaded = 0,
                    totalBytes = localFile.length(),
                    progressPercent = 0f,
                    speedMBps = 0f,
                    estimatedRemainingSeconds = 0,
                    isFinished = true,
                    error = ex?.message ?: "Falha na transferência para o Google Drive."
                )
            )
            return@withContext uploadResult
        }

        val driveFile = uploadResult.getOrThrow()

        // 4. Rigorous Post-Upload Verification
        dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.VERIFYING)
        onProgress(UploadProgressInfo("Verificando arquivo no Google Drive...", localFile.length(), localFile.length(), 1.0f, 0f, 0, isFinished = false))

        // Confirm size
        if (driveFile.sizeBytes > 0 && driveFile.sizeBytes != localFile.length()) {
            val sizeMismatchMsg = "Inconsistência no tamanho do arquivo remoto: esperado ${localFile.length()} bytes, obtido ${driveFile.sizeBytes} bytes."
            dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.FAILED)
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", sizeMismatchMsg)
            onProgress(UploadProgressInfo("Falha", localFile.length(), localFile.length(), 1.0f, 0f, 0, isFinished = true, error = sizeMismatchMsg))
            return@withContext Result.failure(IllegalStateException(sizeMismatchMsg))
        }

        // Integrity Validation (Checksum & MD5)
        val integrity = integrityManager.verifyUploadIntegrity(localFile, driveFile)
        if (!integrity.isVerified) {
            dbHelper.updateBackupSyncStatus(backupRecord.id, SyncStatus.FAILED)
            dbHelper.insertLog(LogLevel.ERROR, "GoogleDrive", "Falha na verificação de integridade pós-upload: ${integrity.failureReason}")
            onProgress(UploadProgressInfo("Falha", localFile.length(), localFile.length(), 1.0f, 0f, 0, isFinished = true, error = integrity.failureReason))
            return@withContext Result.failure(IllegalStateException(integrity.failureReason ?: "Falha na verificação de integridade."))
        }

        // 5. Update local record with SINGLE SOURCE OF TRUTH: SYNCED
        val updatedRecord = backupRecord.copy(
            destination = "Google Drive",
            status = BackupStatus.COMPLETED,
            syncStatus = SyncStatus.SYNCED,
            driveFileId = driveFile.id
        )
        dbHelper.insertBackupRecord(updatedRecord)
        dbHelper.insertLog(LogLevel.INFO, "GoogleDrive", "Backup verificado com sucesso no Google Drive: ${driveFile.name}")

        // Refresh remote storage quota in database
        val quotaAfter = driveFileManager.getStorageQuota().getOrNull()
        if (quotaAfter != null) {
            dbHelper.updateCloudAccountQuota(
                CloudProviderType.GOOGLE_DRIVE.name,
                quotaAfter.usageBytes,
                quotaAfter.limitBytes
            )
        }

        onProgress(UploadProgressInfo("✓ Backup sincronizado", localFile.length(), localFile.length(), 1.0f, 0f, 0, isFinished = true))
        Result.success(driveFile)
    }

    /**
     * Check real state of backups against Google Drive and update Single Source of Truth
     */
    suspend fun verifyRemoteStatusForLocalBackups(): List<BackupRecord> = withContext(Dispatchers.IO) {
        val remoteListRes = driveFileManager.listRemoteBackups()
        val allLocal = dbHelper.getAllBackupRecords()
        if (remoteListRes.isSuccess) {
            val remoteFiles = remoteListRes.getOrThrow()
            val remoteIds = remoteFiles.map { it.id }.toSet()
            val remoteNames = remoteFiles.map { it.name }.toSet()

            for (local in allLocal) {
                val fileName = File(local.filePath).name
                val isPresentInDrive = (local.driveFileId != null && remoteIds.contains(local.driveFileId)) ||
                        remoteNames.contains(fileName)

                if (local.syncStatus == SyncStatus.SYNCED && !isPresentInDrive) {
                    // File was deleted from Google Drive! Revert to LOCAL_ONLY
                    dbHelper.updateBackupSyncStatus(local.id, SyncStatus.LOCAL_ONLY, null)
                    dbHelper.insertLog(LogLevel.WARN, "GoogleDrive", "Backup ${local.title} não encontrado no Google Drive. Marcado como Não Sincronizado.")
                } else if (local.syncStatus != SyncStatus.SYNCED && isPresentInDrive) {
                    // File was found on Google Drive!
                    val matching = remoteFiles.find { it.id == local.driveFileId || it.name == fileName }
                    dbHelper.updateBackupSyncStatus(local.id, SyncStatus.SYNCED, matching?.id)
                }
            }
        }
        dbHelper.getAllBackupRecords()
    }

    /**
     * Download backup from Google Drive and prepare for restoration
     */
    suspend fun downloadRemoteBackup(
        remoteFile: DriveFileMetadata,
        onProgress: (DownloadProgressInfo) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "gdrive_downloads")
        tempDir.mkdirs()
        val destinationFile = File(tempDir, remoteFile.name)

        downloadManager.downloadBackupFile(
            fileId = remoteFile.id,
            destinationFile = destinationFile,
            totalBytesEstimated = remoteFile.sizeBytes,
            onProgress = onProgress
        )
    }

    /**
     * Delete remote backup from Google Drive
     */
    suspend fun deleteRemoteBackup(fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        driveFileManager.deleteFile(fileId)
    }
}
