package com.example.appdev.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.appdev.R
import com.example.appdev.domain.autopsy.AutopsyFactor
import com.example.appdev.domain.autopsy.AutopsyReport
import com.example.appdev.domain.autopsy.FactorType
import com.example.appdev.domain.autopsy.HeatAutopsy
import com.example.appdev.domain.autopsy.MissingSignal
import com.example.appdev.domain.autopsy.RecoveryVerdict
import com.example.appdev.domain.autopsy.Strength
import com.example.appdev.domain.model.PowerSource
import com.example.appdev.domain.model.TemperatureUnit
import com.example.appdev.domain.model.Temperatures
import com.example.appdev.ui.components.Durations
import com.example.appdev.ui.components.HeatCard
import com.example.appdev.ui.components.labelRes
import com.example.appdev.ui.theme.HeatColors

/**
 * Heat Autopsy: what the phone looked like when this incident began, set against the user's own history.
 * It lists plausible contributors with their evidence, says what could not be checked, and is explicit
 * that this is correlation, not proof.
 */
@Composable
fun AutopsyCard(report: AutopsyReport, unit: TemperatureUnit, onExplain: () -> Unit) {
    HeatCard(Modifier.fillMaxWidth(), spacing = 10.dp) {
        Text(
            stringResource(R.string.autopsy_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = HeatColors.TextSecondary,
        )

        Text(
            stringResource(
                R.string.autopsy_summary,
                Temperatures.formatDelta(report.riseC, unit),
                Durations.format(report.timeToPeakMillis),
                Temperatures.formatDelta(report.peakOverThresholdC.coerceAtLeast(0f), unit),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = HeatColors.TextSecondary,
        )

        HorizontalDivider(color = HeatColors.OutlineSubtle)

        if (report.factors.isEmpty()) {
            Text(
                stringResource(if (report.contextRecorded) R.string.autopsy_none_found else R.string.autopsy_nothing_checkable),
                style = MaterialTheme.typography.titleMedium,
                color = HeatColors.TextPrimary,
            )
        } else {
            Text(stringResource(R.string.autopsy_contributors), style = MaterialTheme.typography.titleMedium, color = HeatColors.TextPrimary)
            report.factors.forEach { FactorRow(it, unit) }
        }

        report.recovery?.let {
            val verdict = when (it.verdict) {
                RecoveryVerdict.SLOWER -> R.string.autopsy_recovery_slower
                RecoveryVerdict.FASTER -> R.string.autopsy_recovery_faster
                RecoveryVerdict.TYPICAL -> R.string.autopsy_recovery_typical
            }
            Text(
                stringResource(verdict, Durations.format(it.durationMillis), Durations.format(it.typicalMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = HeatColors.TextSecondary,
            )
        }

        if (!report.contextRecorded) {
            Text(stringResource(R.string.autopsy_old_incident), style = MaterialTheme.typography.bodySmall, color = HeatColors.Elevated)
        } else if (report.missing.isNotEmpty()) {
            val labels = report.missing.map { stringResource(it.labelRes()) }
            val names = labels.joinToString(", ")
            Text(stringResource(R.string.autopsy_not_recorded, names), style = MaterialTheme.typography.bodySmall, color = HeatColors.TextTertiary)
        }
        if (report.baselineIncidents < HeatAutopsy.MIN_BASELINE) {
            Text(
                stringResource(R.string.autopsy_need_history, HeatAutopsy.MIN_BASELINE, report.baselineIncidents),
                style = MaterialTheme.typography.bodySmall,
                color = HeatColors.TextTertiary,
            )
        }

        Text(stringResource(R.string.autopsy_disclaimer), style = MaterialTheme.typography.bodySmall, color = HeatColors.TextTertiary)

        OutlinedButton(onClick = onExplain, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null, tint = HeatColors.Accent, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.autopsy_explain), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun FactorRow(factor: AutopsyFactor, unit: TemperatureUnit) {
    val color = if (factor.strength == Strength.HIGH) HeatColors.High else HeatColors.Elevated
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(color, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(factor.titleRes()), style = MaterialTheme.typography.bodyLarge, color = HeatColors.TextPrimary)
                Text(
                    stringResource(if (factor.strength == Strength.HIGH) R.string.autopsy_strong else R.string.autopsy_possible).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }
            Text(evidence(factor, unit), style = MaterialTheme.typography.bodySmall, color = HeatColors.TextSecondary)
        }
    }
}

private fun AutopsyFactor.titleRes(): Int = when (type) {
    FactorType.FAST_HEATING -> R.string.autopsy_f_fast
    FactorType.CHARGING -> R.string.autopsy_f_charging
    FactorType.HIGH_BRIGHTNESS -> R.string.autopsy_f_brightness
    FactorType.LOW_MEMORY -> R.string.autopsy_f_memory
    FactorType.ALREADY_WARM -> R.string.autopsy_f_warm
}

private fun MissingSignal.labelRes(): Int = when (this) {
    MissingSignal.BRIGHTNESS -> R.string.autopsy_m_brightness
    MissingSignal.MEMORY -> R.string.autopsy_m_memory
    MissingSignal.HEATING_RATE -> R.string.autopsy_m_rate
    MissingSignal.PRE_TEMPERATURE -> R.string.autopsy_m_pre_temp
}

@Composable
private fun evidence(f: AutopsyFactor, unit: TemperatureUnit): String {
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    return when (f.type) {
        FactorType.FAST_HEATING -> {
            val rate = Temperatures.formatDelta(f.value ?: 0f, unit)
            val usual = f.baseline
            if (usual != null && usual > 0f) {
                stringResource(
                    R.string.autopsy_e_fast_vs_usual, rate,
                    String.format(locale, "%.1f", (f.value ?: 0f) / usual), Temperatures.formatDelta(usual, unit),
                )
            } else {
                stringResource(R.string.autopsy_e_fast, rate)
            }
        }
        FactorType.CHARGING -> {
            val source = stringResource((f.power ?: PowerSource.UNKNOWN).labelRes())
            stringResource(
                if (f.power == PowerSource.WIRELESS) R.string.autopsy_e_charging_wireless else R.string.autopsy_e_charging,
                source,
            )
        }
        FactorType.HIGH_BRIGHTNESS -> stringResource(R.string.autopsy_e_brightness, (f.value ?: 0f).toInt())
        FactorType.LOW_MEMORY ->
            f.value?.let { stringResource(R.string.autopsy_e_memory, it.toInt()) } ?: stringResource(R.string.autopsy_e_memory_flag)
        FactorType.ALREADY_WARM -> stringResource(R.string.autopsy_e_warm, Temperatures.format(f.value ?: 0f, unit))
    }
}
