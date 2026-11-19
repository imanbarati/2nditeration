package com.example.dns

import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.TunnelMode
import com.example.data.preferences.BpbPreferencesRepository
import com.example.vpn.BaseVpnService
import com.example.vpn.VpnStateHolder
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import javax.net.SocketFactory

/**
 * Dedicated Android VpnService for Serverless DNS-over-HTTPS (DoH) DNS Tunneling.
 * Extends [BaseVpnService] to intercept device IPv4/UDP:53 DNS queries on the virtual TUN
 * interface and resolve them via [ServerlessDohTunnelEngine] using protected HTTPS sockets
 * with Clean IP bootstrap dialing and automatic censorship failover.
 */
class ServerlessDohVpnService : BaseVpnService() {

    private var dohEngine: ServerlessDohTunnelEngine? = null
    private var activeDohUrl: String = BuiltInDnsPresets.all.first().endpoint
    private var activeBootstrapIp: String = ""

    override suspend fun onPrepareSession(): TunnelSessionConfig {
        val prefs = BpbPreferencesRepository(applicationContext).getSnapshot()
        val preset = BuiltInDnsPresets.all.find { it.id == prefs.activeDnsPresetId }
            ?: BuiltInDnsPresets.all.first()

        activeDohUrl = prefs.customDohUrl.takeIf { it.isNotBlank() } ?: preset.endpoint
        activeBootstrapIp = prefs.dohBootstrapIp.ifBlank { preset.bootstrapIp }

        return TunnelSessionConfig(
            sessionName = "Serverless DoH • ${preset.name}",
            mode = TunnelMode.SERVERLESS_DOH,
            endpointLabel = activeBootstrapIp,
            chainLabel = "TUN UDP:53 → RFC 8484 DoH ($activeDohUrl) • Bootstrap $activeBootstrapIp",
            splitTunnelMode = prefs.splitTunnelMode,
            selectedPackages = prefs.selectedPackages
        )
    }

    override suspend fun onVerifyEndpoint(
        config: TunnelSessionConfig,
        protectedSocketFactory: SocketFactory,
    ): Result<Long> {
        val prefs = BpbPreferencesRepository(applicationContext).getSnapshot()
        dohEngine?.stop()
        val engine = ServerlessDohTunnelEngine(
            primaryDohUrl = activeDohUrl,
            bootstrapIp = activeBootstrapIp,
            routingConfig = prefs.routingConfig,
            socketFactory = protectedSocketFactory,
            enableLocalUdpListener = true,
            localUdpPort = 5353
        )
        dohEngine = engine
        return engine.verifyUpstreamSync()
    }

    override fun onSessionEstablished(config: TunnelSessionConfig, socketFactory: SocketFactory) {
        dohEngine?.start(serviceScope)
    }

    override fun onSessionStopped() {
        dohEngine?.stop()
        dohEngine = null
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
        if (protocol != 17) return // UDP

        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val dnsQuery = packet.copyOfRange(ihl + 8, length)
        VpnStateHolder.recordPacketTraffic(txDelta = length.toLong(), rxDelta = 0L)

        val engine = dohEngine ?: return
        serviceScope.launch {
            val outcome = engine.resolveWire(dnsQuery) ?: return@launch
            val replyPacket = DnsPacketBuilder.buildIpv4UdpResponse(packet, ihl, outcome.responseWire)
            synchronized(output) {
                runCatching { output.write(replyPacket) }
            }
            VpnStateHolder.recordPacketTraffic(
                txDelta = 0L,
                rxDelta = replyPacket.size.toLong(),
                dnsIncrement = 1L,
                blockedIncrement = if (outcome.blocked) 1L else 0L
            )
        }
    }
}

object DnsPacketBuilder {
    fun buildIpv4UdpResponse(
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
