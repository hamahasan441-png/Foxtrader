package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.runtime.Stable
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Chart viewport — the "camera" over the candle series.
 *
 * Holds the visible index range, auto-scaled price bounds, and layout
 * geometry (margins for price scale + time axis). This is the single
 * source of truth for all coordinate transformations.
 *
 * Performance contract:
 * - Pure, allocation-free math in hot paths (xForIndex, yForPrice)
 * - No Compose snapshot-state reads — mutations happen outside composition
 * - Viewport culling: only indices in [startIndex, startIndex+visibleBars] are drawn
 *
 * Layout:
 * ```
 * ┌────────────────────────────────┬──────────┐
 * │         CHART AREA             │  PRICE   │
 * │                                │  SCALE   │
 * │                                │  (Y)     │
 * ├────────────────────────────────┴──────────┤
 * │              TIME AXIS (X)                 │
 * └────────────────────────────────────────────┘
 * ```
 */
@Stable
class ChartViewport(
    var startIndex: Float = 0f,
    var visibleBars: Float = 100f,
    var priceHigh: Double = 1.0,
    var priceLow: Double = 0.0,
) {
    // ========================================================================
    // LAYOUT CONSTANTS (dp-independent pixel values set by the composable)
    // ========================================================================

    /** Right margin reserved for the price scale (Y-axis labels). */
    var priceScaleWidth: Float = 72f

    /** Bottom margin reserved for the time axis (X-axis labels). */
    var timeAxisHeight: Float = 28f

    /** The usable chart drawing area width (total width - priceScaleWidth). */
    fun chartWidth(totalWidth: Float): Float = (totalWidth - priceScaleWidth).coerceAtLeast(1f)

    /** The usable chart drawing area height (total height - timeAxisHeight). */
    fun chartHeight(totalHeight: Float): Float = (totalHeight - timeAxisHeight).coerceAtLeast(1f)

    // ========================================================================
    // FLING / VELOCITY STATE
    // ========================================================================

    /** Current horizontal velocity in bars/second (set on finger lift). */
    var velocityBarsPerSec: Float = 0f

    /** Whether fling animation is currently active. */
    var isFling: Boolean = false

    // ========================================================================
    // CROSSHAIR STATE
    // ========================================================================

    /** Whether the crosshair is active (activated by long-press). */
    var crosshairActive: Boolean = false

    /** Crosshair X position in chart-area pixels. */
    var crosshairX: Float = 0f

    /** Crosshair Y position in chart-area pixels. */
    var crosshairY: Float = 0f

    /** The candle index the crosshair is snapped to. */
    val crosshairIndex: Int get() = (startIndex + crosshairX / chartWidth(crosshairTotalWidth) * visibleBars).toInt()

    /** Total width (needed for crosshair index calc). Set externally. */
    var crosshairTotalWidth: Float = 1f

    // ========================================================================
    // COORDINATE TRANSFORMS
    // ========================================================================

    /** Map a bar index to x pixel within the CHART AREA (excludes price scale). */
    fun xForIndex(index: Float, chartAreaWidth: Float): Float =
        (index - startIndex) / visibleBars * chartAreaWidth

    /** Map a price to y pixel within the CHART AREA (excludes time axis). */
    fun yForPrice(price: Double, chartAreaHeight: Float): Float {
        val range = (priceHigh - priceLow).coerceAtLeast(1e-9)
        return (((priceHigh - price) / range) * chartAreaHeight).toFloat()
    }

    /** Map an x pixel (in chart area) back to a bar index. */
    fun indexForX(x: Float, chartAreaWidth: Float): Float =
        startIndex + (x / chartAreaWidth) * visibleBars

    /** Map a y pixel (in chart area) back to a price. */
    fun priceForY(y: Float, chartAreaHeight: Float): Double {
        val range = (priceHigh - priceLow).coerceAtLeast(1e-9)
        return priceHigh - (y / chartAreaHeight) * range
    }

    /** Pixel width of a single bar within the chart area. */
    fun barWidthPx(chartAreaWidth: Float): Float = chartAreaWidth / visibleBars

    // ========================================================================
    // AUTO-SCALE
    // ========================================================================

    /** Recompute price bounds from the visible candle range. */
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

    // ========================================================================
    // CLAMPING
    // ========================================================================

    /** Clamp the viewport to valid bounds. */
    fun clamp(total: Int, minBars: Float = 10f, maxBars: Float = 100_000f) {
        visibleBars = visibleBars.coerceIn(minBars, maxBars)
        val maxStart = max(0f, total - visibleBars)
        startIndex = startIndex.coerceIn(0f, maxStart)
    }

    // ========================================================================
    // GRID & SCALE HELPERS
    // ========================================================================

    /**
     * "Nice" round price step for grid lines.
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

    /**
     * "Nice" round time step for time-axis labels (in number of bars).
     * Adapts based on visible bars count and timeframe.
     */
    fun niceTimeStep(targetLabels: Int = 6): Int {
        val rough = (visibleBars / targetLabels.coerceAtLeast(1)).toInt()
        if (rough <= 1) return 1
        // Snap to round numbers: 1, 2, 5, 10, 15, 20, 30, 60, 120...
        val candidates = intArrayOf(1, 2, 5, 10, 15, 20, 30, 60, 120, 240, 480, 1000)
        return candidates.lastOrNull { it <= rough } ?: rough
    }

    /**
     * Format a price for the Y-axis label.
     * Adapts decimal places based on the price magnitude.
     */
    fun formatPrice(price: Double): String = when {
        price >= 10_000 -> String.format(Locale.US, "%,.0f", price)
        price >= 100 -> String.format(Locale.US, "%,.2f", price)
        price >= 1 -> String.format(Locale.US, "%.4f", price)
        else -> String.format(Locale.US, "%.5f", price) // Forex pairs
    }

    /**
     * Format a timestamp for the X-axis label.
     * Adapts based on the timeframe.
     */
    fun formatTime(timestamp: Long, timeframe: Timeframe): String {
        val sdf = when {
            timeframe.minutes >= 1440 -> SimpleDateFormat("MMM dd", Locale.US)
            timeframe.minutes >= 60 -> SimpleDateFormat("HH:mm", Locale.US)
            else -> SimpleDateFormat("HH:mm", Locale.US)
        }
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(timestamp))
    }

    // ========================================================================
    // COPY
    // ========================================================================

    fun copyState(): ChartViewport =
        ChartViewport(startIndex, visibleBars, priceHigh, priceLow)
}
