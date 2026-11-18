package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.example.core.BpbLogBuffer
import com.example.core.CloudflareScanner
import com.example.core.ConfigParser
import com.example.core.DnsOverHttpsClient
import com.example.core.ProfileLatencyTester
import com.example.data.local.BpbDatabase
import com.example.data.local.CleanIpEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.local.VpnProfileEntity
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.DnsTestResult
import com.example.data.model.FragmentConfig
import com.example.data.model.InstalledAppInfo
import com.example.data.model.RoutingConfig
import com.example.data.model.ScanProgress
import com.example.data.model.ScannerConfig
import com.example.data.model.SplitTunnelMode
import com.example.data.model.ThemeMode
import com.example.data.model.TunnelMode
import com.example.data.model.UdpNoisePreset
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
    private val profileDao = database.vpnProfileDao()
    private val subscriptionDao = database.subscriptionDao()
    private val cleanIpDao = database.cleanIpDao()
    private val prefsRepo = BpbPreferencesRepository(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val profilesFlow: Flow<List<VpnProfileEntity>> = profileDao.observeAllProfiles()
    val subscriptionsFlow: Flow<List<SubscriptionEntity>> = subscriptionDao.observeSubscriptions()
    val cleanIpsFlow: Flow<List<CleanIpEntity>> = cleanIpDao.observeCleanIps()
    val settingsFlow: Flow<BpbSettingsSnapshot> = prefsRepo.settingsFlow

    suspend fun importFromUriOrPayload(rawText: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = ConfigParser.parseSubscriptionPayload(rawText)
            require(parsed.isNotEmpty()) {
                "No valid VLESS, Trojan, Shadowsocks, SOCKS, or WARP configs found."
            }
            profileDao.insertProfiles(parsed)
            val all = profileDao.getAllProfilesOnce()
            val settings = prefsRepo.getSnapshot()
            if (settings.activeProfileId == null && all.isNotEmpty()) {
                prefsRepo.setActiveProfileId(all.first().id)
            }
            BpbLogBuffer.info("PROFILE", "Imported ${parsed.size} profile(s) from text/URI.")
            parsed.size
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
                .header("User-Agent", "BPB-VPN-Android/1.0")
                .build()
            val bodyText = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code} while fetching subscription")
                }
                resp.body?.string().orEmpty()
            }
            val subId = subscriptionDao.insertSubscription(
                SubscriptionEntity(
                    name = name.ifBlank { "BPB Subscription" },
                    url = cleanUrl,
                    profileCount = 0
                )
            )
            val parsed = ConfigParser.parseSubscriptionPayload(bodyText, subscriptionId = subId)
            if (parsed.isEmpty()) {
                error("Subscription fetched, but no supported VPN configs were found.")
            }
            profileDao.insertProfiles(parsed)
            subscriptionDao.updateSubscription(
                SubscriptionEntity(
                    id = subId,
                    name = name.ifBlank { "BPB Subscription" },
                    url = cleanUrl,
                    profileCount = parsed.size,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )
            val settings = prefsRepo.getSnapshot()
            if (settings.activeProfileId == null) {
                val all = profileDao.getAllProfilesOnce()
                all.firstOrNull()?.let { prefsRepo.setActiveProfileId(it.id) }
            }
            BpbLogBuffer.info("SUB", "Imported ${parsed.size} profile(s) for '$name'.")
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
                .header("User-Agent", "BPB-VPN-Android/1.0")
                .build()
            val bodyText = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val parsed = ConfigParser.parseSubscriptionPayload(bodyText, subscriptionId = sub.id)
            if (parsed.isEmpty()) error("No valid profiles returned")
            profileDao.deleteBySubscriptionId(sub.id)
            profileDao.insertProfiles(parsed)
            subscriptionDao.updateSubscription(
                sub.copy(profileCount = parsed.size, lastUpdatedAt = System.currentTimeMillis())
            )
            BpbLogBuffer.info("SUB", "Refreshed '${sub.name}' (${parsed.size} profiles).")
            parsed.size
        }
    }

    suspend fun saveProfile(profile: VpnProfileEntity): Long {
        val id = profileDao.insertProfile(profile)
        val settings = prefsRepo.getSnapshot()
        if (settings.activeProfileId == null) {
            prefsRepo.setActiveProfileId(id)
        }
        BpbLogBuffer.info("PROFILE", "Saved profile '${profile.name}' (${profile.protocol}).")
        return id
    }

    suspend fun deleteProfile(profile: VpnProfileEntity) {
        profileDao.deleteProfile(profile)
        val settings = prefsRepo.getSnapshot()
        if (settings.activeProfileId == profile.id) {
            val remaining = profileDao.getAllProfilesOnce().firstOrNull()?.id
            prefsRepo.setActiveProfileId(remaining)
        }
        BpbLogBuffer.info("PROFILE", "Deleted profile '${profile.name}'.")
    }

    suspend fun togglePinProfile(profile: VpnProfileEntity) {
        profileDao.updateProfile(profile.copy(isPinned = !profile.isPinned))
    }

    suspend fun applyCleanIpToProfile(profileId: Long, cleanIp: String) {
        profileDao.updateAddress(profileId, cleanIp)
        BpbLogBuffer.info("PROFILE", "Applied Clean IP $cleanIp to profile #$profileId.")
    }

    suspend fun pingProfile(profile: VpnProfileEntity): Long? {
        val rtt = ProfileLatencyTester.measureProfileLatency(profile)
        profileDao.updateLatency(profile.id, rtt)
        BpbLogBuffer.debug(
            "PING",
            "${profile.name} (${profile.address}:${profile.port}) -> ${rtt?.let { "${it}ms" } ?: "Timeout"}"
        )
        return rtt
    }

    suspend fun pingAllProfiles() = coroutineScope {
        val profiles = profileDao.getAllProfilesOnce()
        BpbLogBuffer.info("PING", "Measuring TCP/TLS latency for ${profiles.size} profile(s)…")
        profiles.map { profile ->
            async(Dispatchers.IO) {
                pingProfile(profile)
            }
        }.awaitAll()
    }

    suspend fun runCleanIpScan(
        config: ScannerConfig,
        onProgress: (ScanProgress) -> Unit,
    ): List<CleanIpEntity> {
        cleanIpDao.clearAll()
        val hits = mutableListOf<CleanIpEntity>()
        val finalResults = CloudflareScanner.runScan(
            config = config,
            onProgress = onProgress,
            onHit = { hit ->
                hits.add(hit)
            }
        )
        if (finalResults.isNotEmpty()) {
            cleanIpDao.insertAll(finalResults)
        }
        return finalResults
    }

    suspend fun clearCleanIps() = cleanIpDao.clearAll()

    suspend fun testAllDnsResolvers(domain: String, customDohUrl: String): List<DnsTestResult> = coroutineScope {
        BpbLogBuffer.info("DNS", "Benchmarking all DoH resolvers for '$domain'…")
        val presets = BuiltInDnsPresets.all.toMutableList()
        if (customDohUrl.isNotBlank()) {
            presets.add(
                0,
                com.example.data.model.DnsPreset(
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
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        resolveInfos
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

    suspend fun setActiveProfileId(id: Long?) = prefsRepo.setActiveProfileId(id)
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
}
