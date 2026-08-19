package com.foxtrader.app.feature.backtest.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Strategy templates available in the Backtesting Lab. */
enum class BacktestStrategyTemplate(
    val displayName: String,
    val description: String,
) {
    RSI_MEAN_REVERSION(
        displayName = "RSI Mean Reversion",
        description = "Fades RSI extremes with ATR-based 2R/3R exits.",
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
        displayName = "LIT X Institutional",
        description = "Sweep → market shift (CHOCH/MSS) → POI retest, gated by an 11-factor score.",
    ),
}

/** Immutable UI state for the Backtesting Lab screen. */
@Immutable
data class BacktestLabUiState(
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.H1,
    val strategy: BacktestStrategyTemplate = BacktestStrategyTemplate.RSI_MEAN_REVERSION,
    /** Non-null when a saved visual-builder strategy is selected. */
    val selectedBlueprintId: String? = null,
    val strategyBlueprints: ImmutableList<StrategyBlueprint> = persistentListOf(),
    val initialBalance: Double = 100_000.0,
    val riskPercent: Double = 1.0,
    val aiScoringEnabled: Boolean = true,
    val availableSymbols: ImmutableList<String> = DEFAULT_SYMBOLS,
    val isRunning: Boolean = false,
    val error: String? = null,
    val result: BacktestResult? = null,
    val analyticsReport: BacktestAnalyticsReport? = null,
    val lastRunTime: Long = 0L,
) {
    val hasResult: Boolean get() = result != null
    val selectedBlueprint: StrategyBlueprint?
        get() = selectedBlueprintId?.let { id -> strategyBlueprints.firstOrNull { it.id == id } }

    val selectedStrategyName: String
        get() = selectedBlueprint?.name ?: strategy.displayName

    val selectedStrategyDescription: String
        get() = selectedBlueprint?.summary() ?: strategy.description

    companion object {
        val DEFAULT_SYMBOLS = persistentListOf(
            "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "XAUUSD",
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "NAS100", "US500",
        )
    }
}
