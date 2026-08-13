package com.foxtrader.app.feature.chart.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.SmcVisualMode
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DrawingMode
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.FairValueGap
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LiquidityPool
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.SessionRange
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.usecase.ai.MarketExplanation
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.analysis.FibonacciEngine
import com.foxtrader.app.domain.usecase.analysis.MarketProfile
import com.foxtrader.app.domain.usecase.analysis.SupportResistanceDetector
import com.foxtrader.app.domain.usecase.mtf.ConfluenceEngine
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Which indicators are currently enabled on the chart.
 * Immutable — toggled via the chart's indicator panel.
 */
@Immutable
data class IndicatorToggles(
    val ema: Boolean = true,
    val bollinger: Boolean = false,
    val superTrend: Boolean = false,
    val parabolicSar: Boolean = false,
    val vwap: Boolean = false,
    val anchoredVwap: Boolean = false,
    val ichimoku: Boolean = false,
    val volumeProfile: Boolean = false,
    val marketProfile: Boolean = false,
    val supportResistance: Boolean = false,
    val fibonacci: Boolean = false,
    val confluence: Boolean = false,
    val orderBlocks: Boolean = true,
    val fairValueGaps: Boolean = true,
    val liquidity: Boolean = true,
    val sessions: Boolean = false,
    val structure: Boolean = true,
    // --- Strategy-as-Indicator overlays (gated independently of their engines) ---
    val litX: Boolean = false,
    val smt: Boolean = false,
    val tradePro: Boolean = true,
    // --- Separate-pane ("study") indicators, rendered in the resizable pane
    // stack below the price chart rather than as overlays (R3). ---
    val rsi: Boolean = false,
    val macd: Boolean = false,
    val volume: Boolean = false,
    val smcVisualMode: SmcVisualMode = SmcVisualMode.PROFESSIONAL,
)

/**
 * Immutable UI state for the Chart screen (MVVM).
 * The View is a pure function of this state.
 */
@Immutable
data class ChartUiState(
    // --- Core market data ---
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.M15,
    val barMode: ChartBarMode = ChartBarMode.TIME,
    val renkoSize: Double = 10.0,
    val candles: CandleSeries = CandleSeries.EMPTY,
    /**
     * Provenance of [candles]. Drives the SIMULATED DATA banner and the
     * decision engine's data-integrity veto.
     */
    val dataSource: CandleSource = CandleSource.CACHED,
    val bias: Bias = Bias.NEUTRAL,

    // --- Technical analysis ---
    val structureBreaks: ImmutableList<StructureBreak> = persistentListOf(),
    val emaShort: ImmutableDoubleSeries? = null,  // EMA(20)
    val emaLong: ImmutableDoubleSeries? = null,   // EMA(50)
    val bollingerUpper: ImmutableDoubleSeries? = null,
    val bollingerMiddle: ImmutableDoubleSeries? = null,
    val bollingerLower: ImmutableDoubleSeries? = null,
    val superTrendValues: ImmutableDoubleSeries? = null,
    val superTrendDir: ImmutableIntSeries? = null,
    val parabolicSar: ImmutableDoubleSeries? = null,
    val vwap: ImmutableDoubleSeries? = null,
    val anchoredVwap: ImmutableDoubleSeries? = null,
    val anchoredVwapUpper: ImmutableDoubleSeries? = null,
    val anchoredVwapLower: ImmutableDoubleSeries? = null,
    val ichimokuTenkan: ImmutableDoubleSeries? = null,
    val ichimokuKijun: ImmutableDoubleSeries? = null,
    val ichimokuSenkouA: ImmutableDoubleSeries? = null,
    val ichimokuSenkouB: ImmutableDoubleSeries? = null,
    val ichimokuChikou: ImmutableDoubleSeries? = null,

    // --- Smart Money Concepts ---
    val orderBlocks: ImmutableList<OrderBlock> = persistentListOf(),
    val fairValueGaps: ImmutableList<FairValueGap> = persistentListOf(),
    val liquidityPools: ImmutableList<LiquidityPool> = persistentListOf(),
    val tradeProAnalysis: TradeProAnalysis? = null,
    val litXAnalysis: LitXAnalysis? = null,
    val smtDivergences: List<SmtDivergenceDetector.SmtDivergence> = emptyList(),
    val signals: List<ChartSignal> = emptyList(),
    val showSignalHistory: Boolean = false,
    val volumeProfile: com.foxtrader.app.domain.model.VolumeProfile? = null,
    val marketProfile: MarketProfile.ProfileResult? = null,
    val supportResistanceZones: ImmutableList<SupportResistanceDetector.SRZone> = persistentListOf(),
    val autoFibLevels: ImmutableList<FibonacciEngine.FibLevel> = persistentListOf(),
    val autoFibDirection: Direction? = null,
    val autoFibSwingHigh: Double? = null,
    val autoFibSwingLow: Double? = null,

    // --- Trading sessions ---
    val sessions: ImmutableList<SessionRange> = persistentListOf(),

    // --- Drawing tools ---
    val drawings: ImmutableList<ChartDrawing> = persistentListOf(),
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
    val availableSymbols: ImmutableList<String> = persistentListOf(),
    /** Active (default) watchlist id, or null before the first emission. */
    val activeWatchlistId: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val liveEnabled: Boolean = false,

    // --- Loading / error ---
    val isLoading: Boolean = true,
    val isLoadingOlder: Boolean = false,
    val historyEndReached: Boolean = false,
    val error: String? = null,

    // --- AI / Market Context ---
    val aiDecision: DecisionResult? = null,
    val marketExplanation: MarketExplanation? = null,
    val confluence: ConfluenceEngine.ConfluenceResult? = null,
    val syncedCrosshairTimestamp: Long? = null,

    // --- Oscillator sub-panels ---
    val rsiValues: ImmutableDoubleSeries? = null,
    val macdLine: ImmutableDoubleSeries? = null,
    val macdSignal: ImmutableDoubleSeries? = null,
    val macdHistogram: ImmutableDoubleSeries? = null,
) {
    val lastPrice: Double? get() = candles.lastOrNull()?.close
    val hasData: Boolean get() = candles.isNotEmpty()

    /** True when the chart is rendering generated bars rather than real prices. */
    val isSyntheticData: Boolean get() = hasData && dataSource == CandleSource.SYNTHETIC

    val hasSmcData: Boolean
        get() = orderBlocks.isNotEmpty() || fairValueGaps.isNotEmpty() || liquidityPools.isNotEmpty()

}
