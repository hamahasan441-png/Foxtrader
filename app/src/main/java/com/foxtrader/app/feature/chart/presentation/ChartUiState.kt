package com.foxtrader.app.feature.chart.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.BacktestChartMarker
import com.foxtrader.app.domain.model.EquityPoint
import com.foxtrader.app.domain.model.SmcVisualMode
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DrawingMode
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.domain.model.FairValueGap
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.SmsAnalysis
import com.foxtrader.app.domain.model.SignalFusionResult
import com.foxtrader.app.domain.model.LiquidityPool
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.SessionRange
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.usecase.ai.MarketExplanation
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.analysis.FibonacciEngine
import com.foxtrader.app.domain.usecase.analysis.MarketProfile
import com.foxtrader.app.domain.usecase.indicators.PivotPoints
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
    val ema: Boolean = false,
    val bollinger: Boolean = false,
    val superTrend: Boolean = false,
    val parabolicSar: Boolean = false,
    val vwap: Boolean = false,
    val anchoredVwap: Boolean = false,
    val ichimoku: Boolean = false,
    val keltner: Boolean = false,
    val donchian: Boolean = false,
    val pivotPoints: Boolean = false,
    val volumeProfile: Boolean = false,
    val marketProfile: Boolean = false,
    val supportResistance: Boolean = false,
    val fibonacci: Boolean = false,
    val confluence: Boolean = false,
    val orderBlocks: Boolean = false,
    val fairValueGaps: Boolean = false,
    val liquidity: Boolean = false,
    val sessions: Boolean = false,
    val structure: Boolean = false,
    val litX: Boolean = false,
    val lit: Boolean = false,
    val sms: Boolean = false,
    val smt: Boolean = false,
    val tradePro: Boolean = false,
    val binary3m: Boolean = false,
    val rsi: Boolean = false,
    val macd: Boolean = false,
    val volume: Boolean = false,
    val stochastic: Boolean = false,
    val obv: Boolean = false,
    val moneyFlowIndex: Boolean = false,
    val smcVisualMode: SmcVisualMode = SmcVisualMode.MINIMAL,
    val activeStrategy: StrategyType? = null,
    val activeBlueprintId: String? = null,
    val allStrategies: Boolean = false,
) {
    val anyActive: Boolean
        get() = ema || bollinger || superTrend || parabolicSar || vwap || anchoredVwap ||
            ichimoku || keltner || donchian || pivotPoints || volumeProfile || marketProfile ||
            supportResistance || fibonacci || confluence || orderBlocks || fairValueGaps ||
            liquidity || sessions || structure || litX || lit || sms || smt || tradePro || binary3m ||
            rsi || macd || volume || stochastic || obv || moneyFlowIndex ||
            activeStrategy != null || activeBlueprintId != null || allStrategies

    val smcSuiteActive: Boolean
        get() = structure && orderBlocks && fairValueGaps && liquidity && sessions

    fun withSmcSuite(enabled: Boolean): IndicatorToggles = copy(
        structure = enabled,
        orderBlocks = enabled,
        fairValueGaps = enabled,
        liquidity = enabled,
        sessions = enabled,
    )

    fun withLitXSuite(enabled: Boolean): IndicatorToggles = if (enabled) {
        copy(litX = true, structure = true, orderBlocks = true, fairValueGaps = true, liquidity = true, sessions = true)
    } else copy(litX = false)

    fun withLitSuite(enabled: Boolean): IndicatorToggles = if (enabled) {
        copy(lit = true, structure = true, orderBlocks = true, fairValueGaps = true, liquidity = true, sessions = true)
    } else copy(lit = false)

    fun withSmsSuite(enabled: Boolean): IndicatorToggles = if (enabled) {
        copy(sms = true, structure = true, liquidity = true)
    } else copy(sms = false)

    fun withSmtSuite(enabled: Boolean): IndicatorToggles = if (enabled) {
        copy(smt = true, structure = true, liquidity = true)
    } else copy(smt = false)

    fun withTradeProSuite(enabled: Boolean): IndicatorToggles = if (enabled) {
        copy(
            tradePro = true,
            structure = true,
            orderBlocks = true,
            fairValueGaps = true,
            liquidity = true,
            sessions = true,
            confluence = true,
        )
    } else copy(tradePro = false)

    val institutionalSuiteActive: Boolean
        get() = smcSuiteActive && litX && lit && sms && smt && tradePro

    fun withInstitutionalSuite(enabled: Boolean): IndicatorToggles = if (enabled) {
        copy(
            structure = true,
            orderBlocks = true,
            fairValueGaps = true,
            liquidity = true,
            sessions = true,
            confluence = true,
            litX = true,
            lit = true,
            sms = true,
            smt = true,
            tradePro = true,
        )
    } else {
        copy(
            structure = false,
            orderBlocks = false,
            fairValueGaps = false,
            liquidity = false,
            sessions = false,
            confluence = false,
            litX = false,
            lit = false,
            sms = false,
            smt = false,
            tradePro = false,
        )
    }
}

enum class ChartBacktestRange(val label: String, val days: Int?) {
    /** Test exactly the bars currently visible in the primary chart viewport. */
    VISIBLE("Visible", null),
    LOADED("Loaded", null),
    ONE_MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180),
    ONE_YEAR("1Y", 365),
}

