package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Bollinger Bands — volatility envelope around a moving average.
 * Middle = SMA(period), Upper/Lower = Middle +/- stdDev * multiplier.
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

        val safePeriod = period.coerceAtLeast(1)
        // A negative/NaN multiplier inverts or poisons upper/lower bands. Keep
        // direct callers safe in addition to the chart-settings sanitizer.
        val safeMultiplier = multiplier.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 2.0

        val requestedStart = if (previous != null && recomputeFrom > 0) {
            maxOf(0, recomputeFrom - safePeriod + 1)
        } else {
            0
        }
        val canReuse = previous != null &&
            requestedStart > 0 &&
            previous.middle.size >= requestedStart &&
            previous.upper.size >= requestedStart &&
            previous.lower.size >= requestedStart &&
            previous.percentB.size >= requestedStart &&
            previous.bandwidth.size >= requestedStart
        val startIndex = if (canReuse) requestedStart else 0
        if (canReuse && previous != null) {
            System.arraycopy(previous.middle, 0, middle, 0, startIndex)
            System.arraycopy(previous.upper, 0, upper, 0, startIndex)
            System.arraycopy(previous.lower, 0, lower, 0, startIndex)
            System.arraycopy(previous.percentB, 0, percentB, 0, startIndex)
            System.arraycopy(previous.bandwidth, 0, bandwidth, 0, startIndex)
        }

        for (i in startIndex until n) {
            val start = maxOf(0, i - safePeriod + 1)
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
            upper[i] = mean + sd * safeMultiplier
            lower[i] = mean - sd * safeMultiplier
            val bandRange = (upper[i] - lower[i]).coerceAtLeast(1e-9)
            percentB[i] = (candles[i].close - lower[i]) / bandRange
            bandwidth[i] = if (mean != 0.0) (upper[i] - lower[i]) / mean else 0.0
        }
        return BollingerResult(middle, upper, lower, percentB, bandwidth)
    }

    fun isSqueeze(result: BollingerResult, lookback: Int = 50): Boolean {
        val safeLookback = lookback.coerceAtLeast(1)
        val bw = result.bandwidth
        if (bw.size < safeLookback) return false
        val recent = bw.takeLast(safeLookback)
        val current = bw.last()
        val minBw = recent.min()
        return current <= minBw * 1.05
    }
}
