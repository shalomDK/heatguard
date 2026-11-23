package com.example.appdev.domain.autopsy

import com.example.appdev.domain.incident.Incident
import com.example.appdev.domain.model.PowerSource

enum class FactorType { FAST_HEATING, CHARGING, HIGH_BRIGHTNESS, LOW_MEMORY, ALREADY_WARM }

enum class Strength { HIGH, MEDIUM }

/**
 * Something that was true when the incident began and is a plausible contributor to heat. This is
 * correlation, not proof: the phone cannot tell *why* it heated, only what it looked like at the time.
 */
data class AutopsyFactor(
    val type: FactorType,
    val strength: Strength,
    /** The measured value (rate, percent or temperature, depending on [type]). */
    val value: Float? = null,
    /** The user's own typical value, when there is enough history to know it. */
    val baseline: Float? = null,
    val power: PowerSource? = null,
)

/** Something that could not be assessed because it was not recorded for this incident. */
enum class MissingSignal { BRIGHTNESS, MEMORY, HEATING_RATE, PRE_TEMPERATURE }

enum class RecoveryVerdict { SLOWER, TYPICAL, FASTER }

data class RecoveryComparison(val verdict: RecoveryVerdict, val durationMillis: Long, val typicalMillis: Long)

data class AutopsyReport(
    val incidentId: Long,
    val ongoing: Boolean,
    val timeToPeakMillis: Long,
    val riseC: Float,
    val peakOverThresholdC: Float,
    /** Plausible contributors, strongest first. May be empty, which is an honest answer. */
    val factors: List<AutopsyFactor>,
    val missing: List<MissingSignal>,
    /** False for incidents recorded before context was captured. */
    val contextRecorded: Boolean,
    /** How many earlier incidents were available to compare against. */
    val baselineIncidents: Int,
    val recovery: RecoveryComparison?,
)

/**
 * Builds the Heat Autopsy for one incident by comparing it with the user's own history. It reports
 * only what was measured, says what is missing, and never attributes heat to a particular app.
 */
object HeatAutopsy {
    /** Earlier incidents needed before "your usual" is trusted. */
    const val MIN_BASELINE = 3

    private const val FAST_RATIO_MEDIUM = 1.5f
    private const val FAST_RATIO_HIGH = 2f
    private const val FAST_ABSOLUTE_MEDIUM = 0.6f
    private const val FAST_ABSOLUTE_HIGH = 1.0f
    private const val BRIGHTNESS_MEDIUM = 75
    private const val BRIGHTNESS_HIGH = 90
    private const val LOW_RAM_PERCENT = 10
    private const val WARM_BEFORE_MARGIN_C = 3f
    private const val VERY_WARM_BEFORE_MARGIN_C = 1.5f
    private const val SLOWER_RATIO = 1.5f
    private const val FASTER_RATIO = 0.67f

