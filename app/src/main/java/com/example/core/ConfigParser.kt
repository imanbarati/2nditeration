package com.example.core

import android.util.Base64
import com.example.data.local.VpnProfileEntity
import com.example.data.model.DnsPreset
import com.example.data.model.FragmentConfig
import com.example.data.model.ProtocolType
import com.example.data.model.RoutingConfig
import com.example.data.model.UdpNoisePreset
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ConfigParser {

    fun parseSubscriptionPayload(rawPayload: String, subscriptionId: Long? = null): List<VpnProfileEntity> {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Try Base64 decoding first if it doesn't start with a known URI scheme or JSON
        val decoded = if (
            !trimmed.startsWith("vless://") &&
            !trimmed.startsWith("trojan://") &&
            !trimmed.startsWith("ss://") &&
            !trimmed.startsWith("socks://") &&
            !trimmed.startsWith("http://") &&
            !trimmed.startsWith("wg://") &&
            !trimmed.startsWith("warp://") &&
            !trimmed.startsWith("{") &&
            !trimmed.startsWith("[")
        ) {
            decodeBase64Flexible(trimmed) ?: trimmed
        } else {
            trimmed
        }

        // Check if JSON Xray config
        if (decoded.trimStart().startsWith("{")) {
            parseXrayJsonProfile(decoded, subscriptionId)?.let { return listOf(it) }
        }

        val lines = decoded
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

        val profiles = mutableListOf<VpnProfileEntity>()
        for (line in lines) {
            parseSingleUri(line, subscriptionId)?.let { profiles.add(it) }
        }
        return profiles
    }

    fun parseSingleUri(uriString: String, subscriptionId: Long? = null): VpnProfileEntity? {
        val clean = uriString.trim()
        return when {
            clean.startsWith("vless://", ignoreCase = true) -> parseVlessOrTrojan(clean, ProtocolType.VLESS, subscriptionId)
            clean.startsWith("trojan://", ignoreCase = true) -> parseVlessOrTrojan(clean, ProtocolType.TROJAN, subscriptionId)
            clean.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(clean, subscriptionId)
            clean.startsWith("socks://", ignoreCase = true) || clean.startsWith("socks5://", ignoreCase = true) ->
                parseSimpleProxy(clean, ProtocolType.SOCKS, subscriptionId)
            clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) ->
                parseSimpleProxy(clean, ProtocolType.HTTP, subscriptionId)
            clean.startsWith("wg://", ignoreCase = true) || clean.startsWith("warp://", ignoreCase = true) ->
                parseWarpOrMasque(clean, subscriptionId)
            clean.startsWith("masque://", ignoreCase = true) ->
                parseWarpOrMasque(clean, subscriptionId)
            else -> null
        }
    }

    private fun parseVlessOrTrojan(
        raw: String,
        protocol: ProtocolType,
        subscriptionId: Long?,
    ): VpnProfileEntity? {
        return runCatching {
            val uri = URI(raw)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val secret = uri.userInfo ?: ""
            val query = parseQueryParams(uri.rawQuery)
            val fragmentName = uri.rawFragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() }
                ?: "${protocol.label} • $host:$port"

            VpnProfileEntity(
                name = fragmentName,
                protocol = protocol.name,
                address = host,
                port = port,
                uuidOrSecret = secret,
                sni = query["sni"] ?: query["peer"] ?: host,
                hostHeader = query["host"] ?: query["sni"] ?: host,
                path = query["path"] ?: "/",
                transport = query["type"] ?: "ws",
                security = query["security"] ?: "tls",
                fingerprint = query["fp"] ?: "chrome",
                alpn = query["alpn"] ?: "h2,http/1.1",
                publicKey = query["pbk"] ?: "",
                shortId = query["sid"] ?: "",
                flow = query["flow"] ?: "",
                subscriptionId = subscriptionId,
                rawUri = raw
            )
        }.getOrNull()
    }

    private fun parseShadowsocks(raw: String, subscriptionId: Long?): VpnProfileEntity? {
        return runCatching {
            val afterScheme = raw.substringAfter("://")
            val fragmentPart = afterScheme.substringAfter("#", "")
            val mainPart = afterScheme.substringBefore("#").substringBefore("?")
            val name = fragmentPart.takeIf { it.isNotBlank() }?.let { urlDecode(it) }

            val (userInfo, hostPort) = if ("@" in mainPart) {
                val left = mainPart.substringBeforeLast("@")
                val right = mainPart.substringAfterLast("@")
                val decodedUser = decodeBase64Flexible(left) ?: left
                decodedUser to right
            } else {
                val decoded = decodeBase64Flexible(mainPart) ?: return null
                decoded.substringBeforeLast("@") to decoded.substringAfterLast("@")
            }

            val host = hostPort.substringBeforeLast(":").trim('[', ']')
            val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443
            if (host.isBlank()) return null

            VpnProfileEntity(
                name = name ?: "SS • $host:$port",
                protocol = ProtocolType.SHADOWSOCKS.name,
                address = host,
                port = port,
                uuidOrSecret = userInfo,
                transport = "tcp",
                security = "none",
                subscriptionId = subscriptionId,
                rawUri = raw
            )
        }.getOrNull()
    }

    private fun parseSimpleProxy(
        raw: String,
        protocol: ProtocolType,
        subscriptionId: Long?,
    ): VpnProfileEntity? {
        return runCatching {
            val uri = URI(raw)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else if (protocol == ProtocolType.SOCKS) 1080 else 8080
            val name = uri.rawFragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() }
                ?: "${protocol.label} • $host:$port"

            VpnProfileEntity(
                name = name,
                protocol = protocol.name,
                address = host,
                port = port,
                uuidOrSecret = uri.userInfo ?: "",
                transport = "tcp",
                security = if (raw.startsWith("https://", true)) "tls" else "none",
                subscriptionId = subscriptionId,
                rawUri = raw
            )
        }.getOrNull()
    }

    private fun parseWarpOrMasque(raw: String, subscriptionId: Long?): VpnProfileEntity? {
        return runCatching {
            val isMasque = raw.startsWith("masque://", true)
            val normalized = raw
                .replaceFirst("wg://", "https://", ignoreCase = true)
                .replaceFirst("warp://", "https://", ignoreCase = true)
                .replaceFirst("masque://", "https://", ignoreCase = true)
            val uri = URI(normalized)
            val host = uri.host ?: "engage.cloudflareclient.com"
            val port = if (uri.port > 0) uri.port else if (isMasque) 443 else 2408
            val query = parseQueryParams(uri.rawQuery)
            val proto = if (isMasque) ProtocolType.MASQUE else ProtocolType.WARP
            val name = uri.rawFragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() }
                ?: "${proto.label} • $host:$port"

            VpnProfileEntity(
                name = name,
                protocol = proto.name,
                address = host,
                port = port,
                uuidOrSecret = uri.userInfo ?: query["privateKey"] ?: "",
                publicKey = query["publicKey"] ?: query["pbk"] ?: "",
                sni = query["sni"] ?: "consumer-masque.cloudflareclient.com",
                transport = if (isMasque) "masque-h3" else "wireguard",
                security = "tls",
                subscriptionId = subscriptionId,
                rawUri = raw
            )
        }.getOrNull()
    }

    private fun parseXrayJsonProfile(jsonText: String, subscriptionId: Long?): VpnProfileEntity? {
        return runCatching {
            val root = JSONObject(jsonText)
            val outbounds = root.optJSONArray("outbounds") ?: return null
            if (outbounds.length() == 0) return null
            val first = outbounds.getJSONObject(0)
            val protoStr = first.optString("protocol", "vless").uppercase()
            val protocol = runCatching { ProtocolType.valueOf(protoStr) }.getOrDefault(ProtocolType.VLESS)
            val settings = first.optJSONObject("settings")
            val vnext = settings?.optJSONArray("vnext")?.optJSONObject(0)
            val servers = settings?.optJSONArray("servers")?.optJSONObject(0)
            val targetObj = vnext ?: servers ?: return null

            val address = targetObj.optString("address")
            val port = targetObj.optInt("port", 443)
            val userSecret = vnext?.optJSONArray("users")?.optJSONObject(0)?.optString("id")
                ?: targetObj.optString("password", "")
            val stream = first.optJSONObject("streamSettings")
            val network = stream?.optString("network", "ws") ?: "ws"
            val security = stream?.optString("security", "tls") ?: "tls"
            val tls = stream?.optJSONObject("tlsSettings")
            val ws = stream?.optJSONObject("wsSettings")
            val sni = tls?.optString("serverName")?.takeIf { it.isNotBlank() } ?: address
            val fp = tls?.optString("fingerprint")?.takeIf { it.isNotBlank() } ?: "chrome"
            val path = ws?.optString("path")?.takeIf { it.isNotBlank() } ?: "/"
            val hostHdr = ws?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() } ?: sni

            if (address.isBlank()) return null
            VpnProfileEntity(
                name = root.optString("remarks").takeIf { it.isNotBlank() } ?: "Xray JSON • $address",
                protocol = protocol.name,
                address = address,
                port = port,
                uuidOrSecret = userSecret,
                sni = sni,
                hostHeader = hostHdr,
                path = path,
                transport = network,
                security = security,
                fingerprint = fp,
                subscriptionId = subscriptionId,
                rawUri = jsonText
            )
        }.getOrNull()
    }

    fun toShareUri(profile: VpnProfileEntity): String {
        if (profile.rawUri.isNotBlank() && !profile.rawUri.trimStart().startsWith("{")) {
            return profile.rawUri
        }
        val encodedName = URLEncoder.encode(profile.name, "UTF-8").replace("+", "%20")
        return when (profile.protocolEnum()) {
            ProtocolType.VLESS -> buildString {
                append("vless://${profile.uuidOrSecret}@${profile.address}:${profile.port}")
                append("?type=${profile.transport}")
                append("&security=${profile.security}")
                if (profile.sni.isNotBlank()) append("&sni=${urlEncode(profile.sni)}")
                if (profile.hostHeader.isNotBlank()) append("&host=${urlEncode(profile.hostHeader)}")
                if (profile.path.isNotBlank()) append("&path=${urlEncode(profile.path)}")
                if (profile.fingerprint.isNotBlank()) append("&fp=${profile.fingerprint}")
                if (profile.alpn.isNotBlank()) append("&alpn=${urlEncode(profile.alpn)}")
                append("#$encodedName")
            }
            ProtocolType.TROJAN -> buildString {
                append("trojan://${profile.uuidOrSecret}@${profile.address}:${profile.port}")
                append("?type=${profile.transport}")
                append("&security=${profile.security}")
                if (profile.sni.isNotBlank()) append("&sni=${urlEncode(profile.sni)}")
                if (profile.hostHeader.isNotBlank()) append("&host=${urlEncode(profile.hostHeader)}")
                if (profile.path.isNotBlank()) append("&path=${urlEncode(profile.path)}")
                append("#$encodedName")
            }
            ProtocolType.WARP -> "wg://${profile.uuidOrSecret}@${profile.address}:${profile.port}?publicKey=${urlEncode(profile.publicKey)}#$encodedName"
            ProtocolType.MASQUE -> "masque://${profile.address}:${profile.port}?sni=${urlEncode(profile.sni)}#$encodedName"
            else -> "${profile.protocol.lowercase()}://${profile.address}:${profile.port}#$encodedName"
        }
    }

    fun buildXrayConfigJson(
        profile: VpnProfileEntity?,
        dnsPreset: DnsPreset,
        customDohUrl: String,
        fragmentConfig: FragmentConfig,
        udpNoisePreset: UdpNoisePreset,
        routingConfig: RoutingConfig,
    ): String {
        val dohUrl = customDohUrl.takeIf { it.isNotBlank() } ?: dnsPreset.endpoint
        val root = JSONObject()

        root.put("remarks", profile?.name ?: "BPB Serverless DoH")
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // DNS section
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(dohUrl)
                put(JSONObject().apply {
                    put("address", dnsPreset.bootstrapIp)
                    put("domains", JSONArray().apply {
                        put("geosite:private")
                        if (routingConfig.bypassLocalRegion) put("geosite:ir")
                    })
                })
            })
            put("queryStrategy", "UseIP")
        })

        // Inbounds
        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks-in")
                put("port", 10808)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http")
                        put("tls")
                        put("quic")
                    })
                })
            })
        })

        // Outbounds
        val outbounds = JSONArray()
        if (profile != null) {
            val proxyOutbound = JSONObject().apply {
                put("tag", "proxy")
                put("protocol", profile.protocol.lowercase())
                when (profile.protocolEnum()) {
                    ProtocolType.VLESS -> {
                        put("settings", JSONObject().apply {
                            put("vnext", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("address", profile.address)
                                    put("port", profile.port)
                                    put("users", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("id", profile.uuidOrSecret.ifEmpty { "00000000-0000-0000-0000-000000000000" })
                                            put("encryption", "none")
                                            if (profile.flow.isNotBlank()) put("flow", profile.flow)
                                        })
                                    })
                                })
                            })
                        })
                    }
                    ProtocolType.TROJAN -> {
                        put("settings", JSONObject().apply {
                            put("servers", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("address", profile.address)
                                    put("port", profile.port)
                                    put("password", profile.uuidOrSecret)
                                })
                            })
                        })
                    }
                    else -> {
                        put("settings", JSONObject().apply {
                            put("servers", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("address", profile.address)
                                    put("port", profile.port)
                                })
                            })
                        })
                    }
                }
                put("streamSettings", JSONObject().apply {
                    put("network", profile.transport)
                    put("security", profile.security)
                    if (profile.security == "tls") {
                        put("tlsSettings", JSONObject().apply {
                            put("serverName", profile.sni.ifEmpty { profile.address })
                            put("fingerprint", profile.fingerprint.ifEmpty { "chrome" })
                            put("allowInsecure", false)
                            put("alpn", JSONArray(profile.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                        })
                    }
                    if (profile.transport == "ws") {
                        put("wsSettings", JSONObject().apply {
                            put("path", profile.path.ifEmpty { "/" })
                            put("headers", JSONObject().apply {
                                put("Host", profile.hostHeader.ifEmpty { profile.sni.ifEmpty { profile.address } })
                            })
                        })
                    } else if (profile.transport == "xhttp") {
                        put("xhttpSettings", JSONObject().apply {
                            put("path", profile.path.ifEmpty { "/" })
                            put("host", profile.hostHeader.ifEmpty { profile.sni.ifEmpty { profile.address } })
                        })
                    }
                    if (fragmentConfig.enabled) {
                        put("sockopt", JSONObject().apply {
                            put("dialerProxy", "fragment")
                            put("tcpKeepAliveIdle", 100)
                        })
                    }
                })
            }
            outbounds.put(proxyOutbound)
        }

        if (fragmentConfig.enabled) {
            outbounds.put(JSONObject().apply {
                put("tag", "fragment")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("domainStrategy", "AsIs")
                    put("fragment", JSONObject().apply {
                        put("packets", fragmentConfig.packets)
                        put("length", fragmentConfig.length)
                        put("interval", fragmentConfig.interval)
                    })
                    if (udpNoisePreset != UdpNoisePreset.NONE) {
                        put("noises", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", udpNoisePreset.name.lowercase())
                                put("packet", udpNoisePreset.packetSpec)
                                put("delay", udpNoisePreset.delayRangeMs)
                            })
                        })
                    }
                })
                put("streamSettings", JSONObject().apply {
                    put("sockopt", JSONObject().apply {
                        put("tcpNoDelay", true)
                    })
                })
            })
        }

        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        })
        root.put("outbounds", outbounds)

        // Routing rules
        val rules = JSONArray()
        if (routingConfig.blockQuic) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "udp")
                put("port", "443")
                put("outboundTag", "block")
            })
        }
        if (routingConfig.blockAds) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray().apply { put("geosite:category-ads-all") })
                put("outboundTag", "block")
            })
        }
        if (routingConfig.blockMalware) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray().apply { put("geosite:malware"); put("geosite:phishing") })
                put("outboundTag", "block")
            })
        }
        if (routingConfig.bypassLan) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().apply { put("geoip:private") })
                put("outboundTag", "direct")
            })
        }
        val directDomains = routingConfig.customDirectDomains.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (directDomains.isNotEmpty()) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray(directDomains))
                put("outboundTag", "direct")
            })
        }
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", rules)
        })

        return root.toString(2)
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else urlDecode(pair.substring(0, idx)) to urlDecode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun urlEncode(value: String): String =
        runCatching { URLEncoder.encode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun decodeBase64Flexible(input: String): String? {
        val cleaned = input.replace("\n", "").replace("\r", "").trim()
        if (cleaned.length < 8) return null
        val flags = listOf(
            Base64.DEFAULT,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.NO_WRAP
        )
        for (flag in flags) {
            val decoded = runCatching {
                val padded = when (cleaned.length % 4) {
                    2 -> "$cleaned=="
                    3 -> "$cleaned="
                    else -> cleaned
                }
                String(Base64.decode(padded, flag), StandardCharsets.UTF_8)
            }.getOrNull()
            if (decoded != null && decoded.any { it.isLetterOrDigit() }) {
                return decoded
            }
        }
        return null
    }
}
