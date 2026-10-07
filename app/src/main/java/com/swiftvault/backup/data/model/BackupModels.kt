package com.swiftvault.backup.data.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import com.google.gson.annotations.SerializedName

/**
 * Types of granular backup items supported by SwiftVault
 */
enum class BackupItemType {
    @SerializedName("app_apk")
    APP_APK,

    @SerializedName("app_split_apks")
    APP_SPLIT_APKS,

    @SerializedName("app_data")
    APP_DATA,

    @SerializedName("folder")
    FOLDER,

    @SerializedName("file")
    FILE,

    @SerializedName("sms")
    SMS,

    @SerializedName("call_log")
    CALL_LOG,

    @SerializedName("dns_config")
    DNS_CONFIG,

    @SerializedName("contacts")
    CONTACTS,

    @SerializedName("wallpaper")
    WALLPAPER,

    @SerializedName("system_settings")
    SYSTEM_SETTINGS
}

/**
 * Contact record model for backup and restore
 */
data class ContactRecord(
    val id: String,
    val displayName: String,
    val phoneNumbers: List<String> = emptyList(),
    val emails: List<String> = emptyList()
)

/**
 * App information retrieved from PackageManager
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val sourceDir: String,
    val splitSourceDirs: List<String> = emptyList(),
    val dataDir: String,
    val isSystemApp: Boolean,
    val sizeBytes: Long,
    val lastInstallTime: Long,
    val lastUpdateTime: Long,
    val lastBackupDate: String? = null,
    val hasBackup: Boolean = false,
    val backupSize: Long = 0L,
    val architecture: String = "arm64-v8a",
    @Transient var iconBitmap: ImageBitmap? = null
)

/**
 * Granular options when backing up an individual app
 */
data class AppSelectionOptions(
    val includeBaseApk: Boolean = true,
    val includeSplits: Boolean = true,
    val includeData: Boolean = false,
    val includePermissions: Boolean = true,
    val includeCache: Boolean = false,
    val selectedFiles: List<String> = emptyList(),
    val relatedSettings: Boolean = false
)

/**
 * Hierarchical tree node for File & Folder Explorer
 */
data class FileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val childrenCount: Int = 0,
    val extension: String = "",
    val isSelected: Boolean = false,
    val isPartiallySelected: Boolean = false,
    val children: List<FileNode> = emptyList()
)

/**
 * Reusable backup filter criteria
 */
data class BackupFilter(
    val id: String,
    val name: String,
    val includedExtensions: List<String> = emptyList(), // e.g. .jpg, .png, .mp4, .pdf
    val minSizeBytes: Long = 0L, // e.g. > 100MB
    val maxAgeDays: Int = 0, // e.g. 30 days
    val excludedPatterns: List<String> = listOf("*.tmp", "*.cache")
)

/**
 * DNS & Network configuration snapshot
 */
data class DnsConfig(
    val mode: String, // "off", "opportunistic", "strict", "private_dns"
    val isActive: Boolean,
    val hostname: String?,
    val dnsServers: List<String> = emptyList(),
    val interfaceName: String? = null,
    val isVpnActive: Boolean = false,
    val capturedAt: Long = System.currentTimeMillis()
)

/**
 * SMS record model for backup and restore
 */
data class SmsRecord(
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Int
)

/**
 * Call log record model for backup and restore
 */
data class CallRecord(
    val number: String,
    val date: Long,
    val duration: Long,
    val type: Int
)

/**
 * Wallpaper & system preferences backup model
 */
data class SystemSettingsConfig(
    val wallpaperBase64: String? = null,
    val screenTimeout: Int = 30000,
    val fontScale: Float = 1.0f,
    val autoRotate: Boolean = true,
    val hapticFeedback: Boolean = true,
    val capturedAt: Long = System.currentTimeMillis()
)

/**
 * Specification for multi-part archives (.zip.001, .zip.002...)
 */
data class BackupPartInfo(
    val partIndex: Int,
    val fileName: String,
    val sizeBytes: Long,
    val sha256Checksum: String,
    val startByte: Long = 0L,
    val endByte: Long = 0L,
    val cloudAccountId: String? = null
)

/**
 * Item specification inside the SwiftVault Container Manifest
 */
