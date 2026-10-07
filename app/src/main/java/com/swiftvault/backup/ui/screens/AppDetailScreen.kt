package com.swiftvault.backup.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftvault.backup.data.model.AppInfo
import com.swiftvault.backup.data.model.AppSelectionOptions
import com.swiftvault.backup.engine.CapabilityManager
import com.swiftvault.backup.engine.IconCacheManager
import com.swiftvault.backup.ui.components.cards.InfoRow
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppDetailScreen(
    app: AppInfo,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSelectFiles: (AppInfo) -> Unit,
    onViewBackup: () -> Unit
) {
    val context = LocalContext.current
    val iconCache = remember { IconCacheManager.getInstance(context) }
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)
    val activeMode by viewModel.activeMode.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0: Visão Geral / Backup, 1: Arquivos Relacionados

    // Granular checkboxes
    var includeApk by remember { mutableStateOf(true) }
    var includeSplits by remember { mutableStateOf(app.splitSourceDirs.isNotEmpty()) }
    var includeData by remember { mutableStateOf(false) }
    var includeSelectedFiles by remember { mutableStateOf(false) }
    var includeSettings by remember { mutableStateOf(false) }

    val appIconState = produceState<ImageBitmap?>(initialValue = null, key1 = app.packageName) {
        value = iconCache.getAppIcon(app.packageName)
    }

    val installDateStr = remember(app.lastInstallTime) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(app.lastInstallTime))
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Detalhes do Aplicativo",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Header with Real Icon
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(DarkBg),
                        contentAlignment = Alignment.Center
                    ) {
                        if (appIconState.value != null) {
                            Image(
                                bitmap = appIconState.value!!,
                                contentDescription = app.appName,
                                modifier = Modifier.size(60.dp)
                            )
                        } else {
                            CircularProgressIndicator(color = primaryAccent, modifier = Modifier.size(28.dp))
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Versão ${app.versionName} • ${"%.1f MB".format(app.sizeBytes.toDouble() / (1024 * 1024))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = primaryAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation Tabs (Visão Geral vs Arquivos)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurfaceVariant,
                contentColor = primaryAccent
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Backup Granular", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Arquivos Relacionados", fontWeight = FontWeight.SemiBold) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == 0) {
                // Technical Info Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Especificações Técnicas",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Versão Mínima", "Android ${app.minSdkVersion}")
                        InfoRow("Target SDK", "Android ${app.targetSdkVersion}")
                        InfoRow("Arquitetura", app.architecture)
                        InfoRow("Data de Instalação", installDateStr)
                        InfoRow("Splits / Módulos", "${app.splitSourceDirs.size} APKs adicionais")
                        InfoRow("Último Backup", app.lastBackupDate ?: "Nenhum")
                        InfoRow("Local do Backup", if (app.hasBackup) "Armazenamento Interno" else "—")
                        InfoRow("Criptografia", "AES-256-GCM Protegida", StatusSuccess)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Granular Backup Selection Options
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "BACKUP SELETIVO",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = primaryAccent
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // APK checkbox
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeApk,
                                onCheckedChange = { includeApk = it },
                                colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("APK Base", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Salva o pacote de instalação oficial", fontSize = 12.sp, color = TextSecondary)
                            }
                        }

                        // Split APKs checkbox
                        if (app.splitSourceDirs.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = includeSplits,
                                    onCheckedChange = { includeSplits = it },
                                    colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("APKs Divididos (Splits)", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text("${app.splitSourceDirs.size} módulos de densidade e ABI", fontSize = 12.sp, color = TextSecondary)
                                }
                            }
                        }

                        // App Data checkbox (with Android 17 capability check notice)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeData,
                                onCheckedChange = {
                                    if (activeMode == CapabilityManager.ExecutionMode.STANDARD) {
                                        includeData = false
                                    } else {
                                        includeData = it
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Dados do Aplicativo", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                val notice = if (activeMode == CapabilityManager.ExecutionMode.STANDARD) {
                                    "Indisponível no Android 17 sem Shizuku ou Root autorizado."
                                } else {
                                    "Acessível via privilégio ${activeMode.name}"
                                }
                                Text(
                                    text = notice,
                                    fontSize = 12.sp,
                                    color = if (activeMode == CapabilityManager.ExecutionMode.STANDARD) StatusWarning else StatusSuccess
                                )
                            }
                        }

                        // Related settings
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeSettings,
                                onCheckedChange = { includeSettings = it },
                                colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Configurações Relacionadas", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Permissões concedidas e metadados de sistema", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Button(
                    onClick = {
                        val options = AppSelectionOptions(
                            includeBaseApk = includeApk,
                            includeSplits = includeSplits,
                            includeData = includeData,
                            relatedSettings = includeSettings
                        )
                        viewModel.launchBackup(
                            title = "Backup de ${app.appName}",
                            selectedApps = listOf(Pair(app, options)),
                            selectedFiles = emptyList(),
                            includeSms = false,
                            includeCalls = false,
                            includeDns = false,
                            includeWallpaper = false
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fazer Backup", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onViewBackup,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Text("Ver Backup", color = TextPrimary)
                    }

                    OutlinedButton(
                        onClick = { onSelectFiles(app) },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Text("Selecionar Arquivos", color = TextPrimary)
                    }
                }
            } else {
                // Related Files Tab
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Arquivos da Aplicação",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Selecione pastas de mídia pública ou documentos gerados por ${app.appName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        val mockPaths = listOf(
                            "Android/media/${app.packageName}",
                            "Download/${app.appName}",
                            "Documents/${app.appName}"
                        )

                        for (path in mockPaths) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = AccentSunsetOrange)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = path, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(text = "Armazenamento Interno", color = TextMuted, fontSize = 11.sp)
                                }
                                Checkbox(
                                    checked = includeSelectedFiles,
                                    onCheckedChange = { includeSelectedFiles = it },
                                    colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
