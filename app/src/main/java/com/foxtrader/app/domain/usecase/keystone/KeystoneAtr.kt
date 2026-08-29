package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * Wilder's ATR as a per-bar series, plus the rolling median used by the
 * volatility floor.
 *
 * Computed once for the whole run. Every value at index `i` depends only on
 * bars at or before `i`, so reading the array at a bar is the same answer a run
 * truncated at that bar would give — which is what lets the engine hold one
 * array instead of recomputing volatility inside every stage.
 */
object KeystoneAtr {

    /** ATR at each bar; entries before the period has filled are 0. */
    fun series(candles: List<Candle>, period: Int): DoubleArray {
        val out = DoubleArray(candles.size)
        if (candles.size < period + 1) return out

        var sum = 0.0
        for (i in 1..period) sum += trueRange(candles, i)
        var atr = sum / period
        out[period] = atr
        for (i in period + 1 until candles.size) {
            atr = (atr * (period - 1) + trueRange(candles, i)) / period
            out[i] = atr
        }
        return out
    }

    /**
     * Median of the [window] ATR values ending at each bar.
     *
     * The median rather than the mean: a single volatility spike drags a mean
     * upward for the whole window, and the floor would then stand the engine
     * down through the calm that follows precisely because the market was once
     * loud.
     */
    fun rollingMedian(atr: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(atr.size)
        if (atr.isEmpty()) return out
        val buffer = DoubleArray(window)
        for (i in atr.indices) {
            val start = (i - window + 1).coerceAtLeast(0)
            var count = 0
            for (j in start..i) {
                if (atr[j] > 0.0) buffer[count++] = atr[j]
            }
            if (count == 0) continue
            val slice = buffer.copyOf(count)
            slice.sort()
            out[i] = if (count % 2 == 1) slice[count / 2] else (slice[count / 2 - 1] + slice[count / 2]) / 2.0
        }
        return out
    }

    private fun trueRange(candles: List<Candle>, i: Int): Double {
        val current = candles[i]
        val previousClose = candles[i - 1].close
        return max(
            current.high - current.low,
            max(abs(current.high - previousClose), abs(current.low - previousClose)),
        )
    }
}
