package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface XrayConfigDao {
    @Query("SELECT * FROM xray_configs ORDER BY isPinned DESC, isActive DESC, latencyMs IS NULL ASC, latencyMs ASC, updatedAt DESC")
    fun observeAllConfigs(): Flow<List<XrayConfigEntity>>

    @Query("SELECT * FROM xray_configs WHERE isActive = 1 LIMIT 1")
    fun observeActiveConfig(): Flow<XrayConfigEntity?>

    @Query("SELECT * FROM xray_configs WHERE id = :id LIMIT 1")
    suspend fun getConfigById(id: Long): XrayConfigEntity?

    @Query("SELECT * FROM xray_configs WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveConfigOnce(): XrayConfigEntity?

    @Query("SELECT * FROM xray_configs")
    suspend fun getAllConfigsOnce(): List<XrayConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: XrayConfigEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfigs(configs: List<XrayConfigEntity>)

    @Update
    suspend fun updateConfig(config: XrayConfigEntity)

    @Delete
    suspend fun deleteConfig(config: XrayConfigEntity)

    @Query("DELETE FROM xray_configs WHERE subscriptionId = :subId")
    suspend fun deleteBySubscriptionId(subId: Long)

    @Query("UPDATE xray_configs SET isActive = 0")
    suspend fun clearActiveFlag()

    @Query("UPDATE xray_configs SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: Long)

    @Transaction
    suspend fun setActiveConfig(id: Long) {
        clearActiveFlag()
        markActive(id)
    }

    @Query("UPDATE xray_configs SET latencyMs = :latencyMs WHERE id = :id")
    suspend fun updateLatency(id: Long, latencyMs: Long?)

    @Query("UPDATE xray_configs SET serverAddress = :address, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateServerAddress(id: Long, address: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE xray_configs SET
            fragmentEnabled = :fragmentEnabled,
            fragmentPackets = :fragmentPackets,
            fragmentLength = :fragmentLength,
            fragmentInterval = :fragmentInterval,
            udpNoiseEnabled = :udpNoiseEnabled,
            udpNoiseType = :udpNoiseType,
            udpNoisePacket = :udpNoisePacket,
            udpNoiseDelay = :udpNoiseDelay,
            muxEnabled = :muxEnabled,
            muxConcurrency = :muxConcurrency,
            psiphonChainEnabled = :psiphonChainEnabled,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateObfuscationParams(
        id: Long,
        fragmentEnabled: Boolean,
        fragmentPackets: String,
        fragmentLength: String,
        fragmentInterval: String,
        udpNoiseEnabled: Boolean,
        udpNoiseType: String,
        udpNoisePacket: String,
        udpNoiseDelay: String,
        muxEnabled: Boolean,
        muxConcurrency: Int,
        psiphonChainEnabled: Boolean,
        updatedAt: Long = System.currentTimeMillis(),
    )
}