data class ManifestItem(
    val id: String,
    val type: BackupItemType,
    val name: String,
    val originalPath: String,
    val archiveEntryPath: String,
    val sizeBytes: Long,
    val sha256Checksum: String,
    val isEncrypted: Boolean = false,
    val compressionMethod: String = "DEFLATE", // "STORED" or "DEFLATE"
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Manifest header written inside SwiftVault containers (.zip and legacy .svb)
 */
data class BackupManifest(
    val formatIdentifier: String = "SwiftVault",
    val manifestVersion: Int = 2,
    val vaultVersion: String = "2.0.0",
    val backupId: String,
    val title: String,
    val deviceName: String = "",
    val androidSdk: Int = 0,
    val androidRelease: String = "",
    val rootManager: String? = null,
    val createdAt: Long,
    val isEncrypted: Boolean,
    val encryptionAlgorithm: String = "AES-256-GCM-CHUNKED",
    val compressionAlgorithm: String = "ZIP-MIXED",
    val totalSizeBytes: Long,
    val items: List<ManifestItem>,
    val sha256PayloadHash: String = "",
    val parts: List<BackupPartInfo> = emptyList()
)

/**
 * Stored Backup history record
 */
data class BackupRecord(
    val id: String,
    val title: String,
    val filePath: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val isEncrypted: Boolean,
    val timestamp: Long,
    val itemCount: Int,
    val status: BackupStatus,
    val includedAppsCount: Int = 0,
    val includedFilesCount: Int = 0,
    val includedDns: Boolean = false,
    val destination: String = "Local",
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val driveFileId: String? = null
)

enum class BackupStatus {
    COMPLETED,
    IN_PROGRESS,
    FAILED,
    CORRUPTED,
    VERIFYING
}

/**
 * Single Source of Truth for Cloud Synchronization Status
 */
enum class SyncStatus(val displayName: String) {
    LOCAL_ONLY("Não sincronizado"),
    QUEUED("Na fila"),
    UPLOADING("Enviando..."),
    UPLOADED("Enviado"),
    VERIFYING("Verificando integridade"),
    SYNCED("Sincronizado"),
    DOWNLOADING("Baixando..."),
    FAILED("Falha na sincronização"),
    PAUSED("Pausado"),
    CONFLICT("Conflito")
}

/**
 * Exclusively Supported Cloud Storage Provider
 */
enum class CloudProviderType(val displayName: String) {
    GOOGLE_DRIVE("Google Drive")
}

/**
 * Legitimate saved Wi-Fi network information (retrieved via authorized root from WifiConfigStore)
 */
data class SavedWifiNetwork(
    val ssid: String,
    val password: String?,
    val securityType: String = "WPA2/WPA3",
    val lastConnected: String = "Salva no dispositivo"
)

/**
 * Legitimate Wi-Fi connection metadata
 */
data class WifiConnectionDetails(
    val isConnected: Boolean,
    val ssid: String,
    val linkSpeedMbps: Int,
    val frequencyGhz: String,
    val ipAddress: String,
    val gateway: String,
    val dnsServers: List<String>,
    val privateDnsServerName: String? = null,
    val wifiPasswordStatus: String = "Indisponível pelo Android\nO Android não permite que este aplicativo acesse a senha desta rede sem autorização root.",
    val savedNetworks: List<SavedWifiNetwork> = emptyList()
)

/**
 * Network data usage breakdown
 */
data class DataUsagePeriod(
    val downloadBytes: Long,
    val uploadBytes: Long,
    val totalBytes: Long,
    val wifiBytes: Long,
    val mobileBytes: Long
)

data class NetworkDataUsageStats(
    val today: DataUsagePeriod,
    val thisWeek: DataUsagePeriod,
    val thisMonth: DataUsagePeriod,
    val weeklyHistory: List<Pair<String, Long>>,
    val limitationNotice: String? = null
)

/**
 * Cloud Account information and connection status
 */
data class CloudAccount(
    val providerType: CloudProviderType,
    val accountName: String,
    val isConnected: Boolean,
    val quotaUsedBytes: Long = 0L,
    val quotaTotalBytes: Long = 0L,
    val lastSyncTimestamp: Long = 0L,
    val filesCount: Int = 0,
    val errorsCount: Int = 0,
    val serverHost: String? = null,
    val serverPort: Int = 0,
    val username: String? = null,
    val authTokenOrSecret: String? = null
)

/**
 * Remote cloud file representation
 */
data class CloudRemoteFile(
    val id: String,
    val name: String,
    val remotePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val md5OrSha: String? = null
)

/**
 * Cloud Transfer Queue item
 */
data class CloudSyncQueueItem(
    val id: String,
    val backupId: String,
    val backupTitle: String,
    val providerType: CloudProviderType,
    val direction: TransferDirection,
    val status: QueueStatus,
    val progressBytes: Long,
    val totalBytes: Long,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val priority: Int = 0 // Higher = processed first
)

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD
}

enum class QueueStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    RETRYING
}

/**
 * Result of individual cloud diagnostic test
 */
data class CloudDiagnosticResult(
    val stepName: String,
    val status: DiagnosticStepStatus,
    val latencyMs: Long = 0L,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val possibleCause: String? = null,
    val recommendedSolution: String? = null
)

enum class DiagnosticStepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}

/**
 * Conflict detection for bi-directional synchronization
 */
data class SyncConflict(
    val fileId: String,
    val fileName: String,
    val localTimestamp: Long,
    val localSize: Long,
    val remoteTimestamp: Long,
    val remoteSize: Long,
    val localHash: String,
    val remoteHash: String
)

enum class ConflictResolution {
    USE_LOCAL,
    USE_REMOTE,
    KEEP_BOTH
}

/**
 * Device diagnostic and storage breakdown stats
 */
data class DeviceStats(
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val ramTotalBytes: Long,
    val ramAvailableBytes: Long,
    val securityPatch: String,
    val storageTotalBytes: Long,
    val storageUsedBytes: Long,
    val storageFreeBytes: Long,
    val backupStorageUsedBytes: Long,
    val appsStorageBytes: Long,
    val photosBytes: Long,
    val videosBytes: Long,
    val documentsBytes: Long,
    val othersBytes: Long
)

/**
 * Structured system log entry
 */
data class LogEntry(
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val details: String? = null
)

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG
}

/**
 * Accent colors for visual theme personalization
 */
enum class ThemeAccent(val displayName: String, val hexCode: Long) {
    BLUE("Azul Neon", 0xFF2979FF),
    PURPLE("Roxo Cyber", 0xFFA855F7),
    GREEN("Verde Esmeralda", 0xFF10B981),
    CYAN("Ciano Elétrico", 0xFF06B6D4),
    ORANGE("Laranja Sunset", 0xFFF97316),
    RED("Vermelho Carmim", 0xFFEF4444)
}

/**
 * Step in the 8-Step Backup Wizard
 */
enum class WizardStep(val stepNumber: Int, val title: String) {
    APPS(1, "Aplicativos"),
    DATA(2, "Dados"),
    FILES(3, "Arquivos"),
    SYSTEM(4, "Sistema"),
    DNS(5, "DNS"),
    STORAGE(6, "Armazenamento"),
    SECURITY(7, "Segurança"),
    SUMMARY(8, "Resumo")
}
