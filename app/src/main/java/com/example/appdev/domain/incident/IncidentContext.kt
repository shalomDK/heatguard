package com.example.appdev.domain.incident

import com.example.appdev.domain.ai.PhoneMetrics
import com.example.appdev.domain.model.BatterySnapshot
import com.example.appdev.domain.thermal.TemperaturePoint
import com.example.appdev.domain.thermal.TrendEstimator

/**
 * What the phone was like at the moment an incident began. Every field is nullable: a value is only
 * stored if Android actually reported it (or the history was long enough to measure it), so the
 * autopsy never reasons from invented data. Incidents recorded before this existed have no context.
 */
data class IncidentContext(
    val brightnessPercent: Int? = null,
    val powerSaveOn: Boolean? = null,
    val freeRamPercent: Int? = null,
    val lowMemory: Boolean? = null,
    /** How fast the battery was warming just before the threshold was reached, in °C per minute. */
    val heatingRateCPerMin: Float? = null,
    /** The battery temperature about five minutes before the incident began. */
    val tempFiveMinutesBeforeC: Float? = null,
)

object IncidentContextBuilder {
    private const val MINUTE = 60_000L

    /** Readings this far (inclusive) before the start count as "about five minutes before". */
    private const val EARLIEST_AGE_MINUTES = 6L
    private const val LATEST_AGE_MINUTES = 4L

    fun build(snapshot: BatterySnapshot, history: List<TemperaturePoint>, metrics: PhoneMetrics?): IncidentContext {
        val before = history
            .filter { snapshot.timestampMillis - it.timestampMillis in (LATEST_AGE_MINUTES * MINUTE)..(EARLIEST_AGE_MINUTES * MINUTE) }
            .minByOrNull { kotlin.math.abs(snapshot.timestampMillis - it.timestampMillis - 5 * MINUTE) }

        return IncidentContext(
            brightnessPercent = metrics?.brightnessPercent,
            powerSaveOn = metrics?.powerSaveOn,
            freeRamPercent = metrics?.takeIf { it.totalRamBytes > 0 }?.let { (it.availRamBytes * 100 / it.totalRamBytes).toInt() },
            lowMemory = metrics?.lowMemory,
            heatingRateCPerMin = TrendEstimator.estimate(history)?.slopeCPerMin,
            tempFiveMinutesBeforeC = before?.temperatureC,
        )
    }
}
