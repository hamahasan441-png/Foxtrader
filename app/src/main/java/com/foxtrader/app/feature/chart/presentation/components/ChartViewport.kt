package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.runtime.Stable
import com.foxtrader.app.domain.model.Candle
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Chart viewport — the "camera" over the candle series.
 * Holds the visible index range + auto-scaled price bounds.
 *
 * Pure, allocation-free math. Only VISIBLE candles are ever drawn
 * (viewport culling), so millions of candles render at 120fps.
 */
@Stable
class ChartViewport(
    var startIndex: Float = 0f,
    var visibleBars: Float = 100f,
    var priceHigh: Double = 1.0,
    var priceLow: Double = 0.0,
) {
    /** Recompute price bounds from the visible candle range (auto-scale). */
    fun autoScale(candles: List<Candle>, pad: Double = 0.08) {
        if (candles.isEmpty()) return
        val start = max(0, startIndex.toInt())
        val end = min(candles.size, (startIndex + visibleBars).toInt() + 1)
        if (start >= end) return

        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (i in start until end) {
            if (candles[i].high > hi) hi = candles[i].high
            if (candles[i].low < lo) lo = candles[i].low
        }
        if (hi == Double.NEGATIVE_INFINITY) return
        val range = (hi - lo).coerceAtLeast(1e-9)
        val padding = range * pad
        priceHigh = hi + padding
        priceLow = lo - padding
    }

    /** Clamp the viewport to valid bounds with light elastic tolerance. */
    fun clamp(total: Int, minBars: Float = 10f, maxBars: Float = 100_000f) {
        visibleBars = visibleBars.coerceIn(minBars, maxBars)
        val maxStart = max(0f, total - visibleBars)
        startIndex = startIndex.coerceIn(0f, maxStart)
    }

    /** Map a bar index → x pixel. */
    fun xForIndex(index: Float, width: Float): Float =
        (index - startIndex) / visibleBars * width

    /** Map a price → y pixel (inverted: high price = low y). */
    fun yForPrice(price: Double, height: Float): Float {
        val range = (priceHigh - priceLow).coerceAtLeast(1e-9)
        return (((priceHigh - price) / range) * height).toFloat()
    }

    /** Pixel width of a single bar. */
    fun barWidthPx(width: Float): Float = width / visibleBars

    /**
     * A "nice" round price increment for grid lines so levels land on human
     * numbers (…, 1.0, 1.25, 1.5, …) instead of arbitrary geometric fractions.
     * Standard 1-2-5 progression scaled to the visible price range.
     */
    fun niceStep(targetLines: Int = 5): Double {
        val range = (priceHigh - priceLow).coerceAtLeast(1e-9)
        val rough = range / targetLines.coerceAtLeast(1)
        val mag = 10.0.pow(floor(log10(rough)))
        val norm = rough / mag
        val niceNorm = when {
            norm < 1.5 -> 1.0
            norm < 3.0 -> 2.0
            norm < 7.0 -> 5.0
            else -> 10.0
        }
        return niceNorm * mag
    }

    fun copyState(): ChartViewport =
        ChartViewport(startIndex, visibleBars, priceHigh, priceLow)
}
