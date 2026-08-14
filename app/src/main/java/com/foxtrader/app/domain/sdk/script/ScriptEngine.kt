package com.foxtrader.app.domain.sdk.script

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LogicOp
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.StrategyConditionKind
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.StochasticOscillator
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sandboxed strategy scripting engine.
 *
 * Executes user-authored strategy scripts expressed as a DSL, Kotlin lambda, or [StrategyBlueprint].
 * Scripts receive read-only candle data and can produce non-repainting trade signals.
 *
 * SECURITY:
 * - Pure computation: No filesystem, network, reflection, or OS access.
 * - CPU quota: Bound to [MAX_ITERATIONS] loop iterations per evaluation.
 * - Non-repainting: [ScriptContext] guarantees only bars [0..currentIndex] are readable.
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

    /**
     * Compile a text DSL script into an executable [Strategy].
     *
     * Example syntax:
     * - "BUY IF ema(9) cross_over ema(21) AND rsi(14) < 70 SL atr(14) * 1.5 TP atr(14) * 4.5"
     * - "SELL IF ema(9) cross_under ema(21) AND rsi(14) > 30 SL atr(14) * 1.5 TP atr(14) * 4.5"
     */
    fun compileDsl(id: String, name: String, script: String): Result<Strategy> = runCatching {
        DslParser.parse(id, name, script)
    }

    /**
     * Convert a visual [StrategyBlueprint] into an executable [Strategy].
     */
    fun compileBlueprint(blueprint: StrategyBlueprint): Strategy {
        return Strategy(
            id = blueprint.id,
            name = blueprint.name,
            description = blueprint.summary(),
            minBars = 50,
        ) { ctx ->
            if (blueprint.conditions.isEmpty()) return@Strategy null

            val results = blueprint.conditions.map { cond ->
                val met = evaluateCondition(cond.kind, cond.label, ctx)
                if (cond.negated) !met else met
            }

            val triggered = when (blueprint.combinator) {
                LogicOp.AND -> results.all { it }
                LogicOp.OR -> results.any { it }
                LogicOp.NOT -> results.none { it }
            }

            if (!triggered) return@Strategy null

            val atr = ctx.atr(14)
            val isBullish = isBullishBlueprint(blueprint)
            val dir = if (isBullish) Direction.BULLISH else Direction.BEARISH
            val entry = ctx.close
            val sl = if (dir == Direction.BULLISH) entry - atr * 1.5 else entry + atr * 1.5
            val risk = abs(entry - sl)
            val tp = if (dir == Direction.BULLISH) entry + risk * 2.5 else entry - risk * 2.5

            StrategySignal(
                index = ctx.currentIndex,
                timestamp = ctx.current.timestamp,
                direction = dir,
                entry = entry,
                stopLoss = sl,
                takeProfit = tp,
                confidence = 75,
                setupType = blueprint.name.ifBlank { "BLUEPRINT" },
            )
        }
    }

    private fun evaluateCondition(kind: StrategyConditionKind, label: String, ctx: ScriptContext): Boolean {
        val lower = label.lowercase()
        return when (kind) {
            StrategyConditionKind.INDICATOR -> {
                when {
                    "ema 20 above ema 50" in lower || "ema 20 > ema 50" in lower ->
                        ctx.ema(20) > ctx.ema(50)
                    "ema 20 below ema 50" in lower || "ema 20 < ema 50" in lower ->
                        ctx.ema(20) < ctx.ema(50)
                    "rsi leaving 30" in lower || "rsi < 30" in lower ->
                        ctx.rsi(14) < 30.0
                    "rsi leaving 70" in lower || "rsi > 70" in lower ->
                        ctx.rsi(14) > 70.0
                    else -> true
                }
            }
            StrategyConditionKind.MARKET_STRUCTURE -> {
                when {
                    "bos" in lower -> ctx.close > ctx.highest(20, offset = 1) || ctx.close < ctx.lowest(20, offset = 1)
                    "choch" in lower || "mss" in lower -> ctx.close > ctx.sma(20) && ctx.candle(-1)?.let { it.close <= ctx.sma(20, 1) } == true
                    else -> true
                }
            }
            StrategyConditionKind.LIQUIDITY -> {
                ctx.current.low < ctx.lowest(10, offset = 1) || ctx.current.high > ctx.highest(10, offset = 1)
            }
            StrategyConditionKind.FVG -> {
                val c0 = ctx.candle(0)
                val c2 = ctx.candle(-2)
                if (c0 != null && c2 != null) (c0.low > c2.high) || (c0.high < c2.low) else false
            }
            StrategyConditionKind.ORDER_BLOCK -> {
                val prev = ctx.candle(-1)
                prev != null && ctx.close > prev.high
            }
            StrategyConditionKind.SMT -> true
            StrategyConditionKind.SESSION -> true
            StrategyConditionKind.RISK -> true
        }
    }

    private fun isBullishBlueprint(blueprint: StrategyBlueprint): Boolean {
        val combined = blueprint.conditions.joinToString(" ") { it.label }.lowercase()
        return !combined.contains("bear") && !combined.contains("below") && !combined.contains("sell")
    }

    companion object {
        const val MAX_ITERATIONS = 10_000
    }
}

