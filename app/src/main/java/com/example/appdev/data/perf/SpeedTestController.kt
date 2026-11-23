package com.example.appdev.data.perf

import com.example.appdev.domain.model.BatterySnapshot
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.perf.CpuWorkload
import com.example.appdev.domain.perf.SimulatedSpeed
import com.example.appdev.domain.perf.SpeedTestRepository
import com.example.appdev.domain.perf.SpeedTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SpeedTestError { NO_TEMPERATURE, FAILED }

data class SpeedTestState(val running: Boolean = false, val error: SpeedTestError? = null)

/**
 * Runs one speed test at a time, on request, and stores the result with the battery temperature at the
 * moment it started. In Demo Mode it records a result from the plainly labelled simulated speed model
 * instead, tagged as demo data. It only ever runs when the user taps the button.
 */
class SpeedTestController(
    private val repository: SpeedTestRepository,
    private val scope: CoroutineScope,
    /** A fresh battery reading (real or simulated), or null if none is available. */
    private val readSnapshot: () -> BatterySnapshot?,
    private val isDemo: () -> Boolean,
    private val thermalStatusName: () -> String?,
    /** Measures real speed. Replaced in tests. */
    private val measure: suspend () -> Double = { withContext(Dispatchers.Default) { CpuWorkload.medianScore() } },
    private val clock: () -> Long = System::currentTimeMillis,
    private val simulatedTestMillis: Long = 900,
) {
    private val _state = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = _state.asStateFlow()

    @Synchronized
    fun run() {
        if (_state.value.running) return
        _state.value = SpeedTestState(running = true)
        scope.launch {
            var error: SpeedTestError? = null
            try {
                val demo = isDemo()
                val snapshot = readSnapshot()
                val temperature = snapshot?.temperatureC?.takeIf { it.isFinite() }
                if (snapshot == null || temperature == null) {
                    error = SpeedTestError.NO_TEMPERATURE
                } else {
                    val score = if (demo) {
                        delay(simulatedTestMillis) // so a simulated test feels like a test
                        SimulatedSpeed.scoreAt(temperature)
                    } else {
                        measure()
                    }
                    if (score > 0) {
                        repository.add(
                            SpeedTestResult(
                                source = if (demo) ReadingSource.DEMO else ReadingSource.DEVICE,
                                timestampMillis = clock(),
                                temperatureC = temperature, // the temperature before the test, which is what is being compared
                                score = score,
                                chargeStatus = snapshot.chargeStatus,
                                thermalStatus = thermalStatusName(),
                            ),
                        )
                    } else {
                        error = SpeedTestError.FAILED
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = SpeedTestError.FAILED
            }
            _state.value = SpeedTestState(running = false, error = error)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
