package com.foxtrader.app.feature.backtest.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BinaryBacktestResult
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsReport
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
        displayName = "RSI Orderflow Divergence",
        description = "Confirmed RSI/orderflow-proxy divergence with provider-volume coverage disclosed explicitly.",
    ),
    RSI_REVERSAL(
        displayName = "RSI Orderflow Reversal",
        description = "Price extreme RSI refused to confirm, armed after an RSI structure break, " +
            "entered on a lower-timeframe sweep/CHOCH with a fixed 4R target. The selected timeframe " +
            "is the entry timeframe; the context timeframe is reconstructed from it by resampling.",
    ),
    LIQUIDITY_SWEEP(
        displayName = "Liquidity Sweep",
        description = "Higher-timeframe bias, a marked liquidity level taken and reclaimed, entered on the " +
            "reaction with the stop behind the swept extreme. The two timeframes above the chart are derived " +
            "from it by resampling, so no second data feed can disagree with what the chart showed.",
    ),
    VIRGIN_WICK(
        displayName = "Virgin Wick",
        description = "An untested wick the context timeframe closed away from becomes a zone; price returning " +
            "to it and an inverted fair value gap confirming the rejection is the trade. Stop behind the safer " +
            "of the inversion and the entry bar; target the next untested wick, or a fixed multiple when that " +
            "draw is out of reach.",
    ),
    NASCENT(
        displayName = "Nascent FX Primary Analysis",
        description = "External key level (ILQ/SLQ/TLQ or EPA+DP) gating an internal MSU / EPA+DP setup, " +
            "entered on a closed-bar sweep, engulf or 50% direct pullback.",
    ),
    AMD(
        displayName = "AMD (Accumulation/Manipulation/Distribution)",
        description = "Range compression, a liquidity sweep beyond it, then a displaced reversal back through the whole range. Structural — works on every timeframe.",
    ),
    APEX(
        displayName = "Apex Consensus",
        description = "Several methodologies must independently reach the same trade within a short window; " +
            "the engine then measures its own resolved trades and publishes only while that record supports " +
            "the required hit rate. Backtesting it shows what the gate actually did — including the long " +
            "stretches where it correctly published nothing at all.",
    ),
    COMPASS(
        displayName = "Compass Accuracy",
        description = "Scores every call the other engines make and publishes only those clearing an accuracy " +
            "threshold earned on calls already proved right or wrong. The barrier is the same distance on both " +
            "sides, so the figure is direction alone and cannot be improved by moving a target closer; accuracy " +
            "is always judged against what a constant-direction rule would have scored on the same bars.",
    ),
    CRUCIBLE(
        displayName = "Crucible Discovery",
        description = "Searches thousands of candidate conditions and keeps only those that survive purged " +
            "out-of-sample testing, a false-discovery correction for the size of the search, an effective-sample " +
            "deflation for overlapping outcomes, and a measurement of how often the search's own winner fails " +
            "out of sample. Publishes nothing when that measurement says the search cannot be trusted.",
    ),
    DERIV_BINARY_3M(
        displayName = "Deriv Binary 3m Precision",
        description = "M1 closed-bar EMA/ADX pullback-reclaim setup; enters next bar and settles after 3 minutes. Non-repainting and fixed-expiry backtestable.",
    );

    companion object {
        /** The only built-in methodologies exposed in FoxTrader's primary Lab. */
        val primaryEntries = listOf(LITX, LIT_MAY_MADNESS, SMT, RSI_ORDERFLOW, RSI_REVERSAL, AMD, NASCENT, LIQUIDITY_SWEEP, VIRGIN_WICK, APEX, COMPASS, CRUCIBLE)
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
