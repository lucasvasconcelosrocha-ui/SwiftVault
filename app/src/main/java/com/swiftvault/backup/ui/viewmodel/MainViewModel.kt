package com.swiftvault.backup.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swiftvault.backup.cloud.CloudStorageManager
import com.swiftvault.backup.data.database.AppDatabaseHelper
import com.swiftvault.backup.data.model.*
import com.swiftvault.backup.engine.*
import com.swiftvault.backup.service.BackupForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class GoogleDriveState {
    NOT_CONNECTED,   // ⚪ Não conectado
    CONNECTING,      // 🔵 Conectando...
    CONNECTED,       // 🟢 Conectado
    SYNCING,         // 🔵 Sincronizando...
    SESSION_EXPIRED, // 🟠 Reconectar
    CONNECTION_ERROR // 🔴 Erro
}

sealed class DriveQuotaState {
    object Idle : DriveQuotaState()
    object Loading : DriveQuotaState()
    data class Loaded(
        val totalBytes: Long,
        val usedBytes: Long,
        val availableBytes: Long,
        val userEmail: String?,
        val displayName: String?
    ) : DriveQuotaState()
    data class Error(val message: String, val technicalCode: String? = null) : DriveQuotaState()
}

sealed class RemoteBackupsState {
    object Idle : RemoteBackupsState()
    object Loading : RemoteBackupsState()
    data class Loaded(val backups: List<com.swiftvault.backup.cloud.gdrive.DriveFileMetadata>) : RemoteBackupsState()
    data class Error(val message: String, val technicalCode: String? = null) : RemoteBackupsState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val iconCache = IconCacheManager.getInstance(context)
    private val dnsManager = DnsManager(context)
    private val capabilityManager = CapabilityManager(context)
    private val backupEngine = BackupEngine(context)
    private val restoreEngine = RestoreEngine(context)
    val cloudManager = CloudStorageManager(context)

    private val prefs = context.getSharedPreferences("swiftvault_prefs", Context.MODE_PRIVATE)

    // Current Theme Accent
    private val _themeAccent = MutableStateFlow(
        ThemeAccent.values().getOrNull(prefs.getInt("theme_accent", 0)) ?: ThemeAccent.BLUE
    )
    val themeAccent: StateFlow<ThemeAccent> = _themeAccent.asStateFlow()

    // Apps list
    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _selectedAppForDetail = MutableStateFlow<AppInfo?>(null)
    val selectedAppForDetail: StateFlow<AppInfo?> = _selectedAppForDetail.asStateFlow()

    // Backups history
    private val _backupRecords = MutableStateFlow<List<BackupRecord>>(emptyList())
    val backupRecords: StateFlow<List<BackupRecord>> = _backupRecords.asStateFlow()

    // Cloud Accounts & Queue
    private val _cloudAccounts = MutableStateFlow<List<CloudAccount>>(emptyList())
    val cloudAccounts: StateFlow<List<CloudAccount>> = _cloudAccounts.asStateFlow()

    private val _driveQuota = MutableStateFlow<com.swiftvault.backup.cloud.gdrive.DriveStorageQuota?>(null)
    val driveQuota: StateFlow<com.swiftvault.backup.cloud.gdrive.DriveStorageQuota?> = _driveQuota.asStateFlow()

    private val _syncQueue = MutableStateFlow<List<CloudSyncQueueItem>>(emptyList())
    val syncQueue: StateFlow<List<CloudSyncQueueItem>> = _syncQueue.asStateFlow()

    // Google Drive direct provider access
    val gdriveProvider = cloudManager.googleDriveProvider

    private val _remoteDriveBackups = MutableStateFlow<List<com.swiftvault.backup.cloud.gdrive.DriveFileMetadata>>(emptyList())
    val remoteDriveBackups: StateFlow<List<com.swiftvault.backup.cloud.gdrive.DriveFileMetadata>> = _remoteDriveBackups.asStateFlow()

    private val networkDetailsManager = NetworkDetailsManager(context)

    private val _wifiDetails = MutableStateFlow(networkDetailsManager.getWifiDetails())
    val wifiDetails: StateFlow<WifiConnectionDetails> = _wifiDetails.asStateFlow()

    private val _dataUsageStats = MutableStateFlow(networkDetailsManager.getDataUsageStats())
    val dataUsageStats: StateFlow<NetworkDataUsageStats> = _dataUsageStats.asStateFlow()

    private val _syncSettings = MutableStateFlow(gdriveProvider.syncManager.getSettings())
    val syncSettings: StateFlow<com.swiftvault.backup.cloud.gdrive.AutoSyncSettings> = _syncSettings.asStateFlow()

