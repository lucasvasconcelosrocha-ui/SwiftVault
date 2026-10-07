package com.swiftvault.backup.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftvault.backup.data.model.*
import com.swiftvault.backup.ui.components.cards.InfoRow
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel

enum class CustomStep(val stepNumber: Int, val title: String) {
    APPS(1, "Aplicativos"),
    FILES(2, "Arquivos e Pastas"),
    COMMUNICATIONS(3, "SMS / Chamadas / Contatos"),
    SYSTEM_DNS(4, "DNS / Configurações"),
    DESTINATION(5, "Destino"),
    SECURITY(6, "Segurança"),
    SUMMARY(7, "Resumo"),
    EXECUTE(8, "Executar")
}

@Composable
fun CustomBackupWizardScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit
) {
    val apps by viewModel.installedApps.collectAsState()
    val backupProgress by viewModel.backupProgress.collectAsState()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var currentStep by remember { mutableStateOf(CustomStep.APPS) }

    // Step 1: Apps
    val selectedAppPackages = remember { mutableStateListOf<String>() }
    var appSearch by remember { mutableStateOf("") }
    var includeApk by remember { mutableStateOf(true) }
    var includeData by remember { mutableStateOf(false) }

    // Step 2: Files & Folders
    var includeCamera by remember { mutableStateOf(true) }
    var includeDocuments by remember { mutableStateOf(true) }
    var includeDownloads by remember { mutableStateOf(false) }
    var includeMusic by remember { mutableStateOf(false) }

    // Step 3: Communications
    var includeSms by remember { mutableStateOf(true) }
    var includeCalls by remember { mutableStateOf(false) }
    var includeContacts by remember { mutableStateOf(true) }

    // Step 4: System & DNS
    var includeDns by remember { mutableStateOf(true) }
    var includeWifiSettings by remember { mutableStateOf(true) }
    var includeWallpaper by remember { mutableStateOf(true) }

    // Step 5: Destination
    var selectedDestination by remember { mutableStateOf("Memória interna") } // "Memória interna", "Google Drive"

    // Step 6: Security
    var encryptionMode by remember { mutableStateOf("Sem criptografia") } // "Sem criptografia", "Criptografia com senha"
    var password by remember { mutableStateOf("") }

    val estimatedBytes = remember(
        selectedAppPackages.size, includeCamera, includeDocuments, includeDownloads,
        includeMusic, includeSms, includeCalls, includeContacts, includeDns
    ) {
        var bytes = 0L
        val chosenApps = apps.filter { selectedAppPackages.contains(it.packageName) }
        bytes += chosenApps.sumOf { if (it.sizeBytes > 0L) it.sizeBytes else 45 * 1024 * 1024L }
        if (includeCamera) bytes += 1500 * 1024 * 1024L
        if (includeDocuments) bytes += 200 * 1024 * 1024L
        if (includeDownloads) bytes += 500 * 1024 * 1024L
        if (includeMusic) bytes += 400 * 1024 * 1024L
        if (includeSms) bytes += 2 * 1024 * 1024L
        if (includeCalls) bytes += 1 * 1024 * 1024L
        if (includeContacts) bytes += 5 * 1024 * 1024L
        if (includeDns) bytes += 100 * 1024L
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
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = {
                        if (currentStep.stepNumber > 1) {
                            currentStep = CustomStep.values()[currentStep.stepNumber - 2]
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Novo Backup Personalizado",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Etapa ${currentStep.stepNumber} de 8: ${currentStep.title}",
                            fontSize = 11.sp,
                            color = primaryAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    TextButton(onClick = onBack) {
                        Text("Cancelar", color = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { currentStep.stepNumber.toFloat() / 8f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = primaryAccent,
                    trackColor = DarkSurfaceVariant
                )
            }
        },
        bottomBar = {
            if (currentStep != CustomStep.EXECUTE) {
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
                                    currentStep = CustomStep.values()[currentStep.stepNumber - 2]
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Voltar", color = TextPrimary)
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        if (currentStep != CustomStep.SUMMARY) {
                            Button(
                                onClick = {
                                    currentStep = CustomStep.values()[currentStep.stepNumber]
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Avançar", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        } else {
                            Button(
                                onClick = {
                                    currentStep = CustomStep.EXECUTE

                                    val selectedAppsList = apps.filter { selectedAppPackages.contains(it.packageName) }
                                        .map {
                                            Pair(
                                                it,
                                                AppSelectionOptions(
                                                    includeBaseApk = includeApk,
                                                    includeSplits = true,
                                                    includeData = includeData
                                                )
                                            )
                                        }

                                    val storageDir = android.os.Environment.getExternalStorageDirectory()
                                    val filesList = mutableListOf<String>()
                                    if (includeCamera) filesList.add(java.io.File(storageDir, "DCIM").absolutePath)
                                    if (includeDocuments) filesList.add(java.io.File(storageDir, "Documents").absolutePath)
                                    if (includeDownloads) filesList.add(java.io.File(storageDir, "Download").absolutePath)
                                    if (includeMusic) filesList.add(java.io.File(storageDir, "Music").absolutePath)

                                    val targetProvider = if (selectedDestination == "Google Drive") CloudProviderType.GOOGLE_DRIVE else null

                                    viewModel.launchBackup(
                                        title = "Backup Personalizado ODIN",
                                        selectedApps = selectedAppsList,
                                        selectedFiles = filesList,
                                        includeSms = includeSms,
                                        includeCalls = includeCalls,
                                        includeContacts = includeContacts,
                                        includeDns = includeDns,
                                        includeWallpaper = includeWallpaper,
                                        encryptionPassword = if (encryptionMode != "Sem criptografia") password.ifEmpty { "OdinSecuredKey" } else null,
                                        targetCloudProvider = targetProvider
                                    )
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentStep) {
                CustomStep.APPS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text("Etapa 1: Aplicativos", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text("Selecione os aplicativos que deseja incluir.", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val nonSystem = apps.filter { !it.isSystemApp }.map { it.packageName }
                                    selectedAppPackages.clear()
                                    selectedAppPackages.addAll(nonSystem)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Marcar Usuário", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { selectedAppPackages.clear() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Limpar", fontSize = 11.sp, color = StatusError)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = appSearch,
                            onValueChange = { appSearch = it },
                            placeholder = { Text("Filtrar apps...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = includeApk, onCheckedChange = { includeApk = it })
                                Text("Copiar APK", fontSize = 12.sp, color = TextPrimary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = includeData, onCheckedChange = { includeData = it })
                                Text("Dados disponíveis", fontSize = 12.sp, color = TextPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val filteredApps = remember(apps, appSearch) {
                            apps.filter { it.appName.contains(appSearch, ignoreCase = true) || it.packageName.contains(appSearch, ignoreCase = true) }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                val isChecked = selectedAppPackages.contains(app.packageName)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            if (isChecked) selectedAppPackages.remove(app.packageName)
                                            else selectedAppPackages.add(app.packageName)
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isChecked) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = {
                                                if (isChecked) selectedAppPackages.remove(app.packageName)
                                                else selectedAppPackages.add(app.packageName)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        com.swiftvault.backup.ui.components.RealAppIcon(
                                            packageName = app.packageName,
                                            size = 38.dp,
                                            shapeRadius = 8.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(app.appName, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            Text(app.packageName, fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        }
                                        if (app.isSystemApp) {
                                            Text("Sistema", fontSize = 10.sp, color = TextMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                CustomStep.FILES -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text("Etapa 2: Arquivos e Pastas", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text("Escolha os diretórios de mídia e documentos.", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(16.dp))

                        CheckOptionCard("Fotos da Câmera (DCIM/Camera)", "Fotos e vídeos registrados pela câmera", includeCamera, { includeCamera = it }, primaryAccent)
                        CheckOptionCard("Documentos (Documents)", "PDFs, planilhas e arquivos de texto", includeDocuments, { includeDocuments = it }, primaryAccent)
                        CheckOptionCard("Downloads (Download)", "Arquivos baixados pelo navegador", includeDownloads, { includeDownloads = it }, primaryAccent)
                        CheckOptionCard("Músicas (Music)", "Faixas de áudio salvas no aparelho", includeMusic, { includeMusic = it }, primaryAccent)
                    }
                }

                CustomStep.COMMUNICATIONS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text("Etapa 3: Comunicações", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text("Mensagens, contatos e histórico de chamadas.", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(16.dp))

                        CheckOptionCard("Contatos", "Exporta agenda de contatos completa para o container SVB", includeContacts, { includeContacts = it }, primaryAccent)
                        CheckOptionCard("Mensagens SMS", "Conversas e mensagens de texto SMS", includeSms, { includeSms = it }, primaryAccent)
                        CheckOptionCard("Registro de Chamadas", "Histórico de ligações efetuadas e recebidas", includeCalls, { includeCalls = it }, primaryAccent)
                    }
                }

                CustomStep.SYSTEM_DNS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text("Etapa 4: DNS / Configurações", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text("Definições do sistema suportadas pelo Android.", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(16.dp))

                        CheckOptionCard("Configurações de DNS Privado", "Hostname e modo do resolvedor DNS criptografado", includeDns, { includeDns = it }, primaryAccent)
                        CheckOptionCard("Rede e Wi-Fi", "Metadados da conexão e redes salvas (com root autorizado)", includeWifiSettings, { includeWifiSettings = it }, primaryAccent)
                        CheckOptionCard("Papel de Parede", "Imagem de fundo atual da tela inicial", includeWallpaper, { includeWallpaper = it }, primaryAccent)
                    }
                }

                CustomStep.DESTINATION -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text("Etapa 5: Destino", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text("Onde você deseja salvar este container de backup?", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(16.dp))

                        RadioOptionCard("Memória interna", "Armazenamento local do dispositivo", selectedDestination == "Memória interna", { selectedDestination = "Memória interna" }, primaryAccent)
                        RadioOptionCard("Google Drive", "Nuvem oficial do Google (único provedor em nuvem do app)", selectedDestination == "Google Drive", { selectedDestination = "Google Drive" }, primaryAccent)
                    }
                }

                CustomStep.SECURITY -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text("Etapa 6: Segurança", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text("Configure a proteção criptográfica dos seus dados.", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(16.dp))

                        RadioOptionCard("Sem criptografia", "Container padrão legível sem senha", encryptionMode == "Sem criptografia", { encryptionMode = "Sem criptografia" }, primaryAccent)
                        RadioOptionCard("Criptografia com senha", "Protegido por chave AES-256 e hash SHA-256", encryptionMode == "Criptografia com senha", { encryptionMode = "Criptografia com senha" }, primaryAccent)

                        if (encryptionMode == "Criptografia com senha") {
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Senha do Backup") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }

                CustomStep.SUMMARY -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text("Etapa 7: RESUMO DO BACKUP", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text("Verifique todos os itens antes de gravar.", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                InfoRow("Aplicativos", "${selectedAppPackages.size}")
                                InfoRow("Arquivos / Pastas", if (includeCamera || includeDocuments || includeDownloads || includeMusic) "Sim" else "Não")
                                InfoRow("Contatos", if (includeContacts) "Sim" else "Não")
                                InfoRow("SMS", if (includeSms) "Sim" else "Não")
                                InfoRow("Chamadas", if (includeCalls) "Sim" else "Não")
                                InfoRow("DNS", if (includeDns) "Sim" else "Não")
                                InfoRow("Destino", selectedDestination)
                                InfoRow("Criptografia", if (encryptionMode != "Sem criptografia") "Ativada" else "Desativada")
                                HorizontalDivider(color = DarkCardBorder)
                                InfoRow("Tamanho estimado", estimatedSizeDisplay, StatusSuccess)
                            }
                        }
                    }
                }

                CustomStep.EXECUTE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (backupProgress != null) {
                            val bp = backupProgress!!
                            if (bp.isFinished) {
                                Icon(
                                    imageVector = if (bp.error == null) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (bp.error == null) StatusSuccess else StatusError,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (bp.error == null) "✓ Backup concluído" else "✕ Backup não concluído",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (bp.error == null) StatusSuccess else StatusError
                                )
                                if (bp.error != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Falha: ${bp.error}",
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = onFinished,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                                ) {
                                    Text("CONCLUIR", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                CircularProgressIndicator(
                                    progress = { bp.progressPercent },
                                    modifier = Modifier.size(64.dp),
                                    color = primaryAccent,
                                    strokeWidth = 5.dp
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(bp.stage, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(bp.currentItemName, fontSize = 12.sp, color = TextSecondary)
                                Spacer(modifier = Modifier.height(16.dp))
                                LinearProgressIndicator(
                                    progress = { bp.progressPercent },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = primaryAccent,
                                    trackColor = DarkSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("${(bp.progressPercent * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                            }
                        } else {
                            CircularProgressIndicator(color = primaryAccent)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Preparando container...", color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckOptionCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) },
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, colors = CheckboxDefaults.colors(checkedColor = accent))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                Text(subtitle, fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun RadioOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    accent: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) accent else DarkCardBorder, RoundedCornerShape(10.dp))
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) accent.copy(alpha = 0.12f) else DarkSurfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect, colors = RadioButtonDefaults.colors(selectedColor = accent))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                Text(subtitle, fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}