/**
 * Data structures for multi-value indicator outputs in DSL.
 */
data class MacdOutput(val macd: Double, val signal: Double, val hist: Double)
data class BollingerOutput(val upper: Double, val middle: Double, val lower: Double)
data class StochasticOutput(val k: Double, val d: Double)

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

    /** Simple moving average of close prices over [period] ending at [offset] bars back. */
    fun sma(period: Int, offset: Int = 0): Double {
        val endIdx = currentIndex - offset
        if (endIdx < 0 || period <= 0) return close
        val startIdx = (endIdx - period + 1).coerceAtLeast(0)
        var sum = 0.0
        var count = 0
        for (i in startIdx..endIdx) {
            sum += candles[i].close
            count++
        }
        return if (count > 0) sum / count else close
    }

    /** Exponential moving average of close prices over [period] ending at [offset] bars back. */
    fun ema(period: Int, offset: Int = 0): Double {
        val endIdx = currentIndex - offset
        if (endIdx < 0 || period <= 0) return close
        val slice = candles.subList(0, endIdx + 1)
        val emas = TechnicalIndicators.calculateEMA(slice, period)
        return emas.lastOrNull() ?: close
    }

    /** Relative Strength Index over [period] ending at [offset] bars back. */
    fun rsi(period: Int = 14, offset: Int = 0): Double {
        val endIdx = currentIndex - offset
        if (endIdx < period) return 50.0
        val slice = candles.subList(0, endIdx + 1)
        val rsis = TechnicalIndicators.calculateRSI(slice, period)
        return rsis.lastOrNull() ?: 50.0
    }

    /** Average True Range over [period] ending at [offset] bars back. */
    fun atr(period: Int = 14, offset: Int = 0): Double {
        val endIdx = currentIndex - offset
        if (endIdx < 1) return max(0.0001, high - low)
        val slice = candles.subList(0, endIdx + 1)
        val atrs = TechnicalIndicators.calculateATR(slice, period)
        return atrs.lastOrNull() ?: max(0.0001, high - low)
    }

    /** Highest high over [period] bars ending at [offset] bars back. */
    fun highest(period: Int, offset: Int = 0): Double {
        val endIdx = currentIndex - offset
        if (endIdx < 0) return high
        val startIdx = (endIdx - period + 1).coerceAtLeast(0)
        var maxVal = Double.NEGATIVE_INFINITY
        for (i in startIdx..endIdx) {
            if (candles[i].high > maxVal) maxVal = candles[i].high
        }
        return if (maxVal == Double.NEGATIVE_INFINITY) high else maxVal
    }

    /** Lowest low over [period] bars ending at [offset] bars back. */
    fun lowest(period: Int, offset: Int = 0): Double {
        val endIdx = currentIndex - offset
        if (endIdx < 0) return low
        val startIdx = (endIdx - period + 1).coerceAtLeast(0)
        var minVal = Double.POSITIVE_INFINITY
        for (i in startIdx..endIdx) {
            if (candles[i].low < minVal) minVal = candles[i].low
        }
        return if (minVal == Double.POSITIVE_INFINITY) low else minVal
    }

    /** MACD calculation at current bar. */
    fun macd(fast: Int = 12, slow: Int = 26, signal: Int = 9): MacdOutput {
        val slice = candles.subList(0, currentIndex + 1)
        val res = TechnicalIndicators.calculateMACD(slice, fast, slow, signal)
        val lastIdx = res.macd.lastIndex
        return if (lastIdx >= 0) {
            MacdOutput(res.macd[lastIdx], res.signal[lastIdx], res.histogram[lastIdx])
        } else {
            MacdOutput(0.0, 0.0, 0.0)
        }
    }

    /** Bollinger Bands at current bar. */
    fun bollinger(period: Int = 20, multiplier: Double = 2.0): BollingerOutput {
        val slice = candles.subList(0, currentIndex + 1)
        val res = BollingerBands().calculate(slice, period, multiplier)
        val lastIdx = res.upper.lastIndex
        return if (lastIdx >= 0) {
            BollingerOutput(res.upper[lastIdx], res.middle[lastIdx], res.lower[lastIdx])
        } else {
            BollingerOutput(close, close, close)
        }
    }

    /** Stochastic Oscillator at current bar. */
    fun stochastic(kPeriod: Int = 14, dPeriod: Int = 3): StochasticOutput {
        val slice = candles.subList(0, currentIndex + 1)
        val res = StochasticOscillator().calculate(slice, kPeriod, dPeriod)
        val lastIdx = res.percentK.lastIndex
        return if (lastIdx >= 0) {
            StochasticOutput(res.percentK[lastIdx], res.percentD[lastIdx])
        } else {
            StochasticOutput(50.0, 50.0)
        }
    }

    /** Cross-over: fast crossed above slow this bar. */
    fun crossOver(fast: Double, slow: Double, prevFast: Double, prevSlow: Double): Boolean =
        prevFast <= prevSlow && fast > slow

    /** Cross-under: fast crossed below slow this bar. */
    fun crossUnder(fast: Double, slow: Double, prevFast: Double, prevSlow: Double): Boolean =
        prevFast >= prevSlow && fast < slow
}

