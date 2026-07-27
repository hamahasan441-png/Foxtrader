package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Bollinger Bands — volatility envelope around a moving average.
 *
 * Middle = SMA(period)
 * Upper  = Middle + stdDev * multiplier
 * Lower  = Middle - stdDev * multiplier
 *
 * Also provides:
 * - %B (position within bands: 0 = lower, 1 = upper)
 * - Bandwidth (band width relative to middle — volatility proxy)
 * - Squeeze detection (low bandwidth = pending breakout)
 */
class BollingerBands @Inject constructor() {

    data class BollingerResult(
        val middle: DoubleArray,
        val upper: DoubleArray,
        val lower: DoubleArray,
        val percentB: DoubleArray,
        val bandwidth: DoubleArray,
    )

    fun calculate(
        candles: List<Candle>,
        period: Int = 20,
        multiplier: Double = 2.0,
    ): BollingerResult = calculateIncremental(candles, previous = null, recomputeFrom = 0, period = period, multiplier = multiplier)

    fun calculateIncremental(
        candles: List<Candle>,
        previous: BollingerResult?,
        recomputeFrom: Int,
        period: Int = 20,
        multiplier: Double = 2.0,
    ): BollingerResult {
        val n = candles.size
        val middle = DoubleArray(n)
        val upper = DoubleArray(n)
        val lower = DoubleArray(n)
        val percentB = DoubleArray(n)
        val bandwidth = DoubleArray(n)
        if (n == 0) return BollingerResult(middle, upper, lower, percentB, bandwidth)

        val startIndex = if (previous != null && recomputeFrom > 0) {
            maxOf(0, recomputeFrom - period + 1)
        } else {
            0
        }
        if (previous != null && previous.middle.size >= startIndex && startIndex > 0) {
            System.arraycopy(previous.middle, 0, middle, 0, startIndex)
            System.arraycopy(previous.upper, 0, upper, 0, startIndex)
            System.arraycopy(previous.lower, 0, lower, 0, startIndex)
            System.arraycopy(previous.percentB, 0, percentB, 0, startIndex)
            System.arraycopy(previous.bandwidth, 0, bandwidth, 0, startIndex)
        }

        for (i in startIndex until n) {
            val start = maxOf(0, i - period + 1)
            var sum = 0.0
            for (j in start..i) sum += candles[j].close
            val count = i - start + 1
            val mean = sum / count
            var varianceSum = 0.0
            for (j in start..i) {
                val diff = candles[j].close - mean
                varianceSum += diff * diff
            }
            val sd = sqrt(varianceSum / count)

            middle[i] = mean
            upper[i] = mean + sd * multiplier
            lower[i] = mean - sd * multiplier
            val bandRange = (upper[i] - lower[i]).coerceAtLeast(1e-9)
            percentB[i] = (candles[i].close - lower[i]) / bandRange
            bandwidth[i] = if (mean != 0.0) (upper[i] - lower[i]) / mean else 0.0
        }
        return BollingerResult(middle, upper, lower, percentB, bandwidth)
    }

    /**
     * Detect a "squeeze" — bandwidth at a local minimum over [lookback] bars,
     * indicating compressed volatility that often precedes a breakout.
     */
    fun isSqueeze(result: BollingerResult, lookback: Int = 50): Boolean {
        val bw = result.bandwidth
        if (bw.size < lookback) return false
        val recent = bw.takeLast(lookback)
        val current = bw.last()
        val minBw = recent.min()
        return current <= minBw * 1.05 // within 5% of the lowest bandwidth
    }
}
