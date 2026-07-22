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
    )

    fun calculate(
        candles: List<Candle>,
        atrPeriod: Int = 10,
        multiplier: Double = 3.0,
    ): SuperTrendResult {
        val n = candles.size
        val st = DoubleArray(n)
        val dir = IntArray(n)
        if (n == 0) return SuperTrendResult(st, dir)

        val atr = TechnicalIndicators.calculateATR(candles, atrPeriod)
        var finalUpper = 0.0
        var finalLower = 0.0
        var prevDir = 1

        for (i in 0 until n) {
            val hl2 = (candles[i].high + candles[i].low) / 2.0
            val basicUpper = hl2 + multiplier * atr[i]
            val basicLower = hl2 - multiplier * atr[i]

            if (i == 0) {
                finalUpper = basicUpper
                finalLower = basicLower
                st[i] = basicUpper
                dir[i] = 1
                continue
            }

            val prevClose = candles[i - 1].close
            finalUpper = if (basicUpper < finalUpper || prevClose > finalUpper) basicUpper else finalUpper
            finalLower = if (basicLower > finalLower || prevClose < finalLower) basicLower else finalLower

            val close = candles[i].close
            prevDir = dir[i - 1]
            val newDir = when {
                prevDir == 1 && close < finalLower -> -1
                prevDir == -1 && close > finalUpper -> 1
                else -> prevDir
            }
            dir[i] = newDir
            st[i] = if (newDir == 1) finalLower else finalUpper
        }
        return SuperTrendResult(st, dir)
    }

    /** Current trend direction at the last bar. */
    fun currentTrend(result: SuperTrendResult): Direction? {
        if (result.direction.isEmpty()) return null
        return if (result.direction.last() == 1) Direction.BULLISH else Direction.BEARISH
    }
}
