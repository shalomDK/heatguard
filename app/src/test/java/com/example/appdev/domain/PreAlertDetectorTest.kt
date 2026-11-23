package com.example.appdev.domain

import com.example.appdev.domain.alert.PreAlertDecision
import com.example.appdev.domain.alert.PreAlertDetector
import com.example.appdev.domain.thermal.Confidence
import com.example.appdev.domain.thermal.Prediction
import org.junit.Assert.assertEquals
import org.junit.Test

class PreAlertDetectorTest {

    private val minute = 60_000L

    private fun heating(minutes: Float, confidence: Confidence = Confidence.HIGH) =
        Prediction.Heating(slopeCPerMin = 0.5f, minutesToThreshold = minutes, confidence = confidence)

    private fun PreAlertDetector.at(min: Long, p: Prediction, incident: Boolean = false) =
        onReading(p, nowMillis = min * minute, inIncident = incident)

    @Test
    fun `warns once when the threshold is about to be reached`() {
        val d = PreAlertDetector()
        assertEquals(PreAlertDecision.None, d.at(0, heating(9f)))
        assertEquals(PreAlertDecision.None, d.at(1, heating(6f)))
        assertEquals(PreAlertDecision.Fire(5), d.at(2, heating(4.5f)))
        // Still heading there: no repeat.
        assertEquals(PreAlertDecision.None, d.at(3, heating(3.5f)))
        assertEquals(PreAlertDecision.None, d.at(4, heating(2.5f)))
    }

    @Test
    fun `minutes are rounded up and never below one`() {
        assertEquals(PreAlertDecision.Fire(4), PreAlertDetector().at(0, heating(3.2f)))
        assertEquals(PreAlertDecision.Fire(1), PreAlertDetector().at(0, heating(0.2f)))
        assertEquals(PreAlertDecision.Fire(5), PreAlertDetector().at(0, heating(5f)))
    }

    @Test
    fun `a forecast it is not confident in never triggers a warning`() {
        val d = PreAlertDetector()
        assertEquals(PreAlertDecision.None, d.at(0, heating(2f, Confidence.LOW)))
        assertEquals(PreAlertDecision.Fire(2), d.at(1, heating(2f, Confidence.MEDIUM)))
    }

    @Test
    fun `nothing happens for other forecasts`() {
        val d = PreAlertDetector()
        listOf(
            Prediction.Unavailable, Prediction.CollectingData(2, 10_000), Prediction.Stable(0f),
            Prediction.Cooling(-0.3f), Prediction.HeatingSlowly(0.1f), Prediction.AlreadyOverThreshold(1f),
        ).forEachIndexed { i, p -> assertEquals(PreAlertDecision.None, d.at(i.toLong(), p)) }
    }

    @Test
    fun `a forecast wobbling around the limit cannot repeat the warning`() {
        val d = PreAlertDetector()
        assertEquals(PreAlertDecision.Fire(5), d.at(0, heating(4.9f)))
        assertEquals(PreAlertDecision.Withdraw, d.at(1, heating(5.4f))) // eased a little: notification withdrawn
        assertEquals(PreAlertDecision.None, d.at(2, heating(4.6f))) // back inside, but not re-armed
        assertEquals(PreAlertDecision.None, d.at(3, heating(5.8f)))
        assertEquals(PreAlertDecision.None, d.at(4, heating(4.2f)))
    }

    @Test
    fun `it re-arms only after the forecast is clearly easy and the cooldown has passed`() {
        val d = PreAlertDetector()
        assertEquals(PreAlertDecision.Fire(4), d.at(0, heating(4f)))
        assertEquals(PreAlertDecision.Withdraw, d.at(1, Prediction.Stable(0f)))

        // Re-armed, but the 10 minute cooldown has not passed yet.
        assertEquals(PreAlertDecision.None, d.at(5, heating(3f)))
        assertEquals(PreAlertDecision.None, d.at(6, Prediction.Stable(0f)))
        // Cooldown over and re-armed: a new approach is warned about.
        assertEquals(PreAlertDecision.None, d.at(11, Prediction.Stable(0f)))
        assertEquals(PreAlertDecision.Fire(3), d.at(12, heating(3f)))
    }

    @Test
    fun `an easing forecast withdraws the warning`() {
        val d = PreAlertDetector()
        assertEquals(PreAlertDecision.Fire(3), d.at(0, heating(3f)))
        assertEquals(PreAlertDecision.Withdraw, d.at(1, Prediction.Cooling(-0.2f)))
        assertEquals(PreAlertDecision.None, d.at(2, Prediction.Cooling(-0.2f))) // only once
    }

    @Test
    fun `during a real alert the pre-alert is withdrawn and stays quiet`() {
        val d = PreAlertDetector()
        assertEquals(PreAlertDecision.Fire(2), d.at(0, heating(2f)))
        assertEquals(PreAlertDecision.Withdraw, d.at(1, Prediction.AlreadyOverThreshold(0.5f), incident = true))
        assertEquals(PreAlertDecision.None, d.at(2, heating(1f), incident = true))
        assertEquals(PreAlertDecision.None, d.at(3, heating(1f), incident = true))
    }

    @Test
    fun `no warning is shown when an incident is already running`() {
        assertEquals(PreAlertDecision.None, PreAlertDetector().at(0, heating(2f), incident = true))
    }

    @Test
    fun `after an incident ends a later approach is warned about again`() {
        val d = PreAlertDetector()
        d.at(0, heating(2f))
        d.at(1, Prediction.AlreadyOverThreshold(1f), incident = true)
        d.at(2, Prediction.Cooling(-0.4f)) // incident over, forecast clearly easy -> re-armed
        assertEquals(PreAlertDecision.Fire(4), d.at(20, heating(4f)))
    }
}
