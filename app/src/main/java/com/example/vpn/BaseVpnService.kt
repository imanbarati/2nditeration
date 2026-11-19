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
import com.example.data.model.ConnectionStatus
import com.example.data.model.SplitTunnelMode
import com.example.data.model.TunnelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Base Android VpnService lifecycle and TUN traffic routing abstraction.
 * Handles foreground service setup, Android TUN interface creation via VpnService.Builder,
 * per-app split tunneling, protected SocketFactory creation, and packet dispatch loops.
 */
abstract class BaseVpnService : VpnService() {

    data class TunnelSessionConfig(
        val sessionName: String,
        val mode: TunnelMode,
        val endpointLabel: String,
        val chainLabel: String = "",
        val localSocksAddress: String? = null,
        val tunAddress: String = "10.10.0.1",
        val tunPrefix: Int = 30,
        val dnsAddress: String = "10.10.0.2",
        val mtu: Int = 1500,
        val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
        val selectedPackages: Set<String> = emptySet(),
    )

    companion object {
        const val ACTION_START = "com.example.vpn.START"
        const val ACTION_STOP = "com.example.vpn.STOP"
        private const val CHANNEL_ID = "bpb_vpn_tunnel_channel"
        private const val NOTIFICATION_ID = 7041
    }

    protected val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected var tunnelInterface: ParcelFileDescriptor? = null
        private set
    private var tunnelJob: Job? = null

    protected abstract suspend fun onPrepareSession(): TunnelSessionConfig
    protected abstract suspend fun onVerifyEndpoint(
        config: TunnelSessionConfig,
        protectedSocketFactory: SocketFactory,
    ): Result<Long>

    protected abstract suspend fun onHandleTunPacket(
        packet: ByteArray,
        length: Int,
        output: FileOutputStream,
    )

    protected open fun onSessionEstablished(config: TunnelSessionConfig, socketFactory: SocketFactory) {}
    protected open fun onSessionStopped() {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                BpbLogBuffer.info("VPN", "Disconnect requested.")
                stopTunnelLifecycle()
                START_NOT_STICKY
            }
            else -> {
                startForegroundWithStatus("Preparing VPN tunnel…")
                startTunnelLifecycle()
                START_STICKY
            }
        }
    }

    private fun startTunnelLifecycle() {
        tunnelJob?.cancel()
        tunnelJob = serviceScope.launch {
            try {
                val config = onPrepareSession()
                VpnStateHolder.updateStatus(
                    status = ConnectionStatus.VERIFYING,
                    mode = config.mode,
                    profileName = config.sessionName,
                    endpoint = config.endpointLabel,
                    chainLabel = config.chainLabel,
                    localSocksAddress = config.localSocksAddress
                )

                val protectedFactory = createProtectedSocketFactory()
                val verification = onVerifyEndpoint(config, protectedFactory)
                val verifiedRtt = verification.getOrElse { err ->
                    BpbLogBuffer.warn("VPN", "Verification failed: ${err.message}")
                    VpnStateHolder.updateStatus(
                        status = ConnectionStatus.ERROR,
                        mode = config.mode,
                        profileName = config.sessionName,
                        endpoint = config.endpointLabel,
                        chainLabel = config.chainLabel,
                        errorMessage = err.message ?: "Tunnel verification failed"
                    )
                    stopSelfCleanly()
                    return@launch
                }

                val pfd = buildTunInterface(config)
                if (pfd == null) {
                    VpnStateHolder.updateStatus(
                        status = ConnectionStatus.ERROR,
                        errorMessage = "Android VpnService.Builder.establish() returned null."
                    )
                    stopSelfCleanly()
                    return@launch
                }

                tunnelInterface = pfd
                onSessionEstablished(config, protectedFactory)

                VpnStateHolder.updateStatus(
                    status = ConnectionStatus.CONNECTED,
                    mode = config.mode,
                    profileName = config.sessionName,
                    endpoint = config.endpointLabel,
                    chainLabel = config.chainLabel,
                    localSocksAddress = config.localSocksAddress,
                    verifiedLatencyMs = verifiedRtt
                )
                updateTunnelNotification("Protected • ${config.sessionName} (${verifiedRtt} ms)")

                runPacketLoop(pfd)
            } catch (e: Exception) {
                BpbLogBuffer.error("VPN", "Tunnel lifecycle error: ${e.message}")
                VpnStateHolder.updateStatus(
                    status = ConnectionStatus.ERROR,
                    errorMessage = e.message ?: "Unexpected tunnel failure"
                )
                stopSelfCleanly()
            }
        }
    }

    protected fun createProtectedSocketFactory(): SocketFactory = object : SocketFactory() {
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

    private fun buildTunInterface(config: TunnelSessionConfig): ParcelFileDescriptor? {
        tunnelInterface?.close()
        val builder = Builder()
            .setSession("BPB VPN • ${config.sessionName}")
            .setMtu(config.mtu)
            .addAddress(config.tunAddress, config.tunPrefix)
            .addDnsServer(config.dnsAddress)
            .addRoute(config.dnsAddress, 32)
            .allowBypass()

        when (config.splitTunnelMode) {
            SplitTunnelMode.ALL_APPS -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
            SplitTunnelMode.EXCLUDE_SELECTED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                config.selectedPackages.forEach { pkg ->
                    if (pkg != packageName) runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
            SplitTunnelMode.INCLUDE_ONLY -> {
                if (config.selectedPackages.isEmpty()) {
                    runCatching { builder.addDisallowedApplication(packageName) }
                } else {
                    config.selectedPackages.forEach { pkg ->
                        if (pkg != packageName) runCatching { builder.addAllowedApplication(pkg) }
                    }
                }
            }
        }
        return builder.establish()
    }

    private suspend fun runPacketLoop(pfd: ParcelFileDescriptor) {
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
            onHandleTunPacket(buffer.copyOf(length), length, output)
        }
    }

    protected fun stopTunnelLifecycle() {
        VpnStateHolder.updateStatus(ConnectionStatus.DISCONNECTING)
        tunnelJob?.cancel()
        onSessionStopped()
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

    private fun startForegroundWithStatus(statusText: String) {
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

    protected fun updateTunnelNotification(statusText: String) {
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
            Intent(this, this::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BPB VPN • Aether")
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
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
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
        stopTunnelLifecycle()
        super.onRevoke()
    }

    override fun onDestroy() {
        tunnelJob?.cancel()
        onSessionStopped()
        runCatching { tunnelInterface?.close() }
        serviceScope.cancel()
        super.onDestroy()
    }
}
