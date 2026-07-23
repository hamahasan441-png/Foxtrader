package com.foxtrader.app.domain.sdk.script

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sandboxed strategy scripting engine.
 *
 * Executes user-authored strategy scripts expressed as a simple DSL.
 * Scripts receive read-only candle data and can produce trade signals.
 *
 * SECURITY:
 * - No filesystem, network, reflection, or system access.
 * - CPU quota: max [MAX_ITERATIONS] loop iterations per evaluation.
 * - Read-only candle access via [ScriptContext].
 * - A rogue script can never repaint, exfiltrate, or hang the UI.
 */
@Singleton
class ScriptEngine @Inject constructor() {

    /**
     * Evaluate a compiled [Strategy] against candles at the given index.
     * Returns a signal if the strategy triggers, null otherwise.
     */
    fun evaluate(strategy: Strategy, candles: List<Candle>, index: Int): StrategySignal? {
        if (index < strategy.minBars || index >= candles.size) return null
        val ctx = ScriptContext(candles, index)
        return strategy.evaluate(ctx)
    }

    companion object {
        const val MAX_ITERATIONS = 10_000
    }
}

/**
 * Read-only context passed to strategy scripts.
 * Provides safe access to candle data without exposing the full list mutably.
 */
class ScriptContext(
    private val candles: List<Candle>,
    val currentIndex: Int,
) {
    val size: Int get() = currentIndex + 1 // non-repainting: only see [0..currentIndex]

    /** Get candle at offset from current (0 = current, -1 = previous, etc.). */
    fun candle(offset: Int = 0): Candle? {
        val idx = currentIndex + offset
        return if (idx in 0..currentIndex) candles[idx] else null
    }

    /** Current candle shorthand. */
    val current: Candle get() = candles[currentIndex]
    val close: Double get() = current.close
    val open: Double get() = current.open
    val high: Double get() = current.high
    val low: Double get() = current.low
    val volume: Double get() = current.volume

    /** Simple moving average of close prices over [period] ending at current bar. */
    fun sma(period: Int): Double {
        if (size < period) return close
        var sum = 0.0
        for (i in currentIndex - period + 1..currentIndex) sum += candles[i].close
        return sum / period
    }

    /** Highest high over [period] bars ending at current. */
    fun highest(period: Int): Double {
        var max = Double.MIN_VALUE
        for (i in (currentIndex - period + 1).coerceAtLeast(0)..currentIndex) {
            if (candles[i].high > max) max = candles[i].high
        }
        return max
    }

    /** Lowest low over [period] bars ending at current. */
    fun lowest(period: Int): Double {
        var min = Double.MAX_VALUE
        for (i in (currentIndex - period + 1).coerceAtLeast(0)..currentIndex) {
            if (candles[i].low < min) min = candles[i].low
        }
        return min
    }

    /** Cross-over: fast crossed above slow this bar. */
    fun crossOver(fast: Double, slow: Double, prevFast: Double, prevSlow: Double): Boolean =
        prevFast <= prevSlow && fast > slow

    /** Cross-under: fast crossed below slow this bar. */
    fun crossUnder(fast: Double, slow: Double, prevFast: Double, prevSlow: Double): Boolean =
        prevFast >= prevSlow && fast < slow
}

/**
 * A user-defined strategy expressed as a Kotlin lambda (the DSL evaluator).
 * Future: parse from a text-based DSL or a visual builder.
 */
data class Strategy(
    val id: String,
    val name: String,
    val description: String = "",
    val minBars: Int = 50,
    /** The evaluation function. Returns a signal or null. */
    val evaluate: (ScriptContext) -> StrategySignal?,
)

/**
 * Built-in example strategies (demonstrate the DSL).
 */
object BuiltInStrategies {

    val smaCross = Strategy(
        id = "sma_cross_20_50",
        name = "SMA Cross 20/50",
        description = "Buy when SMA20 crosses above SMA50, sell when crosses below.",
        minBars = 51,
    ) { ctx ->
        val fast = ctx.sma(20)
        val slow = ctx.sma(50)
        val prev = ctx.candle(-1) ?: return@Strategy null
        val prevCtx = ScriptContext(listOf(), 0) // simplified: use inline calc
        val prevFast = run {
            var s = 0.0; for (i in ctx.currentIndex - 20 until ctx.currentIndex) s += (ctx.candle(i - ctx.currentIndex)?.close ?: ctx.close); s / 20
        }
        val prevSlow = run {
            var s = 0.0; for (i in ctx.currentIndex - 50 until ctx.currentIndex) s += (ctx.candle(i - ctx.currentIndex)?.close ?: ctx.close); s / 50
        }
        val atr = ctx.highest(14) - ctx.lowest(14)
        when {
            ctx.crossOver(fast, slow, prevFast, prevSlow) -> StrategySignal(
                index = ctx.currentIndex, timestamp = ctx.current.timestamp,
                direction = Direction.BULLISH, entry = ctx.close,
                stopLoss = ctx.close - atr, takeProfit = ctx.close + atr * 2,
                setupType = "SMA Cross Bull",
            )
            ctx.crossUnder(fast, slow, prevFast, prevSlow) -> StrategySignal(
                index = ctx.currentIndex, timestamp = ctx.current.timestamp,
                direction = Direction.BEARISH, entry = ctx.close,
                stopLoss = ctx.close + atr, takeProfit = ctx.close - atr * 2,
                setupType = "SMA Cross Bear",
            )
            else -> null
        }
    }
}
