package com.swiftvault.backup.ui.navigation

sealed class Screen(val route: String) {
    object InitialSetup : Screen("initial_setup")
    object Dashboard : Screen("dashboard")
    object AppsList : Screen("apps_list")
    object AppDetail : Screen("app_detail")
    object FileExplorer : Screen("file_explorer")
    object DnsSettings : Screen("dns_settings")
    object SystemSettings : Screen("system_settings")
    object BackupWizard : Screen("backup_wizard")
    object CustomBackupWizard : Screen("custom_backup_wizard")
    object CloudSync : Screen("cloud_sync")
    object Restore : Screen("restore")
    object Permissions : Screen("permissions")
    object Logs : Screen("logs")
}
