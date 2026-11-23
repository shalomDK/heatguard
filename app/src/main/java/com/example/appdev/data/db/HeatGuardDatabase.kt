package com.example.appdev.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version history:
 * - 1: incidents and incident samples
 * - 2: adds cooling sessions and samples (auto-migration; existing incident data is preserved)
 * - 4: adds the speed_tests table for Heat Tax (auto-migration; adds a table only)
 * - 3: adds nullable context columns to incidents for the Heat Autopsy (auto-migration; nothing is lost)
 * - 5: adds the charge_sessions table for the Charging Lab (auto-migration; adds a table only)
 */
@Database(
    entities = [
        IncidentEntity::class,
        IncidentSampleEntity::class,
        CoolingSessionEntity::class,
        CoolingSampleEntity::class,
        SpeedTestEntity::class,
        ChargeSessionEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class HeatGuardDatabase : RoomDatabase() {
    abstract fun incidentDao(): IncidentDao
    abstract fun coolingDao(): CoolingDao
    abstract fun speedTestDao(): SpeedTestDao
    abstract fun chargeSessionDao(): ChargeSessionDao

    companion object {
        fun create(context: Context): HeatGuardDatabase =
            Room.databaseBuilder(context.applicationContext, HeatGuardDatabase::class.java, "heatguard.db").build()
    }
}
