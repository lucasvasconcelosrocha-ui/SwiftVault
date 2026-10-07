package com.swiftvault.backup.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftvault.backup.engine.DnsManager
import com.swiftvault.backup.ui.components.cards.InfoRow
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel

@Composable
fun DnsSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val dnsConfig by viewModel.dnsConfig.collectAsState()
    val wifiDetails by viewModel.wifiDetails.collectAsState()
    val dataUsage by viewModel.dataUsageStats.collectAsState()

    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var selectedTab by remember { mutableStateOf(0) } // 0: DNS, 1: Wi-Fi, 2: Consumo
    var usageFilter by remember { mutableStateOf(0) } // 0: Hoje, 1: Esta Semana, 2: Este Mês
    var copiedNotice by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshNetworkAndWifi()
    }

    val formattedSummary = remember(dnsConfig) {
        DnsManager(context).formatDnsBackupSummary(dnsConfig)
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
                        text = "DNS & Rede",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DarkSurfaceVariant,
                    contentColor = primaryAccent
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("DNS Privado", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Wi-Fi Conectado", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Consumo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
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
            when (selectedTab) {
                0 -> {
                    // TAB 0: DNS PRIVADO & REDE
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Dns, contentDescription = null, tint = AccentSunsetOrange)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Status do DNS Privado", fontWeight = FontWeight.Bold, color = TextPrimary)
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (dnsConfig.isActive) StatusSuccess.copy(alpha = 0.2f) else StatusWarning.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (dnsConfig.isActive) "● Ativo" else "○ Inativo",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (dnsConfig.isActive) StatusSuccess else StatusWarning
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            InfoRow("Modo Detectado", dnsConfig.mode)
                            InfoRow("Hostname do Provedor", dnsConfig.hostname ?: "Automático / Nenhum")
                            InfoRow("Servidores Ativos", if (dnsConfig.dnsServers.isNotEmpty()) dnsConfig.dnsServers.joinToString(", ") else "DNS Padrão do Roteador")
                            InfoRow("Interface Ativa", dnsConfig.interfaceName ?: "wlan0 / rede móvel")
                            InfoRow("Status VPN", if (dnsConfig.isVpnActive) "Ativa" else "Inativa")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Export Preview
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Visualização da Exportação", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkBg)
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = formattedSummary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = AccentElectricCyan
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Buttons
                    Button(
                        onClick = {
                            viewModel.launchBackup(
                                title = "Backup de Configuração DNS",
                                selectedApps = emptyList(),
                                selectedFiles = emptyList(),
                                includeSms = false,
                                includeCalls = false,
                                includeDns = true,
                                includeWallpaper = false
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fazer Backup do DNS", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                viewModel.copyDnsHost()
                                copiedNotice = true
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (copiedNotice) "Copiado!" else "Copiar Host", color = TextPrimary, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(viewModel.getOpenDnsSettingsIntent())
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = primaryAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Configurações", color = primaryAccent, fontSize = 12.sp)
                        }
                    }
                }

                1 -> {
                    // TAB 1: REDE WI-FI ATUAL (Section 7)
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
                                    Icon(Icons.Default.Wifi, contentDescription = null, tint = AccentEmeraldGreen, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Rede Wi-Fi", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (wifiDetails.isConnected) StatusSuccess.copy(alpha = 0.2f) else StatusWarning.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (wifiDetails.isConnected) "● Conectado" else "○ Desconectado",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (wifiDetails.isConnected) StatusSuccess else StatusWarning
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            InfoRow("Nome da rede", wifiDetails.ssid)
                            InfoRow("Status", if (wifiDetails.isConnected) "● Conectado" else "○ Desconectado", if (wifiDetails.isConnected) StatusSuccess else StatusWarning)
                            InfoRow("Tipo", "Wi-Fi")
                            InfoRow("Velocidade do link", "${wifiDetails.linkSpeedMbps} Mbps")
                            InfoRow("Frequência", wifiDetails.frequencyGhz)
                            InfoRow("Endereço IP", wifiDetails.ipAddress)
                            InfoRow("Gateway", wifiDetails.gateway)
                            val privateDns = wifiDetails.privateDnsServerName
                            if (privateDns != null) {
                                InfoRow("DNS Privado", privateDns)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 22. SENHA DO WI-FI
                    val isPasswordRevealed = wifiDetails.wifiPasswordStatus.isNotBlank() &&
                            !wifiDetails.wifiPasswordStatus.contains("Indisponível", ignoreCase = true) &&
                            !wifiDetails.wifiPasswordStatus.contains("Desconectado", ignoreCase = true)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                1.dp,
                                if (isPasswordRevealed) StatusSuccess.copy(alpha = 0.3f) else StatusWarning.copy(alpha = 0.3f),
                                RoundedCornerShape(14.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isPasswordRevealed) StatusSuccess.copy(alpha = 0.08f) else StatusWarning.copy(alpha = 0.08f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isPasswordRevealed) Icons.Default.LockOpen else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isPasswordRevealed) StatusSuccess else StatusWarning,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Senha do Wi-Fi", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            if (isPasswordRevealed) {
                                Text(
                                    text = wifiDetails.wifiPasswordStatus,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusSuccess,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Senha obtida legitimamente via acesso root autorizado no WifiConfigStore.",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            } else {
                                Text(
                                    text = "Indisponível",
                                    fontWeight = FontWeight.Bold,
                                    color = StatusWarning,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "O Android não disponibiliza esta informação para esta operação.",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 21. REDES WI-FI CONHECIDAS / SALVAS
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.WifiFind, contentDescription = null, tint = AccentEmeraldGreen, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("REDES WI-FI CONHECIDAS", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                }

                                if (wifiDetails.savedNetworks.isNotEmpty()) {
                                    Text("${wifiDetails.savedNetworks.size} salvas", fontSize = 11.sp, color = primaryAccent, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (wifiDetails.savedNetworks.isNotEmpty()) {
                                for (saved in wifiDetails.savedNetworks) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("● ${saved.ssid}", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                            Text(saved.securityType, fontSize = 11.sp, color = TextSecondary)
                                        }
                                        if (saved.password != null) {
                                            Text("Senha: ${saved.password}", fontSize = 12.sp, color = StatusSuccess, fontWeight = FontWeight.SemiBold)
                                        }
                                        Text("Última conexão: ${saved.lastConnected}", fontSize = 11.sp, color = TextMuted)
                                        HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = DarkCardBorder.copy(alpha = 0.5f))
                                    }
                                }
                            } else {
                                Text(
                                    text = "Nenhuma rede salva disponibilizada diretamente pelo Android.\nPara inspecionar o arquivo WifiConfigStore do sistema, autorize o acesso root no KernelSU Next.",
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                2 -> {
                    // TAB 2: CONSUMO DE INTERNET (Section 8)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val filterNames = listOf("Hoje", "Esta semana", "Este mês")
                        for ((index, name) in filterNames.withIndex()) {
                            val isSelected = usageFilter == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) primaryAccent else DarkSurfaceVariant)
                                    .clickable { usageFilter = index }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = name,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else TextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val activePeriod = when (usageFilter) {
                        1 -> dataUsage.thisWeek
                        2 -> dataUsage.thisMonth
                        else -> dataUsage.today
                    }

                    fun formatBytes(bytes: Long): String {
                        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
                        return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text("CONSUMO DE INTERNET", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("↓ Download", color = StatusSuccess, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(formatBytes(activePeriod.downloadBytes), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 18.sp)
                                }
                                Column {
                                    Text("↑ Upload", color = AccentElectricCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(formatBytes(activePeriod.uploadBytes), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 18.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = DarkCardBorder)
                            Spacer(modifier = Modifier.height(14.dp))

                            InfoRow("Total de Dados", formatBytes(activePeriod.totalBytes), StatusInfo)
                            InfoRow("Tráfego Wi-Fi", formatBytes(activePeriod.wifiBytes))
                            InfoRow("Tráfego Móvel (4G/5G)", formatBytes(activePeriod.mobileBytes))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Visual Consumption Chart
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Consumo de Dados (Semanal)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(16.dp))

                            // Chart Canvas
                            Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                                val points = dataUsage.weeklyHistory
                                val maxVal = points.maxOfOrNull { it.second } ?: 1L
                                val widthStep = size.width / (points.size.coerceAtLeast(1))
                                val path = Path()

                                for ((i, pair) in points.withIndex()) {
                                    val x = (i * widthStep) + (widthStep / 2)
                                    val y = size.height - ((pair.second.toFloat() / maxVal.toFloat()) * (size.height - 20f))

                                    if (i == 0) path.moveTo(x, y)
                                    else path.lineTo(x, y)

                                    drawCircle(
                                        color = primaryAccent,
                                        radius = 4.dp.toPx(),
                                        center = Offset(x, y)
                                    )
                                }

                                drawPath(
                                    path = path,
                                    color = primaryAccent,
                                    style = Stroke(width = 3.dp.toPx())
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                for (pair in dataUsage.weeklyHistory) {
                                    Text(pair.first, fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Dados calculados com precisão a partir das estatísticas oficiais de rede do aparelho (TrafficStats).",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
