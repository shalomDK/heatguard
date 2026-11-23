package com.example.appdev.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "incidents", indices = [Index("source", "startMillis")])
data class IncidentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val startMillis: Long,
    val endMillis: Long?,
    val lastSeenMillis: Long,
    val initialTempC: Float,
    val peakTempC: Float,
    val peakMillis: Long,
    val endTempC: Float?,
    val thresholdC: Float,
    val startBatteryPercent: Int?,
    val endBatteryPercent: Int?,
    val startChargeStatus: String,
    val startPowerSource: String,
    // Context when the incident began (database version 3). All nullable: absent = not recorded.
    val ctxBrightnessPercent: Int? = null,
    val ctxPowerSaveOn: Boolean? = null,
    val ctxFreeRamPercent: Int? = null,
    val ctxLowMemory: Boolean? = null,
    val ctxHeatingRateCPerMin: Float? = null,
    val ctxTempFiveMinBeforeC: Float? = null,
)

@Entity(
    tableName = "incident_samples",
    foreignKeys = [
        ForeignKey(
            entity = IncidentEntity::class,
            parentColumns = ["id"],
            childColumns = ["incidentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("incidentId", "timestampMillis")],
)
data class IncidentSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val incidentId: Long,
    val timestampMillis: Long,
    val temperatureC: Float,
    val batteryPercent: Int?,
    val chargeStatus: String,
    val powerSource: String,
)

@Entity(tableName = "cooling_sessions", indices = [Index("source", "startMillis")])
data class CoolingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val startMillis: Long,
    val endMillis: Long?,
    val lastSeenMillis: Long,
    val startTempC: Float,
    val lastTempC: Float,
    val minTempC: Float,
    val targetTempC: Float,
    val startBatteryPercent: Int?,
    val startChargeStatus: String,
    val startPowerSource: String,
    val outcome: String?,
)

@Entity(
    tableName = "cooling_samples",
    foreignKeys = [
        ForeignKey(
            entity = CoolingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId", "timestampMillis")],
)
data class CoolingSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampMillis: Long,
    val temperatureC: Float,
)
