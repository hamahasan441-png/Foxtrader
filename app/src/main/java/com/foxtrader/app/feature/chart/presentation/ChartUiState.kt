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
    val emaShort: DoubleArray? = null,  // EMA(20) values per candle index
    val emaLong: DoubleArray? = null,   // EMA(50) values per candle index
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val lastPrice: Double? get() = candles.lastOrNull()?.close
    val hasData: Boolean get() = candles.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChartUiState) return false
        return symbol == other.symbol && timeframe == other.timeframe &&
            candles == other.candles && bias == other.bias &&
            structureBreaks == other.structureBreaks &&
            emaShort.contentEquals(other.emaShort) &&
            emaLong.contentEquals(other.emaLong) &&
            isLoading == other.isLoading && error == other.error
    }

    override fun hashCode(): Int {
        var result = symbol.hashCode()
        result = 31 * result + timeframe.hashCode()
        result = 31 * result + candles.hashCode()
        result = 31 * result + (emaShort?.contentHashCode() ?: 0)
        result = 31 * result + (emaLong?.contentHashCode() ?: 0)
        return result
    }
}

private fun DoubleArray?.contentEquals(other: DoubleArray?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return this == other
    return this.contentEquals(other)
}
