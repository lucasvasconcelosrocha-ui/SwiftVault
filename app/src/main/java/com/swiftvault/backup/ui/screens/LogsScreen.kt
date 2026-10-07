package com.swiftvault.backup.ui.screens

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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftvault.backup.data.model.LogEntry
import com.swiftvault.backup.data.model.LogLevel
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val logs by viewModel.systemLogs.collectAsState()
    val currentAccent by viewModel.themeAccent.collectAsState()
    val primaryAccent = getAccentColor(currentAccent)

    var selectedLevelFilter by remember { mutableStateOf<LogLevel?>(null) }

    val filteredLogs = remember(logs, selectedLevelFilter) {
        if (selectedLevelFilter == null) logs
        else logs.filter { it.level == selectedLevelFilter }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Logs de Sistema",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    IconButton(onClick = { viewModel.loadData() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Atualizar", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Level filter buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val levels = listOf(null, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)
                    for (lvl in levels) {
                        val isSelected = lvl == selectedLevelFilter
                        val label = lvl?.name ?: "Todos"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) primaryAccent.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                .border(1.dp, if (isSelected) primaryAccent else DarkCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedLevelFilter = lvl }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) primaryAccent else TextSecondary
                            )
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "${filteredLogs.size} registros de eventos capturados:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            items(filteredLogs, key = { it.id }) { log ->
                val dateFormatted = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                val levelColor = when (log.level) {
                    LogLevel.INFO -> StatusInfo
                    LogLevel.WARN -> StatusWarning
                    LogLevel.ERROR -> StatusError
                    LogLevel.DEBUG -> TextMuted
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(levelColor.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = log.level.name,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = levelColor
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = log.tag,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }

                            Text(
                                text = dateFormatted,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = log.message,
                            fontSize = 13.sp,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )

                        if (log.details != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = log.details,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
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
