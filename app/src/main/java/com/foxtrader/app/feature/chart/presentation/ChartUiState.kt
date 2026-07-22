package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.DrawingMode
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.FairValueGap
import com.foxtrader.app.domain.model.LiquidityPool
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.SessionRange
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.Timeframe

/**
 * Immutable UI state for the Chart screen (MVVM).
 * The View is a pure function of this state.
 *
 * Contains ALL data the chart composables need to render every layer:
 * candles, indicators, structure, SMC concepts, sessions, drawings, and UI flags.
 */
data class ChartUiState(
    // --- Core market data ---
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.M15,
    val candles: List<Candle> = emptyList(),
    val bias: Bias = Bias.NEUTRAL,

    // --- Technical analysis ---
    val structureBreaks: List<StructureBreak> = emptyList(),
    val emaShort: DoubleArray? = null,  // EMA(20)
    val emaLong: DoubleArray? = null,   // EMA(50)

    // --- Smart Money Concepts ---
    val orderBlocks: List<OrderBlock> = emptyList(),
    val fairValueGaps: List<FairValueGap> = emptyList(),
    val liquidityPools: List<LiquidityPool> = emptyList(),

    // --- Trading sessions ---
    val sessions: List<SessionRange> = emptyList(),

    // --- Drawing tools ---
    val drawings: List<ChartDrawing> = emptyList(),
    val drawingMode: DrawingMode = DrawingMode.NONE,
    val activeTool: DrawingToolType? = null,
    val showDrawingToolbar: Boolean = false,

    // --- Loading / error ---
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val lastPrice: Double? get() = candles.lastOrNull()?.close
    val hasData: Boolean get() = candles.isNotEmpty()

    /** Whether any SMC overlay is available for rendering. */
    val hasSmcData: Boolean
        get() = orderBlocks.isNotEmpty() || fairValueGaps.isNotEmpty() || liquidityPools.isNotEmpty()

    // Custom equals/hashCode because of DoubleArray fields
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChartUiState) return false
        return symbol == other.symbol && timeframe == other.timeframe &&
            candles == other.candles && bias == other.bias &&
            structureBreaks == other.structureBreaks &&
            emaShort.contentEqualsNullable(other.emaShort) &&
            emaLong.contentEqualsNullable(other.emaLong) &&
            orderBlocks == other.orderBlocks &&
            fairValueGaps == other.fairValueGaps &&
            liquidityPools == other.liquidityPools &&
            sessions == other.sessions &&
            drawings == other.drawings &&
            drawingMode == other.drawingMode &&
            activeTool == other.activeTool &&
            showDrawingToolbar == other.showDrawingToolbar &&
            isLoading == other.isLoading && error == other.error
    }

    override fun hashCode(): Int {
        var result = symbol.hashCode()
        result = 31 * result + timeframe.hashCode()
        result = 31 * result + candles.hashCode()
        result = 31 * result + (emaShort?.contentHashCode() ?: 0)
        result = 31 * result + (emaLong?.contentHashCode() ?: 0)
        result = 31 * result + orderBlocks.hashCode()
        result = 31 * result + fairValueGaps.hashCode()
        return result
    }
}

private fun DoubleArray?.contentEqualsNullable(other: DoubleArray?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return this == other
    return this.contentEquals(other)
}
