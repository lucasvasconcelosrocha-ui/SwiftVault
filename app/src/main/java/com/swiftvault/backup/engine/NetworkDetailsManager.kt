package com.swiftvault.backup.engine

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.swiftvault.backup.data.model.*
import com.topjohnwu.superuser.Shell
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.*

/**
 * Legitimate Network, Wi-Fi, and Data Usage Inspector.
 * Strictly adheres to Android 17 security rules. Never invents data or bypasses system permissions.
 */
class NetworkDetailsManager(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val networkStatsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
    } else null

    /**
     * Inspect active Wi-Fi connection metadata and saved Wi-Fi networks (via authorized root)
     */
    fun getWifiDetails(): WifiConnectionDetails {
        val activeNetwork = connectivityManager?.activeNetwork
        val netCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val linkProps = activeNetwork?.let { connectivityManager.getLinkProperties(it) }

        val isWifi = netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (!isWifi) {
            val savedNetworks = readSavedWifiNetworksViaRoot()
            return WifiConnectionDetails(
                isConnected = false,
                ssid = "Desconectado do Wi-Fi",
                linkSpeedMbps = 0,
                frequencyGhz = "N/A",
                ipAddress = "N/A",
                gateway = "N/A",
                dnsServers = emptyList(),
                privateDnsServerName = null,
                wifiPasswordStatus = "Desconectado",
                savedNetworks = savedNetworks
            )
        }

        // Get WiFi info
        val wifiInfo: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            netCaps?.transportInfo as? WifiInfo ?: @Suppress("DEPRECATION") wifiManager?.connectionInfo
        } else {
            @Suppress("DEPRECATION")
            wifiManager?.connectionInfo
        }

        val hasLocationPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        var rawSsid = wifiInfo?.ssid ?: ""
        if (rawSsid.startsWith("\"") && rawSsid.endsWith("\"") && rawSsid.length >= 2) {
            rawSsid = rawSsid.substring(1, rawSsid.length - 1)
        }

        val cleanSsid = when {
            rawSsid.isNotBlank() && rawSsid != "<unknown ssid>" -> rawSsid
            !hasLocationPerm -> "Wi-Fi Conectado (Permissão de localização necessária para exibir SSID)"
            else -> "Wi-Fi Conectado (SSID Oculto ou Indisponível)"
        }

        val linkSpeed = wifiInfo?.linkSpeed ?: (netCaps?.linkDownstreamBandwidthKbps?.div(1000) ?: 0)
        val freqMhz = wifiInfo?.frequency ?: 0
        val freqDisplay = when {
            freqMhz in 2400..2500 -> "2.4 GHz ($freqMhz MHz)"
            freqMhz in 4900..5900 -> "5 GHz ($freqMhz MHz)"
            freqMhz in 5925..7125 -> "6 GHz Wi-Fi 6E/7 ($freqMhz MHz)"
            freqMhz > 0 -> "$freqMhz MHz"
            else -> "Desconhecido"
        }

        // IP Address
        val ipv4Address = linkProps?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()?.hostAddress ?: "Indisponível"

        // Gateway
        val gatewayAddress = linkProps?.routes
            ?.filter { it.isDefaultRoute }
            ?.mapNotNull { it.gateway?.hostAddress }
            ?.firstOrNull() ?: "Indisponível"

        // DNS
        val dnsList = linkProps?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

        // Private DNS (Android 9+)
        val privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            linkProps?.privateDnsServerName
        } else null

        // Saved networks via authorized root
        val savedNetworks = readSavedWifiNetworksViaRoot()

        // Password for current network
        val currentSaved = savedNetworks.firstOrNull { it.ssid.equals(cleanSsid, ignoreCase = true) }
        val passwordStatus = when {
            currentSaved?.password != null -> currentSaved.password
            Shell.isAppGrantedRoot() == true -> "Rede aberta ou senha não localizada no WifiConfigStore."
            else -> "Indisponível pelo Android\nO Android não permite acessar a senha desta rede sem autorização root."
        }

        return WifiConnectionDetails(
            isConnected = true,
            ssid = cleanSsid,
            linkSpeedMbps = if (linkSpeed > 0) linkSpeed else 0,
            frequencyGhz = freqDisplay,
            ipAddress = ipv4Address,
            gateway = gatewayAddress,
            dnsServers = dnsList,
            privateDnsServerName = privateDns,
            wifiPasswordStatus = passwordStatus,
            savedNetworks = savedNetworks
        )
    }

    /**
     * Reads saved Wi-Fi networks from Android's WifiConfigStore XML if root access is granted.
     * Never forges networks when unrooted or denied.
     */
    fun readSavedWifiNetworksViaRoot(): List<SavedWifiNetwork> {
        if (Shell.isAppGrantedRoot() != true) {
            return emptyList()
        }

        return try {
            val cmdResult = Shell.cmd(
                "cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null || cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null"
            ).exec()

            if (!cmdResult.isSuccess || cmdResult.out.isEmpty()) {
                return emptyList()
            }

            val fullXml = cmdResult.out.joinToString("\n")
            parseWifiConfigStoreXml(fullXml)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseWifiConfigStoreXml(xmlContent: String): List<SavedWifiNetwork> {
        val list = mutableListOf<SavedWifiNetwork>()
        // Network blocks are defined between <Network> ... </Network>
        val networkRegex = Regex("<Network>(.*?)</Network>", RegexOption.DOT_MATCHES_ALL)
        val ssidRegex = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>")
        val pskRegex = Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>")
        val configKeyRegex = Regex("<string name=\"ConfigKey\">&quot;(.*?)&quot;</string>")

        val matches = networkRegex.findAll(xmlContent)
        for (match in matches) {
            val block = match.groupValues[1]
            val ssid = ssidRegex.find(block)?.groupValues?.get(1) ?: continue
            val psk = pskRegex.find(block)?.groupValues?.get(1)
            val configKey = configKeyRegex.find(block)?.groupValues?.get(1) ?: ""

            val securityType = when {
                configKey.contains("WPA_PSK") || configKey.contains("WPA2") -> "WPA2-PSK"
                configKey.contains("SAE") || configKey.contains("WPA3") -> "WPA3-SAE"
                configKey.contains("NONE") || psk == null -> "Rede Aberta"
                else -> "WPA/WPA2"
            }

            list.add(
                SavedWifiNetwork(
                    ssid = unescapeXml(ssid),
                    password = if (psk != null) unescapeXml(psk) else null,
                    securityType = securityType,
                    lastConnected = "Salva no sistema"
                )
            )
        }
        return list
    }

    private fun unescapeXml(text: String): String {
        return text.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
    }

    /**
     * Compute actual network data consumption.
     * Uses NetworkStatsManager if PACKAGE_USAGE_STATS is granted;
     * otherwise falls back to TrafficStats with an explicit disclosure notice.
     * Never invents values.
     */
    fun getDataUsageStats(): NetworkDataUsageStats {
        val hasUsageAccess = hasUsageStatsPermission()

        if (hasUsageAccess && networkStatsManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return queryNetworkStatsManager()
        }

        // Fallback to TrafficStats (device boot session) without fake multipliers
        val totalRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
        val totalTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
        val mobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0)
        val mobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0)

        val totalBytes = totalRx + totalTx
        val mobileBytes = mobileRx + mobileTx
        val wifiBytes = (totalBytes - mobileBytes).coerceAtLeast(0)

        val sessionPeriod = DataUsagePeriod(
            downloadBytes = totalRx,
            uploadBytes = totalTx,
            totalBytes = totalBytes,
            wifiBytes = (totalRx - mobileRx).coerceAtLeast(0) + (totalTx - mobileTx).coerceAtLeast(0),
            mobileBytes = mobileBytes
        )

        return NetworkDataUsageStats(
            today = sessionPeriod,
            thisWeek = sessionPeriod,
            thisMonth = sessionPeriod,
            weeklyHistory = emptyList(),
            limitationNotice = "Dados obtidos desde a última reinicialização do dispositivo (TrafficStats). Para histórico por período (Hoje, 7 dias, Mês), conceda a permissão de Acesso de Uso nas configurações do Android."
        )
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun queryNetworkStatsManager(): NetworkDataUsageStats {
        val now = System.currentTimeMillis()

        // Today start (midnight)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = calendar.timeInMillis

        // 7 days ago
        val weekStart = now - (7 * 24 * 60 * 60 * 1000L)

        // Month start
        val monthCalendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthStart = monthCalendar.timeInMillis

        val todayPeriod = queryPeriod(todayStart, now)
        val weekPeriod = queryPeriod(weekStart, now)
        val monthPeriod = queryPeriod(monthStart, now)

        // Query daily breakdown for last 7 days
        val weeklyHistory = mutableListOf<Pair<String, Long>>()
        val dayFormat = SimpleDateFormat("EEE", Locale("pt", "BR"))
        for (i in 6 downTo 0) {
            val dayCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -i)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startDay = dayCal.timeInMillis
            val endDay = startDay + (24 * 60 * 60 * 1000L) - 1
            val dayPeriod = queryPeriod(startDay, endDay.coerceAtMost(now))
            weeklyHistory.add(Pair(dayFormat.format(dayCal.time).replace(".", ""), dayPeriod.totalBytes))
        }

        return NetworkDataUsageStats(
            today = todayPeriod,
            thisWeek = weekPeriod,
            thisMonth = monthPeriod,
            weeklyHistory = weeklyHistory,
            limitationNotice = null
        )
    }

    private fun queryPeriod(startTime: Long, endTime: Long): DataUsagePeriod {
        if (networkStatsManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return DataUsagePeriod(0L, 0L, 0L, 0L, 0L)
        }

        var wifiRx = 0L
        var wifiTx = 0L
        var mobileRx = 0L
        var mobileTx = 0L

        try {
            // Wi-Fi
            val wifiBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_WIFI,
                null,
                startTime,
                endTime
            )
            wifiRx = wifiBucket.rxBytes
            wifiTx = wifiBucket.txBytes
        } catch (_: Exception) { }

        try {
            // Mobile
            val mobileBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                null,
                startTime,
                endTime
            )
            mobileRx = mobileBucket.rxBytes
            mobileTx = mobileBucket.txBytes
        } catch (_: Exception) { }

        val totalRx = wifiRx + mobileRx
        val totalTx = wifiTx + mobileTx
        val total = totalRx + totalTx
        val wifiTotal = wifiRx + wifiTx
        val mobileTotal = mobileRx + mobileTx

        return DataUsagePeriod(
            downloadBytes = totalRx,
            uploadBytes = totalTx,
            totalBytes = total,
            wifiBytes = wifiTotal,
            mobileBytes = mobileTotal
        )
    }
}
