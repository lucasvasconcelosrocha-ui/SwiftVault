package com.swiftvault.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftvault.backup.data.model.SyncStatus
import com.swiftvault.backup.engine.CapabilityManager
import com.swiftvault.backup.ui.components.cards.InfoRow
import com.swiftvault.backup.ui.components.cards.InteractiveGlassCard
import com.swiftvault.backup.cloud.gdrive.DriveFileMetadata
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToApps: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToCloud: () -> Unit,
    onNavigateToDns: () -> Unit,
    onNavigateToCustomWizard: () -> Unit,
    onNavigateToQuickWizard: () -> Unit,
    onNavigateToRestore: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val stats by viewModel.deviceStats.collectAsState()
    val apps by viewModel.installedApps.collectAsState()
    val backups by viewModel.backupRecords.collectAsState()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val activeMode by viewModel.activeMode.collectAsState()

    val primaryAccent = getAccentColor(currentAccent)
    val totalBackupsSize = remember(backups) { backups.sumOf { it.sizeBytes } }
    val latestBackup = remember(backups) { backups.firstOrNull() }

    val userAppsCount = remember(apps) { apps.count { !it.isSystemApp } }
    val systemAppsCount = remember(apps) { apps.count { it.isSystemApp } }
    val withBackupApps = remember(apps) { apps.count { it.hasBackup } }
    val withoutBackupApps = remember(apps) { apps.count { !it.hasBackup } }

    val isGoogleConnected = viewModel.gdriveProvider.oAuthManager.isConnected()
    val connectedEmail = viewModel.gdriveProvider.oAuthManager.getConnectedAccountEmail()
    val googleDriveState by viewModel.googleDriveState.collectAsState()
    val driveQuotaState by viewModel.driveQuotaState.collectAsState()
    val remoteBackupsState by viewModel.remoteBackupsState.collectAsState()
    val rootStatus by viewModel.rootAccessStatus.collectAsState()

    val isAllSynced = remember(backups, isGoogleConnected) {
        isGoogleConnected && backups.isNotEmpty() && backups.all { it.syncStatus == SyncStatus.SYNCED }
    }

    val latestBackupFormatted = remember(latestBackup) {
        if (latestBackup != null) {
            val sdf = SimpleDateFormat("dd/MM/yyyy — HH:mm", Locale.getDefault())
            sdf.format(Date(latestBackup.timestamp))
        } else {
            "Nenhum backup realizado"
        }
    }

    val totalStorageGb = "%.1f GB".format(stats.storageTotalBytes.toDouble() / (1024 * 1024 * 1024))
    val usedStorageGb = "%.1f GB".format((stats.storageTotalBytes - stats.storageFreeBytes).toDouble() / (1024 * 1024 * 1024))

    val scrollState = rememberScrollState()
    var isPullRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = isPullRefreshing,
        onRefresh = {
            isPullRefreshing = true
            coroutineScope.launch {
                viewModel.refreshDashboard()
                delay(600)
                isPullRefreshing = false
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
        // 32. BANNER / IDENTIDADE DO APLICATIVO
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSurfaceVariant)
                .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Linha superior: ODIN_BACKUP à esquerda, badge Root à direita
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(primaryAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = primaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            text = "ODIN_BACKUP",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 36. ROOT NO DASHBOARD
                    val (rootText, rootColor, rootBg) = when (rootStatus) {
                        CapabilityManager.RootStatus.AUTHORIZED -> Triple("● Autorizado", StatusSuccess, StatusSuccess.copy(alpha = 0.15f))
                        CapabilityManager.RootStatus.PERMISSION_NEEDED -> Triple("⚠ Permissão necessária", StatusWarning, StatusWarning.copy(alpha = 0.15f))
                        else -> Triple("✕ Não autorizado", TextSecondary, DarkSurface)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(rootBg)
                            .border(1.dp, rootColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable {
                                if (rootStatus == CapabilityManager.RootStatus.PERMISSION_NEEDED) {
                                    viewModel.requestRootPermission()
                                } else {
                                    onNavigateToPermissions()
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Root ",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = rootText,
                                fontSize = 11.sp,
                                color = rootColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Linha inferior: Subtítulo à esquerda, ícone Terminal de logs à direita
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Backup Granular & Proteção Avançada",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = primaryAccent,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onNavigateToLogs,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Logs",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // PRIMARY ACTION: [ NOVO BACKUP ]
        Button(
            onClick = onNavigateToCustomWizard,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
        ) {
            Icon(imageVector = Icons.Default.AddModerator, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "NOVO BACKUP",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // SECONDARY ACTIONS: [ BACKUP RÁPIDO ]  [ RESTAURAR ]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onNavigateToQuickWizard,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(12.dp),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(DarkCardBorder))
            ) {
                Icon(imageVector = Icons.Default.Bolt, contentDescription = null, tint = AccentNeonBlue, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "BACKUP RÁPIDO", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onNavigateToRestore,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(12.dp),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(DarkCardBorder))
            ) {
                Icon(imageVector = Icons.Default.Restore, contentDescription = null, tint = AccentEmeraldGreen, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "RESTAURAR", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // CARD: Último Backup
        InteractiveGlassCard(
            title = "Último Backup",
            icon = Icons.Default.Schedule,
            iconTint = AccentCyberPurple,
            onClick = onNavigateToRestore
        ) {
            Text(
                text = latestBackupFormatted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            if (latestBackup != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = latestBackup.title,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // CARD: Backups
        InteractiveGlassCard(
            title = "Backups",
            icon = Icons.Default.Inventory2,
            iconTint = AccentCyberPurple,
            badgeText = "${backups.size}",
            badgeColor = AccentCyberPurple,
            onClick = onNavigateToRestore
        ) {
            InfoRow("Total de backups", "${backups.size}")
            InfoRow("Espaço ocupado", "%.1f GB".format(totalBackupsSize.toDouble() / (1024 * 1024 * 1024)))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // CARD: GOOGLE DRIVE (RESUMO COMPLETO)
        val (driveBadgeText, driveBadgeColor) = when (googleDriveState) {
            GoogleDriveState.CONNECTED -> Pair("● Conectado", StatusSuccess)
            GoogleDriveState.SYNCING -> Pair("● Sincronizando...", AccentNeonBlue)
            GoogleDriveState.SESSION_EXPIRED -> Pair("● Reconectar", AccentSunsetOrange)
            GoogleDriveState.CONNECTION_ERROR -> Pair("● Erro", StatusError)
            GoogleDriveState.CONNECTING -> Pair("● Conectando...", AccentElectricCyan)
            GoogleDriveState.NOT_CONNECTED -> Pair("○ Não conectado", TextMuted)
        }

        InteractiveGlassCard(
            title = "GOOGLE DRIVE",
            icon = Icons.Default.CloudQueue,
            iconTint = AccentElectricCyan,
            badgeText = driveBadgeText,
            badgeColor = driveBadgeColor,
            onClick = onNavigateToCloud
        ) {
            InfoRow("Conta", connectedEmail ?: "Não autenticada")

            val spaceText = when (val s = driveQuotaState) {
                is DriveQuotaState.Loaded -> formatStorageSize(s.availableBytes)
                is DriveQuotaState.Loading -> "Consultando..."
                is DriveQuotaState.Error -> "Indisponível"
                is DriveQuotaState.Idle -> if (isGoogleConnected) "Consultando..." else "Indisponível"
            }
            val spaceColor = if (spaceText.contains("B")) StatusSuccess else TextSecondary
            InfoRow("Espaço disponível", spaceText, spaceColor)

            val backupsCountText = when (val b = remoteBackupsState) {
                is RemoteBackupsState.Loaded -> "${b.backups.size}"
                is RemoteBackupsState.Loading -> "..."
                else -> if (isGoogleConnected) "..." else "—"
            }
            InfoRow("Backups em nuvem", backupsCountText)

            val lastSyncFormatted = when {
                remoteBackupsState is RemoteBackupsState.Loaded && (remoteBackupsState as RemoteBackupsState.Loaded).backups.isNotEmpty() -> {
                    val maxTime = (remoteBackupsState as RemoteBackupsState.Loaded).backups.maxOf { it.modifiedTime }
                    SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault()).format(Date(maxTime))
                }
                googleDriveState == GoogleDriveState.CONNECTED -> "Nenhum backup"
                else -> "—"
            }
            InfoRow("Última sincronização", lastSyncFormatted)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // CARD: Armazenamento
        InteractiveGlassCard(
            title = "Armazenamento",
            icon = Icons.Default.Storage,
            iconTint = AccentEmeraldGreen,
            onClick = onNavigateToFiles
        ) {
            InfoRow("Espaço", "$usedStorageGb / $totalStorageGb")
            InfoRow("Livre", "%.1f GB".format(stats.storageFreeBytes.toDouble() / (1024 * 1024 * 1024)), StatusSuccess)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 35. APLICATIVOS NO DASHBOARD
        InteractiveGlassCard(
            title = "Aplicativos",
            icon = Icons.Default.Apps,
            iconTint = AccentNeonBlue,
            onClick = onNavigateToApps
        ) {
            InfoRow("Total", "${apps.size}")
            InfoRow("Usuário", "$userAppsCount")
            InfoRow("Sistema", "$systemAppsCount")
            InfoRow("Com backup", "$withBackupApps", StatusSuccess)
            InfoRow("Sem backup", "$withoutBackupApps", if (withoutBackupApps > 0) StatusWarning else StatusSuccess)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes.toDouble() / 1024
    val mb = kb / 1024
    val gb = mb / 1024
    val tb = gb / 1024
    return when {
        tb >= 1.0 -> "%.2f TB".format(tb)
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        else -> "%.1f KB".format(kb)
    }
}
