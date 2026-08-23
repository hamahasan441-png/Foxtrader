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
        require(candles.size >= 2) { "Historical test requires at least 2 candles." }
        val selected = window.clampTo(candles)
        require(selected.barCount >= 2) { "Historical test range must contain at least 2 bars." }

        val causalSeries = candles.subList(0, selected.endIndex + 1)
        val guarded: StrategyFunction = { prefix, index ->
            if (index < selected.startIndex || index > selected.endIndex) {
                null
            } else {
                strategy(prefix, index)
            }
        }

        // Historical-range mode must use the exact execution/risk model selected
        // by its caller. BacktestEngine is stateful by configuration and Hilt is
        // free to provide this runner a different engine instance than a screen.
        backtestEngine.updateConfig(config)
        val result = backtestEngine(
            candles = causalSeries,
            strategy = guarded,
            symbol = symbol,
            timeframe = timeframe,
        )

        val selectedEquity = result.equityCurve.filter { it.index in selected.startIndex..selected.endIndex }
        val startTs = candles[selected.startIndex].timestamp
        val endTs = candles[selected.endIndex].timestamp

        return result.copy(
            equityCurve = selectedEquity,
            startDate = startTs,
            endDate = endTs,
            durationDays = (endTs - startTs).coerceAtLeast(0L) / MILLIS_PER_DAY.toDouble(),
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
