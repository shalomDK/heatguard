package com.example.appdev.domain.autopsy

import com.example.appdev.domain.ai.RelativeTime
import com.example.appdev.domain.incident.Incident
import com.example.appdev.domain.model.Temperatures
import java.util.Locale

/**
 * The question sent when the user taps "Explain with AI" on an incident. The facts are written into the
 * message itself, so the user can see exactly what is being asked, and so the answer is about *this*
 * incident rather than the phone in general.
 */
object AutopsyPrompt {

    fun build(incident: Incident, report: AutopsyReport, nowMillis: Long): String {
        val locale = Locale.US
        fun temp(v: Float) = String.format(locale, "%.1f °C (%.1f °F)", v, Temperatures.celsiusToFahrenheit(v))
        val ctx = incident.context

        return buildString {
            appendLine(
                "Explain this overheating incident from my phone and what would have helped. Be honest about what " +
                    "the data can and cannot show, treat these as contributing factors rather than proven causes, and " +
                    "do not guess which app was responsible.",
            )
            appendLine()
            appendLine("Incident facts:")
            appendLine("- Began ${RelativeTime.ago(incident.startMillis, nowMillis)}; started at ${temp(incident.initialTempC)} against my threshold of ${temp(incident.thresholdC)}.")
            appendLine("- Peaked at ${temp(incident.peakTempC)} after ${RelativeTime.span(report.timeToPeakMillis)} (${String.format(locale, "%.1f", report.peakOverThresholdC)} °C over the threshold).")
            appendLine(
                "- " + (if (incident.isActive) "Still ongoing." else "Lasted ${RelativeTime.span(incident.durationMillis)}.") +
                    (incident.startBatteryPercent?.let { " Battery was $it% at the start." } ?: ""),
            )
            appendLine("- It began while ${if (incident.startedWhileCharging) "charging via ${incident.startPowerSource.name.lowercase()}" else "running on battery"}.")
            ctx?.heatingRateCPerMin?.let { rate ->
                val usual = report.factors.firstOrNull { it.type == FactorType.FAST_HEATING }?.baseline
                appendLine(
                    "- Heating rate just before: ${String.format(locale, "%.2f", rate)} °C/min" +
                        (usual?.let { String.format(locale, " (my usual for incidents: %.2f °C/min)", it) } ?: "") + ".",
                )
            }
            ctx?.tempFiveMinutesBeforeC?.let { appendLine("- About five minutes before it began the battery was ${temp(it)}.") }
            ctx?.brightnessPercent?.let { appendLine("- Screen brightness was $it%.") }
            ctx?.freeRamPercent?.let { appendLine("- Free memory was $it%" + if (ctx.lowMemory == true) " and Android reported low memory." else ".") }
            ctx?.powerSaveOn?.let { appendLine("- Power saver was ${if (it) "on" else "off"}.") }
            report.recovery?.let {
                appendLine("- Recovery was ${it.verdict.name.lowercase()} than usual (${RelativeTime.span(it.durationMillis)} versus a typical ${RelativeTime.span(it.typicalMillis)}).")
            }
            if (report.missing.isNotEmpty()) {
                appendLine("- Not recorded for this incident: ${report.missing.joinToString { it.name.lowercase().replace('_', ' ') }}.")
            }
        }.trim()
    }
}
