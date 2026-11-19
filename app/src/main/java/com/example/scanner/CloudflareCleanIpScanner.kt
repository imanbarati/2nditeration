package com.example.scanner

import com.example.core.BpbLogBuffer
import com.example.data.local.CleanIpEntity
import com.example.data.model.ScanProgress
import com.example.data.model.ScannerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.roundToInt
import kotlin.random.Random

sealed interface CleanIpScanEvent {
    data class Progress(val progress: ScanProgress) : CleanIpScanEvent
    data class EndpointFound(val endpoint: CleanIpEntity) : CleanIpScanEvent
    data class Completed(val optimalEndpoints: List<CleanIpEntity>) : CleanIpScanEvent
}

/**
 * Dedicated Cloudflare Clean IP scanning module.
 * Probes Cloudflare Anycast IPv4 ranges across TCP connect, TLS+SNI handshake,
 * and optional `/cdn-cgi/trace` datacenter (colo) & throughput checks, then computes
 * a composite optimality score (0–100) to rank optimal VPN server endpoints.
 */
object CloudflareCleanIpScanner {

    const val ALL_CLOUDFLARE_TOKEN = "ALL"

    val officialCloudflareCidrs = listOf(
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "162.159.0.0/16",
        "188.114.96.0/20",
        "198.41.128.0/17",
        "141.101.64.0/18",
        "108.162.192.0/18",
    )

    val tlsSupportedPorts = setOf(443, 2053, 2083, 2087, 2096, 8443)

    /**
     * Reactive Flow API emitting real-time scan progress, individual clean IP hits,
     * and the final sorted list of optimal endpoints.
     */
    fun scanFlow(config: ScannerConfig): Flow<CleanIpScanEvent> = channelFlow {
        val finalList = runScan(
            config = config,
            onProgress = { progress -> trySend(CleanIpScanEvent.Progress(progress)) },
            onHit = { hit -> trySend(CleanIpScanEvent.EndpointFound(hit)) }
        )
        send(CleanIpScanEvent.Completed(finalList))
    }

    suspend fun runScan(
        config: ScannerConfig,
        onProgress: (ScanProgress) -> Unit = {},
        onHit: (CleanIpEntity) -> Unit = {},
    ): List<CleanIpEntity> = withContext(Dispatchers.IO) {
        val candidatePairs = sampleCandidates(config.cidrRange, config.sampleCount)
        if (candidatePairs.isEmpty()) {
            BpbLogBuffer.warn("SCANNER", "Invalid or empty CIDR range: '${config.cidrRange}'")
            return@withContext emptyList()
        }

        BpbLogBuffer.info(
            "SCANNER",
            "Scanning ${candidatePairs.size} Cloudflare IPs (${config.cidrRange}) on port ${config.port} | TLS=${config.verifyTls} | Trace=${config.verifyCloudflareTrace}"
        )

        val semaphore = Semaphore(config.concurrency.coerceIn(1, 32))
        val tested = AtomicInteger(0)
        val found = AtomicInteger(0)
        val bestEndpoint = AtomicReference<CleanIpEntity?>(null)
        val results = mutableListOf<CleanIpEntity>()

        coroutineScope {
            val jobs = candidatePairs.map { (ip, sourceCidr) ->
                async {
                    if (!isActive) return@async null
                    semaphore.withPermit {
                        if (!isActive) return@withPermit null
                        val hit = probeEndpoint(ip, sourceCidr, config)
                        val t = tested.incrementAndGet()
                        if (hit != null) {
                            found.incrementAndGet()
                            synchronized(results) { results.add(hit) }
                            bestEndpoint.updateAndGet { current ->
                                if (current == null || hit.score > current.score ||
                                    (hit.score == current.score && hit.latencyMs < current.latencyMs)
                                ) hit else current
                            }
                            onHit(hit)
                        }
                        val best = bestEndpoint.get()
                        onProgress(
                            ScanProgress(
                                isScanning = true,
                                testedCount = t,
                                totalCount = candidatePairs.size,
                                foundCount = found.get(),
                                currentIp = ip,
                                bestIpSoFar = best?.ip.orEmpty(),
                                bestLatencySoFarMs = best?.latencyMs
                            )
                        )
                        hit
                    }
                }
            }
            jobs.awaitAll()
        }

        val ranked = results.sortedWith(
            compareByDescending<CleanIpEntity> { it.score }.thenBy { it.latencyMs }
        )
        val topMsg = ranked.firstOrNull()?.let {
            "Optimal: ${it.ip} (${it.latencyMs}ms, colo=${it.colo.ifEmpty { "EDGE" }}, score=${it.score})"
        } ?: "No responsive clean IPs under ${config.maxLatencyMs}ms"
        BpbLogBuffer.info("SCANNER", "Scan complete: ${ranked.size}/${candidatePairs.size} clean IPs. $topMsg")
        ranked
    }

    /**
     * Quickly selects the single optimal endpoint from a list of scanned IPs or runs a fast targeted scan.
     */
    suspend fun findOptimalEndpoint(
        config: ScannerConfig = ScannerConfig(sampleCount = 24),
    ): CleanIpEntity? = runScan(config).firstOrNull()

