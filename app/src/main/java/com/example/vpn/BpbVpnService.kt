package com.example.vpn

import com.example.core.BpbLogBuffer
import com.example.core.LocalSocksProxyServer
import com.example.core.ProfileLatencyTester
import com.example.core.WarpEndpointScanner
import com.example.data.local.BpbDatabase
import com.example.data.local.XrayConfigEntity
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.PsiphonChainOrder
import com.example.data.model.TunnelMode
import com.example.data.preferences.BpbPreferencesRepository
import com.example.data.preferences.BpbSettingsSnapshot
import com.example.dns.DnsPacketBuilder
import com.example.dns.ServerlessDohTunnelEngine
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import javax.net.SocketFactory

/**
 * Concrete VPN tunnel service extending BaseVpnService.
 * Coordinates Aether WARP + Psiphon Chain (with user-space SOCKS5 127.0.0.1:1819),
 * Room-stored Xray Config verification, and Serverless DoH encrypted DNS tunneling.
 */
class BpbVpnService : BaseVpnService() {

    private var dohEngine: ServerlessDohTunnelEngine? = null
    private var dohUrl: String = BuiltInDnsPresets.all.first().endpoint
    private var dohBootstrapIp: String = ""
    private var latestSettings: BpbSettingsSnapshot = BpbSettingsSnapshot()
    private var localSocksProxy: LocalSocksProxyServer? = null

    override suspend fun onPrepareSession(): TunnelSessionConfig {
        val prefsRepo = BpbPreferencesRepository(applicationContext)
        val settings = prefsRepo.getSnapshot()
        latestSettings = settings

        val dnsPreset = BuiltInDnsPresets.all.find { it.id == settings.activeDnsPresetId }
            ?: BuiltInDnsPresets.all.first()
        dohUrl = settings.customDohUrl.takeIf { it.isNotBlank() } ?: dnsPreset.endpoint
        dohBootstrapIp = settings.dohBootstrapIp.ifBlank { dnsPreset.bootstrapIp }

        val db = BpbDatabase.getInstance(applicationContext)
        val activeXray = db.xrayConfigDao().getActiveConfigOnce()
            ?: settings.activeProfileId?.let { db.xrayConfigDao().getConfigById(it) }
            ?: db.vpnProfileDao().getAllProfilesOnce().firstOrNull()?.let {
                XrayConfigEntity.fromVpnProfile(it)
            }

        val resolvedMode = when {
            settings.tunnelMode == TunnelMode.PROFILE_TUNNEL && activeXray != null -> TunnelMode.PROFILE_TUNNEL
            settings.tunnelMode == TunnelMode.AETHER_WARP -> TunnelMode.AETHER_WARP
            else -> TunnelMode.SERVERLESS_DOH
        }

        val aether = settings.aetherConfig
        val psiphon = settings.psiphonConfig
        val chainText = when (resolvedMode) {
            TunnelMode.AETHER_WARP -> {
                if (psiphon.enabled) {
                    val reg = if (psiphon.region.code.isEmpty()) "Auto" else psiphon.region.code
                    val torSuffix = if (psiphon.torAfterChain) " → Tor" else ""
                    "${psiphon.chainOrder.routeSummary} (${psiphon.transport.label} • $reg)$torSuffix"
                } else {
                    "Aether ${aether.transportMode.label} Direct"
                }
            }
            TunnelMode.PROFILE_TUNNEL -> {
                val frag = if (activeXray?.fragmentEnabled == true) "Frag:${activeXray.fragmentPackets}" else "Direct"
                val psiphonFlag = if (activeXray?.psiphonChainEnabled == true) " + Psiphon" else ""
                "Xray ${activeXray?.protocol ?: "VLESS"} • $frag$psiphonFlag"
            }
            TunnelMode.SERVERLESS_DOH -> "DoH (${dnsPreset.name}) • Bootstrap $dohBootstrapIp"
        }

        val sessionName = when (resolvedMode) {
            TunnelMode.AETHER_WARP ->
                if (psiphon.enabled) "Aether ${aether.transportMode.flag} + Psiphon"
                else "Aether ${aether.transportMode.flag}"
            TunnelMode.PROFILE_TUNNEL -> activeXray?.name ?: "Xray Tunnel"
            TunnelMode.SERVERLESS_DOH -> "${dnsPreset.name} (Serverless DoH)"
        }

        val endpointLabel = when (resolvedMode) {
            TunnelMode.AETHER_WARP -> "${aether.endpointHost}:${aether.endpointPort}"
            TunnelMode.PROFILE_TUNNEL -> "${activeXray?.serverAddress ?: "1.1.1.1"}:${activeXray?.port ?: 443}"
            TunnelMode.SERVERLESS_DOH -> dohBootstrapIp
        }

        val socksAddr = if (resolvedMode == TunnelMode.AETHER_WARP && aether.localSocksEnabled) {
            "127.0.0.1:${aether.localSocksPort}"
        } else null

        return TunnelSessionConfig(
            sessionName = sessionName,
            mode = resolvedMode,
            endpointLabel = endpointLabel,
            chainLabel = chainText,
            localSocksAddress = socksAddr,
            splitTunnelMode = settings.splitTunnelMode,
            selectedPackages = settings.selectedPackages
        )
    }

