package com.example

import com.example.core.CloudflareScanner
import com.example.core.DnsOverHttpsClient
import com.example.data.model.RoutingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
