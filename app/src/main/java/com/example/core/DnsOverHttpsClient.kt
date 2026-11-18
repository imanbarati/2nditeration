package com.example.core

import com.example.data.model.DnsPreset
import com.example.data.model.DnsTestResult
import com.example.data.model.RoutingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.random.Random

object DnsOverHttpsClient {

    private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()

    // Compact built-in domain suffixes for real DNS ad/tracker/malware filtering
    private val adTrackerKeywords = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.", "ads.facebook.com", "an.facebook.com",
        "ads.twitter.com", "analytics.tiktok.com", "app-measurement.com",
        "pagead2.googlesyndication.com", "adnxs.com", "taboola.com",
        "outbrain.com", "criteo.com", "pubmatic.com", "rubiconproject.com",
        "moatads.com", "scorecardresearch.com", "hotjar.com", "mixpanel.com",
        "appsflyer.com", "adjust.com", "branch.io", "unityads.unity3d.com"
    )

    private val malwareKeywords = listOf(
        "malware-traffic-analysis", "eicar.org", "coinhive.com",
        "cryptoloot.pro", "minero.cc", "webminepool.com"
    )

    fun createClient(socketFactory: SocketFactory? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
        if (socketFactory != null) {
            builder.socketFactory(socketFactory)
        }
        return builder.build()
    }

    fun shouldBlockDomain(domain: String, routingConfig: RoutingConfig): Boolean {
        val normalized = domain.trim().lowercase().trimEnd('.')
        if (normalized.isEmpty()) return false

        val customBlocks = routingConfig.customBlockDomains
            .split(",", "\n")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (customBlocks.any { normalized == it || normalized.endsWith(".$it") }) {
            return true
        }

        if (routingConfig.blockAds && adTrackerKeywords.any { normalized.contains(it) }) {
            return true
        }
        if (routingConfig.blockMalware && malwareKeywords.any { normalized.contains(it) }) {
            return true
        }
        return false
    }

    suspend fun testResolver(
        preset: DnsPreset,
        domain: String,
        overrideEndpoint: String? = null,
    ): DnsTestResult = withContext(Dispatchers.IO) {
        val endpoint = overrideEndpoint?.takeIf { it.isNotBlank() } ?: preset.endpoint
        val queryBytes = buildDnsQuery(domain.trim().ifEmpty { "cloudflare.com" }, qType = 1)
        val client = createClient()

        val startNs = System.nanoTime()
        try {
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/dns-message")
                .header("Content-Type", "application/dns-message")
                .post(queryBytes.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                if (!response.isSuccessful) {
                    return@withContext DnsTestResult(
                        preset = preset,
                        domain = domain,
                        resolvedIps = emptyList(),
                        latencyMs = null,
                        error = "HTTP ${response.code}"
                    )
                }
                val body = response.body?.bytes() ?: ByteArray(0)
                val ips = parseARecordsFromDnsResponse(body)
                DnsTestResult(
                    preset = preset,
                    domain = domain,
                    resolvedIps = ips,
                    latencyMs = elapsedMs,
                    error = if (ips.isEmpty()) "No A record" else null
                )
            }
        } catch (e: Exception) {
            DnsTestResult(
                preset = preset,
                domain = domain,
                resolvedIps = emptyList(),
                latencyMs = null,
                error = e.message ?: "Request failed"
            )
        }
    }

    fun resolveWireSync(
        client: OkHttpClient,
        endpointUrl: String,
        dnsQueryWire: ByteArray,
    ): ByteArray? {
        return runCatching {
            val request = Request.Builder()
                .url(endpointUrl)
                .header("Accept", "application/dns-message")
                .header("Content-Type", "application/dns-message")
                .post(dnsQueryWire.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrNull()
    }

    fun buildDnsQuery(domain: String, qType: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        val txId = Random.nextInt(1, 65535)
        // Header: ID, Flags(RD=1), QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out.write((txId shr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01) // Recursion desired
        out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)

        for (label in domain.trim().trimEnd('.').split(".")) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty() && bytes.size < 64) {
                out.write(bytes.size)
                out.write(bytes)
            }
        }
        out.write(0x00) // Root label
        out.write((qType shr 8) and 0xFF)
        out.write(qType and 0xFF)
        out.write(0x00)
        out.write(0x01) // Class IN
        return out.toByteArray()
    }

    fun extractDomainFromDnsWire(dnsPayload: ByteArray): String? {
        if (dnsPayload.size < 13) return null
        val labels = mutableListOf<String>()
        var idx = 12
        var guard = 0
        while (idx < dnsPayload.size && guard++ < 64) {
            val len = dnsPayload[idx].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) break
            idx++
            if (idx + len > dnsPayload.size) return null
            labels.add(String(dnsPayload, idx, len, Charsets.UTF_8))
            idx += len
        }
        return if (labels.isEmpty()) null else labels.joinToString(".")
    }

    fun buildNxdomainResponse(query: ByteArray): ByteArray {
        if (query.size < 12) return query
        val resp = query.copyOf()
        // Set QR = 1 (Response), preserve Opcode/RD, set RA = 1, RCODE = 3 (NXDOMAIN)
        resp[2] = ((resp[2].toInt() and 0x79) or 0x80).toByte()
        resp[3] = 0x83.toByte()
        // Zero ANCOUNT, NSCOUNT, ARCOUNT
        for (i in 6 until 12) resp[i] = 0
        return resp
    }

    fun parseARecordsFromDnsResponse(response: ByteArray): List<String> {
        if (response.size < 12) return emptyList()
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        var offset = 12

        // Skip questions
        repeat(qdCount) {
            offset = skipDnsName(response, offset)
            if (offset < 0 || offset + 4 > response.size) return emptyList()
            offset += 4
        }

        val ips = mutableListOf<String>()
        repeat(anCount) {
            offset = skipDnsName(response, offset)
            if (offset < 0 || offset + 10 > response.size) return ips
            val type = ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)
            val rdLength = ((response[offset + 8].toInt() and 0xFF) shl 8) or (response[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLength > response.size) return ips
            if (type == 1 && rdLength == 4) {
                val addr = InetAddress.getByAddress(response.copyOfRange(offset, offset + 4)).hostAddress
                if (addr != null) ips.add(addr)
            } else if (type == 28 && rdLength == 16) {
                val addr = InetAddress.getByAddress(response.copyOfRange(offset, offset + 16)).hostAddress
                if (addr != null) ips.add(addr)
            }
            offset += rdLength
        }
        return ips
    }

    private fun skipDnsName(packet: ByteArray, start: Int): Int {
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
