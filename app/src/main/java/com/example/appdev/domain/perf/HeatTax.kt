package com.example.appdev.domain.perf

import com.example.appdev.domain.model.ChargeStatus
import com.example.appdev.domain.model.ReadingSource
import kotlinx.coroutines.flow.Flow
import kotlin.math.sqrt

/** One speed test: how fast a fixed amount of work ran, and how hot the battery was at the time. */
data class SpeedTestResult(
    val id: Long = 0,
    val source: ReadingSource,
    val timestampMillis: Long,
    val temperatureC: Float,
    /** Work completed per millisecond. Only meaningful compared with other scores from the same phone. */
    val score: Double,
    val chargeStatus: ChargeStatus,
    /** Android's own thermal status at the time (its enum name), when the device reports it. */
    val thermalStatus: String?,
)

interface SpeedTestRepository {
    suspend fun add(result: SpeedTestResult)
    fun observe(source: ReadingSource): Flow<List<SpeedTestResult>>
    suspend fun deleteAll(source: ReadingSource)
}

/**
 * A fixed, deterministic amount of integer and floating-point work, timed. The same code is run every time,
 * so a lower score on the same phone means the processor was running slower, which is what thermal
 * throttling does. It is not a general benchmark and its numbers mean nothing across different phones.
 */
object CpuWorkload {
    private const val CHUNK = 2_000

    /** Written so the compiler cannot discard the work as unused. */
    @Volatile
    var sink: Double = 0.0

    /** Runs the workload for about [durationNanos] and returns iterations per millisecond. */
    fun score(durationNanos: Long, clock: () -> Long = System::nanoTime): Double {
        var x = 123_456_789L
        var acc = 0.0
        var iterations = 0L
        val start = clock()
        var now = start
        while (now - start < durationNanos) {
            repeat(CHUNK) {
                x = x * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
                acc += sqrt((x ushr 11).toDouble())
            }
            iterations += CHUNK
            now = clock()
        }
        sink = acc
        val elapsedMillis = (now - start) / 1_000_000.0
        return if (elapsedMillis > 0) iterations / elapsedMillis else 0.0
    }

    /** One untimed warm-up, then the median of [trials] runs, so one unlucky run does not decide the result. */
    fun medianScore(trials: Int = 3, durationNanos: Long = 250_000_000L, warmUpNanos: Long = 80_000_000L): Double {
        score(warmUpNanos)
        val scores = List(trials) { score(durationNanos) }.sorted()
        return scores[scores.size / 2]
    }
}

enum class SlowdownVerdict { FULL_SPEED, SLIGHT, NOTICEABLE, HEAVY }

/** Average speed, as a percentage of the baseline, for readings within one temperature range. */
data class TemperatureBand(val fromC: Float, val toC: Float?, val averagePercent: Int?, val samples: Int)

data class HeatTaxReport(
    val resultCount: Int,
    /** Null until there are enough results to know what normal speed is. */
    val baselineScore: Double?,
    /** True when the baseline is the best of mixed-temperature tests rather than of cool ones. */
    val provisionalBaseline: Boolean,
    val coolSamples: Int,
    val latest: SpeedTestResult?,
    /** The latest result as a percentage of normal speed (capped at 100), or null without a baseline. */
    val latestPercent: Int?,
    /** 100 minus [latestPercent]. */
    val heatTaxPercent: Int?,
    val verdict: SlowdownVerdict?,
    val bands: List<TemperatureBand>,
)

object HeatTax {
    /** Tests below this temperature define what "normal speed" is. */
    const val COOL_BELOW_C = 35f

    /** Cool results needed before the baseline is firm. */
    const val MIN_COOL_SAMPLES = 3

    /** Results needed to give a provisional baseline when too few were taken while cool. */
    const val MIN_PROVISIONAL_SAMPLES = 3

    /** Differences smaller than this are inside the noise of a single test, so they are not called a slowdown. */
    const val NOISE_PERCENT = 5

    private val BAND_EDGES = listOf(0f, COOL_BELOW_C, 38f, 41f)

    fun analyze(results: List<SpeedTestResult>): HeatTaxReport {
        val ordered = results.sortedBy { it.timestampMillis }
        val latest = ordered.lastOrNull()
        val cool = ordered.filter { it.temperatureC < COOL_BELOW_C && it.score > 0 }

        val baseline: Double?
        val provisional: Boolean
        when {
            cool.size >= MIN_COOL_SAMPLES -> { baseline = percentile75(cool.map { it.score }); provisional = false }
            ordered.count { it.score > 0 } >= MIN_PROVISIONAL_SAMPLES -> {
                baseline = ordered.maxOf { it.score }; provisional = true
            }
            else -> { baseline = null; provisional = false }
        }

        val percent = latest?.let { l -> baseline?.takeIf { it > 0 }?.let { percentOf(l.score, it) } }
        val tax = percent?.let { 100 - it }

        return HeatTaxReport(
            resultCount = ordered.size,
            baselineScore = baseline,
            provisionalBaseline = provisional,
            coolSamples = cool.size,
            latest = latest,
            latestPercent = percent,
            heatTaxPercent = tax,
            verdict = tax?.let(::verdict),
            bands = bands(ordered, baseline),
        )
    }

    fun verdict(taxPercent: Int): SlowdownVerdict = when {
        taxPercent < NOISE_PERCENT -> SlowdownVerdict.FULL_SPEED
        taxPercent < 15 -> SlowdownVerdict.SLIGHT
        taxPercent < 30 -> SlowdownVerdict.NOTICEABLE
        else -> SlowdownVerdict.HEAVY
    }

    private fun percentOf(score: Double, baseline: Double): Int =
        (score / baseline * 100).toInt().coerceIn(0, 100)

    private fun bands(results: List<SpeedTestResult>, baseline: Double?): List<TemperatureBand> =
        BAND_EDGES.mapIndexed { i, from ->
            val to = BAND_EDGES.getOrNull(i + 1)
            val inBand = results.filter { it.temperatureC >= from && (to == null || it.temperatureC < to) && it.score > 0 }
            val avg = if (baseline != null && baseline > 0 && inBand.isNotEmpty()) {
                inBand.map { percentOf(it.score, baseline) }.average().toInt()
            } else {
                null
            }
            TemperatureBand(from, to, avg, inBand.size)
        }

    private fun percentile75(values: List<Double>): Double {
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * 0.75).toInt()
        return sorted[index]
    }
}

/**
 * A stand-in speed model for Demo Mode: full speed up to 38 C, then a steady slowdown to 70% at 46 C.
 * It is plainly labelled as simulated wherever it appears, and is stored with the demo data.
 */
object SimulatedSpeed {
    const val FULL_SPEED_SCORE = 1_000.0

    fun scoreAt(temperatureC: Float): Double {
        val percent = when {
            temperatureC <= 38f -> 100.0
            temperatureC >= 46f -> 70.0
            else -> 100.0 - (temperatureC - 38f) / 8f * 30.0
        }
        return FULL_SPEED_SCORE * percent / 100.0
    }
}
