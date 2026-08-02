package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.TradeProBacktestResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Immutable UI state for the TRADEPRO Backtest Report screen.
 *
 * The report replays the full TRADEPRO lifecycle (signal -> 3-contract T1/T2/runner
 * management -> exit) over sourced history via [com.foxtrader.app.domain.usecase.tradepro.TradeProBacktestEngine].
 */
@Immutable
data class TradeProBacktestReportUiState(
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.H1,
    val availableSymbols: ImmutableList<String> = DEFAULT_SYMBOLS,
    val isRunning: Boolean = false,
    val error: String? = null,
    val result: TradeProBacktestResult? = null,
    /** True when the backtested candles were flagged synthetic — the report is then illustrative only. */
    val isSynthetic: Boolean = false,
) {
    val hasResult: Boolean get() = result != null

    companion object {
        val DEFAULT_SYMBOLS: ImmutableList<String> = persistentListOf(
            "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "XAUUSD",
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "NAS100", "US500",
        )
    }
}
