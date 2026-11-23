package com.example.appdev.domain

import com.example.appdev.domain.model.ChargeStatus
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.perf.CpuWorkload
import com.example.appdev.domain.perf.HeatTax
import com.example.appdev.domain.perf.SimulatedSpeed
import com.example.appdev.domain.perf.SlowdownVerdict
import com.example.appdev.domain.perf.SpeedTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatTaxTest {

    private var clock = 0L

    private fun result(temp: Float, score: Double, t: Long = clock++) = SpeedTestResult(
        source = ReadingSource.DEVICE, timestampMillis = t, temperatureC = temp, score = score,
        chargeStatus = ChargeStatus.DISCHARGING, thermalStatus = null,
    )

    // ---------------------------------------------------------------- workload

    @Test
    fun `the workload score is work done divided by time taken`() {
        var calls = 0L
        // Each clock() call advances 1 ms: 10 ms of work is 5 chunks of 2000 iterations at 2 chunks per... see loop.
        val score = CpuWorkload.score(durationNanos = 10_000_000) { (calls++) * 1_000_000 }
        // clock() is called once for start and once per chunk; the loop ends when 10 ms have passed.
        assertEquals(2_000.0, score, 0.001) // 2000 iterations every millisecond of fake time
    }

    @Test
    fun `the real workload produces a positive score and keeps its result alive`() {
        val score = CpuWorkload.score(durationNanos = 20_000_000)
        assertTrue("score was $score", score > 0)
        assertTrue(CpuWorkload.sink > 0)
    }

    @Test
    fun `median score is positive and uses several trials`() {
        val score = CpuWorkload.medianScore(trials = 3, durationNanos = 15_000_000, warmUpNanos = 5_000_000)
        assertTrue(score > 0)
    }

    // ---------------------------------------------------------------- baseline

    @Test
    fun `no results means no baseline and no claims`() {
        val r = HeatTax.analyze(emptyList())
        assertNull(r.baselineScore)
        assertNull(r.latestPercent)
        assertNull(r.heatTaxPercent)
        assertNull(r.verdict)
        assertEquals(0, r.resultCount)
    }

    @Test
    fun `a firm baseline needs three cool tests`() {
        val two = HeatTax.analyze(listOf(result(30f, 1000.0), result(31f, 1000.0), result(42f, 800.0)))
        assertTrue(two.provisionalBaseline) // fell back to the best of all three
        assertEquals(2, two.coolSamples)

        val three = HeatTax.analyze(listOf(result(30f, 1000.0), result(31f, 990.0), result(32f, 1010.0), result(42f, 800.0)))
        assertFalse(three.provisionalBaseline)
        assertEquals(3, three.coolSamples)
        assertNotNull(three.baselineScore)
    }

    @Test
    fun `baseline ignores slow outliers from background load`() {
        // One cool test was run while something else was busy: the baseline still reflects normal speed.
        val r = HeatTax.analyze(
            listOf(result(30f, 1000.0), result(31f, 1010.0), result(30f, 500.0), result(32f, 990.0), result(33f, 1005.0)),
        )
        assertTrue("baseline ${r.baselineScore}", r.baselineScore!! >= 990.0)
    }

    @Test
    fun `fewer than three results gives no baseline at all`() {
        assertNull(HeatTax.analyze(listOf(result(30f, 1000.0), result(40f, 900.0))).baselineScore)
    }

    // ---------------------------------------------------------------- the tax

    private fun withBaseline(vararg latest: Pair<Float, Double>) =
        listOf(result(30f, 1000.0), result(31f, 1000.0), result(32f, 1000.0)) + latest.map { result(it.first, it.second) }

    @Test
    fun `the latest result is a percentage of normal speed and the tax is the rest`() {
        val r = HeatTax.analyze(withBaseline(42f to 820.0))
        assertEquals(82, r.latestPercent)
        assertEquals(18, r.heatTaxPercent)
        assertEquals(SlowdownVerdict.NOTICEABLE, r.verdict)
        assertEquals(42f, r.latest!!.temperatureC, 0f)
    }

    @Test
    fun `speed never reads above 100 percent`() {
        assertEquals(100, HeatTax.analyze(withBaseline(30f to 1100.0)).latestPercent)
        assertEquals(0, HeatTax.analyze(withBaseline(30f to 1100.0)).heatTaxPercent)
    }

    @Test
    fun `verdicts follow the thresholds and small differences count as noise`() {
        assertEquals(SlowdownVerdict.FULL_SPEED, HeatTax.verdict(0))
        assertEquals(SlowdownVerdict.FULL_SPEED, HeatTax.verdict(HeatTax.NOISE_PERCENT - 1))
        assertEquals(SlowdownVerdict.SLIGHT, HeatTax.verdict(HeatTax.NOISE_PERCENT))
        assertEquals(SlowdownVerdict.SLIGHT, HeatTax.verdict(14))
        assertEquals(SlowdownVerdict.NOTICEABLE, HeatTax.verdict(15))
        assertEquals(SlowdownVerdict.NOTICEABLE, HeatTax.verdict(29))
        assertEquals(SlowdownVerdict.HEAVY, HeatTax.verdict(30))
    }

    @Test
    fun `the latest result is the most recent by time, not by insertion order`() {
        val r = HeatTax.analyze(listOf(result(42f, 700.0, t = 100), result(30f, 1000.0, t = 1), result(31f, 1000.0, t = 2), result(32f, 1000.0, t = 3)))
        assertEquals(700.0, r.latest!!.score, 0.0)
        assertEquals(70, r.latestPercent)
    }

    // ---------------------------------------------------------------- bands

    @Test
    fun `speed is averaged by temperature band`() {
        val r = HeatTax.analyze(
            withBaseline(36f to 980.0, 37f to 960.0, 39f to 900.0, 43f to 800.0, 44f to 760.0),
        )
        val byFrom = r.bands.associateBy { it.fromC }
        assertEquals(100, byFrom[0f]!!.averagePercent) // the three cool tests
        assertEquals(3, byFrom[0f]!!.samples)
        assertEquals(97, byFrom[35f]!!.averagePercent) // (98 + 96) / 2
        assertEquals(90, byFrom[38f]!!.averagePercent)
        assertEquals(78, byFrom[41f]!!.averagePercent) // (80 + 76) / 2
        assertNull(byFrom[41f]!!.toC)
    }

    @Test
    fun `bands with no tests are left empty, not filled in`() {
        val r = HeatTax.analyze(withBaseline())
        assertNull(r.bands.first { it.fromC == 41f }.averagePercent)
        assertEquals(0, r.bands.first { it.fromC == 41f }.samples)
    }

    // ---------------------------------------------------------------- demo model

    @Test
    fun `the simulated speed model slows steadily above 38 degrees`() {
        assertEquals(1000.0, SimulatedSpeed.scoreAt(30f), 0.001)
        assertEquals(1000.0, SimulatedSpeed.scoreAt(38f), 0.001)
        assertEquals(850.0, SimulatedSpeed.scoreAt(42f), 0.001)
        assertEquals(700.0, SimulatedSpeed.scoreAt(46f), 0.001)
        assertEquals(700.0, SimulatedSpeed.scoreAt(55f), 0.001)
        assertTrue(SimulatedSpeed.scoreAt(40f) > SimulatedSpeed.scoreAt(44f))
    }

    @Test
    fun `simulated results run through the same analysis`() {
        val demo = listOf(30f, 31f, 32f, 44f).mapIndexed { i, t -> result(t, SimulatedSpeed.scoreAt(t), t = i.toLong()) }
        val r = HeatTax.analyze(demo)
        assertEquals(77, r.latestPercent) // 44 C -> 77.5% of full speed, shown rounded down
        assertEquals(SlowdownVerdict.NOTICEABLE, r.verdict)
    }
}
