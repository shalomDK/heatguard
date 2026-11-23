package com.example.appdev.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.appdev.HeatGuardApp
import com.example.appdev.R
import com.example.appdev.data.perf.SpeedTestController
import com.example.appdev.data.perf.SpeedTestError
import com.example.appdev.data.settings.SettingsRepository
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.model.TemperatureUnit
import com.example.appdev.domain.model.Temperatures
import com.example.appdev.domain.perf.HeatTax
import com.example.appdev.domain.perf.HeatTaxReport
import com.example.appdev.domain.perf.SlowdownVerdict
import com.example.appdev.domain.perf.SpeedTestRepository
import com.example.appdev.ui.components.Bar
import com.example.appdev.ui.components.BarChart
import com.example.appdev.ui.components.HeatCard
import com.example.appdev.ui.components.rememberDateTimeFormatter
import com.example.appdev.ui.history.StatusChip
import com.example.appdev.ui.theme.HeatColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class HeatTaxUiState(
    val report: HeatTaxReport = HeatTax.analyze(emptyList()),
    val running: Boolean = false,
    val error: SpeedTestError? = null,
    val isDemo: Boolean = false,
    val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HeatTaxViewModel(
    private val repository: SpeedTestRepository,
    private val controller: SpeedTestController,
    private val activeSource: StateFlow<ReadingSource>,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<HeatTaxUiState> = combine(
        activeSource.flatMapLatest { repository.observe(it) },
        controller.state,
        activeSource,
        settingsRepository.settings.map { it.unit },
    ) { results, state, source, unit ->
        HeatTaxUiState(HeatTax.analyze(results), state.running, state.error, source == ReadingSource.DEMO, unit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeatTaxUiState())

    fun run() = controller.run()

    fun dismissError() = controller.dismissError()

    /** Clears the results currently shown: real ones normally, demo ones in Demo Mode. */
    fun clear() {
        viewModelScope.launch { repository.deleteAll(activeSource.value) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as HeatGuardApp).container
                HeatTaxViewModel(c.speedTestRepository, c.speedTests, c.demoController.activeSource, c.settingsRepository)
            }
        }
    }
}

private fun SlowdownVerdict.color(): Color = when (this) {
    SlowdownVerdict.FULL_SPEED -> HeatColors.Normal
    SlowdownVerdict.SLIGHT -> HeatColors.Elevated
    SlowdownVerdict.NOTICEABLE -> HeatColors.High
    SlowdownVerdict.HEAVY -> HeatColors.Critical
}

private fun SlowdownVerdict.labelRes(): Int = when (this) {
    SlowdownVerdict.FULL_SPEED -> R.string.heattax_v_full
    SlowdownVerdict.SLIGHT -> R.string.heattax_v_slight
    SlowdownVerdict.NOTICEABLE -> R.string.heattax_v_noticeable
    SlowdownVerdict.HEAVY -> R.string.heattax_v_heavy
}

/**
 * Heat Tax: how much speed the phone loses when it is hot. The user runs a short test now and then; each
 * result is stored with the battery temperature, so the card can show the speed at different temperatures.
 */
@Composable
fun HeatTaxCard(viewModel: HeatTaxViewModel = viewModel(factory = HeatTaxViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val report = state.report
    val unit = state.unit
    val formatDateTime = rememberDateTimeFormatter()
    var confirmClear by remember { mutableStateOf(false) }
    val coolLimit = Temperatures.format(HeatTax.COOL_BELOW_C, unit)

    HeatCard(Modifier.fillMaxWidth(), spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.heattax_title).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = HeatColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (state.isDemo) StatusChip(stringResource(R.string.demo_simulated_chip), HeatColors.Accent)
        }

        val percent = report.latestPercent
        val verdict = report.verdict
        val latest = report.latest
        if (percent != null && verdict != null && latest != null) {
            val color = verdict.color()
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$percent%", style = MaterialTheme.typography.displayLarge, color = HeatColors.TextPrimary)
                Text(
                    stringResource(R.string.heattax_of_normal),
                    style = MaterialTheme.typography.titleMedium,
                    color = HeatColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(stringResource(verdict.labelRes()), color)
                Text(
                    stringResource(R.string.heattax_tax, report.heatTaxPercent ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }
            Text(
                stringResource(R.string.heattax_latest, Temperatures.format(latest.temperatureC, unit), formatDateTime(latest.timestampMillis)),
                style = MaterialTheme.typography.bodySmall,
                color = HeatColors.TextSecondary,
            )
        } else {
            Text(stringResource(R.string.heattax_headline), style = MaterialTheme.typography.titleMedium, color = HeatColors.TextPrimary)
            Text(
                stringResource(R.string.heattax_need_data, coolLimit, report.resultCount, HeatTax.MIN_PROVISIONAL_SAMPLES),
                style = MaterialTheme.typography.bodyMedium,
                color = HeatColors.TextSecondary,
            )
        }

        val bands = report.bands.filter { it.averagePercent != null }
        if (bands.size >= 2) {
            HorizontalDivider(color = HeatColors.OutlineSubtle)
            Text(stringResource(R.string.heattax_bands_title), style = MaterialTheme.typography.labelLarge, color = HeatColors.TextPrimary)
            BarChart(
                bars = bands.map { band ->
                    Bar(band.averagePercent ?: 0, bandLabel(band.fromC, band.toC, unit), highlighted = (band.averagePercent ?: 100) < 100 - HeatTax.NOISE_PERCENT)
                },
                color = HeatColors.Normal,
                highlightColor = HeatColors.High,
                maxValue = 100,
                valueSuffix = "%",
            )
        }

        if (report.baselineScore != null) {
            Text(
                if (report.provisionalBaseline) {
                    stringResource(R.string.heattax_baseline_provisional, coolLimit)
                } else {
                    stringResource(R.string.heattax_baseline_firm, report.coolSamples, coolLimit)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (report.provisionalBaseline) HeatColors.Elevated else HeatColors.TextTertiary,
            )
        }

        state.error?.let { error ->
            Text(
                stringResource(if (error == SpeedTestError.NO_TEMPERATURE) R.string.heattax_err_no_temp else R.string.heattax_err_failed),
                style = MaterialTheme.typography.bodySmall,
                color = HeatColors.Critical,
            )
        }

        Button(
            onClick = { viewModel.dismissError(); viewModel.run() },
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = HeatColors.Accent,
                contentColor = HeatColors.OnAccent,
                disabledContainerColor = HeatColors.SurfaceHighest,
                disabledContentColor = HeatColors.TextTertiary,
            ),
        ) {
            Text(stringResource(if (state.running) R.string.heattax_running else R.string.heattax_run))
        }

        Text(
            stringResource(if (state.isDemo) R.string.heattax_demo_note else R.string.heattax_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = HeatColors.TextTertiary,
        )
        if (report.resultCount > 0) {
            TextButton(onClick = { confirmClear = true }) {
                Text(stringResource(R.string.heattax_clear), color = HeatColors.TextSecondary)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.heattax_clear_title)) },
            text = { Text(stringResource(R.string.heattax_clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clear() }) {
                    Text(stringResource(R.string.history_clear_confirm), color = HeatColors.Critical)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun bandLabel(fromC: Float, toC: Float?, unit: TemperatureUnit): String {
    fun n(c: Float) = String.format(Locale.ROOT, "%.0f", Temperatures.fromCelsius(c, unit))
    return when {
        fromC <= 0f -> "<${n(toC ?: 0f)}°"
        toC == null -> "${n(fromC)}°+"
        else -> "${n(fromC)}–${n(toC)}°"
    }
}
