package com.swiftvault.backup.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftvault.backup.data.model.AppInfo
import com.swiftvault.backup.data.model.AppSelectionOptions
import com.swiftvault.backup.engine.CapabilityManager
import com.swiftvault.backup.engine.IconCacheManager
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsListScreen(
    viewModel: MainViewModel,
    onAppClick: (AppInfo) -> Unit,
    onBack: () -> Unit
) {
    val apps by viewModel.installedApps.collectAsState()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)
    val activeMode by viewModel.activeMode.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("Todos") } // "Todos", "Aplicativos", "Sistema", "Com backup", "Sem backup"
    var sortBy by remember { mutableStateOf("Nome A → Z") }
    var showSortMenu by remember { mutableStateOf(false) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedPackages = remember { mutableStateListOf<String>() }

    // Sort and filter pipeline
    val processedApps = remember(apps, searchQuery, selectedFilter, sortBy) {
        val filtered = apps.filter { app ->
            val matchesSearch = app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "Aplicativos" -> !app.isSystemApp
                "Sistema" -> app.isSystemApp
                "Com backup" -> app.hasBackup
                "Sem backup" -> !app.hasBackup
                else -> true
            }
            matchesSearch && matchesFilter
        }

        when (sortBy) {
            "Nome A → Z" -> filtered.sortedBy { it.appName.lowercase() }
            "Nome Z → A" -> filtered.sortedByDescending { it.appName.lowercase() }
            "Tamanho" -> filtered.sortedByDescending { it.sizeBytes }
            "Data de instalação" -> filtered.sortedByDescending { it.lastInstallTime }
            "Com backup" -> filtered.sortedByDescending { it.hasBackup }
            "Sem backup" -> filtered.sortedBy { it.hasBackup }
            else -> filtered
        }
    }

    val userApps = remember(processedApps) { processedApps.filter { !it.isSystemApp } }
    val systemApps = remember(processedApps) { processedApps.filter { it.isSystemApp } }

    val totalSelectedSize = remember(selectedPackages, apps) {
        apps.filter { selectedPackages.contains(it.packageName) }.sumOf { it.sizeBytes }
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
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Aplicativos",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val modeLabel = when (activeMode) {
                                CapabilityManager.ExecutionMode.ROOT -> "Root autorizado"
                                CapabilityManager.ExecutionMode.SHIZUKU -> "Shizuku ativo"
                                CapabilityManager.ExecutionMode.STANDARD -> "Acesso padrão"
                            }
                            Text(
                                text = modeLabel,
                                fontSize = 11.sp,
                                color = if (activeMode != CapabilityManager.ExecutionMode.STANDARD) StatusSuccess else TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Sort Button with dropdown
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Ordenar", tint = TextSecondary)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.background(DarkSurfaceVariant)
                            ) {
                                val sortOptions = listOf(
                                    "Nome A → Z",
                                    "Nome Z → A",
                                    "Tamanho",
                                    "Data de instalação",
                                    "Com backup",
                                    "Sem backup"
                                )
                                sortOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = option,
                                                color = if (sortBy == option) primaryAccent else TextPrimary,
                                                fontWeight = if (sortBy == option) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            sortBy = option
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        IconButton(onClick = {
                            isMultiSelectMode = !isMultiSelectMode
                            if (!isMultiSelectMode) selectedPackages.clear()
                        }) {
                            Icon(
                                imageVector = if (isMultiSelectMode) Icons.Default.ChecklistRtl else Icons.Default.Checklist,
                                contentDescription = "Seleção Múltipla",
                                tint = if (isMultiSelectMode) primaryAccent else TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Pesquisar aplicativo ou pacote...", color = TextMuted, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar", tint = TextMuted)
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedBorderColor = primaryAccent,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Filter Chips Row (Scrollable horizontally to prevent compression and vertical character wrapping)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf("Todos", "Aplicativos", "Sistema", "Com backup", "Sem backup")
                    for (f in filters) {
                        val isSelected = f == selectedFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) primaryAccent.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedFilter = f }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = f,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) primaryAccent else TextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Batch Selection Control Row
                if (isMultiSelectMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    val currentVisiblePackages = processedApps.map { it.packageName }
                                    selectedPackages.clear()
                                    selectedPackages.addAll(currentVisiblePackages)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("[Selecionar todos]", fontSize = 12.sp, color = primaryAccent, fontWeight = FontWeight.Bold)
                            }

                            TextButton(
                                onClick = { selectedPackages.clear() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("[Desmarcar todos]", fontSize = 12.sp, color = TextMuted)
                            }
                        }

                        Text(
                            text = "${selectedPackages.size}/${processedApps.size} selecionados",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (isMultiSelectMode && selectedPackages.isNotEmpty()) {
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
                        Column {
                            Text(
                                text = "${selectedPackages.size} selecionados",
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Estimado: ${formatAppSize(totalSelectedSize)}",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = {
                                val selectedAppInfos = apps.filter { selectedPackages.contains(it.packageName) }
                                    .map { Pair(it, AppSelectionOptions(includeBaseApk = true, includeSplits = it.splitSourceDirs.isNotEmpty())) }
                                viewModel.launchBackup(
                                    title = "Backup de ${selectedAppInfos.size} Apps",
                                    selectedApps = selectedAppInfos,
                                    selectedFiles = emptyList(),
                                    includeSms = false,
                                    includeCalls = false,
                                    includeDns = false,
                                    includeWallpaper = false
                                )
                                isMultiSelectMode = false
                                selectedPackages.clear()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fazer Backup", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selectedFilter == "Todos" && searchQuery.isBlank()) {
                // Section 1: User Apps
                if (userApps.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "APLICATIVOS INSTALADOS",
                            count = userApps.size,
                            accentColor = primaryAccent
                        )
                    }
                    items(userApps, key = { "user_${it.packageName}" }) { app ->
                        AppListItemCard(
                            app = app,
                            isMultiSelectMode = isMultiSelectMode,
                            isSelected = selectedPackages.contains(app.packageName),
                            accentColor = primaryAccent,
                            onToggleSelect = {
                                if (selectedPackages.contains(app.packageName)) {
                                    selectedPackages.remove(app.packageName)
                                } else {
                                    selectedPackages.add(app.packageName)
                                }
                            },
                            onClick = {
                                if (isMultiSelectMode) {
                                    if (selectedPackages.contains(app.packageName)) {
                                        selectedPackages.remove(app.packageName)
                                    } else {
                                        selectedPackages.add(app.packageName)
                                    }
                                } else {
                                    viewModel.selectAppForDetail(app)
                                    onAppClick(app)
                                }
                            }
                        )
                    }
                }

                // Section 2: System Apps
                if (systemApps.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        SectionHeader(
                            title = "APLICATIVOS DO SISTEMA",
                            count = systemApps.size,
                            accentColor = TextSecondary
                        )
                    }
                    items(systemApps, key = { "sys_${it.packageName}" }) { app ->
                        AppListItemCard(
                            app = app,
                            isMultiSelectMode = isMultiSelectMode,
                            isSelected = selectedPackages.contains(app.packageName),
                            accentColor = primaryAccent,
                            onToggleSelect = {
                                if (selectedPackages.contains(app.packageName)) {
                                    selectedPackages.remove(app.packageName)
                                } else {
                                    selectedPackages.add(app.packageName)
                                }
                            },
                            onClick = {
                                if (isMultiSelectMode) {
                                    if (selectedPackages.contains(app.packageName)) {
                                        selectedPackages.remove(app.packageName)
                                    } else {
                                        selectedPackages.add(app.packageName)
                                    }
                                } else {
                                    viewModel.selectAppForDetail(app)
                                    onAppClick(app)
                                }
                            }
                        )
                    }
                }
            } else {
                // Filtered or searched view
                item {
                    Text(
                        text = "${processedApps.size} aplicativos encontrados",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(processedApps, key = { it.packageName }) { app ->
                    AppListItemCard(
                        app = app,
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = selectedPackages.contains(app.packageName),
                        accentColor = primaryAccent,
                        onToggleSelect = {
                            if (selectedPackages.contains(app.packageName)) {
                                selectedPackages.remove(app.packageName)
                            } else {
                                selectedPackages.add(app.packageName)
                            }
                        },
                        onClick = {
                            if (isMultiSelectMode) {
                                if (selectedPackages.contains(app.packageName)) {
                                    selectedPackages.remove(app.packageName)
                                } else {
                                    selectedPackages.add(app.packageName)
                                }
                            } else {
                                viewModel.selectAppForDetail(app)
                                onAppClick(app)
                            }
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count apps",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun AppListItemCard(
    app: AppInfo,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    accentColor: Color,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val iconCache = remember { IconCacheManager.getInstance(context) }

    // Asynchronously load real icon from cache (non-blocking)
    val appIconState = produceState<ImageBitmap?>(initialValue = null, key1 = app.packageName) {
        value = iconCache.getAppIcon(app.packageName)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (isSelected) accentColor else DarkCardBorder,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = accentColor,
                        uncheckedColor = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Real Icon Display
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkBg),
                contentAlignment = Alignment.Center
            ) {
                if (appIconState.value != null) {
                    Image(
                        bitmap = appIconState.value!!,
                        contentDescription = app.appName,
                        modifier = Modifier.size(44.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = accentColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatAppSize(app.sizeBytes),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Text(
                    text = "${app.packageName} • v${app.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // System App Badge & Limitation tag
                if (app.isSystemApp) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(DarkSurface)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "APLICATIVO DO SISTEMA",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(StatusWarning.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Backup limitado",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatusWarning
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (app.hasBackup) "Último backup: ${app.lastBackupDate ?: "Hoje"}" else "Sem backup",
                        fontSize = 11.sp,
                        color = if (app.hasBackup) StatusSuccess else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (app.hasBackup) StatusSuccess.copy(alpha = 0.15f)
                                else TextMuted.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (app.hasBackup) "✓ Backup disponível" else "Pendente",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (app.hasBackup) StatusSuccess else TextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

private fun formatAppSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1024) {
        "%.1f GB".format(mb / 1024)
    } else {
        "%.1f MB".format(mb)
    }
}