    // Diagnostic Runner State
    private val _diagnosticResults = MutableStateFlow<List<CloudDiagnosticResult>>(emptyList())
    val diagnosticResults: StateFlow<List<CloudDiagnosticResult>> = _diagnosticResults.asStateFlow()

    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()

    // Backup Progress Flow
    private val _backupProgress = MutableStateFlow<BackupProgress?>(null)
    val backupProgress: StateFlow<BackupProgress?> = _backupProgress.asStateFlow()

    // Restore Progress Flow
    private val _restoreProgress = MutableStateFlow<RestoreProgress?>(null)
    val restoreProgress: StateFlow<RestoreProgress?> = _restoreProgress.asStateFlow()

    // Device Stats
    private val _deviceStats = MutableStateFlow(generateDeviceStats())
    val deviceStats: StateFlow<DeviceStats> = _deviceStats.asStateFlow()

    // Active DNS Config
    private val _dnsConfig = MutableStateFlow(dnsManager.getDnsConfiguration())
    val dnsConfig: StateFlow<DnsConfig> = _dnsConfig.asStateFlow()

    // System Logs
    private val _systemLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val systemLogs: StateFlow<List<LogEntry>> = _systemLogs.asStateFlow()

    // Filters
    private val _backupFilters = MutableStateFlow<List<BackupFilter>>(emptyList())
    val backupFilters: StateFlow<List<BackupFilter>> = _backupFilters.asStateFlow()

    // Conflicts
    private val _detectedConflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val detectedConflicts: StateFlow<List<SyncConflict>> = _detectedConflicts.asStateFlow()

    // Google Drive Enhanced States
    private val _googleDriveState = MutableStateFlow(
        if (gdriveProvider.oAuthManager.isConnected()) {
            if (gdriveProvider.oAuthManager.isSessionExpired()) GoogleDriveState.SESSION_EXPIRED else GoogleDriveState.CONNECTED
        } else GoogleDriveState.NOT_CONNECTED
    )
    val googleDriveState: StateFlow<GoogleDriveState> = _googleDriveState.asStateFlow()

    private val _driveQuotaState = MutableStateFlow<DriveQuotaState>(DriveQuotaState.Idle)
    val driveQuotaState: StateFlow<DriveQuotaState> = _driveQuotaState.asStateFlow()

    private val _remoteBackupsState = MutableStateFlow<RemoteBackupsState>(RemoteBackupsState.Idle)
    val remoteBackupsState: StateFlow<RemoteBackupsState> = _remoteBackupsState.asStateFlow()

    // Root granular capability state
    private val _rootAccessStatus = MutableStateFlow(CapabilityManager.RootStatus.UNAUTHORIZED)
    val rootAccessStatus: StateFlow<CapabilityManager.RootStatus> = _rootAccessStatus.asStateFlow()

    // Capability state
    private val _activeMode = MutableStateFlow(CapabilityManager.ExecutionMode.STANDARD)
    val activeMode: StateFlow<CapabilityManager.ExecutionMode> = _activeMode.asStateFlow()

    init {
        loadData()
        observeDatabaseChanges()
    }

    fun setThemeAccent(accent: ThemeAccent) {
        _themeAccent.value = accent
        prefs.edit().putInt("theme_accent", accent.ordinal).apply()
    }

    fun selectAppForDetail(app: AppInfo) {
        _selectedAppForDetail.value = app
    }

    fun clearSelectedApp() {
        _selectedAppForDetail.value = null
    }

