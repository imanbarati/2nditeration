package com.example.vpn

import com.example.core.BpbLogBuffer
import com.example.core.DnsOverHttpsClient
import com.example.core.LocalSocksProxyServer
import com.example.core.ProfileLatencyTester
import com.example.core.WarpEndpointScanner
import com.example.data.local.BpbDatabase
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.PsiphonChainOrder
import com.example.data.model.RoutingConfig
import com.example.data.model.TunnelMode
import com.example.data.preferences.BpbPreferencesRepository
import com.example.data.preferences.BpbSettingsSnapshot
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.FileOutputStream
import javax.net.SocketFactory

/**
 * Concrete VPN tunnel service extending BaseVpnService.
 * Coordinates Aether WARP + Psiphon Chain (with user-space SOCKS5 127.0.0.1:1819),
 * Room-stored Xray Config verification, and Serverless DoH encrypted DNS packet routing.
 */
class BpbVpnService : BaseVpnService() {

    private var dohClient: OkHttpClient? = null
    private var dohUrl: String = BuiltInDnsPresets.all.first().endpoint
    private var routingConfig: RoutingConfig = RoutingConfig()
    private var latestSettings: BpbSettingsSnapshot = BpbSettingsSnapshot()
    private var localSocksProxy: LocalSocksProxyServer? = null

    override suspend fun onPrepareSession(): TunnelSessionConfig {
        val prefsRepo = BpbPreferencesRepository(applicationContext)
        val settings = prefsRepo.getSnapshot()
        latestSettings = settings
        routingConfig = settings.routingConfig

        val dnsPreset = BuiltInDnsPresets.all.find { it.id == settings.activeDnsPresetId }
            ?: BuiltInDnsPresets.all.first()
        dohUrl = settings.customDohUrl.takeIf { it.isNotBlank() } ?: dnsPreset.endpoint

        val db = BpbDatabase.getInstance(applicationContext)
        val activeXray = db.xrayConfigDao().getActiveConfigOnce()
            ?: settings.activeProfileId?.let { db.xrayConfigDao().getConfigById(it) }
            ?: db.vpnProfileDao().getAllProfilesOnce().firstOrNull()?.let {
                com.example.data.local.XrayConfigEntity.fromVpnProfile(it)
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
            TunnelMode.SERVERLESS_DOH -> "DoH (${dnsPreset.name}) • Fake SNI ${settings.fakeSniHost}"
        }

        val sessionName = when (resolvedMode) {
            TunnelMode.AETHER_WARP ->
                if (psiphon.enabled) "Aether ${aether.transportMode.flag} + Psiphon"
                else "Aether ${aether.transportMode.flag}"
            TunnelMode.PROFILE_TUNNEL -> activeXray?.name ?: "Xray Tunnel"
            TunnelMode.SERVERLESS_DOH -> "${dnsPreset.name} (Serverless)"
        }

        val endpointLabel = when (resolvedMode) {
            TunnelMode.AETHER_WARP -> "${aether.endpointHost}:${aether.endpointPort}"
            TunnelMode.PROFILE_TUNNEL -> "${activeXray?.serverAddress ?: "1.1.1.1"}:${activeXray?.port ?: 443}"
            TunnelMode.SERVERLESS_DOH -> dnsPreset.bootstrapIp
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
        dohClient = DnsOverHttpsClient.createClient(protectedSocketFactory)

        return when (config.mode) {
            TunnelMode.AETHER_WARP -> verifyAetherWarpAndChain(protectedSocketFactory)
            TunnelMode.PROFILE_TUNNEL -> verifyXrayConfigEndpoint()
            TunnelMode.SERVERLESS_DOH -> verifyDohResolver()
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

    private fun verifyDohResolver(): Result<Long> {
        val client = dohClient ?: return Result.failure(IllegalStateException("DoH client uninitialized"))
        val startNs = System.nanoTime()
        val probeWire = DnsOverHttpsClient.buildDnsQuery("cloudflare.com", 1)
        val resp = DnsOverHttpsClient.resolveWireSync(client, dohUrl, probeWire)
            ?: return Result.failure(IllegalStateException("DoH resolver $dohUrl unreachable."))
        val rtt = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        BpbLogBuffer.info("DNS", "Serverless DoH verified via $dohUrl (${resp.size}B) in ${rtt}ms.")
        return Result.success(rtt)
    }

    override fun onSessionEstablished(config: TunnelSessionConfig, socketFactory: SocketFactory) {
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
        val queriedDomain = DnsOverHttpsClient.extractDomainFromDnsWire(dnsQuery) ?: "unknown"
        VpnStateHolder.recordPacketTraffic(txDelta = length.toLong(), rxDelta = 0L)

        val client = dohClient ?: return
        serviceScope.launch {
            val isBlocked = DnsOverHttpsClient.shouldBlockDomain(queriedDomain, routingConfig)
            val dnsResponse = if (isBlocked) {
                BpbLogBuffer.debug("DNS", "BLOCKED $queriedDomain (Routing rule)")
                DnsOverHttpsClient.buildNxdomainResponse(dnsQuery)
            } else {
                val resolved = DnsOverHttpsClient.resolveWireSync(client, dohUrl, dnsQuery)
                if (resolved != null) {
                    BpbLogBuffer.debug("DNS", "DoH resolved $queriedDomain (${resolved.size}B)")
                }
                resolved
            }

            if (dnsResponse != null) {
                val ipUdpPacket = buildIpv4UdpResponsePacket(packet, ihl, dnsResponse)
                synchronized(output) {
                    runCatching { output.write(ipUdpPacket) }
                }
                VpnStateHolder.recordPacketTraffic(
                    txDelta = 0L,
                    rxDelta = ipUdpPacket.size.toLong(),
                    dnsIncrement = 1L,
                    blockedIncrement = if (isBlocked) 1L else 0L
                )
            }
        }
    }

    private fun buildIpv4UdpResponsePacket(
        requestPacket: ByteArray,
        ihl: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val udpLength = 8 + payload.size
        val out = ByteArray(totalLength)

        out[0] = 0x45.toByte()
        out[1] = 0x00.toByte()
        out[2] = ((totalLength shr 8) and 0xFF).toByte()
        out[3] = (totalLength and 0xFF).toByte()
        out[4] = requestPacket[4]
        out[5] = requestPacket[5]
        out[6] = 0x40.toByte()
        out[7] = 0x00.toByte()
        out[8] = 64.toByte()
        out[9] = 17.toByte()
        System.arraycopy(requestPacket, 16, out, 12, 4)
        System.arraycopy(requestPacket, 12, out, 16, 4)

        val ipChecksum = computeChecksum(out, 0, 20)
        out[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        out[20] = requestPacket[ihl + 2]
        out[21] = requestPacket[ihl + 3]
        out[22] = requestPacket[ihl]
        out[23] = requestPacket[ihl + 1]
        out[24] = ((udpLength shr 8) and 0xFF).toByte()
        out[25] = (udpLength and 0xFF).toByte()
        out[26] = 0x00
        out[27] = 0x00

        System.arraycopy(payload, 0, out, 28, payload.size)

        val udpChecksum = computeUdpChecksum(out, 20, udpLength)
        out[26] = ((udpChecksum shr 8) and 0xFF).toByte()
        out[27] = (udpChecksum and 0xFF).toByte()

        return out
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFFL) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun computeUdpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += 17L
        sum += udpLength.toLong()
        var i = udpOffset
        val end = udpOffset + udpLength
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFFL) + (sum shr 16)
        val result = (sum.inv() and 0xFFFFL).toInt()
        return if (result == 0) 0xFFFF else result
    }
}
