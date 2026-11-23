package com.example.appdev.platform

import com.example.appdev.domain.alert.PreAlertDecision
import com.example.appdev.domain.charging.ChargeSessionTracker
import kotlinx.coroutines.CancellationException
import com.example.appdev.domain.alert.PreAlertDetector
import com.example.appdev.domain.incident.IncidentTracker
import com.example.appdev.domain.incident.TrackerEvent
import com.example.appdev.domain.model.BatterySnapshot
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.model.UserSettings
import com.example.appdev.domain.thermal.Prediction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Feeds every reading through the incident tracker for its source and turns the resulting events
 * into alerts. Real and demo readings take exactly the same path; each has its own tracker, so
 * demo incidents are stored as demo data and never touch real history.
 *
 * It also issues the early "heating fast" warning: on every reading it asks the heating forecast how
 * long until the threshold, and a [PreAlertDetector] (one per source, so demo and real never share
 * cooldowns) decides whether to warn, with a cooldown so it cannot nag.
 */
class MonitoringCoordinator(
    private val snapshots: Flow<BatterySnapshot?>,
    private val settings: Flow<UserSettings>,
    private val trackers: Map<ReadingSource, IncidentTracker>,
    private val presenter: AlertPresenter,
    /** The persistent notification is only updated while the foreground service owns it. */
    private val isServiceRunning: () -> Boolean,
    /** Called after each reading, e.g. to refresh the home-screen widget. */
    private val onProcessed: (BatterySnapshot, UserSettings) -> Unit = { _, _ -> },
    /** The heating forecast for a reading, against the settings' threshold. Defaults to "no forecast". */
    private val forecast: (BatterySnapshot, UserSettings) -> Prediction = { _, _ -> Prediction.Unavailable },
    /** Records charge sessions for the Charging Lab. A failure here must never disturb heat alerts. */
    private val chargeTrackers: Map<ReadingSource, ChargeSessionTracker> = emptyMap(),
) {
    private val overheating = mutableMapOf<ReadingSource, Boolean>()
    private val preAlerts = mutableMapOf<ReadingSource, PreAlertDetector>()

    /** Suspends until cancelled. */
    suspend fun run() {
        snapshots.filterNotNull().collect { snapshot -> handle(snapshot, settings.first()) }
    }

    internal suspend fun handle(snapshot: BatterySnapshot, settings: UserSettings) {
        val source = snapshot.source
        when (trackers[source]?.onReading(snapshot, settings)) {
            is TrackerEvent.Triggered -> {
                overheating[source] = true
                presenter.showOverheatAlert(snapshot, settings)
                if (settings.vibrationEnabled) presenter.vibrate()
            }
            is TrackerEvent.Resolved -> {
                overheating[source] = false
                presenter.cancelOverheatAlert()
            }
            null -> Unit
        }
        handlePreAlert(snapshot, settings, source)
        try {
            chargeTrackers[source]?.onReading(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Charging history is a bonus; never let it break monitoring.
        }
        if (isServiceRunning()) presenter.updateMonitoring(snapshot, settings, overheating[source] == true)
        onProcessed(snapshot, settings)
    }

    private fun handlePreAlert(snapshot: BatterySnapshot, settings: UserSettings, source: ReadingSource) {
        val detector = preAlerts.getOrPut(source) { PreAlertDetector() }
        // When the user has turned pre-alerts (or notifications) off, the detector sees "no forecast":
        // it stays silent and withdraws a warning that is already showing.
        val enabled = settings.preAlertsEnabled && settings.notificationsEnabled && snapshot.temperatureC != null
        val prediction = if (enabled) forecast(snapshot, settings) else Prediction.Unavailable

        when (val decision = detector.onReading(prediction, snapshot.timestampMillis, overheating[source] == true)) {
            is PreAlertDecision.Fire -> presenter.showPreAlert(snapshot, settings, decision.minutes)
            PreAlertDecision.Withdraw -> presenter.cancelPreAlert()
            PreAlertDecision.None -> Unit
        }
    }
}
