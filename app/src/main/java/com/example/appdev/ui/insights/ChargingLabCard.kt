package com.example.appdev.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.pluralStringResource
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
import com.example.appdev.data.settings.SettingsRepository
import com.example.appdev.domain.charging.ChargeSessionRepository
import com.example.appdev.domain.charging.ChargerStats
import com.example.appdev.domain.charging.ChargingLab
import com.example.appdev.domain.charging.ChargingLabReport
import com.example.appdev.domain.charging.ChargingVerdict
import com.example.appdev.domain.model.ReadingSource
import com.example.appdev.domain.model.TemperatureUnit
import com.example.appdev.domain.model.Temperatures
import com.example.appdev.ui.components.HeatCard
import com.example.appdev.ui.components.labelRes
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

data class ChargingLabUiState(
    val report: ChargingLabReport = ChargingLab.analyze(emptyList()),
    val isDemo: Boolean = false,
    val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChargingLabViewModel(
    private val repository: ChargeSessionRepository,
    private val activeSource: StateFlow<ReadingSource>,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<ChargingLabUiState> = combine(
        activeSource.flatMapLatest { repository.observe(it) },
        activeSource,
        settingsRepository.settings.map { it.unit },
    ) { sessions, source, unit ->
        ChargingLabUiState(ChargingLab.analyze(sessions), source == ReadingSource.DEMO, unit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChargingLabUiState())

    /** Clears the sessions currently shown: real ones normally, demo ones in Demo Mode. */
    fun clear() {
        viewModelScope.launch { repository.deleteAll(activeSource.value) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as HeatGuardApp).container
                ChargingLabViewModel(c.chargeSessionRepository, c.demoController.activeSource, c.settingsRepository)
            }
        }
    }
}

/**
 * Charging Lab: a report card for the user's own chargers. Every charge is recorded automatically while
 * monitoring is on; once a few sessions exist for at least two kinds of charger, they are ranked by how
 * much the phone warmed up while charging.
 */
@Composable
fun ChargingLabCard(viewModel: ChargingLabViewModel = viewModel(factory = ChargingLabViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val report = state.report
    val unit = state.unit
    var confirmClear by remember { mutableStateOf(false) }

    HeatCard(Modifier.fillMaxWidth(), spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.lab_title).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = HeatColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (state.isDemo) StatusChip(stringResource(R.string.demo_simulated_chip), HeatColors.Accent)
        }

        when (report.verdict) {
            ChargingVerdict.CLEAR_DIFFERENCE -> {
                val coolest = report.coolest
                val hottest = report.hottest
                val spread = report.spreadC
                if (coolest != null && hottest != null && spread != null) {
                    Text(
                        stringResource(R.string.lab_headline_clear, stringResource(coolest.labelRes())),
                        style = MaterialTheme.typography.titleLarge,
                        color = HeatColors.TextPrimary,
                    )
                    Text(
                        stringResource(
                            R.string.lab_clear_detail,
                            stringResource(hottest.labelRes()),
                            Temperatures.formatDelta(spread, unit),
                            stringResource(coolest.labelRes()),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HeatColors.TextSecondary,
                    )
                }
            }
            ChargingVerdict.NO_CLEAR_DIFFERENCE -> {
                Text(stringResource(R.string.lab_headline_none), style = MaterialTheme.typography.titleLarge, color = HeatColors.TextPrimary)
                Text(
                    stringResource(R.string.lab_none_detail, Temperatures.formatDelta(ChargingLab.MIN_MEANINGFUL_SPREAD_C, unit)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeatColors.TextSecondary,
                )
            }
            ChargingVerdict.NOT_ENOUGH_DATA -> {
                Text(stringResource(R.string.lab_headline_need), style = MaterialTheme.typography.titleLarge, color = HeatColors.TextPrimary)
                Text(
                    stringResource(R.string.lab_need_detail, ChargingLab.MIN_SESSIONS_PER_CHARGER, ChargingLab.MIN_SESSION_MILLIS / 60_000),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeatColors.TextSecondary,
                )
            }
        }

        if (report.chargers.isNotEmpty()) {
            HorizontalDivider(color = HeatColors.OutlineSubtle)
            val maxRise = report.chargers.maxOf { it.averageRiseC }.coerceAtLeast(0.1f)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                report.chargers.forEachIndexed { index, stats ->
                    ChargerRow(
                        stats = stats,
                        unit = unit,
                        fraction = stats.averageRiseC / maxRise,
                        isCoolest = report.verdict != ChargingVerdict.NOT_ENOUGH_DATA && stats.type == report.coolest,
                        rank = if (stats.ranked) index + 1 else null,
                    )
                }
            }
        }

        if (report.inProgress) {
            Text(stringResource(R.string.lab_in_progress), style = MaterialTheme.typography.bodySmall, color = HeatColors.Accent)
        }
        Text(
            stringResource(if (state.isDemo) R.string.lab_demo_note else R.string.lab_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = HeatColors.TextTertiary,
        )
        if (report.countedSessions > 0) {
            TextButton(onClick = { confirmClear = true }) {
                Text(stringResource(R.string.lab_clear), color = HeatColors.TextSecondary)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.lab_clear_title)) },
            text = { Text(stringResource(R.string.lab_clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clear() }) {
                    Text(stringResource(R.string.history_clear_confirm), color = HeatColors.Critical)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ChargerRow(stats: ChargerStats, unit: TemperatureUnit, fraction: Float, isCoolest: Boolean, rank: Int?) {
    val name = stringResource(stats.type.labelRes())
    val barColor = when {
        !stats.ranked -> HeatColors.TextTertiary
        isCoolest -> HeatColors.Normal
        else -> HeatColors.Elevated
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (rank != null) "$rank. $name" else name,
                style = MaterialTheme.typography.titleSmall,
                color = HeatColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (!stats.ranked) {
                StatusChip(
                    stringResource(R.string.lab_collecting, stats.sessions, ChargingLab.MIN_SESSIONS_PER_CHARGER),
                    HeatColors.TextSecondary,
                )
            } else {
                Text(
                    stringResource(R.string.lab_avg_rise, Temperatures.formatDelta(stats.averageRiseC, unit)),
                    style = MaterialTheme.typography.titleSmall,
                    color = barColor,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(HeatColors.SurfaceHighest, CircleShape)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                    .height(8.dp)
                    .background(barColor, CircleShape),
            )
        }
        val speed = stats.averagePercentPerHour?.let { String.format(Locale.ROOT, "%.0f", it) }
        val parts = listOfNotNull(
            pluralStringResource(R.plurals.lab_row_sessions, stats.sessions, stats.sessions),
            stringResource(R.string.lab_row_peak, Temperatures.format(stats.averagePeakC, unit), Temperatures.format(stats.hottestPeakC, unit)),
            speed?.let { stringResource(R.string.lab_row_speed, it) },
        )
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = HeatColors.TextSecondary,
        )
    }
}
