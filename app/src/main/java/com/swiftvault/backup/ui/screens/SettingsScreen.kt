package com.swiftvault.backup.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftvault.backup.data.model.ThemeAccent
import com.swiftvault.backup.engine.CapabilityManager
import com.swiftvault.backup.ui.components.cards.InfoRow
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel
import com.swiftvault.backup.updater.UpdateState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToDns: () -> Unit,
    onNavigateToInitialSetup: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val capabilityManager = remember { CapabilityManager(context) }
    val currentAccent by viewModel.themeAccent.collectAsState()
    val activeMode by viewModel.activeMode.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var rootStatus by remember { mutableStateOf(CapabilityManager.RootStatus.NOT_INSTALLED) }

    val isAutoCheck by viewModel.isAutoUpdateCheckEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val installedVersionName = remember { viewModel.appUpdateManager.getInstalledVersionName() }
    val installedVersionCode = remember { viewModel.appUpdateManager.getInstalledVersionCode() }

    LaunchedEffect(Unit) {
        rootStatus = capabilityManager.checkRootStatus()
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Configurações",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 1. TEMA VISUAL
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tema Visual & Destaques", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (accent in ThemeAccent.values()) {
                            val color = getAccentColor(accent)
                            val isSelected = accent == currentAccent
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.setThemeAccent(accent) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. ROOT & KERNELSU NEXT
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Root & KernelSU Next", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                        val isRootGranted = rootStatus == CapabilityManager.RootStatus.AUTHORIZED
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isRootGranted) StatusSuccess.copy(alpha = 0.15f) else StatusWarning.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (isRootGranted) "● Root Autorizado" else "✕ Não Autorizado",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRootGranted) StatusSuccess else StatusWarning
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "O ODIN_BACKUP nunca tenta obter root automaticamente ou burlar proteções. Ele solicita autorização explícita via KernelSU Next para recursos avançados.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val intent = capabilityManager.getRootManagerLaunchIntent()
                                if (intent != null) {
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("ABRIR KERNELSU NEXT", fontSize = 10.sp, color = primaryAccent, fontWeight = FontWeight.Bold)
                        }

                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    rootStatus = capabilityManager.checkRootStatus()
                                }
                            }
                        ) {
                            Text("Verificar", fontSize = 12.sp, color = TextPrimary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. SEÇÕES RÁPIDAS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    SettingNavRow(
                        title = "Permissões do Sistema",
                        subtitle = "Controle de acessos a arquivos, contatos e chamadas",
                        icon = Icons.Default.Security,
                        onClick = onNavigateToPermissions
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SettingNavRow(
                        title = "DNS & Rede",
                        subtitle = "Verificação de DNS Privado, redes Wi-Fi e consumo",
                        icon = Icons.Default.Dns,
                        onClick = onNavigateToDns
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SettingNavRow(
                        title = "Assistente de Configuração Inicial",
                        subtitle = "Executar novamente o fluxo de boas-vindas e checagem",
                        icon = Icons.Default.AutoFixHigh,
                        onClick = onNavigateToInitialSetup
                    )
                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    SettingNavRow(
                        title = "Logs do Sistema",
                        subtitle = "Histórico de eventos, diagnósticos e erros",
                        icon = Icons.Default.Terminal,
                        onClick = onNavigateToLogs
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. ATUALIZAÇÕES DO APLICATIVO
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.SystemUpdate, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Atualizações do Aplicativo", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Verificar ao abrir", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text("Consulta a última versão no GitHub ao iniciar o app", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = isAutoCheck,
                            onCheckedChange = { viewModel.toggleAutoUpdateCheck(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = primaryAccent)
                        )
                    }

                    HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Versão Atual: v$installedVersionName", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text("Build $installedVersionCode", fontSize = 11.sp, color = TextSecondary)
                        }
                        Button(
                            onClick = { viewModel.checkForUpdateManual() },
                            enabled = updateState !is UpdateState.Checking,
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            if (updateState is UpdateState.Checking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                            } else {
                                Text("Verificar agora", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (updateState is UpdateState.UpToDate) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "✓ SwiftVault já está na versão mais recente (v$installedVersionName).",
                            color = StatusSuccess,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (updateState is UpdateState.Error) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = (updateState as UpdateState.Error).message,
                            color = StatusError,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. INFORMAÇÕES DO APLICATIVO
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SwiftVault", fontWeight = FontWeight.ExtraBold, color = TextPrimary, fontSize = 15.sp)
                    Text("Versão $installedVersionName — API 35 (Android 17 Ready)", fontSize = 12.sp, color = primaryAccent, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(10.dp))
                    InfoRow("Motor Criptográfico", "AES-256-GCM Hardware-Backed", StatusSuccess)
                    InfoRow("Validação de Integridade", "SHA-256 e MD5 Hash", StatusSuccess)
                    InfoRow("Nuvem Suportada", "Exclusivamente Google Drive", AccentElectricCyan)
                    InfoRow("Invariância de Fluxo", "Kotlin channelFlow Auditado", StatusSuccess)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SettingNavRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(imageVector = icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                Text(subtitle, fontSize = 11.sp, color = TextSecondary)
            }
        }
        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
    }
}
