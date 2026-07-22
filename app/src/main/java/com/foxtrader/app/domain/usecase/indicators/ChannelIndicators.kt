package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Channel indicators — Keltner Channels and Donchian Channels.
 *
 * Keltner: EMA middle band +/- ATR * multiplier (volatility-adaptive)
 * Donchian: highest high / lowest low over a period (breakout channel)
 */
class ChannelIndicators @Inject constructor() {

    data class Channel(
        val upper: DoubleArray,
        val middle: DoubleArray,
        val lower: DoubleArray,
    )

    /** Keltner Channels: EMA +/- (ATR * multiplier). */
    fun keltner(
        candles: List<Candle>,
        emaPeriod: Int = 20,
        atrPeriod: Int = 10,
        multiplier: Double = 2.0,
    ): Channel {
        val n = candles.size
        val ema = TechnicalIndicators.calculateEMA(candles, emaPeriod)
        val atr = TechnicalIndicators.calculateATR(candles, atrPeriod)
        val upper = DoubleArray(n)
        val lower = DoubleArray(n)
        for (i in 0 until n) {
            upper[i] = ema[i] + atr[i] * multiplier
            lower[i] = ema[i] - atr[i] * multiplier
        }
        return Channel(upper, ema, lower)
    }

    /** Donchian Channels: highest high / lowest low over period. */
    fun donchian(candles: List<Candle>, period: Int = 20): Channel {
        val n = candles.size
        val upper = DoubleArray(n)
        val lower = DoubleArray(n)
        val middle = DoubleArray(n)
        for (i in 0 until n) {
            val start = max(0, i - period + 1)
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (j in start..i) {
                hh = max(hh, candles[j].high)
                ll = min(ll, candles[j].low)
            }
            upper[i] = hh
            lower[i] = ll
            middle[i] = (hh + ll) / 2.0
        }
        return Channel(upper, middle, lower)
    }
}
