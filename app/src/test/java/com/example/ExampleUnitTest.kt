package com.example

import com.example.core.CloudflareScanner
import com.example.core.DnsOverHttpsClient
import com.example.data.model.RoutingConfig
import com.example.dns.DnsPacketBuilder
import com.example.dns.ServerlessDohTunnelEngine
import com.example.scanner.CloudflareCleanIpScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `cidr sampler returns valid non-network IPv4 addresses`() {
        val sampled = CloudflareScanner.sampleIpsFromCidr("104.16.0.0/16", 20)
        assertEquals(20, sampled.size)
        assertTrue(sampled.all { it.startsWith("104.16.") && !it.endsWith(".0") && !it.endsWith(".255") })
    }

    @Test
    fun `multi-cidr ALL sampler spans official cloudflare ranges`() {
        val candidates = CloudflareCleanIpScanner.sampleCandidates(
            CloudflareCleanIpScanner.ALL_CLOUDFLARE_TOKEN,
            24
        )
        assertEquals(24, candidates.size)
        val uniqueCidrs = candidates.map { it.second }.toSet()
        assertEquals(CloudflareCleanIpScanner.officialCloudflareCidrs.size, uniqueCidrs.size)
        assertTrue(candidates.all { (ip, _) -> !ip.endsWith(".0") && !ip.endsWith(".255") })
    }

    @Test
    fun `cloudflare trace parser extracts datacenter colo`() {
        val trace = """
            fl=21f402
            h=speed.cloudflare.com
            ip=198.51.100.12
            ts=1730000000.123
            colo=fra
            http=http/2
            tls=TLSv1.3
        """.trimIndent()
        assertEquals("FRA", CloudflareCleanIpScanner.parseColoFromTrace(trace))
        assertEquals("", CloudflareCleanIpScanner.parseColoFromTrace("no-colo-here"))
    }

    @Test
    fun `optimality score ranks low-latency TLS 1_3 endpoint with colo highest`() {
        val optimal = CloudflareCleanIpScanner.computeOptimalityScore(
            latencyMs = 45L,
            maxLatencyMs = 800,
            tlsVerified = true,
            tlsProtocol = "TLSv1.3",
            colo = "FRA",
            downloadKbps = 650
        )
        val slower = CloudflareCleanIpScanner.computeOptimalityScore(
            latencyMs = 520L,
            maxLatencyMs = 800,
            tlsVerified = true,
            tlsProtocol = "TLSv1.2",
            colo = "",
            downloadKbps = 120
        )
        assertTrue("Expected optimal ($optimal) > slower ($slower)", optimal > slower)
        assertTrue(optimal in 85..100)
    }

    @Test
    fun `dns wire query builds and extracts domain accurately`() {
        val wire = DnsOverHttpsClient.buildDnsQuery("speed.cloudflare.com")
        val extracted = DnsOverHttpsClient.extractDomainFromDnsWire(wire)
        assertEquals("speed.cloudflare.com", extracted)
    }

    @Test
    fun `routing blocklist blocks ads and custom domains`() {
        val routing = RoutingConfig(
            blockAds = true,
            blockMalware = true,
            customBlockDomains = "tracker.example.org"
        )
        assertTrue(DnsOverHttpsClient.shouldBlockDomain("doubleclick.net", routing))
        assertTrue(DnsOverHttpsClient.shouldBlockDomain("sub.tracker.example.org", routing))
        assertFalse(DnsOverHttpsClient.shouldBlockDomain("cloudflare.com", routing))
    }

    @Test
    fun `serverless doh engine returns immediate NXDOMAIN for blocked domain`() {
        val engine = ServerlessDohTunnelEngine(
            primaryDohUrl = "https://cloudflare-dns.com/dns-query",
            bootstrapIp = "104.16.248.249",
            routingConfig = RoutingConfig(blockAds = true, customBlockDomains = "ads.blocked.test"),
            enableLocalUdpListener = false
        )
        val query = DnsOverHttpsClient.buildDnsQuery("ads.blocked.test")
        val outcome = engine.resolveWire(query)
        assertNotNull(outcome)
        assertTrue(outcome!!.blocked)
        assertEquals("ads.blocked.test", outcome.domain)
        assertEquals(query[0], outcome.responseWire[0])
        assertEquals(query[1], outcome.responseWire[1])
        // RCODE = 3 (NXDOMAIN) in lower 4 bits of byte 3
        assertEquals(3, outcome.responseWire[3].toInt() and 0x0F)
    }

    @Test
    fun `dns packet builder swaps IPv4 addresses and UDP ports cleanly`() {
        val dnsPayload = DnsOverHttpsClient.buildDnsQuery("example.com")
        val req = ByteArray(28 + dnsPayload.size)
        req[0] = 0x45.toByte()
        req[9] = 17.toByte()
        // Src IP 10.77.0.2 -> Dst IP 1.1.1.1
        req[12] = 10; req[13] = 77; req[14] = 0; req[15] = 2
        req[16] = 1; req[17] = 1; req[18] = 1; req[19] = 1
        // Src port 53000 (0xCF08) -> Dst port 53 (0x0035)
        req[20] = 0xCF.toByte(); req[21] = 0x08.toByte()
        req[22] = 0x00; req[23] = 53.toByte()

        val responsePayload = DnsOverHttpsClient.buildNxdomainResponse(dnsPayload)
        val respPacket = DnsPacketBuilder.buildIpv4UdpResponse(req, ihl = 20, payload = responsePayload)

        assertEquals(28 + responsePayload.size, respPacket.size)
        // Src IP is now 1.1.1.1, Dst IP is now 10.77.0.2
        assertEquals(1, respPacket[12].toInt() and 0xFF)
        assertEquals(10, respPacket[16].toInt() and 0xFF)
        assertEquals(77, respPacket[17].toInt() and 0xFF)
        // Src port is now 53, Dst port is now 53000
        assertEquals(53, ((respPacket[20].toInt() and 0xFF) shl 8) or (respPacket[21].toInt() and 0xFF))
        assertEquals(53000, ((respPacket[22].toInt() and 0xFF) shl 8) or (respPacket[23].toInt() and 0xFF))
    }
}