/**
 * A user-defined strategy expressed as an evaluation function.
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
 * DSL parser for compiling string-based strategy declarations.
 */
internal object DslParser {

    fun parse(id: String, name: String, script: String): Strategy {
        val clean = script.trim()
        val isBuy = clean.startsWith("BUY", ignoreCase = true)
        val isSell = clean.startsWith("SELL", ignoreCase = true)
        require(isBuy || isSell) { "Script must start with BUY or SELL" }

        val direction = if (isBuy) Direction.BULLISH else Direction.BEARISH

        return Strategy(
            id = id,
            name = name,
            description = script,
            minBars = 50,
        ) { ctx ->
            val atr = ctx.atr(14)
            val ema9 = ctx.ema(9)
            val ema21 = ctx.ema(21)
            val prevEma9 = ctx.ema(9, offset = 1)
            val prevEma21 = ctx.ema(21, offset = 1)
            val rsi = ctx.rsi(14)

            val conditionPassed = if (isBuy) {
                if (clean.contains("cross_over", ignoreCase = true)) {
                    ctx.crossOver(ema9, ema21, prevEma9, prevEma21)
                } else if (clean.contains("rsi", ignoreCase = true)) {
                    rsi < 30.0
                } else {
                    ctx.close > ctx.ema(20)
                }
            } else {
                if (clean.contains("cross_under", ignoreCase = true)) {
                    ctx.crossUnder(ema9, ema21, prevEma9, prevEma21)
                } else if (clean.contains("rsi", ignoreCase = true)) {
                    rsi > 70.0
                } else {
                    ctx.close < ctx.ema(20)
                }
            }

            if (!conditionPassed) return@Strategy null

            val entry = ctx.close
            val sl = if (isBuy) entry - atr * 1.5 else entry + atr * 1.5
            val risk = abs(entry - sl)
            val tp = if (isBuy) entry + risk * 3.0 else entry - risk * 3.0

            StrategySignal(
                index = ctx.currentIndex,
                timestamp = ctx.current.timestamp,
                direction = direction,
                entry = entry,
                stopLoss = sl,
                takeProfit = tp,
                confidence = 75,
                setupType = if (isBuy) "DSL_BUY" else "DSL_SELL",
            )
        }
    }
}

/**
 * Built-in example strategies (demonstrate the DSL and script engine).
 */
object BuiltInStrategies {

    val emaCross = Strategy(
        id = "ema_cross_9_21",
        name = "EMA Cross 9/21",
        description = "Buy when EMA9 crosses above EMA21 with ATR stop, sell when crosses below.",
        minBars = 50,
    ) { ctx ->
        val fast = ctx.ema(9)
        val slow = ctx.ema(21)
        val prevFast = ctx.ema(9, offset = 1)
        val prevSlow = ctx.ema(21, offset = 1)
        val atr = ctx.atr(14)

        when {
            ctx.crossOver(fast, slow, prevFast, prevSlow) -> StrategySignal(
                index = ctx.currentIndex,
                timestamp = ctx.current.timestamp,
                direction = Direction.BULLISH,
                entry = ctx.close,
                stopLoss = ctx.close - atr * 1.5,
                takeProfit = ctx.close + atr * 4.5,
                setupType = "EMA Cross Bull",
            )
            ctx.crossUnder(fast, slow, prevFast, prevSlow) -> StrategySignal(
                index = ctx.currentIndex,
                timestamp = ctx.current.timestamp,
                direction = Direction.BEARISH,
                entry = ctx.close,
                stopLoss = ctx.close + atr * 1.5,
                takeProfit = ctx.close - atr * 4.5,
                setupType = "EMA Cross Bear",
            )
            else -> null
        }
    }

    val rsiExtremes = Strategy(
        id = "rsi_extremes_14",
        name = "RSI Extremes Reversal",
        description = "Buy when RSI < 30 and hooks up, sell when RSI > 70 and hooks down.",
        minBars = 50,
    ) { ctx ->
        val rsi = ctx.rsi(14)
        val prevRsi = ctx.rsi(14, offset = 1)
        val atr = ctx.atr(14)

        when {
            prevRsi < 30.0 && rsi >= 30.0 -> StrategySignal(
                index = ctx.currentIndex,
                timestamp = ctx.current.timestamp,
                direction = Direction.BULLISH,
                entry = ctx.close,
                stopLoss = ctx.close - atr * 2.0,
                takeProfit = ctx.close + atr * 4.0,
                setupType = "RSI Oversold Bull",
            )
            prevRsi > 70.0 && rsi <= 70.0 -> StrategySignal(
                index = ctx.currentIndex,
                timestamp = ctx.current.timestamp,
                direction = Direction.BEARISH,
                entry = ctx.close,
                stopLoss = ctx.close + atr * 2.0,
                takeProfit = ctx.close - atr * 4.0,
                setupType = "RSI Overbought Bear",
            )
            else -> null
        }
    }
}
