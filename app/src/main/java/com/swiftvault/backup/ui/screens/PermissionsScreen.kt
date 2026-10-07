package com.swiftvault.backup.ui.screens

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.core.content.ContextCompat
import com.swiftvault.backup.engine.CapabilityManager
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

enum class PermissionStatusType(val label: String, val isGood: Boolean) {
    PERMITTED("✓ Permitido", true),
    AVAILABLE("✓ Disponível", true),
    AUTHORIZED("✓ Autorizado", true),
    REQUIRED("✕ Necessário", false),
    DENIED("✕ Negado", false),
    OPTIONAL("○ Opcional", false),
    NOT_AVAILABLE("— Não disponível no Android atual", false)
}

data class PermissionItem(
    val title: String,
    val description: String,
    val status: PermissionStatusType,
    val isRoot: Boolean = false,
    val isSpecial: Boolean = false,
    val permissions: List<String> = emptyList(),
    val onSpecialAction: (() -> Unit)? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val capabilityManager = remember { CapabilityManager(context) }
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var rootGranted by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        rootGranted = capabilityManager.isRootGranted()
    }

    // Permission launcher for standard runtime permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTrigger++
    }

    // Evaluate permissions
    fun checkPerm(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAllFiles(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun hasUsageStats(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    val permissionsList = remember(refreshTrigger, rootGranted) {
        val list = mutableListOf<PermissionItem>()

        // 1. Armazenamento
        val storageStatus = if (hasAllFiles()) PermissionStatusType.PERMITTED else PermissionStatusType.REQUIRED
        list.add(
            PermissionItem(
                title = "Armazenamento",
                description = "Necessário para ler e gravar arquivos e containers de backup (.svb).",
                status = storageStatus,
                isSpecial = true,
                onSpecialAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        }
                    } else {
                        permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }
                }
            )
        )

        // 2. Fotos e Vídeos
        val photosStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkPerm(Manifest.permission.READ_MEDIA_IMAGES) && checkPerm(Manifest.permission.READ_MEDIA_VIDEO))
                PermissionStatusType.PERMITTED
            else
                PermissionStatusType.OPTIONAL
        } else {
            if (hasAllFiles()) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL
        }
        val mediaPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else emptyList()
        list.add(
            PermissionItem(
                title = "Fotos e vídeos",
                description = "Permite incluir fotos e mídias no backup de dados do aparelho.",
                status = photosStatus,
                permissions = mediaPerms
            )
        )

        // 3. Contatos
        val contactsStatus = if (checkPerm(Manifest.permission.READ_CONTACTS) && checkPerm(Manifest.permission.WRITE_CONTACTS))
            PermissionStatusType.PERMITTED
        else
            PermissionStatusType.OPTIONAL
        list.add(
            PermissionItem(
                title = "Contatos",
                description = "Backup e restauração completa da agenda de contatos no formato SVB.",
                status = contactsStatus,
                permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            )
        )

        // 4. SMS
        val smsStatus = if (checkPerm(Manifest.permission.READ_SMS)) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL
        list.add(
            PermissionItem(
                title = "SMS",
                description = "Permite salvar e restaurar mensagens de texto SMS.",
                status = smsStatus,
                permissions = listOf(Manifest.permission.READ_SMS)
            )
        )

        // 5. Chamadas
        val callsStatus = if (checkPerm(Manifest.permission.READ_CALL_LOG)) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL
        list.add(
            PermissionItem(
                title = "Chamadas",
                description = "Permite salvar histórico do registro de chamadas telefônicas.",
                status = callsStatus,
                permissions = listOf(Manifest.permission.READ_CALL_LOG)
            )
        )

        // 6. Notificações
        val notifStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkPerm(Manifest.permission.POST_NOTIFICATIONS)) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL
        } else {
            PermissionStatusType.AVAILABLE
        }
        list.add(
            PermissionItem(
                title = "Notificações",
                description = "Exibe o progresso em segundo plano e alertas de sincronização.",
                status = notifStatus,
                permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            )
        )

        // 7. Wi-Fi / Rede
        val wifiStatus = if (checkPerm(Manifest.permission.ACCESS_FINE_LOCATION)) PermissionStatusType.AVAILABLE else PermissionStatusType.OPTIONAL
        list.add(
            PermissionItem(
                title = "Wi-Fi / Localização da Rede",
                description = "Necessário pelo Android para identificar o SSID da rede Wi-Fi conectada.",
                status = wifiStatus,
                permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_NETWORK_STATE)
            )
        )

        // 8. Estatísticas de Consumo
        val usageStatus = if (hasUsageStats()) PermissionStatusType.PERMITTED else PermissionStatusType.OPTIONAL
        list.add(
            PermissionItem(
                title = "Acesso de Uso (Consumo de Dados)",
                description = "Permite obter histórico de consumo de internet (Hoje, 7 dias, Mês) via NetworkStatsManager.",
                status = usageStatus,
                isSpecial = true,
                onSpecialAction = {
                    try {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
            )
        )

        // 9. Root (KernelSU Next)
        val rootStatus = if (rootGranted) PermissionStatusType.AUTHORIZED else PermissionStatusType.OPTIONAL
        list.add(
            PermissionItem(
                title = "Acesso Root (KernelSU Next)",
                description = "Necessário para backup granular de dados protegidos e extração de senhas de Wi-Fi salvas.",
                status = rootStatus,
                isRoot = true,
                onSpecialAction = {
                    val intent = capabilityManager.getRootManagerLaunchIntent()
                    if (intent != null) {
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                }
            )
        )

        list
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
                        text = "Permissões",
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
            Text(
                text = "Controle de Acessos e Privilégios",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "O ODIN_BACKUP opera de forma granular. Funções não autorizadas são desativadas de forma transparente sem falhas.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Main Action Button: CONCEDER PERMISSÕES
            Button(
                onClick = {
                    val standardToRequest = permissionsList
                        .filter { !it.isRoot && !it.isSpecial && !it.status.isGood && it.permissions.isNotEmpty() }
                        .flatMap { it.permissions }
                        .distinct()

                    if (standardToRequest.isNotEmpty()) {
                        permissionLauncher.launch(standardToRequest.toTypedArray())
                    } else {
                        // Open app settings if all standard were prompted
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
            ) {
                Icon(imageVector = Icons.Default.Security, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("CONCEDER PERMISSÕES", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // List of permissions
            for (perm in permissionsList) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
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
                            Text(
                                text = perm.title,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )

                            val badgeColor = when (perm.status) {
                                PermissionStatusType.PERMITTED,
                                PermissionStatusType.AVAILABLE,
                                PermissionStatusType.AUTHORIZED -> StatusSuccess
                                PermissionStatusType.REQUIRED -> StatusError
                                PermissionStatusType.DENIED -> StatusError
                                PermissionStatusType.OPTIONAL -> StatusWarning
                                PermissionStatusType.NOT_AVAILABLE -> TextMuted
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(badgeColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = perm.status.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = perm.description,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )

                        if (!perm.status.isGood) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (perm.isRoot) {
                                    OutlinedButton(
                                        onClick = {
                                            perm.onSpecialAction?.invoke()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("ABRIR KERNELSU NEXT", fontSize = 11.sp, color = primaryAccent)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                rootGranted = capabilityManager.isRootGranted()
                                            }
                                        }
                                    ) {
                                        Text("VERIFICAR NOVAMENTE", fontSize = 11.sp, color = TextPrimary)
                                    }
                                } else if (perm.isSpecial && perm.onSpecialAction != null) {
                                    OutlinedButton(
                                        onClick = { perm.onSpecialAction.invoke() },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Configurar", fontSize = 11.sp, color = primaryAccent)
                                    }
                                } else if (perm.permissions.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = { permissionLauncher.launch(perm.permissions.toTypedArray()) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Permitir", fontSize = 11.sp, color = primaryAccent)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
