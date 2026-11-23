package com.example.appdev.domain

import com.example.appdev.domain.charging.ChargeSession
import com.example.appdev.domain.charging.ChargeSessionTracker
import com.example.appdev.domain.charging.ChargingLab
import com.example.appdev.domain.charging.ChargingVerdict
import com.example.appdev.domain.charging.SimulatedCharging
import com.example.appdev.domain.model.ChargeStatus
import com.example.appdev.domain.model.PowerSource
import com.example.appdev.domain.model.ReadingSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingLabTest {

    private val repo = FakeChargeSessionRepository()
    private val tracker = ChargeSessionTracker(repo, ReadingSource.DEVICE, maxGapMillis = 120_000, writeIntervalMillis = 30_000)

    private fun charging(t: Long, temp: Float?, source: PowerSource = PowerSource.AC, level: Int = 50) =
        snapshot(temp, timestampMillis = t, chargeStatus = ChargeStatus.CHARGING, powerSource = source).copy(levelPercent = level)

    private fun unplugged(t: Long, level: Int = 60) =
        snapshot(33f, timestampMillis = t).copy(levelPercent = level)

    // ---- tracker ----

    @Test
    fun `a charge becomes one session with start, peak, average and battery gain`() = runBlocking {
        tracker.onReading(charging(0, 30f, level = 40))
        tracker.onReading(charging(60_000, 33f, level = 43))
        tracker.onReading(charging(120_000, 36f, level = 46))
        tracker.onReading(charging(180_000, 34f, level = 49))
        tracker.onReading(unplugged(240_000, level = 50))

        val s = repo.all.single()
        assertEquals(PowerSource.AC, s.powerSource)
        assertEquals(30f, s.startTempC, 0f)
        assertEquals(36f, s.peakTempC, 0f)
        assertEquals(6f, s.riseC, 0f)
        assertEquals(4, s.readingCount)
        assertEquals(133.0, s.tempSumC, 0.001)
        assertEquals(40, s.startPercent)
        assertEquals(50, s.lastPercent)
        assertEquals(240_000L, s.endMillis)
    }

    @Test
    fun `readings that are not charging, or are from another source, record nothing`() = runBlocking {
        tracker.onReading(unplugged(0))
        tracker.onReading(snapshot(31f, timestampMillis = 1_000, chargeStatus = ChargeStatus.FULL, powerSource = PowerSource.AC))
        tracker.onReading(charging(2_000, 31f).copy(source = ReadingSource.DEMO))
        assertTrue(repo.all.isEmpty())
    }

    @Test
    fun `a charging reading without a temperature does not start a session`() = runBlocking {
        tracker.onReading(charging(0, null))
        assertTrue(repo.all.isEmpty())
    }

    @Test
    fun `switching to a different kind of source ends one session and starts the next`() = runBlocking {
        tracker.onReading(charging(0, 30f, PowerSource.AC))
        tracker.onReading(charging(60_000, 32f, PowerSource.AC))
        tracker.onReading(charging(120_000, 31f, PowerSource.USB))

        assertEquals(2, repo.all.size)
        val first = repo.all[0]
        val second = repo.all[1]
        assertEquals(PowerSource.AC, first.powerSource)
        assertEquals(120_000L, first.endMillis)
        assertEquals(PowerSource.USB, second.powerSource)
        assertNull(second.endMillis)
    }

    @Test
    fun `after a long silence the session ends at the last time it was seen and a new one begins`() = runBlocking {
        tracker.onReading(charging(0, 30f))
        tracker.onReading(charging(60_000, 32f))
        tracker.onReading(charging(1_000_000, 31f)) // far beyond the 2-minute gap

        assertEquals(2, repo.all.size)
        assertEquals(60_000L, repo.all[0].endMillis) // not inflated to the later reading
        assertEquals(1_000_000L, repo.all[1].startMillis)
    }

    @Test
    fun `a session left open by a killed process is resumed when recent and closed at last seen when stale`() = runBlocking {
        tracker.onReading(charging(0, 30f))
        tracker.onReading(charging(60_000, 33f))
        val open = repo.all.single() // the peak update was stored

        // Recent: a fresh tracker picks the same session up.
        val resumed = ChargeSessionTracker(repo, ReadingSource.DEVICE, 120_000, 30_000)
        resumed.onReading(charging(90_000, 34f))
        assertEquals(1, repo.all.size)
        assertEquals(34f, repo.all.single().peakTempC, 0f)

        // Stale: another fresh tracker much later closes it where it was last seen.
        val late = ChargeSessionTracker(repo, ReadingSource.DEVICE, 120_000, 30_000)
        late.onReading(unplugged(5_000_000))
        assertNotNull(repo.all.single().endMillis)
        assertTrue(repo.all.single().endMillis!! < 5_000_000)
        assertEquals(open.id, repo.all.single().id)
    }

    // ---- report card ----

    private fun session(type: PowerSource, rise: Float, minutes: Long = 30, ended: Boolean = true, readings: Int = 10, start: Int = 40, end: Int = 60) =
        ChargeSession(
            source = ReadingSource.DEVICE, startMillis = 0, endMillis = if (ended) minutes * 60_000 else null,
            lastSeenMillis = minutes * 60_000, powerSource = type, startTempC = 30f, peakTempC = 30f + rise,
            tempSumC = 31.0 * readings, readingCount = readings, startPercent = start, lastPercent = end,
        )

    private fun many(type: PowerSource, rise: Float, n: Int) = List(n) { session(type, rise) }

    @Test
    fun `chargers are ranked coolest first once two kinds have enough sessions`() {
        val report = ChargingLab.analyze(many(PowerSource.AC, 7f, 3) + many(PowerSource.USB, 3f, 3) + many(PowerSource.WIRELESS, 9f, 3))

        assertEquals(ChargingVerdict.CLEAR_DIFFERENCE, report.verdict)
        assertEquals(listOf(PowerSource.USB, PowerSource.AC, PowerSource.WIRELESS), report.chargers.map { it.type })
        assertEquals(PowerSource.USB, report.coolest)
        assertEquals(PowerSource.WIRELESS, report.hottest)
        assertEquals(6f, report.spreadC!!, 0.001f)
        assertEquals(9, report.countedSessions)
    }

    @Test
    fun `one charger type alone is never ranked or compared`() {
        val report = ChargingLab.analyze(many(PowerSource.AC, 7f, 8))
        assertEquals(ChargingVerdict.NOT_ENOUGH_DATA, report.verdict)
        assertNull(report.coolest)
        assertNull(report.spreadC)
        assertEquals(8, report.chargers.single().sessions)
    }

    @Test
    fun `a charger with too few sessions is listed as still collecting and is not ranked`() {
        val report = ChargingLab.analyze(many(PowerSource.AC, 7f, 3) + many(PowerSource.USB, 2f, 2))
        assertEquals(ChargingVerdict.NOT_ENOUGH_DATA, report.verdict) // only one ranked type
        val usb = report.chargers.first { it.type == PowerSource.USB }
        assertFalse(usb.ranked)
        assertTrue(report.chargers.first().ranked) // ranked entries come first
    }

    @Test
    fun `a small gap between chargers is reported as no clear difference`() {
        val report = ChargingLab.analyze(many(PowerSource.AC, 5.0f, 3) + many(PowerSource.USB, 5.6f, 3))
        assertEquals(ChargingVerdict.NO_CLEAR_DIFFERENCE, report.verdict)
    }

    @Test
    fun `short, unfinished and thin sessions are not counted, but a live one is flagged`() {
        val report = ChargingLab.analyze(
            listOf(
                session(PowerSource.AC, 4f, minutes = 5), // too short
                session(PowerSource.AC, 4f, readings = 2), // too few readings
                session(PowerSource.AC, 4f, ended = false), // still running
            ),
        )
        assertEquals(0, report.countedSessions)
        assertTrue(report.chargers.isEmpty())
        assertTrue(report.inProgress)
    }

    @Test
    fun `charging speed is battery points gained per hour and needs at least two sessions that reported it`() {
        val two = ChargingLab.analyze(List(3) { session(PowerSource.AC, 5f, minutes = 30, start = 40, end = 60) })
        assertEquals(40.0, two.chargers.single().averagePercentPerHour!!, 0.001) // 20 points in 30 minutes

        val noLevels = ChargingLab.analyze(List(3) { session(PowerSource.AC, 5f).copy(startPercent = null, lastPercent = null) })
        assertNull(noLevels.chargers.single().averagePercentPerHour)
    }

    @Test
    fun `temperature rise is never negative`() {
        assertEquals(0f, session(PowerSource.AC, -2f).riseC, 0f)
    }

    @Test
    fun `the simulated demo sessions are all tagged demo and produce a complete report`() {
        val sessions = SimulatedCharging.sessions(1_700_000_000_000L)
        assertTrue(sessions.all { it.source == ReadingSource.DEMO })
        assertTrue(sessions.all { it.endMillis != null })

        val report = ChargingLab.analyze(sessions)
        assertEquals(ChargingVerdict.CLEAR_DIFFERENCE, report.verdict)
        assertEquals(PowerSource.USB, report.coolest)
        assertEquals(PowerSource.WIRELESS, report.hottest)
        assertEquals(sessions.size, report.countedSessions)
    }
}
