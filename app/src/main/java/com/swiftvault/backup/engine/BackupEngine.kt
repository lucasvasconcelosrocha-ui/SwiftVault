package com.swiftvault.backup.engine

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import android.provider.CallLog
import android.provider.Telephony
import android.util.Base64
import com.google.gson.Gson
import com.swiftvault.backup.data.database.AppDatabaseHelper
import com.swiftvault.backup.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class BackupProgress(
    val stage: String,
    val currentItemName: String,
    val progressPercent: Float,
    val bytesProcessed: Long,
    val totalBytesEstimated: Long,
    val isFinished: Boolean = false,
    val error: String? = null
)

/**
 * Main Backup Orchestration Engine.
 * Executes granular, selective backups with real post-backup SHA-256 integrity validation.
 */
class BackupEngine(private val context: Context) {

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val dnsManager = DnsManager(context)
    private val gson = Gson()

    /**
     * Executes a selective backup session
     */
    fun hasRootOrManageExternalStorage(): Boolean {
        val hasManage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }
        val hasRoot = com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true
        return hasManage || hasRoot
    }

    /**
     * Executes a selective backup session
     */
    fun performSelectiveBackup(
        backupTitle: String,
        selectedApps: List<Pair<AppInfo, AppSelectionOptions>> = emptyList(),
        selectedFilesAndFolders: List<String> = emptyList(),
        includeSms: Boolean = false,
        includeCalls: Boolean = false,
        includeContacts: Boolean = false,
        includeDns: Boolean = false,
        includeWallpaper: Boolean = false,
        filter: BackupFilter? = null,
        encryptionPassword: String? = null,
        destinationDir: File? = null
    ): Flow<BackupProgress> = channelFlow {
        val backupId = "svb_" + System.currentTimeMillis()

        send(BackupProgress("Iniciando", "Verificando permissões e espaço de armazenamento...", 0.02f, 0, 100))
        dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Iniciando backup: $backupTitle")

        // 0. Verify broad file access permissions if saving to internal storage root
        if (destinationDir == null && !hasRootOrManageExternalStorage()) {
            val permError = "Permissão necessária: Gravar na raiz da memória interna (/storage/emulated/0/SwiftVaultBackups) exige permissão 'Acesso a todos os arquivos' (MANAGE_EXTERNAL_STORAGE) ou Root autorizado no Magisk/KernelSU."
            dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", permError)
            send(BackupProgress("Erro", permError, 1.0f, 0, 0, isFinished = true, error = permError))
            return@channelFlow
        }

        // Staging directory for atomic generation and hash validation
        val stagingDir = File(context.cacheDir, "staging_$backupId")
        stagingDir.mkdirs()
        val stagingFile = File(stagingDir, "$backupId.zip")

        try {
            val packEntries = mutableListOf<ArchiveEngine.PackEntry>()
            val manifestItems = mutableListOf<ManifestItem>()

            var totalEstimatedSize = 0L

            // 1. Process Selected Apps (Base APK + Splits)
            val totalApps = selectedApps.size
            for ((index, pair) in selectedApps.withIndex()) {
                val (app, options) = pair
                val stepFraction = if (totalApps > 0) (index.toFloat() / totalApps.toFloat()) else 0f
                val appProgressPercent = 0.05f + (stepFraction * 0.15f)
                send(BackupProgress("Aplicativos", "Analisando ${app.appName}... (${index + 1}/$totalApps)", appProgressPercent, 0, 100))

                // Base APK
                if (options.includeBaseApk) {
                    val baseApk = File(app.sourceDir)
                    if (baseApk.exists() && baseApk.canRead()) {
                        val entryName = "apps/${app.packageName}/base.apk"
                        val item = ManifestItem(
                            id = UUID.randomUUID().toString(),
                            type = BackupItemType.APP_APK,
                            name = "${app.appName} (Base APK)",
                            originalPath = app.sourceDir,
                            archiveEntryPath = entryName,
                            sizeBytes = baseApk.length(),
                            sha256Checksum = "",
                            compressionMethod = "STORED",
                            metadata = mapOf(
                                "packageName" to app.packageName,
                                "versionName" to app.versionName,
                                "versionCode" to app.versionCode.toString(),
                                "minSdk" to app.minSdkVersion.toString(),
                                "targetSdk" to app.targetSdkVersion.toString()
                            )
                        )
                        packEntries.add(ArchiveEngine.PackEntry(entryName, sourceFile = baseApk, manifestItem = item, isStoreOnly = true))
                        manifestItems.add(item)
                        totalEstimatedSize += baseApk.length()
                    } else {
                        dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "APK base de ${app.packageName} não pôde ser lido diretamente pelo processo.")
                    }
                }

                // Split APKs
                if (options.includeSplits && app.splitSourceDirs.isNotEmpty()) {
                    for (splitPath in app.splitSourceDirs) {
                        val splitFile = File(splitPath)
                        if (splitFile.exists() && splitFile.canRead()) {
                            val entryName = "apps/${app.packageName}/splits/${splitFile.name}"
                            val item = ManifestItem(
                                id = UUID.randomUUID().toString(),
                                type = BackupItemType.APP_SPLIT_APKS,
                                name = "${app.appName} (${splitFile.name})",
                                originalPath = splitPath,
                                archiveEntryPath = entryName,
                                sizeBytes = splitFile.length(),
                                sha256Checksum = "",
                                compressionMethod = "STORED",
                                metadata = mapOf("packageName" to app.packageName, "isSplit" to "true")
                            )
                            packEntries.add(ArchiveEngine.PackEntry(entryName, sourceFile = splitFile, manifestItem = item, isStoreOnly = true))
                            manifestItems.add(item)
                            totalEstimatedSize += splitFile.length()
                        } else {
                            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Split APK $splitPath não acessível para leitura direta.")
                        }
                    }
                }
            }

            // 2. Process Files & Folders
            for (filePath in selectedFilesAndFolders) {
                val file = File(filePath)
                if (file.exists()) {
                    collectFilesRecursively(file, filter, packEntries, manifestItems) { sizeAdded ->
                        totalEstimatedSize += sizeAdded
                    }
                } else {
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Caminho de arquivo não encontrado: $filePath")
                }
            }

            // Check free space on internal storage before proceeding
            val internalRoot = android.os.Environment.getExternalStorageDirectory()
            val freeBytes = try {
                android.os.StatFs(internalRoot.absolutePath).availableBytes
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
            val requiredMargin = totalEstimatedSize + (100L * 1024 * 1024) // 100 MB margin
            if (freeBytes < requiredMargin) {
                val spaceErr = "Espaço insuficiente no armazenamento interno. Necessário: ${(requiredMargin / (1024 * 1024))} MB. Disponível: ${(freeBytes / (1024 * 1024))} MB."
                dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", spaceErr)
                send(BackupProgress("Erro", spaceErr, 1.0f, 0, 0, isFinished = true, error = spaceErr))
                stagingDir.deleteRecursively()
                return@channelFlow
            }

            // 3. Process SMS
            if (includeSms) {
                send(BackupProgress("Mensagens", "Exportando mensagens SMS...", 0.22f, 0, 100))
                val smsList = readSmsMessages()
                if (smsList.isNotEmpty()) {
                    val smsJson = gson.toJson(smsList).toByteArray(Charsets.UTF_8)
                    val entryName = "system/telephony/sms_backup.json"
                    val item = ManifestItem(
                        id = UUID.randomUUID().toString(),
                        type = BackupItemType.SMS,
                        name = "Mensagens SMS (${smsList.size})",
                        originalPath = "content://sms",
                        archiveEntryPath = entryName,
                        sizeBytes = smsJson.size.toLong(),
                        sha256Checksum = SecurityManager.computeSha256(smsJson),
                        compressionMethod = "DEFLATE"
                    )
                    packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = smsJson, manifestItem = item))
                    manifestItems.add(item)
                    totalEstimatedSize += smsJson.size
                }
            }

            // 4. Process Call Logs
            if (includeCalls) {
                send(BackupProgress("Chamadas", "Exportando histórico de chamadas...", 0.25f, 0, 100))
                val callsList = readCallLogs()
                if (callsList.isNotEmpty()) {
                    val callsJson = gson.toJson(callsList).toByteArray(Charsets.UTF_8)
                    val entryName = "system/telephony/calls_backup.json"
                    val item = ManifestItem(
                        id = UUID.randomUUID().toString(),
                        type = BackupItemType.CALL_LOG,
                        name = "Registro de Chamadas (${callsList.size})",
                        originalPath = "content://call_log/calls",
                        archiveEntryPath = entryName,
                        sizeBytes = callsJson.size.toLong(),
                        sha256Checksum = SecurityManager.computeSha256(callsJson),
                        compressionMethod = "DEFLATE"
                    )
                    packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = callsJson, manifestItem = item))
                    manifestItems.add(item)
                    totalEstimatedSize += callsJson.size
                }
            }

            // 5. Process Contacts
            if (includeContacts) {
                send(BackupProgress("Contatos", "Exportando catálogo de contatos...", 0.28f, 0, 100))
                val contactsList = readContactsList()
                if (contactsList.isNotEmpty()) {
                    val contactsJson = gson.toJson(contactsList).toByteArray(Charsets.UTF_8)
                    val entryName = "system/contacts/contacts_backup.json"
                    val item = ManifestItem(
                        id = UUID.randomUUID().toString(),
                        type = BackupItemType.CONTACTS,
                        name = "Contatos (${contactsList.size})",
                        originalPath = "content://contacts",
                        archiveEntryPath = entryName,
                        sizeBytes = contactsJson.size.toLong(),
                        sha256Checksum = SecurityManager.computeSha256(contactsJson),
                        compressionMethod = "DEFLATE"
                    )
                    packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = contactsJson, manifestItem = item))
                    manifestItems.add(item)
                    totalEstimatedSize += contactsJson.size
                }
            }

            // 6. Process DNS
            if (includeDns) {
                send(BackupProgress("DNS & Rede", "Capturando configuração de DNS...", 0.32f, 0, 100))
                val dnsConfig = dnsManager.getDnsConfiguration()
                val dnsJson = gson.toJson(dnsConfig).toByteArray(Charsets.UTF_8)
                val entryName = "system/dns_network.json"
                val item = ManifestItem(
                    id = UUID.randomUUID().toString(),
                    type = BackupItemType.DNS_CONFIG,
                    name = "Configuração DNS (${dnsConfig.mode})",
                    originalPath = "Settings.Global.private_dns",
                    archiveEntryPath = entryName,
                    sizeBytes = dnsJson.size.toLong(),
                    sha256Checksum = SecurityManager.computeSha256(dnsJson),
                    compressionMethod = "DEFLATE",
                    metadata = mapOf("mode" to dnsConfig.mode, "host" to (dnsConfig.hostname ?: ""))
                )
                packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = dnsJson, manifestItem = item))
                manifestItems.add(item)
                totalEstimatedSize += dnsJson.size
            }

            // 7. Process Wallpaper
            if (includeWallpaper) {
                send(BackupProgress("Sistema", "Capturando Papel de Parede...", 0.35f, 0, 100))
                val wallpaperBytes = captureWallpaperBytes()
                if (wallpaperBytes != null) {
                    val entryName = "system/wallpaper.png"
                    val item = ManifestItem(
                        id = UUID.randomUUID().toString(),
                        type = BackupItemType.WALLPAPER,
                        name = "Papel de Parede Atual",
                        originalPath = "WallpaperManager",
                        archiveEntryPath = entryName,
                        sizeBytes = wallpaperBytes.size.toLong(),
                        sha256Checksum = SecurityManager.computeSha256(wallpaperBytes),
                        compressionMethod = "STORED"
                    )
                    packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = wallpaperBytes, manifestItem = item, isStoreOnly = true))
                    manifestItems.add(item)
                    totalEstimatedSize += wallpaperBytes.size
                }
            }

            // 8. Assemble Manifest
            val manifest = BackupManifest(
                formatIdentifier = "SwiftVault",
                manifestVersion = 2,
                vaultVersion = "2.0.0",
                backupId = backupId,
                title = backupTitle,
                deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                androidSdk = android.os.Build.VERSION.SDK_INT,
                androidRelease = android.os.Build.VERSION.RELEASE,
                rootManager = CapabilityManager(context).getInstalledRootManagerPackage(),
                createdAt = System.currentTimeMillis(),
                isEncrypted = !encryptionPassword.isNullOrEmpty(),
                encryptionAlgorithm = if (!encryptionPassword.isNullOrEmpty()) "AES-256-GCM-CHUNKED" else "NONE",
                compressionAlgorithm = "ZIP-MIXED",
                totalSizeBytes = totalEstimatedSize,
                items = manifestItems,
                sha256PayloadHash = ""
            )

            // 9. Stream Archive & Compress directly into Staging Zip
            send(BackupProgress("Comprimindo", "Empacotando e gravando container .zip...", 0.40f, 0, totalEstimatedSize))

            ArchiveEngine.createZipContainer(
                outputFile = stagingFile,
                manifest = manifest,
                entries = packEntries,
                encryptionPassword = encryptionPassword
            ) { written, total ->
                val ratio = if (total > 0) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                val percent = 0.40f + (ratio * 0.45f) // 40% -> 85%
                send(BackupProgress("Gravando", "Processando arquivos... (${(written / (1024 * 1024))} MB)", percent, written, total))
            }

            // 10. RIGOROUS INTEGRITY VALIDATION ON STAGED FILE
            send(BackupProgress("Verificando", "Executando verificação de integridade SHA-256 do arquivo...", 0.88f, 0, 100))
            val stagingSha256 = FileInputStream(stagingFile).use { SecurityManager.computeSha256(it) }

            // Test readability of the container manifest
            val readBackManifest = try {
                ArchiveEngine.readManifest(stagingFile)
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", "Falha na verificação de integridade do container: ${e.message}")
                send(BackupProgress("Erro", "Container corrompido durante a gravação: ${e.message}", 1.0f, 0, 0, isFinished = true, error = e.message))
                return@channelFlow
            }

            if (readBackManifest.items.size != manifestItems.size) {
                stagingDir.deleteRecursively()
                val countErr = "Inconsistência no número de itens validados: esperado ${manifestItems.size}, encontrado ${readBackManifest.items.size}"
                dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", countErr)
                send(BackupProgress("Erro", countErr, 1.0f, 0, 0, isFinished = true, error = countErr))
                return@channelFlow
            }

            // 11. Create Destination Subfolder on Internal Storage Root ONLY after validation
            send(BackupProgress("Finalizando", "Criando pasta e movendo container validado para o armazenamento...", 0.95f, 0, 100))

            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val deviceSanitized = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}".replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val subfolderName = "${dateFormat.format(Date())}_$deviceSanitized"
            val finalVaultDir = destinationDir ?: File(getOrCreateDefaultVaultDir(), subfolderName)

            if (!finalVaultDir.exists()) {
                val created = finalVaultDir.mkdirs()
                if (!created && com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                    com.topjohnwu.superuser.Shell.cmd("mkdir -p \"${finalVaultDir.absolutePath}\"").exec()
                }
            }

            if (!finalVaultDir.exists()) {
                stagingDir.deleteRecursively()
                val dirErr = "Não foi possível criar o diretório final na raiz: ${finalVaultDir.absolutePath}"
                dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", dirErr)
                send(BackupProgress("Erro", dirErr, 1.0f, 0, 0, isFinished = true, error = dirErr))
                return@channelFlow
            }

            val finalOutputFile = File(finalVaultDir, "$backupId.zip")
            stagingFile.copyTo(finalOutputFile, overwrite = true)

            // Re-verify SHA-256 of copied file to guarantee exact disk copy
            val copiedSha256 = FileInputStream(finalOutputFile).use { SecurityManager.computeSha256(it) }
            if (copiedSha256 != stagingSha256) {
                finalOutputFile.delete()
                stagingDir.deleteRecursively()
                val copyErr = "Divergência de hash SHA-256 após cópia para ${finalOutputFile.absolutePath}."
                dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", copyErr)
                send(BackupProgress("Erro", copyErr, 1.0f, 0, 0, isFinished = true, error = copyErr))
                return@channelFlow
            }

            // Clean staging
            stagingDir.deleteRecursively()

            // 12. Persist Record to Database
            val record = BackupRecord(
                id = backupId,
                title = backupTitle,
                filePath = finalOutputFile.absolutePath,
                sizeBytes = finalOutputFile.length(),
                checksumSha256 = copiedSha256,
                isEncrypted = !encryptionPassword.isNullOrEmpty(),
                timestamp = System.currentTimeMillis(),
                itemCount = manifestItems.size,
                status = BackupStatus.COMPLETED,
                includedAppsCount = selectedApps.size,
                includedFilesCount = manifestItems.count { it.type == BackupItemType.FILE },
                includedDns = includeDns,
                destination = finalVaultDir.name
            )
            dbHelper.insertBackupRecord(record)
            dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Backup concluído com sucesso e validado: ${finalOutputFile.name} (${finalOutputFile.length() / (1024 * 1024)} MB)")

            send(BackupProgress("Concluído", "Backup verificado com sucesso! (${finalOutputFile.name})", 1.0f, finalOutputFile.length(), finalOutputFile.length(), isFinished = true))

        } catch (e: CancellationException) {
            stagingDir.deleteRecursively()
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Operação de backup cancelada: ${e.message}")
            throw e
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", "Erro crítico durante backup: ${e.message}")
            send(BackupProgress("Erro", e.message ?: "Falha ao gravar container de backup.", 1.0f, 0, 0, isFinished = true, error = e.message))
        }
    }.flowOn(Dispatchers.IO)

    private fun collectFilesRecursively(
        target: File,
        filter: BackupFilter?,
        packEntries: MutableList<ArchiveEngine.PackEntry>,
        manifestItems: MutableList<ManifestItem>,
        onSizeAdded: (Long) -> Unit
    ) {
        if (target.isFile) {
            if (matchesFilter(target, filter)) {
                val entryName = "files/" + target.name
                val item = ManifestItem(
                    id = UUID.randomUUID().toString(),
                    type = BackupItemType.FILE,
                    name = target.name,
                    originalPath = target.absolutePath,
                    archiveEntryPath = entryName,
                    sizeBytes = target.length(),
                    sha256Checksum = ""
                )
                packEntries.add(ArchiveEngine.PackEntry(entryName, sourceFile = target, manifestItem = item))
                manifestItems.add(item)
                onSizeAdded(target.length())
            }
        } else if (target.isDirectory) {
            val children = target.listFiles() ?: return
            for (child in children) {
                collectFilesRecursively(child, filter, packEntries, manifestItems, onSizeAdded)
            }
        }
    }

    private fun matchesFilter(file: File, filter: BackupFilter?): Boolean {
        if (filter == null) return true

        // Exclusions
        val fileNameLower = file.name.lowercase(Locale.ROOT)
        for (pattern in filter.excludedPatterns) {
            val cleanPattern = pattern.replace("*", "").lowercase(Locale.ROOT)
            if (cleanPattern.isNotEmpty() && fileNameLower.contains(cleanPattern)) {
                return false
            }
        }

        // Min Size
        if (filter.minSizeBytes > 0 && file.length() < filter.minSizeBytes) {
            return false
        }

        // Max Age Days
        if (filter.maxAgeDays > 0) {
            val ageMs = System.currentTimeMillis() - file.lastModified()
            val maxAgeMs = filter.maxAgeDays * 24L * 60 * 60 * 1000
            if (ageMs > maxAgeMs) return false
        }

        // Extensions
        if (filter.includedExtensions.isNotEmpty()) {
            val ext = "." + file.extension.lowercase(Locale.ROOT)
            if (!filter.includedExtensions.any { it.equals(ext, ignoreCase = true) }) {
                return false
            }
        }

        return true
    }

    private suspend fun readSmsMessages(): List<SmsRecord> {
        val list = mutableListOf<SmsRecord>()
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT 5000"
            )
            cursor?.use {
                val addrCol = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeCol = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readCol = it.getColumnIndexOrThrow(Telephony.Sms.READ)

                while (it.moveToNext()) {
                    list.add(
                        SmsRecord(
                            address = it.getString(addrCol) ?: "",
                            body = it.getString(bodyCol) ?: "",
                            date = it.getLong(dateCol),
                            type = it.getInt(typeCol),
                            read = it.getInt(readCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Não foi possível ler mensagens SMS: ${e.message}")
        }
        return list
    }

    private suspend fun readCallLogs(): List<CallRecord> {
        val list = mutableListOf<CallRecord>()
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT 5000"
            )
            cursor?.use {
                val numCol = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateCol = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durCol = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val typeCol = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                while (it.moveToNext()) {
                    list.add(
                        CallRecord(
                            number = it.getString(numCol) ?: "",
                            date = it.getLong(dateCol),
                            duration = it.getLong(durCol),
                            type = it.getInt(typeCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Não foi possível ler histórico de chamadas: ${e.message}")
        }
        return list
    }

    private fun captureWallpaperBytes(): ByteArray? {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val drawable = wallpaperManager.drawable ?: return null
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                IconCacheManager.getInstance(context).drawableToBitmap(drawable)
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    fun getContactsCount(): Int {
        return try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts._ID),
                null, null, null
            )
            cursor?.use { it.count } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    suspend fun readContactsList(): List<ContactRecord> {
        val list = mutableListOf<ContactRecord>()
        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.Contacts._ID,
                    android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                    android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER
                ),
                null, null, null
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex(android.provider.ContactsContract.Contacts._ID)
                val nameCol = c.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneCol = c.getColumnIndex(android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER)

                while (c.moveToNext()) {
                    val id = if (idCol != -1) c.getString(idCol) ?: "" else ""
                    val name = if (nameCol != -1) c.getString(nameCol) ?: "Sem Nome" else "Sem Nome"
                    val hasPhone = if (hasPhoneCol != -1) c.getInt(hasPhoneCol) > 0 else false

                    val phones = mutableListOf<String>()
                    if (hasPhone && id.isNotEmpty()) {
                        val pCursor = resolver.query(
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        pCursor?.use { pc ->
                            val numCol = pc.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (pc.moveToNext()) {
                                if (numCol != -1) {
                                    pc.getString(numCol)?.let { phones.add(it) }
                                }
                            }
                        }
                    }

                    val emails = mutableListOf<String>()
                    if (id.isNotEmpty()) {
                        val eCursor = resolver.query(
                            android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS),
                            "${android.provider.ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        eCursor?.use { ec ->
                            val emailCol = ec.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS)
                            while (ec.moveToNext()) {
                                if (emailCol != -1) {
                                    ec.getString(emailCol)?.let { emails.add(it) }
                                }
                            }
                        }
                    }

                    list.add(ContactRecord(id = id, displayName = name, phoneNumbers = phones, emails = emails))
                }
            }
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Acesso aos contatos restrito ou erro: ${e.message}")
        }
        return list
    }

    fun getOrCreateDefaultVaultDir(): File {
        val rootDir = File(android.os.Environment.getExternalStorageDirectory(), "SwiftVaultBackups")
        if (!rootDir.exists()) {
            val created = rootDir.mkdirs()
            if (!created && com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                com.topjohnwu.superuser.Shell.cmd("mkdir -p \"${rootDir.absolutePath}\"").exec()
            }
        }
        return rootDir
    }
}
