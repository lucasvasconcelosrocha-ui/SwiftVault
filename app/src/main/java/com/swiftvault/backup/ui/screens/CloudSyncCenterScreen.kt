package com.swiftvault.backup.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftvault.backup.cloud.gdrive.AutoSyncSettings
import com.swiftvault.backup.cloud.gdrive.DriveFileMetadata
import com.swiftvault.backup.data.model.*
import com.swiftvault.backup.ui.components.cards.InfoRow
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.*

@Composable
fun CloudSyncCenterScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onViewLogs: () -> Unit
) {
    val context = LocalContext.current
    val cloudAccounts by viewModel.cloudAccounts.collectAsState()
    val syncQueue by viewModel.syncQueue.collectAsState()
    val diagnosticResults by viewModel.diagnosticResults.collectAsState()
    val isDiagnosing by viewModel.isDiagnosing.collectAsState()
    val conflicts by viewModel.detectedConflicts.collectAsState()
    val remoteBackups by viewModel.remoteDriveBackups.collectAsState()
    val syncSettings by viewModel.syncSettings.collectAsState()

    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var selectedProviderType by remember { mutableStateOf(CloudProviderType.GOOGLE_DRIVE) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Visão Geral, 1: Google Drive (Nuvem), 2: Fila, 3: Diagnóstico

    var inputEmail by remember { mutableStateOf("") }
    var isPullRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val isGoogleConnected = viewModel.gdriveProvider.oAuthManager.isConnected()
    val connectedEmail = viewModel.gdriveProvider.oAuthManager.getConnectedAccountEmail()
    val googleDriveState by viewModel.googleDriveState.collectAsState()
    val driveQuotaState by viewModel.driveQuotaState.collectAsState()
    val remoteBackupsState by viewModel.remoteBackupsState.collectAsState()

    // Activity result launcher for Google Consent Intent
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val emailToUse = inputEmail.ifBlank { viewModel.gdriveProvider.oAuthManager.getConnectedAccountEmail() ?: "" }
            if (emailToUse.isNotBlank()) {
                viewModel.connectGoogleAccount(emailToUse, context as? Activity) { authResult ->
                    if (authResult.isSuccess) {
                        Toast.makeText(context, "Google Drive conectado e validado com sucesso!", Toast.LENGTH_SHORT).show()
                    } else {
                        val msg = authResult.exceptionOrNull()?.message ?: "Falha ao validar conta após consentimento."
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                viewModel.refreshGoogleAuth { authResult ->
                    if (authResult.isSuccess) {
                        Toast.makeText(context, "Google Drive conectado com sucesso!", Toast.LENGTH_SHORT).show()
                    } else {
                        val msg = authResult.exceptionOrNull()?.message ?: "Falha ao renovar autenticação."
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Toast.makeText(context, "Autorização OAuth cancelada pelo usuário.", Toast.LENGTH_SHORT).show()
        }
    }

    // Activity result launcher for Google Account chooser
    val accountChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val accountName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                inputEmail = accountName
                viewModel.connectGoogleAccount(accountName, context as? Activity) { authResult ->
                    if (authResult.isSuccess) {
                        Toast.makeText(context, "Google Drive conectado e validado com sucesso!", Toast.LENGTH_SHORT).show()
                    } else {
                        val ex = authResult.exceptionOrNull()
                        if (ex is com.swiftvault.backup.cloud.gdrive.AuthIntentRequiredException) {
                            try {
                                consentLauncher.launch(ex.intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Erro ao abrir tela de autorização do Google: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val msg = ex?.message ?: "Falha ao autenticar com o Google."
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (isGoogleConnected) {
            viewModel.refreshDriveAccountInfo()
            viewModel.fetchRemoteDriveBackups()
        }
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Google Drive",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    IconButton(onClick = onViewLogs) {
                        Icon(imageVector = Icons.Default.Article, contentDescription = "Ver Logs", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Tab Switcher with 4 tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DarkSurfaceVariant,
                    contentColor = primaryAccent
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Visão Geral", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            viewModel.fetchRemoteDriveBackups()
                        },
                        text = {
                            Text(
                                "Nuvem (${remoteBackups.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                "Fila (${syncQueue.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text("Diagnóstico", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                coroutineScope.launch {
                    viewModel.refreshCloudData()
                    delay(600)
                    isPullRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            when (selectedTab) {
                0 -> {
                    // TAB 0: Visão Geral e Conexão Gratuita
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(modifier = Modifier.height(14.dp))

                        // OAuth Configuration / Connection Error Card
                        if (googleDriveState == GoogleDriveState.CONNECTION_ERROR) {
                            val errorInfo = driveQuotaState as? DriveQuotaState.Error
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, StatusError.copy(alpha = 0.7f), RoundedCornerShape(14.dp)),
                                colors = CardDefaults.cardColors(containerColor = StatusError.copy(alpha = 0.12f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusError, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Erro de Configuração / Autenticação", fontWeight = FontWeight.Bold, color = StatusError, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = errorInfo?.message ?: "Falha ao autenticar com o Google Drive.",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                    if (!errorInfo?.technicalCode.isNullOrBlank()) {
                                        Text(
                                            text = "Código técnico: ${errorInfo?.technicalCode}",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = viewModel.gdriveProvider.oAuthManager.createChooseAccountIntent()
                                                accountChooserLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Erro ao abrir seletor de contas: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text("TENTAR CONECTAR NOVAMENTE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // Session Expired Warning Card
                        if (googleDriveState == GoogleDriveState.SESSION_EXPIRED) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, AccentSunsetOrange.copy(alpha = 0.7f), RoundedCornerShape(14.dp)),
                                colors = CardDefaults.cardColors(containerColor = AccentSunsetOrange.copy(alpha = 0.12f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = AccentSunsetOrange, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sessão Expirada", fontWeight = FontWeight.Bold, color = AccentSunsetOrange, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "A sessão do Google Drive expirou. É necessário reconectar sua conta para continuar.",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Código técnico: HTTP 401",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = viewModel.gdriveProvider.oAuthManager.createChooseAccountIntent()
                                                accountChooserLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Erro ao abrir seletor de contas: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentSunsetOrange),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text("RECONECTAR GOOGLE DRIVE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // Active Account Status Card
                        val (driveBadgeText, driveBadgeColor) = when (googleDriveState) {
                            GoogleDriveState.CONNECTED -> Pair("● Conectado", StatusSuccess)
                            GoogleDriveState.SYNCING -> Pair("● Sincronizando...", AccentNeonBlue)
                            GoogleDriveState.SESSION_EXPIRED -> Pair("● Reconectar", AccentSunsetOrange)
                            GoogleDriveState.CONNECTION_ERROR -> Pair("● Erro", StatusError)
                            GoogleDriveState.CONNECTING -> Pair("● Conectando...", AccentElectricCyan)
                            GoogleDriveState.NOT_CONNECTED -> Pair("○ Não conectado", TextMuted)
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CloudQueue, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(24.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Google Drive",
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                            color = TextPrimary
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(driveBadgeColor.copy(alpha = 0.2f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = driveBadgeText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = driveBadgeColor
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = DarkCardBorder)
                                Spacer(modifier = Modifier.height(12.dp))

                                val gdriveAccount = cloudAccounts.find { it.providerType == CloudProviderType.GOOGLE_DRIVE }

                                InfoRow("Status", driveBadgeText, driveBadgeColor)
                                InfoRow("Conta Google", connectedEmail ?: "Não autenticada")

                                val displayName = when (val q = driveQuotaState) {
                                    is DriveQuotaState.Loaded -> q.displayName
                                    else -> null
                                }
                                if (!displayName.isNullOrBlank()) {
                                    InfoRow("Nome do usuário", displayName)
                                }

                                val usedStr = when (val q = driveQuotaState) {
                                    is DriveQuotaState.Loaded -> formatStorageSize(q.usedBytes)
                                    is DriveQuotaState.Loading -> "Consultando..."
                                    else -> "Indisponível"
                                }
                                InfoRow("Espaço utilizado", usedStr)

                                val availStr = when (val q = driveQuotaState) {
                                    is DriveQuotaState.Loaded -> formatStorageSize(q.availableBytes)
                                    is DriveQuotaState.Loading -> "Consultando..."
                                    else -> "Indisponível"
                                }
                                val availColor = if (availStr.contains("B")) StatusSuccess else TextSecondary
                                InfoRow("Espaço disponível", availStr, availColor)

                                val totalStr = when (val q = driveQuotaState) {
                                    is DriveQuotaState.Loaded -> formatStorageSize(q.totalBytes)
                                    is DriveQuotaState.Loading -> "Consultando..."
                                    else -> "Indisponível"
                                }
                                InfoRow("Espaço total", totalStr)

                                val backupsCountStr = when (val b = remoteBackupsState) {
                                    is RemoteBackupsState.Loaded -> "${b.backups.size}"
                                    is RemoteBackupsState.Loading -> "..."
                                    else -> if (isGoogleConnected) "..." else "—"
                                }
                                InfoRow("Backups no Drive", backupsCountStr)
                                InfoRow("Fila pendente", "${syncQueue.size}")
                                InfoRow("Erros", "${gdriveAccount?.errorsCount ?: 0}", if ((gdriveAccount?.errorsCount ?: 0) == 0) StatusSuccess else StatusError)

                                val lastSyncStr = when {
                                    remoteBackupsState is RemoteBackupsState.Loaded && (remoteBackupsState as RemoteBackupsState.Loaded).backups.isNotEmpty() -> {
                                        val maxTime = (remoteBackupsState as RemoteBackupsState.Loaded).backups.maxOf { it.modifiedTime }
                                        java.text.SimpleDateFormat("dd/MM/yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(maxTime))
                                    }
                                    googleDriveState == GoogleDriveState.CONNECTED -> "Nenhum backup"
                                    else -> "—"
                                }
                                InfoRow("Última sincronização", lastSyncStr)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Connect / Action Buttons
                        if (!isGoogleConnected) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = viewModel.gdriveProvider.oAuthManager.createChooseAccountIntent()
                                        accountChooserLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Erro ao abrir seletor de contas: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                            ) {
                                Icon(Icons.Default.AccountCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("CONECTAR GOOGLE DRIVE", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.fetchRemoteDriveBackups()
                                        viewModel.verifyDriveBackupsStatus()
                                        viewModel.refreshDriveAccountInfo()
                                        Toast.makeText(context, "Sincronizando com Google Drive...", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("SINCRONIZAR", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        viewModel.refreshDriveAccountInfo()
                                        viewModel.fetchRemoteDriveBackups()
                                        Toast.makeText(context, "Dados do Google Drive atualizados", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, primaryAccent)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("ATUALIZAR", color = primaryAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        selectedTab = 3
                                        viewModel.runCloudDiagnostic(CloudProviderType.GOOGLE_DRIVE)
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, DarkCardBorder)
                                ) {
                                    Text("TESTAR CONEXÃO", color = TextPrimary, fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val intent = viewModel.gdriveProvider.oAuthManager.createChooseAccountIntent()
                                            accountChooserLauncher.launch(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Erro ao abrir seletor de contas: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, DarkCardBorder)
                                ) {
                                    Text("RECONECTAR CONTA", color = TextPrimary, fontSize = 10.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        viewModel.disconnectGoogleDrive()
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f))
                                ) {
                                    Text("DESCONECTAR", color = StatusError, fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = onViewLogs,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Icon(Icons.Default.Article, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("VER LOGS", color = TextSecondary, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Sincronização Automática Card (Section 35.8)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = primaryAccent)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Sincronização Automática (Gratuita)", fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                                Text("Execução segura via WorkManager em segundo plano", fontSize = 11.sp, color = TextSecondary)

                                Spacer(modifier = Modifier.height(12.dp))

                                SyncSettingCheckbox(
                                    label = "Sincronização automática ativa",
                                    checked = syncSettings.isAutoSyncEnabled,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(isAutoSyncEnabled = it)) },
                                    accent = primaryAccent
                                )

                                SyncSettingCheckbox(
                                    label = "Somente Wi-Fi (Economizar dados móveis)",
                                    checked = syncSettings.wifiOnly,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(wifiOnly = it)) },
                                    accent = primaryAccent
                                )

                                SyncSettingCheckbox(
                                    label = "Somente durante carregamento da bateria",
                                    checked = syncSettings.requiresCharging,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(requiresCharging = it)) },
                                    accent = primaryAccent
                                )

                                SyncSettingCheckbox(
                                    label = "Sincronizar diariamente",
                                    checked = syncSettings.isDaily,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(isDaily = it)) },
                                    accent = primaryAccent
                                )

                                SyncSettingCheckbox(
                                    label = "Sincronizar imediatamente após backup",
                                    checked = syncSettings.syncImmediatelyAfterBackup,
                                    onCheckedChange = { viewModel.updateAutoSyncSettings(syncSettings.copy(syncImmediatelyAfterBackup = it)) },
                                    accent = primaryAccent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(30.dp))
                    }
                }

                1 -> {
                    // TAB 1: Backups no Google Drive (Nuvem)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Backups no Google Drive", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Listagem direta da pasta Rodin_Backup/Backups/", fontSize = 12.sp, color = TextSecondary)
                                }
                                IconButton(onClick = { viewModel.fetchRemoteDriveBackups() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = primaryAccent)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        if (remoteBackups.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.CloudQueue, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Nenhum backup encontrado no Google Drive.", color = TextSecondary)
                                        Text("Crie um backup e escolha 'Google Drive' como destino.", fontSize = 12.sp, color = TextMuted)
                                    }
                                }
                            }
                        } else {
                            items(remoteBackups, key = { it.id }) { item ->
                                RemoteBackupCard(
                                    item = item,
                                    primaryAccent = primaryAccent,
                                    onRestore = {
                                        viewModel.downloadAndRestoreFromGoogleDrive(item, null)
                                    },
                                    onDelete = {
                                        viewModel.deleteRemoteDriveBackup(item.id)
                                    }
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(40.dp))
                        }
                    }
                }

                2 -> {
                    // TAB 2: Sync Queue
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Fila de Transferência Ativa", fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Uploads resumíveis com priorização de itens", fontSize = 12.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        val queueItems = syncQueue
                        if (queueItems.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.DoneAll, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(40.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Fila vazia. Todos os backups estão sincronizados!", color = TextSecondary)
                                    }
                                }
                            }
                        } else {
                            items(queueItems, key = { it.id }) { item ->
                                SyncQueueCard(
                                    item = item,
                                    primaryAccent = primaryAccent,
                                    onPause = { viewModel.pauseQueueItem(item.id) },
                                    onResume = { viewModel.resumeQueueItem(item.id) },
                                    onPrioritize = { viewModel.prioritizeQueueItem(item.id) },
                                    onRetry = { viewModel.retryQueueItem(item.id) },
                                    onRemove = { viewModel.removeQueueItem(item.id) }
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(40.dp))
                        }
                    }
                }

                3 -> {
                    // TAB 3: Diagnóstico da Nuvem (9 Testes Individuais)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = { viewModel.runCloudDiagnostic(CloudProviderType.GOOGLE_DRIVE) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            enabled = !isDiagnosing
                        ) {
                            if (isDiagnosing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Executando Testes...")
                            } else {
                                Icon(Icons.Default.Speed, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Diagnosticar Conexão", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (diagnosticResults.isEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                border = BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Nenhum teste de diagnóstico executado",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Toque no botão 'Diagnosticar Conexão' acima para executar em tempo real a validação da sua conta e do Google Drive.",
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            for (res in diagnosticResults) {
                                DiagnosticResultCard(res)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteBackupCard(
    item: DriveFileMetadata,
    primaryAccent: Color,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(item.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(StatusSuccess.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("✓ Íntegro", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = StatusSuccess)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tamanho: %.2f MB • MD5: ${item.md5Checksum ?: "verificado"}".format(item.sizeBytes.toDouble() / (1024 * 1024)),
                fontSize = 11.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f))
                ) {
                    Text("Excluir", color = StatusError, fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onRestore,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restaurar da Nuvem", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SyncSettingCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = accent)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, fontSize = 13.sp, color = TextPrimary)
    }
}

@Composable
fun SyncQueueCard(
    item: CloudSyncQueueItem,
    primaryAccent: Color,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPrioritize: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusIcon = when (item.status) {
                        QueueStatus.COMPLETED -> Icons.Default.CheckCircle
                        QueueStatus.RUNNING -> Icons.Default.CloudUpload
                        QueueStatus.PAUSED -> Icons.Default.PauseCircle
                        QueueStatus.FAILED -> Icons.Default.Error
                        else -> Icons.Default.Schedule
                    }
                    val statusTint = when (item.status) {
                        QueueStatus.COMPLETED -> StatusSuccess
                        QueueStatus.RUNNING -> primaryAccent
                        QueueStatus.PAUSED -> StatusWarning
                        QueueStatus.FAILED -> StatusError
                        else -> TextSecondary
                    }
                    Icon(statusIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(item.backupTitle, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (item.status) {
                                QueueStatus.COMPLETED -> StatusSuccess.copy(alpha = 0.15f)
                                QueueStatus.RUNNING -> primaryAccent.copy(alpha = 0.15f)
                                QueueStatus.PAUSED -> StatusWarning.copy(alpha = 0.15f)
                                QueueStatus.FAILED -> StatusError.copy(alpha = 0.15f)
                                else -> DarkCardBorder
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.status.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (item.status) {
                            QueueStatus.COMPLETED -> StatusSuccess
                            QueueStatus.RUNNING -> primaryAccent
                            QueueStatus.PAUSED -> StatusWarning
                            QueueStatus.FAILED -> StatusError
                            else -> TextSecondary
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val progress = if (item.totalBytes > 0) (item.progressBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = primaryAccent,
                trackColor = DarkCardBorder
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "%.1f MB / %.1f MB".format(
                        item.progressBytes.toDouble() / (1024 * 1024),
                        item.totalBytes.toDouble() / (1024 * 1024)
                    ),
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Text(
                    text = "Prioridade: ${item.priority}",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            if (item.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(item.errorMessage, fontSize = 11.sp, color = StatusError)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (item.status == QueueStatus.RUNNING) {
                    TextButton(onClick = onPause) {
                        Text("Pausar", fontSize = 11.sp, color = StatusWarning)
                    }
                } else if (item.status == QueueStatus.PAUSED) {
                    TextButton(onClick = onResume) {
                        Text("Continuar", fontSize = 11.sp, color = primaryAccent)
                    }
                }

                if (item.status == QueueStatus.FAILED) {
                    TextButton(onClick = onRetry) {
                        Text("Tentar Novamente", fontSize = 11.sp, color = primaryAccent)
                    }
                }

                TextButton(onClick = onPrioritize) {
                    Text("Priorizar", fontSize = 11.sp, color = TextSecondary)
                }

                TextButton(onClick = onRemove) {
                    Text("Remover", fontSize = 11.sp, color = StatusError)
                }
            }
        }
    }
}

@Composable
fun DiagnosticResultCard(res: CloudDiagnosticResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (res.status == DiagnosticStepStatus.FAILED) StatusError.copy(alpha = 0.4f) else DarkCardBorder,
                RoundedCornerShape(10.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Linha superior: Ícone de status, Nome do passo (com weight) e Latência
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when (res.status) {
                    DiagnosticStepStatus.SUCCESS -> Icons.Default.CheckCircle
                    DiagnosticStepStatus.FAILED -> Icons.Default.Cancel
                    DiagnosticStepStatus.RUNNING -> Icons.Default.Sync
                    else -> Icons.Default.Schedule
                }
                val tint = when (res.status) {
                    DiagnosticStepStatus.SUCCESS -> StatusSuccess
                    DiagnosticStepStatus.FAILED -> StatusError
                    DiagnosticStepStatus.RUNNING -> AccentElectricCyan
                    else -> TextSecondary
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = res.stepName,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${res.latencyMs} ms",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // Descrição/Mensagem de erro em largura total com quebra natural de linha (sem compressão lateral)
            if (!res.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = res.errorMessage,
                    color = StatusError,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 26.dp),
                    softWrap = true
                )
            }
            if (!res.possibleCause.isNullOrBlank() && res.status == DiagnosticStepStatus.FAILED) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Causa: ${res.possibleCause}",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 26.dp),
                    softWrap = true
                )
            }
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
