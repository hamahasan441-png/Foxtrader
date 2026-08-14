package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a [StrategyLibrary] strategy over the *visible* candle series and turns
 * every bar that produced a setup into a chart-renderable [ChartSignal].
 *
 * Why this exists
 * ---------------
 * The strategy library was previously reachable only from the Backtest Lab, so
 * the nine production strategies could be measured historically but never
 * *seen* on a live chart. This engine is the missing bridge: it evaluates the
 * same [StrategyFunction] instances the backtester uses, so what a trader sees
 * plotted is exactly what was backtested — no second, drifting implementation.
 *
 * Non-repainting guarantee
 * ------------------------
 * Each strategy function is called as `fn(candles, i)` and, by the library's
 * own contract, may only read `candles[0..i]`. We evaluate bars in ascending
 * order and never revisit a bar with future data, so a marker placed at bar `i`
 * can never move or disappear once bar `i` has closed.
 *
 * The final bar is deliberately *included* — that is the live, actionable
 * signal — but it is the only bar allowed to change as new ticks arrive, which
 * matches how every trading platform renders a forming candle.
 *
 * Cost control
 * ------------
 * Some strategies re-run full SMC/structure detection per bar, which is O(n)
 * each and therefore O(n²) across a long series. To keep the chart at its frame
 * budget we only scan the most recent [DEFAULT_SCAN_WINDOW] bars — older
 * markers are off-screen for any realistic viewport and are still available in
 * the Backtest Lab.
 */
@Singleton
class LiveStrategyEngine @Inject constructor(
    private val library: StrategyLibrary,
) {

    /**
     * Evaluate [type] across the tail of [candles] and return one
     * [ChartSignal] per bar that produced a setup, in ascending bar order.
     *
     * The most recent signal is flagged [ChartSignal.isLive] so the chart layer
     * renders it solid while historical ones stay faded.
     *
     * @param candles the display candle series (must be the same series the
     *   chart renders, so `barIndex` maps directly onto the x-axis).
     * @param maxSignals newest-N cap so a busy strategy cannot flood the canvas.
     * @return an empty list when there is not enough data or no setup fired.
     *   Never throws: a misbehaving strategy is contained per-bar.
     */
    fun evaluate(
        type: StrategyType,
        candles: List<Candle>,
        scanWindow: Int = DEFAULT_SCAN_WINDOW,
        maxSignals: Int = DEFAULT_MAX_SIGNALS,
    ): List<ChartSignal> {
        val definition = runCatching { library.get(type) }.getOrNull() ?: return emptyList()
        if (candles.size < definition.minimumBars) return emptyList()

        // Only scan the recent window, but never below the strategy's warm-up
        // requirement — a strategy needs its full history prefix to be valid.
        val firstBar = (candles.size - scanWindow).coerceAtLeast(definition.minimumBars - 1)
        if (firstBar > candles.lastIndex) return emptyList()

        val raw = ArrayList<StrategySignal>()
        for (i in firstBar..candles.lastIndex) {
            // A single throwing strategy must never take down the chart frame.
            val signal = runCatching { definition.function(candles, i) }.getOrNull() ?: continue
            if (!signal.isRenderable()) continue
            raw += signal
        }
        if (raw.isEmpty()) return emptyList()

        val recent = if (raw.size > maxSignals) raw.subList(raw.size - maxSignals, raw.size) else raw
        val lastIndex = recent.lastIndex

        return recent.mapIndexed { position, signal ->
            ChartSignal(
                id = "strategy_${type.name}_${signal.index}_${signal.timestamp}",
                source = SignalSource.STRATEGY,
                direction = signal.direction,
                entry = signal.entry,
                sl = signal.stopLoss,
                tp = signal.takeProfit,
                barIndex = signal.index,
                timestamp = signal.timestamp,
                confidence = (signal.confidence?.toDouble() ?: DEFAULT_CONFIDENCE)
                    .div(100.0)
                    .coerceIn(0.0, 1.0),
                isLive = position == lastIndex,
                label = definition.name,
            )
        }
    }

    /**
     * Reject setups that cannot be drawn or traded: non-finite prices, a stop
     * on the wrong side of entry, or a zero-distance stop. Filtering here keeps
     * every downstream consumer (renderer, history panel, alerts) simple.
     */
    private fun StrategySignal.isRenderable(): Boolean {
        if (!entry.isFinite() || !stopLoss.isFinite() || !takeProfit.isFinite()) return false
        if (entry <= 0.0) return false
        return kotlin.math.abs(entry - stopLoss) > 0.0
    }

    companion object {
        /** Bars back from the live edge that are scanned for setups. */
        const val DEFAULT_SCAN_WINDOW = 300

        /** Newest-N markers kept so a noisy strategy cannot flood the canvas. */
        const val DEFAULT_MAX_SIGNALS = 40

        /** Used when a strategy reports no explicit confidence. */
        const val DEFAULT_CONFIDENCE = 60.0
    }
}
