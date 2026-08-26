package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestConfig
import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject

/**
 * Runs the production [BacktestEngine] over a user-selected historical window
 * without sacrificing indicator/structure warm-up.
 *
 * The engine receives candles from the beginning of the loaded series through
 * the selected end bar. A guarded strategy blocks entries before the selected
 * start bar. Therefore every strategy evaluation still sees the exact causal
 * prefix [0..i], while trades and report dates belong only to the selected
 * research window. No candle after the selected end can affect the result.
 */
class HistoricalBacktestRunner @Inject constructor(
    private val backtestEngine: BacktestEngine,
) {
    operator fun invoke(
        candles: List<Candle>,
        strategy: StrategyFunction,
        window: HistoricalTestWindow,
        symbol: String,
        timeframe: Timeframe,
        config: BacktestConfig = BacktestConfig(),
    ): BacktestResult {
        val selected = WindowedBacktest.requireUsable(candles, window)

        // Historical-range mode must use the exact execution/risk model selected
        // by its caller. BacktestEngine is stateful by configuration and Hilt is
        // free to provide this runner a different engine instance than a screen.
        backtestEngine.updateConfig(config)
        val result = backtestEngine(
            candles = WindowedBacktest.causalSeries(candles, selected),
            strategy = WindowedBacktest.guard(strategy, selected),
            symbol = symbol,
            timeframe = timeframe,
        )

        return WindowedBacktest.finalize(result, candles, selected)
    }
}
