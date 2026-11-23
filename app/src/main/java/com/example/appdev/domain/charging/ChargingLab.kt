package com.example.appdev.domain.charging

import com.example.appdev.domain.model.BatterySnapshot
import com.example.appdev.domain.model.ChargeStatus
import com.example.appdev.domain.model.PowerSource
import com.example.appdev.domain.model.ReadingSource
import kotlinx.coroutines.flow.Flow

/**
 * One stretch of charging from a single kind of power source, with the temperatures seen during it.
 * Android reports only the kind of source (wall adapter, USB port, wireless pad), not the charger's
 * wattage or cable quality, so that is the finest comparison this app can make.
 */
data class ChargeSession(
    val id: Long = 0,
    val source: ReadingSource,
    val startMillis: Long,
    /** Null while the session is still going. */
    val endMillis: Long?,
    val lastSeenMillis: Long,
    val powerSource: PowerSource,
    val startTempC: Float,
    val peakTempC: Float,
    val tempSumC: Double,
    val readingCount: Int,
    val startPercent: Int?,
    val lastPercent: Int?,
) {
    val durationMillis: Long get() = (endMillis ?: lastSeenMillis) - startMillis

    /** How far the battery temperature climbed above where it started. Never negative. */
    val riseC: Float get() = (peakTempC - startTempC).coerceAtLeast(0f)

    val percentGained: Int?
        get() = if (startPercent != null && lastPercent != null) lastPercent - startPercent else null

    /** Charging speed in battery percentage points per hour, when the level was reported. */
    val percentPerHour: Double?
        get() {
            val gained = percentGained ?: return null
            if (gained <= 0 || durationMillis <= 0) return null
            return gained * 3_600_000.0 / durationMillis
        }
}

interface ChargeSessionRepository {
    suspend fun start(session: ChargeSession): Long
    suspend fun update(session: ChargeSession)
    suspend fun getActive(source: ReadingSource): ChargeSession?
    fun observe(source: ReadingSource): Flow<List<ChargeSession>>
    suspend fun deleteAll(source: ReadingSource)
}

/**
 * Turns a stream of readings into charge sessions: one per stretch of continuous charging from the same
 * kind of source. Only readings from [source] are processed. Not thread-safe; feed it from one coroutine.
 */
class ChargeSessionTracker(
    private val repository: ChargeSessionRepository,
    private val source: ReadingSource = ReadingSource.DEVICE,
    private val maxGapMillis: Long = DEFAULT_MAX_GAP_MILLIS,
    private val writeIntervalMillis: Long = DEFAULT_WRITE_INTERVAL_MILLIS,
) {
    private var current: ChargeSession? = null
    private var restored = false
    private var lastWriteMillis = 0L

    fun reset() {
        current = null
        restored = false
        lastWriteMillis = 0L
    }

    suspend fun onReading(snapshot: BatterySnapshot) {
        if (snapshot.source != source) return
        if (!restored) {
            restore(snapshot.timestampMillis)
            restored = true
        }
        val ts = snapshot.timestampMillis
        val charging = snapshot.chargeStatus == ChargeStatus.CHARGING && snapshot.powerSource in CHARGER_TYPES
        val open = current
        if (open != null) {
            val gap = ts - open.lastSeenMillis
            val ended = !charging || snapshot.powerSource != open.powerSource || gap > maxGapMillis
            if (ended) {
                // After a long silence the real end is unknown, so close at the last time it was seen.
                val end = if (gap > maxGapMillis) open.lastSeenMillis else ts
                val finished = open.copy(
                    endMillis = end,
                    lastSeenMillis = end,
                    lastPercent = if (gap > maxGapMillis) open.lastPercent else snapshot.levelPercent ?: open.lastPercent,
                )
                repository.update(finished)
                current = null
            }
        }
        val temperature = snapshot.temperatureC?.takeIf { it.isFinite() } ?: return
        if (!charging) return
        val running = current
        if (running == null) start(snapshot, temperature) else track(running, snapshot, temperature)
    }

    private suspend fun restore(nowMillis: Long) {
        val open = repository.getActive(source) ?: return
        if (nowMillis - open.lastSeenMillis > maxGapMillis) {
            repository.update(open.copy(endMillis = open.lastSeenMillis))
        } else {
            current = open
        }
    }

    private suspend fun start(snapshot: BatterySnapshot, temperature: Float) {
        val ts = snapshot.timestampMillis
        val draft = ChargeSession(
            source = source, startMillis = ts, endMillis = null, lastSeenMillis = ts,
            powerSource = snapshot.powerSource, startTempC = temperature, peakTempC = temperature,
            tempSumC = temperature.toDouble(), readingCount = 1,
            startPercent = snapshot.levelPercent, lastPercent = snapshot.levelPercent,
        )
        current = draft.copy(id = repository.start(draft))
        lastWriteMillis = ts
    }

    private suspend fun track(session: ChargeSession, snapshot: BatterySnapshot, temperature: Float) {
        val ts = snapshot.timestampMillis
        val updated = session.copy(
            lastSeenMillis = ts,
            peakTempC = maxOf(session.peakTempC, temperature),
            tempSumC = session.tempSumC + temperature,
            readingCount = session.readingCount + 1,
            lastPercent = snapshot.levelPercent ?: session.lastPercent,
        )
        current = updated
        if (updated.peakTempC > session.peakTempC || ts - lastWriteMillis >= writeIntervalMillis) {
            repository.update(updated)
            lastWriteMillis = ts
        }
    }

    companion object {
        val CHARGER_TYPES = setOf(PowerSource.AC, PowerSource.USB, PowerSource.WIRELESS, PowerSource.DOCK)
        const val DEFAULT_MAX_GAP_MILLIS = 120_000L
        const val DEFAULT_WRITE_INTERVAL_MILLIS = 30_000L
    }
}

