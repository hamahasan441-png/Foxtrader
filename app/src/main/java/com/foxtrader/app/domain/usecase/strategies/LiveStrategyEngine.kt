package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

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
     * A signal is flagged [ChartSignal.isLive] only when it belongs to the
     * chart's current forming bar; the newest historical setup stays historical.
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
        val definition = try {
            library.get(type)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return emptyList()
        }
        return evaluateDefinition(
            strategyId = type.name,
            strategyName = definition.name,
            minimumBars = definition.minimumBars,
            function = definition.function,
            candles = candles,
            scanWindow = scanWindow,
            maxSignals = maxSignals,
        )
    }

    /**
     * Evaluate a user-authored/compiled strategy with the same containment,
     * validation, scan-window, and marker semantics as built-in strategies.
     */
    fun evaluateCustom(
        strategyId: String,
        strategyName: String,
        minimumBars: Int,
        function: StrategyFunction,
        candles: List<Candle>,
        scanWindow: Int = DEFAULT_SCAN_WINDOW,
        maxSignals: Int = DEFAULT_MAX_SIGNALS,
    ): List<ChartSignal> = evaluateDefinition(
        strategyId = "custom_$strategyId",
        strategyName = strategyName,
        minimumBars = minimumBars,
        function = function,
        candles = candles,
        scanWindow = scanWindow,
        maxSignals = maxSignals,
    )

    private fun evaluateDefinition(
        strategyId: String,
        strategyName: String,
        minimumBars: Int,
        function: StrategyFunction,
        candles: List<Candle>,
        scanWindow: Int,
        maxSignals: Int,
    ): List<ChartSignal> {
        val safeMinimumBars = minimumBars.coerceAtLeast(1)
        val safeScanWindow = scanWindow.coerceAtLeast(1)
        val safeMaxSignals = maxSignals.coerceAtLeast(0)
        if (safeMaxSignals == 0 || candles.size < safeMinimumBars) return emptyList()

        // Only scan the recent window, but never below the strategy's warm-up
        // requirement — a strategy needs its full history prefix to be valid.
        val firstBar = (candles.size - safeScanWindow).coerceAtLeast(safeMinimumBars - 1)
        if (firstBar > candles.lastIndex) return emptyList()

        val raw = ArrayList<StrategySignal>()
        for (i in firstBar..candles.lastIndex) {
            // Pass a prefix view, exactly like BacktestEngine, so even a
            // third-party StrategyFunction cannot inspect future bars.
            // A single throwing strategy must never take down the chart frame.
            val visiblePrefix = candles.subList(0, i + 1)
            val signal = try {
                function(visiblePrefix, i)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            } ?: continue
            if (!signal.isRenderable(expectedIndex = i, expectedTimestamp = candles[i].timestamp)) continue
            raw += signal
        }
        if (raw.isEmpty()) return emptyList()

        val recent = if (raw.size > safeMaxSignals) {
            raw.subList(raw.size - safeMaxSignals, raw.size)
        } else {
            raw
        }

        return recent.map { signal ->
            ChartSignal(
                id = "strategy_${strategyId}_${signal.index}_${signal.timestamp}",
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
                // A historical setup is not live merely because it is the
                // newest setup found. Only a signal on the current forming bar
                // may participate in live confluence or alerting.
                isLive = signal.index == candles.lastIndex,
                label = strategyName,
            )
        }
    }

    /**
     * Evaluates every strategy over the visible series and merges their signals
     * into a single ascending list.
     *
     * Intended for the chart's "All strategies" view. To keep the canvas from
     * being flooded, the scan is bounded to a short window and each strategy is
     * capped at a small number of newest signals (see constants). A throwing
     * strategy is contained and skipped rather than failing the whole view.
     */
    fun evaluateAll(
        candles: List<Candle>,
        scanWindow: Int = ALL_STRATEGY_SCAN_WINDOW,
        maxSignalsPerStrategy: Int = ALL_STRATEGY_MAX_SIGNALS_PER_STRATEGY,
    ): List<ChartSignal> {
        val definitions = library.all()
        val merged = ArrayList<ChartSignal>(maxSignalsPerStrategy.coerceAtLeast(0) * definitions.size)
        for ((type, _) in definitions) {
            val signals = try {
                evaluate(type, candles, scanWindow, maxSignalsPerStrategy)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
            merged += signals
        }
        return merged.sortedBy { it.barIndex }
    }

    /**
     * Reject setups that cannot be drawn or traded: non-finite prices, a stop
     * on the wrong side of entry, or a zero-distance stop. Filtering here keeps
     * every downstream consumer (renderer, history panel, alerts) simple.
     */
    private fun StrategySignal.isRenderable(expectedIndex: Int, expectedTimestamp: Long): Boolean {
        if (index != expectedIndex || timestamp != expectedTimestamp) return false
        if (!entry.isFinite() || !stopLoss.isFinite() || !takeProfit.isFinite()) return false
        if (entry <= 0.0 || stopLoss <= 0.0 || takeProfit <= 0.0) return false
        if (kotlin.math.abs(entry - stopLoss) <= 0.0) return false
        return when (direction) {
            Direction.BULLISH -> stopLoss < entry && takeProfit > entry
            Direction.BEARISH -> stopLoss > entry && takeProfit < entry
        }
    }

    companion object {
        /** Bars back from the live edge that are scanned for setups. */
        const val DEFAULT_SCAN_WINDOW = 300

        /** Newest-N markers kept so a noisy strategy cannot flood the canvas. */
        const val DEFAULT_MAX_SIGNALS = 40

        /**
         * Bars back from the live edge scanned in "all strategies" mode. Kept
         * deliberately short (180) because nine strategies run per bar.
         */
        const val ALL_STRATEGY_SCAN_WINDOW = 180

        /** Per-strategy marker cap in "all strategies" mode. */
        const val ALL_STRATEGY_MAX_SIGNALS_PER_STRATEGY = 12

        /** Used when a strategy reports no explicit confidence. */
        const val DEFAULT_CONFIDENCE = 60.0
    }
}