    private fun observeDatabaseChanges() {
        viewModelScope.launch {
            dbHelper.dbChanges.collect { table ->
                when (table) {
                    "backup_records" -> loadBackupRecords()
                    "cloud_accounts" -> loadCloudAccounts()
                    "sync_queue" -> loadSyncQueue()
                    "system_logs" -> loadLogs()
                    "backup_filters" -> loadFilters()
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            refreshRootStatus()
            refreshDns()
            loadInstalledApps()
            loadBackupRecords()
            loadCloudAccounts()
            loadSyncQueue()
            loadLogs()
            loadFilters()
            _deviceStats.value = generateDeviceStats()
            if (gdriveProvider.oAuthManager.isConnected()) {
                refreshDriveAccountInfo()
                fetchRemoteDriveBackups()
            } else {
                _googleDriveState.value = if (gdriveProvider.oAuthManager.isSessionExpired()) {
                    GoogleDriveState.SESSION_EXPIRED
                } else {
                    GoogleDriveState.NOT_CONNECTED
                }
                _driveQuotaState.value = DriveQuotaState.Idle
                _remoteBackupsState.value = RemoteBackupsState.Idle
            }
        }
    }

    fun refreshDashboard() = loadData()

    fun refreshBackups() {
        viewModelScope.launch {
            loadBackupRecords()
        }
    }

    fun refreshCloudData() {
        viewModelScope.launch {
            loadCloudAccounts()
            loadSyncQueue()
            refreshDriveAccountInfo()
            fetchRemoteDriveBackups()
        }
    }

    fun refreshRootStatus() {
        viewModelScope.launch {
            val status = capabilityManager.checkRootAccess()
            _rootAccessStatus.value = status
            _activeMode.value = if (status == CapabilityManager.RootStatus.AUTHORIZED) {
                CapabilityManager.ExecutionMode.ROOT
            } else if (capabilityManager.isShizukuRunning()) {
                CapabilityManager.ExecutionMode.SHIZUKU
            } else {
                CapabilityManager.ExecutionMode.STANDARD
            }
        }
    }

    fun requestRootPermission() {
        viewModelScope.launch {
            capabilityManager.requestRootAccess()
            refreshRootStatus()
        }
    }

    fun refreshDns() {
        _dnsConfig.value = dnsManager.getDnsConfiguration()
    }

    fun copyDnsHost() {
        _dnsConfig.value.hostname?.let { dnsManager.copyHostToClipboard(it) }
    }

    fun getOpenDnsSettingsIntent() = dnsManager.createOpenDnsSettingsIntent()

    private suspend fun loadInstalledApps() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val appList = mutableListOf<AppInfo>()

        val existingBackups = dbHelper.getAllBackupRecords()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            val appName = pm.getApplicationLabel(appInfo).toString()
            val baseApk = File(appInfo.sourceDir)
            val baseSize = if (baseApk.exists()) baseApk.length() else 0L

            val splitDirs = appInfo.splitSourceDirs?.toList() ?: emptyList()
            val splitsSize = splitDirs.sumOf { File(it).length() }
            val totalSize = baseSize + splitsSize

            val matchingBackup = existingBackups.find { it.title.contains(appName, ignoreCase = true) }

            val item = AppInfo(
                packageName = pkg.packageName,
                appName = appName,
                versionName = pkg.versionName ?: "1.0",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode.toLong(),
                minSdkVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 21,
                targetSdkVersion = appInfo.targetSdkVersion,
                sourceDir = appInfo.sourceDir,
                splitSourceDirs = splitDirs,
                dataDir = appInfo.dataDir ?: "",
                isSystemApp = isSystem,
                sizeBytes = totalSize,
                lastInstallTime = pkg.firstInstallTime,
                lastUpdateTime = pkg.lastUpdateTime,
                lastBackupDate = matchingBackup?.let { "Disponível" },
                hasBackup = matchingBackup != null,
                backupSize = matchingBackup?.sizeBytes ?: 0L,
                architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            )
            appList.add(item)
        }

        // Sort: user apps first, then by name
        _installedApps.value = appList.sortedWith(
            compareBy<AppInfo> { it.isSystemApp }.thenBy { it.appName.lowercase() }
        )
    }

    private suspend fun loadBackupRecords() = withContext(Dispatchers.IO) {
        _backupRecords.value = dbHelper.getAllBackupRecords()
    }

    private suspend fun loadCloudAccounts() = withContext(Dispatchers.IO) {
        _cloudAccounts.value = dbHelper.getAllCloudAccounts()
    }

    private suspend fun loadSyncQueue() = withContext(Dispatchers.IO) {
        _syncQueue.value = dbHelper.getAllQueueItems()
    }

    private suspend fun loadLogs() = withContext(Dispatchers.IO) {
        _systemLogs.value = dbHelper.getRecentLogs()
    }

    private suspend fun loadFilters() = withContext(Dispatchers.IO) {
        _backupFilters.value = dbHelper.getAllFilters()
    }

    // Diagnostic Runner
    fun runCloudDiagnostic(providerType: CloudProviderType) {
        viewModelScope.launch {
            _isDiagnosing.value = true
            _diagnosticResults.value = emptyList()

            cloudManager.runDiagnostic(providerType).collect { result ->
                _diagnosticResults.value = _diagnosticResults.value + result
            }
            _isDiagnosing.value = false
        }
    }

