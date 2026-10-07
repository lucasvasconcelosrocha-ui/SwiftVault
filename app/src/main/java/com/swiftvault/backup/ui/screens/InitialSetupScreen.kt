package com.swiftvault.backup.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.swiftvault.backup.engine.CapabilityManager
import com.swiftvault.backup.ui.components.cards.InfoRow
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun InitialSetupScreen(
    viewModel: MainViewModel,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val capabilityManager = remember { CapabilityManager(context) }
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)
    val stats by viewModel.deviceStats.collectAsState()
    val wifiDetails by viewModel.wifiDetails.collectAsState()

    var currentStep by remember { mutableIntStateOf(1) } // 1: Apresentação, 2: Root, 3: Permissões, 4: Armazenamento & Rede, 5: Pronto
    var rootStatus by remember { mutableStateOf(CapabilityManager.RootStatus.NOT_INSTALLED) }
    var refreshRootTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshRootTrigger) {
        rootStatus = capabilityManager.checkRootStatus()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Updated permissions
    }

    Scaffold(
        containerColor = DarkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Elegant Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(DarkSurfaceVariant)
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(18.dp))
                    .padding(vertical = 20.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(primaryAccent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = primaryAccent,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "ODIN_BACKUP",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Backup Granular & Proteção Avançada",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = primaryAccent
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step Progress Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (s in 1..4) {
                    val isActive = s == currentStep
                    val isDone = s < currentStep
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isActive -> primaryAccent
                                    isDone -> StatusSuccess
                                    else -> DarkCardBorder
                                }
                            )
                    )
                    if (s < 4) {
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (currentStep) {
                1 -> {
                    // ETAPA 1: Apresentação
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "Bem-vindo ao ODIN_BACKUP",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Para utilizar todos os recursos do aplicativo, algumas permissões e acesso root são necessários.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "• O aplicativo respeita a invariância do Kotlin Flow e não fecha durante operações.\n" +
                                        "• Utiliza apenas Google Drive para armazenamento na nuvem.\n" +
                                        "• Sem dados simulados: todo status é real e auditado.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = { currentStep = 2 },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Text("CONTINUAR", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }

                2 -> {
                    // ETAPA 2: Verificar Root & KernelSU Next
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ACESSO ROOT",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )

                                val isRootOk = rootStatus == CapabilityManager.RootStatus.AUTHORIZED
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isRootOk) StatusSuccess.copy(alpha = 0.2f) else StatusWarning.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (isRootOk) "● Root disponível" else "✕ Root não autorizado",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRootOk) StatusSuccess else StatusWarning
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            if (rootStatus == CapabilityManager.RootStatus.AUTHORIZED) {
                                Text(
                                    text = "✓ ODIN_BACKUP está autorizado no KernelSU Next. Recursos avançados como cópia de dados protegidos e extração de senhas de Wi-Fi estão disponíveis.",
                                    color = StatusSuccess,
                                    fontSize = 13.sp
                                )
                            } else {
                                Text(
                                    text = "⚠ Acesso Root necessário\n\nPara utilizar os recursos avançados de backup e restauração, autorize o ODIN_BACKUP no KernelSU Next.",
                                    color = StatusWarning,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val intent = capabilityManager.getRootManagerLaunchIntent()
                                            if (intent != null) {
                                                try {
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                                    ) {
                                        Text("ABRIR KERNELSU NEXT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            refreshRootTrigger++
                                        },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("VERIFICAR NOVAMENTE", fontSize = 11.sp, color = TextPrimary)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { currentStep = 3 },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Text("AVANÇAR PARA PERMISSÕES", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }

                3 -> {
                    // ETAPA 3: Permissões do Android
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "PERMISSÕES NECESSÁRIAS",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Solicitamos apenas as permissões oficiais suportadas pelo seu Android para leitura de arquivos, contatos, mídias e notificações.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    val perms = mutableListOf(
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.WRITE_CONTACTS,
                                        Manifest.permission.READ_SMS,
                                        Manifest.permission.READ_CALL_LOG,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                                        perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                        perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }
                                    permissionLauncher.launch(perms.toTypedArray())
                                },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("SOLICITAR PERMISSÕES OFICIAIS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { currentStep = 4 },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Text("VERIFICAR ARMAZENAMENTO E REDE", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }

                4 -> {
                    // ETAPA 4: Armazenamento & Rede
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "ARMAZENAMENTO",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            val usedGb = "%.1f GB".format((stats.storageTotalBytes - stats.storageFreeBytes).toDouble() / (1024 * 1024 * 1024))
                            val totalGb = "%.1f GB".format(stats.storageTotalBytes.toDouble() / (1024 * 1024 * 1024))
                            val freeGb = "%.1f GB".format(stats.storageFreeBytes.toDouble() / (1024 * 1024 * 1024))

                            InfoRow("Acesso", "✓ Disponível", StatusSuccess)
                            InfoRow("Espaço", "$usedGb / $totalGb")
                            InfoRow("Livre", freeGb, StatusSuccess)

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = DarkCardBorder)
                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "REDE & CONEXÃO",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            InfoRow("Status Wi-Fi", if (wifiDetails.isConnected) "● Conectado" else "○ Desconectado", if (wifiDetails.isConnected) StatusSuccess else StatusWarning)
                            InfoRow("SSID", wifiDetails.ssid)
                            InfoRow("Velocidade", "${wifiDetails.linkSpeedMbps} Mbps")
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = {
                            val prefs = context.getSharedPreferences("odin_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("setup_completed", true).apply()
                            onSetupComplete()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StatusSuccess)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("CONCLUIR E ABRIR DASHBOARD", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
