package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.Timeframe

/**
 * Immutable UI state for the Chart screen (MVVM).
 * The View is a pure function of this state.
 */
data class ChartUiState(
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.M15,
    val candles: List<Candle> = emptyList(),
    val bias: Bias = Bias.NEUTRAL,
    val structureBreaks: List<StructureBreak> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val lastPrice: Double? get() = candles.lastOrNull()?.close
    val hasData: Boolean get() = candles.isNotEmpty()
}
