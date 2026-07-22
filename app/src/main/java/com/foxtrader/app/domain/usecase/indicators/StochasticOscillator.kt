package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Stochastic Oscillator — momentum indicator comparing close to the
 * high/low range over a period.
 *
 * %K = 100 * (close - lowestLow) / (highestHigh - lowestLow)
 * %D = SMA of %K (signal line)
 *
 * Also provides Stochastic RSI (Stoch applied to RSI values).
 */
class StochasticOscillator @Inject constructor() {

    data class StochResult(
        val percentK: DoubleArray,
        val percentD: DoubleArray,
    )

    fun calculate(
        candles: List<Candle>,
        kPeriod: Int = 14,
        dPeriod: Int = 3,
    ): StochResult {
        val n = candles.size
        val k = DoubleArray(n) { 50.0 }
        val d = DoubleArray(n) { 50.0 }
        if (n == 0) return StochResult(k, d)

        for (i in 0 until n) {
            val start = max(0, i - kPeriod + 1)
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (j in start..i) {
                hh = max(hh, candles[j].high)
                ll = min(ll, candles[j].low)
            }
            val range = (hh - ll).coerceAtLeast(1e-9)
            k[i] = ((candles[i].close - ll) / range * 100.0).coerceIn(0.0, 100.0)
        }

        // %D = SMA of %K
        for (i in 0 until n) {
            val start = max(0, i - dPeriod + 1)
            var sum = 0.0
            for (j in start..i) sum += k[j]
            d[i] = sum / (i - start + 1)
        }
        return StochResult(k, d)
    }

    /** Overbought (>80) / oversold (<20) classification at the last bar. */
    fun zone(result: StochResult): Zone {
        if (result.percentK.isEmpty()) return Zone.NEUTRAL
        val k = result.percentK.last()
        return when {
            k > 80 -> Zone.OVERBOUGHT
            k < 20 -> Zone.OVERSOLD
            else -> Zone.NEUTRAL
        }
    }

    enum class Zone { OVERBOUGHT, NEUTRAL, OVERSOLD }
}
