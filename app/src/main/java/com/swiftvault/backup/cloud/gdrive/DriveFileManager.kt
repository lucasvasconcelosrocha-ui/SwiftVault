package com.swiftvault.backup.cloud.gdrive

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DriveStorageQuota(
    val limitBytes: Long,
    val usageBytes: Long,
    val usageInDriveBytes: Long,
    val isStorageFull: Boolean,
    val userEmail: String?,
    val displayName: String? = null
) {
    val availableBytes: Long
        get() = maxOf(0L, limitBytes - usageBytes)
}

data class DriveFileMetadata(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val createdTime: Long,
    val modifiedTime: Long,
    val md5Checksum: String?,
    val mimeType: String,
    val appProperties: Map<String, String> = emptyMap()
)

data class BackupFolderHierarchy(
    val rootFolderId: String,
    val backupsFolderId: String,
    val appsFolderId: String,
    val filesFolderId: String,
    val systemFolderId: String,
    val dnsFolderId: String,
    val smsFolderId: String,
    val callsFolderId: String,
    val metadataFolderId: String
)

class InsufficientStorageException(msg: String) : Exception(msg)
class GoogleDriveApiException(val statusCode: Int, msg: String) : Exception(msg)

class DriveFileManager(private val oAuthManager: OAuthManager) {

    private val gson = Gson()

    companion object {
        private const val API_BASE = "https://www.googleapis.com/drive/v3"
    }

    /**
     * Inspect Google Drive personal storage quota
     */
    suspend fun getStorageQuota(): Result<DriveStorageQuota> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken()
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado no Google Drive.", 401))

        try {
            var quotaRes = fetchStorageQuotaInternal(token)
            if (quotaRes.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                val refreshed = oAuthManager.forceRefreshToken()
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    quotaRes = fetchStorageQuotaInternal(token)
                }
            }
            quotaRes
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchStorageQuotaInternal(token: String): Result<DriveStorageQuota> {
        val url = URL("$API_BASE/about?fields=storageQuota,user")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val code = conn.responseCode
        if (code == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val json = gson.fromJson(reader, JsonObject::class.java)

            val quotaObj = json.getAsJsonObject("storageQuota")
            val limit = quotaObj?.get("limit")?.asLong ?: (15L * 1024 * 1024 * 1024)
            val usage = quotaObj?.get("usage")?.asLong ?: 0L
            val usageInDrive = quotaObj?.get("usageInDrive")?.asLong ?: 0L

            val userObj = json.getAsJsonObject("user")
            val email = userObj?.get("emailAddress")?.asString
            val displayName = userObj?.get("displayName")?.asString

            val isFull = usage >= limit
            return Result.success(
                DriveStorageQuota(
                    limitBytes = limit,
                    usageBytes = usage,
                    usageInDriveBytes = usageInDrive,
                    isStorageFull = isFull,
                    userEmail = email,
                    displayName = displayName
                )
            )
        } else if (code == 401) {
            return Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
        } else {
            return handleApiError(conn, code)
        }
    }

