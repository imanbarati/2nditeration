package com.example.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.BpbLogBuffer
import com.example.core.ConfigParser
import com.example.data.local.CleanIpEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.local.VpnProfileEntity
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.ConnectionStatus
import com.example.data.model.DnsTestResult
import com.example.data.model.FragmentConfig
import com.example.data.model.InstalledAppInfo
import com.example.data.model.LogEntry
import com.example.data.model.ProtocolType
import com.example.data.model.RoutingConfig
import com.example.data.model.ScanProgress
import com.example.data.model.ScannerConfig
import com.example.data.model.SplitTunnelMode
import com.example.data.model.ThemeMode
import com.example.data.model.TunnelMode
import com.example.data.model.TunnelStats
import com.example.data.model.UdpNoisePreset
import com.example.data.preferences.BpbSettingsSnapshot
import com.example.data.repository.BpbRepository
import com.example.vpn.BpbVpnService
import com.example.vpn.VpnStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BpbUiState(
    val tunnelStats: TunnelStats = TunnelStats(),
    val settings: BpbSettingsSnapshot = BpbSettingsSnapshot(),
    val profiles: List<VpnProfileEntity> = emptyList(),
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val cleanIps: List<CleanIpEntity> = emptyList(),
    val scannerConfig: ScannerConfig = ScannerConfig(),
    val scanProgress: ScanProgress = ScanProgress(),
    val dnsTestResults: List<DnsTestResult> = emptyList(),
    val isTestingDns: Boolean = false,
    val isImporting: Boolean = false,
    val isPingingProfiles: Boolean = false,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
) {
    val activeProfile: VpnProfileEntity?
        get() = profiles.firstOrNull { it.id == settings.activeProfileId } ?: profiles.firstOrNull()
}

class BpbViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BpbRepository(application)

    private val _scannerConfig = MutableStateFlow(ScannerConfig())
    private val _scanProgress = MutableStateFlow(ScanProgress())
    private val _dnsTestResults = MutableStateFlow<List<DnsTestResult>>(emptyList())
    private val _isTestingDns = MutableStateFlow(false)
    private val _isImporting = MutableStateFlow(false)
    private val _isPingingProfiles = MutableStateFlow(false)
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val _isLoadingApps = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scanJob: Job? = null

    val logs: StateFlow<List<LogEntry>> = BpbLogBuffer.logs

    private data class CoreSnapshot(
        val stats: TunnelStats,
        val settings: BpbSettingsSnapshot,
        val profiles: List<VpnProfileEntity>,
        val subs: List<SubscriptionEntity>,
        val ips: List<CleanIpEntity>,
    )

    private data class AuxiliarySnapshot(
        val scannerConfig: ScannerConfig,
        val scanProgress: ScanProgress,
        val dnsResults: List<DnsTestResult>,
        val isTestingDns: Boolean,
        val isImporting: Boolean,
        val isPinging: Boolean,
        val apps: List<InstalledAppInfo>,
        val isLoadingApps: Boolean,
    )

    private val coreFlow = combine(
        VpnStateHolder.stats,
        repository.settingsFlow,
        repository.profilesFlow,
        repository.subscriptionsFlow,
        repository.cleanIpsFlow
    ) { stats, settings, profiles, subs, ips ->
        CoreSnapshot(stats, settings, profiles, subs, ips)
    }

    private val auxFlow = combine(
        _scannerConfig,
        _scanProgress,
        _dnsTestResults,
        _isTestingDns,
        _isImporting,
        _isPingingProfiles,
        _installedApps,
        _isLoadingApps
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        AuxiliarySnapshot(
            scannerConfig = arr[0] as ScannerConfig,
            scanProgress = arr[1] as ScanProgress,
            dnsResults = arr[2] as List<DnsTestResult>,
            isTestingDns = arr[3] as Boolean,
            isImporting = arr[4] as Boolean,
            isPinging = arr[5] as Boolean,
            apps = arr[6] as List<InstalledAppInfo>,
            isLoadingApps = arr[7] as Boolean
        )
    }

    val uiState: StateFlow<BpbUiState> = combine(coreFlow, auxFlow) { core, aux ->
        BpbUiState(
            tunnelStats = core.stats,
            settings = core.settings,
            profiles = core.profiles,
            subscriptions = core.subs,
            cleanIps = core.ips,
            scannerConfig = aux.scannerConfig,
            scanProgress = aux.scanProgress,
            dnsTestResults = aux.dnsResults,
            isTestingDns = aux.isTestingDns,
            isImporting = aux.isImporting,
            isPingingProfiles = aux.isPinging,
            installedApps = aux.apps,
            isLoadingApps = aux.isLoadingApps
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BpbUiState()
    )

    fun startVpnService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, BpbVpnService::class.java).apply {
            action = BpbVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun stopVpnService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, BpbVpnService::class.java).apply {
            action = BpbVpnService.ACTION_STOP
        }
        ctx.startService(intent)
    }

    fun setTunnelMode(mode: TunnelMode) {
        viewModelScope.launch {
            repository.setTunnelMode(mode)
            if (uiState.value.tunnelStats.status == ConnectionStatus.CONNECTED) {
                emitMessage("Mode updated. Reconnect tunnel to apply.")
            }
        }
    }

    fun selectProfile(profile: VpnProfileEntity) {
        viewModelScope.launch {
            repository.setActiveProfileId(profile.id)
            repository.setTunnelMode(TunnelMode.PROFILE_TUNNEL)
            emitMessage("Selected ${profile.name}")
        }
    }

    fun importFromUriOrPayload(rawText: String) {
        viewModelScope.launch {
            _isImporting.value = true
            repository.importFromUriOrPayload(rawText)
                .onSuccess { count -> emitMessage("Imported $count profile(s)") }
                .onFailure { err -> emitMessage(err.message ?: "Import failed") }
            _isImporting.value = false
        }
    }

    fun importSubscription(name: String, url: String) {
        viewModelScope.launch {
            _isImporting.value = true
            repository.importSubscriptionUrl(name, url)
                .onSuccess { count -> emitMessage("Imported $count profile(s) from subscription") }
                .onFailure { err -> emitMessage(err.message ?: "Subscription fetch failed") }
            _isImporting.value = false
        }
    }

    fun refreshSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            _isImporting.value = true
            repository.refreshSubscription(sub)
                .onSuccess { count -> emitMessage("Updated ${sub.name}: $count profiles") }
                .onFailure { err -> emitMessage("Refresh failed: ${err.message}") }
            _isImporting.value = false
        }
    }

    fun createBpbWizardProfile(
        name: String,
        protocol: ProtocolType,
        workerDomain: String,
        cleanIpOrHost: String,
        port: Int,
        uuidOrSecret: String,
        transport: String,
        path: String,
        fingerprint: String,
    ) {
        viewModelScope.launch {
            val host = workerDomain.trim()
            val address = cleanIpOrHost.trim().ifEmpty { host }
            val profile = VpnProfileEntity(
                name = name.trim().ifEmpty { "BPB ${protocol.label} • $host" },
                protocol = protocol.name,
                address = address,
                port = port,
                uuidOrSecret = uuidOrSecret.trim(),
                sni = host.ifEmpty { address },
                hostHeader = host.ifEmpty { address },
                path = path.trim().ifEmpty { "/" },
                transport = transport,
                security = "tls",
                fingerprint = fingerprint
            )
            val id = repository.saveProfile(profile)
            repository.setActiveProfileId(id)
            repository.setTunnelMode(TunnelMode.PROFILE_TUNNEL)
            emitMessage("Created BPB profile '${profile.name}'")
        }
    }

    fun deleteProfile(profile: VpnProfileEntity) {
        viewModelScope.launch {
            repository.deleteProfile(profile)
            emitMessage("Removed ${profile.name}")
        }
    }

    fun togglePinProfile(profile: VpnProfileEntity) {
        viewModelScope.launch { repository.togglePinProfile(profile) }
    }

    fun pingAllProfiles() {
        if (_isPingingProfiles.value) return
        viewModelScope.launch {
            _isPingingProfiles.value = true
            repository.pingAllProfiles()
            _isPingingProfiles.value = false
            emitMessage("Profile latency check complete")
        }
    }

    fun updateScannerConfig(config: ScannerConfig) {
        _scannerConfig.value = config
    }

    fun startCleanIpScan() {
        if (_scanProgress.value.isScanning) return
        val config = _scannerConfig.value
        scanJob = viewModelScope.launch {
            _scanProgress.value = ScanProgress(
                isScanning = true,
                testedCount = 0,
                totalCount = config.sampleCount,
                foundCount = 0
            )
            val results = repository.runCleanIpScan(config) { progress ->
                _scanProgress.value = progress
            }
            _scanProgress.value = _scanProgress.value.copy(isScanning = false)
            emitMessage("Scan finished: ${results.size} clean IP(s) found")
        }
    }

    fun stopCleanIpScan() {
        scanJob?.cancel()
        _scanProgress.value = _scanProgress.value.copy(isScanning = false)
        BpbLogBuffer.warn("SCANNER", "Clean IP scan stopped by user.")
    }

    fun applyCleanIpToActiveProfile(ip: String) {
        viewModelScope.launch {
            val active = uiState.value.activeProfile
            if (active == null) {
                emitMessage("Create or import a BPB profile first to apply $ip")
                return@launch
            }
            repository.applyCleanIpToProfile(active.id, ip)
            emitMessage("Applied $ip to ${active.name}")
        }
    }

    fun testAllDns(domain: String) {
        if (_isTestingDns.value) return
        viewModelScope.launch {
            _isTestingDns.value = true
            val results = repository.testAllDnsResolvers(
                domain = domain.trim().ifEmpty { "cloudflare.com" },
                customDohUrl = uiState.value.settings.customDohUrl
            )
            _dnsTestResults.value = results
            _isTestingDns.value = false
        }
    }

    fun selectDnsPreset(presetId: String) {
        viewModelScope.launch {
            repository.setDnsPreset(presetId)
            val name = BuiltInDnsPresets.all.find { it.id == presetId }?.name ?: presetId
            emitMessage("Active DNS set to $name")
        }
    }

    fun saveCustomDoh(url: String, fakeSni: String) {
        viewModelScope.launch {
            repository.setCustomDoh(url, fakeSni)
            emitMessage("Saved Serverless DNS & Fake SNI settings")
        }
    }

    fun updateFragmentConfig(config: FragmentConfig) {
        viewModelScope.launch { repository.updateFragmentConfig(config) }
    }

    fun setUdpNoisePreset(preset: UdpNoisePreset) {
        viewModelScope.launch { repository.setUdpNoisePreset(preset) }
    }

    fun updateRoutingConfig(config: RoutingConfig) {
        viewModelScope.launch { repository.updateRoutingConfig(config) }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        viewModelScope.launch { repository.setSplitTunnelMode(mode) }
    }

    fun togglePackageSelection(packageName: String) {
        viewModelScope.launch { repository.togglePackageSelection(packageName) }
    }

    fun clearSelectedPackages() {
        viewModelScope.launch { repository.clearSelectedPackages() }
    }

    fun loadInstalledAppsIfNeeded() {
        if (_installedApps.value.isNotEmpty() || _isLoadingApps.value) return
        viewModelScope.launch {
            _isLoadingApps.value = true
            _installedApps.value = repository.loadInstalledApps()
            _isLoadingApps.value = false
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun buildXrayJsonFor(profile: VpnProfileEntity?): String {
        val settings = uiState.value.settings
        val preset = BuiltInDnsPresets.all.find { it.id == settings.activeDnsPresetId }
            ?: BuiltInDnsPresets.all.first()
        return ConfigParser.buildXrayConfigJson(
            profile = profile,
            dnsPreset = preset,
            customDohUrl = settings.customDohUrl,
            fragmentConfig = settings.fragmentConfig,
            udpNoisePreset = settings.udpNoisePreset,
            routingConfig = settings.routingConfig
        )
    }

    fun clearLogs() = BpbLogBuffer.clear()

    private fun emitMessage(msg: String) {
        _messages.tryEmit(msg)
    }
}
