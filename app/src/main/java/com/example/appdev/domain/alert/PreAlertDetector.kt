package com.example.appdev.domain.alert

import com.example.appdev.domain.thermal.Confidence
import com.example.appdev.domain.thermal.Prediction
import kotlin.math.ceil

sealed interface PreAlertDecision {
    data object None : PreAlertDecision

    /** Warn now: the phone is projected to reach the threshold in about [minutes] minutes. */
    data class Fire(val minutes: Int) : PreAlertDecision

    /** The warning on screen is out of date (the forecast eased, or the real alert took over). */
    data object Withdraw : PreAlertDecision
}

/**
 * Decides when to warn that the phone is *about to* overheat, before the real alert. It uses the
 * existing heating forecast, so it follows the active profile's threshold automatically.
 *
 * To avoid nagging it warns once per approach: after a warning it stays quiet until the forecast has
 * clearly eased (so a forecast flickering around the limit cannot repeat it) and a minimum cooldown has
 * passed. It also ignores forecasts it is not confident in. Not thread-safe; use from one coroutine.
 */
class PreAlertDetector(
    private val leadMinutes: Float = DEFAULT_LEAD_MINUTES,
    private val rearmMarginMinutes: Float = DEFAULT_REARM_MARGIN_MINUTES,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
) {
    private var armed = true
    private var showing = false
    private var lastFiredMillis: Long? = null

    /**
     * @param prediction the current forecast.
     * @param nowMillis the time of this reading (the reading's own timestamp, so tests are deterministic).
     * @param inIncident true while a real overheating alert is active; the pre-alert then has no purpose.
     */
    fun onReading(prediction: Prediction, nowMillis: Long, inIncident: Boolean): PreAlertDecision {
        val heating = prediction as? Prediction.Heating

        if (inIncident) {
            armed = false // re-armed below once the forecast is clear again
            return withdrawIfShowing()
        }

        val soon = heating != null &&
            heating.minutesToThreshold <= leadMinutes &&
            heating.confidence != Confidence.LOW

        // Re-arm only when the forecast is clearly out of range, not merely wobbling around the limit.
        val clearlyEasy = heating == null || heating.minutesToThreshold > leadMinutes + rearmMarginMinutes
        if (clearlyEasy) armed = true

        if (soon) {
            val cooledDown = lastFiredMillis?.let { nowMillis - it >= cooldownMillis } ?: true
            if (armed && cooledDown && !showing) {
                armed = false
                showing = true
                lastFiredMillis = nowMillis
                return PreAlertDecision.Fire(minutesToShow(heating.minutesToThreshold))
            }
            return PreAlertDecision.None
        }
        return withdrawIfShowing()
    }

    private fun withdrawIfShowing(): PreAlertDecision {
        if (!showing) return PreAlertDecision.None
        showing = false
        return PreAlertDecision.Withdraw
    }

    /** Rounds up (it is better to say 4 than 3 when unsure) and never says 0. */
    private fun minutesToShow(minutes: Float): Int = ceil(minutes.toDouble()).toInt().coerceAtLeast(1)

    companion object {
        /** Warn when the threshold is projected to be reached within this many minutes. */
        const val DEFAULT_LEAD_MINUTES = 5f

        /** The forecast must be this much further out than the lead time before another warning is allowed. */
        const val DEFAULT_REARM_MARGIN_MINUTES = 3f

        const val DEFAULT_COOLDOWN_MILLIS = 10 * 60_000L
    }
}
