package com.example.data.model

enum class ProtocolType(val label: String) {
    VLESS("VLESS"),
    TROJAN("Trojan"),
    WARP("WARP"),
    MASQUE("MASQUE"),
    SHADOWSOCKS("Shadowsocks"),
    SOCKS("SOCKS5"),
    HTTP("HTTP"),
    SERVERLESS("Serverless"),
}

enum class TunnelMode(val title: String, val subtitle: String) {
    SERVERLESS_DOH(
        title = "Serverless DoH",
        subtitle = "Encrypted DNS-over-HTTPS tunnel with DPI & ad-block routing"
    ),
    PROFILE_TUNNEL(
        title = "BPB Profile",
        subtitle = "Verified endpoint handshake + encrypted DoH & fragment rules"
    ),
}

enum class ConnectionStatus(val label: String) {
    IDLE("Disconnected"),
    VERIFYING("Verifying Tunnel"),
    CONNECTED("Protected"),
    DISCONNECTING("Disconnecting"),
    ERROR("Connection Error"),
}

enum class ThemeMode(val label: String) {
    DARK("Obsidian Dark"),
    LIGHT("Daylight"),
    SYSTEM("System Default"),
}

enum class SplitTunnelMode(val label: String, val description: String) {
    ALL_APPS("All Apps", "Route every installed app through the tunnel"),
    INCLUDE_ONLY("Only Selected", "Tunnel only the selected apps"),
    EXCLUDE_SELECTED("Bypass Selected", "Selected apps connect directly outside the tunnel"),
}

enum class UdpNoisePreset(
    val label: String,
    val packetSpec: String,
    val delayRangeMs: String,
    val description: String,
) {
    NONE("Disabled", "0", "0", "No synthetic UDP padding"),
    AMNEZIA_WG("AmneziaWG", "rand:40-120", "10-25", "Mimics obfuscated WireGuard handshake bursts"),
    QUIC("QUIC Initial", "quic:1200", "5-15", "Pads UDP datagrams to mimic HTTP/3 QUIC packets"),
    DNS("DNS Query", "dns:64-128", "15-30", "Simulates standard DNS resolver packet cadence"),
    STUN("STUN Binding", "stun:48-88", "8-20", "Mimics WebRTC / STUN NAT traversal frames"),
}

data class FragmentConfig(
    val enabled: Boolean = true,
    val packets: String = "tlshello",
    val length: String = "10-20",
    val interval: String = "10-20",
)

data class RoutingConfig(
    val bypassLan: Boolean = true,
    val bypassLocalRegion: Boolean = true,
    val blockAds: Boolean = true,
    val blockMalware: Boolean = true,
    val blockQuic: Boolean = false,
    val customDirectDomains: String = "ir,local,lan",
    val customBlockDomains: String = "",
)

data class DnsPreset(
    val id: String,
    val name: String,
    val protocol: String,
    val endpoint: String,
    val bootstrapIp: String,
    val description: String,
)

object BuiltInDnsPresets {
    val all: List<DnsPreset> = listOf(
        DnsPreset(
            id = "cf_doh",
            name = "Cloudflare DoH",
            protocol = "DoH (HTTPS)",
            endpoint = "https://1.1.1.1/dns-query",
            bootstrapIp = "1.1.1.1",
            description = "Fastest global Anycast encrypted DNS"
        ),
        DnsPreset(
            id = "cf_security",
            name = "Cloudflare Security",
            protocol = "DoH (HTTPS)",
            endpoint = "https://security.cloudflare-dns.com/dns-query",
            bootstrapIp = "1.1.1.2",
            description = "Blocks known malware & phishing domains"
        ),
        DnsPreset(
            id = "google_doh",
            name = "Google Public DoH",
            protocol = "DoH (HTTPS)",
            endpoint = "https://dns.google/dns-query",
            bootstrapIp = "8.8.8.8",
            description = "Reliable multi-region HTTPS resolver"
        ),
        DnsPreset(
            id = "quad9_doh",
            name = "Quad9 Secured",
            protocol = "DoH (HTTPS)",
            endpoint = "https://dns.quad9.net/dns-query",
            bootstrapIp = "9.9.9.9",
            description = "Threat-blocking privacy DNS with DNSSEC"
        ),
        DnsPreset(
            id = "adguard_doh",
            name = "AdGuard Filtering",
            protocol = "DoH (HTTPS)",
            endpoint = "https://dns.adguard-dns.com/dns-query",
            bootstrapIp = "94.140.14.14",
            description = "Blocks ads, trackers, and telemetry domains"
        ),
    )
}

data class DnsTestResult(
    val preset: DnsPreset,
    val domain: String,
    val resolvedIps: List<String>,
    val latencyMs: Long?,
    val error: String? = null,
)

enum class LogLevel(val badge: String) {
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERR"),
    DEBUG("DBG"),
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

data class TunnelStats(
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val mode: TunnelMode = TunnelMode.SERVERLESS_DOH,
    val activeProfileName: String = "Serverless DoH",
    val activeEndpoint: String = "1.1.1.1:443",
    val startedAtMs: Long = 0L,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val dnsQueriesHandled: Long = 0L,
    val blockedQueriesCount: Long = 0L,
    val verifiedLatencyMs: Long? = null,
    val errorMessage: String? = null,
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean = false,
)

data class ScannerConfig(
    val cidrRange: String = "104.16.0.0/13",
    val port: Int = 443,
    val sni: String = "speed.cloudflare.com",
    val sampleCount: Int = 40,
    val maxLatencyMs: Int = 500,
    val verifyTls: Boolean = true,
    val runSpeedProbe: Boolean = false,
    val concurrency: Int = 16,
)

data class ScanProgress(
    val isScanning: Boolean = false,
    val testedCount: Int = 0,
    val totalCount: Int = 0,
    val foundCount: Int = 0,
    val currentIp: String = "",
)
