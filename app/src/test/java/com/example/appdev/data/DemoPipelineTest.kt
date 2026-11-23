package com.example.appdev.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.appdev.data.battery.BatterySnapshotSource
import com.example.appdev.data.cooling.CoolingSessionManager
import com.example.appdev.data.demo.DemoAwareBatterySource
import com.example.appdev.data.demo.DemoController
import com.example.appdev.data.demo.DemoModeManager
import com.example.appdev.data.settings.SettingsRepository
import com.example.appdev.domain.FakeCoolingRepository
import com.example.appdev.domain.FakeIncidentRepository
import com.example.appdev.domain.cooling.CoolingSessionTracker
import com.example.appdev.domain.demo.DemoScenario
import com.example.appdev.domain.incident.IncidentTracker
import com.example.appdev.domain.model.BatterySnapshot
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.model.UserSettings
import com.example.appdev.domain.snapshot
import com.example.appdev.domain.thermal.SystemThermalState
import com.example.appdev.domain.thermal.ThermalAnalysis
import com.example.appdev.platform.AlertPresenter
import com.example.appdev.platform.MonitoringCoordinator
import com.example.appdev.platform.MonitoringRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Called from the coordinator's thread and read from the test thread, so everything here is thread-safe. */
class FakePresenter : AlertPresenter {
    val alerts = java.util.concurrent.CopyOnWriteArrayList<BatterySnapshot>()
    val preAlertMinutes = java.util.concurrent.CopyOnWriteArrayList<Int>()
    val preAlertSources = java.util.concurrent.CopyOnWriteArrayList<ReadingSource>()

    /** Everything that happened, in order: "pre", "pre-cancel", "alert", "alert-cancel". */
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val cancelCount = java.util.concurrent.atomic.AtomicInteger()
    private val vibrationCount = java.util.concurrent.atomic.AtomicInteger()
    private val updateCount = java.util.concurrent.atomic.AtomicInteger()

    val cancels: Int get() = cancelCount.get()
    val vibrations: Int get() = vibrationCount.get()
    val monitoringUpdates: Int get() = updateCount.get()

    override fun showOverheatAlert(snapshot: BatterySnapshot, settings: UserSettings) { alerts += snapshot; events += "alert" }
    override fun cancelOverheatAlert() { cancelCount.incrementAndGet(); events += "alert-cancel" }
    override fun showPreAlert(snapshot: BatterySnapshot, settings: UserSettings, minutes: Int) {
        preAlertMinutes += minutes
        preAlertSources += snapshot.source
        events += "pre"
    }
    override fun cancelPreAlert() { events += "pre-cancel" }
    override fun vibrate() { vibrationCount.incrementAndGet() }
    override fun updateMonitoring(snapshot: BatterySnapshot?, settings: UserSettings, overheating: Boolean) { updateCount.incrementAndGet() }
}

/**
 * Exercises Demo Mode end to end against the real pipeline classes (repository, coordinator,
 * trackers, runner), with only storage and notifications faked.
 */
class DemoPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: SettingsRepository
    private val incidents = FakeIncidentRepository()
    private val cooling = FakeCoolingRepository()
    private val speedTests = com.example.appdev.domain.FakeSpeedTestRepository()
    private val chargeSessions = com.example.appdev.domain.FakeChargeSessionRepository()
    private val presenter = FakePresenter()
    private val realReadings = MutableSharedFlow<BatterySnapshot>(extraBufferCapacity = 16)
    private var serviceRunning = false

    private lateinit var demoController: DemoController
    private lateinit var thermalRepository: ThermalRepository
    private lateinit var demoMode: DemoModeManager

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "demo.preferences_pb") }
        settings = SettingsRepository(dataStore)

        // One simulated second per 10 ms tick, so a full heating cycle takes a couple of seconds.
        // Virtual time: each reading is one simulated second after the last, so the forecast (which needs a
        // minute of readings) works even though the test only runs for a few seconds of real time.
        val virtualNow = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        demoController = DemoController(settings, clock = { virtualNow.addAndGet(1_000) }, tickMillis = 10, secondsPerTick = 1f)
        val real = BatterySnapshotSource { realReadings }
        thermalRepository = ThermalRepository(
            source = DemoAwareBatterySource(real, demoController),
            settingsRepository = settings,
            scope = scope,
            sharingStarted = SharingStarted.Eagerly,
        )

        val deviceTracker = IncidentTracker(incidents, ReadingSource.DEVICE)
        val demoTracker = IncidentTracker(incidents, ReadingSource.DEMO)
        val demoCooling = CoolingSessionManager(
            ReadingSource.DEMO, CoolingSessionTracker(cooling, ReadingSource.DEMO), cooling, thermalRepository, scope,
        )
        val coordinator = MonitoringCoordinator(
            snapshots = thermalRepository.latestSnapshot,
            settings = settings.settings,
            trackers = mapOf(ReadingSource.DEVICE to deviceTracker, ReadingSource.DEMO to demoTracker),
            presenter = presenter,
            isServiceRunning = { serviceRunning },
            forecast = { snap, s ->
                ThermalAnalysis.analyze(snap, thermalRepository.history.value, s.thresholdC, SystemThermalState.Unknown).prediction
            },
        )
        val runner = MonitoringRunner(scope) { coordinator }
        demoMode = DemoModeManager(demoController, incidents, cooling, demoTracker, demoCooling, runner, scope, speedTests, chargeSessions)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(20) }
    }

    @Test
    fun `demo controller emits simulated readings tagged as demo that follow the scenario`() = runBlocking {
        demoController.update { it.copy(scenario = DemoScenario.HEAT_UP, manualTempC = 34f) }
        val readings = withTimeout(10_000) { demoController.snapshots().take(5).toList() }
        assertTrue(readings.all { it.source == ReadingSource.DEMO })
        val temps = readings.map { it.temperatureC!! }
        assertEquals(temps.sorted(), temps) // warming
        assertTrue(temps.last() > temps.first())
    }

    @Test
    fun `a full demo cycle records a demo incident and alerts without touching real data`() = runBlocking {
        demoController.update { it.copy(scenario = DemoScenario.FULL_CYCLE, manualTempC = 34f) }
        demoMode.setEnabled(true)

        awaitUntil { presenter.alerts.isNotEmpty() }
        awaitUntil { incidents.allIncidents.any { !it.isActive } } // resolved after cooling
        // The coordinator stores the resolution first and then clears the notification, so wait for that too.
        awaitUntil { presenter.cancels >= 1 }

        assertEquals(1, presenter.alerts.size)
        assertEquals(ReadingSource.DEMO, presenter.alerts.single().source)
        assertTrue(presenter.cancels >= 1)
        assertTrue(presenter.vibrations >= 1)

        val stored = incidents.allIncidents
        assertEquals(1, stored.size)
        assertEquals(ReadingSource.DEMO, stored.single().source)
        assertEquals(1, incidents.observeIncidents(ReadingSource.DEMO).first().size)
        assertTrue(incidents.observeIncidents(ReadingSource.DEVICE).first().isEmpty())
        assertTrue(incidents.getActive(ReadingSource.DEVICE) == null)
        assertTrue(stored.single().peakTempC > UserSettings.DEFAULT_THRESHOLD_C)
    }

    @Test
    fun `the heating pre-alert comes once before the alert in a demo cycle`() = runBlocking {
        demoController.update { it.copy(scenario = DemoScenario.FULL_CYCLE, manualTempC = 28f) }
        demoMode.setEnabled(true)

        awaitUntil { presenter.alerts.isNotEmpty() }
        awaitUntil { incidents.allIncidents.any { !it.isActive } } // let the whole cycle finish

        val events = presenter.events.toList()
        assertEquals("exactly one early warning for the one approach: $events", 1, events.count { it == "pre" })
        assertTrue("early warning first: $events", events.indexOf("pre") < events.indexOf("alert"))
        // The warning is withdrawn once the real alert takes over.
        assertTrue(events.indexOf("pre-cancel") > events.indexOf("pre"))

        assertEquals(ReadingSource.DEMO, presenter.preAlertSources.single())
        val minutes = presenter.preAlertMinutes.single()
        assertTrue("minutes was $minutes", minutes in 1..5)
    }

    @Test
    fun `no early warning when pre-alerts or notifications are turned off`() = runBlocking {
        settings.setPreAlertsEnabled(false)
        demoController.update { it.copy(scenario = DemoScenario.FULL_CYCLE, manualTempC = 28f) }
        demoMode.setEnabled(true)
        awaitUntil { presenter.alerts.isNotEmpty() } // the real alert still works
        assertTrue(presenter.preAlertMinutes.isEmpty())
        assertFalse("pre" in presenter.events)
    }

    @Test
    fun `leaving demo mode deletes demo data and leaves real data alone`() = runBlocking {
        // A real incident already in history.
        val realTracker = IncidentTracker(incidents, ReadingSource.DEVICE)
        realTracker.onReading(snapshot(41f, timestampMillis = 1_000), UserSettings())
        assertEquals(1, incidents.allIncidents.size)

        demoController.update { it.copy(scenario = DemoScenario.HEAT_UP, manualTempC = 38f) }
        demoMode.setEnabled(true)
        awaitUntil { incidents.allIncidents.any { it.source == ReadingSource.DEMO } }
        assertEquals(ReadingSource.DEMO, demoController.activeSource.value)

        demoMode.setEnabled(false)
        awaitUntil { incidents.allIncidents.none { it.source == ReadingSource.DEMO } }

        assertEquals(ReadingSource.DEVICE, demoController.activeSource.value)
        assertFalse(demoController.enabled.value)
        assertEquals(1, incidents.allIncidents.size)
        assertEquals(ReadingSource.DEVICE, incidents.allIncidents.single().source)
        assertEquals(1_000L, incidents.allIncidents.single().startMillis)
    }

    @Test
    fun `speed tests follow the same demo separation as everything else`() = runBlocking {
        fun result(source: ReadingSource, t: Long) = com.example.appdev.domain.perf.SpeedTestResult(
            source = source, timestampMillis = t, temperatureC = 33f, score = 1000.0,
            chargeStatus = com.example.appdev.domain.model.ChargeStatus.DISCHARGING, thermalStatus = null,
        )
        speedTests.add(result(ReadingSource.DEVICE, 1))
        speedTests.add(result(ReadingSource.DEMO, 2))

        demoMode.setEnabled(true)
        awaitUntil { speedTests.all.none { it.source == ReadingSource.DEMO } } // stale demo results cleared on entry
        speedTests.add(result(ReadingSource.DEMO, 3))

        demoMode.setEnabled(false)
        awaitUntil { speedTests.all.none { it.source == ReadingSource.DEMO } }
        assertEquals(listOf(ReadingSource.DEVICE), speedTests.all.map { it.source })
    }

    @Test
    fun `demo mode seeds simulated charge sessions and removes them on exit without touching real ones`() = runBlocking {
        val realSession = com.example.appdev.domain.charging.SimulatedCharging.sessions(0L).first().copy(source = ReadingSource.DEVICE)
        chargeSessions.start(realSession)

        demoMode.setEnabled(true)
        awaitUntil { chargeSessions.all.any { it.source == ReadingSource.DEMO } }
        assertTrue(chargeSessions.all.filter { it.source == ReadingSource.DEMO }.size > 3)

        demoMode.setEnabled(false)
        awaitUntil { chargeSessions.all.none { it.source == ReadingSource.DEMO } }
        assertEquals(1, chargeSessions.all.size)
        assertEquals(ReadingSource.DEVICE, chargeSessions.all.single().source)
    }

    @Test
    fun `entering or leaving demo mode starts the graph and statistics from a clean slate`() = runBlocking {
        realReadings.emit(snapshot(33f, timestampMillis = 1_000))
        realReadings.emit(snapshot(34f, timestampMillis = 2_000))
        awaitUntil { thermalRepository.history.value.size == 2 }
        assertEquals(2, thermalRepository.sessionStats.value.sampleCount)

        demoController.update { it.copy(scenario = DemoScenario.MANUAL, manualTempC = 36f) }
        demoMode.setEnabled(true)
        awaitUntil { thermalRepository.latestSnapshot.value?.source == ReadingSource.DEMO }

        // No real readings remain in the demo history.
        awaitUntil { thermalRepository.history.value.isNotEmpty() }
        assertTrue(thermalRepository.history.value.all { it.timestampMillis > 100_000 })
        assertTrue(thermalRepository.sessionStats.value.minC!! >= 25f)
    }

    @Test
    fun `real readings keep flowing through the same coordinator when demo is off`() = runBlocking {
        val realTracker = IncidentTracker(incidents, ReadingSource.DEVICE)
        val coordinator = MonitoringCoordinator(
            snapshots = thermalRepository.latestSnapshot,
            settings = settings.settings,
            trackers = mapOf(ReadingSource.DEVICE to realTracker),
            presenter = presenter,
            isServiceRunning = { true },
        )
        coordinator.handle(snapshot(41f, timestampMillis = 5_000), UserSettings())
        assertEquals(1, presenter.alerts.size)
        assertEquals(ReadingSource.DEVICE, presenter.alerts.single().source)
        assertEquals(1, presenter.monitoringUpdates) // service running -> persistent notification refreshed
        assertEquals(ReadingSource.DEVICE, incidents.allIncidents.single().source)
    }

    @Test
    fun `the persistent notification is not touched when the service is not running`() = runBlocking {
        val coordinator = MonitoringCoordinator(
            snapshots = thermalRepository.latestSnapshot,
            settings = settings.settings,
            trackers = mapOf(ReadingSource.DEVICE to IncidentTracker(incidents, ReadingSource.DEVICE)),
            presenter = presenter,
            isServiceRunning = { false },
        )
        coordinator.handle(snapshot(36f, timestampMillis = 1_000), UserSettings())
        assertEquals(0, presenter.monitoringUpdates)
    }

    private fun coordinatorWithForecast(
        thresholds: MutableList<Float>,
        prediction: () -> com.example.appdev.domain.thermal.Prediction,
    ) = MonitoringCoordinator(
        snapshots = thermalRepository.latestSnapshot,
        settings = settings.settings,
        trackers = mapOf(ReadingSource.DEVICE to IncidentTracker(incidents, ReadingSource.DEVICE)),
        presenter = presenter,
        isServiceRunning = { false },
        forecast = { _, s -> thresholds += s.thresholdC; prediction() },
    )

    private fun heatingIn(minutes: Float) = com.example.appdev.domain.thermal.Prediction.Heating(
        slopeCPerMin = 0.5f, minutesToThreshold = minutes,
        confidence = com.example.appdev.domain.thermal.Confidence.HIGH,
    )

    @Test
    fun `an approaching limit gives one pre-alert and the cooldown stops repeats`() = runBlocking {
        val seen = mutableListOf<Float>()
        var minutes = 9f
        val c = coordinatorWithForecast(seen) { heatingIn(minutes) }
        val s = UserSettings()

        c.handle(snapshot(34f, timestampMillis = 0), s) // 9 min away: too early
        assertTrue(presenter.preAlertMinutes.isEmpty())

        minutes = 4f
        c.handle(snapshot(36f, timestampMillis = 60_000), s)
        assertEquals(listOf(4), presenter.preAlertMinutes.toList())

        for (i in 2..6) { minutes -= 0.5f; c.handle(snapshot(37f, timestampMillis = i * 60_000L), s) }
        assertEquals("still only one warning", 1, presenter.preAlertMinutes.size)
    }

    @Test
    fun `the forecast is judged against the active profile's threshold`() = runBlocking {
        val seen = mutableListOf<Float>()
        val c = coordinatorWithForecast(seen) { heatingIn(3f) }
        c.handle(snapshot(36f, timestampMillis = 0), UserSettings(thresholdC = 43f, activeProfile = com.example.appdev.domain.profile.ProfileType.GAMING))
        assertEquals(listOf(43f), seen)
    }

    @Test
    fun `turning off notifications or pre-alerts silences the early warning`() = runBlocking {
        val c = coordinatorWithForecast(mutableListOf()) { heatingIn(2f) }
        c.handle(snapshot(37f, timestampMillis = 0), UserSettings(notificationsEnabled = false))
        c.handle(snapshot(37f, timestampMillis = 60_000), UserSettings(preAlertsEnabled = false))
        assertTrue(presenter.preAlertMinutes.isEmpty())
        // Turning it back on lets the next approach through.
        c.handle(snapshot(37f, timestampMillis = 120_000), UserSettings())
        assertEquals(1, presenter.preAlertMinutes.size)
    }

    @Test
    fun `a warning on screen is withdrawn when the user switches pre-alerts off`() = runBlocking {
        val c = coordinatorWithForecast(mutableListOf()) { heatingIn(2f) }
        c.handle(snapshot(37f, timestampMillis = 0), UserSettings())
        assertEquals(1, presenter.preAlertMinutes.size)
        c.handle(snapshot(37f, timestampMillis = 5_000), UserSettings(preAlertsEnabled = false))
        assertTrue(presenter.events.last() == "pre-cancel")
    }

    @Test
    fun `vibration respects the user setting`() = runBlocking {
        val coordinator = MonitoringCoordinator(
            snapshots = thermalRepository.latestSnapshot,
            settings = settings.settings,
            trackers = mapOf(ReadingSource.DEVICE to IncidentTracker(incidents, ReadingSource.DEVICE)),
            presenter = presenter,
            isServiceRunning = { false },
        )
        coordinator.handle(snapshot(41f, timestampMillis = 1_000), UserSettings(vibrationEnabled = false))
        assertEquals(1, presenter.alerts.size)
        assertEquals(0, presenter.vibrations)
    }
}
