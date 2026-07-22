package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Ichimoku Kinko Hyo (Ichimoku Cloud) — complete trend/momentum system.
 *
 * Components:
 * - Tenkan-sen (Conversion Line): (9-period high + low) / 2
 * - Kijun-sen (Base Line): (26-period high + low) / 2
 * - Senkou Span A (Leading Span A): (Tenkan + Kijun) / 2, plotted 26 ahead
 * - Senkou Span B (Leading Span B): (52-period high + low) / 2, plotted 26 ahead
 * - Chikou Span (Lagging Span): close plotted 26 behind
 *
 * The area between Span A and Span B forms the "cloud" (Kumo).
 * Non-repainting for the historical lines; leading spans project forward.
 */
class IchimokuCloud @Inject constructor() {

    data class IchimokuResult(
        val tenkan: DoubleArray,
        val kijun: DoubleArray,
        val senkouA: DoubleArray,
        val senkouB: DoubleArray,
        val chikou: DoubleArray,
        val displacement: Int,
    )

    fun calculate(
        candles: List<Candle>,
        tenkanPeriod: Int = 9,
        kijunPeriod: Int = 26,
        senkouBPeriod: Int = 52,
        displacement: Int = 26,
    ): IchimokuResult {
        val n = candles.size
        val tenkan = DoubleArray(n)
        val kijun = DoubleArray(n)
        val senkouA = DoubleArray(n)
        val senkouB = DoubleArray(n)
        val chikou = DoubleArray(n)
        if (n == 0) return IchimokuResult(tenkan, kijun, senkouA, senkouB, chikou, displacement)

        for (i in 0 until n) {
            tenkan[i] = midpoint(candles, i, tenkanPeriod)
            kijun[i] = midpoint(candles, i, kijunPeriod)
            senkouA[i] = (tenkan[i] + kijun[i]) / 2.0
            senkouB[i] = midpoint(candles, i, senkouBPeriod)
            // Chikou: current close plotted 'displacement' bars back
            chikou[i] = candles[i].close
        }
        return IchimokuResult(tenkan, kijun, senkouA, senkouB, chikou, displacement)
    }

    /** Determine if price is above, inside, or below the cloud at the last bar. */
    fun cloudPosition(candles: List<Candle>, result: IchimokuResult): CloudPosition {
        if (candles.isEmpty()) return CloudPosition.INSIDE
        val i = candles.lastIndex
        val price = candles[i].close
        val top = max(result.senkouA[i], result.senkouB[i])
        val bottom = min(result.senkouA[i], result.senkouB[i])
        return when {
            price > top -> CloudPosition.ABOVE
            price < bottom -> CloudPosition.BELOW
            else -> CloudPosition.INSIDE
        }
    }

    enum class CloudPosition { ABOVE, INSIDE, BELOW }

    private fun midpoint(candles: List<Candle>, index: Int, period: Int): Double {
        val start = max(0, index - period + 1)
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (j in start..index) {
            if (candles[j].high > hi) hi = candles[j].high
            if (candles[j].low < lo) lo = candles[j].low
        }
        return (hi + lo) / 2.0
    }
}
