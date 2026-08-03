package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.runtime.Stable
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.chart.ChartViewportState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

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

    /**
     * Logarithmic price scale (R7). When true, [yForPrice] / [priceForY] map
     * price to pixels in log space so equal *percentage* moves occupy equal
     * vertical distance — the standard view for long-range / high-growth series.
     * Every layer maps price through [yForPrice], so this switch alone flips the
     * whole chart (candles, overlays, drawings, grid, price scale, crosshair).
     */
    var logScale: Boolean = false

    /** The usable chart drawing area width (total width - priceScaleWidth). */
    fun chartWidth(totalWidth: Float): Float = (totalWidth - priceScaleWidth).coerceAtLeast(1f)

    /** The usable chart drawing area height (total height - timeAxisHeight). */
    fun chartHeight(totalHeight: Float): Float = (totalHeight - timeAxisHeight).coerceAtLeast(1f)

    // ========================================================================
    // FLING / VELOCITY STATE (DEVELOPMENT.md §4.9)
    // ========================================================================

    /** Current horizontal velocity in bars/second (set on finger lift). */
    var velocityBarsPerSec: Float = 0f

    /** Whether fling animation is currently active. */
    var isFling: Boolean = false

    /**
     * Start a fling from a lift-off velocity expressed in **pixels/second**.
     *
     * The pixel velocity is converted into bar-space using the current zoom
     * level so the deceleration feels identical at every zoom. Velocities below
     * [MIN_FLING_BARS_PER_SEC] are ignored (a slow lift-off is not a fling).
     *
     * @return true when a fling was actually started.
     */
    fun startFling(velocityPxPerSec: Float, chartAreaWidth: Float): Boolean {
        val barsPerPx = visibleBars / chartAreaWidth.coerceAtLeast(1f)
        // Dragging right (positive px velocity) moves the viewport left in bar space.
        val barsPerSec = -velocityPxPerSec * barsPerPx
        if (abs(barsPerSec) < MIN_FLING_BARS_PER_SEC) {
            stopFling()
            return false
        }
        velocityBarsPerSec = barsPerSec.coerceIn(-MAX_FLING_BARS_PER_SEC, MAX_FLING_BARS_PER_SEC)
        isFling = true
        return true
    }

    /**
     * Advance an in-flight fling by [deltaSeconds] of wall-clock time.
     *
     * Uses exponential (friction-based) deceleration, which is the model
     * Android's own scrollers use: `v *= friction^dt`. This is frame-rate
     * independent — a 120 Hz device and a 60 Hz device travel the same distance.
     *
     * `PERF` Pure math, zero allocation. Safe to call once per frame.
     *
     * @return true while the fling is still running, false once it settled.
     */
    fun advanceFling(deltaSeconds: Float, total: Int): Boolean {
        if (!isFling) return false
        val dt = deltaSeconds.coerceIn(0f, MAX_FLING_STEP_SECONDS)
        startIndex += velocityBarsPerSec * dt
        velocityBarsPerSec *= FLING_FRICTION.pow(dt)

        val maxStart = max(0f, total - visibleBars)
        // Hitting a hard bound kills the fling immediately (no rubber-banding).
        if (startIndex <= 0f || startIndex >= maxStart) {
            startIndex = startIndex.coerceIn(0f, maxStart)
            stopFling()
            return false
        }
        if (abs(velocityBarsPerSec) < MIN_FLING_BARS_PER_SEC) {
            stopFling()
            return false
        }
        return true
    }

    /** Cancel any in-flight fling (called when a new touch lands). */
    fun stopFling() {
        isFling = false
        velocityBarsPerSec = 0f
    }

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

    /**
     * Bar index under the crosshair, snapped to the nearest bar centre and
     * clamped into `[0, total - 1]`. Returns -1 when there is no data.
     *
     * `RULE` (DEVELOPMENT.md §4.7) Crosshair readouts must always resolve to a
     * real, in-range bar — never to a fractional or out-of-bounds index.
     */
    fun snappedCrosshairIndex(total: Int, chartAreaWidth: Float): Int {
        if (total <= 0) return -1
        val raw = indexForX(crosshairX.coerceIn(0f, chartAreaWidth), chartAreaWidth)
        return raw.roundToInt().coerceIn(0, total - 1)
    }

    // ========================================================================
    // COORDINATE TRANSFORMS
    // ========================================================================

    /** Map a bar index to x pixel within the CHART AREA (excludes price scale). */
    fun xForIndex(index: Float, chartAreaWidth: Float): Float =
        (index - startIndex) / visibleBars * chartAreaWidth

    /** Map a price to y pixel within the CHART AREA (excludes time axis). */
    fun yForPrice(price: Double, chartAreaHeight: Float): Float {
        if (logScale) {
            val hi = ln(priceHigh.coerceAtLeast(LOG_MIN_PRICE))
            val lo = ln(priceLow.coerceAtLeast(LOG_MIN_PRICE))
            val p = ln(price.coerceAtLeast(LOG_MIN_PRICE))
            val range = (hi - lo).coerceAtLeast(1e-9)
            return (((hi - p) / range) * chartAreaHeight).toFloat()
        }
        val range = (priceHigh - priceLow).coerceAtLeast(1e-9)
        return (((priceHigh - price) / range) * chartAreaHeight).toFloat()
    }

    /** Map an x pixel (in chart area) back to a bar index. */
    fun indexForX(x: Float, chartAreaWidth: Float): Float =
        startIndex + (x / chartAreaWidth) * visibleBars

    /** Map a y pixel (in chart area) back to a price. */
    fun priceForY(y: Float, chartAreaHeight: Float): Double {
        if (logScale) {
            val hi = ln(priceHigh.coerceAtLeast(LOG_MIN_PRICE))
            val lo = ln(priceLow.coerceAtLeast(LOG_MIN_PRICE))
            val range = (hi - lo).coerceAtLeast(1e-9)
            return exp(hi - (y / chartAreaHeight) * range)
        }
        val range = (priceHigh - priceLow).coerceAtLeast(1e-9)
        return priceHigh - (y / chartAreaHeight) * range
    }

    /** Pixel width of a single bar within the chart area. */
    fun barWidthPx(chartAreaWidth: Float): Float = chartAreaWidth / visibleBars

    // ========================================================================
    // CAMERA OPERATIONS
    // ========================================================================

    /**
     * Pan the camera horizontally by a finger delta in pixels.
     *
     * Positive [panPx] (finger moving right) reveals *older* bars, matching the
     * direct-manipulation model required by DEVELOPMENT.md §4.9.
     */
    fun panByPixels(panPx: Float, chartAreaWidth: Float) {
        if (panPx == 0f) return
        startIndex -= panPx * (visibleBars / chartAreaWidth.coerceAtLeast(1f))
    }

    /**
     * Pinch-zoom anchored to the gesture centroid.
     *
     * `RULE` (DEVELOPMENT.md §4.6) The bar under the user's fingers must stay
     * pinned during the zoom. This is the hard-won "no viewport jump" invariant
     * — do not replace it with a centre-anchored zoom.
     *
     * @param zoom scale factor from `detectTransformGestures` (>1 = zoom in).
     * @param centroidX centroid x in chart-area pixels.
     */
    fun zoomBy(zoom: Float, centroidX: Float, chartAreaWidth: Float, total: Int) {
        if (zoom == 1f || zoom <= 0f) return
        val cw = chartAreaWidth.coerceAtLeast(1f)
        val anchorBar = startIndex + (centroidX.coerceIn(0f, cw) / cw) * visibleBars
        val anchorFraction = (anchorBar - startIndex) / visibleBars.coerceAtLeast(1f)
        val maxBars = max(MIN_VISIBLE_BARS, total.toFloat())
        visibleBars = (visibleBars / zoom).coerceIn(MIN_VISIBLE_BARS, maxBars)
        startIndex = anchorBar - anchorFraction * visibleBars
    }

    /**
     * Reset the camera to the most recent [barCount] bars ("go to now").
     * Used by the double-tap gesture and the chart's initial layout.
     */
    fun resetToLatest(total: Int, barCount: Float = DEFAULT_VISIBLE_BARS) {
        if (total <= 0) return
        visibleBars = min(barCount, total.toFloat()).coerceAtLeast(MIN_VISIBLE_BARS)
        startIndex = max(0f, total - visibleBars)
        stopFling()
    }

    /** Whether the right edge of the viewport is pinned to the newest bar. */
    fun isAtRightEdge(total: Int, toleranceBars: Float = 1f): Boolean =
        startIndex + visibleBars >= total - toleranceBars

    /**
     * Preserve the current visual anchor when older history is prepended.
     *
     * If N bars are inserted at the front of the dataset, every previously
     * visible bar's index increases by N. Shifting [startIndex] by the same
     * amount keeps the exact same bars under the user's eyes instead of
     * snapping the camera left into the newly loaded history.
     */
    fun shiftForPrependedBars(prependedCount: Int) {
        if (prependedCount <= 0) return
        startIndex += prependedCount
    }

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
    fun clamp(total: Int, minBars: Float = MIN_VISIBLE_BARS, maxBars: Float = MAX_VISIBLE_BARS) {
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

    // `PERF` Date formatting sits in the draw hot path — it is called once per
    // visible time-axis label and on every crosshair frame. Constructing a
    // SimpleDateFormat + a Date on each call (the previous implementation) was a
    // per-frame allocation that churned the GC and violated the "zero per-frame
    // allocations" contract in the header. These formatters are built once and
    // reused; a single scratch Date is mutated in place. Safe because all
    // drawing happens on the single UI/render thread.
    private val dateAxisFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("MMM dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
    }
    private val timeAxisFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }
    }
    private val scratchDate = Date()

    /**
     * Format a timestamp for the X-axis label. Adapts based on the timeframe.
     *
     * `PERF` Reuses hoisted formatters + a scratch Date — no per-call allocation.
     */
    fun formatTime(timestamp: Long, timeframe: Timeframe): String {
        val sdf = if (timeframe.minutes >= 1440) dateAxisFormat else timeAxisFormat
        scratchDate.time = timestamp
        return sdf.format(scratchDate)
    }

    // ========================================================================
    // SNAPSHOT / RESTORE
    // ========================================================================

    fun snapshotState(): ChartViewportState = ChartViewportState(
        startIndex = startIndex,
        visibleBars = visibleBars,
        priceHigh = priceHigh,
        priceLow = priceLow,
    )

    fun restoreState(state: ChartViewportState, total: Int) {
        startIndex = state.startIndex
        visibleBars = state.visibleBars
        priceHigh = state.priceHigh
        priceLow = state.priceLow
        clamp(total)
        stopFling()
    }

    fun copyState(): ChartViewport =
        ChartViewport(startIndex, visibleBars, priceHigh, priceLow)

    companion object {
        /** Max zoom-in: never fewer than this many bars on screen. */
        const val MIN_VISIBLE_BARS = 10f

        /** Max zoom-out: bounds the cull-loop cost (DEVELOPMENT.md §4.8). */
        const val MAX_VISIBLE_BARS = 100_000f

        /** Default window on first layout / after "go to now". */
        const val DEFAULT_VISIBLE_BARS = 80f

        /**
         * Per-second velocity retention during a fling (`v *= friction^dt`).
         * 0.06 ≈ a ~1.2 s glide, tuned to feel like a native Android scroller.
         */
        const val FLING_FRICTION = 0.06f

        /** Below this speed the fling is considered settled. */
        const val MIN_FLING_BARS_PER_SEC = 0.5f

        /** Safety cap so a violent swipe cannot teleport across the dataset. */
        const val MAX_FLING_BARS_PER_SEC = 4_000f

        /** Clamp for a single integration step (guards against frame stalls). */
        const val MAX_FLING_STEP_SECONDS = 0.05f

        /**
         * Floor applied before taking a logarithm on the log price scale.
         * ln(price) is undefined for price <= 0, so prices are clamped to this
         * tiny positive value (real instrument prices are always positive).
         */
        const val LOG_MIN_PRICE = 1e-9
    }
}