    // Queue Actions
    fun pauseQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.updateQueueItemStatus(id, QueueStatus.PAUSED)
        }
    }

    fun resumeQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.updateQueueItemStatus(id, QueueStatus.PENDING)
            val item = _syncQueue.value.find { it.id == id }
            if (item != null) {
                cloudManager.processQueueItem(item) { _, _ -> }
            }
        }
    }

    fun prioritizeQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.prioritizeQueueItem(id)
        }
    }

    fun removeQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.removeQueueItem(id)
        }
    }

    fun retryQueueItem(id: String) {
        viewModelScope.launch {
            dbHelper.updateQueueItemStatus(id, QueueStatus.PENDING)
            val item = _syncQueue.value.find { it.id == id }
            if (item != null) {
                cloudManager.processQueueItem(item) { _, _ -> }
            }
        }
    }

    fun refreshNetworkAndWifi() {
        _wifiDetails.value = networkDetailsManager.getWifiDetails()
        _dataUsageStats.value = networkDetailsManager.getDataUsageStats()
        _dnsConfig.value = dnsManager.getDnsConfiguration()
    }

    fun verifyDriveBackupsStatus() {
        viewModelScope.launch {
            if (gdriveProvider.oAuthManager.isConnected()) {
                val updated = gdriveProvider.cloudBackupRepository.verifyRemoteStatusForLocalBackups()
                _backupRecords.value = updated
            }
        }
    }

    // Backup Orchestration
    fun launchBackup(
        title: String,
        selectedApps: List<Pair<AppInfo, AppSelectionOptions>>,
        selectedFiles: List<String>,
        includeSms: Boolean,
        includeCalls: Boolean,
        includeContacts: Boolean = false,
        includeDns: Boolean,
        includeWallpaper: Boolean,
        encryptionPassword: String? = null,
        targetCloudProvider: CloudProviderType? = null
    ) {
        viewModelScope.launch {
            try {
                BackupForegroundService.startService(context, title)

                backupEngine.performSelectiveBackup(
                    backupTitle = title,
                    selectedApps = selectedApps,
                    selectedFilesAndFolders = selectedFiles,
                    includeSms = includeSms,
                    includeCalls = includeCalls,
                    includeContacts = includeContacts,
                    includeDns = includeDns,
                    includeWallpaper = includeWallpaper,
                    encryptionPassword = encryptionPassword
                ).collect { progress ->
                    _backupProgress.value = progress

                    if (progress.isFinished) {
                        BackupForegroundService.stopService(context)
                        loadBackupRecords()
                        _deviceStats.value = generateDeviceStats()

                        // If Google Drive target requested, enqueue/start real upload
                        if (targetCloudProvider != null && progress.error == null) {
                            val latest = dbHelper.getAllBackupRecords().firstOrNull()
                            if (latest != null) {
                                uploadRecordToGoogleDrive(latest)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                BackupForegroundService.stopService(context)
                throw e
            } catch (e: Exception) {
                BackupForegroundService.stopService(context)
                dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", "Erro ao executar backup: ${e.message}")
                _backupProgress.value = BackupProgress(
                    stage = "Erro",
                    currentItemName = e.message ?: "Falha ao executar backup.",
                    progressPercent = 1.0f,
                    bytesProcessed = 0,
                    totalBytesEstimated = 0,
                    isFinished = true,
                    error = e.message
                )
            }
        }
    }

    fun getContactsCount(): Int = backupEngine.getContactsCount()

    // Restore Orchestration
    fun launchRestore(
        record: BackupRecord,
        selectedItemIds: Set<String>,
        password: String? = null
    ) {
        viewModelScope.launch {
            try {
                val file = File(record.filePath)
                if (!file.exists()) {
                    _restoreProgress.value = RestoreProgress("Erro", "Arquivo de backup não encontrado.", 1.0f, true, "Arquivo não encontrado")
                    return@launch
                }

                val manifest = try {
                    restoreEngine.inspectBackup(file)
                } catch (e: Exception) {
                    _restoreProgress.value = RestoreProgress("Erro", "Falha ao inspecionar container: ${e.message}", 1.0f, true, e.message)
                    return@launch
                }

                val targetItemIds = if (selectedItemIds.isEmpty()) manifest.items.map { it.id }.toSet() else selectedItemIds

                restoreEngine.performSelectiveRestore(
                    containerFile = file,
                    manifest = manifest,
                    selectedItemIds = targetItemIds,
                    password = password
                ).collect { progress ->
                    _restoreProgress.value = progress
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _restoreProgress.value = RestoreProgress("Erro", "Erro ao restaurar: ${e.message}", 1.0f, true, e.message)
            }
        }
    }

    fun dismissProgress() {
        _backupProgress.value = null
        _restoreProgress.value = null
    }

    fun checkSyncConflicts(providerType: CloudProviderType) {
        viewModelScope.launch {
            val conflicts = cloudManager.detectConflicts(providerType, _backupRecords.value)
            _detectedConflicts.value = conflicts
        }
    }

    fun resolveConflict(conflict: SyncConflict, resolution: ConflictResolution, providerType: CloudProviderType) {
        viewModelScope.launch {
            cloudManager.resolveConflict(conflict, resolution, providerType)
            _detectedConflicts.value = _detectedConflicts.value.filter { it.fileId != conflict.fileId }
        }
    }

    // Google Drive Specific Operations
    fun fetchRemoteDriveBackups() {
        viewModelScope.launch {
            if (!gdriveProvider.oAuthManager.isConnected()) {
                _remoteBackupsState.value = RemoteBackupsState.Idle
                _remoteDriveBackups.value = emptyList()
                return@launch
            }
            if (gdriveProvider.oAuthManager.isSessionExpired()) {
                _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                _remoteBackupsState.value = RemoteBackupsState.Error("A sessão do Google Drive expirou.", "HTTP 401")
                return@launch
            }

            _remoteBackupsState.value = RemoteBackupsState.Loading
            val res = gdriveProvider.cloudBackupRepository.fetchRemoteBackups()
            if (res.isSuccess) {
                val list = res.getOrThrow()
                _remoteDriveBackups.value = list
                _remoteBackupsState.value = RemoteBackupsState.Loaded(list)
                if (_googleDriveState.value != GoogleDriveState.SYNCING) {
                    _googleDriveState.value = GoogleDriveState.CONNECTED
                }
            } else {
                val ex = res.exceptionOrNull()
                val is401 = ex is com.swiftvault.backup.cloud.gdrive.GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
                if (is401) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    _remoteBackupsState.value = RemoteBackupsState.Error("A sessão do Google Drive expirou. É necessário reconectar sua conta.", "HTTP 401")
                } else {
                    _remoteBackupsState.value = RemoteBackupsState.Error(ex?.message ?: "Não foi possível consultar os backups no Google Drive.")
                }
            }
        }
    }

    fun refreshDriveAccountInfo() {
        viewModelScope.launch {
            if (!gdriveProvider.oAuthManager.isConnected()) {
                _googleDriveState.value = GoogleDriveState.NOT_CONNECTED
                _driveQuotaState.value = DriveQuotaState.Idle
                _driveQuota.value = null
                return@launch
            }
            if (gdriveProvider.oAuthManager.isSessionExpired()) {
                _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                _driveQuotaState.value = DriveQuotaState.Error("A sessão do Google Drive expirou.", "HTTP 401")
                return@launch
            }

            _driveQuotaState.value = DriveQuotaState.Loading
            val res = gdriveProvider.driveFileManager.getStorageQuota()
            if (res.isSuccess) {
                val quota = res.getOrThrow()
                _driveQuota.value = quota
                if (_googleDriveState.value != GoogleDriveState.SYNCING) {
                    _googleDriveState.value = GoogleDriveState.CONNECTED
                }
                _driveQuotaState.value = DriveQuotaState.Loaded(
                    totalBytes = quota.limitBytes,
                    usedBytes = quota.usageBytes,
                    availableBytes = quota.availableBytes,
                    userEmail = quota.userEmail,
                    displayName = quota.displayName
                )
                dbHelper.updateCloudAccountQuota(
                    CloudProviderType.GOOGLE_DRIVE.name,
                    quota.usageBytes,
                    quota.limitBytes
                )
            } else {
                val ex = res.exceptionOrNull()
                val is401 = ex is com.swiftvault.backup.cloud.gdrive.GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
                if (is401) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    _driveQuotaState.value = DriveQuotaState.Error("A sessão do Google Drive expirou. É necessário reconectar sua conta.", "HTTP 401")
                } else {
                    _driveQuotaState.value = DriveQuotaState.Error(ex?.message ?: "Indisponível")
                    _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                }
            }
        }
    }

    private val isDriveRefreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun refreshAllGoogleDriveData(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (!gdriveProvider.oAuthManager.isConnected()) {
                _googleDriveState.value = GoogleDriveState.NOT_CONNECTED
                _driveQuotaState.value = DriveQuotaState.Idle
                _remoteBackupsState.value = RemoteBackupsState.Idle
                onComplete?.invoke()
                return@launch
            }
            if (isDriveRefreshing.compareAndSet(false, true)) {
                try {
                    if (gdriveProvider.oAuthManager.isSessionExpired()) {
                        _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    }
                    refreshDriveAccountInfo()
                    fetchRemoteDriveBackups()
                    loadCloudAccounts()
                } finally {
                    isDriveRefreshing.set(false)
                    onComplete?.invoke()
                }
            } else {
                onComplete?.invoke()
            }
        }
    }

    fun connectGoogleAccount(email: String, activity: android.app.Activity? = null, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            _googleDriveState.value = GoogleDriveState.CONNECTING
            val authRes = gdriveProvider.oAuthManager.authenticateWithAccountName(email, activity)
            if (authRes.isSuccess) {
                // Pre-flight check: Verify real token and permissions with Drive API (about.get)
                val quotaRes = gdriveProvider.driveFileManager.getStorageQuota()
                if (quotaRes.isSuccess) {
                    val quota = quotaRes.getOrThrow()
                    _googleDriveState.value = GoogleDriveState.CONNECTED
                    _driveQuota.value = quota
                    _driveQuotaState.value = DriveQuotaState.Loaded(
                        totalBytes = quota.limitBytes,
                        usedBytes = quota.usageBytes,
                        availableBytes = quota.availableBytes,
                        userEmail = quota.userEmail ?: email,
                        displayName = quota.displayName
                    )
                    dbHelper.updateCloudAccount(
                        CloudAccount(
                            providerType = CloudProviderType.GOOGLE_DRIVE,
                            accountName = quota.userEmail ?: email,
                            isConnected = true,
                            quotaUsedBytes = quota.usageBytes,
                            quotaTotalBytes = quota.limitBytes,
                            lastSyncTimestamp = System.currentTimeMillis()
                        )
                    )
                    loadCloudAccounts()
                    fetchRemoteDriveBackups()
                    onResult(authRes)
                } else {
                    val ex = quotaRes.exceptionOrNull()
                    val is401 = ex is com.swiftvault.backup.cloud.gdrive.GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
                    if (is401) {
                        _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                        _driveQuotaState.value = DriveQuotaState.Error("Acesso à Google Drive API não autorizado (HTTP 401). Reconecte sua conta.", "HTTP 401")
                    } else {
                        _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                        _driveQuotaState.value = DriveQuotaState.Error(ex?.message ?: "Falha ao validar chamada da Google Drive API")
                    }
                    onResult(Result.failure(ex ?: IllegalStateException("Falha ao validar chamada da Google Drive API")))
                }
            } else {
                val ex = authRes.exceptionOrNull()
                if (ex is com.swiftvault.backup.cloud.gdrive.GoogleCloudOAuthConfigurationException) {
                    _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                    _driveQuotaState.value = DriveQuotaState.Error(
                        message = ex.message ?: "Cliente OAuth não registrado no Google Cloud Console",
                        technicalCode = "UnregisteredOnApiConsole"
                    )
                } else if (ex is com.swiftvault.backup.cloud.gdrive.GoogleDriveSessionExpiredException) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    _driveQuotaState.value = DriveQuotaState.Error(ex.message ?: "Sessão expirada", "HTTP 401")
                } else if (ex !is com.swiftvault.backup.cloud.gdrive.AuthIntentRequiredException) {
                    _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                    _driveQuotaState.value = DriveQuotaState.Error(ex?.message ?: "Erro de conexão")
                }
                onResult(authRes)
            }
        }
    }

    fun refreshGoogleAuth(onResult: ((Result<String>) -> Unit)? = null) {
        viewModelScope.launch {
            _googleDriveState.value = GoogleDriveState.CONNECTING
            val authRes = gdriveProvider.oAuthManager.refreshAccessToken()
            if (authRes.isSuccess) {
                val quotaRes = gdriveProvider.driveFileManager.getStorageQuota()
                if (quotaRes.isSuccess) {
                    val quota = quotaRes.getOrThrow()
                    _googleDriveState.value = GoogleDriveState.CONNECTED
                    _driveQuota.value = quota
                    _driveQuotaState.value = DriveQuotaState.Loaded(
                        totalBytes = quota.limitBytes,
                        usedBytes = quota.usageBytes,
                        availableBytes = quota.availableBytes,
                        userEmail = quota.userEmail,
                        displayName = quota.displayName
                    )
                    loadCloudAccounts()
                    fetchRemoteDriveBackups()
                    onResult?.invoke(authRes)
                } else {
                    val ex = quotaRes.exceptionOrNull()
                    val is401 = ex is com.swiftvault.backup.cloud.gdrive.GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
                    if (is401) {
                        _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                    } else {
                        _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                    }
                    onResult?.invoke(Result.failure(ex ?: IllegalStateException("Validação da API falhou após renovação de token.")))
                }
            } else {
                val ex = authRes.exceptionOrNull()
                if (ex is com.swiftvault.backup.cloud.gdrive.GoogleCloudOAuthConfigurationException) {
                    _googleDriveState.value = GoogleDriveState.CONNECTION_ERROR
                    _driveQuotaState.value = DriveQuotaState.Error(
                        message = ex.message ?: "Cliente OAuth não registrado no Google Cloud Console",
                        technicalCode = "UnregisteredOnApiConsole"
                    )
                } else {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                }
                onResult?.invoke(authRes)
            }
        }
    }

    fun deleteBackupRecord(record: BackupRecord, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val file = File(record.filePath)
                val fileDeleted = if (file.exists()) file.delete() else true
                if (fileDeleted) {
                    dbHelper.deleteBackupRecord(record.id)
                    loadBackupRecords()
                    _deviceStats.value = generateDeviceStats()
                    onResult(true, null)
                } else {
                    onResult(false, "Não foi possível excluir o arquivo de backup do armazenamento.")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Erro ao excluir backup.")
            }
        }
    }

    fun pauseCloudUpload() {
        gdriveProvider.cloudBackupRepository.pauseUpload()
    }

    fun resumeCloudUpload() {
        gdriveProvider.cloudBackupRepository.resumeUpload()
    }

    fun cancelCloudUpload() {
        gdriveProvider.cloudBackupRepository.cancelUpload()
    }

    fun uploadRecordToGoogleDrive(record: BackupRecord) {
        viewModelScope.launch {
            try {
                _googleDriveState.value = GoogleDriveState.SYNCING
                BackupForegroundService.startService(context, "Enviando ${record.title} para Google Drive...")
                val result = gdriveProvider.cloudBackupRepository.uploadLocalBackup(record) { progressInfo ->
                    val speedStr = if (progressInfo.speedMBps > 0) " • %.1f MB/s".format(progressInfo.speedMBps) else ""
                    val etaStr = if (progressInfo.estimatedRemainingSeconds > 0) " • Restam ${progressInfo.estimatedRemainingSeconds}s" else ""
                    val currentName = "${record.title}$speedStr$etaStr"

                    _backupProgress.value = BackupProgress(
                        stage = progressInfo.stage,
                        currentItemName = currentName,
                        progressPercent = progressInfo.progressPercent,
                        bytesProcessed = progressInfo.bytesUploaded,
                        totalBytesEstimated = progressInfo.totalBytes,
                        isFinished = progressInfo.isFinished,
                        error = progressInfo.error
                    )
                }

                if (result.isFailure) {
                    val ex = result.exceptionOrNull()
                    val is401 = ex is com.swiftvault.backup.cloud.gdrive.GoogleDriveSessionExpiredException || ex?.message?.contains("401") == true
                    val err = if (is401) {
                        _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                        "A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.\nCódigo técnico: HTTP 401"
                    } else {
                        ex?.message ?: "Falha no envio para o Google Drive."
                    }
                    _backupProgress.value = BackupProgress(
                        stage = "Falha",
                        currentItemName = err,
                        progressPercent = 0f,
                        bytesProcessed = 0,
                        totalBytesEstimated = record.sizeBytes,
                        isFinished = true,
                        error = err
                    )
                }
            } catch (e: Exception) {
                val is401 = e is com.swiftvault.backup.cloud.gdrive.GoogleDriveSessionExpiredException || e.message?.contains("401") == true
                if (is401) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                }
                val msg = if (is401) {
                    "A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.\nCódigo técnico: HTTP 401"
                } else {
                    e.message ?: "Erro no upload."
                }
                _backupProgress.value = BackupProgress(
                    stage = "Erro",
                    currentItemName = msg,
                    progressPercent = 0f,
                    bytesProcessed = 0,
                    totalBytesEstimated = record.sizeBytes,
                    isFinished = true,
                    error = msg
                )
            } finally {
                BackupForegroundService.stopService(context)
                loadBackupRecords()
                fetchRemoteDriveBackups()
                refreshDriveAccountInfo()
                if (gdriveProvider.oAuthManager.isSessionExpired()) {
                    _googleDriveState.value = GoogleDriveState.SESSION_EXPIRED
                } else if (_googleDriveState.value == GoogleDriveState.SYNCING) {
                    _googleDriveState.value = GoogleDriveState.CONNECTED
                }
            }
        }
    }

    fun downloadAndRestoreFromGoogleDrive(remoteFile: com.swiftvault.backup.cloud.gdrive.DriveFileMetadata, password: String?) {
        viewModelScope.launch {
            val downloadRes = gdriveProvider.cloudBackupRepository.downloadRemoteBackup(remoteFile) { progressInfo ->
                _restoreProgress.value = RestoreProgress(
                    stage = progressInfo.stage,
                    currentItemName = "${remoteFile.name} (${"%.1f MB/s".format(progressInfo.speedMBps)})",
                    progressPercent = progressInfo.progressPercent,
                    isFinished = false
                )
            }

            if (downloadRes.isSuccess) {
                val downloadedFile = downloadRes.getOrThrow()
                try {
                    val manifest = restoreEngine.inspectBackup(downloadedFile)
                    restoreEngine.performSelectiveRestore(
                        containerFile = downloadedFile,
                        manifest = manifest,
                        selectedItemIds = manifest.items.map { it.id }.toSet(),
                        password = password
                    ).collect { progress ->
                        _restoreProgress.value = progress
                    }
                } catch (e: Exception) {
                    _restoreProgress.value = RestoreProgress("Erro", "Falha ao ler container baixado: ${e.message}", 1.0f, true, e.message)
                }
            } else {
                val err = downloadRes.exceptionOrNull()
                _restoreProgress.value = RestoreProgress("Erro", "Falha ao baixar da nuvem: ${err?.message}", 1.0f, true, err?.message)
            }
        }
    }

    fun deleteRemoteDriveBackup(fileId: String) {
        viewModelScope.launch {
            val res = gdriveProvider.cloudBackupRepository.deleteRemoteBackup(fileId)
            if (res.isSuccess) {
                fetchRemoteDriveBackups()
                refreshDriveAccountInfo()
            }
        }
    }

    fun updateAutoSyncSettings(settings: com.swiftvault.backup.cloud.gdrive.AutoSyncSettings) {
        _syncSettings.value = settings
        gdriveProvider.syncManager.updateSettings(settings)
    }

    fun disconnectGoogleDrive() {
        viewModelScope.launch {
            gdriveProvider.oAuthManager.disconnect()
            dbHelper.updateCloudAccount(
                com.swiftvault.backup.data.model.CloudAccount(
                    providerType = com.swiftvault.backup.data.model.CloudProviderType.GOOGLE_DRIVE,
                    accountName = "Google Drive (Não configurado)",
                    isConnected = false,
                    quotaUsedBytes = 0L,
                    quotaTotalBytes = 0L,
                    lastSyncTimestamp = 0L
                )
            )
            _googleDriveState.value = GoogleDriveState.NOT_CONNECTED
            _driveQuotaState.value = DriveQuotaState.Idle
            _remoteBackupsState.value = RemoteBackupsState.Idle
            _driveQuota.value = null
            _remoteDriveBackups.value = emptyList()
            loadCloudAccounts()
        }
    }

    private fun generateDeviceStats(): DeviceStats {
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalStorage = totalBlocks * blockSize
        val freeStorage = availableBlocks * blockSize
        val usedStorage = totalStorage - freeStorage

        val backupDir = backupEngine.getOrCreateDefaultVaultDir()
        val backupsSize = backupDir.listFiles()?.sumOf { it.length() } ?: 0L

        // Estimates based on standard device distribution
        val appsSize = (usedStorage * 0.40).toLong()
        val photosSize = (usedStorage * 0.25).toLong()
        val videosSize = (usedStorage * 0.15).toLong()
        val docsSize = (usedStorage * 0.05).toLong()
        val otherSize = (usedStorage - (appsSize + photosSize + videosSize + docsSize + backupsSize)).coerceAtLeast(0L)

        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.getMemoryInfo(memoryInfo)

        return DeviceStats(
            model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (Baklava / API ${Build.VERSION.SDK_INT})",
            apiLevel = Build.VERSION.SDK_INT,
            ramTotalBytes = memoryInfo.totalMem,
            ramAvailableBytes = memoryInfo.availMem,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "2026-10-01",
            storageTotalBytes = totalStorage,
            storageUsedBytes = usedStorage,
            storageFreeBytes = freeStorage,
            backupStorageUsedBytes = backupsSize,
            appsStorageBytes = appsSize,
            photosBytes = photosSize,
            videosBytes = videosSize,
            documentsBytes = docsSize,
            othersBytes = otherSize
        )
    }
}
