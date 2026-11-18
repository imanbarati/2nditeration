package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.BpbLogBuffer
import com.example.core.DnsOverHttpsClient
import com.example.core.ProfileLatencyTester
import com.example.data.local.BpbDatabase
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.ConnectionStatus
import com.example.data.model.RoutingConfig
import com.example.data.model.SplitTunnelMode
import com.example.data.model.TunnelMode
import com.example.data.preferences.BpbPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class BpbVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.vpn.START"
        const val ACTION_STOP = "com.example.vpn.STOP"
        private const val CHANNEL_ID = "bpb_vpn_tunnel_channel"
        private const val NOTIFICATION_ID = 7041
        private const val VIRTUAL_TUN_IP = "10.10.0.1"
        private const val VIRTUAL_DNS_IP = "10.10.0.2"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnelInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                BpbLogBuffer.info("VPN", "Disconnect requested by user.")
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundNotification("Verifying BPB tunnel…")
                launchTunnel()
                return START_STICKY
            }
        }
    }

    private fun launchTunnel() {
        tunnelJob?.cancel()
        tunnelJob = serviceScope.launch {
            try {
                val prefsRepo = BpbPreferencesRepository(applicationContext)
                val settings = prefsRepo.getSnapshot()
                val dnsPreset = BuiltInDnsPresets.all.find { it.id == settings.activeDnsPresetId }
                    ?: BuiltInDnsPresets.all.first()
                val dohUrl = settings.customDohUrl.takeIf { it.isNotBlank() } ?: dnsPreset.endpoint

                val profile = settings.activeProfileId?.let { id ->
                    BpbDatabase.getInstance(applicationContext).vpnProfileDao().getProfileById(id)
                }

                val activeMode = if (settings.tunnelMode == TunnelMode.PROFILE_TUNNEL && profile != null) {
                    TunnelMode.PROFILE_TUNNEL
                } else {
                    TunnelMode.SERVERLESS_DOH
                }

                val displayName = if (activeMode == TunnelMode.PROFILE_TUNNEL && profile != null) {
                    profile.name
                } else {
                    "${dnsPreset.name} (Serverless)"
                }

                val endpointLabel = if (activeMode == TunnelMode.PROFILE_TUNNEL && profile != null) {
                    "${profile.address}:${profile.port}"
                } else {
                    dnsPreset.bootstrapIp
                }

                VpnStateHolder.updateStatus(
                    status = ConnectionStatus.VERIFYING,
                    mode = activeMode,
                    profileName = displayName,
                    endpoint = endpointLabel
                )
                BpbLogBuffer.info("VPN", "Smart Connection check: verifying $displayName ($endpointLabel)…")

                // Build protect()-aware SocketFactory so DoH / verification sockets never loop into TUN
                val protectedSocketFactory = object : SocketFactory() {
                    private val delegate = getDefault()
                    override fun createSocket(): Socket = delegate.createSocket().also { protect(it) }
                    override fun createSocket(host: String?, port: Int): Socket =
                        delegate.createSocket(host, port).also { protect(it) }
                    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                        delegate.createSocket(host, port, localHost, localPort).also { protect(it) }
                    override fun createSocket(host: InetAddress?, port: Int): Socket =
                        delegate.createSocket(host, port).also { protect(it) }
                    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                        delegate.createSocket(address, port, localAddress, localPort).also { protect(it) }
                }

                val dohClient = DnsOverHttpsClient.createClient(protectedSocketFactory)

                // Smart connection check: verify profile TCP/TLS or DoH reachability BEFORE marking CONNECTED
                val verifiedLatency: Long? = if (activeMode == TunnelMode.PROFILE_TUNNEL && profile != null) {
                    val rtt = ProfileLatencyTester.measureProfileLatency(profile, timeoutMs = 3500)
                    if (rtt == null) {
                        BpbLogBuffer.warn("VPN", "Profile handshake failed for ${profile.address}:${profile.port}; aborting false-positive connect.")
                        VpnStateHolder.updateStatus(
                            status = ConnectionStatus.ERROR,
                            mode = activeMode,
                            profileName = displayName,
                            endpoint = endpointLabel,
                            errorMessage = "Endpoint ${profile.address}:${profile.port} unreachable. Try scanning a Clean IP."
                        )
                        stopSelfCleanly()
                        return@launch
                    }
                    BpbLogBuffer.info("VPN", "Profile TLS handshake verified in ${rtt}ms.")
                    rtt
                } else {
                    val startNs = System.nanoTime()
                    val probeWire = DnsOverHttpsClient.buildDnsQuery("cloudflare.com", 1)
                    val resp = DnsOverHttpsClient.resolveWireSync(dohClient, dohUrl, probeWire)
                    if (resp == null) {
                        BpbLogBuffer.warn("VPN", "DoH probe failed for $dohUrl. Check internet connection.")
                        VpnStateHolder.updateStatus(
                            status = ConnectionStatus.ERROR,
                            mode = activeMode,
                            profileName = displayName,
                            endpoint = endpointLabel,
                            errorMessage = "DoH resolver $dohUrl unreachable."
                        )
                        stopSelfCleanly()
                        return@launch
                    }
                    val rtt = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    BpbLogBuffer.info("VPN", "Serverless DoH verified via $dohUrl in ${rtt}ms.")
                    rtt
                }

                // Configure TUN interface for encrypted DNS + split tunneling
                tunnelInterface?.close()
                val builder = Builder()
                    .setSession("BPB VPN • $displayName")
                    .setMtu(1500)
                    .addAddress(VIRTUAL_TUN_IP, 30)
                    .addDnsServer(VIRTUAL_DNS_IP)
                    .addRoute(VIRTUAL_DNS_IP, 32)

                when (settings.splitTunnelMode) {
                    SplitTunnelMode.ALL_APPS -> {
                        runCatching { builder.addDisallowedApplication(packageName) }
                    }
                    SplitTunnelMode.EXCLUDE_SELECTED -> {
                        runCatching { builder.addDisallowedApplication(packageName) }
                        settings.selectedPackages.forEach { pkg ->
                            if (pkg != packageName) runCatching { builder.addDisallowedApplication(pkg) }
                        }
                    }
                    SplitTunnelMode.INCLUDE_ONLY -> {
                        if (settings.selectedPackages.isEmpty()) {
                            runCatching { builder.addDisallowedApplication(packageName) }
                        } else {
                            settings.selectedPackages.forEach { pkg ->
                                if (pkg != packageName) runCatching { builder.addAllowedApplication(pkg) }
                            }
                        }
                    }
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    VpnStateHolder.updateStatus(
                        status = ConnectionStatus.ERROR,
                        errorMessage = "Failed to establish Android TUN interface."
                    )
                    BpbLogBuffer.error("VPN", "VpnService.Builder.establish() returned null.")
                    stopSelfCleanly()
                    return@launch
                }

                tunnelInterface = pfd
                VpnStateHolder.updateStatus(
                    status = ConnectionStatus.CONNECTED,
                    mode = activeMode,
                    profileName = displayName,
                    endpoint = endpointLabel,
                    verifiedLatencyMs = verifiedLatency
                )
                updateNotification("Protected • $displayName (${verifiedLatency ?: 0} ms)")
                BpbLogBuffer.info(
                    "VPN",
                    "TUN active (10.10.0.1 -> DoH $dohUrl) | Fragment=${settings.fragmentConfig.enabled} (${settings.fragmentConfig.packets}) | Noise=${settings.udpNoisePreset.label}"
                )

                runDnsTunLoop(pfd, dohClient, dohUrl, settings.routingConfig)
            } catch (e: Exception) {
                BpbLogBuffer.error("VPN", "Tunnel error: ${e.message}")
                VpnStateHolder.updateStatus(
                    status = ConnectionStatus.ERROR,
                    errorMessage = e.message ?: "Unexpected tunnel failure"
                )
                stopSelfCleanly()
            }
        }
    }

    private fun runDnsTunLoop(
        pfd: ParcelFileDescriptor,
        dohClient: OkHttpClient,
        dohUrl: String,
        routingConfig: RoutingConfig,
    ) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (!Thread.currentThread().isInterrupted) {
            val length = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (length <= 0) continue

            val packet = buffer.copyOf(length)
            // Only handle IPv4 UDP port 53 DNS packets directed to VIRTUAL_DNS_IP
            val version = (packet[0].toInt() shr 4) and 0xF
            if (version != 4 || length < 28) continue
            val ihl = (packet[0].toInt() and 0xF) * 4
            if (ihl < 20 || length < ihl + 8) continue
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 17) continue // UDP only

            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) continue

            val dnsQuery = packet.copyOfRange(ihl + 8, length)
            val queriedDomain = DnsOverHttpsClient.extractDomainFromDnsWire(dnsQuery) ?: "unknown"
            VpnStateHolder.recordPacketTraffic(txDelta = length.toLong(), rxDelta = 0L)

            // Handle DNS query asynchronously so concurrent lookups don't stall the TUN loop
            serviceScope.launch {
                val isBlocked = DnsOverHttpsClient.shouldBlockDomain(queriedDomain, routingConfig)
                val dnsResponse = if (isBlocked) {
                    BpbLogBuffer.debug("DNS", "BLOCKED $queriedDomain (Routing rule)")
                    DnsOverHttpsClient.buildNxdomainResponse(dnsQuery)
                } else {
                    val resolved = DnsOverHttpsClient.resolveWireSync(dohClient, dohUrl, dnsQuery)
                    if (resolved != null) {
                        BpbLogBuffer.debug("DNS", "DoH resolved $queriedDomain (${resolved.size}B)")
                    }
                    resolved
                }

                if (dnsResponse != null) {
                    val ipUdpPacket = buildIpv4UdpResponsePacket(
                        requestPacket = packet,
                        ihl = ihl,
                        payload = dnsResponse
                    )
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
    }

    private fun buildIpv4UdpResponsePacket(
        requestPacket: ByteArray,
        ihl: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val udpLength = 8 + payload.size
        val out = ByteArray(totalLength)

        // IPv4 Header (20 bytes)
        out[0] = 0x45.toByte()
        out[1] = 0x00.toByte()
        out[2] = ((totalLength shr 8) and 0xFF).toByte()
        out[3] = (totalLength and 0xFF).toByte()
        out[4] = requestPacket[4]
        out[5] = requestPacket[5]
        out[6] = 0x40.toByte() // Don't fragment
        out[7] = 0x00.toByte()
        out[8] = 64.toByte() // TTL
        out[9] = 17.toByte() // UDP
        // Checksum bytes 10..11 computed below
        // Swap src (12..15) and dst (16..19)
        System.arraycopy(requestPacket, 16, out, 12, 4)
        System.arraycopy(requestPacket, 12, out, 16, 4)

        val ipChecksum = computeChecksum(out, 0, 20)
        out[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header (8 bytes at offset 20)
        out[20] = requestPacket[ihl + 2]
        out[21] = requestPacket[ihl + 3]
        out[22] = requestPacket[ihl]
        out[23] = requestPacket[ihl + 1]
        out[24] = ((udpLength shr 8) and 0xFF).toByte()
        out[25] = (udpLength and 0xFF).toByte()
        out[26] = 0x00
        out[27] = 0x00

        System.arraycopy(payload, 0, out, 28, payload.size)

        // Optional in IPv4 UDP, compute full checksum with pseudo-header for strict stacks
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
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun computeUdpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L
        // Pseudo-header: srcIp (12..15), dstIp (16..19), zero+proto (17), udpLength
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += 17L
        sum += udpLength.toLong()

        var i = udpOffset
        val end = udpOffset + udpLength
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }
        val result = (sum.inv() and 0xFFFFL).toInt()
        return if (result == 0) 0xFFFF else result
    }

    private fun stopTunnel() {
        VpnStateHolder.updateStatus(ConnectionStatus.DISCONNECTING)
        tunnelJob?.cancel()
        runCatching { tunnelInterface?.close() }
        tunnelInterface = null
        VpnStateHolder.updateStatus(ConnectionStatus.IDLE)
        BpbLogBuffer.info("VPN", "Tunnel stopped cleanly.")
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundNotification(statusText: String) {
        ensureNotificationChannel()
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BpbVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BPB VPN")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Disconnect", stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.vpn_notification_channel_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onRevoke() {
        BpbLogBuffer.warn("VPN", "VPN permission revoked by system or another VPN app.")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        tunnelJob?.cancel()
        runCatching { tunnelInterface?.close() }
        serviceScope.cancel()
        super.onDestroy()
    }
}
