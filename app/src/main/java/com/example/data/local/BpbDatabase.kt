package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VpnProfileEntity::class,
        XrayConfigEntity::class,
        SubscriptionEntity::class,
        CleanIpEntity::class,
    ],
    version = 3,
    exportSchema = false
)
abstract class BpbDatabase : RoomDatabase() {
    abstract fun vpnProfileDao(): VpnProfileDao
    abstract fun xrayConfigDao(): XrayConfigDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun cleanIpDao(): CleanIpDao

    companion object {
        @Volatile
        private var INSTANCE: BpbDatabase? = null

        fun getInstance(context: Context): BpbDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BpbDatabase::class.java,
                    "bpb_vpn_database.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
