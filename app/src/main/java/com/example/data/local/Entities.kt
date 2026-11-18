package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.ProtocolType

@Entity(tableName = "vpn_profiles")
data class VpnProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val protocol: String = ProtocolType.VLESS.name,
    val address: String,
    val port: Int = 443,
    val uuidOrSecret: String = "",
    val sni: String = "",
    val hostHeader: String = "",
    val path: String = "/",
    val transport: String = "ws",
    val security: String = "tls",
    val fingerprint: String = "chrome",
    val alpn: String = "h2,http/1.1",
    val publicKey: String = "",
    val shortId: String = "",
    val flow: String = "",
    val subscriptionId: Long? = null,
    val rawUri: String = "",
    val latencyMs: Long? = null,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun protocolEnum(): ProtocolType =
        runCatching { ProtocolType.valueOf(protocol) }.getOrDefault(ProtocolType.VLESS)
}

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val url: String,
    val profileCount: Int = 0,
    val lastUpdatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "clean_ips")
data class CleanIpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ip: String,
    val port: Int,
    val sni: String,
    val cidr: String,
    val latencyMs: Long,
    val tlsSuccess: Boolean,
    val downloadKbps: Int? = null,
    val scannedAt: Long = System.currentTimeMillis(),
)
