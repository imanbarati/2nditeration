package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VpnProfileDao {
    @Query("SELECT * FROM vpn_profiles ORDER BY isPinned DESC, latencyMs IS NULL ASC, latencyMs ASC, createdAt DESC")
    fun observeAllProfiles(): Flow<List<VpnProfileEntity>>

    @Query("SELECT * FROM vpn_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: Long): VpnProfileEntity?

    @Query("SELECT * FROM vpn_profiles")
    suspend fun getAllProfilesOnce(): List<VpnProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: VpnProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(profiles: List<VpnProfileEntity>)

    @Update
    suspend fun updateProfile(profile: VpnProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: VpnProfileEntity)

    @Query("DELETE FROM vpn_profiles WHERE subscriptionId = :subId")
    suspend fun deleteBySubscriptionId(subId: Long)

    @Query("UPDATE vpn_profiles SET latencyMs = :latencyMs WHERE id = :id")
    suspend fun updateLatency(id: Long, latencyMs: Long?)

    @Query("UPDATE vpn_profiles SET address = :cleanIp WHERE id = :id")
    suspend fun updateAddress(id: Long, cleanIp: String)
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY lastUpdatedAt DESC")
    fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(sub: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(sub: SubscriptionEntity)

    @Delete
    suspend fun deleteSubscription(sub: SubscriptionEntity)
}

@Dao
interface CleanIpDao {
    @Query("SELECT * FROM clean_ips ORDER BY score DESC, latencyMs ASC")
    fun observeCleanIps(): Flow<List<CleanIpEntity>>

    @Query("SELECT * FROM clean_ips ORDER BY score DESC, latencyMs ASC LIMIT 1")
    suspend fun getOptimalCleanIp(): CleanIpEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ips: List<CleanIpEntity>)

    @Query("DELETE FROM clean_ips")
    suspend fun clearAll()
}
