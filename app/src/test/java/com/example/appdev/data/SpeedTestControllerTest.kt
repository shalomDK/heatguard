package com.example.appdev.data

import com.example.appdev.data.perf.SpeedTestController
import com.example.appdev.data.perf.SpeedTestError
import com.example.appdev.domain.FakeSpeedTestRepository
import com.example.appdev.domain.model.BatterySnapshot
import com.example.appdev.domain.model.ChargeStatus
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.perf.SimulatedSpeed
import com.example.appdev.domain.snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpeedTestControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = FakeSpeedTestRepository()
    private val measureCalls = AtomicInteger()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(
        reading: BatterySnapshot? = snapshot(36.5f, chargeStatus = ChargeStatus.CHARGING),
        demo: Boolean = false,
        measure: suspend () -> Double = { measureCalls.incrementAndGet(); 880.0 },
    ) = SpeedTestController(
        repository = repo, scope = scope,
        readSnapshot = { reading?.copy(source = if (demo) ReadingSource.DEMO else ReadingSource.DEVICE) },
        isDemo = { demo }, thermalStatusName = { "LIGHT" },
        measure = measure, clock = { 123_456L }, simulatedTestMillis = 10,
    )

    private fun SpeedTestController.awaitDone() = runBlocking {
        withTimeout(10_000) { while (state.value.running) delay(10) }
    }

    @Test
    fun `a real test stores the score with the temperature from before it ran`() {
        val c = controller()
        c.run()
        c.awaitDone()

        val stored = repo.all.single()
        assertEquals(ReadingSource.DEVICE, stored.source)
        assertEquals(36.5f, stored.temperatureC, 0f)
        assertEquals(880.0, stored.score, 0.0)
        assertEquals(ChargeStatus.CHARGING, stored.chargeStatus)
        assertEquals("LIGHT", stored.thermalStatus)
        assertEquals(123_456L, stored.timestampMillis)
        assertEquals(1, measureCalls.get())
        assertNull(c.state.value.error)
    }

    @Test
    fun `demo mode records the labelled simulated speed and never runs the real workload`() {
        val c = controller(reading = snapshot(44f), demo = true)
        c.run()
        c.awaitDone()

        val stored = repo.all.single()
        assertEquals(ReadingSource.DEMO, stored.source)
        assertEquals(SimulatedSpeed.scoreAt(44f), stored.score, 0.0)
        assertEquals(0, measureCalls.get())
    }

    @Test
    fun `without a temperature nothing is stored and the reason is reported`() {
        val c = controller(reading = null)
        c.run()
        c.awaitDone()
        assertTrue(repo.all.isEmpty())
        assertEquals(SpeedTestError.NO_TEMPERATURE, c.state.value.error)
        assertEquals(0, measureCalls.get())

        c.dismissError()
        assertNull(c.state.value.error)
    }

    @Test
    fun `a failing or useless measurement is reported instead of storing nonsense`() {
        val crashing = controller(measure = { error("boom") })
        crashing.run(); crashing.awaitDone()
        assertEquals(SpeedTestError.FAILED, crashing.state.value.error)

        val zero = controller(measure = { 0.0 })
        zero.run(); zero.awaitDone()
        assertEquals(SpeedTestError.FAILED, zero.state.value.error)
        assertTrue(repo.all.isEmpty())
    }

    @Test
    fun `only one test runs at a time`() {
        val gate = CompletableDeferred<Double>()
        val c = controller(measure = { measureCalls.incrementAndGet(); gate.await() })
        c.run()
        c.run() // ignored while the first is still running
        runBlocking { delay(100) }
        assertTrue(c.state.value.running)
        assertEquals(1, measureCalls.get())

        gate.complete(900.0)
        c.awaitDone()
        assertEquals(1, repo.all.size)
        assertFalse(c.state.value.running)
    }
}
