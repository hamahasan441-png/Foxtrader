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
    ): IchimokuResult = calculateIncremental(
        candles = candles,
        previous = null,
        recomputeFrom = 0,
        tenkanPeriod = tenkanPeriod,
        kijunPeriod = kijunPeriod,
        senkouBPeriod = senkouBPeriod,
        displacement = displacement,
    )

    fun calculateIncremental(
        candles: List<Candle>,
        previous: IchimokuResult?,
        recomputeFrom: Int,
        tenkanPeriod: Int = 9,
        kijunPeriod: Int = 26,
        senkouBPeriod: Int = 52,
        displacement: Int = 26,
    ): IchimokuResult {
        val n = candles.size
        val tenkan = DoubleArray(n)
        val kijun = DoubleArray(n)
        val senkouA = DoubleArray(n) { Double.NaN }
        val senkouB = DoubleArray(n) { Double.NaN }
        val chikou = DoubleArray(n) { Double.NaN }
        if (n == 0) return IchimokuResult(tenkan, kijun, senkouA, senkouB, chikou, displacement)

        // Non-positive periods make `midpoint`'s window `start > index`, leaving
        // hi/lo at ±Infinity and producing NaN cloud lines.
        @Suppress("NAME_SHADOWING") val tenkanPeriod = tenkanPeriod.coerceAtLeast(1)
        @Suppress("NAME_SHADOWING") val kijunPeriod = kijunPeriod.coerceAtLeast(1)
        @Suppress("NAME_SHADOWING") val senkouBPeriod = senkouBPeriod.coerceAtLeast(1)
        val shift = displacement.coerceIn(0, n)

        val startIndex = if (previous != null && recomputeFrom > 0) {
            max(0, recomputeFrom - senkouBPeriod + 1)
        } else {
            0
        }
        // `SAFETY` Mirror the size guards Bollinger/SuperTrend already have: a
        // previous result shorter than the resume point (stale snapshot after a
        // rapid toggle/timeframe race) must fall back to a full recompute, not
        // throw ArrayIndexOutOfBounds from arraycopy on a background thread.
        val canReuse = previous != null &&
            startIndex > 0 &&
            previous.tenkan.size >= startIndex &&
            previous.kijun.size >= startIndex
        val resumeFrom = if (canReuse) startIndex else 0
        if (canReuse && previous != null) {
            System.arraycopy(previous.tenkan, 0, tenkan, 0, startIndex)
            System.arraycopy(previous.kijun, 0, kijun, 0, startIndex)
        }

        for (i in resumeFrom until n) {
            tenkan[i] = midpoint(candles, i, tenkanPeriod)
            kijun[i] = midpoint(candles, i, kijunPeriod)
        }
        // Senkou is plotted `shift` bars AHEAD of the bar that produced it.
        // Chikou is the close plotted `shift` bars BEHIND. Leaving them on the
        // computation bar drew the cloud on the wrong candles, so every engine
        // that asked "is price above the cloud?" was reading the future cloud.
        for (i in 0 until n) {
            val ahead = i + shift
            if (ahead < n) {
                senkouA[ahead] = (tenkan[i] + kijun[i]) / 2.0
                senkouB[ahead] = midpoint(candles, i, senkouBPeriod)
            }
            val behind = i - shift
            if (behind >= 0) chikou[behind] = candles[i].close
        }
        return IchimokuResult(tenkan, kijun, senkouA, senkouB, chikou, shift)
    }

    /** Determine if price is above, inside, or below the cloud at the last bar. */
    fun cloudPosition(candles: List<Candle>, result: IchimokuResult): CloudPosition {
        if (candles.isEmpty()) return CloudPosition.INSIDE
        val i = candles.lastIndex
        val price = candles[i].close
        val spanA = result.senkouA.getOrNull(i) ?: return CloudPosition.INSIDE
        val spanB = result.senkouB.getOrNull(i) ?: return CloudPosition.INSIDE
        if (!spanA.isFinite() || !spanB.isFinite() || !price.isFinite()) return CloudPosition.INSIDE
        val top = max(spanA, spanB)
        val bottom = min(spanA, spanB)
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