    fun analyze(incident: Incident, others: List<Incident>): AutopsyReport {
        val history = others.filter { it.id != incident.id }
        val ctx = incident.context

        val factors = buildList {
            fastHeating(incident, history)?.let(::add)
            if (incident.startedWhileCharging) {
                add(
                    AutopsyFactor(
                        FactorType.CHARGING,
                        if (incident.startPowerSource == PowerSource.WIRELESS) Strength.HIGH else Strength.MEDIUM,
                        power = incident.startPowerSource,
                    ),
                )
            }
            ctx?.brightnessPercent?.let { b ->
                when {
                    b >= BRIGHTNESS_HIGH -> add(AutopsyFactor(FactorType.HIGH_BRIGHTNESS, Strength.HIGH, value = b.toFloat()))
                    b >= BRIGHTNESS_MEDIUM -> add(AutopsyFactor(FactorType.HIGH_BRIGHTNESS, Strength.MEDIUM, value = b.toFloat()))
                }
            }
            val ram = ctx?.freeRamPercent
            when {
                ctx?.lowMemory == true -> add(AutopsyFactor(FactorType.LOW_MEMORY, Strength.HIGH, value = ram?.toFloat()))
                ram != null && ram < LOW_RAM_PERCENT -> add(AutopsyFactor(FactorType.LOW_MEMORY, Strength.MEDIUM, value = ram.toFloat()))
            }
            ctx?.tempFiveMinutesBeforeC?.let { before ->
                when {
                    before >= incident.thresholdC - VERY_WARM_BEFORE_MARGIN_C ->
                        add(AutopsyFactor(FactorType.ALREADY_WARM, Strength.HIGH, value = before))
                    before >= incident.thresholdC - WARM_BEFORE_MARGIN_C ->
                        add(AutopsyFactor(FactorType.ALREADY_WARM, Strength.MEDIUM, value = before))
                }
            }
        }.sortedBy { it.strength.ordinal } // stable: strongest first, otherwise in the order above

        return AutopsyReport(
            incidentId = incident.id,
            ongoing = incident.isActive,
            timeToPeakMillis = (incident.peakMillis - incident.startMillis).coerceAtLeast(0),
            riseC = (incident.peakTempC - incident.initialTempC).coerceAtLeast(0f),
            peakOverThresholdC = incident.peakTempC - incident.thresholdC,
            factors = factors,
            missing = missingSignals(incident),
            contextRecorded = ctx != null,
            baselineIncidents = history.size,
            recovery = recovery(incident, history),
        )
    }

    private fun fastHeating(incident: Incident, history: List<Incident>): AutopsyFactor? {
        val rate = incident.context?.heatingRateCPerMin?.takeIf { it > 0f } ?: return null
        val rates = history.mapNotNull { it.context?.heatingRateCPerMin?.takeIf { r -> r > 0f } }
        if (rates.size >= MIN_BASELINE) {
            val typical = median(rates)
            val ratio = rate / typical
            val strength = when {
                ratio >= FAST_RATIO_HIGH -> Strength.HIGH
                ratio >= FAST_RATIO_MEDIUM -> Strength.MEDIUM
                else -> return null
            }
            return AutopsyFactor(FactorType.FAST_HEATING, strength, value = rate, baseline = typical)
        }
        // Not enough history to know what is usual for this phone: only an absolutely fast rise counts.
        val strength = when {
            rate >= FAST_ABSOLUTE_HIGH -> Strength.HIGH
            rate >= FAST_ABSOLUTE_MEDIUM -> Strength.MEDIUM
            else -> return null
        }
        return AutopsyFactor(FactorType.FAST_HEATING, strength, value = rate)
    }

    private fun missingSignals(incident: Incident): List<MissingSignal> {
        val ctx = incident.context ?: return MissingSignal.entries
        return buildList {
            if (ctx.brightnessPercent == null) add(MissingSignal.BRIGHTNESS)
            if (ctx.freeRamPercent == null && ctx.lowMemory == null) add(MissingSignal.MEMORY)
            if (ctx.heatingRateCPerMin == null) add(MissingSignal.HEATING_RATE)
            if (ctx.tempFiveMinutesBeforeC == null) add(MissingSignal.PRE_TEMPERATURE)
        }
    }

    private fun recovery(incident: Incident, history: List<Incident>): RecoveryComparison? {
        if (incident.isActive) return null
        val durations = history.filter { !it.isActive }.map { it.durationMillis }
        if (durations.size < MIN_BASELINE) return null
        val typical = median(durations.map { it.toFloat() }).toLong()
        if (typical <= 0) return null
        val ratio = incident.durationMillis.toFloat() / typical
        val verdict = when {
            ratio >= SLOWER_RATIO -> RecoveryVerdict.SLOWER
            ratio <= FASTER_RATIO -> RecoveryVerdict.FASTER
            else -> RecoveryVerdict.TYPICAL
        }
        return RecoveryComparison(verdict, incident.durationMillis, typical)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