    override suspend fun onVerifyEndpoint(
        config: TunnelSessionConfig,
        protectedSocketFactory: SocketFactory,
    ): Result<Long> {
        dohEngine?.stop()
        dohEngine = ServerlessDohTunnelEngine(
            primaryDohUrl = dohUrl,
            bootstrapIp = dohBootstrapIp,
            routingConfig = latestSettings.routingConfig,
            socketFactory = protectedSocketFactory,
            enableLocalUdpListener = true,
            localUdpPort = 5353
        )

        return when (config.mode) {
            TunnelMode.AETHER_WARP -> verifyAetherWarpAndChain(protectedSocketFactory)
            TunnelMode.PROFILE_TUNNEL -> verifyXrayConfigEndpoint()
            TunnelMode.SERVERLESS_DOH -> dohEngine!!.verifyUpstreamSync()
        }
    }

    private suspend fun verifyAetherWarpAndChain(protectedSocketFactory: SocketFactory): Result<Long> {
        val aether = latestSettings.aetherConfig
        val psiphon = latestSettings.psiphonConfig

        BpbLogBuffer.info(
            "AETHER",
            "Initializing ${aether.transportMode.label} (${aether.transportMode.flag}) • MASQUE SNI=${aether.masqueSni}"
        )

        var candidate = WarpEndpointScanner.probeEndpoint(
            ip = aether.endpointHost,
            port = aether.endpointPort,
            mode = aether.transportMode,
            masqueSni = aether.masqueSni,
            socketFactory = protectedSocketFactory
        )

        if (candidate == null && aether.autoDiscoverEndpoint) {
            BpbLogBuffer.info("AETHER", "Configured endpoint unreachable; auto-discovering clean WARP gateway…")
            val discovered = WarpEndpointScanner.discoverEndpoints(
                mode = aether.transportMode,
                masqueSni = aether.masqueSni,
                socketFactory = protectedSocketFactory,
                maxCandidates = 10
            )
            candidate = discovered.firstOrNull()
        }

        if (candidate == null) {
            return Result.failure(IllegalStateException("No reachable Aether WARP endpoint found. Run Endpoint Discovery."))
        }

        if (psiphon.enabled) {
            BpbLogBuffer.info(
                "PSIPHON",
                "Chain ready: ${psiphon.chainOrder.routeSummary} via ${psiphon.transport.id} (Region=${psiphon.region.label})"
            )
        }

        return Result.success(candidate.latencyMs)
    }

    private suspend fun verifyXrayConfigEndpoint(): Result<Long> {
        val db = BpbDatabase.getInstance(applicationContext)
        val active = db.xrayConfigDao().getActiveConfigOnce()
            ?: latestSettings.activeProfileId?.let { db.xrayConfigDao().getConfigById(it) }
            ?: return Result.failure(IllegalStateException("No active Xray config selected."))

        val rtt = ProfileLatencyTester.measureProfileLatency(active.toVpnProfileEntity(), timeoutMs = 3500)
            ?: return Result.failure(
                IllegalStateException("Xray endpoint ${active.serverAddress}:${active.port} unreachable. Scan a Clean IP.")
            )

        BpbLogBuffer.info(
            "XRAY",
            "Verified '${active.name}' (${active.protocol}/${active.networkTransport}) in ${rtt}ms | Fragment=${active.fragmentEnabled} (${active.fragmentPackets})"
        )
        return Result.success(rtt)
    }

    override fun onSessionEstablished(config: TunnelSessionConfig, socketFactory: SocketFactory) {
        dohEngine?.start(serviceScope)

        val aether = latestSettings.aetherConfig
        val psiphon = latestSettings.psiphonConfig
        if (config.mode == TunnelMode.AETHER_WARP && aether.localSocksEnabled) {
            val useUpstream = psiphon.enabled && psiphon.chainOrder == PsiphonChainOrder.PSIPHON_OVER_WARP
            localSocksProxy = LocalSocksProxyServer(
                port = aether.localSocksPort,
                socketFactory = socketFactory,
                upstreamHost = if (useUpstream) psiphon.upstreamHost else null,
                upstreamPort = if (useUpstream) psiphon.upstreamPort else null,
                onTraffic = { tx, rx, newConn ->
                    VpnStateHolder.recordPacketTraffic(
                        txDelta = tx,
                        rxDelta = rx,
                        proxiedIncrement = if (newConn) 1L else 0L
                    )
                }
            ).also { it.start(serviceScope) }
        }
    }

    override fun onSessionStopped() {
        dohEngine?.stop()
        dohEngine = null
        localSocksProxy?.stop()
        localSocksProxy = null
    }

    override suspend fun onHandleTunPacket(
        packet: ByteArray,
        length: Int,
        output: FileOutputStream,
    ) {
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4 || length < 28) return
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl + 8) return
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val dnsQuery = packet.copyOfRange(ihl + 8, length)
        VpnStateHolder.recordPacketTraffic(txDelta = length.toLong(), rxDelta = 0L)

        val engine = dohEngine ?: return
        serviceScope.launch {
            val outcome = engine.resolveWire(dnsQuery) ?: return@launch
            val ipUdpPacket = DnsPacketBuilder.buildIpv4UdpResponse(packet, ihl, outcome.responseWire)
            synchronized(output) {
                runCatching { output.write(ipUdpPacket) }
            }
            VpnStateHolder.recordPacketTraffic(
                txDelta = 0L,
                rxDelta = ipUdpPacket.size.toLong(),
                dnsIncrement = 1L,
                blockedIncrement = if (outcome.blocked) 1L else 0L
            )
        }
    }
}
