package com.swiftvault.backup.cloud.gdrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadProgressInfo(
    val stage: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressPercent: Float,
    val speedMBps: Float,
    val estimatedRemainingSeconds: Long,
    val isFinished: Boolean = false,
    val error: String? = null
)

class DownloadManager(private val oAuthManager: OAuthManager) {

    private val isCancelled = AtomicBoolean(false)
    private val BUFFER_SIZE = 64 * 1024

    fun cancel() {
        isCancelled.set(true)
    }

    /**
     * Downloads .svb backup from Google Drive with real-time speed and byte tracking
     */
    suspend fun downloadBackupFile(
        fileId: String,
        destinationFile: File,
        totalBytesEstimated: Long,
        onProgress: (DownloadProgressInfo) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val token = oAuthManager.getValidAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("Usuário não autenticado no Google Drive."))

        isCancelled.set(false)
        destinationFile.parentFile?.mkdirs()

        try {
            var currentToken = token
            var conn = (URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $currentToken")
                connectTimeout = 15000
                readTimeout = 30000
            }

            var code = conn.responseCode
            if (code == 401) {
                try { conn.disconnect() } catch (_: Exception) {}
                val refreshed = oAuthManager.forceRefreshToken()
                if (refreshed.isSuccess) {
                    currentToken = refreshed.getOrThrow()
                    conn = (URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $currentToken")
                        connectTimeout = 15000
                        readTimeout = 30000
                    }
                    code = conn.responseCode
                } else {
                    return@withContext Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
                }
            }

            if (code == 401) {
                return@withContext Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
            } else if (code != 200) {
                return@withContext Result.failure(GoogleDriveApiException(code, "Falha ao baixar arquivo do Google Drive (HTTP $code)"))
            }

            val remoteLength = conn.contentLengthLong.let { if (it > 0) it else totalBytesEstimated }
            var bytesDownloaded = 0L
            val startTime = System.currentTimeMillis()

            conn.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        if (isCancelled.get()) {
                            destinationFile.delete()
                            onProgress(
                                DownloadProgressInfo("Cancelado", bytesDownloaded, remoteLength, 0f, 0f, 0, true, "Download cancelado.")
                            )
                            return@withContext Result.failure(Exception("Download cancelado pelo usuário."))
                        }

                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                        val speedBytesSec = bytesDownloaded / elapsedSeconds
                        val speedMBps = (speedBytesSec / (1024.0 * 1024.0)).toFloat()
                        val remainingBytes = (remoteLength - bytesDownloaded).coerceAtLeast(0)
                        val etaSeconds = if (speedBytesSec > 0) (remainingBytes / speedBytesSec).toLong() else 0L
                        val percent = if (remoteLength > 0) (bytesDownloaded.toFloat() / remoteLength.toFloat()).coerceIn(0f, 1f) else 0.5f

                        onProgress(
                            DownloadProgressInfo(
                                stage = "Baixando backup do Google Drive...",
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = remoteLength,
                                progressPercent = percent,
                                speedMBps = speedMBps,
                                estimatedRemainingSeconds = etaSeconds
                            )
                        )
                    }
                    output.flush()
                }
            }

            onProgress(
                DownloadProgressInfo(
                    stage = "Download concluído com sucesso!",
                    bytesDownloaded = destinationFile.length(),
                    totalBytes = destinationFile.length(),
                    progressPercent = 1.0f,
                    speedMBps = 0f,
                    estimatedRemainingSeconds = 0,
                    isFinished = true
                )
            )

            Result.success(destinationFile)
        } catch (e: Exception) {
            destinationFile.delete()
            Result.failure(e)
        }
    }
}
