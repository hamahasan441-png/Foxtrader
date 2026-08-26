package com.foxtrader.app.feature.backtest.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BinaryBacktestResult
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsReport
import com.foxtrader.app.domain.usecase.backtest.BacktestDateRange
import com.foxtrader.app.domain.usecase.backtest.BacktestRangePreset
import com.foxtrader.app.domain.usecase.backtest.LitXModeComparisonRunner
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Strategy templates available in the Backtesting Lab. */
enum class BacktestStrategyTemplate(
    val displayName: String,
    val description: String,
) {
    RSI_MEAN_REVERSION(
        displayName = "RSI Mean Reversion",
        description = "Trades confirmed RSI exits from oversold/overbought with ATR-adaptive risk.",
    ),
    EMA_TREND_PULLBACK(
        displayName = "EMA Trend Pullback",
        description = "Trades pullbacks in the direction of a 20/50 moving-average trend.",
    ),
    ATR_BREAKOUT(
        displayName = "ATR Breakout",
        description = "Trades volatility expansion through the recent range.",
    ),
    TRADEPRO(
        displayName = "TRADEPRO",
        description = "Order-flow/auction: Flip-Zone bias, Buy/Sell-Hold pullback + confirmation, " +
            "structural stop, trend-filtered.",
    ),
    LITX(
        displayName = "LiT Adventure",
        description = "LiT Adventure: sweep → market shift (CHOCH/MSS) → POI retest with selectable execution modes.",
    ),
    LIT_MAY_MADNESS(
        displayName = "LiT May Madness",
        description = "Canonical IDM → BOS → CHOCH → displacement → POI/SCOB first-retest execution.",
    ),
    SMT(
        displayName = "SMT",
        description = "Confirmed divergence against real correlated peer history with causal 2R execution geometry.",
    ),
    RSI_ORDERFLOW(
        displayName = "RSI Orderflow Candle",
        description = "Confirmed RSI/orderflow-proxy divergence with provider-volume coverage disclosed explicitly.",
    ),
    DERIV_BINARY_3M(
        displayName = "Deriv Binary 3m Precision",
        description = "M1 closed-bar EMA/ADX pullback-reclaim setup; enters next bar and settles after 3 minutes. Non-repainting and fixed-expiry backtestable.",
    );

    companion object {
        /** The only built-in methodologies exposed in FoxTrader's primary Lab. */
        val primaryEntries = listOf(LITX, LIT_MAY_MADNESS, SMT, RSI_ORDERFLOW)
    }
}

/** Immutable UI state for the Backtesting Lab screen. */
@Immutable
data class BacktestLabUiState(
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.H1,
    val dataProvider: DataProvider = DataProvider.DUKASCOPY,
    val strategy: BacktestStrategyTemplate = BacktestStrategyTemplate.LITX,
    /** Non-null when a saved visual-builder strategy is selected. */
    val selectedBlueprintId: String? = null,
    val strategyBlueprints: ImmutableList<StrategyBlueprint> = persistentListOf(),
    val initialBalance: Double = 100_000.0,
    val riskPercent: Double = 1.0,
    val aiScoringEnabled: Boolean = true,
    val binaryPayoutRatio: Double = 0.85,
    val binaryMinConfidence: Int = 72,
    val availableSymbols: ImmutableList<String> = DEFAULT_SYMBOLS,
    /** Research period. Presets track "now"; CUSTOM pins absolute dates. */
    val rangePreset: BacktestRangePreset = BacktestRangePreset.LOADED,
    val customStartMillis: Long? = null,
    val customEndMillis: Long? = null,
    /** Set when the provider could not supply the whole requested period. */
    val rangeNotice: String? = null,
    /** Bars actually measured, and the dates they span, after the last run. */
    val measuredBars: Int = 0,
    val measuredStartMillis: Long? = null,
    val measuredEndMillis: Long? = null,
    val isLoadingHistory: Boolean = false,
    val loadedBars: Int = 0,
    val isRunning: Boolean = false,
    val error: String? = null,
    val result: BacktestResult? = null,
    val binaryResult: BinaryBacktestResult? = null,
    val analyticsReport: BacktestAnalyticsReport? = null,
    val lastRunTime: Long = 0L,
    val replayCandles: ImmutableList<Candle> = persistentListOf(),
    val replayCursor: Int = 0,
    val replayPlaying: Boolean = false,
    val isComparingModes: Boolean = false,
    val modeComparisonCompleted: Int = 0,
    val modeComparisonTotal: Int = 0,
    val modeComparisonReport: LitXModeComparisonRunner.ComparisonReport? = null,
    val modeComparisonError: String? = null,
) {
    val hasResult: Boolean get() = result != null || binaryResult != null
    val hasReplayData: Boolean get() = hasResult && replayCandles.isNotEmpty()
    val replayProgress: Double get() = if (replayCandles.size <= 1) 0.0 else replayCursor.coerceIn(0, replayCandles.lastIndex).toDouble() / replayCandles.lastIndex.toDouble()
    val isBinary3m: Boolean get() = selectedBlueprintId == null && strategy == BacktestStrategyTemplate.DERIV_BINARY_3M
    val canCompareLitModes: Boolean get() = selectedBlueprintId == null && strategy == BacktestStrategyTemplate.LITX
    val selectedBlueprint: StrategyBlueprint?
        get() = selectedBlueprintId?.let { id -> strategyBlueprints.firstOrNull { it.id == id } }

    val selectedStrategyName: String
        get() = selectedBlueprint?.name ?: strategy.displayName

    /** The absolute period this configuration would measure, or null for all loaded bars. */
    val resolvedRange: BacktestDateRange?
        get() = BacktestDateRange.resolve(rangePreset, customStartMillis, customEndMillis)

    /** A custom range is only runnable once both ends have been chosen. */
    val customRangeIncomplete: Boolean
        get() = rangePreset.isCustom && (customStartMillis == null || customEndMillis == null)

    val canRun: Boolean get() = !isRunning && !isComparingModes && !customRangeIncomplete

    val selectedStrategyDescription: String
        get() {
            val base = selectedBlueprint?.summary() ?: strategy.description
            return if (isBinary3m) {
                base
            } else {
                "$base • Execution: closed-bar signal → next-bar-open market fill."
            }
        }

    companion object {
        val DEFAULT_SYMBOLS = persistentListOf(
            "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "XAUUSD",
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "NAS100", "US500",
            "R_10", "R_25", "R_50", "R_75", "R_100",
        )
        val DERIV_BINARY_SYMBOLS = persistentListOf(
            "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD",
            "R_10", "R_25", "R_50", "R_75", "R_100",
        )
    }
}
