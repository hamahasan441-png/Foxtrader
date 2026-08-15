package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import javax.inject.Inject

/**
 * SuperTrend — ATR-based trend-following indicator.
 *
 * Plots a single line that flips above/below price based on volatility bands.
 * Widely used for trailing stops and trend confirmation.
 *
 * Non-repainting: each bar's value depends only on prior bars.
 */
class SuperTrend @Inject constructor() {

    data class SuperTrendResult(
        val values: DoubleArray,        // The SuperTrend line
        val direction: IntArray,        // +1 = bullish (line below price), -1 = bearish
        val finalUpperBands: DoubleArray,
        val finalLowerBands: DoubleArray,
    )

    fun calculate(
        candles: List<Candle>,
        atrPeriod: Int = 10,
        multiplier: Double = 3.0,
    ): SuperTrendResult = calculateIncremental(
        candles = candles,
        previous = null,
        recomputeFrom = 0,
        atrPeriod = atrPeriod,
        multiplier = multiplier,
    )

    fun calculateIncremental(
        candles: List<Candle>,
        previous: SuperTrendResult?,
        recomputeFrom: Int,
        atrPeriod: Int = 10,
        multiplier: Double = 3.0,
    ): SuperTrendResult {
        val n = candles.size
        val st = DoubleArray(n)
        val dir = IntArray(n)
        val finalUpperBands = DoubleArray(n)
        val finalLowerBands = DoubleArray(n)
        if (n == 0) return SuperTrendResult(st, dir, finalUpperBands, finalLowerBands)

        val atr = TechnicalIndicators.calculateATRIncremental(
            candles = candles,
            // ATR period reaches here from indicator settings / the plugin SDK.
            // TechnicalIndicators clamps it too, but doing it here keeps the
            // band math below reading a period that matches what was computed.
            period = atrPeriod.coerceAtLeast(1),
            previous = null,
            recomputeFrom = recomputeFrom,
        )
        val requestedStart = if (previous != null && recomputeFrom > 0) {
            maxOf(0, recomputeFrom - 1)
        } else {
            0
        }
        // `SAFETY` Resume only when the previous snapshot covers the resume
        // point. A short/stale snapshot previously left startIndex > 0 with a
        // zeroed prefix: no crash, but the loop then seeded dir[i-1] = 0 and
        // band state 0.0, silently drawing a wrong SuperTrend line. Fall back
        // to a full recompute instead.
        val canReuse = previous != null &&
            requestedStart > 0 &&
            previous.values.size >= requestedStart &&
            previous.direction.size >= requestedStart &&
            previous.finalUpperBands.size >= requestedStart &&
            previous.finalLowerBands.size >= requestedStart
        val startIndex = if (canReuse) requestedStart else 0
        if (canReuse && previous != null) {
            System.arraycopy(previous.values, 0, st, 0, startIndex)
            System.arraycopy(previous.direction, 0, dir, 0, startIndex)
            System.arraycopy(previous.finalUpperBands, 0, finalUpperBands, 0, startIndex)
            System.arraycopy(previous.finalLowerBands, 0, finalLowerBands, 0, startIndex)
        }

        var finalUpper = if (canReuse && previous != null) previous.finalUpperBands[startIndex - 1] else 0.0
        var finalLower = if (canReuse && previous != null) previous.finalLowerBands[startIndex - 1] else 0.0

        for (i in startIndex until n) {
            val hl2 = (candles[i].high + candles[i].low) / 2.0
            val basicUpper = hl2 + multiplier * atr[i]
            val basicLower = hl2 - multiplier * atr[i]

            if (i == 0) {
                finalUpper = basicUpper
                finalLower = basicLower
                finalUpperBands[i] = finalUpper
                finalLowerBands[i] = finalLower
                st[i] = basicUpper
                dir[i] = 1
                continue
            }

            val prevClose = candles[i - 1].close
            finalUpper = if (basicUpper < finalUpper || prevClose > finalUpper) basicUpper else finalUpper
            finalLower = if (basicLower > finalLower || prevClose < finalLower) basicLower else finalLower

            val prevDir = dir[i - 1]
            val close = candles[i].close
            val newDir = when {
                prevDir == 1 && close < finalLower -> -1
                prevDir == -1 && close > finalUpper -> 1
                else -> prevDir
            }
            dir[i] = newDir
            finalUpperBands[i] = finalUpper
            finalLowerBands[i] = finalLower
            st[i] = if (newDir == 1) finalLower else finalUpper
        }
        return SuperTrendResult(st, dir, finalUpperBands, finalLowerBands)
    }

    /** Current trend direction at the last bar. */
    fun currentTrend(result: SuperTrendResult): Direction? {
        if (result.direction.isEmpty()) return null
        return if (result.direction.last() == 1) Direction.BULLISH else Direction.BEARISH
    }
}
