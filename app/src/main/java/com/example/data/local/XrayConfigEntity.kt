package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.ProtocolType

/**
 * Room Entity storing complete Xray configurations including server addresses,
 * ports, transport security, and per-configuration DPI obfuscation parameters.
 */
@Entity(tableName = "xray_configs")
data class XrayConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val protocol: String = ProtocolType.VLESS.name,
    val serverAddress: String,
    val port: Int = 443,
    val userIdOrPassword: String = "",
    val encryption: String = "none",
    val flow: String = "",
    // Transport & TLS
    val networkTransport: String = "ws",
    val securityType: String = "tls",
    val sni: String = "",
    val hostHeader: String = "",
    val pathOrServiceName: String = "/",
    val tlsFingerprint: String = "chrome",
    val alpn: String = "h2,http/1.1",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    // Obfuscation & Chaining Parameters
    val fragmentEnabled: Boolean = true,
    val fragmentPackets: String = "tlshello",
    val fragmentLength: String = "10-20",
    val fragmentInterval: String = "10-20",
    val udpNoiseEnabled: Boolean = true,
    val udpNoiseType: String = "rand",
    val udpNoisePacket: String = "40-120",
    val udpNoiseDelay: String = "10-25",
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 8,
    val psiphonChainEnabled: Boolean = false,
    val psiphonRegion: String = "",
    // Metadata
    val subscriptionId: Long? = null,
    val rawUri: String = "",
    val customJsonOverride: String = "",
    val latencyMs: Long? = null,
    val isActive: Boolean = false,
    val isPinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun protocolEnum(): ProtocolType =
        runCatching { ProtocolType.valueOf(protocol) }.getOrDefault(ProtocolType.VLESS)

    fun toVpnProfileEntity(): VpnProfileEntity = VpnProfileEntity(
        id = id,
        name = name,
        protocol = protocol,
        address = serverAddress,
        port = port,
        uuidOrSecret = userIdOrPassword,
        sni = sni,
        hostHeader = hostHeader,
        path = pathOrServiceName,
        transport = networkTransport,
        security = securityType,
        fingerprint = tlsFingerprint,
        alpn = alpn,
        publicKey = realityPublicKey,
        shortId = realityShortId,
        flow = flow,
        subscriptionId = subscriptionId,
        rawUri = rawUri,
        latencyMs = latencyMs,
        isPinned = isPinned,
        createdAt = updatedAt
    )

    companion object {
        fun fromVpnProfile(profile: VpnProfileEntity): XrayConfigEntity = XrayConfigEntity(
            name = profile.name,
            protocol = profile.protocol,
            serverAddress = profile.address,
            port = profile.port,
            userIdOrPassword = profile.uuidOrSecret,
            flow = profile.flow,
            networkTransport = profile.transport,
            securityType = profile.security,
            sni = profile.sni,
            hostHeader = profile.hostHeader,
            pathOrServiceName = profile.path,
            tlsFingerprint = profile.fingerprint,
            alpn = profile.alpn,
            realityPublicKey = profile.publicKey,
            realityShortId = profile.shortId,
            subscriptionId = profile.subscriptionId,
            rawUri = profile.rawUri,
            latencyMs = profile.latencyMs,
            isPinned = profile.isPinned,
            updatedAt = profile.createdAt
        )
    }
}
