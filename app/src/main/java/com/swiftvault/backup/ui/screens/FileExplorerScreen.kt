package com.swiftvault.backup.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.swiftvault.backup.data.model.BackupFilter
import com.swiftvault.backup.engine.CapabilityManager
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class FileCategory(val label: String) {
    ALL("Todos"),
    IMAGES("Imagens"),
    VIDEOS("Vídeos"),
    AUDIO("Áudios"),
    DOCUMENTS("Documentos"),
    ARCHIVES("Compactados"),
    APK("APK"),
    BACKUPS("Backups"),
    OTHERS("Outros")
}

enum class FileSortOrder(val label: String) {
    NAME_ASC("Nome A → Z"),
    NAME_DESC("Nome Z → A"),
    SIZE_DESC("Maior tamanho"),
    SIZE_ASC("Menor tamanho"),
    DATE_DESC("Mais recente"),
    DATE_ASC("Mais antigo"),
    TYPE("Tipo de arquivo")
}

enum class DeletionState {
    IDLE,
    CONFIRMING_DELETE,
    DELETING,
    DELETE_SUCCESS,
    DELETE_ERROR
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileExplorerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onStartBackupWithFiles: (List<String>, BackupFilter?) -> Unit
) {
    val context = LocalContext.current
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)
    val savedFilters by viewModel.backupFilters.collectAsState()
    val activeMode by viewModel.activeMode.collectAsState()
    val isRootActive = activeMode == CapabilityManager.ExecutionMode.ROOT
    val coroutineScope = rememberCoroutineScope()

    // Navigation state
    val rootDir = remember { Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0") }
    var currentDir by remember { mutableStateOf(rootDir) }
    var directoryRefreshTrigger by remember { mutableStateOf(0) }
    var isPullRefreshing by remember { mutableStateOf(false) }

    // Search and Filters
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(FileCategory.ALL) }
    var selectedSortOrder by remember { mutableStateOf(FileSortOrder.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Multi-selection state
    val selectedPaths = remember { mutableStateListOf<String>() }

    // Modals & Deletion State Machine
    var showFilterDialog by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf<BackupFilter?>(null) }
    var fileToRename by remember { mutableStateOf<File?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<File?>(null) }
    var deleteState by remember { mutableStateOf(DeletionState.IDLE) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }
    var fileForInfo by remember { mutableStateOf<File?>(null) }
    var showBackupConfirmDialog by remember { mutableStateOf(false) }

    // Permission check for MANAGE_EXTERNAL_STORAGE (Android 11+)
    val hasAllFilesAccess = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    // Android System Back Navigation Handler
    BackHandler(enabled = currentDir.absolutePath != rootDir.absolutePath) {
        if (currentDir.parentFile != null && currentDir.absolutePath != rootDir.absolutePath) {
            currentDir = currentDir.parentFile!!
        }
    }

    // Query and process real files in directory
    val directoryItems by produceState<List<File>>(
        initialValue = emptyList(),
        key1 = currentDir.absolutePath,
        key2 = directoryRefreshTrigger,
        key3 = isRootActive
    ) {
        value = withContext(Dispatchers.IO) {
            val listedFiles = currentDir.listFiles()?.toList()
            if (listedFiles != null && listedFiles.isNotEmpty()) {
                listedFiles
            } else if (isRootActive) {
                // Root fallback via Shell if direct access is restricted
                try {
                    val out = Shell.cmd("ls -1 \"${currentDir.absolutePath}\"").exec().out
                    out.mapNotNull { name ->
                        val f = File(currentDir, name)
                        if (f.exists()) f else null
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }

    // Filter and Sort files
    val filteredFiles = remember(directoryItems, searchQuery, selectedCategory, selectedSortOrder) {
        directoryItems
            .filter { file ->
                // Search filter
                val matchesSearch = if (searchQuery.isBlank()) true else file.name.contains(searchQuery.trim(), ignoreCase = true)

                // Category filter
                val matchesCategory = if (selectedCategory == FileCategory.ALL || file.isDirectory) {
                    true
                } else {
                    getFileCategory(file) == selectedCategory
                }

                matchesSearch && matchesCategory
            }
            .sortedWith { f1, f2 ->
                // Keep directories at top unless sorting strictly by size
                if (f1.isDirectory && !f2.isDirectory) return@sortedWith -1
                if (!f1.isDirectory && f2.isDirectory) return@sortedWith 1

                when (selectedSortOrder) {
                    FileSortOrder.NAME_ASC -> f1.name.compareTo(f2.name, ignoreCase = true)
                    FileSortOrder.NAME_DESC -> f2.name.compareTo(f1.name, ignoreCase = true)
                    FileSortOrder.SIZE_DESC -> f2.length().compareTo(f1.length())
                    FileSortOrder.SIZE_ASC -> f1.length().compareTo(f2.length())
                    FileSortOrder.DATE_DESC -> f2.lastModified().compareTo(f1.lastModified())
                    FileSortOrder.DATE_ASC -> f1.lastModified().compareTo(f2.lastModified())
                    FileSortOrder.TYPE -> f1.extension.compareTo(f2.extension, ignoreCase = true)
                }
            }
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
                        IconButton(onClick = {
                            if (currentDir.absolutePath != rootDir.absolutePath && currentDir.parentFile != null) {
                                currentDir = currentDir.parentFile!!
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Voltar",
                                tint = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Gerenciador de Arquivos",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isRootActive) "● Acesso Root Autorizado (KernelSU)" else "Acesso Padrão",
                                fontSize = 11.sp,
                                color = if (isRootActive) StatusSuccess else TextMuted,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Search Toggle
                        IconButton(onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                        }) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Pesquisar",
                                tint = if (isSearchActive) primaryAccent else TextSecondary
                            )
                        }

                        // Sort Menu
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Ordenar",
                                    tint = TextSecondary
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.background(DarkSurfaceVariant)
                            ) {
                                FileSortOrder.values().forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = order.label,
                                                fontWeight = if (selectedSortOrder == order) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selectedSortOrder == order) primaryAccent else TextPrimary
                                            )
                                        },
                                        onClick = {
                                            selectedSortOrder = order
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Preset Filter
                        IconButton(onClick = { showFilterDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filtros",
                                tint = if (activeFilter != null) primaryAccent else TextSecondary
                            )
                        }
                    }
                }

                // Search Bar
                if (isSearchActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Pesquisar arquivos na pasta...", color = TextMuted) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = primaryAccent) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpar", tint = TextMuted)
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryAccent,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Category Chips Row (Horizontal Scrollable)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FileCategory.values().forEach { cat ->
                        val isSelected = selectedCategory == cat
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) primaryAccent.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cat.label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) primaryAccent else TextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Breadcrumb path display
                val displayPath = currentDir.absolutePath
                    .replace(rootDir.absolutePath, "Armazenamento Interno")
                    .replace("/", " > ")
                Text(
                    text = "📁 $displayPath",
                    fontSize = 12.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        bottomBar = {
            if (selectedPaths.isNotEmpty()) {
                Surface(
                    color = DarkSurface,
                    border = BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val totalBytes = selectedPaths.sumOf { File(it).length() }
                            Column {
                                Text(
                                    text = "${selectedPaths.size} arquivos selecionados",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Tamanho: ${formatFileSize(totalBytes)}",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (selectedPaths.size == filteredFiles.filter { !it.isDirectory }.size) {
                                            selectedPaths.clear()
                                        } else {
                                            selectedPaths.clear()
                                            selectedPaths.addAll(filteredFiles.filter { !it.isDirectory }.map { it.absolutePath })
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, DarkCardBorder),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    val allSelected = selectedPaths.size == filteredFiles.filter { !it.isDirectory }.size
                                    Text(
                                        text = if (allSelected) "Desmarcar" else "Todos",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                }

                                Button(
                                    onClick = { showBackupConfirmDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("FAZER BACKUP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                directoryRefreshTrigger++
                coroutineScope.launch {
                    kotlinx.coroutines.delay(600)
                    isPullRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            // Permission Notice if All Files Access is missing on Android 11+
            if (!hasAllFilesAccess) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, StatusWarning, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.12f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Acesso Completo aos Arquivos", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Para listar e fazer backup de todos os arquivos do dispositivo, conceda a permissão especial do Android.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
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
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("CONCEDER ACESSO TOTAL", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Quick Up Directory Item if inside subdirectory
            if (currentDir.absolutePath != rootDir.absolutePath && currentDir.parentFile != null) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                            .clickable { currentDir = currentDir.parentFile!! },
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = primaryAccent, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(".. (Pasta anterior)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Empty state
            if (filteredFiles.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Nenhum arquivo encontrado nesta pasta", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }

            // Files and Folders list
            items(filteredFiles, key = { it.absolutePath }) { file ->
                val isSelected = selectedPaths.contains(file.absolutePath)
                val isDir = file.isDirectory
                val category = getFileCategory(file)

                var showItemMenu by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            1.dp,
                            if (isSelected) primaryAccent else DarkCardBorder,
                            RoundedCornerShape(12.dp)
                        )
                        .combinedClickable(
                            onClick = {
                                if (isDir) {
                                    currentDir = file
                                } else {
                                    if (isSelected) selectedPaths.remove(file.absolutePath)
                                    else selectedPaths.add(file.absolutePath)
                                }
                            },
                            onLongClick = {
                                itemToDelete = file
                                deleteErrorMessage = null
                                deleteState = DeletionState.CONFIRMING_DELETE
                            }
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) primaryAccent.copy(alpha = 0.12f) else DarkSurfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Multi-selection Checkbox for Files
                        if (!isDir) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (isSelected) selectedPaths.remove(file.absolutePath)
                                    else selectedPaths.add(file.absolutePath)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = primaryAccent),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        // File Thumbnail or Category Material Icon
                        FileThumbnailOrIcon(
                            file = file,
                            category = category,
                            isDir = isDir,
                            primaryAccent = primaryAccent
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // File details column
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val sizeText = if (isDir) {
                                    val count = file.list()?.size ?: 0
                                    "$count itens"
                                } else {
                                    formatFileSize(file.length())
                                }

                                Text(
                                    text = sizeText,
                                    fontSize = 11.sp,
                                    color = if (isDir) AccentSunsetOrange else primaryAccent,
                                    fontWeight = FontWeight.Medium
                                )

                                Text(text = " • ", fontSize = 11.sp, color = TextMuted)

                                Text(
                                    text = formatFileDate(file.lastModified()),
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Context menu for individual file operations
                        Box {
                            IconButton(onClick = { showItemMenu = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Mais opções", tint = TextSecondary, modifier = Modifier.size(20.dp))
                            }

                            DropdownMenu(
                                expanded = showItemMenu,
                                onDismissRequest = { showItemMenu = false },
                                modifier = Modifier.background(DarkSurfaceVariant)
                            ) {
                                if (!isDir) {
                                    DropdownMenuItem(
                                        text = { Text("Abrir", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = primaryAccent) },
                                        onClick = {
                                            showItemMenu = false
                                            openFile(context, file)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Compartilhar", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = AccentElectricCyan) },
                                        onClick = {
                                            showItemMenu = false
                                            shareFile(context, file)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Fazer Backup", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = primaryAccent) },
                                        onClick = {
                                            showItemMenu = false
                                            onStartBackupWithFiles(listOf(file.absolutePath), activeFilter)
                                        }
                                    )
                                }

                                DropdownMenuItem(
                                    text = { Text("Renomear", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = AccentCyberPurple) },
                                    onClick = {
                                        showItemMenu = false
                                        fileToRename = file
                                        renameInput = file.name
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Informações", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary) },
                                    onClick = {
                                        showItemMenu = false
                                        fileForInfo = file
                                    }
                                )

                                HorizontalDivider(color = DarkCardBorder)

                                DropdownMenuItem(
                                    text = { Text("Excluir", color = StatusError) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError) },
                                    onClick = {
                                        showItemMenu = false
                                        itemToDelete = file
                                        deleteErrorMessage = null
                                        deleteState = DeletionState.CONFIRMING_DELETE
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

    // Modal: File Info Details
    if (fileForInfo != null) {
        val f = fileForInfo!!
        AlertDialog(
            onDismissRequest = { fileForInfo = null },
            containerColor = DarkSurface,
            title = {
                Text("Detalhes do Arquivo", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nome: ${f.name}", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                    Text("Caminho: ${f.absolutePath}", color = TextSecondary, fontSize = 12.sp)
                    Text("Tamanho: ${formatFileSize(f.length())} (${f.length()} bytes)", color = TextSecondary, fontSize = 12.sp)
                    Text("Tipo: ${getFileCategory(f).label} (${f.extension.uppercase()})", color = TextSecondary, fontSize = 12.sp)
                    Text("Modificado em: ${formatFileDate(f.lastModified())}", color = TextSecondary, fontSize = 12.sp)
                    Text("Permissões: R:${if (f.canRead()) "✓" else "✕"} W:${if (f.canWrite()) "✓" else "✕"} X:${if (f.canExecute()) "✓" else "✕"}", color = TextMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { fileForInfo = null },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Fechar")
                }
            }
        )
    }

    // Modal: Rename File
    if (fileToRename != null) {
        val f = fileToRename!!
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            containerColor = DarkSurface,
            title = { Text("Renomear", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryAccent,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank() && renameInput != f.name) {
                            val target = File(f.parentFile, renameInput.trim())
                            val success = f.renameTo(target)
                            if (success) {
                                directoryRefreshTrigger++
                                Toast.makeText(context, "Renomeado com sucesso", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Falha ao renomear arquivo", Toast.LENGTH_SHORT).show()
                            }
                        }
                        fileToRename = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Modal: Delete Confirm with DeletionState machine & recursive support
    if (itemToDelete != null && deleteState != DeletionState.IDLE) {
        val f = itemToDelete!!
        val isFolder = f.isDirectory

        AlertDialog(
            onDismissRequest = {
                if (deleteState != DeletionState.DELETING) {
                    itemToDelete = null
                    deleteState = DeletionState.IDLE
                }
            },
            containerColor = DarkSurface,
            title = {
                Text(
                    text = if (isFolder) "Excluir pasta?" else "Excluir arquivo?",
                    fontWeight = FontWeight.Bold,
                    color = StatusError
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (deleteState == DeletionState.DELETING) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = StatusError,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = if (isFolder) "Excluindo pasta e conteúdos..." else "Excluindo arquivo...",
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Text(
                            text = if (isFolder)
                                "Tem certeza que deseja excluir esta pasta e todo o seu conteúdo?"
                            else
                                "Tem certeza que deseja excluir este arquivo?",
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Item: ${f.name}",
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        if (deleteState == DeletionState.DELETE_ERROR && !deleteErrorMessage.isNullOrBlank()) {
                            Text(
                                text = deleteErrorMessage ?: "Não foi possível excluir o item.",
                                color = StatusError,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (deleteState != DeletionState.DELETING) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                deleteState = DeletionState.DELETING
                                val success = withContext(Dispatchers.IO) {
                                    val normalSuccess = if (isFolder) {
                                        f.deleteRecursively()
                                    } else {
                                        f.delete()
                                    }
                                    if (normalSuccess) true
                                    else if (isRootActive) {
                                        try {
                                            Shell.cmd("rm -rf \"${f.absolutePath}\"").exec().isSuccess
                                        } catch (e: Exception) {
                                            false
                                        }
                                    } else {
                                        false
                                    }
                                }

                                if (success) {
                                    selectedPaths.remove(f.absolutePath)
                                    directoryRefreshTrigger++
                                    deleteState = DeletionState.DELETE_SUCCESS
                                    Toast.makeText(
                                        context,
                                        if (isFolder) "Pasta excluída com sucesso" else "Arquivo excluído com sucesso",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    itemToDelete = null
                                    deleteState = DeletionState.IDLE
                                } else {
                                    deleteState = DeletionState.DELETE_ERROR
                                    deleteErrorMessage = "Não foi possível excluir o item. Verifique as permissões de armazenamento."
                                    Toast.makeText(context, "Falha ao excluir o item", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                    ) {
                        Text("Excluir", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (deleteState != DeletionState.DELETING) {
                    TextButton(onClick = {
                        itemToDelete = null
                        deleteState = DeletionState.IDLE
                    }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                }
            }
        )
    }

    // Modal: Backup Confirm Dialog for Selected Files
    if (showBackupConfirmDialog) {
        val totalBytes = selectedPaths.sumOf { File(it).length() }
        AlertDialog(
            onDismissRequest = { showBackupConfirmDialog = false },
            containerColor = DarkSurface,
            title = { Text("Iniciar Backup de Arquivos", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${selectedPaths.size} arquivos selecionados para backup.", color = TextSecondary, fontSize = 13.sp)
                    Text("Tamanho total estimado: ${formatFileSize(totalBytes)}", fontWeight = FontWeight.SemiBold, color = primaryAccent, fontSize = 13.sp)
                    Text("Os arquivos serão empacotados com verificação de integridade no container ODIN.", color = TextMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBackupConfirmDialog = false
                        onStartBackupWithFiles(selectedPaths.toList(), activeFilter)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("INICIAR BACKUP")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupConfirmDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    // Filter Dialog Modal (Preset filters)
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            containerColor = DarkSurface,
            title = {
                Text("Filtros de Backup", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Selecione um filtro pré-configurado:", fontSize = 13.sp, color = TextSecondary)

                    for (filter in savedFilters) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, if (activeFilter?.id == filter.id) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    activeFilter = filter
                                    showFilterDialog = false
                                },
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(filter.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                                if (filter.includedExtensions.isNotEmpty()) {
                                    Text("Extensões: ${filter.includedExtensions.joinToString(", ")}", fontSize = 11.sp, color = TextMuted)
                                }
                                if (filter.minSizeBytes > 0) {
                                    Text("Tamanho > ${filter.minSizeBytes / (1024 * 1024)} MB", fontSize = 11.sp, color = TextMuted)
                                }
                                Text("Exclui: ${filter.excludedPatterns.joinToString(", ")}", fontSize = 11.sp, color = StatusWarning)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    activeFilter = null
                    showFilterDialog = false
                }) {
                    Text("Limpar Filtros", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFilterDialog = false }) {
                    Text("Fechar", color = TextPrimary)
                }
            }
        )
    }
}

/**
 * Thumbnail or category icon renderer with sampled bitmap decoding for images
 */
@Composable
private fun FileThumbnailOrIcon(
    file: File,
    category: FileCategory,
    isDir: Boolean,
    primaryAccent: Color
) {
    if (isDir) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentSunsetOrange.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = AccentSunsetOrange, modifier = Modifier.size(24.dp))
        }
        return
    }

    // For images, load efficient thumbnail
    if (category == FileCategory.IMAGES) {
        val thumbnailBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = file.absolutePath) {
            value = withContext(Dispatchers.IO) {
                try {
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
                    val sampleSize = calculateInSampleSize(boundsOpts, 96, 96)
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    BitmapFactory.decodeFile(file.absolutePath, decodeOpts)?.asImageBitmap()
                } catch (e: Throwable) {
                    null
                }
            }
        }

        if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap!!,
                contentDescription = file.name,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            return
        }
    }

    // Material category icon fallback
    val (icon, tint) = when (category) {
        FileCategory.IMAGES -> Pair(Icons.Default.Image, StatusSuccess)
        FileCategory.VIDEOS -> Pair(Icons.Default.Movie, AccentCyberPurple)
        FileCategory.AUDIO -> Pair(Icons.Default.MusicNote, AccentElectricCyan)
        FileCategory.DOCUMENTS -> Pair(Icons.Default.Description, AccentNeonBlue)
        FileCategory.ARCHIVES -> Pair(Icons.Default.FolderZip, StatusWarning)
        FileCategory.APK -> Pair(Icons.Default.Android, StatusSuccess)
        FileCategory.BACKUPS -> Pair(Icons.Default.Shield, primaryAccent)
        else -> Pair(Icons.Default.InsertDriveFile, TextSecondary)
    }

    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun getFileCategory(file: File): FileCategory {
    if (file.isDirectory) return FileCategory.ALL
    return when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg" -> FileCategory.IMAGES
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp" -> FileCategory.VIDEOS
        "mp3", "m4a", "aac", "ogg", "wav", "flac", "opus", "wma" -> FileCategory.AUDIO
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "md" -> FileCategory.DOCUMENTS
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> FileCategory.ARCHIVES
        "apk", "apks", "xapk", "apkm" -> FileCategory.APK
        "svb", "bak", "backup", "ab" -> FileCategory.BACKUPS
        else -> FileCategory.OTHERS
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes.toDouble() / 1024
    val mb = kb / 1024
    val gb = mb / 1024
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.1f KB".format(kb)
        else -> "$bytes B"
    }
}

private fun formatFileDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Data desconhecida"
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
    val sdfDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return when {
        diff in 0..(24 * 60 * 60 * 1000L) -> "Hoje, ${sdfTime.format(Date(timestamp))}"
        diff in 0..(48 * 60 * 60 * 1000L) -> "Ontem, ${sdfTime.format(Date(timestamp))}"
        else -> sdfDate.format(Date(timestamp))
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun openFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Abrir com"))
    } catch (e: Exception) {
        Toast.makeText(context, "Não foi possível abrir o arquivo: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar arquivo"))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao compartilhar: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
