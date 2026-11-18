package com.example.data.model

enum class ProtocolType(val label: String) {
    VLESS("VLESS"),
    TROJAN("Trojan"),
    VMESS("VMess"),
    WARP("WARP"),
    MASQUE("MASQUE"),
    SHADOWSOCKS("Shadowsocks"),
    SOCKS("SOCKS5"),
    HTTP("HTTP"),
    SERVERLESS("Serverless"),
}

enum class TunnelMode(val title: String, val subtitle: String) {
    AETHER_WARP(
        title = "Aether WARP",
        subtitle = "User-space WARP/MASQUE tunnel with Psiphon chain & local SOCKS5"
    ),
    PROFILE_TUNNEL(
        title = "Xray Config",
        subtitle = "Stored Xray profile handshake + obfuscation & DoH routing"
    ),
    SERVERLESS_DOH(
        title = "Serverless DoH",
        subtitle = "Encrypted DNS-over-HTTPS tunnel with DPI & ad-block routing"
    ),
}

enum class AetherTransportMode(
    val flag: String,
    val label: String,
    val defaultPort: Int,
    val description: String,
) {
    MASQUE(
        flag = "--masque",
        label = "MASQUE (H3/H2)",
        defaultPort = 443,
        description = "Tunnels WARP over QUIC/HTTP/3 or HTTP/2 on port 443"
    ),
    WIREGUARD(
        flag = "--wg",
        label = "WireGuard",
        defaultPort = 2408,
        description = "Native Cloudflare WARP UDP tunnel on port 2408 & fallbacks"
    ),
    GOOL(
        flag = "--gool",
        label = "Gool (WG-in-MASQUE)",
        defaultPort = 443,
        description = "Tunnels WireGuard inside a MASQUE outer layer to bypass UDP blocks"
    ),
    GOOL_CLASSIC(
        flag = "--gool-classic",
        label = "Classic Gool (WG²)",
        defaultPort = 2408,
        description = "Nested WARP-in-WARP double hop for clean exit IP"
    ),
    MIM(
        flag = "--mim",
        label = "MIM (MASQUE²)",
        defaultPort = 443,
        description = "Nested MASQUE-in-MASQUE multi-hop encrypted routing"
    ),
}

enum class PsiphonTransport(val id: String, val label: String, val description: String) {
    OSSH("OSSH", "OSSH", "Obfuscated SSH stream for resilient bypass"),
    FRONTED_MEEK("FRONTED-MEEK-OSSH", "Fronted Meek", "CDN domain-fronted HTTPS meek tunnel"),
    UNFRONTED_MEEK("UNFRONTED-MEEK-OSSH", "Meek HTTPS", "Direct HTTPS meek payload encapsulation"),
    QUIC_OSSH("QUIC-OSSH", "QUIC OSSH", "Low-latency obfuscated QUIC transport"),
    INPROXY_WEBRTC("INPROXY-WEBRTC", "Inproxy WebRTC", "Peer-assisted WebRTC bridge transport"),
}

enum class PsiphonChainOrder(val label: String, val routeSummary: String) {
    WARP_OVER_PSIPHON("WARP → Psiphon", "Device → Aether WARP → Psiphon Exit"),
    PSIPHON_OVER_WARP("Psiphon → WARP", "Device → Psiphon Upstream → Aether WARP Exit"),
}

enum class PsiphonRegion(val code: String, val label: String) {
    AUTO("", "Fastest Auto"),
    DE("DE", "Germany (DE)"),
    NL("NL", "Netherlands (NL)"),
    US("US", "United States (US)"),
    GB("GB", "United Kingdom (GB)"),
    CH("CH", "Switzerland (CH)"),
    SE("SE", "Sweden (SE)"),
    SG("SG", "Singapore (SG)"),
    JP("JP", "Japan (JP)"),
    CA("CA", "Canada (CA)"),
}

data class AetherWarpConfig(
    val transportMode: AetherTransportMode = AetherTransportMode.GOOL,
    val endpointHost: String = "162.159.192.1",
    val endpointPort: Int = 443,
    val masqueSni: String = "consumer-masque.cloudflareclient.com",
    val noiseEnabled: Boolean = true,
    val tlsFragmentEnabled: Boolean = true,
    val localSocksEnabled: Boolean = true,
    val localSocksPort: Int = 1819,
    val autoDiscoverEndpoint: Boolean = true,
)

data class PsiphonChainConfig(
    val enabled: Boolean = true,
    val chainOrder: PsiphonChainOrder = PsiphonChainOrder.WARP_OVER_PSIPHON,
    val transport: PsiphonTransport = PsiphonTransport.FRONTED_MEEK,
    val region: PsiphonRegion = PsiphonRegion.AUTO,
    val upstreamHost: String = "127.0.0.1",
    val upstreamPort: Int = 1080,
    val useBuiltInRelayWhenNoExternal: Boolean = true,
    val torAfterChain: Boolean = false,
)

data class WarpEndpointCandidate(
    val ip: String,
    val port: Int,
    val modeLabel: String,
    val latencyMs: Long,
    val dataPlaneVerified: Boolean,
)

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
    val mode: TunnelMode = TunnelMode.AETHER_WARP,
    val activeProfileName: String = "Aether WARP + Psiphon",
    val activeEndpoint: String = "162.159.192.1:443",
    val activeChainLabel: String = "",
    val localSocksAddress: String? = null,
    val startedAtMs: Long = 0L,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val dnsQueriesHandled: Long = 0L,
    val blockedQueriesCount: Long = 0L,
    val proxiedConnections: Long = 0L,
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
