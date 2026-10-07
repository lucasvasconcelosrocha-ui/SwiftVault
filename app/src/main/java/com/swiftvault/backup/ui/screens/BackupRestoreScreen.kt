package com.swiftvault.backup.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.swiftvault.backup.data.model.BackupRecord
import com.swiftvault.backup.data.model.BackupStatus
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupRestoreScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val backups by viewModel.backupRecords.collectAsState()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    val context = LocalContext.current
    var selectedBackup by remember { mutableStateOf<BackupRecord?>(null) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var backupToDelete by remember { mutableStateOf<BackupRecord?>(null) }
    var isPullRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

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
                    text = "Gerenciador de Backups",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                coroutineScope.launch {
                    viewModel.refreshBackups()
                    delay(500)
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
            item {
                Text(
                    text = "Containers SwiftVault (.svb) disponíveis para restauração e validação:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (backups.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Inventory2, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Nenhum backup encontrado no armazenamento.", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                items(backups, key = { it.id }) { record ->
                    val dateFormatted = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(record.timestamp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, if (selectedBackup?.id == record.id) primaryAccent else DarkCardBorder, RoundedCornerShape(14.dp))
                            .clickable { selectedBackup = record },
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (record.isEncrypted) primaryAccent else AccentSunsetOrange,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = record.title,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .wrapContentWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(StatusSuccess.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "✓ Integridade OK",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusSuccess,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Criado em: $dateFormatted • Destino: ${record.destination}",
                                fontSize = 11.sp,
                                color = TextMuted
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tamanho: %.2f MB • ${record.itemCount} itens arquivados".format(record.sizeBytes.toDouble() / (1024 * 1024)),
                                fontSize = 12.sp,
                                color = primaryAccent,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { backupToDelete = record },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusError)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("EXCLUIR", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StatusError)
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Button(
                                    onClick = {
                                        selectedBackup = record
                                        showRestoreConfirmDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("RESTAURAR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
    }

    if (showRestoreConfirmDialog && selectedBackup != null) {
        val b = selectedBackup!!
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            containerColor = DarkSurface,
            title = {
                Text("Confirmar Restauração", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column {
                    Text("Deseja restaurar os itens do container '${b.title}'?", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Arquivo: ${File(b.filePath).name}", color = TextMuted, fontSize = 12.sp)
                    Text("Itens: ${b.itemCount} itens preservados", color = TextMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.launchRestore(b, emptySet())
                        showRestoreConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                ) {
                    Text("Iniciar Restauração")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }

    if (backupToDelete != null) {
        val b = backupToDelete!!
        val dateFormatted = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(b.timestamp))
        val sizeFormatted = "%.2f MB".format(b.sizeBytes.toDouble() / (1024 * 1024))

        AlertDialog(
            onDismissRequest = { backupToDelete = null },
            containerColor = DarkSurface,
            title = {
                Text("Excluir backup?", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tem certeza que deseja excluir este backup?", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Backup: ${b.title}", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text("Tamanho: $sizeFormatted", color = TextSecondary, fontSize = 12.sp)
                            Text("Data: $dateFormatted", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = b
                        backupToDelete = null
                        viewModel.deleteBackupRecord(toDelete) { success, errorMsg ->
                            if (success) {
                                android.widget.Toast.makeText(context, "Backup excluído com sucesso.", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, errorMsg ?: "Não foi possível excluir este backup.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("EXCLUIR", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { backupToDelete = null }) {
                    Text("CANCELAR", color = TextSecondary)
                }
            }
        )
    }
}
