package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DrawingMode
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.FairValueGap
import com.foxtrader.app.domain.model.LiquidityPool
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.SessionRange
import com.foxtrader.app.domain.usecase.ai.MarketExplanation
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.Timeframe

/**
 * Which indicators are currently enabled on the chart.
 * Immutable — toggled via the chart's indicator panel.
 */
data class IndicatorToggles(
    val ema: Boolean = true,
    val bollinger: Boolean = false,
    val superTrend: Boolean = false,
    val parabolicSar: Boolean = false,
    val vwap: Boolean = false,
    val ichimoku: Boolean = false,
    val volumeProfile: Boolean = false,
    val orderBlocks: Boolean = true,
    val fairValueGaps: Boolean = true,
    val liquidity: Boolean = true,
    val sessions: Boolean = false,
    val structure: Boolean = true,
)

/**
 * Immutable UI state for the Chart screen (MVVM).
 * The View is a pure function of this state.
 */
data class ChartUiState(
    // --- Core market data ---
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.M15,
    val candles: List<Candle> = emptyList(),
    /**
     * Provenance of [candles]. Drives the SIMULATED DATA banner and the
     * decision engine's data-integrity veto.
     */
    val dataSource: CandleSource = CandleSource.CACHED,
    val bias: Bias = Bias.NEUTRAL,

    // --- Technical analysis ---
    val structureBreaks: List<StructureBreak> = emptyList(),
    val emaShort: DoubleArray? = null,  // EMA(20)
    val emaLong: DoubleArray? = null,   // EMA(50)
    val bollingerUpper: DoubleArray? = null,
    val bollingerMiddle: DoubleArray? = null,
    val bollingerLower: DoubleArray? = null,
    val superTrendValues: DoubleArray? = null,
    val superTrendDir: IntArray? = null,
    val parabolicSar: DoubleArray? = null,
    val vwap: DoubleArray? = null,
    val ichimokuTenkan: DoubleArray? = null,
    val ichimokuKijun: DoubleArray? = null,
    val ichimokuSenkouA: DoubleArray? = null,
    val ichimokuSenkouB: DoubleArray? = null,
    val ichimokuChikou: DoubleArray? = null,

    // --- Smart Money Concepts ---
    val orderBlocks: List<OrderBlock> = emptyList(),
    val fairValueGaps: List<FairValueGap> = emptyList(),
    val liquidityPools: List<LiquidityPool> = emptyList(),
    val volumeProfile: com.foxtrader.app.domain.model.VolumeProfile? = null,

    // --- Trading sessions ---
    val sessions: List<SessionRange> = emptyList(),

    // --- Drawing tools ---
    val drawings: List<ChartDrawing> = emptyList(),
    val drawingMode: DrawingMode = DrawingMode.NONE,
    val activeTool: DrawingToolType? = null,
    val showDrawingToolbar: Boolean = false,

    // --- Indicator / symbol / connection UI ---
    val indicators: IndicatorToggles = IndicatorToggles(),
    val showIndicatorPanel: Boolean = false,
    val showSymbolPicker: Boolean = false,
    val showCalculator: Boolean = false,
    /**
     * Symbols from the user's active watchlist. Empty until the repository
     * emits. The seed list now lives in WatchlistRepositoryImpl and is a
     * starting point the user can edit, not a compiled-in list.
     */
    val availableSymbols: List<String> = emptyList(),
    /** Active (default) watchlist id, or null before the first emission. */
    val activeWatchlistId: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val liveEnabled: Boolean = false,

    // --- Loading / error ---
    val isLoading: Boolean = true,
    val error: String? = null,

    // --- AI / Market Context ---
    val aiDecision: DecisionResult? = null,
    val marketExplanation: MarketExplanation? = null,
) {
    val lastPrice: Double? get() = candles.lastOrNull()?.close
    val hasData: Boolean get() = candles.isNotEmpty()

    /** True when the chart is rendering generated bars rather than real prices. */
    val isSyntheticData: Boolean get() = hasData && dataSource == CandleSource.SYNTHETIC

    val hasSmcData: Boolean
        get() = orderBlocks.isNotEmpty() || fairValueGaps.isNotEmpty() || liquidityPools.isNotEmpty()

    // Custom equals/hashCode because of array fields. Note: indicator arrays
    // co-vary with `candles`, so comparing candles + toggles is sufficient to
    // drive recomposition correctly without comparing every array.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChartUiState) return false
        return symbol == other.symbol && timeframe == other.timeframe &&
            candles == other.candles && dataSource == other.dataSource &&
            availableSymbols == other.availableSymbols &&
            activeWatchlistId == other.activeWatchlistId &&
            bias == other.bias &&
            structureBreaks == other.structureBreaks &&
            orderBlocks == other.orderBlocks &&
            fairValueGaps == other.fairValueGaps &&
            liquidityPools == other.liquidityPools &&
            sessions == other.sessions &&
            drawings == other.drawings &&
            drawingMode == other.drawingMode &&
            activeTool == other.activeTool &&
            showDrawingToolbar == other.showDrawingToolbar &&
            indicators == other.indicators &&
            showIndicatorPanel == other.showIndicatorPanel &&
            showSymbolPicker == other.showSymbolPicker &&
            showCalculator == other.showCalculator &&
            connectionState == other.connectionState &&
            liveEnabled == other.liveEnabled &&
            isLoading == other.isLoading && error == other.error &&
            aiDecision == other.aiDecision &&
            marketExplanation == other.marketExplanation
    }

    override fun hashCode(): Int {
        var result = symbol.hashCode()
        result = 31 * result + timeframe.hashCode()
        result = 31 * result + candles.hashCode()
        result = 31 * result + dataSource.hashCode()
        result = 31 * result + indicators.hashCode()
        result = 31 * result + connectionState.hashCode()
        result = 31 * result + showIndicatorPanel.hashCode()
        result = 31 * result + showSymbolPicker.hashCode()
        result = 31 * result + showCalculator.hashCode()
        result = 31 * result + (aiDecision?.hashCode() ?: 0)
        result = 31 * result + (marketExplanation?.hashCode() ?: 0)
        return result
    }

}
