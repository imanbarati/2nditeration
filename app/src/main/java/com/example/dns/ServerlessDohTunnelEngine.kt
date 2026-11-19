package com.example.dns

import com.example.core.BpbLogBuffer
import com.example.core.DnsOverHttpsClient
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.DnsQueryLogItem
import com.example.data.model.DohTunnelMetrics
import com.example.data.model.RoutingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * Serverless DoH (RFC 8484) DNS Tunneling Engine.
 *
 * Anti-censorship capabilities:
 * - Bootstrap Clean IP / custom DNS override so resolving the DoH server never touches ISP DNS.
 * - Automatic failover chain across resilient DoH upstreams if the primary endpoint is blocked.
 * - TTL-aware in-memory DNS wire response cache for instant repeat resolutions.
 * - Real-time Ad, Tracker, Malware, and Custom Domain filtering via RFC-compliant NXDOMAIN.
 * - Optional local UDP DNS listener (127.0.0.1:5353) in addition to Android VpnService TUN packets.
 */
class ServerlessDohTunnelEngine(
    private val primaryDohUrl: String,
    private val bootstrapIp: String = "",
    private val routingConfig: RoutingConfig = RoutingConfig(),
    private val socketFactory: SocketFactory? = null,
    private val enableLocalUdpListener: Boolean = true,
    private val localUdpPort: Int = 5353,
) {
    private data class CacheEntry(
        val wireResponse: ByteArray,
        val resolvedIps: List<String>,
        val expiresAtMs: Long,
        val upstreamLabel: String,
    )

    companion object {
        private val DNS_MEDIA_TYPE = "application/dns-message".toMediaType()
        private const val MAX_RECENT_QUERIES = 30
        private val queryIdSeq = AtomicLong(1L)

        private val _metrics = MutableStateFlow(DohTunnelMetrics())
        val metrics: StateFlow<DohTunnelMetrics> = _metrics.asStateFlow()

        fun resetMetrics() {
            _metrics.value = DohTunnelMetrics()
        }
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val failoverEndpoints: List<Pair<String, String>> = buildFailoverList(primaryDohUrl)
    private val okHttpClient: OkHttpClient = buildProtectedDohClient()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var udpSocket: DatagramSocket? = null
    private var udpJob: Job? = null
    private var totalLatencyAccumMs = AtomicLong(0L)
    private var latencySamples = AtomicLong(0L)

    fun start(scope: CoroutineScope) {
        val boundPort = if (enableLocalUdpListener) startLocalUdpForwarder(scope) else null
        _metrics.value = DohTunnelMetrics(
            isRunning = true,
            activeUpstream = primaryDohUrl,
            bootstrapIp = bootstrapIp.ifBlank { "System/Auto" },
            localUdpPort = boundPort
        )
        BpbLogBuffer.info(
            "DOH-TUNNEL",
            "Serverless DoH engine active -> $primaryDohUrl (Bootstrap=${bootstrapIp.ifBlank { "Auto" }}, LocalUDP=${boundPort ?: "Off"})"
        )
    }

    fun stop() {
        udpJob?.cancel()
        udpJob = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        cache.clear()
        _metrics.update { it.copy(isRunning = false, localUdpPort = null) }
        BpbLogBuffer.info("DOH-TUNNEL", "Serverless DoH engine stopped.")
    }

    fun clearCache() {
        cache.clear()
        BpbLogBuffer.info("DOH-TUNNEL", "Cleared Serverless DoH DNS cache.")
    }

    /**
     * Verifies upstream Serverless DoH reachability before establishing the VPN interface.
     */
    fun verifyUpstreamSync(): Result<Long> {
        val probe = DnsOverHttpsClient.buildDnsQuery("cloudflare.com", qType = 1)
        val startNs = System.nanoTime()
        val outcome = resolveWire(probe)
        val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        return if (outcome != null && !outcome.blocked && outcome.responseWire.size >= 12) {
            Result.success(rttMs)
        } else {
            Result.failure(IllegalStateException("All Serverless DoH upstreams failed verification."))
        }
    }

    data class ResolutionOutcome(
        val domain: String,
        val responseWire: ByteArray,
        val resolvedIps: List<String>,
        val latencyMs: Long,
        val fromCache: Boolean,
        val blocked: Boolean,
        val upstreamLabel: String,
    )

    /**
     * Resolves a raw RFC 1035 DNS wire query through blocklist -> TTL cache -> Serverless DoH failover chain.
     */
    fun resolveWire(dnsQueryWire: ByteArray): ResolutionOutcome? {
        if (dnsQueryWire.size < 12) return null
        val domain = DnsOverHttpsClient.extractDomainFromDnsWire(dnsQueryWire) ?: "unknown"
        val qType = extractQuestionType(dnsQueryWire)
        val cacheKey = "${domain.lowercase()}:$qType"

        // 1. Censorship/Privacy Blocklist check
        if (DnsOverHttpsClient.shouldBlockDomain(domain, routingConfig)) {
            val nx = DnsOverHttpsClient.buildNxdomainResponse(dnsQueryWire)
            recordQueryMetrics(
                domain = domain,
                ips = emptyList(),
                latencyMs = 0L,
                fromCache = false,
                blocked = true,
                upstreamLabel = "Blocked"
            )
            BpbLogBuffer.debug("DOH-TUNNEL", "BLOCKED $domain (NXDOMAIN)")
            return ResolutionOutcome(domain, nx, emptyList(), 0L, fromCache = false, blocked = true, upstreamLabel = "Blocked")
        }

        // 2. TTL Cache check (patch transaction ID to match current request)
        val now = System.currentTimeMillis()
        val cached = cache[cacheKey]
        if (cached != null && cached.expiresAtMs > now && cached.wireResponse.size >= 12) {
            val patched = cached.wireResponse.copyOf()
            patched[0] = dnsQueryWire[0]
            patched[1] = dnsQueryWire[1]
            recordQueryMetrics(
                domain = domain,
                ips = cached.resolvedIps,
                latencyMs = 1L,
                fromCache = true,
                blocked = false,
                upstreamLabel = "${cached.upstreamLabel} (Cache)"
            )
            return ResolutionOutcome(
                domain = domain,
                responseWire = patched,
                resolvedIps = cached.resolvedIps,
                latencyMs = 1L,
                fromCache = true,
                blocked = false,
                upstreamLabel = cached.upstreamLabel
            )
        }

        // 3. Forward over HTTPS (RFC 8484) with automatic failover
        val startNs = System.nanoTime()
        failoverEndpoints.forEachIndexed { index, (label, url) ->
            val resp = executeDohPost(url, dnsQueryWire)
            if (resp != null && resp.size >= 12) {
                val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val ips = DnsOverHttpsClient.parseARecordsFromDnsResponse(resp)
                val ttlSeconds = extractMinTtlSeconds(resp).coerceIn(30L, 600L)
                cache[cacheKey] = CacheEntry(
                    wireResponse = resp,
                    resolvedIps = ips,
                    expiresAtMs = System.currentTimeMillis() + ttlSeconds * 1000L,
                    upstreamLabel = label
                )
                if (index > 0) {
                    _metrics.update { it.copy(failoverCount = it.failoverCount + 1, activeUpstream = url) }
                    BpbLogBuffer.warn("DOH-TUNNEL", "Primary DoH failed; resolved $domain via failover [$label] in ${rttMs}ms")
                } else {
                    BpbLogBuffer.debug("DOH-TUNNEL", "DoH [$label] $domain -> ${ips.joinToString().ifEmpty { "OK" }} (${rttMs}ms)")
                }
                recordQueryMetrics(
                    domain = domain,
                    ips = ips,
                    latencyMs = rttMs,
                    fromCache = false,
                    blocked = false,
                    upstreamLabel = label
                )
                return ResolutionOutcome(
                    domain = domain,
                    responseWire = resp,
                    resolvedIps = ips,
                    latencyMs = rttMs,
                    fromCache = false,
                    blocked = false,
                    upstreamLabel = label
                )
            }
        }

        BpbLogBuffer.warn("DOH-TUNNEL", "Failed to resolve $domain across ${failoverEndpoints.size} DoH endpoints.")
        return null
    }

    private fun startLocalUdpForwarder(scope: CoroutineScope): Int? {
        return try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", localUdpPort))
            }
            udpSocket = socket
            udpJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(4096)
                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        break
                    }
                    val queryBytes = packet.data.copyOf(packet.length)
                    val clientAddr = packet.address
                    val clientPort = packet.port
                    launch {
                        val outcome = resolveWire(queryBytes) ?: return@launch
                        val reply = DatagramPacket(
                            outcome.responseWire,
                            outcome.responseWire.size,
                            clientAddr,
                            clientPort
                        )
                        runCatching { socket.send(reply) }
                    }
                }
            }
            socket.localPort
        } catch (e: Exception) {
            BpbLogBuffer.warn("DOH-TUNNEL", "Local UDP forwarder skipped: ${e.message}")
            null
        }
    }

    private fun executeDohPost(endpointUrl: String, dnsQueryWire: ByteArray): ByteArray? {
        return runCatching {
            val request = Request.Builder()
                .url(endpointUrl)
                .header("Accept", "application/dns-message")
                .header("Content-Type", "application/dns-message")
                .post(dnsQueryWire.toRequestBody(DNS_MEDIA_TYPE))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrNull()
    }

    private fun buildProtectedDohClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (socketFactory != null) {
            builder.socketFactory(socketFactory)
        }

        // Bootstrap Clean IP override prevents ISP DNS poisoning when resolving DoH hostnames!
        val cleanBootstrap = bootstrapIp.trim()
        val staticBootstrapMap = mapOf(
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
            "security.cloudflare-dns.com" to listOf("1.1.1.2", "1.0.0.2"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112"),
            "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15"),
        )
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val lower = hostname.lowercase()
                if (cleanBootstrap.isNotEmpty() &&
                    (lower.contains("cloudflare") || primaryDohUrl.contains(lower, ignoreCase = true))
                ) {
                    val custom = runCatching { InetAddress.getByName(cleanBootstrap) }.getOrNull()
                    if (custom != null) return listOf(custom)
                }
                staticBootstrapMap[lower]?.let { ips ->
                    val resolved = ips.mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
                    if (resolved.isNotEmpty()) return resolved
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        })

        return builder.build()
    }

    private fun buildFailoverList(primary: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val primaryLabel = BuiltInDnsPresets.all.find { it.endpoint == primary }?.name ?: "Primary DoH"
        list.add(primaryLabel to primary)
        BuiltInDnsPresets.all.forEach { preset ->
            if (preset.endpoint != primary) {
                list.add(preset.name to preset.endpoint)
            }
        }
        return list.take(4)
    }

    private fun recordQueryMetrics(
        domain: String,
        ips: List<String>,
        latencyMs: Long,
        fromCache: Boolean,
        blocked: Boolean,
        upstreamLabel: String,
    ) {
        val avg = if (!blocked && !fromCache) {
            val total = totalLatencyAccumMs.addAndGet(latencyMs)
            val count = latencySamples.incrementAndGet()
            total / count
        } else {
            _metrics.value.avgLatencyMs
        }

        val item = DnsQueryLogItem(
            id = queryIdSeq.getAndIncrement(),
            timestamp = timeFmt.format(Date()),
            domain = domain,
            resolvedIps = ips,
            latencyMs = latencyMs,
            fromCache = fromCache,
            blocked = blocked,
            upstreamLabel = upstreamLabel
        )

        _metrics.update { cur ->
            val updatedRecent = (listOf(item) + cur.recentQueries).take(MAX_RECENT_QUERIES)
            cur.copy(
                totalQueries = cur.totalQueries + 1,
                cacheHits = cur.cacheHits + if (fromCache) 1 else 0,
                dohForwarded = cur.dohForwarded + if (!fromCache && !blocked) 1 else 0,
                blockedQueries = cur.blockedQueries + if (blocked) 1 else 0,
                avgLatencyMs = avg,
                recentQueries = updatedRecent
            )
        }
    }

    private fun extractQuestionType(packet: ByteArray): Int {
        var idx = 12
        var guard = 0
        while (idx < packet.size && guard++ < 64) {
            val len = packet[idx].toInt() and 0xFF
            if (len == 0) {
                idx++
                break
            }
            if ((len and 0xC0) == 0xC0) {
                idx += 2
                break
            }
            idx += 1 + len
        }
        return if (idx + 1 < packet.size) {
            ((packet[idx].toInt() and 0xFF) shl 8) or (packet[idx + 1].toInt() and 0xFF)
        } else 1
    }

    private fun extractMinTtlSeconds(response: ByteArray): Long {
        if (response.size < 12) return 120L
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        if (anCount <= 0) return 60L

        var offset = 12
        repeat(qdCount) {
            offset = skipName(response, offset)
            if (offset < 0 || offset + 4 > response.size) return 120L
            offset += 4
        }
        var minTtl = 300L
        repeat(anCount) {
            offset = skipName(response, offset)
            if (offset < 0 || offset + 10 > response.size) return minTtl
            val ttl = ((response[offset + 4].toInt() and 0xFF).toLong() shl 24) or
                ((response[offset + 5].toInt() and 0xFF).toLong() shl 16) or
                ((response[offset + 6].toInt() and 0xFF).toLong() shl 8) or
                (response[offset + 7].toInt() and 0xFF).toLong()
            val rdLength = ((response[offset + 8].toInt() and 0xFF) shl 8) or (response[offset + 9].toInt() and 0xFF)
            if (ttl in 10L until minTtl) minTtl = ttl
            offset += 10 + rdLength
        }
        return minTtl
    }

    private fun skipName(packet: ByteArray, start: Int): Int {
        var idx = start
        var steps = 0
        while (idx < packet.size && steps++ < 128) {
            val len = packet[idx].toInt() and 0xFF
            if (len == 0) return idx + 1
            if ((len and 0xC0) == 0xC0) return idx + 2
            idx += 1 + len
        }
        return -1
    }
}
