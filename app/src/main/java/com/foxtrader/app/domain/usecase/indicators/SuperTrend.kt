package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import javax.inject.Inject

/**
 * SuperTrend — ATR-based trend-following indicator.
 *
 * Plots a single line that flips above/below price based on volatility bands.
 * Non-repainting: each bar's value depends only on prior/current bars.
 */
class SuperTrend @Inject constructor() {

    data class SuperTrendResult(
        val values: DoubleArray,
        val direction: IntArray,
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

        val safeAtrPeriod = atrPeriod.coerceAtLeast(1)
        val safeMultiplier = multiplier.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 3.0
        val atr = TechnicalIndicators.calculateATRIncremental(
            candles = candles,
            period = safeAtrPeriod,
            previous = null,
            recomputeFrom = recomputeFrom,
        )
        val requestedStart = if (previous != null && recomputeFrom > 0) {
            maxOf(0, recomputeFrom - 1)
        } else {
            0
        }
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
            val basicUpper = hl2 + safeMultiplier * atr[i]
            val basicLower = hl2 - safeMultiplier * atr[i]

            if (i == 0) {
                finalUpper = basicUpper
                finalLower = basicLower
                finalUpperBands[i] = finalUpper
                finalLowerBands[i] = finalLower
                // Seed bullish and place the bullish line BELOW price. The old
                // seed used basicUpper while reporting direction=+1, which made
                // the first state internally contradictory and could contaminate
                // the next recursive flip decision.
                dir[i] = 1
                st[i] = basicLower
                continue
            }

            val prevClose = candles[i - 1].close
            finalUpper = if (basicUpper < finalUpper || prevClose > finalUpper) basicUpper else finalUpper
            finalLower = if (basicLower > finalLower || prevClose < finalLower) basicLower else finalLower

            val prevDir = dir[i - 1].let { if (it == -1) -1 else 1 }
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

    fun currentTrend(result: SuperTrendResult): Direction? {
        if (result.direction.isEmpty()) return null
        return if (result.direction.last() == 1) Direction.BULLISH else Direction.BEARISH
    }
}
