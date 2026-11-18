package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.core.ConfigParser
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.FragmentConfig
import com.example.data.model.ProtocolType
import com.example.data.model.RoutingConfig
import com.example.data.model.UdpNoisePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches BPB VPN`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("BPB VPN", appName)
    }

    @Test
    fun `parse base64 subscription with vless and trojan URIs`() {
        val vless = "vless://11111111-2222-3333-4444-555555555555@104.16.1.1:443?type=ws&security=tls&sni=my.workers.dev&path=%2Fws#BPB-VLESS"
        val trojan = "trojan://secretpass@172.64.1.2:2053?type=ws&security=tls&sni=my.workers.dev#BPB-Trojan"
        val payload = Base64.encodeToString("$vless\n$trojan".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

        val profiles = ConfigParser.parseSubscriptionPayload(payload)
        assertEquals(2, profiles.size)
        assertEquals(ProtocolType.VLESS, profiles[0].protocolEnum())
        assertEquals("104.16.1.1", profiles[0].address)
        assertEquals("my.workers.dev", profiles[0].sni)
        assertEquals(ProtocolType.TROJAN, profiles[1].protocolEnum())
        assertEquals(2053, profiles[1].port)
    }

    @Test
    fun `xray json generator includes fragment and routing outbounds`() {
        val profile = ConfigParser.parseSingleUri(
            "vless://11111111-2222-3333-4444-555555555555@104.16.1.1:443?type=ws&security=tls&sni=panel.workers.dev#Worker"
        )
        assertNotNull(profile)

        val json = ConfigParser.buildXrayConfigJson(
            profile = profile,
            dnsPreset = BuiltInDnsPresets.all.first(),
            customDohUrl = "",
            fragmentConfig = FragmentConfig(enabled = true, packets = "tlshello", length = "10-20", interval = "10-20"),
            udpNoisePreset = UdpNoisePreset.AMNEZIA_WG,
            routingConfig = RoutingConfig(blockAds = true, blockQuic = true)
        )
        assertTrue(json.contains("\"dialerProxy\": \"fragment\""))
        assertTrue(json.contains("\"packets\": \"tlshello\""))
        assertTrue(json.contains("\"amnezia_wg\""))
        assertTrue(json.contains("category-ads-all"))
    }
}
