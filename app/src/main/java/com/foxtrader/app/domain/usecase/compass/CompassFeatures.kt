package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the market state at a call's own bar into the numbers the scorer reads.
 *
 * Every feature is computed from bars at or before the call and is expressed
 * **relative to the call's direction**, so that a value meaning "favourable"
 * means the same thing for a long and a short. Without that, the scorer would
 * have to learn the market twice and would learn it half as well from the same
 * data.
 *
 * They are also all scale-free — ratios, positions and normalised distances,
 * never raw prices — so a model calibrated on one instrument is not silently
 * describing that instrument's price level.
 */
object CompassFeatures {

    /** Feature count, fixed so the scorer's weights keep their meaning. */
    const val SIZE = 8

    val NAMES = listOf(
        "bias",
        "trend alignment",
        "momentum extremity",
        "position in range",
        "volatility regime",
        "body dominance",
        "distance from mean",
        "recent run",
    )

    /**
     * @param index the call's bar; nothing after it is read.
     */
    fun extract(candles: List<Candle>, index: Int, direction: Direction): DoubleArray {
        val out = DoubleArray(SIZE)
        out[0] = 1.0 // intercept
        if (index !in candles.indices || index < 2) return out

        val sign = if (direction == Direction.BULLISH) 1.0 else -1.0
        val closes = DoubleArray(index + 1) { candles[it].close }
        val price = closes[index]
        if (!price.isFinite() || price <= 0.0) return out

        val atr = CompassLabeler.atrAt(candles, index, ATR_PERIOD).takeIf { it > 0.0 } ?: return out

        // 1. Trend alignment: is the call with or against the slope?
        val fast = mean(closes, index, FAST)
        val slow = mean(closes, index, SLOW)
        out[1] = clamp(sign * (fast - slow) / atr, 3.0)

        // 2. Momentum extremity, signed so that "stretched against the call"
        //    and "stretched with the call" are opposite values.
        val rsi = TechnicalIndicators.calculateRsiSeries(closes, RSI_PERIOD).lastOrNull()
        if (rsi != null && rsi.isFinite()) out[2] = clamp(sign * (rsi - 50.0) / 50.0, 1.0)

        // 3. Where the call sits in the recent range: near the low is a
        //    different proposition for a long than near the high.
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        for (i in max(0, index - RANGE + 1)..index) {
            high = max(high, candles[i].high)
            low = min(low, candles[i].low)
        }
        val span = high - low
        if (span > 0.0) out[3] = clamp(sign * (2.0 * (price - low) / span - 1.0), 1.0)

        // 4. Volatility regime: this bar's range against its recent normal.
        val longAtr = CompassLabeler.atrAt(candles, index, ATR_PERIOD * 4).takeIf { it > 0.0 }
        if (longAtr != null) out[4] = clamp(atr / longAtr - 1.0, 3.0)

        // 5. Body dominance: conviction in the closing bar, signed by the call.
        val bar = candles[index]
        val range = bar.high - bar.low
        if (range > 0.0) out[5] = clamp(sign * (bar.close - bar.open) / range, 1.0)

        // 6. Distance from the mean, in volatility units.
        out[6] = clamp(sign * (price - slow) / atr, 3.0)

        // 7. Recent run of closes in the call's direction.
        var run = 0
        for (i in index downTo max(1, index - RUN + 1)) {
            val step = closes[i] - closes[i - 1]
            if (sign * step > 0.0) run++ else break
        }
        out[7] = clamp(run.toDouble() / RUN, 1.0)

        return out
    }

    private fun mean(values: DoubleArray, index: Int, period: Int): Double {
        val from = max(0, index - period + 1)
        var sum = 0.0
        for (i in from..index) sum += values[i]
        return sum / (index - from + 1)
    }

    private fun clamp(value: Double, limit: Double): Double =
        if (!value.isFinite()) 0.0 else value.coerceIn(-limit, limit)

    private const val ATR_PERIOD = 14
    private const val FAST = 10
    private const val SLOW = 40
    private const val RSI_PERIOD = 14
    private const val RANGE = 60
    private const val RUN = 5
}

/** Absolute value helper kept local so the feature file has no wider surface. */
internal fun DoubleArray.magnitude(): Double = sumOf { abs(it) }
