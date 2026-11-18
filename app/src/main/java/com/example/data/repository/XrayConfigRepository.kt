package com.example.data.repository

import com.example.core.BpbLogBuffer
import com.example.core.ConfigParser
import com.example.core.ProfileLatencyTester
import com.example.data.local.XrayConfigDao
import com.example.data.local.XrayConfigEntity
import com.example.data.model.BuiltInDnsPresets
import com.example.data.model.FragmentConfig
import com.example.data.model.RoutingConfig
import com.example.data.model.UdpNoisePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Dedicated repository abstracting Room CRUD, latency testing, obfuscation tuning,
 * and Xray JSON serialization for multiple stored Xray configurations.
 */
class XrayConfigRepository(private val dao: XrayConfigDao) {

    val allConfigs: Flow<List<XrayConfigEntity>> = dao.observeAllConfigs()
    val activeConfig: Flow<XrayConfigEntity?> = dao.observeActiveConfig()

    suspend fun getById(id: Long): XrayConfigEntity? = dao.getConfigById(id)

    suspend fun getActiveOrFirst(): XrayConfigEntity? =
        dao.getActiveConfigOnce() ?: dao.getAllConfigsOnce().firstOrNull()

    suspend fun saveConfig(config: XrayConfigEntity, activate: Boolean = false): Long {
        val id = dao.insertConfig(config.copy(updatedAt = System.currentTimeMillis()))
        if (activate || dao.getActiveConfigOnce() == null) {
            dao.setActiveConfig(id)
        }
        BpbLogBuffer.info("XRAY-DB", "Saved Xray config '${config.name}' (${config.serverAddress}:${config.port}).")
        return id
    }

    suspend fun importFromText(rawText: String, subscriptionId: Long? = null): List<XrayConfigEntity> {
        val parsed = ConfigParser.parseSubscriptionPayload(rawText, subscriptionId)
            .map { XrayConfigEntity.fromVpnProfile(it) }
        if (parsed.isNotEmpty()) {
            dao.insertConfigs(parsed)
            if (dao.getActiveConfigOnce() == null) {
                dao.getAllConfigsOnce().firstOrNull()?.let { dao.setActiveConfig(it.id) }
            }
        }
        return parsed
    }

    suspend fun setActive(id: Long) {
        dao.setActiveConfig(id)
    }

    suspend fun togglePin(config: XrayConfigEntity) {
        dao.updateConfig(config.copy(isPinned = !config.isPinned, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateObfuscation(config: XrayConfigEntity) {
        dao.updateObfuscationParams(
            id = config.id,
            fragmentEnabled = config.fragmentEnabled,
            fragmentPackets = config.fragmentPackets,
            fragmentLength = config.fragmentLength,
            fragmentInterval = config.fragmentInterval,
            udpNoiseEnabled = config.udpNoiseEnabled,
            udpNoiseType = config.udpNoiseType,
            udpNoisePacket = config.udpNoisePacket,
            udpNoiseDelay = config.udpNoiseDelay,
            muxEnabled = config.muxEnabled,
            muxConcurrency = config.muxConcurrency,
            psiphonChainEnabled = config.psiphonChainEnabled
        )
        BpbLogBuffer.info("XRAY-DB", "Updated obfuscation parameters for '${config.name}'.")
    }

    suspend fun updateServerAddress(id: Long, cleanIp: String) {
        dao.updateServerAddress(id, cleanIp)
    }

    suspend fun deleteConfig(config: XrayConfigEntity) {
        dao.deleteConfig(config)
        if (config.isActive) {
            dao.getAllConfigsOnce().firstOrNull()?.let { dao.setActiveConfig(it.id) }
        }
    }

    suspend fun deleteBySubscriptionId(subId: Long) = dao.deleteBySubscriptionId(subId)

    suspend fun pingAllConfigs() = coroutineScope {
        val configs = dao.getAllConfigsOnce()
        configs.map { cfg ->
            async(Dispatchers.IO) {
                val rtt = ProfileLatencyTester.measureProfileLatency(cfg.toVpnProfileEntity())
                dao.updateLatency(cfg.id, rtt)
            }
        }.awaitAll()
    }

    fun buildXrayJson(
        config: XrayConfigEntity,
        routingConfig: RoutingConfig,
        dnsPresetId: String,
        customDohUrl: String,
    ): String {
        val preset = BuiltInDnsPresets.all.find { it.id == dnsPresetId } ?: BuiltInDnsPresets.all.first()
        val fragment = FragmentConfig(
            enabled = config.fragmentEnabled,
            packets = config.fragmentPackets,
            length = config.fragmentLength,
            interval = config.fragmentInterval
        )
        val noisePreset = if (config.udpNoiseEnabled) UdpNoisePreset.AMNEZIA_WG else UdpNoisePreset.NONE
        return ConfigParser.buildXrayConfigJson(
            profile = config.toVpnProfileEntity(),
            dnsPreset = preset,
            customDohUrl = customDohUrl,
            fragmentConfig = fragment,
            udpNoisePreset = noisePreset,
            routingConfig = routingConfig
        )
    }
}
