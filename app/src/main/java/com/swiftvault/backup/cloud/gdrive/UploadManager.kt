package com.swiftvault.backup.cloud.gdrive

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class UploadProgressInfo(
    val stage: String,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val progressPercent: Float,
    val speedMBps: Float,
    val estimatedRemainingSeconds: Long,
    val isPaused: Boolean = false,
    val isFinished: Boolean = false,
    val error: String? = null
)

class UploadManager(private val oAuthManager: OAuthManager) {

    private val gson = Gson()

    // 2 MB chunks (8 * 256 KB) - Optimal for high-speed Wi-Fi and mobile networks according to Google Drive spec
    private val CHUNK_SIZE = 2 * 1024 * 1024

    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    fun cancel() {
        isCancelled.set(true)
    }

    /**
     * Executes real high-throughput Resumable Upload of local .svb backup to Google Drive.
     * Features:
     * - Direct streaming without memory buffering (setFixedLengthStreamingMode)
     * - Immediate recovery and resume across network drops
     * - Accurate rolling transfer speed (MB/s) and ETA calculation
     * - Atomic pause, resume and cancellation controls
     */
    suspend fun executeResumableUpload(
        localFile: File,
        targetFolderId: String?,
        backupMetadata: Map<String, String>,
        existingSessionUri: String? = null,
        onProgress: (UploadProgressInfo) -> Unit
    ): Result<DriveFileMetadata> = withContext(Dispatchers.IO) {
        if (!localFile.exists()) {
            val err = "Arquivo local não encontrado: ${localFile.absolutePath}"
            onProgress(UploadProgressInfo("Falha", 0, 0, 0f, 0f, 0, isFinished = true, error = err))
            return@withContext Result.failure(FileNotFoundException(err))
        }

        val totalBytes = localFile.length()
        if (totalBytes <= 0) {
            val err = "Arquivo de backup vazio não pode ser enviado."
            onProgress(UploadProgressInfo("Falha", 0, 0, 0f, 0f, 0, isFinished = true, error = err))
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        var token = oAuthManager.getValidAccessToken()
        if (token == null) {
            val err = "A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar."
            onProgress(UploadProgressInfo("Falha", 0, totalBytes, 0f, 0f, 0, isFinished = true, error = "$err\nCódigo técnico: HTTP 401"))
            return@withContext Result.failure(GoogleDriveSessionExpiredException(err, 401))
        }

        isPaused.set(false)
        isCancelled.set(false)

        try {
            // 1. Get or initiate resumable upload session
            onProgress(
                UploadProgressInfo(
                    stage = "Conectando ao Google Drive...",
                    bytesUploaded = 0,
                    totalBytes = totalBytes,
                    progressPercent = 0.02f,
                    speedMBps = 0f,
                    estimatedRemainingSeconds = 0
                )
            )

            val sessionUri: String = existingSessionUri ?: run {
                var sUri: String? = null
                try {
                    sUri = initiateResumableSession(
                        fileName = localFile.name,
                        totalBytes = totalBytes,
                        targetFolderId = targetFolderId,
                        appProperties = backupMetadata,
                        token = token
                    )
                } catch (e: GoogleDriveApiException) {
                    if (e.statusCode == 401) {
                        val refreshed = oAuthManager.forceRefreshToken()
                        if (refreshed.isSuccess) {
                            token = refreshed.getOrThrow()
                            sUri = initiateResumableSession(
                                fileName = localFile.name,
                                totalBytes = totalBytes,
                                targetFolderId = targetFolderId,
                                appProperties = backupMetadata,
                                token = token
                            )
                        } else {
                            throw GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401)
                        }
                    } else {
                        throw e
                    }
                }
                sUri ?: throw IllegalStateException("Não foi possível iniciar a sessão de upload.")
            }

            // 2. Query already uploaded bytes from Google Drive if resuming
            var startByte = 0L
            if (existingSessionUri != null) {
                startByte = queryCurrentServerByteOffset(sessionUri, totalBytes)
            }

            var bytesUploaded = startByte
            var lastReportTime = System.currentTimeMillis()
            var lastReportBytes = bytesUploaded

            // Rolling speed window calculation
            var smoothedSpeedMBps = 0f

            RandomAccessFile(localFile, "r").use { raf ->
                raf.seek(startByte)
                val buffer = ByteArray(CHUNK_SIZE)

                while (bytesUploaded < totalBytes) {
                    if (isCancelled.get()) {
                        val cancelMsg = "Upload cancelado pelo usuário."
                        onProgress(
                            UploadProgressInfo(
                                stage = "Cancelado",
                                bytesUploaded = bytesUploaded,
                                totalBytes = totalBytes,
                                progressPercent = (bytesUploaded.toFloat() / totalBytes),
                                speedMBps = 0f,
                                estimatedRemainingSeconds = 0,
                                isFinished = true,
                                error = cancelMsg
                            )
                        )
                        return@withContext Result.failure(Exception(cancelMsg))
                    }

                    if (isPaused.get()) {
                        onProgress(
                            UploadProgressInfo(
                                stage = "Pausado",
                                bytesUploaded = bytesUploaded,
                                totalBytes = totalBytes,
                                progressPercent = (bytesUploaded.toFloat() / totalBytes),
                                speedMBps = 0f,
                                estimatedRemainingSeconds = 0,
                                isPaused = true
                            )
                        )
                        return@withContext Result.failure(UploadInterruptedException(sessionUri, bytesUploaded, "Upload pausado pelo usuário."))
                    }

                    val remaining = totalBytes - bytesUploaded
                    val currentChunkSize = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
                    val bytesRead = raf.read(buffer, 0, currentChunkSize)

                    if (bytesRead <= 0) break

                    val endByte = bytesUploaded + bytesRead - 1

                    // Send chunk with exponential retry on network glitches
                    val chunkResult = uploadChunkWithRetry(
                        sessionUri = sessionUri,
                        chunkData = buffer,
                        length = bytesRead,
                        startByte = bytesUploaded,
                        endByte = endByte,
                        totalBytes = totalBytes,
                        onRetry = { attempt, delaySec ->
                            onProgress(
                                UploadProgressInfo(
                                    stage = "Reconectando (tentativa $attempt em ${delaySec}s)...",
                                    bytesUploaded = bytesUploaded,
                                    totalBytes = totalBytes,
                                    progressPercent = (bytesUploaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
                                    speedMBps = 0f,
                                    estimatedRemainingSeconds = 0
                                )
                            )
                        }
                    )

                    // Verify acknowledged byte offset from server or update
                    val acknowledgedBytes = chunkResult.acknowledgedByteOffset ?: (bytesUploaded + bytesRead)
                    bytesUploaded = acknowledgedBytes
                    raf.seek(bytesUploaded)

                    // Calculate real instantaneous metrics
                    val now = System.currentTimeMillis()
                    val timeDeltaSec = (now - lastReportTime) / 1000.0
                    if (timeDeltaSec >= 0.3) {
                        val bytesDelta = bytesUploaded - lastReportBytes
                        val currentSpeedMBps = ((bytesDelta / (1024.0 * 1024.0)) / timeDeltaSec).toFloat()
                        smoothedSpeedMBps = if (smoothedSpeedMBps == 0f) currentSpeedMBps else (0.7f * smoothedSpeedMBps + 0.3f * currentSpeedMBps)
                        lastReportTime = now
                        lastReportBytes = bytesUploaded
                    }

                    val remainingBytes = totalBytes - bytesUploaded
                    val speedBytesSec = smoothedSpeedMBps * 1024 * 1024
                    val etaSeconds = if (speedBytesSec > 0) (remainingBytes / speedBytesSec).toLong() else 0L
                    val progressPercent = (bytesUploaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)

                    onProgress(
                        UploadProgressInfo(
                            stage = "Enviando para Google Drive (${(progressPercent * 100).toInt()}%)",
                            bytesUploaded = bytesUploaded,
                            totalBytes = totalBytes,
                            progressPercent = progressPercent,
                            speedMBps = smoothedSpeedMBps,
                            estimatedRemainingSeconds = etaSeconds
                        )
                    )

                    // Final chunk completed and file created on Google Drive
                    if (chunkResult.metadata != null) {
                        onProgress(
                            UploadProgressInfo(
                                stage = "Verificando no Google Drive...",
                                bytesUploaded = totalBytes,
                                totalBytes = totalBytes,
                                progressPercent = 1.0f,
                                speedMBps = smoothedSpeedMBps,
                                estimatedRemainingSeconds = 0,
                                isFinished = false
                            )
                        )
                        return@withContext Result.success(chunkResult.metadata)
                    }
                }
            }

            val finalError = "Upload finalizado sem confirmação de metadados do Google Drive."
            onProgress(UploadProgressInfo("Falha", bytesUploaded, totalBytes, 0f, 0f, 0, isFinished = true, error = finalError))
            Result.failure(IllegalStateException(finalError))
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Erro durante o upload."
            onProgress(UploadProgressInfo("Falha", 0, totalBytes, 0f, 0f, 0, isFinished = true, error = errorMsg))
            Result.failure(e)
        }
    }

    /**
     * Step 1: Initiate session with Google Drive
     */
    private fun initiateResumableSession(
        fileName: String,
        totalBytes: Long,
        targetFolderId: String?,
        appProperties: Map<String, String>,
        token: String
    ): String {
        val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Upload-Content-Length", totalBytes.toString())
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 30000

            val metadata = JsonObject().apply {
                addProperty("name", fileName)
                addProperty("description", "SwiftVault Container Backup (.svb)")
                if (!targetFolderId.isNullOrBlank()) {
                    val parentsArr = com.google.gson.JsonArray().apply { add(targetFolderId) }
                    add("parents", parentsArr)
                }
                if (appProperties.isNotEmpty()) {
                    val props = JsonObject()
                    for ((k, v) in appProperties) {
                        props.addProperty(k, v)
                    }
                    add("appProperties", props)
                }
            }

            OutputStreamWriter(conn.outputStream).use { it.write(metadata.toString()) }

            val code = conn.responseCode
            if (code == 200 || code == 201) {
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    return location
                } else {
                    throw IllegalStateException("Google Drive não retornou a URL de sessão resumível (Location header).")
                }
            } else if (code == 403 || code == 507) {
                throw InsufficientStorageException("Não há espaço suficiente no Google Drive para concluir este backup.")
            } else {
                throw GoogleDriveApiException(code, "Falha ao iniciar upload resumível no Google Drive (HTTP $code)")
            }
        } finally {
            conn.disconnect()
        }
    }

    private data class ChunkUploadResult(
        val acknowledgedByteOffset: Long?,
        val metadata: DriveFileMetadata?
    )

    /**
     * Upload an individual chunk with Content-Range and retry logic
     */
    private suspend fun uploadChunkWithRetry(
        sessionUri: String,
        chunkData: ByteArray,
        length: Int,
        startByte: Long,
        endByte: Long,
        totalBytes: Long,
        onRetry: (attempt: Int, delaySec: Long) -> Unit
    ): ChunkUploadResult {
        var attempts = 0
        val maxAttempts = 4
        var lastException: Exception? = null

        while (attempts < maxAttempts) {
            attempts++
            try {
                return uploadChunkInternal(
                    sessionUri = sessionUri,
                    chunkData = chunkData,
                    length = length,
                    startByte = startByte,
                    endByte = endByte,
                    totalBytes = totalBytes
                )
            } catch (e: Exception) {
                lastException = e
                if (isCancelled.get() || isPaused.get()) {
                    throw e
                }
                if (attempts < maxAttempts) {
                    val backoffSec = (1L shl (attempts - 1)).coerceAtMost(16L)
                    onRetry(attempts, backoffSec)
                    delay(backoffSec * 1000)
                }
            }
        }

        throw lastException ?: IOException("Falha no upload do bloco após $maxAttempts tentativas.")
    }

    /**
     * Transmit single chunk via HTTP PUT
     */
    private fun uploadChunkInternal(
        sessionUri: String,
        chunkData: ByteArray,
        length: Int,
        startByte: Long,
        endByte: Long,
        totalBytes: Long
    ): ChunkUploadResult {
        val url = URL(sessionUri)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.instanceFollowRedirects = false
            conn.setFixedLengthStreamingMode(length)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", length.toString())
            conn.setRequestProperty("Content-Range", "bytes $startByte-$endByte/$totalBytes")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 45000

            conn.outputStream.use { os ->
                os.write(chunkData, 0, length)
                os.flush()
            }

            val code = conn.responseCode
            return when (code) {
                308 -> {
                    // Resume Incomplete - Extract acknowledged Range from server
                    val rangeHeader = conn.getHeaderField("Range")
                    val ackOffset = if (!rangeHeader.isNullOrBlank()) {
                        val match = Regex("bytes=0-(\\d+)").find(rangeHeader)
                        match?.groupValues?.get(1)?.toLong()?.plus(1) ?: (endByte + 1)
                    } else {
                        endByte + 1
                    }
                    ChunkUploadResult(acknowledgedByteOffset = ackOffset, metadata = null)
                }
                200, 201 -> {
                    // Final chunk completed - parse DriveFileMetadata
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val json = gson.fromJson(reader, JsonObject::class.java)

                    val meta = DriveFileMetadata(
                        id = json.get("id").asString,
                        name = json.get("name").asString,
                        sizeBytes = json.get("size")?.asLong ?: totalBytes,
                        createdTime = System.currentTimeMillis(),
                        modifiedTime = System.currentTimeMillis(),
                        md5Checksum = json.get("md5Checksum")?.asString,
                        mimeType = json.get("mimeType")?.asString ?: "application/octet-stream"
                    )
                    ChunkUploadResult(acknowledgedByteOffset = totalBytes, metadata = meta)
                }
                403, 507 -> {
                    throw InsufficientStorageException("Não há espaço suficiente no Google Drive para concluir este backup.")
                }
                else -> {
                    throw GoogleDriveApiException(code, "Falha no envio do bloco para o Google Drive (HTTP $code)")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Query server for acknowledged byte offset in case of connection drop
     */
    fun queryCurrentServerByteOffset(sessionUri: String, totalBytes: Long): Long {
        val url = URL(sessionUri)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.instanceFollowRedirects = false
            conn.setFixedLengthStreamingMode(0)
            conn.setRequestProperty("Content-Range", "bytes */$totalBytes")
            conn.connectTimeout = 20000
            conn.readTimeout = 20000

            val code = conn.responseCode
            if (code == 308) {
                val rangeHeader = conn.getHeaderField("Range")
                if (!rangeHeader.isNullOrBlank()) {
                    val match = Regex("bytes=0-(\\d+)").find(rangeHeader)
                    if (match != null) {
                        val lastByte = match.groupValues[1].toLong()
                        return lastByte + 1
                    }
                }
            }
            return 0L
        } catch (e: Exception) {
            return 0L
        } finally {
            conn.disconnect()
        }
    }
}

class UploadInterruptedException(
    val sessionUri: String,
    val offsetBytes: Long,
    msg: String
) : Exception(msg)
