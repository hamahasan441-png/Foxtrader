package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.max

/**
 * Volume-based indicators: OBV, Money Flow Index, Accumulation/Distribution.
 */
class VolumeIndicators @Inject constructor() {

    /** On-Balance Volume — cumulative volume weighted by price direction. */
    fun obv(candles: List<Candle>): DoubleArray {
        val n = candles.size
        val obv = DoubleArray(n)
        if (n == 0) return obv
        for (i in 1 until n) {
            val volume = candles[i].volume.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            obv[i] = when {
                candles[i].close > candles[i - 1].close -> obv[i - 1] + volume
                candles[i].close < candles[i - 1].close -> obv[i - 1] - volume
                else -> obv[i - 1]
            }
        }
        return obv
    }

    /** Money Flow Index — volume-weighted RSI (0-100). */
    fun moneyFlowIndex(candles: List<Candle>, period: Int = 14): DoubleArray {
        val n = candles.size
        val mfi = DoubleArray(n) { 50.0 }
        val safePeriod = period.coerceAtLeast(1)
        if (n < safePeriod + 1) return mfi

        for (i in safePeriod until n) {
            var positiveFlow = 0.0
            var negativeFlow = 0.0
            for (j in (i - safePeriod + 1)..i) {
                if (j == 0) continue
                val current = candles[j]
                val previous = candles[j - 1]
                val tp = (current.high + current.low + current.close) / 3.0
                val prevTp = (previous.high + previous.low + previous.close) / 3.0
                val volume = current.volume.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                val rawFlow = tp * volume
                if (tp > prevTp) positiveFlow += rawFlow
                else if (tp < prevTp) negativeFlow += rawFlow
            }
            // Mirror RSI's exact edge behavior. A window with only positive
            // money flow is 100, only negative is 0, and a no-volume/flat
            // window is neutral 50 instead of the old arbitrary 99.0099.
            mfi[i] = when {
                positiveFlow <= 0.0 && negativeFlow <= 0.0 -> 50.0
                negativeFlow <= 0.0 -> 100.0
                positiveFlow <= 0.0 -> 0.0
                else -> {
                    val ratio = positiveFlow / negativeFlow
                    100.0 - 100.0 / (1.0 + ratio)
                }
            }
        }
        return mfi
    }

    /** Accumulation/Distribution Line — cumulative money flow volume. */
    fun accumulationDistribution(candles: List<Candle>): DoubleArray {
        val n = candles.size
        val ad = DoubleArray(n)
        if (n == 0) return ad
        var cumulative = 0.0
        for (i in 0 until n) {
            val c = candles[i]
            val range = max(c.high - c.low, 1e-9)
            val mfMultiplier = ((c.close - c.low) - (c.high - c.close)) / range
            val volume = c.volume.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            cumulative += mfMultiplier * volume
            ad[i] = cumulative
        }
        return ad
    }
}
