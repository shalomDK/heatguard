package com.example.appdev.domain

import com.example.appdev.data.db.toDomain
import com.example.appdev.data.db.toEntity
import com.example.appdev.domain.ai.PhoneMetrics
import com.example.appdev.domain.autopsy.AutopsyPrompt
import com.example.appdev.domain.autopsy.FactorType
import com.example.appdev.domain.autopsy.HeatAutopsy
import com.example.appdev.domain.autopsy.MissingSignal
import com.example.appdev.domain.autopsy.RecoveryVerdict
import com.example.appdev.domain.autopsy.Strength
import com.example.appdev.domain.incident.Incident
import com.example.appdev.domain.incident.IncidentContext
import com.example.appdev.domain.incident.IncidentContextBuilder
import com.example.appdev.domain.incident.IncidentTracker
import com.example.appdev.domain.incident.TrackerEvent
import com.example.appdev.domain.model.ChargeStatus
import com.example.appdev.domain.model.PowerSource
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.model.UserSettings
import com.example.appdev.domain.thermal.TemperaturePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatAutopsyTest {

    private val minute = 60_000L
    private var nextId = 1L

    private fun incident(
        ctx: IncidentContext? = IncidentContext(),
        charging: Boolean = false,
        power: PowerSource = if (charging) PowerSource.USB else PowerSource.NONE,
        durationMin: Long = 10,
        active: Boolean = false,
        initial: Float = 40f,
        peak: Float = 42f,
        id: Long = nextId++,
        startMin: Long = id * 100,
    ) = Incident(
        id = id, source = ReadingSource.DEVICE, startMillis = startMin * minute,
        endMillis = if (active) null else (startMin + durationMin) * minute,
        lastSeenMillis = (startMin + durationMin) * minute,
        initialTempC = initial, peakTempC = peak, peakMillis = (startMin + 3) * minute,
        endTempC = if (active) null else 37f, thresholdC = 40f,
        startBatteryPercent = 80, endBatteryPercent = null,
        startChargeStatus = if (charging) ChargeStatus.CHARGING else ChargeStatus.DISCHARGING,
        startPowerSource = power, context = ctx,
    )

    private fun withRates(vararg rates: Float) = rates.map { incident(IncidentContext(heatingRateCPerMin = it)) }

    private fun factors(i: Incident, others: List<Incident> = emptyList()) =
        HeatAutopsy.analyze(i, others).factors

    // ---------------------------------------------------------------- fast heating

    @Test
    fun `fast heating is judged against the user's own usual rate when there is history`() {
        val usual = withRates(0.3f, 0.4f, 0.5f) // median 0.4
        val high = factors(incident(IncidentContext(heatingRateCPerMin = 0.9f)), usual).single()
        assertEquals(FactorType.FAST_HEATING, high.type)
        assertEquals(Strength.HIGH, high.strength)
        assertEquals(0.9f, high.value!!, 0f)
        assertEquals(0.4f, high.baseline!!, 0.0001f)

        assertEquals(Strength.MEDIUM, factors(incident(IncidentContext(heatingRateCPerMin = 0.65f)), usual).single().strength)
        assertTrue(factors(incident(IncidentContext(heatingRateCPerMin = 0.5f)), usual).isEmpty())
    }

    @Test
    fun `without enough history only an absolutely fast rise counts and there is no baseline`() {
        val few = withRates(0.3f, 0.4f) // two is not enough to know what is usual
        val high = factors(incident(IncidentContext(heatingRateCPerMin = 1.2f)), few).single()
        assertEquals(Strength.HIGH, high.strength)
        assertNull(high.baseline)
        assertEquals(Strength.MEDIUM, factors(incident(IncidentContext(heatingRateCPerMin = 0.7f))).single().strength)
        assertTrue(factors(incident(IncidentContext(heatingRateCPerMin = 0.3f))).isEmpty())
    }

    @Test
    fun `an incident is never compared with itself`() {
        val me = incident(IncidentContext(heatingRateCPerMin = 0.9f), id = 50)
        val report = HeatAutopsy.analyze(me, listOf(me) + withRates(0.4f, 0.4f))
        assertEquals(2, report.baselineIncidents)
    }

    // ---------------------------------------------------------------- the other factors

    @Test
    fun `charging counts, and wireless counts more`() {
        assertEquals(Strength.MEDIUM, factors(incident(charging = true, power = PowerSource.USB)).single().strength)
        assertEquals(Strength.MEDIUM, factors(incident(charging = true, power = PowerSource.AC)).single().strength)
        val wireless = factors(incident(charging = true, power = PowerSource.WIRELESS)).single()
        assertEquals(Strength.HIGH, wireless.strength)
        assertEquals(PowerSource.WIRELESS, wireless.power)
        assertTrue(factors(incident(charging = false)).isEmpty())
    }

    @Test
    fun `bright screens and memory pressure are flagged only when they are extreme`() {
        assertEquals(Strength.HIGH, factors(incident(IncidentContext(brightnessPercent = 95))).single().strength)
        assertEquals(Strength.MEDIUM, factors(incident(IncidentContext(brightnessPercent = 80))).single().strength)
        assertTrue(factors(incident(IncidentContext(brightnessPercent = 50))).isEmpty())

        val low = factors(incident(IncidentContext(lowMemory = true, freeRamPercent = 6))).single()
        assertEquals(FactorType.LOW_MEMORY, low.type)
        assertEquals(Strength.HIGH, low.strength)
        assertEquals(Strength.MEDIUM, factors(incident(IncidentContext(freeRamPercent = 5, lowMemory = false))).single().strength)
        assertTrue(factors(incident(IncidentContext(freeRamPercent = 40, lowMemory = false))).isEmpty())
    }

    @Test
    fun `a phone that was already warm is flagged`() {
        assertEquals(Strength.HIGH, factors(incident(IncidentContext(tempFiveMinutesBeforeC = 39f))).single().strength)
        assertEquals(Strength.MEDIUM, factors(incident(IncidentContext(tempFiveMinutesBeforeC = 37.5f))).single().strength)
        assertTrue(factors(incident(IncidentContext(tempFiveMinutesBeforeC = 34f))).isEmpty())
    }

    @Test
    fun `factors are ranked strongest first`() {
        val ranked = factors(
            incident(
                IncidentContext(brightnessPercent = 80, tempFiveMinutesBeforeC = 39f, lowMemory = true),
                charging = true, power = PowerSource.WIRELESS,
            ),
        )
        assertEquals(ranked.map { it.strength.ordinal }, ranked.map { it.strength.ordinal }.sorted())
        assertEquals(Strength.MEDIUM, ranked.last().strength)
        assertEquals(FactorType.HIGH_BRIGHTNESS, ranked.last().type)
    }

    // ---------------------------------------------------------------- honesty

    @Test
    fun `a calm incident honestly has no factors`() {
        val report = HeatAutopsy.analyze(incident(IncidentContext(brightnessPercent = 40, freeRamPercent = 50, lowMemory = false, heatingRateCPerMin = 0.2f, tempFiveMinutesBeforeC = 33f, powerSaveOn = false)), emptyList())
        assertTrue(report.factors.isEmpty())
        assertTrue(report.contextRecorded)
        assertTrue(report.missing.isEmpty())
    }

    @Test
    fun `an incident from before context was recorded reports everything as missing`() {
        val report = HeatAutopsy.analyze(incident(ctx = null, charging = true), emptyList())
        assertFalse(report.contextRecorded)
        assertEquals(MissingSignal.entries, report.missing)
        assertEquals(listOf(FactorType.CHARGING), report.factors.map { it.type }) // what is known is still used
    }

    @Test
    fun `partially recorded context lists only what is missing`() {
        val report = HeatAutopsy.analyze(incident(IncidentContext(brightnessPercent = 50, lowMemory = false)), emptyList())
        assertEquals(listOf(MissingSignal.HEATING_RATE, MissingSignal.PRE_TEMPERATURE), report.missing)
    }

    @Test
    fun `summary numbers come straight from the incident`() {
        val report = HeatAutopsy.analyze(incident(initial = 40.5f, peak = 43f), emptyList())
        assertEquals(3 * minute, report.timeToPeakMillis)
        assertEquals(2.5f, report.riseC, 0.001f)
        assertEquals(3f, report.peakOverThresholdC, 0.001f)
    }

    @Test
    fun `recovery is compared with the user's usual but only with enough history`() {
        val typical = listOf(10L, 10L, 12L).map { incident(durationMin = it) } // median 10 min
        assertEquals(RecoveryVerdict.SLOWER, HeatAutopsy.analyze(incident(durationMin = 20), typical).recovery!!.verdict)
        assertEquals(RecoveryVerdict.FASTER, HeatAutopsy.analyze(incident(durationMin = 5), typical).recovery!!.verdict)
        assertEquals(RecoveryVerdict.TYPICAL, HeatAutopsy.analyze(incident(durationMin = 11), typical).recovery!!.verdict)
        assertNull(HeatAutopsy.analyze(incident(durationMin = 20), typical.take(2)).recovery)
        assertNull(HeatAutopsy.analyze(incident(active = true), typical).recovery)
    }

    // ---------------------------------------------------------------- prompt

    @Test
    fun `the ai question carries the facts, the caveats and never blames an app`() {
        val i = incident(
            IncidentContext(brightnessPercent = 92, freeRamPercent = 8, lowMemory = true, heatingRateCPerMin = 0.9f, tempFiveMinutesBeforeC = 38f),
            charging = true, power = PowerSource.WIRELESS,
        )
        val report = HeatAutopsy.analyze(i, withRates(0.3f, 0.4f, 0.5f))
        val text = AutopsyPrompt.build(i, report, nowMillis = i.startMillis + 30 * minute)

        assertTrue(text.contains("30 min ago"))
        assertTrue(text.contains("charging via wireless"))
        assertTrue(text.contains("Screen brightness was 92%"))
        assertTrue(text.contains("0.90 °C/min (my usual for incidents: 0.40 °C/min)"))
        assertTrue(text.contains("low memory"))
        assertTrue(text.contains("do not guess which app"))
        assertTrue(text.contains("contributing factors rather than proven causes"))
        assertFalse(text.contains("Not recorded"))
        val old = AutopsyPrompt.build(incident(ctx = null), HeatAutopsy.analyze(incident(ctx = null), emptyList()), 0)
        assertTrue(old.contains("Not recorded for this incident"))
    }

    // ---------------------------------------------------------------- capturing the context

    private val metrics = PhoneMetrics(
        totalRamBytes = 8_000, availRamBytes = 2_000, lowMemory = false,
        totalStorageBytes = 100, freeStorageBytes = 50, powerSaveOn = true, brightnessPercent = 64,
        uptimeMillis = 1, cpuCores = 8,
    )

    @Test
    fun `context is measured from the history and the phone's metrics`() {
        val snap = snapshot(40f, timestampMillis = 10 * minute)
        val history = (0..20).map { TemperaturePoint(it * 30_000L, 30f + 0.4f * (it * 0.5f)) } // 0.4 C/min, up to 10 min
        val ctx = IncidentContextBuilder.build(snap, history, metrics)

        assertEquals(0.4f, ctx.heatingRateCPerMin!!, 0.01f)
        assertEquals(32f, ctx.tempFiveMinutesBeforeC!!, 0.01f)
        assertEquals(64, ctx.brightnessPercent)
        assertEquals(true, ctx.powerSaveOn)
        assertEquals(25, ctx.freeRamPercent)
        assertEquals(false, ctx.lowMemory)
    }

    @Test
    fun `nothing is invented when history or metrics are missing`() {
        val ctx = IncidentContextBuilder.build(snapshot(40f, timestampMillis = 10 * minute), emptyList(), null)
        assertEquals(IncidentContext(), ctx)
        // History that does not reach back five minutes gives no "before" temperature.
        val short = IncidentContextBuilder.build(snapshot(40f, timestampMillis = 10 * minute), listOf(TemperaturePoint(9 * minute, 39f)), null)
        assertNull(short.tempFiveMinutesBeforeC)
    }

    @Test
    fun `the tracker stores the context when an incident begins, and survives a failing provider`() = runBlocking {
        val repo = FakeIncidentRepository()
        val ctx = IncidentContext(brightnessPercent = 70, heatingRateCPerMin = 0.8f)
        val tracker = IncidentTracker(repo, ReadingSource.DEVICE, contextProvider = { ctx })
        val event = tracker.onReading(snapshot(41f, timestampMillis = 1_000), UserSettings())
        assertTrue(event is TrackerEvent.Triggered)
        assertEquals(ctx, repo.allIncidents.single().context)

        val repo2 = FakeIncidentRepository()
        val broken = IncidentTracker(repo2, ReadingSource.DEVICE, contextProvider = { error("sensor failed") })
        assertTrue(broken.onReading(snapshot(41f, timestampMillis = 1_000), UserSettings()) is TrackerEvent.Triggered)
        assertNull(repo2.allIncidents.single().context) // the incident is still recorded
    }

    // ---------------------------------------------------------------- storage

    @Test
    fun `context survives the database round trip, and old rows stay without context`() {
        val full = incident(IncidentContext(70, true, 12, false, 0.75f, 36.5f))
        assertEquals(full.context, full.toEntity().toDomain().context)

        val old = incident(ctx = null)
        assertNull(old.toEntity().toDomain().context)
        assertNotNull(incident(IncidentContext(lowMemory = false)).toEntity().toDomain().context)
    }
}
