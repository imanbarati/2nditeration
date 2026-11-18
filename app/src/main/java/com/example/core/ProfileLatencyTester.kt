package com.example.core

import com.example.data.local.VpnProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object ProfileLatencyTester {

    suspend fun measureProfileLatency(
        profile: VpnProfileEntity,
        timeoutMs: Int = 2500,
    ): Long? = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            val startNs = System.nanoTime()
            socket.connect(InetSocketAddress(profile.address, profile.port), timeoutMs)
            val tcpMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

            if (profile.security.equals("tls", ignoreCase = true) ||
                profile.port in listOf(443, 2053, 2083, 2087, 2096, 8443)
            ) {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sniHost = profile.sni.ifBlank { profile.address }
                val sslSocket = factory.createSocket(socket, sniHost, profile.port, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs
                if (sniHost.isNotBlank() && !isIpAddress(sniHost)) {
                    val params = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    sslSocket.sslParameters = params
                }
                val tlsStart = System.nanoTime()
                sslSocket.startHandshake()
                val tlsMs = ((System.nanoTime() - tlsStart) / 1_000_000L).coerceAtLeast(1L)
                sslSocket.close()
                tcpMs + tlsMs
            } else {
                socket.close()
                tcpMs
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
            null
        }
    }

    private fun isIpAddress(value: String): Boolean {
        return value.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || value.contains(":")
    }
}
