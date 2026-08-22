package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Runs production strategies over the visible confirmed candle series and turns
 * every valid setup into an on-chart [ChartSignal].
 *
 * The same StrategyFunction is used by live charting and backtesting. Every
 * strategy and every evaluated bar is isolated so an invalid custom/built-in
 * implementation can only lose its own marker; it cannot crash the chart or
 * suppress unrelated strategies.
 */
@Singleton
class LiveStrategyEngine @Inject constructor(
    private val library: StrategyLibrary,
) {

    fun evaluate(
        type: StrategyType,
        candles: List<Candle>,
        scanWindow: Int = DEFAULT_SCAN_WINDOW,
        maxSignals: Int = DEFAULT_MAX_SIGNALS,
        symbol: String = "",
        timeframe: Timeframe = Timeframe.H1,
    ): List<ChartSignal> {
        val definition = try {
            library.get(type, symbol, timeframe)
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

        val firstBar = (candles.size - safeScanWindow).coerceAtLeast(safeMinimumBars - 1)
        if (firstBar > candles.lastIndex) return emptyList()

        val raw = ArrayList<StrategySignal>()
        for (i in firstBar..candles.lastIndex) {
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
        } else raw

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
                isLive = signal.index == candles.lastIndex,
                label = strategyName,
            )
        }
    }

    /**
     * Evaluates all built-in strategies independently.
     *
     * Do not call StrategyLibrary.all() here: constructing the whole map as one
     * operation means one broken strategy definition can prevent every other
     * strategy from rendering. Enumerating [StrategyType.entries] and delegating
     * to [evaluate] gives each definition its own failure boundary.
     */
    fun evaluateAll(
        candles: List<Candle>,
        scanWindow: Int = ALL_STRATEGY_SCAN_WINDOW,
        maxSignalsPerStrategy: Int = ALL_STRATEGY_MAX_SIGNALS_PER_STRATEGY,
        symbol: String = "",
        timeframe: Timeframe = Timeframe.H1,
    ): List<ChartSignal> {
        val safeMax = maxSignalsPerStrategy.coerceAtLeast(0)
        if (safeMax == 0 || candles.isEmpty()) return emptyList()

        val merged = ArrayList<ChartSignal>(safeMax * StrategyType.entries.size)
        for (type in StrategyType.entries) {
            val signals = try {
                evaluate(
                    type = type,
                    candles = candles,
                    scanWindow = scanWindow,
                    maxSignals = safeMax,
                    symbol = symbol,
                    timeframe = timeframe,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
            merged += signals
        }
        return merged.sortedWith(compareBy<ChartSignal> { it.barIndex }.thenBy { it.source.name }.thenBy { it.id })
    }

    private fun StrategySignal.isRenderable(expectedIndex: Int, expectedTimestamp: Long): Boolean {
        if (index != expectedIndex || timestamp != expectedTimestamp) return false
        if (!entry.isFinite() || !stopLoss.isFinite() || !takeProfit.isFinite()) return false
        if (entry <= 0.0 || stopLoss <= 0.0 || takeProfit <= 0.0) return false
        if (kotlin.math.abs(entry - stopLoss) <= MIN_PRICE_DISTANCE) return false
        return when (direction) {
            Direction.BULLISH -> stopLoss < entry && takeProfit > entry
            Direction.BEARISH -> stopLoss > entry && takeProfit < entry
        }
    }

    companion object {
        const val DEFAULT_SCAN_WINDOW = 300
        const val DEFAULT_MAX_SIGNALS = 40
        const val ALL_STRATEGY_SCAN_WINDOW = 180
        const val ALL_STRATEGY_MAX_SIGNALS_PER_STRATEGY = 12
        const val DEFAULT_CONFIDENCE = 60.0
        private const val MIN_PRICE_DISTANCE = 1e-12
    }
}
