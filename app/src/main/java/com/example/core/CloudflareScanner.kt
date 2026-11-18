package com.example.core

import com.example.data.local.CleanIpEntity
import com.example.data.model.ScanProgress
import com.example.data.model.ScannerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

object CloudflareScanner {

    val defaultCloudflareCidrs = listOf(
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "162.159.0.0/16",
        "188.114.96.0/20",
        "198.41.128.0/17",
        "141.101.64.0/18",
    )

    suspend fun runScan(
        config: ScannerConfig,
        onProgress: (ScanProgress) -> Unit,
        onHit: (CleanIpEntity) -> Unit,
    ): List<CleanIpEntity> = withContext(Dispatchers.IO) {
        val candidateIps = sampleIpsFromCidr(config.cidrRange, config.sampleCount)
        if (candidateIps.isEmpty()) {
            BpbLogBuffer.warn("SCANNER", "Invalid CIDR range: ${config.cidrRange}")
            return@withContext emptyList()
        }

        BpbLogBuffer.info(
            "SCANNER",
            "Starting Cloudflare scan: ${candidateIps.size} IPs from ${config.cidrRange} on port ${config.port} (TLS=${config.verifyTls})"
        )

        val semaphore = Semaphore(config.concurrency.coerceIn(1, 32))
        val tested = AtomicInteger(0)
        val found = AtomicInteger(0)
        val results = mutableListOf<CleanIpEntity>()

        coroutineScope {
            val jobs = candidateIps.map { ip ->
                async {
                    if (!isActive) return@async null
                    semaphore.withPermit {
                        if (!isActive) return@withPermit null
                        val result = probeSingleIp(ip, config)
                        val t = tested.incrementAndGet()
                        if (result != null) {
                            found.incrementAndGet()
                            synchronized(results) { results.add(result) }
                            onHit(result)
                        }
                        onProgress(
                            ScanProgress(
                                isScanning = true,
                                testedCount = t,
                                totalCount = candidateIps.size,
                                foundCount = found.get(),
                                currentIp = ip
                            )
                        )
                        result
                    }
                }
            }
            jobs.awaitAll()
        }

        val sorted = results.sortedBy { it.latencyMs }
        BpbLogBuffer.info(
            "SCANNER",
            "Scan complete: ${sorted.size}/${candidateIps.size} clean IPs within ${config.maxLatencyMs}ms."
        )
        sorted
    }

    private fun probeSingleIp(ip: String, config: ScannerConfig): CleanIpEntity? {
        val socket = Socket()
        return try {
            val timeout = config.maxLatencyMs.coerceIn(150, 3000)
            val startNs = System.nanoTime()
            socket.connect(InetSocketAddress(ip, config.port), timeout)
            val tcpMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            if (tcpMs > config.maxLatencyMs) {
                socket.close()
                return null
            }

            var tlsOk = false
            var finalLatency = tcpMs
            var speedKbps: Int? = null

            if (config.verifyTls && config.port in listOf(443, 2053, 2083, 2087, 2096, 8443)) {
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(socket, config.sni, config.port, true) as SSLSocket
                sslSocket.soTimeout = timeout
                val params = sslSocket.sslParameters
                if (config.sni.isNotBlank()) {
                    params.serverNames = listOf(SNIHostName(config.sni))
                }
                sslSocket.sslParameters = params
                val tlsStart = System.nanoTime()
                sslSocket.startHandshake()
                val tlsMs = ((System.nanoTime() - tlsStart) / 1_000_000L).coerceAtLeast(1L)
                finalLatency = tcpMs + tlsMs
                tlsOk = true

                if (config.runSpeedProbe && finalLatency <= config.maxLatencyMs) {
                    speedKbps = measureSimpleHttpsProbe(sslSocket, config.sni.ifEmpty { "speed.cloudflare.com" })
                }
                sslSocket.close()
            } else {
                socket.close()
            }

            if (finalLatency > config.maxLatencyMs) return null

            CleanIpEntity(
                ip = ip,
                port = config.port,
                sni = config.sni,
                cidr = config.cidrRange,
                latencyMs = finalLatency,
                tlsSuccess = tlsOk,
                downloadKbps = speedKbps
            )
        } catch (_: Exception) {
            runCatching { socket.close() }
            null
        }
    }

    private fun measureSimpleHttpsProbe(sslSocket: SSLSocket, host: String): Int? {
        return runCatching {
            val req = "GET /cdn-cgi/trace HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: BPB-Scanner/1.0\r\n\r\n"
            val start = System.nanoTime()
            sslSocket.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            sslSocket.outputStream.flush()
            val buf = ByteArray(4096)
            var totalBytes = 0
            while (true) {
                val read = sslSocket.inputStream.read(buf)
                if (read <= 0) break
                totalBytes += read
            }
            val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            ((totalBytes * 8L) / elapsedMs).toInt().coerceAtLeast(1)
        }.getOrNull()
    }

    fun sampleIpsFromCidr(cidr: String, count: Int): List<String> {
        val parts = cidr.trim().split("/")
        if (parts.size != 2) return emptyList()
        val baseIp = ipv4ToLong(parts[0]) ?: return emptyList()
        val prefix = parts[1].toIntOrNull()?.coerceIn(8, 32) ?: return emptyList()

        val hostBits = 32 - prefix
        if (hostBits == 0) return listOf(longToIpv4(baseIp))

        val mask = (-1L shl hostBits) and 0xFFFFFFFFL
        val network = baseIp and mask
        val totalHosts = (1L shl hostBits).coerceAtLeast(2L)
        val usableHosts = (totalHosts - 2L).coerceAtLeast(1L)
        val targetCount = count.coerceAtMost(usableHosts.toInt().coerceAtLeast(1))

        val picked = LinkedHashSet<String>()
        var attempts = 0
        while (picked.size < targetCount && attempts < targetCount * 6) {
            attempts++
            val offset = if (usableHosts <= Int.MAX_VALUE) {
                Random.nextLong(1L, usableHosts + 1L)
            } else {
                Random.nextLong(1L, Int.MAX_VALUE.toLong())
            }
            val ip = longToIpv4(network + offset)
            // Avoid .0 and .255 endings
            if (!ip.endsWith(".0") && !ip.endsWith(".255")) {
                picked.add(ip)
            }
        }
        return picked.toList()
    }

    private fun ipv4ToLong(ip: String): Long? {
        val octets = ip.trim().split(".")
        if (octets.size != 4) return null
        var result = 0L
        for (o in octets) {
            val v = o.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            result = (result shl 8) or v.toLong()
        }
        return result
    }

    private fun longToIpv4(value: Long): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }
}
