package com.example.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.BpbLogBuffer
import com.example.data.local.CleanIpEntity
import com.example.data.local.SubscriptionEntity
import com.example.data.local.XrayConfigEntity
import com.example.data.model.AetherWarpConfig
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.ConnectionStatus
import com.example.data.model.DnsTestResult
import com.example.data.model.DohTunnelMetrics
import com.example.data.model.FragmentConfig
import com.example.data.model.InstalledAppInfo
import com.example.data.model.LogEntry
import com.example.data.model.ProtocolType
import com.example.data.model.PsiphonChainConfig
import com.example.data.model.RoutingConfig
import com.example.data.model.ScanProgress
import com.example.data.model.ScannerConfig
import com.example.data.model.SplitTunnelMode
import com.example.data.model.ThemeMode
import com.example.data.model.TunnelMode
import com.example.data.model.TunnelStats
import com.example.data.model.UdpNoisePreset
import com.example.data.model.WarpEndpointCandidate
import com.example.data.preferences.BpbSettingsSnapshot
import com.example.data.repository.BpbRepository
import com.example.dns.ServerlessDohTunnelEngine
import com.example.dns.ServerlessDohVpnService
import com.example.vpn.BaseVpnService
import com.example.vpn.BpbVpnService
import com.example.vpn.VpnStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BpbUiState(
    val tunnelStats: TunnelStats = TunnelStats(),
    val settings: BpbSettingsSnapshot = BpbSettingsSnapshot(),
    val xrayConfigs: List<XrayConfigEntity> = emptyList(),
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val cleanIps: List<CleanIpEntity> = emptyList(),
    val warpCandidates: List<WarpEndpointCandidate> = emptyList(),
    val isDiscoveringWarp: Boolean = false,
    val scannerConfig: ScannerConfig = ScannerConfig(),
    val scanProgress: ScanProgress = ScanProgress(),
    val dnsTestResults: List<DnsTestResult> = emptyList(),
    val isTestingDns: Boolean = false,
    val isImporting: Boolean = false,
    val isPingingConfigs: Boolean = false,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
) {
    val activeXrayConfig: XrayConfigEntity?
        get() = xrayConfigs.firstOrNull { it.isActive }
            ?: xrayConfigs.firstOrNull { it.id == settings.activeProfileId }
            ?: xrayConfigs.firstOrNull()
}

class BpbViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BpbRepository(application)

    private val _scannerConfig = MutableStateFlow(ScannerConfig())
    private val _scanProgress = MutableStateFlow(ScanProgress())
    private val _dnsTestResults = MutableStateFlow<List<DnsTestResult>>(emptyList())
    private val _isTestingDns = MutableStateFlow(false)
    private val _isImporting = MutableStateFlow(false)
    private val _isPingingConfigs = MutableStateFlow(false)
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val _isLoadingApps = MutableStateFlow(false)
    private val _warpCandidates = MutableStateFlow<List<WarpEndpointCandidate>>(emptyList())
    private val _isDiscoveringWarp = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scanJob: Job? = null

    val logs: StateFlow<List<LogEntry>> = BpbLogBuffer.logs
    val dohMetrics: StateFlow<DohTunnelMetrics> = ServerlessDohTunnelEngine.metrics

    private data class CoreSnapshot(
        val stats: TunnelStats,
        val settings: BpbSettingsSnapshot,
        val configs: List<XrayConfigEntity>,
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
        val warpCandidates: List<WarpEndpointCandidate>,
        val isDiscoveringWarp: Boolean,
    )

    private val coreFlow = combine(
        VpnStateHolder.stats,
        repository.settingsFlow,
        repository.xrayConfigsFlow,
        repository.subscriptionsFlow,
        repository.cleanIpsFlow
    ) { stats, settings, configs, subs, ips ->
        CoreSnapshot(stats, settings, configs, subs, ips)
    }

    private val auxFlow = combine(
        _scannerConfig,
        _scanProgress,
        _dnsTestResults,
        _isTestingDns,
        _isImporting,
        _isPingingConfigs,
        _installedApps,
        _isLoadingApps,
        _warpCandidates,
        _isDiscoveringWarp
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
            isLoadingApps = arr[7] as Boolean,
            warpCandidates = arr[8] as List<WarpEndpointCandidate>,
            isDiscoveringWarp = arr[9] as Boolean
        )
    }

    val uiState: StateFlow<BpbUiState> = combine(coreFlow, auxFlow) { core, aux ->
        BpbUiState(
            tunnelStats = core.stats,
            settings = core.settings,
            xrayConfigs = core.configs,
            subscriptions = core.subs,
            cleanIps = core.ips,
            warpCandidates = aux.warpCandidates,
            isDiscoveringWarp = aux.isDiscoveringWarp,
            scannerConfig = aux.scannerConfig,
            scanProgress = aux.scanProgress,
            dnsTestResults = aux.dnsResults,
            isTestingDns = aux.isTestingDns,
            isImporting = aux.isImporting,
            isPingingConfigs = aux.isPinging,
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
        val targetService = if (uiState.value.settings.tunnelMode == TunnelMode.SERVERLESS_DOH) {
            ServerlessDohVpnService::class.java
        } else {
            BpbVpnService::class.java
        }
        val intent = Intent(ctx, targetService).apply {
            action = BaseVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun startServerlessDohService() {
        viewModelScope.launch {
            repository.setTunnelMode(TunnelMode.SERVERLESS_DOH)
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, ServerlessDohVpnService::class.java).apply {
                action = BaseVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    fun stopVpnService() {
        val ctx = getApplication<Application>()
        val activeService = if (uiState.value.tunnelStats.mode == TunnelMode.SERVERLESS_DOH) {
            ServerlessDohVpnService::class.java
        } else {
            BpbVpnService::class.java
        }
        ctx.startService(Intent(ctx, activeService).apply { action = BaseVpnService.ACTION_STOP })
    }

    fun setTunnelMode(mode: TunnelMode) {
        viewModelScope.launch {
            repository.setTunnelMode(mode)
            if (uiState.value.tunnelStats.status == ConnectionStatus.CONNECTED) {
                emitMessage("Mode updated. Reconnect tunnel to apply.")
            }
        }
    }

    fun updateAetherConfig(config: AetherWarpConfig) {
        viewModelScope.launch { repository.updateAetherConfig(config) }
    }

    fun updatePsiphonConfig(config: PsiphonChainConfig) {
        viewModelScope.launch { repository.updatePsiphonConfig(config) }
    }

    fun discoverWarpEndpoints() {
        if (_isDiscoveringWarp.value) return
        val aether = uiState.value.settings.aetherConfig
        viewModelScope.launch {
            _isDiscoveringWarp.value = true
            val found = repository.discoverWarpEndpoints(aether.transportMode, aether.masqueSni)
            _warpCandidates.value = found
            _isDiscoveringWarp.value = false
            if (found.isNotEmpty()) {
                val best = found.first()
                emitMessage("Discovered ${found.size} WARP gateway(s). Fastest: ${best.ip} (${best.latencyMs}ms)")
            } else {
                emitMessage("No WARP gateways responded. Try switching to MASQUE or Gool.")
            }
        }
    }

    fun applyWarpCandidate(candidate: WarpEndpointCandidate) {
        viewModelScope.launch {
            val current = uiState.value.settings.aetherConfig
            repository.updateAetherConfig(
                current.copy(endpointHost = candidate.ip, endpointPort = candidate.port)
            )
            emitMessage("Active Aether gateway set to ${candidate.ip}:${candidate.port}")
        }
    }

    fun selectXrayConfig(config: XrayConfigEntity) {
        viewModelScope.launch {
            repository.setActiveXrayConfig(config.id)
            repository.setTunnelMode(TunnelMode.PROFILE_TUNNEL)
            emitMessage("Selected ${config.name}")
        }
    }

    fun updateXrayObfuscation(config: XrayConfigEntity) {
        viewModelScope.launch {
            repository.updateXrayObfuscation(config)
            emitMessage("Saved obfuscation params for ${config.name}")
        }
    }

    fun importFromUriOrPayload(rawText: String) {
        viewModelScope.launch {
            _isImporting.value = true
            repository.importFromUriOrPayload(rawText)
                .onSuccess { count -> emitMessage("Imported $count Xray config(s)") }
                .onFailure { err -> emitMessage(err.message ?: "Import failed") }
            _isImporting.value = false
        }
    }

    fun importSubscription(name: String, url: String) {
        viewModelScope.launch {
            _isImporting.value = true
            repository.importSubscriptionUrl(name, url)
                .onSuccess { count -> emitMessage("Imported $count config(s) from subscription") }
                .onFailure { err -> emitMessage(err.message ?: "Subscription fetch failed") }
            _isImporting.value = false
        }
    }

    fun refreshSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            _isImporting.value = true
            repository.refreshSubscription(sub)
                .onSuccess { count -> emitMessage("Updated ${sub.name}: $count configs") }
                .onFailure { err -> emitMessage("Refresh failed: ${err.message}") }
            _isImporting.value = false
        }
    }

    fun createXrayConfigFromWizard(
        name: String,
        protocol: ProtocolType,
        serverAddress: String,
        sni: String,
        port: Int,
        uuidOrSecret: String,
        transport: String,
        path: String,
        fingerprint: String,
        fragmentEnabled: Boolean,
        fragmentPackets: String,
        fragmentLength: String,
        fragmentInterval: String,
        udpNoiseEnabled: Boolean,
        muxEnabled: Boolean,
        psiphonChainEnabled: Boolean,
    ) {
        viewModelScope.launch {
            val host = sni.trim()
            val address = serverAddress.trim().ifEmpty { host }
            val entity = XrayConfigEntity(
                name = name.trim().ifEmpty { "Xray ${protocol.label} • $address" },
                protocol = protocol.name,
                serverAddress = address,
                port = port,
                userIdOrPassword = uuidOrSecret.trim(),
                sni = host.ifEmpty { address },
                hostHeader = host.ifEmpty { address },
                pathOrServiceName = path.trim().ifEmpty { "/" },
                networkTransport = transport,
                securityType = "tls",
                tlsFingerprint = fingerprint,
                fragmentEnabled = fragmentEnabled,
                fragmentPackets = fragmentPackets,
                fragmentLength = fragmentLength,
                fragmentInterval = fragmentInterval,
                udpNoiseEnabled = udpNoiseEnabled,
                muxEnabled = muxEnabled,
                psiphonChainEnabled = psiphonChainEnabled
            )
            repository.saveXrayConfig(entity, activate = true)
            repository.setTunnelMode(TunnelMode.PROFILE_TUNNEL)
            emitMessage("Saved Xray config '${entity.name}' to Room DB")
        }
    }

    fun deleteXrayConfig(config: XrayConfigEntity) {
        viewModelScope.launch {
            repository.deleteXrayConfig(config)
            emitMessage("Removed ${config.name}")
        }
    }

    fun togglePinXrayConfig(config: XrayConfigEntity) {
        viewModelScope.launch { repository.togglePinXrayConfig(config) }
    }

    fun pingAllConfigs() {
        if (_isPingingConfigs.value) return
        viewModelScope.launch {
            _isPingingConfigs.value = true
            repository.pingAllXrayConfigs()
            _isPingingConfigs.value = false
            emitMessage("Xray config latency check complete")
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

    fun applyCleanIpToActiveConfig(ip: String) {
        viewModelScope.launch {
            val active = uiState.value.activeXrayConfig
            if (active != null) {
                repository.applyCleanIpToXrayConfig(active.id, ip)
            }
            val aether = uiState.value.settings.aetherConfig
            repository.updateAetherConfig(aether.copy(endpointHost = ip))
            val targetLabel = active?.name ?: "Aether WARP"
            emitMessage("Applied optimal Clean IP $ip to $targetLabel")
        }
    }

    fun applyCleanIpAsDohBootstrap(ip: String) {
        viewModelScope.launch {
            repository.setDohBootstrapIp(ip)
            emitMessage("Pinned $ip as Serverless DoH Bootstrap IP")
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

    fun saveCustomDoh(url: String, fakeSni: String, bootstrapIp: String? = null) {
        viewModelScope.launch {
            repository.setCustomDoh(url, fakeSni, bootstrapIp)
            emitMessage("Saved Serverless DoH & Bootstrap settings")
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

    fun buildXrayJsonFor(config: XrayConfigEntity): String =
        repository.buildXrayJson(config, uiState.value.settings)

    fun clearLogs() = BpbLogBuffer.clear()

    private fun emitMessage(msg: String) {
        _messages.tryEmit(msg)
    }
}