    /**
     * Get or create the official single Rodin_Backup folder in Google Drive.
     * Reuses existing folder if already created, preventing duplicate folders.
     */
    suspend fun getOrCreateRootBackupFolder(): Result<String> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken()
            ?: return@withContext Result.failure(
                GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401)
            )

        // 1. Check cached folder ID
        val cachedId = oAuthManager.getCachedRootFolderId()
        if (!cachedId.isNullOrBlank()) {
            val valid = checkFolderValid(cachedId, token)
            if (valid.isSuccess && valid.getOrNull() == true) {
                return@withContext Result.success(cachedId)
            } else if (valid.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                val refreshed = oAuthManager.forceRefreshToken()
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    val retryValid = checkFolderValid(cachedId, token)
                    if (retryValid.isSuccess && retryValid.getOrNull() == true) {
                        return@withContext Result.success(cachedId)
                    }
                } else {
                    return@withContext Result.failure(refreshed.exceptionOrNull() ?: GoogleDriveSessionExpiredException())
                }
            }
        }

        // 2. Search for existing "Rodin_Backup" folder
        var searchResult = searchFolder("Rodin_Backup", token)
        if (searchResult.exceptionOrNull() is GoogleDriveSessionExpiredException) {
            val refreshed = oAuthManager.forceRefreshToken()
            if (refreshed.isSuccess) {
                token = refreshed.getOrThrow()
                searchResult = searchFolder("Rodin_Backup", token)
            } else {
                return@withContext Result.failure(refreshed.exceptionOrNull() ?: GoogleDriveSessionExpiredException())
            }
        }

        val existingId = searchResult.getOrNull()
        if (!existingId.isNullOrBlank()) {
            oAuthManager.setCachedRootFolderId(existingId)
            return@withContext Result.success(existingId)
        }

        // 3. Create folder if not found
        var createResult = createFolder("Rodin_Backup", null, token)
        if (createResult.exceptionOrNull() is GoogleDriveSessionExpiredException) {
            val refreshed = oAuthManager.forceRefreshToken()
            if (refreshed.isSuccess) {
                token = refreshed.getOrThrow()
                createResult = createFolder("Rodin_Backup", null, token)
            } else {
                return@withContext Result.failure(refreshed.exceptionOrNull() ?: GoogleDriveSessionExpiredException())
            }
        }

        val createdId = createResult.getOrNull()
        if (!createdId.isNullOrBlank()) {
            oAuthManager.setCachedRootFolderId(createdId)
            return@withContext Result.success(createdId)
        } else {
            return@withContext Result.failure(createResult.exceptionOrNull() ?: Exception("Falha ao criar pasta 'Rodin_Backup' no Google Drive."))
        }
    }

    /**
     * Ensure the official Rodin_Backup folder exists in Google Drive
     */
    suspend fun ensureBackupHierarchy(): Result<BackupFolderHierarchy> = withContext(Dispatchers.IO) {
        val rootResult = getOrCreateRootBackupFolder()
        if (rootResult.isFailure) {
            return@withContext Result.failure(rootResult.exceptionOrNull() ?: Exception("Falha ao acessar pasta no Google Drive."))
        }
        val rootId = rootResult.getOrThrow()
        Result.success(
            BackupFolderHierarchy(
                rootFolderId = rootId,
                backupsFolderId = rootId,
                appsFolderId = rootId,
                filesFolderId = rootId,
                systemFolderId = rootId,
                dnsFolderId = rootId,
                smsFolderId = rootId,
                callsFolderId = rootId,
                metadataFolderId = rootId
            )
        )
    }

    /**
     * List all .svb backups in Google Drive
     */
    suspend fun listRemoteBackups(folderId: String? = null): Result<List<DriveFileMetadata>> = withContext(Dispatchers.IO) {
        var token = oAuthManager.getValidAccessToken()
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado.", 401))

        var actualFolderId = folderId
        if (actualFolderId == null) {
            actualFolderId = getOrCreateRootBackupFolder().getOrNull()
        }

        try {
            var listRes = fetchRemoteBackupsInternal(actualFolderId, token)
            if (listRes.exceptionOrNull() is GoogleDriveSessionExpiredException) {
                val refreshed = oAuthManager.forceRefreshToken()
                if (refreshed.isSuccess) {
                    token = refreshed.getOrThrow()
                    listRes = fetchRemoteBackupsInternal(actualFolderId, token)
                }
            }
            listRes
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchRemoteBackupsInternal(folderId: String?, token: String): Result<List<DriveFileMetadata>> {
        val queryBuilder = StringBuilder("trashed = false and mimeType != 'application/vnd.google-apps.folder'")
        if (folderId != null) {
            queryBuilder.append(" and '$folderId' in parents")
        } else {
            queryBuilder.append(" and name contains '.svb'")
        }

        val encodedQuery = URLEncoder.encode(queryBuilder.toString(), "UTF-8")
        val fields = URLEncoder.encode("files(id,name,size,createdTime,modifiedTime,md5Checksum,mimeType,appProperties)", "UTF-8")
        val url = URL("$API_BASE/files?q=$encodedQuery&fields=$fields&pageSize=100")

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 12000
        conn.readTimeout = 12000

        val code = conn.responseCode
        if (code == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val json = gson.fromJson(reader, JsonObject::class.java)
            val filesArray = json.getAsJsonArray("files")

            val list = mutableListOf<DriveFileMetadata>()
            if (filesArray != null) {
                for (element in filesArray) {
                    val obj = element.asJsonObject
                    val name = obj.get("name").asString
                    // Filter ODIN backup containers
                    if (!name.endsWith(".svb", ignoreCase = true) && !name.contains("Odin", ignoreCase = true)) {
                        continue
                    }
                    val id = obj.get("id").asString
                    val size = obj.get("size")?.asLong ?: 0L
                    val md5 = obj.get("md5Checksum")?.asString
                    val mime = obj.get("mimeType")?.asString ?: "application/octet-stream"

                    val appProps = mutableMapOf<String, String>()
                    if (obj.has("appProperties") && !obj.get("appProperties").isJsonNull) {
                        val propsObj = obj.getAsJsonObject("appProperties")
                        for (key in propsObj.keySet()) {
                            appProps[key] = propsObj.get(key).asString
                        }
                    }

                    list.add(
                        DriveFileMetadata(
                            id = id,
                            name = name,
                            sizeBytes = size,
                            createdTime = System.currentTimeMillis(),
                            modifiedTime = System.currentTimeMillis(),
                            md5Checksum = md5,
                            mimeType = mime,
                            appProperties = appProps
                        )
                    )
                }
            }
            return Result.success(list)
        } else if (code == 401) {
            return Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
        } else {
            return handleApiError(conn, code)
        }
    }

    /**
     * Delete file from Google Drive
     */
    suspend fun deleteFile(fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val token = oAuthManager.getValidAccessToken()
            ?: return@withContext Result.failure(GoogleDriveSessionExpiredException("Usuário não autenticado.", 401))

        try {
            val url = URL("$API_BASE/files/$fileId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10000

            val code = conn.responseCode
            if (code == 204 || code == 200) {
                Result.success(true)
            } else if (code == 401) {
                Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou.", 401))
            } else {
                handleApiError(conn, code)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun checkFolderValid(folderId: String, token: String): Result<Boolean> {
        return try {
            val url = URL("$API_BASE/files/$folderId?fields=id,name,trashed,mimeType")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val code = conn.responseCode
            if (code == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val json = gson.fromJson(reader, JsonObject::class.java)
                val trashed = json.get("trashed")?.asBoolean ?: false
                val mime = json.get("mimeType")?.asString ?: ""
                Result.success(!trashed && mime == "application/vnd.google-apps.folder")
            } else if (code == 401) {
                Result.failure(GoogleDriveSessionExpiredException("Sessão expirada (HTTP 401)", 401))
            } else {
                Result.success(false)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun searchFolder(name: String, token: String): Result<String?> {
        return try {
            val query = "name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = URL("$API_BASE/files?q=$encodedQuery&fields=files(id,name)")
            val conn = searchUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val code = conn.responseCode
            if (code == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val json = gson.fromJson(reader, JsonObject::class.java)
                val filesArray = json.getAsJsonArray("files")
                if (filesArray != null && filesArray.size() > 0) {
                    Result.success(filesArray.get(0).asJsonObject.get("id").asString)
                } else {
                    Result.success(null)
                }
            } else if (code == 401) {
                Result.failure(GoogleDriveSessionExpiredException("Sessão expirada (HTTP 401)", 401))
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createFolder(name: String, parentId: String?, token: String): Result<String> {
        return try {
            val createUrl = URL("$API_BASE/files")
            val createConn = createUrl.openConnection() as HttpURLConnection
            createConn.requestMethod = "POST"
            createConn.setRequestProperty("Authorization", "Bearer $token")
            createConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            createConn.connectTimeout = 12000
            createConn.readTimeout = 12000
            createConn.doOutput = true

            val body = JsonObject().apply {
                addProperty("name", name)
                addProperty("mimeType", "application/vnd.google-apps.folder")
                if (parentId != null) {
                    val parentsArr = com.google.gson.JsonArray().apply { add(parentId) }
                    add("parents", parentsArr)
                }
            }

            OutputStreamWriter(createConn.outputStream).use { it.write(body.toString()) }

            val code = createConn.responseCode
            if (code == 200 || code == 201) {
                val reader = BufferedReader(InputStreamReader(createConn.inputStream))
                val json = gson.fromJson(reader, JsonObject::class.java)
                Result.success(json.get("id").asString)
            } else if (code == 401) {
                Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
            } else {
                Result.failure(GoogleDriveApiException(code, "Falha ao criar pasta '$name' no Google Drive (HTTP $code)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun <T> handleApiError(conn: HttpURLConnection, code: Int): Result<T> {
        val errorBody = try {
            BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            ""
        }

        if (code == 401) {
            return Result.failure(GoogleDriveSessionExpiredException("A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.", 401))
        }

        if (code == 403 || code == 507) {
            if (errorBody.contains("storageQuotaExceeded", ignoreCase = true) || errorBody.contains("quota", ignoreCase = true)) {
                return Result.failure(InsufficientStorageException("Não há espaço suficiente no Google Drive para concluir este backup."))
            }
            if (errorBody.contains("insufficientFilePermissions", ignoreCase = true)) {
                return Result.failure(GoogleDriveApiException(code, "A conta Google não concedeu permissão de escrita."))
            }
        }

        return Result.failure(GoogleDriveApiException(code, "Erro na API do Google Drive (HTTP $code): $errorBody"))
    }
}