/** How one kind of charger performed across the user's own sessions. */
data class ChargerStats(
    val type: PowerSource,
    val sessions: Int,
    /** Average of (peak - starting temperature) over the sessions. */
    val averageRiseC: Float,
    val averagePeakC: Float,
    val hottestPeakC: Float,
    /** Average charging speed in percentage points per hour, when enough sessions reported a level. */
    val averagePercentPerHour: Double?,
    /** True once there are enough sessions to rank this charger. */
    val ranked: Boolean,
)

enum class ChargingVerdict { NOT_ENOUGH_DATA, NO_CLEAR_DIFFERENCE, CLEAR_DIFFERENCE }

data class ChargingLabReport(
    /** Completed sessions long enough to count. */
    val countedSessions: Int,
    /** Ranked chargers first (coolest first), then those still collecting data. */
    val chargers: List<ChargerStats>,
    val verdict: ChargingVerdict,
    val coolest: PowerSource?,
    val hottest: PowerSource?,
    /** Difference in average rise between the hottest and coolest ranked charger. */
    val spreadC: Float?,
    /** A session is running right now (it is not counted until it ends). */
    val inProgress: Boolean,
)

object ChargingLab {
    /** Shorter sessions are skipped: too little time for the temperature to settle. */
    const val MIN_SESSION_MILLIS = 10 * 60_000L
    const val MIN_READINGS = 3

    /** A charger type needs this many counted sessions before it is ranked. */
    const val MIN_SESSIONS_PER_CHARGER = 3

    /** Average rises closer than this are reported as no clear difference. */
    const val MIN_MEANINGFUL_SPREAD_C = 1.0f

    fun analyze(sessions: List<ChargeSession>): ChargingLabReport {
        val counted = sessions.filter {
            it.endMillis != null && it.durationMillis >= MIN_SESSION_MILLIS && it.readingCount >= MIN_READINGS
        }
        val stats = counted.groupBy { it.powerSource }.map { (type, list) ->
            val speeds = list.mapNotNull { it.percentPerHour }
            ChargerStats(
                type = type,
                sessions = list.size,
                averageRiseC = list.map { it.riseC }.average().toFloat(),
                averagePeakC = list.map { it.peakTempC }.average().toFloat(),
                hottestPeakC = list.maxOf { it.peakTempC },
                averagePercentPerHour = if (speeds.size >= 2) speeds.average() else null,
                ranked = list.size >= MIN_SESSIONS_PER_CHARGER,
            )
        }
        val ranked = stats.filter { it.ranked }.sortedBy { it.averageRiseC }
        val unranked = stats.filterNot { it.ranked }.sortedByDescending { it.sessions }
        val inProgress = sessions.any { it.endMillis == null }

        if (ranked.size < 2) {
            return ChargingLabReport(counted.size, ranked + unranked, ChargingVerdict.NOT_ENOUGH_DATA, null, null, null, inProgress)
        }
        val spread = ranked.last().averageRiseC - ranked.first().averageRiseC
        val verdict = if (spread < MIN_MEANINGFUL_SPREAD_C) ChargingVerdict.NO_CLEAR_DIFFERENCE else ChargingVerdict.CLEAR_DIFFERENCE
        return ChargingLabReport(
            counted.size, ranked + unranked, verdict,
            coolest = ranked.first().type, hottest = ranked.last().type, spreadC = spread, inProgress = inProgress,
        )
    }
}

/**
 * Fixed, clearly labelled sample sessions for Demo Mode, so the report card can be shown without waiting
 * for real charging. They are stored with the DEMO source and deleted when Demo Mode ends.
 */
object SimulatedCharging {
    private data class Plan(val type: PowerSource, val count: Int, val rise: Float, val percent: Int, val minutes: Int)

    private val plans = listOf(
        Plan(PowerSource.USB, 4, rise = 3.2f, percent = 18, minutes = 90),
        Plan(PowerSource.AC, 5, rise = 6.8f, percent = 46, minutes = 60),
        Plan(PowerSource.WIRELESS, 3, rise = 8.9f, percent = 22, minutes = 75),
    )

    fun sessions(nowMillis: Long): List<ChargeSession> {
        var n = 0
        return plans.flatMap { plan ->
            (0 until plan.count).map { i ->
                n += 1
                val start = nowMillis - n * 6 * 3_600_000L
                val startTemp = 29f + (i % 3) * 0.6f
                // Small deterministic variation so the numbers are not suspiciously identical.
                val rise = plan.rise + ((i % 3) - 1) * 0.4f
                ChargeSession(
                    source = ReadingSource.DEMO,
                    startMillis = start,
                    endMillis = start + plan.minutes * 60_000L,
                    lastSeenMillis = start + plan.minutes * 60_000L,
                    powerSource = plan.type,
                    startTempC = startTemp,
                    peakTempC = startTemp + rise,
                    tempSumC = (startTemp + rise * 0.6f).toDouble() * 20,
                    readingCount = 20,
                    startPercent = 30,
                    lastPercent = 30 + plan.percent,
                )
            }
        }
    }
}