@Immutable
data class ChartBacktestState(
    val selectedStrategy: StrategyType = StrategyType.LITX,
    val selectedRange: ChartBacktestRange = ChartBacktestRange.LOADED,
    val selectedBlueprintId: String? = null,
    val isRunning: Boolean = false,
    val error: String? = null,
    val strategyName: String? = null,
    val totalSignals: Int = 0,
    val winningSignals: Int = 0,
    val losingSignals: Int = 0,
    val breakevenSignals: Int = 0,
    val winRate: Double = 0.0,
    val netPnL: Double = 0.0,
    val profitFactor: Double = 0.0,
    val returnPercent: Double = 0.0,
    val maxDrawdownPercent: Double = 0.0,
    val expectancy: Double = 0.0,
    val averageR: Double = 0.0,
    val showMarkers: Boolean = true,
    val markers: ImmutableList<BacktestChartMarker> = persistentListOf(),
    val equityCurve: ImmutableList<EquityPoint> = persistentListOf(),
    val testedBars: Int = 0,
    val testedFromTimestamp: Long = 0L,
    val testedThroughTimestamp: Long = 0L,
    val rangeCoverageComplete: Boolean = true,
    val historyNotice: String? = null,
    val sourceBarsAtRun: Int = 0,
    val sourceNewestTimestampAtRun: Long = 0L,
    val lastRunTime: Long = 0L,
) {
    val hasResult: Boolean get() = strategyName != null && !isRunning && error == null
}

@Immutable
data class ChartUiState(
    val symbol: String = "EURUSD",
    val dataProvider: DataProvider = DataProvider.DUKASCOPY,
    val timeframe: Timeframe = Timeframe.M15,
    val barMode: ChartBarMode = ChartBarMode.TIME,
    val renkoSize: Double = 10.0,
    val candles: CandleSeries = CandleSeries.EMPTY,
    val dataSource: CandleSource = CandleSource.CACHED,
    val bias: Bias = Bias.NEUTRAL,
    val structureBreaks: ImmutableList<StructureBreak> = persistentListOf(),
    val emaShort: ImmutableDoubleSeries? = null,
    val emaLong: ImmutableDoubleSeries? = null,
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
    val keltnerUpper: ImmutableDoubleSeries? = null,
    val keltnerMiddle: ImmutableDoubleSeries? = null,
    val keltnerLower: ImmutableDoubleSeries? = null,
    val donchianUpper: ImmutableDoubleSeries? = null,
    val donchianMiddle: ImmutableDoubleSeries? = null,
    val donchianLower: ImmutableDoubleSeries? = null,
    val pivotLevels: PivotPoints.PivotLevels? = null,
    val orderBlocks: ImmutableList<OrderBlock> = persistentListOf(),
    val fairValueGaps: ImmutableList<FairValueGap> = persistentListOf(),
    val liquidityPools: ImmutableList<LiquidityPool> = persistentListOf(),
    val tradeProAnalysis: TradeProAnalysis? = null,
    val litXAnalysis: LitXAnalysis? = null,
    val litAnalysis: LitAnalysis? = null,
    val smsAnalysis: SmsAnalysis? = null,
    val signalFusion: SignalFusionResult? = null,
    val smtDivergences: List<SmtDivergenceDetector.SmtDivergence> = emptyList(),
    val signals: List<ChartSignal> = emptyList(),
    val showSignalHistory: Boolean = true,
    val liveSignalStats: LiveSignalPerformanceStats = LiveSignalPerformanceStats(),
    val volumeProfile: com.foxtrader.app.domain.model.VolumeProfile? = null,
    val marketProfile: MarketProfile.ProfileResult? = null,
    val supportResistanceZones: ImmutableList<SupportResistanceDetector.SRZone> = persistentListOf(),
    val autoFibLevels: ImmutableList<FibonacciEngine.FibLevel> = persistentListOf(),
    val autoFibDirection: Direction? = null,
    val autoFibSwingHigh: Double? = null,
    val autoFibSwingLow: Double? = null,
    val sessions: ImmutableList<SessionRange> = persistentListOf(),
    val drawings: ImmutableList<ChartDrawing> = persistentListOf(),
    val drawingMode: DrawingMode = DrawingMode.NONE,
    val activeTool: DrawingToolType? = null,
    val showDrawingToolbar: Boolean = false,
    val indicators: IndicatorToggles = IndicatorToggles(),
    val showIndicatorPanel: Boolean = false,
    val showSymbolPicker: Boolean = false,
    val showCalculator: Boolean = false,
    val chartBacktest: ChartBacktestState = ChartBacktestState(),
    val strategyBlueprints: ImmutableList<StrategyBlueprint> = persistentListOf(),
    val availableSymbols: ImmutableList<String> = persistentListOf(),
    val activeWatchlistId: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val dataFreshness: MarketDataFreshness = MarketDataFreshness.CACHED,
    val dataAgeMs: Long? = null,
    val liveAvailable: Boolean = false,
    val liveEnabled: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingOlder: Boolean = false,
    val historyEndReached: Boolean = false,
    val error: String? = null,
    val aiDecision: DecisionResult? = null,
    val marketExplanation: MarketExplanation? = null,
    val confluence: ConfluenceEngine.ConfluenceResult? = null,
    val syncedCrosshairTimestamp: Long? = null,
    val rsiValues: ImmutableDoubleSeries? = null,
    val macdLine: ImmutableDoubleSeries? = null,
    val macdSignal: ImmutableDoubleSeries? = null,
    val macdHistogram: ImmutableDoubleSeries? = null,
    val stochasticK: ImmutableDoubleSeries? = null,
    val stochasticD: ImmutableDoubleSeries? = null,
    val obv: ImmutableDoubleSeries? = null,
    val moneyFlowIndex: ImmutableDoubleSeries? = null,
) {
    val lastPrice: Double? get() = candles.lastOrNull()?.close
    val hasData: Boolean get() = candles.isNotEmpty()
    val isSyntheticData: Boolean get() = hasData && dataSource == CandleSource.SYNTHETIC
    val hasSmcData: Boolean
        get() = orderBlocks.isNotEmpty() || fairValueGaps.isNotEmpty() || liquidityPools.isNotEmpty()
}
