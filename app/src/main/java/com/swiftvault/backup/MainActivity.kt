package com.swiftvault.backup

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.swiftvault.backup.ui.navigation.Screen
import com.swiftvault.backup.ui.screens.*
import com.swiftvault.backup.ui.theme.*
import com.swiftvault.backup.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("odin_prefs", Context.MODE_PRIVATE)
        val isSetupDone = prefs.getBoolean("setup_completed", false)

        setContent {
            val currentAccent by viewModel.themeAccent.collectAsState()
            val backupProgress by viewModel.backupProgress.collectAsState()
            val restoreProgress by viewModel.restoreProgress.collectAsState()

            val primaryAccent = getAccentColor(currentAccent)

            SwiftVaultTheme(accent = currentAccent) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    val bottomNavRoutes = listOf(
                        Screen.Dashboard.route,
                        Screen.AppsList.route,
                        Screen.FileExplorer.route,
                        Screen.Restore.route,
                        Screen.CloudSync.route,
                        Screen.SystemSettings.route
                    )
                    val showBottomNav = currentRoute in bottomNavRoutes

                    Scaffold(
                        containerColor = DarkBg,
                        bottomBar = {
                            if (showBottomNav) {
                                NavigationBar(
                                    containerColor = DarkSurface,
                                    tonalElevation = 8.dp
                                ) {
                                    NavigationBarItem(
                                        selected = currentRoute == Screen.Dashboard.route,
                                        onClick = {
                                            if (currentRoute != Screen.Dashboard.route) {
                                                navController.navigate(Screen.Dashboard.route) {
                                                    popUpTo(Screen.Dashboard.route) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Home, contentDescription = "Início") },
                                        label = { Text("Início", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = primaryAccent,
                                            selectedTextColor = primaryAccent,
                                            indicatorColor = primaryAccent.copy(alpha = 0.15f),
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )
                                    )

                                    NavigationBarItem(
                                        selected = currentRoute == Screen.AppsList.route,
                                        onClick = {
                                            if (currentRoute != Screen.AppsList.route) {
                                                navController.navigate(Screen.AppsList.route) {
                                                    popUpTo(Screen.Dashboard.route) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Apps, contentDescription = "Apps") },
                                        label = { Text("Apps", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = primaryAccent,
                                            selectedTextColor = primaryAccent,
                                            indicatorColor = primaryAccent.copy(alpha = 0.15f),
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )
                                    )

                                    NavigationBarItem(
                                        selected = currentRoute == Screen.FileExplorer.route,
                                        onClick = {
                                            if (currentRoute != Screen.FileExplorer.route) {
                                                navController.navigate(Screen.FileExplorer.route) {
                                                    popUpTo(Screen.Dashboard.route) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Folder, contentDescription = "Arquivos") },
                                        label = { Text("Arquivos", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = primaryAccent,
                                            selectedTextColor = primaryAccent,
                                            indicatorColor = primaryAccent.copy(alpha = 0.15f),
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )
                                    )

                                    NavigationBarItem(
                                        selected = currentRoute == Screen.Restore.route,
                                        onClick = {
                                            if (currentRoute != Screen.Restore.route) {
                                                navController.navigate(Screen.Restore.route) {
                                                    popUpTo(Screen.Dashboard.route) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Inventory2, contentDescription = "Backups") },
                                        label = { Text("Backups", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = primaryAccent,
                                            selectedTextColor = primaryAccent,
                                            indicatorColor = primaryAccent.copy(alpha = 0.15f),
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )
                                    )

                                    NavigationBarItem(
                                        selected = currentRoute == Screen.CloudSync.route,
                                        onClick = {
                                            if (currentRoute != Screen.CloudSync.route) {
                                                navController.navigate(Screen.CloudSync.route) {
                                                    popUpTo(Screen.Dashboard.route) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = { Icon(Icons.Default.CloudQueue, contentDescription = "Nuvem") },
                                        label = { Text("Nuvem", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = primaryAccent,
                                            selectedTextColor = primaryAccent,
                                            indicatorColor = primaryAccent.copy(alpha = 0.15f),
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )
                                    )

                                    NavigationBarItem(
                                        selected = currentRoute == Screen.SystemSettings.route,
                                        onClick = {
                                            if (currentRoute != Screen.SystemSettings.route) {
                                                navController.navigate(Screen.SystemSettings.route) {
                                                    popUpTo(Screen.Dashboard.route) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                                        label = { Text("Ajustes", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = primaryAccent,
                                            selectedTextColor = primaryAccent,
                                            indicatorColor = primaryAccent.copy(alpha = 0.15f),
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = if (isSetupDone) Screen.Dashboard.route else Screen.InitialSetup.route
                            ) {
                                composable(Screen.InitialSetup.route) {
                                    InitialSetupScreen(
                                        viewModel = viewModel,
                                        onSetupComplete = {
                                            navController.navigate(Screen.Dashboard.route) {
                                                popUpTo(Screen.InitialSetup.route) { inclusive = true }
                                            }
                                        }
                                    )
                                }

                                composable(Screen.Dashboard.route) {
                                    DashboardScreen(
                                        viewModel = viewModel,
                                        onNavigateToApps = { navController.navigate(Screen.AppsList.route) },
                                        onNavigateToFiles = { navController.navigate(Screen.FileExplorer.route) },
                                        onNavigateToCloud = { navController.navigate(Screen.CloudSync.route) },
                                        onNavigateToDns = { navController.navigate(Screen.DnsSettings.route) },
                                        onNavigateToCustomWizard = { navController.navigate(Screen.CustomBackupWizard.route) },
                                        onNavigateToQuickWizard = { navController.navigate(Screen.BackupWizard.route) },
                                        onNavigateToRestore = { navController.navigate(Screen.Restore.route) },
                                        onNavigateToPermissions = { navController.navigate(Screen.Permissions.route) },
                                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) }
                                    )
                                }

                                composable(Screen.AppsList.route) {
                                    AppsListScreen(
                                        viewModel = viewModel,
                                        onAppClick = { app ->
                                            viewModel.selectAppForDetail(app)
                                            navController.navigate(Screen.AppDetail.route)
                                        },
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.AppDetail.route) {
                                    val selectedApp by viewModel.selectedAppForDetail.collectAsState()
                                    if (selectedApp != null) {
                                        AppDetailScreen(
                                            app = selectedApp!!,
                                            viewModel = viewModel,
                                            onBack = { navController.popBackStack() },
                                            onSelectFiles = { navController.navigate(Screen.FileExplorer.route) },
                                            onViewBackup = { navController.navigate(Screen.Restore.route) }
                                        )
                                    } else {
                                        navController.popBackStack()
                                    }
                                }

                                composable(Screen.FileExplorer.route) {
                                    FileExplorerScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onStartBackupWithFiles = { paths, _ ->
                                            viewModel.launchBackup(
                                                title = "Backup de Pastas e Arquivos",
                                                selectedApps = emptyList(),
                                                selectedFiles = paths,
                                                includeSms = false,
                                                includeCalls = false,
                                                includeContacts = false,
                                                includeDns = false,
                                                includeWallpaper = false
                                            )
                                        }
                                    )
                                }

                                composable(Screen.DnsSettings.route) {
                                    DnsSettingsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.SystemSettings.route) {
                                    SettingsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onNavigateToPermissions = { navController.navigate(Screen.Permissions.route) },
                                        onNavigateToDns = { navController.navigate(Screen.DnsSettings.route) },
                                        onNavigateToInitialSetup = { navController.navigate(Screen.InitialSetup.route) },
                                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) }
                                    )
                                }

                                composable(Screen.BackupWizard.route) {
                                    BackupWizardScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onFinished = { navController.popBackStack(Screen.Dashboard.route, false) }
                                    )
                                }

                                composable(Screen.CustomBackupWizard.route) {
                                    CustomBackupWizardScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onFinished = { navController.popBackStack(Screen.Dashboard.route, false) }
                                    )
                                }

                                composable(Screen.Permissions.route) {
                                    PermissionsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.CloudSync.route) {
                                    CloudSyncCenterScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onViewLogs = { navController.navigate(Screen.Logs.route) }
                                    )
                                }

                                composable(Screen.Restore.route) {
                                    BackupRestoreScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Screen.Logs.route) {
                                    LogsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }

                            // Live Operation Floating Overlay / Banner
                            AnimatedVisibility(
                                visible = backupProgress != null,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                            ) {
                                backupProgress?.let { bp ->
                                    val isPausedState = bp.stage.contains("Pausado", ignoreCase = true)
                                    OperationProgressBanner(
                                        title = bp.stage,
                                        subtitle = bp.currentItemName,
                                        progress = bp.progressPercent,
                                        isFinished = bp.isFinished,
                                        error = bp.error,
                                        primaryAccent = primaryAccent,
                                        isPaused = isPausedState,
                                        onPause = { viewModel.pauseCloudUpload() },
                                        onResume = { viewModel.resumeCloudUpload() },
                                        onCancel = { viewModel.cancelCloudUpload() },
                                        onDismiss = { viewModel.dismissProgress() }
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = restoreProgress != null,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                            ) {
                                restoreProgress?.let { rp ->
                                    OperationProgressBanner(
                                        title = rp.stage,
                                        subtitle = rp.currentItemName,
                                        progress = rp.progressPercent,
                                        isFinished = rp.isFinished,
                                        error = rp.error,
                                        primaryAccent = primaryAccent,
                                        manualActionNotice = rp.manualActionRequired,
                                        onDismiss = { viewModel.dismissProgress() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OperationProgressBanner(
    title: String,
    subtitle: String,
    progress: Float,
    isFinished: Boolean,
    error: String?,
    primaryAccent: Color,
    manualActionNotice: String? = null,
    isPaused: Boolean = false,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (error != null) StatusError else primaryAccent, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isFinished) {
                        Icon(
                            imageVector = if (error != null) Icons.Default.Error else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (error != null) StatusError else StatusSuccess
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(24.dp),
                            color = primaryAccent,
                            strokeWidth = 3.dp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isFinished) {
                        if (onPause != null && onResume != null) {
                            TextButton(
                                onClick = { if (isPaused) onResume() else onPause() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isPaused) "Continuar" else "Pausar",
                                    color = primaryAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (onCancel != null) {
                            TextButton(
                                onClick = onCancel,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Cancelar",
                                    color = StatusError,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text("Fechar", color = primaryAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = subtitle, fontSize = 12.sp, color = TextSecondary)

            if (manualActionNotice != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = manualActionNotice,
                    fontSize = 12.sp,
                    color = StatusWarning,
                    fontWeight = FontWeight.Medium
                )
            }

            if (!isFinished) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = primaryAccent,
                    trackColor = DarkSurfaceVariant
                )
            }
        }
    }
}
