package com.foxtrader.app.domain.sdk.script

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LogicOp
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.StrategyConditionKind
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.StochasticOscillator
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.max

/**
 * Strategy scripting engine.
 *
 * Executes user-authored strategy scripts expressed as a DSL, Kotlin lambda, or [StrategyBlueprint].
 * Scripts receive read-only candle data and can produce non-repainting trade signals.
 *
 * Generated DSL/blueprint strategies are pure computations over [ScriptContext],
 * and exceptions fail closed. A caller-supplied Kotlin [Strategy] lambda is
 * trusted in-process code, not an OS/security sandbox and not pre-emptible.
 * Non-repainting access is enforced by exposing only bars [0..currentIndex].
 */
@Singleton
class ScriptEngine @Inject constructor() {

    /**
     * Evaluate a compiled [Strategy] against candles at the given index.
     * Returns a signal if the strategy triggers, null otherwise.
     */
    fun evaluate(strategy: Strategy, candles: List<Candle>, index: Int): StrategySignal? {
        if (index !in candles.indices || index < strategy.minBars.coerceAtLeast(0)) return null
        val ctx = ScriptContext(candles, index)
        return try {
            strategy.evaluate(ctx)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // User-authored logic is contained like any other plugin boundary:
            // one bad rule returns no setup instead of crashing chart/backtest.
            null
        }
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
            // The current builder exposes AND/OR only. Legacy/custom payloads
            // can still contain unary negation or the old NOT combinator, whose
            // directional meaning is ambiguous (NOT bullish is not proof of a
            // bearish setup). Reject those states instead of reversing a trade.
            if (blueprint.combinator == LogicOp.NOT || blueprint.conditions.any { it.negated }) {
                return@Strategy null
            }
            if (
                !blueprint.action.riskPercent.isFinite() ||
                blueprint.action.riskPercent <= 0.0 ||
                blueprint.action.riskPercent > MAX_BLUEPRINT_RISK_PERCENT
            ) return@Strategy null

            val results = blueprint.conditions.map { condition ->
                evaluateCondition(
                    kind = condition.kind,
                    label = condition.label,
                    ctx = ctx,
                    riskPercent = blueprint.action.riskPercent,
                )
            }

            // Evaluate bullish and bearish paths independently. The old
            // implementation collapsed directional rules into one Boolean and
            // then guessed BUY unless a label happened to contain "sell". A
            // bearish BOS/liquidity sweep could therefore emit a BUY. Ambiguous
            // or directionless blueprints now fail closed.
            val bullish = combine(results.map { it.bullish }, blueprint.combinator)
            val bearish = combine(results.map { it.bearish }, blueprint.combinator)
            val hasDirectionalEvidence = results.any { it.directional && it.supported }
            if (!hasDirectionalEvidence || bullish == bearish) return@Strategy null

            val atr = ctx.atr(14)
            if (!atr.isFinite() || atr <= MIN_RISK_DISTANCE) return@Strategy null

            val dir = if (bullish) Direction.BULLISH else Direction.BEARISH
            val entry = ctx.close
            if (!entry.isFinite() || entry <= 0.0) return@Strategy null
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
                confidence = (BASE_BLUEPRINT_CONFIDENCE + results.count { it.supported } * CONFIDENCE_PER_RULE)
                    .coerceAtMost(MAX_BLUEPRINT_CONFIDENCE),
                setupType = blueprint.name.ifBlank { "BLUEPRINT" },
            )
        }
    }

    /**
     * Evaluate one visual-builder condition for both possible trade directions.
     *
     * Returning [DirectionalMatch.unsupported] is intentionally different from
     * returning a false match: unsupported/unknown labels can never make an AND,
     * OR, or negated blueprint fire by accident.
     */
    private fun evaluateCondition(
        kind: StrategyConditionKind,
        label: String,
        ctx: ScriptContext,
        riskPercent: Double,
    ): DirectionalMatch {
        val lower = label.lowercase()
        return when (kind) {
            StrategyConditionKind.INDICATOR -> {
                when {
                    "ema 20 above ema 50" in lower || "ema 20 > ema 50" in lower ->
                        DirectionalMatch.directional(bullish = ctx.ema(20) > ctx.ema(50))
                    "ema 20 below ema 50" in lower || "ema 20 < ema 50" in lower ->
                        DirectionalMatch.directional(bearish = ctx.ema(20) < ctx.ema(50))
                    "rsi leaving 30/70" in lower -> {
                        val current = ctx.rsi(14)
                        val previous = ctx.rsi(14, offset = 1)
                        DirectionalMatch.directional(
                            bullish = previous <= RSI_OVERSOLD && current > RSI_OVERSOLD,
                            bearish = previous >= RSI_OVERBOUGHT && current < RSI_OVERBOUGHT,
                        )
                    }
                    "rsi leaving 30" in lower || "rsi < 30" in lower ->
                        DirectionalMatch.directional(bullish = ctx.rsi(14) < RSI_OVERSOLD)
                    "rsi leaving 70" in lower || "rsi > 70" in lower ->
                        DirectionalMatch.directional(bearish = ctx.rsi(14) > RSI_OVERBOUGHT)
                    else -> DirectionalMatch.unsupported()
                }
            }
            StrategyConditionKind.MARKET_STRUCTURE -> {
                when {
                    "bos" in lower -> DirectionalMatch.directional(
                        bullish = ctx.close > ctx.highest(STRUCTURE_LOOKBACK, offset = 1),
                        bearish = ctx.close < ctx.lowest(STRUCTURE_LOOKBACK, offset = 1),
                    )
                    "choch" in lower || "mss" in lower -> {
                        val previousClose = ctx.candle(-1)?.close ?: return DirectionalMatch.directional()
                        DirectionalMatch.directional(
                            bullish = ctx.close > ctx.sma(20) && previousClose <= ctx.sma(20, offset = 1),
                            bearish = ctx.close < ctx.sma(20) && previousClose >= ctx.sma(20, offset = 1),
                        )
                    }
                    else -> DirectionalMatch.unsupported()
                }
            }
            StrategyConditionKind.LIQUIDITY -> {
                val previousLow = ctx.lowest(LIQUIDITY_LOOKBACK, offset = 1)
                val previousHigh = ctx.highest(LIQUIDITY_LOOKBACK, offset = 1)
                DirectionalMatch.directional(
                    bullish = ctx.current.low < previousLow && ctx.close > previousLow,
                    bearish = ctx.current.high > previousHigh && ctx.close < previousHigh,
                )
            }
            StrategyConditionKind.FVG -> {
                val c0 = ctx.candle(0)
                val c2 = ctx.candle(-2)
                if (c0 == null || c2 == null) {
                    DirectionalMatch.directional()
                } else {
                    DirectionalMatch.directional(
                        bullish = c0.low > c2.high,
                        bearish = c0.high < c2.low,
                    )
                }
            }
            StrategyConditionKind.ORDER_BLOCK -> {
                val prev = ctx.candle(-1)
                if (prev == null) {
                    DirectionalMatch.directional()
                } else {
                    DirectionalMatch.directional(
                        bullish = prev.close < prev.open && ctx.current.low <= prev.high && ctx.close > prev.high,
                        bearish = prev.close > prev.open && ctx.current.high >= prev.low && ctx.close < prev.low,
                    )
                }
            }
            // A single-symbol ScriptContext cannot prove cross-symbol SMT.
            // Treat it as unavailable instead of the former unconditional true.
            StrategyConditionKind.SMT -> DirectionalMatch.unsupported()
            StrategyConditionKind.SESSION -> DirectionalMatch.nonDirectional(isKillZone(ctx.current.timestamp))
            StrategyConditionKind.RISK -> DirectionalMatch.nonDirectional(
                riskPercent.isFinite() && riskPercent > 0.0 && riskPercent <= MAX_BLUEPRINT_RISK_PERCENT,
            )
        }
    }

    private fun combine(values: List<Boolean>, op: LogicOp): Boolean = when (op) {
        LogicOp.AND -> values.all { it }
        LogicOp.OR -> values.any { it }
        LogicOp.NOT -> values.none { it }
    }

    /** London 07:00-10:00 and New York 12:00-15:00, expressed in UTC. */
    private fun isKillZone(timestamp: Long): Boolean {
        val minuteOfDay = Math.floorMod(timestamp, MILLIS_PER_DAY) / MILLIS_PER_MINUTE
        return minuteOfDay in LONDON_KILL_ZONE || minuteOfDay in NEW_YORK_KILL_ZONE
    }

    private data class DirectionalMatch(
        val bullish: Boolean,
        val bearish: Boolean,
        val directional: Boolean,
        val supported: Boolean,
    ) {
        companion object {
            fun directional(bullish: Boolean = false, bearish: Boolean = false) = DirectionalMatch(
                bullish = bullish,
                bearish = bearish,
                directional = true,
                supported = true,
            )

            fun nonDirectional(matched: Boolean) = DirectionalMatch(
                bullish = matched,
                bearish = matched,
                directional = false,
                supported = true,
            )

            fun unsupported() = DirectionalMatch(
                bullish = false,
                bearish = false,
                directional = false,
                supported = false,
            )
        }
    }

    companion object {
        private const val MIN_RISK_DISTANCE = 1e-12
        private const val RSI_OVERSOLD = 30.0
        private const val RSI_OVERBOUGHT = 70.0
        private const val STRUCTURE_LOOKBACK = 20
        private const val LIQUIDITY_LOOKBACK = 10
        private const val MAX_BLUEPRINT_RISK_PERCENT = 5.0
        private const val BASE_BLUEPRINT_CONFIDENCE = 65
        private const val CONFIDENCE_PER_RULE = 5
        private const val MAX_BLUEPRINT_CONFIDENCE = 90
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_DAY = 86_400_000L
        private val LONDON_KILL_ZONE = 7L * 60L until 10L * 60L
        private val NEW_YORK_KILL_ZONE = 12L * 60L until 15L * 60L
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
        val endIdx = safeEndIndex(offset) ?: return close
        if (period <= 0) return close
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
        val endIdx = safeEndIndex(offset) ?: return close
        if (period <= 0) return close
        val slice = candles.subList(0, endIdx + 1)
        val emas = TechnicalIndicators.calculateEMA(slice, period)
        return emas.lastOrNull() ?: close
    }

    /** Relative Strength Index over [period] ending at [offset] bars back. */
    fun rsi(period: Int = 14, offset: Int = 0): Double {
        val endIdx = safeEndIndex(offset) ?: return 50.0
        if (period <= 0) return 50.0
        if (endIdx < period) return 50.0
        val slice = candles.subList(0, endIdx + 1)
        val rsis = TechnicalIndicators.calculateRSI(slice, period)
        return rsis.lastOrNull() ?: 50.0
    }

    /** Average True Range over [period] ending at [offset] bars back. */
    fun atr(period: Int = 14, offset: Int = 0): Double {
        val endIdx = safeEndIndex(offset) ?: return max(0.0001, high - low)
        if (period <= 0) return max(0.0001, high - low)
        if (endIdx < 1) return max(0.0001, high - low)
        val slice = candles.subList(0, endIdx + 1)
        val atrs = TechnicalIndicators.calculateATR(slice, period)
        return atrs.lastOrNull() ?: max(0.0001, high - low)
    }

    /** Highest high over [period] bars ending at [offset] bars back. */
    fun highest(period: Int, offset: Int = 0): Double {
        val endIdx = safeEndIndex(offset) ?: return high
        if (period <= 0) return high
        val startIdx = (endIdx - period + 1).coerceAtLeast(0)
        var maxVal = Double.NEGATIVE_INFINITY
        for (i in startIdx..endIdx) {
            if (candles[i].high > maxVal) maxVal = candles[i].high
        }
        return if (maxVal == Double.NEGATIVE_INFINITY) high else maxVal
    }

    /** Lowest low over [period] bars ending at [offset] bars back. */
    fun lowest(period: Int, offset: Int = 0): Double {
        val endIdx = safeEndIndex(offset) ?: return low
        if (period <= 0) return low
        val startIdx = (endIdx - period + 1).coerceAtLeast(0)
        var minVal = Double.POSITIVE_INFINITY
        for (i in startIdx..endIdx) {
            if (candles[i].low < minVal) minVal = candles[i].low
        }
        return if (minVal == Double.POSITIVE_INFINITY) low else minVal
    }

    /** MACD calculation at current bar. */
    fun macd(fast: Int = 12, slow: Int = 26, signal: Int = 9): MacdOutput {
        if (fast <= 0 || slow <= 0 || signal <= 0 || fast >= slow) {
            return MacdOutput(0.0, 0.0, 0.0)
        }
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
        if (period <= 0 || !multiplier.isFinite() || multiplier < 0.0) {
            return BollingerOutput(close, close, close)
        }
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
        if (kPeriod <= 0 || dPeriod <= 0) return StochasticOutput(50.0, 50.0)
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

    /**
     * Resolve a historical offset without ever allowing a negative offset to
     * expose a future bar. Indicator helpers previously accepted `offset = -1`,
     * which made their sub-list extend beyond [currentIndex] and broke the
     * scripting engine's non-repainting contract.
     */
    private fun safeEndIndex(offset: Int): Int? {
        if (offset < 0) return null
        val endIdx = currentIndex - offset
        return endIdx.takeIf { it in candles.indices && it <= currentIndex }
    }
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
