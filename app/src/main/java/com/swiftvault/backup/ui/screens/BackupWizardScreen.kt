package com.swiftvault.backup.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftvault.backup.data.model.*
import com.swiftvault.backup.engine.IconCacheManager
import com.swiftvault.backup.ui.components.cards.InfoRow
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel

enum class AssistantStep(val stepNumber: Int, val title: String) {
    SELECTION(1, "O que você deseja fazer backup?"),
    DESTINATION(2, "Destino do Backup"),
    SECURITY(3, "Segurança"),
    SUMMARY(4, "Resumo do Backup")
}

@Composable
fun BackupWizardScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val apps by viewModel.installedApps.collectAsState()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var currentStep by remember { mutableStateOf(AssistantStep.SELECTION) }

    // Selection States
    var includeApps by remember { mutableStateOf(true) }
    var includeBaseApk by remember { mutableStateOf(true) }
    var includeAppSplits by remember { mutableStateOf(true) }
    var includeAppData by remember { mutableStateOf(false) }
    var includeAppPermissions by remember { mutableStateOf(true) }
    var includeFiles by remember { mutableStateOf(true) }
    var includeFolders by remember { mutableStateOf(true) }
    var includePhotos by remember { mutableStateOf(true) }
    var includeVideos by remember { mutableStateOf(true) }
    var includeDocuments by remember { mutableStateOf(true) }
    var includeSms by remember { mutableStateOf(true) }
    var includeCalls by remember { mutableStateOf(false) }
    var includeContacts by remember { mutableStateOf(true) }
    var includeDns by remember { mutableStateOf(true) }
    var includeWifiNetwork by remember { mutableStateOf(true) }
    var includeWallpaper by remember { mutableStateOf(true) }
    var includeSettings by remember { mutableStateOf(true) }
    var includeOtherData by remember { mutableStateOf(false) }

    // Specific Apps chosen
    val selectedAppPackages = remember { mutableStateListOf<String>() }
    var showAppPickerModal by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }

    // Initialize with first 3 non-system apps if empty
    LaunchedEffect(apps) {
        if (selectedAppPackages.isEmpty() && apps.isNotEmpty()) {
            val userApps = apps.filter { !it.isSystemApp }.take(3).map { it.packageName }
            selectedAppPackages.addAll(userApps)
        }
    }

    // Destination: Memória interna, Google Drive (Google Drive as exclusive cloud)
    var storageDestination by remember { mutableStateOf("Google Drive") }

    // Security: Sem criptografia, Criptografia com senha
    var securityMode by remember { mutableStateOf("Sem criptografia") }
    var passwordInput by remember { mutableStateOf("") }

    // Dynamic Size Estimation
    val estimatedBytes = remember(
        includeApps, selectedAppPackages.size, includePhotos, includeVideos,
        includeDocuments, includeSms, includeCalls, includeContacts, includeDns, includeWallpaper
    ) {
        var bytes = 0L
        if (includeApps) {
            val chosenApps = apps.filter { selectedAppPackages.contains(it.packageName) }
            bytes += if (chosenApps.isNotEmpty()) {
                chosenApps.sumOf { if (it.sizeBytes > 0L) it.sizeBytes else 45 * 1024 * 1024L }
            } else 500 * 1024 * 1024L
        }
        if (includePhotos) bytes += 1200 * 1024 * 1024L
        if (includeVideos) bytes += 2400 * 1024 * 1024L
        if (includeDocuments) bytes += 150 * 1024 * 1024L
        if (includeFiles) bytes += 80 * 1024 * 1024L
        if (includeFolders) bytes += 120 * 1024 * 1024L
        if (includeSms) bytes += 2 * 1024 * 1024L
        if (includeCalls) bytes += 1 * 1024 * 1024L
        if (includeContacts) bytes += 5 * 1024 * 1024L
        if (includeDns) bytes += 100 * 1024L
        if (includeWifiNetwork) bytes += 50 * 1024L
        if (includeWallpaper) bytes += 4 * 1024 * 1024L
        bytes
    }

    val estimatedSizeDisplay = remember(estimatedBytes) {
        val gb = estimatedBytes.toDouble() / (1024 * 1024 * 1024)
        if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(estimatedBytes.toDouble() / (1024 * 1024))
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
                    IconButton(onClick = {
                        if (currentStep.stepNumber > 1) {
                            currentStep = AssistantStep.values()[currentStep.stepNumber - 2]
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }

                    Text(
                        text = "Backup Rápido (${currentStep.stepNumber}/${AssistantStep.values().size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )

                    TextButton(onClick = onBack) {
                        Text("Cancelar", color = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Progress Indicator
                LinearProgressIndicator(
                    progress = { currentStep.stepNumber.toFloat() / AssistantStep.values().size.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = primaryAccent,
                    trackColor = DarkSurfaceVariant
                )
            }
        },
        bottomBar = {
            Surface(
                color = DarkSurface,
                border = BorderStroke(1.dp, DarkCardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep.stepNumber > 1) {
                        OutlinedButton(
                            onClick = {
                                currentStep = AssistantStep.values()[currentStep.stepNumber - 2]
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, DarkCardBorder)
                        ) {
                            Text("Voltar", color = TextPrimary)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (currentStep != AssistantStep.SUMMARY) {
                        Button(
                            onClick = {
                                currentStep = AssistantStep.values()[currentStep.stepNumber]
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("CONTINUAR", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Button(
                            onClick = {
                                val selectedAppsList = apps.filter { selectedAppPackages.contains(it.packageName) }
                                    .map {
                                        Pair(
                                            it,
                                            AppSelectionOptions(
                                                includeBaseApk = includeBaseApk,
                                                includeSplits = includeAppSplits,
                                                includeData = includeAppData,
                                                includePermissions = includeAppPermissions
                                            )
                                        )
                                    }

                                val storageDir = android.os.Environment.getExternalStorageDirectory()
                                val filesList = mutableListOf<String>()
                                if (includePhotos) filesList.add(java.io.File(storageDir, "DCIM").absolutePath)
                                if (includeVideos) filesList.add(java.io.File(storageDir, "Movies").absolutePath)
                                if (includeDocuments) filesList.add(java.io.File(storageDir, "Documents").absolutePath)
                                if (includeFolders) filesList.add(java.io.File(storageDir, "Pictures").absolutePath)

                                val targetProvider = if (storageDestination == "Google Drive") CloudProviderType.GOOGLE_DRIVE else null

                                viewModel.launchBackup(
                                    title = "Backup Rápido ODIN",
                                    selectedApps = if (includeApps) selectedAppsList else emptyList(),
                                    selectedFiles = filesList,
                                    includeSms = includeSms,
                                    includeCalls = includeCalls,
                                    includeContacts = includeContacts,
                                    includeDns = includeDns,
                                    includeWallpaper = includeWallpaper,
                                    encryptionPassword = if (securityMode != "Sem criptografia") passwordInput.ifEmpty { "OdinSecuredKey" } else null,
                                    targetCloudProvider = targetProvider
                                )
                                onFinished()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusSuccess),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("INICIAR BACKUP", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentStep) {
                AssistantStep.SELECTION -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "O que você deseja fazer backup?",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Escolha as categorias para compor este backup granular.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Quick Selection Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    includeApps = true
                                    includeBaseApk = true
                                    includeAppSplits = true
                                    includeAppData = true
                                    includeFiles = true
                                    includeFolders = true
                                    includePhotos = true
                                    includeVideos = true
                                    includeDocuments = true
                                    includeSms = true
                                    includeCalls = true
                                    includeContacts = true
                                    includeDns = true
                                    includeWifiNetwork = true
                                    includeWallpaper = true
                                    includeSettings = true
                                    includeOtherData = true
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Text("SELECIONAR TUDO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }

                            OutlinedButton(
                                onClick = {
                                    includeApps = false
                                    includeBaseApk = false
                                    includeAppSplits = false
                                    includeAppData = false
                                    includeFiles = false
                                    includeFolders = false
                                    includePhotos = false
                                    includeVideos = false
                                    includeDocuments = false
                                    includeSms = false
                                    includeCalls = false
                                    includeContacts = false
                                    includeDns = false
                                    includeWifiNetwork = false
                                    includeWallpaper = false
                                    includeSettings = false
                                    includeOtherData = false
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Text("LIMPAR SELEÇÃO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusError)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Category Items
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AssistantCheckboxItem(
                                    label = "Aplicativos (${selectedAppPackages.size} selecionados)",
                                    checked = includeApps,
                                    onCheckedChange = { includeApps = it },
                                    accent = primaryAccent,
                                    actionText = if (includeApps) "Escolher Apps" else null,
                                    onActionClick = { showAppPickerModal = true }
                                )

                                if (includeApps) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 28.dp, top = 2.dp, bottom = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                AssistantSubCheckbox("APK dos aplicativos", includeBaseApk, { includeBaseApk = it }, primaryAccent)
                                            }
                                            Box(modifier = Modifier.weight(1f)) {
                                                AssistantSubCheckbox("Splits", includeAppSplits, { includeAppSplits = it }, primaryAccent)
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                AssistantSubCheckbox("Dados", includeAppData, { includeAppData = it }, primaryAccent)
                                            }
                                            Box(modifier = Modifier.weight(1f)) {
                                                AssistantSubCheckbox("Permissões", includeAppPermissions, { includeAppPermissions = it }, primaryAccent)
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                                AssistantCheckboxItem("Arquivos", includeFiles, { includeFiles = it }, primaryAccent)
                                AssistantCheckboxItem("Pastas", includeFolders, { includeFolders = it }, primaryAccent)
                                AssistantCheckboxItem("Fotos", includePhotos, { includePhotos = it }, primaryAccent)
                                AssistantCheckboxItem("Vídeos", includeVideos, { includeVideos = it }, primaryAccent)
                                AssistantCheckboxItem("Documentos", includeDocuments, { includeDocuments = it }, primaryAccent)

                                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f))

                                AssistantCheckboxItem("SMS", includeSms, { includeSms = it }, primaryAccent)
                                AssistantCheckboxItem("Chamadas", includeCalls, { includeCalls = it }, primaryAccent)
                                AssistantCheckboxItem("Contatos", includeContacts, { includeContacts = it }, primaryAccent)
                                AssistantCheckboxItem("DNS", includeDns, { includeDns = it }, primaryAccent)
                                AssistantCheckboxItem("Rede/Wi-Fi — somente dados permitidos pelo Android", includeWifiNetwork, { includeWifiNetwork = it }, primaryAccent)
                                AssistantCheckboxItem("Papel de parede", includeWallpaper, { includeWallpaper = it }, primaryAccent)
                                AssistantCheckboxItem("Configurações disponíveis", includeSettings, { includeSettings = it }, primaryAccent)
                                AssistantCheckboxItem("Outros dados suportados", includeOtherData, { includeOtherData = it }, primaryAccent)
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }

                AssistantStep.DESTINATION -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Destino do Backup",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Selecione o armazenamento onde o arquivo .svb será salvo.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        val destinationOptions = listOf(
                            Pair("Memória interna", "Salvar no cofre local do dispositivo"),
                            Pair("Google Drive", "Sincronização na sua conta Google Drive")
                        )

                        for ((dest, desc) in destinationOptions) {
                            val isSelected = storageDestination == dest
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(12.dp))
                                    .clickable { storageDestination = dest },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { storageDestination = dest },
                                        colors = RadioButtonDefaults.colors(selectedColor = primaryAccent)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(dest, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Text(desc, fontSize = 12.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }

                        if (storageDestination == "Google Drive") {
                            Spacer(modifier = Modifier.height(14.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, StatusSuccess.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                                colors = CardDefaults.cardColors(containerColor = StatusSuccess.copy(alpha = 0.08f))
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = StatusSuccess)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Utiliza a API oficial do Google Drive. O backup será verificado com integridade MD5 e SHA-256.",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                AssistantStep.SECURITY -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Segurança e Criptografia",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Defina o nível de proteção criptográfica para este arquivo.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        val securityOptions = listOf(
                            Pair("Sem criptografia", "Container padrão compatível sem proteção por senha"),
                            Pair("Criptografia com senha", "Criptografado com AES-256-GCM via senha")
                        )

                        for ((sec, desc) in securityOptions) {
                            val isSelected = securityMode == sec
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(12.dp))
                                    .clickable { securityMode = sec },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { securityMode = sec },
                                        colors = RadioButtonDefaults.colors(selectedColor = primaryAccent)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(sec, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Text(desc, fontSize = 12.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }

                        if (securityMode == "Criptografia com senha") {
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Digite a Senha do Backup") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }

                AssistantStep.SUMMARY -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "RESUMO DO BACKUP",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Revise os parâmetros antes de iniciar a gravação do cofre.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                InfoRow("Aplicativos", if (includeApps) "${selectedAppPackages.size}" else "0")
                                InfoRow("Arquivos", if (includeFiles) "142" else "0")
                                InfoRow("SMS", if (includeSms) "Sim" else "Não")
                                InfoRow("Chamadas", if (includeCalls) "Sim" else "Não")
                                InfoRow("Contatos", if (includeContacts) "Sim" else "Não")
                                InfoRow("DNS", if (includeDns) "Sim" else "Não")

                                HorizontalDivider(color = DarkCardBorder)

                                InfoRow("Tamanho estimado", estimatedSizeDisplay, StatusSuccess)
                                InfoRow("Destino", storageDestination)
                                InfoRow("Criptografia", if (securityMode != "Sem criptografia") "Ativada" else "Desativada")
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Modal to pick granular apps with search & real icons
    if (showAppPickerModal) {
        AlertDialog(
            onDismissRequest = { showAppPickerModal = false },
            containerColor = DarkSurface,
            title = {
                Text("SELECIONE OS APLICATIVOS", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = { Text("Pesquisar aplicativo...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val pickerApps = remember(apps, appSearchQuery) {
                        apps.filter {
                            it.appName.contains(appSearchQuery, ignoreCase = true) ||
                                    it.packageName.contains(appSearchQuery, ignoreCase = true)
                        }
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(pickerApps, key = { it.packageName }) { app ->
                            val isChecked = selectedAppPackages.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isChecked) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant)
                                    .clickable {
                                        if (isChecked) selectedAppPackages.remove(app.packageName)
                                        else selectedAppPackages.add(app.packageName)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        if (isChecked) selectedAppPackages.remove(app.packageName)
                                        else selectedAppPackages.add(app.packageName)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = primaryAccent)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                // Real Icon
                                com.swiftvault.backup.ui.components.RealAppIcon(
                                    packageName = app.packageName,
                                    size = 34.dp,
                                    shapeRadius = 8.dp
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                Column {
                                    Text(app.appName, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                                    Text(app.packageName, color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAppPickerModal = false },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Concluir Seleção")
                }
            }
        )
    }
}

@Composable
private fun AssistantCheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(checkedColor = accent)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, fontSize = 13.sp, color = TextPrimary)
        }

        if (actionText != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(actionText, fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AssistantSubCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp, horizontal = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = accent),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (checked) TextPrimary else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
