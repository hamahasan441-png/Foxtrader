package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.Candle

/**
 * Shared machinery for running a backtest over a selected historical window.
 *
 * Extracted so every caller — the on-chart research runner, the Lab's plain
 * engine path, and the Lab's AI-scored path — applies the *same* three rules:
 *
 * 1. The strategy still sees the causal prefix `[0..i]`, so indicator and
 *    structure warm-up is identical to what live code would have had.
 * 2. Entries are admitted only inside the window.
 * 3. The series is truncated at the window end, so no bar after the selected
 *    period can influence a fill, an exit, or a metric.
 *
 * Duplicating these rules per call site is how a windowed backtest quietly
 * starts disagreeing with the chart it is supposed to mirror.
 */
object WindowedBacktest {

    /** The causal series a windowed run must be given: bar 0 through window end. */
    fun causalSeries(candles: List<Candle>, window: HistoricalTestWindow): List<Candle> =
        candles.subList(0, window.endIndex + 1)

    /** Wrap [strategy] so it can only produce entries inside [window]. */
    fun guard(strategy: StrategyFunction, window: HistoricalTestWindow): StrategyFunction =
        { prefix, index ->
            if (index < window.startIndex || index > window.endIndex) null else strategy(prefix, index)
        }

    /**
     * Restrict a raw engine result to the measured window.
     *
     * The equity curve is clipped to the window and the reported dates are the
     * window's own bounds, so a "March 2024" report never claims to start on
     * whatever bar the warm-up prefix happened to begin at.
     */
    fun finalize(
        result: BacktestResult,
        candles: List<Candle>,
        window: HistoricalTestWindow,
    ): BacktestResult {
        val startTs = candles[window.startIndex].timestamp
        val endTs = candles[window.endIndex].timestamp
        return result.copy(
            equityCurve = result.equityCurve.filter { it.index in window.startIndex..window.endIndex },
            startDate = startTs,
            endDate = endTs,
            durationDays = (endTs - startTs).coerceAtLeast(0L) / MILLIS_PER_DAY.toDouble(),
        )
    }

    /** Validate a window against a series, throwing the caller-facing message. */
    fun requireUsable(candles: List<Candle>, window: HistoricalTestWindow): HistoricalTestWindow {
        require(candles.size >= 2) { "Historical test requires at least 2 candles." }
        val selected = window.clampTo(candles)
        require(selected.barCount >= 2) { "Historical test range must contain at least 2 bars." }
        return selected
    }

    const val MILLIS_PER_DAY = 86_400_000L
}
