package com.example.appdev

import android.content.Context
import com.example.appdev.data.ThermalRepository
import com.example.appdev.data.battery.BatteryMonitor
import com.example.appdev.data.ai.AiSettingsRepository
import com.example.appdev.data.ai.AssistantController
import com.example.appdev.data.ai.GroqClient
import com.example.appdev.data.ai.KeystoreSecretStore
import com.example.appdev.data.ai.PhoneContextCollector
import com.example.appdev.data.ai.PhoneMetricsReader
import com.example.appdev.data.ai.SecretStore
import com.example.appdev.data.cooling.CoolingSessionManager
import com.example.appdev.data.cooling.PersonalCoolingProfileProvider
import com.example.appdev.data.db.HeatGuardDatabase
import com.example.appdev.data.db.RoomChargeSessionRepository
import com.example.appdev.data.db.RoomCoolingRepository
import com.example.appdev.domain.charging.ChargeSessionRepository
import com.example.appdev.domain.charging.ChargeSessionTracker
import com.example.appdev.data.db.RoomIncidentRepository
import com.example.appdev.data.db.RoomSpeedTestRepository
import com.example.appdev.data.perf.SpeedTestController
import com.example.appdev.domain.perf.SpeedTestRepository
import com.example.appdev.data.demo.DemoAwareBatterySource
import com.example.appdev.data.demo.DemoAwareSystemSource
import com.example.appdev.data.demo.DemoController
import com.example.appdev.data.demo.DemoModeManager
import com.example.appdev.data.settings.SettingsRepository
import com.example.appdev.data.settings.settingsDataStore
import com.example.appdev.data.thermal.SystemThermalMonitor
import com.example.appdev.domain.cooling.CoolingRepository
import com.example.appdev.domain.cooling.CoolingSessionTracker
import com.example.appdev.domain.incident.IncidentContextBuilder
import com.example.appdev.domain.incident.IncidentRepository
import com.example.appdev.domain.incident.IncidentTracker
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.thermal.ThermalAnalysis
import com.example.appdev.platform.AlertNotifier
import com.example.appdev.platform.ExportManager
import com.example.appdev.platform.MonitoringController
import com.example.appdev.platform.MonitoringCoordinator
import com.example.appdev.platform.MonitoringRunner
import com.example.appdev.platform.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Manual dependency container; one instance per process, owned by [HeatGuardApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = SettingsRepository(appContext.settingsDataStore)

    val batteryMonitor = BatteryMonitor(appContext)

    val demoController = DemoController(settingsRepository)

    /**
     * The single reading pipeline. In Demo Mode both sources are swapped for simulated ones, so
     * everything downstream (alerts, forecast, incidents, coaching) is exercised by the same code.
     */
    val thermalRepository = ThermalRepository(
        source = DemoAwareBatterySource(batteryMonitor, demoController),
        settingsRepository = settingsRepository,
        scope = applicationScope,
        systemSource = DemoAwareSystemSource(SystemThermalMonitor(appContext), demoController),
    )

    private val database: HeatGuardDatabase by lazy { HeatGuardDatabase.create(appContext) }

    val incidentRepository: IncidentRepository by lazy { RoomIncidentRepository(database.incidentDao()) }

    val coolingRepository: CoolingRepository by lazy { RoomCoolingRepository(database.coolingDao()) }

    val speedTestRepository: SpeedTestRepository by lazy { RoomSpeedTestRepository(database.speedTestDao()) }

    val chargeSessionRepository: ChargeSessionRepository by lazy { RoomChargeSessionRepository(database.chargeSessionDao()) }

    private val deviceChargeTracker by lazy { ChargeSessionTracker(chargeSessionRepository, ReadingSource.DEVICE) }

    /** Heat Tax: runs a speed test on request and stores it with the battery temperature. */
    val speedTests by lazy {
        SpeedTestController(
            repository = speedTestRepository,
            scope = applicationScope,
            readSnapshot = { if (demoController.enabled.value) demoController.current.value else batteryMonitor.readNow() },
            isDemo = { demoController.enabled.value },
            thermalStatusName = { thermalRepository.systemState.value.status?.name },
        )
    }

    private fun newCoolingManager(source: ReadingSource) = CoolingSessionManager(
        source = source,
        tracker = CoolingSessionTracker(coolingRepository, source),
        repository = coolingRepository,
        thermalRepository = thermalRepository,
        scope = applicationScope,
    )

    private val deviceCoolingManager by lazy { newCoolingManager(ReadingSource.DEVICE) }
    private val demoCoolingManager by lazy { newCoolingManager(ReadingSource.DEMO) }

    /** The cooling session manager for real or demo data. */
    fun coolingManager(source: ReadingSource): CoolingSessionManager =
        if (source == ReadingSource.DEMO) demoCoolingManager else deviceCoolingManager

    /** The user's cooling profile, derived from stored sessions and incidents for the active data source. */
    val personalCoolingProfile by lazy {
        PersonalCoolingProfileProvider(incidentRepository, coolingRepository, demoController.activeSource)
    }

    // ---- AI assistant (opt-in; the only part of the app that uses the network) ----

    private val secretStore: SecretStore by lazy { KeystoreSecretStore(appContext) }

    private val aiSettings by lazy { AiSettingsRepository(appContext.settingsDataStore) }

    val assistant by lazy {
        val collector = PhoneContextCollector(
            thermalRepository = thermalRepository,
            settingsRepository = settingsRepository,
            batteryMonitor = batteryMonitor,
            demoController = demoController,
            incidents = incidentRepository,
            cooling = coolingRepository,
            profiles = personalCoolingProfile,
            monitoring = monitoringController,
            coolingManagerFor = ::coolingManager,
            metricsReader = metricsReader,
        )
        AssistantController(
            client = GroqClient(),
            secrets = secretStore,
            aiSettings = aiSettings,
            contextProvider = collector::collect,
            scope = applicationScope,
        )
    }

    val exportManager by lazy { ExportManager(appContext, incidentRepository, coolingRepository) }

    val alertNotifier by lazy { AlertNotifier(appContext) }

    val monitoringController = MonitoringController(appContext)

    private val metricsReader by lazy { PhoneMetricsReader(appContext) }

    // Each new incident records the phone's context at its start, for the Heat Autopsy. Demo incidents
    // leave out the real device's metrics because everything in Demo Mode is simulated.
    private val deviceIncidentTracker by lazy {
        IncidentTracker(
            incidentRepository, ReadingSource.DEVICE,
            contextProvider = { snap -> IncidentContextBuilder.build(snap, thermalRepository.history.value, metricsReader.metrics()) },
        )
    }
    private val demoIncidentTracker by lazy {
        IncidentTracker(
            incidentRepository, ReadingSource.DEMO,
            contextProvider = { snap -> IncidentContextBuilder.build(snap, thermalRepository.history.value, null) },
        )
    }

    val monitoringCoordinator by lazy {
        MonitoringCoordinator(
            snapshots = thermalRepository.latestSnapshot,
            settings = settingsRepository.settings,
            trackers = mapOf(
                ReadingSource.DEVICE to deviceIncidentTracker,
                ReadingSource.DEMO to demoIncidentTracker,
            ),
            chargeTrackers = mapOf(ReadingSource.DEVICE to deviceChargeTracker),
            presenter = alertNotifier,
            isServiceRunning = { monitoringController.isRunning.value },
            onProcessed = { snapshot, settings -> WidgetUpdater.onReading(appContext, snapshot, settings) },
            // The same forecast the dashboard shows, computed against the active profile's threshold.
            forecast = { snapshot, settings ->
                ThermalAnalysis.analyze(
                    snapshot, thermalRepository.history.value, settings.thresholdC, thermalRepository.systemState.value,
                ).prediction
            },
        )
    }

    /** Runs the coordinator while the foreground service or Demo Mode needs it. */
    val monitoringRunner by lazy { MonitoringRunner(applicationScope) { monitoringCoordinator } }

    val demoMode by lazy {
        DemoModeManager(
            controller = demoController,
            incidents = incidentRepository,
            cooling = coolingRepository,
            incidentTracker = demoIncidentTracker,
            coolingManager = demoCoolingManager,
            runner = monitoringRunner,
            scope = applicationScope,
            speedTests = speedTestRepository,
            chargeSessions = chargeSessionRepository,
        )
    }

    /** Startup housekeeping: resume an open real cooling session and drop demo rows from an earlier run. */
    fun onAppStart() {
        applicationScope.launch { deviceCoolingManager.resumeIfActive() }
        demoMode.cleanUpStale()
    }

    /**
     * Called when the app comes to the foreground. If the user left monitoring switched on but the
     * service was stopped by the system, start it again (allowed because the app is visible).
     */
    fun restoreMonitoringIfNeeded() {
        applicationScope.launch {
            val wanted = settingsRepository.settings.first().monitoringEnabled
            if (wanted && !monitoringController.isRunning.value) {
                if (!monitoringController.start()) settingsRepository.setMonitoringEnabled(false)
            }
        }
    }
}
