package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

/**
 * Volume-based indicators: OBV, Money Flow Index, Accumulation/Distribution.
 *
 * These reveal buying/selling pressure that price alone doesn't show —
 * essential for confirming trends and spotting divergences.
 */
class VolumeIndicators @Inject constructor() {

    /** On-Balance Volume — cumulative volume weighted by price direction. */
    fun obv(candles: List<Candle>): DoubleArray {
        val n = candles.size
        val obv = DoubleArray(n)
        if (n == 0) return obv
        for (i in 1 until n) {
            obv[i] = when {
                candles[i].close > candles[i - 1].close -> obv[i - 1] + candles[i].volume
                candles[i].close < candles[i - 1].close -> obv[i - 1] - candles[i].volume
                else -> obv[i - 1]
            }
        }
        return obv
    }

    /** Money Flow Index — volume-weighted RSI (0-100). */
    fun moneyFlowIndex(candles: List<Candle>, period: Int = 14): DoubleArray {
        val n = candles.size
        val mfi = DoubleArray(n) { 50.0 }
        if (n < period + 1) return mfi

        for (i in period until n) {
            var positiveFlow = 0.0
            var negativeFlow = 0.0
            for (j in (i - period + 1)..i) {
                if (j == 0) continue
                val tp = (candles[j].high + candles[j].low + candles[j].close) / 3.0
                val prevTp = (candles[j - 1].high + candles[j - 1].low + candles[j - 1].close) / 3.0
                val rawFlow = tp * candles[j].volume
                if (tp > prevTp) positiveFlow += rawFlow
                else if (tp < prevTp) negativeFlow += rawFlow
            }
            val ratio = if (negativeFlow > 0) positiveFlow / negativeFlow else 100.0
            mfi[i] = 100.0 - 100.0 / (1.0 + ratio)
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
            cumulative += mfMultiplier * c.volume
            ad[i] = cumulative
        }
        return ad
    }
}