    private fun probeEndpoint(
        ip: String,
        sourceCidr: String,
        config: ScannerConfig,
    ): CleanIpEntity? {
        val socket = Socket()
        return try {
            val timeout = config.maxLatencyMs.coerceIn(150, 3500)
            val tcpStartNs = System.nanoTime()
            socket.connect(InetSocketAddress(ip, config.port), timeout)
            val tcpMs = ((System.nanoTime() - tcpStartNs) / 1_000_000L).coerceAtLeast(1L)
            if (tcpMs > config.maxLatencyMs) {
                socket.close()
                return null
            }

            var tlsOk = false
            var tlsProtocol = ""
            var colo = ""
            var finalLatencyMs = tcpMs
            var speedKbps: Int? = null

            val shouldUseTls = config.verifyTls && config.port in tlsSupportedPorts
            if (shouldUseTls) {
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sniHost = config.sni.trim().ifEmpty { "speed.cloudflare.com" }
                val sslSocket = sslFactory.createSocket(socket, sniHost, config.port, true) as SSLSocket
                sslSocket.soTimeout = timeout
                val params = sslSocket.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                sslSocket.sslParameters = params

                val tlsStartNs = System.nanoTime()
                sslSocket.startHandshake()
                val tlsMs = ((System.nanoTime() - tlsStartNs) / 1_000_000L).coerceAtLeast(1L)
                tlsOk = true
                tlsProtocol = sslSocket.session?.protocol.orEmpty()
                finalLatencyMs = tcpMs + tlsMs

                if ((config.verifyCloudflareTrace || config.runSpeedProbe) && finalLatencyMs <= config.maxLatencyMs) {
                    val trace = probeCloudflareTrace(sslSocket, sniHost)
                    if (trace != null) {
                        colo = trace.colo
                        if (config.runSpeedProbe) {
                            speedKbps = trace.kbps
                        }
                    }
                }
                sslSocket.close()
            } else {
                socket.close()
            }

            if (finalLatencyMs > config.maxLatencyMs) return null

            val score = computeOptimalityScore(
                latencyMs = finalLatencyMs,
                maxLatencyMs = config.maxLatencyMs,
                tlsVerified = tlsOk,
                tlsProtocol = tlsProtocol,
                colo = colo,
                downloadKbps = speedKbps
            )

            CleanIpEntity(
                ip = ip,
                port = config.port,
                sni = config.sni,
                cidr = sourceCidr,
                latencyMs = finalLatencyMs,
                tlsSuccess = tlsOk,
                tlsProtocol = tlsProtocol,
                colo = colo,
                downloadKbps = speedKbps,
                score = score
            )
        } catch (_: Exception) {
            runCatching { socket.close() }
            null
        }
    }

    private data class TraceProbeResult(val colo: String, val kbps: Int)

    private fun probeCloudflareTrace(sslSocket: SSLSocket, host: String): TraceProbeResult? {
        return runCatching {
            val req = "GET /cdn-cgi/trace HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: BPB-CleanIP-Scanner/2.0\r\n\r\n"
            val startNs = System.nanoTime()
            sslSocket.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            sslSocket.outputStream.flush()

            val sb = StringBuilder()
            val buf = ByteArray(4096)
            var totalBytes = 0
            while (totalBytes < 16384) {
                val read = sslSocket.inputStream.read(buf)
                if (read <= 0) break
                totalBytes += read
                sb.append(String(buf, 0, read, Charsets.UTF_8))
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val kbps = ((totalBytes * 8L) / elapsedMs).toInt().coerceAtLeast(1)
            val body = sb.toString()
            val colo = parseColoFromTrace(body)
            TraceProbeResult(colo = colo, kbps = kbps)
        }.getOrNull()
    }

    fun parseColoFromTrace(traceResponse: String): String {
        val line = traceResponse.lineSequence().firstOrNull { it.startsWith("colo=", ignoreCase = true) }
        return line?.substringAfter("=")?.trim()?.uppercase()?.take(6).orEmpty()
    }

    /**
     * Computes a 0..100 optimality score weighing latency (60 pts), TLS 1.3 verification (20 pts),
     * confirmed Cloudflare edge datacenter colo (10 pts), and throughput probe (10 pts).
     */
    fun computeOptimalityScore(
        latencyMs: Long,
        maxLatencyMs: Int,
        tlsVerified: Boolean,
        tlsProtocol: String,
        colo: String,
        downloadKbps: Int?,
    ): Int {
        val ceiling = maxLatencyMs.coerceAtLeast(100).toFloat()
        val latencyRatio = (1f - (latencyMs.toFloat() / ceiling)).coerceIn(0f, 1f)
        val latencyPoints = (latencyRatio * 60f).roundToInt()
        val tlsPoints = when {
            tlsVerified && tlsProtocol.contains("1.3") -> 20
            tlsVerified -> 15
            else -> 0
        }
        val coloPoints = if (colo.isNotBlank()) 10 else 0
        val speedPoints = when {
            downloadKbps == null -> 5
            downloadKbps >= 500 -> 10
            downloadKbps >= 150 -> 7
            else -> 4
        }
        return (latencyPoints + tlsPoints + coloPoints + speedPoints).coerceIn(1, 100)
    }

    fun sampleCandidates(cidrSpec: String, totalCount: Int): List<Pair<String, String>> {
        val trimmed = cidrSpec.trim()
        val cidrs = when {
            trimmed.equals(ALL_CLOUDFLARE_TOKEN, ignoreCase = true) || trimmed.isEmpty() -> officialCloudflareCidrs
            trimmed.contains(",") -> trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> listOf(trimmed)
        }
        if (cidrs.isEmpty()) return emptyList()

        val perCidr = (totalCount / cidrs.size).coerceAtLeast(1)
        val remainder = (totalCount - perCidr * cidrs.size).coerceAtLeast(0)
        val out = LinkedHashMap<String, String>()

        cidrs.forEachIndexed { index, cidr ->
            val quota = perCidr + if (index < remainder) 1 else 0
            sampleIpsFromCidr(cidr, quota).forEach { ip ->
                out.putIfAbsent(ip, cidr)
            }
        }
        return out.entries.map { it.key to it.value }
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
