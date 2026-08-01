package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.BacktestMetrics
import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestEngine
import com.foxtrader.app.domain.usecase.strategies.StrategyLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the selected [StrategyLibrary] strategy over the chart's candles and
 * produces on-chart backtest trades, aggregate metrics, and the live signal on
 * the most recent closed bar.
 *
 * Design goals (no failures / no gaps):
 * - Never crashes: all computation is wrapped and surfaced as a [StrategyComputation]
 *   with an optional non-fatal note instead of throwing.
 * - Non-repainting: the underlying [BacktestEngine] and strategy functions only
 *   read candles up to the current bar.
 * - Bounded cost: the backtest window is capped so the O(n²) indicator
 *   recomputation can't stall the chart on very long series.
 * - Debounced: recomputes only when the input fingerprint changes (symbol,
 *   timeframe, strategy, bar count, last-bar timestamp), so live ticks that
 *   merely update the forming bar don't trigger a full re-run.
 *
 * Plain class instantiated by [ChartViewModel] (not Hilt-injected directly).
 */
internal class ChartStrategyController(
    private val strategyLibrary: StrategyLibrary,
    private val backtestEngine: BacktestEngine,
    private val scope: CoroutineScope,
    private val defaultDispatcher: CoroutineDispatcher,
    private val onComputing: (Boolean) -> Unit,
    private val onResult: (StrategyComputation) -> Unit,
) {

    private var job: Job? = null
    private var lastFingerprint: String? = null

    fun compute(
        candles: List<Candle>,
        symbol: String,
        timeframe: Timeframe,
        strategy: StrategyType,
        force: Boolean = false,
    ) {
        val def = strategyLibrary.get(strategy)
        val fingerprint = buildString {
            append(symbol); append(':'); append(timeframe.label); append(':')
            append(strategy.name); append(':'); append(candles.size); append(':')
            append(candles.lastOrNull()?.timestamp ?: 0L)
        }
        if (!force && fingerprint == lastFingerprint) return
        lastFingerprint = fingerprint

        job?.cancel()

        if (candles.size < def.minimumBars) {
            onResult(
                StrategyComputation.empty(
                    "Need ${def.minimumBars}+ bars for ${strategy.label} (have ${candles.size}). " +
                        "Scroll to load more history."
                )
            )
            return
        }

        job = scope.launch {
            onComputing(true)
            try {
                val computation = withContext(defaultDispatcher) {
                    // Bound the backtest window so the O(n²) recompute stays snappy.
                    val window = if (candles.size > MAX_BACKTEST_BARS) {
                        candles.subList(candles.size - MAX_BACKTEST_BARS, candles.size)
                    } else {
                        candles
                    }
                    val offset = candles.size - window.size

                    val result = backtestEngine(window, def.function, symbol, timeframe)
                    // Re-base trade indices into full-candle coordinate space so the
                    // chart layer maps them to the correct bars.
                    val trades = result.trades.map { t ->
                        t.copy(entryIndex = t.entryIndex + offset, exitIndex = t.exitIndex + offset)
                    }

                    // Live signal on the most recent closed bar (full history, no look-ahead).
                    val live = runCatching {
                        def.function(candles, candles.lastIndex)
                    }.getOrNull()

                    StrategyComputation(
                        trades = trades,
                        metrics = result.metrics,
                        liveSignal = live,
                        note = null,
                    )
                }
                onResult(computation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(StrategyComputation.empty("Could not run ${strategy.label}: ${e.message ?: "unknown error"}"))
            } finally {
                onComputing(false)
            }
        }
    }

    /** Invalidate the cache so the next [compute] re-runs even if inputs match. */
    fun invalidate() {
        lastFingerprint = null
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Max bars fed to the backtest overlay (bounds O(n²) strategy cost). */
        const val MAX_BACKTEST_BARS = 800
    }
}

/** Result bundle pushed back to [ChartViewModel] for UI-state mapping. */
internal data class StrategyComputation(
    val trades: List<BacktestTrade>,
    val metrics: BacktestMetrics?,
    val liveSignal: StrategySignal?,
    val note: String?,
) {
    companion object {
        fun empty(note: String?) = StrategyComputation(
            trades = emptyList(),
            metrics = null,
            liveSignal = null,
            note = note,
        )
    }
}
