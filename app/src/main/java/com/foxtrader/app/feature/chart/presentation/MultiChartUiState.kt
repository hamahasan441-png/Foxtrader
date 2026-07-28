package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.CandleSource
import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.usecase.chart.ChartLayout
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Lightweight UI state for the supplemental multi-chart monitor.
 *
 * The primary chart remains the full interactive surface. Multi-chart panels are
 * compact comparison views driven by [com.foxtrader.app.domain.usecase.chart.MultiChartManager].
 */
@Immutable
data class MultiChartUiState(
    val layout: ChartLayout = ChartLayout.SINGLE,
    val linkedToPrimary: Boolean = true,
    val symbolLinkEnabled: Boolean = true,
    val timeframeLinkEnabled: Boolean = true,
    val crosshairSyncEnabled: Boolean = true,
    val panels: ImmutableList<MultiChartPanelUiState> = persistentListOf(),
)

@Immutable
data class MultiChartPanelUiState(
    val id: String,
    val symbol: String,
    val timeframe: com.foxtrader.app.domain.model.Timeframe,
    val candles: CandleSeries = CandleSeries.EMPTY,
    val dataSource: CandleSource = CandleSource.CACHED,
    val bias: Bias = Bias.NEUTRAL,
    val isActive: Boolean = false,
    val syncedCrosshairTimestamp: Long? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val lastPrice: Double? get() = candles.lastOrNull()?.close
    val isSyntheticData: Boolean get() = candles.isNotEmpty() && dataSource == CandleSource.SYNTHETIC
}
