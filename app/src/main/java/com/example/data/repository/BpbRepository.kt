package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.example.core.BpbLogBuffer
import com.example.core.CloudflareScanner
import com.example.core.ConfigParser
import com.example.core.DnsOverHttpsClient
import com.example.core.WarpEndpointScanner
import com.example.data.local.BpbDatabase
import com.example.data.local.CleanIpEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.local.XrayConfigEntity
import com.example.data.model.AetherTransportMode
import com.example.data.model.AetherWarpConfig
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.DnsPreset
import com.example.data.model.DnsTestResult
import com.example.data.model.FragmentConfig
import com.example.data.model.InstalledAppInfo
import com.example.data.model.PsiphonChainConfig
import com.example.data.model.RoutingConfig
import com.example.data.model.ScanProgress
import com.example.data.model.ScannerConfig
import com.example.data.model.SplitTunnelMode
import com.example.data.model.ThemeMode
import com.example.data.model.TunnelMode
import com.example.data.model.UdpNoisePreset
import com.example.data.model.WarpEndpointCandidate
import com.example.data.preferences.BpbPreferencesRepository
import com.example.data.preferences.BpbSettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BpbRepository(private val context: Context) {

    private val database = BpbDatabase.getInstance(context)
    private val xrayConfigRepo = XrayConfigRepository(database.xrayConfigDao())
    private val subscriptionDao = database.subscriptionDao()
    private val cleanIpDao = database.cleanIpDao()
    private val prefsRepo = BpbPreferencesRepository(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val xrayConfigsFlow: Flow<List<XrayConfigEntity>> = xrayConfigRepo.allConfigs
    val subscriptionsFlow: Flow<List<SubscriptionEntity>> = subscriptionDao.observeSubscriptions()
    val cleanIpsFlow: Flow<List<CleanIpEntity>> = cleanIpDao.observeCleanIps()
    val settingsFlow: Flow<BpbSettingsSnapshot> = prefsRepo.settingsFlow

    suspend fun importFromUriOrPayload(rawText: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val imported = xrayConfigRepo.importFromText(rawText)
            require(imported.isNotEmpty()) {
                "No valid VLESS, VMess, Trojan, Shadowsocks, SOCKS, or WARP configs found."
            }
            BpbLogBuffer.info("XRAY-DB", "Imported ${imported.size} Xray configuration(s).")
            imported.size
        }
    }

    suspend fun importSubscriptionUrl(name: String, url: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = url.trim()
            require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                "Subscription URL must start with http:// or https://"
            }
            BpbLogBuffer.info("SUB", "Fetching subscription '$name' from $cleanUrl…")
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "BPB-Aether-Android/1.0")
                .build()
            val bodyText = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} while fetching subscription")
                resp.body?.string().orEmpty()
            }
            val subId = subscriptionDao.insertSubscription(
                SubscriptionEntity(
                    name = name.ifBlank { "BPB Subscription" },
                    url = cleanUrl,
                    profileCount = 0
                )
            )
            val parsed = xrayConfigRepo.importFromText(bodyText, subscriptionId = subId)
            if (parsed.isEmpty()) error("Subscription fetched, but no supported Xray/VPN configs were found.")

            subscriptionDao.updateSubscription(
                SubscriptionEntity(
                    id = subId,
                    name = name.ifBlank { "BPB Subscription" },
                    url = cleanUrl,
                    profileCount = parsed.size,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )
            BpbLogBuffer.info("SUB", "Imported ${parsed.size} Xray config(s) for '$name'.")
            parsed.size
        }.onFailure { err ->
            BpbLogBuffer.error("SUB", "Subscription import failed: ${err.message}")
        }
    }

    suspend fun refreshSubscription(sub: SubscriptionEntity): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            BpbLogBuffer.info("SUB", "Refreshing subscription '${sub.name}'…")
            val request = Request.Builder()
                .url(sub.url)
                .header("User-Agent", "BPB-Aether-Android/1.0")
                .build()
            val bodyText = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            xrayConfigRepo.deleteBySubscriptionId(sub.id)
            val parsed = xrayConfigRepo.importFromText(bodyText, subscriptionId = sub.id)
            if (parsed.isEmpty()) error("No valid configs returned")
            subscriptionDao.updateSubscription(
                sub.copy(profileCount = parsed.size, lastUpdatedAt = System.currentTimeMillis())
            )
            BpbLogBuffer.info("SUB", "Refreshed '${sub.name}' (${parsed.size} configs).")
            parsed.size
        }
    }

    suspend fun saveXrayConfig(config: XrayConfigEntity, activate: Boolean = true): Long {
        val id = xrayConfigRepo.saveConfig(config, activate = activate)
        if (activate) prefsRepo.setActiveProfileId(id)
        return id
    }

    suspend fun setActiveXrayConfig(id: Long) {
        xrayConfigRepo.setActive(id)
        prefsRepo.setActiveProfileId(id)
    }

    suspend fun updateXrayObfuscation(config: XrayConfigEntity) = xrayConfigRepo.updateObfuscation(config)

    suspend fun togglePinXrayConfig(config: XrayConfigEntity) = xrayConfigRepo.togglePin(config)

    suspend fun deleteXrayConfig(config: XrayConfigEntity) {
        xrayConfigRepo.deleteConfig(config)
        val remaining = xrayConfigRepo.getActiveOrFirst()?.id
        prefsRepo.setActiveProfileId(remaining)
    }

    suspend fun applyCleanIpToXrayConfig(configId: Long, cleanIp: String) {
        xrayConfigRepo.updateServerAddress(configId, cleanIp)
        BpbLogBuffer.info("XRAY-DB", "Applied Clean IP $cleanIp to Xray config #$configId.")
    }

    suspend fun pingAllXrayConfigs() = xrayConfigRepo.pingAllConfigs()

    fun buildXrayJson(config: XrayConfigEntity, settings: BpbSettingsSnapshot): String =
        xrayConfigRepo.buildXrayJson(
            config = config,
            routingConfig = settings.routingConfig,
            dnsPresetId = settings.activeDnsPresetId,
            customDohUrl = settings.customDohUrl
        )

    suspend fun discoverWarpEndpoints(
        mode: AetherTransportMode,
        masqueSni: String,
    ): List<WarpEndpointCandidate> = WarpEndpointScanner.discoverEndpoints(
        mode = mode,
        masqueSni = masqueSni
    )

    suspend fun runCleanIpScan(
        config: ScannerConfig,
        onProgress: (ScanProgress) -> Unit,
    ): List<CleanIpEntity> {
        cleanIpDao.clearAll()
        val finalResults = CloudflareScanner.runScan(
            config = config,
            onProgress = onProgress,
            onHit = {}
        )
        if (finalResults.isNotEmpty()) {
            cleanIpDao.insertAll(finalResults)
        }
        return finalResults
    }

    suspend fun testAllDnsResolvers(domain: String, customDohUrl: String): List<DnsTestResult> = coroutineScope {
        BpbLogBuffer.info("DNS", "Benchmarking all DoH resolvers for '$domain'…")
        val presets = BuiltInDnsPresets.all.toMutableList()
        if (customDohUrl.isNotBlank()) {
            presets.add(
                0,
                DnsPreset(
                    id = "custom_doh",
                    name = "Custom DoH",
                    protocol = "DoH (Custom)",
                    endpoint = customDohUrl,
                    bootstrapIp = "Custom",
                    description = "User-configured DoH endpoint"
                )
            )
        }
        presets.map { preset ->
            async(Dispatchers.IO) {
                DnsOverHttpsClient.testResolver(preset, domain)
            }
        }.awaitAll()
    }

    suspend fun loadInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { appInfo ->
                InstalledAppInfo(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedBy { it.appName.lowercase() }
    }

    suspend fun setTunnelMode(mode: TunnelMode) = prefsRepo.setTunnelMode(mode)
    suspend fun setDnsPreset(presetId: String) = prefsRepo.setDnsPreset(presetId)
    suspend fun setCustomDoh(url: String, fakeSni: String) = prefsRepo.setCustomDoh(url, fakeSni)
    suspend fun updateFragmentConfig(config: FragmentConfig) = prefsRepo.updateFragmentConfig(config)
    suspend fun setUdpNoisePreset(preset: UdpNoisePreset) = prefsRepo.setUdpNoisePreset(preset)
    suspend fun updateRoutingConfig(config: RoutingConfig) = prefsRepo.updateRoutingConfig(config)
    suspend fun setSplitTunnelMode(mode: SplitTunnelMode) = prefsRepo.setSplitTunnelMode(mode)
    suspend fun togglePackageSelection(pkg: String) = prefsRepo.togglePackageSelection(pkg)
    suspend fun clearSelectedPackages() = prefsRepo.clearSelectedPackages()
    suspend fun setThemeMode(mode: ThemeMode) = prefsRepo.setThemeMode(mode)
    suspend fun updateAetherConfig(config: AetherWarpConfig) = prefsRepo.updateAetherConfig(config)
    suspend fun updatePsiphonConfig(config: PsiphonChainConfig) = prefsRepo.updatePsiphonConfig(config)
}
