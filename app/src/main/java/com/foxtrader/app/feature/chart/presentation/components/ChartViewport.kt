package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.runtime.Stable
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.chart.ChartScaleMode
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
    /** Linear by default; logarithmic mode is useful for multi-order-of-magnitude assets. */
    var scaleMode: ChartScaleMode = ChartScaleMode.LINEAR
        private set

    /** Switch scale mode only when every visible price is strictly positive. */
    fun setScaleMode(mode: ChartScaleMode, candles: List<Candle>): Boolean {
        if (mode == ChartScaleMode.LOGARITHMIC && candles.any { it.low <= 0.0 || !it.low.isFinite() }) {
            return false
        }
        scaleMode = mode
        autoScale(candles)
        return true
    }

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

        val maxStart = maxFutureStartIndex(total)
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
        val high = scaleValue(priceHigh)
        val low = scaleValue(priceLow)
        val value = scaleValue(price)
        val range = (high - low).coerceAtLeast(1e-9)
        return (((high - value) / range) * chartAreaHeight).toFloat()
    }

    /** Map an x pixel (in chart area) back to a bar index. */
    fun indexForX(x: Float, chartAreaWidth: Float): Float =
        startIndex + (x / chartAreaWidth) * visibleBars

    /** Map a y pixel (in chart area) back to a price. */
    fun priceForY(y: Float, chartAreaHeight: Float): Double {
        val high = scaleValue(priceHigh)
        val low = scaleValue(priceLow)
        val range = (high - low).coerceAtLeast(1e-9)
        val value = high - (y / chartAreaHeight) * range
        return unscaleValue(value)
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
        startIndex = latestPinnedStartIndex(total)
        stopFling()
    }

    /**
     * Bring [index] into view without changing the zoom.
     *
     * Placed a little right of centre so the bars that produced the signal are
     * on screen alongside what happened after it, which is the context a marker
     * is read in. Returns false when the index is already visible, so a caller
     * can use this to mean "only move if there is nothing to see".
     */
    fun focusOnIndex(index: Int, total: Int): Boolean {
        if (total <= 0 || index < 0 || index >= total) return false
        if (isIndexVisible(index)) return false
        startIndex = (index - visibleBars * FOCUS_ANCHOR_FRACTION)
            .coerceIn(0f, maxFutureStartIndex(total))
        stopFling()
        return true
    }

    /** Whether [index] currently falls inside the visible window. */
    fun isIndexVisible(index: Int): Boolean =
        index >= startIndex && index <= startIndex + visibleBars

    /**
     * Whether the camera is in the normal live-follow position.
     *
     * Panning into the deliberate future-space area must turn live-follow off;
     * otherwise a new tick would snap the latest candle back to the right edge
     * while the trader is inspecting it near the middle of the screen.
     */
    fun isAtRightEdge(total: Int, toleranceBars: Float = 1f): Boolean =
        abs(startIndex - latestPinnedStartIndex(total)) <= toleranceBars

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

    /** Normal live-follow position: newest data sits at the right edge. */
    private fun latestPinnedStartIndex(total: Int): Float =
        max(0f, total.toFloat() - visibleBars)

    /**
     * Maximum pan into empty future space. At this bound the newest candle is
     * exactly at the horizontal centre of the chart, giving the trader room to
     * inspect price action without permitting unbounded empty scrolling.
     */
    private fun maxFutureStartIndex(total: Int): Float {
        if (total <= 0) return 0f
        val latestIndex = (total - 1).toFloat()
        return max(0f, latestIndex - visibleBars * LAST_CANDLE_CENTER_FRACTION)
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
        if (scaleMode == ChartScaleMode.LOGARITHMIC && lo > 0.0) {
            // Log space needs multiplicative headroom; additive padding could
            // push a low-priced series through zero and invalidate the scale.
            val factor = (1.0 + pad).coerceAtLeast(1.0)
            priceHigh = hi * factor
            priceLow = lo / factor
        } else {
            val range = (hi - lo).coerceAtLeast(1e-9)
            val padding = range * pad
            priceHigh = hi + padding
            priceLow = lo - padding
        }
    }

    // ========================================================================
    // CLAMPING
    // ========================================================================

    /** Clamp the viewport to valid bounds. */
    fun clamp(total: Int, minBars: Float = MIN_VISIBLE_BARS, maxBars: Float = MAX_VISIBLE_BARS) {
        visibleBars = visibleBars.coerceIn(minBars, maxBars)
        val maxStart = maxFutureStartIndex(total)
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

    private var priceGridCacheHigh = Double.NaN
    private var priceGridCacheLow = Double.NaN
    private var priceGridCacheMode = ChartScaleMode.LINEAR
    private var priceGridCacheTarget = 0
    private val priceGridScratch = DoubleArray(MAX_PRICE_GRID_LEVELS)
    private var priceGridCount = 0

    // `PERF` Formatted grid labels, cached with the same key as the levels.
    // String.format is comparatively expensive (locale lookup + parse of the
    // format string); re-running it for every label on every frame — including
    // crosshair-only frames where the price window is unchanged — was wasted
    // work. Entries are formatted lazily on first read after a rebuild.
    private val priceGridLabels = arrayOfNulls<String>(MAX_PRICE_GRID_LEVELS)

    /**
     * Rebuilds a preallocated, human-friendly level buffer for both scale modes.
     * Logarithmic mode uses 1/2/5 levels per decade instead of pretending that
     * equal arithmetic steps are visually equal distances. The draw pass gets
     * an index/count API so this stays allocation-free.
     */
    fun priceGridLevelCount(targetLines: Int = 6): Int {
        if (
            priceGridCacheHigh == priceHigh &&
            priceGridCacheLow == priceLow &&
            priceGridCacheMode == scaleMode &&
            priceGridCacheTarget == targetLines
        ) return priceGridCount

        var count = 0
        if (scaleMode == ChartScaleMode.LOGARITHMIC && priceLow > 0.0) {
            val minExponent = floor(log10(priceLow)).toInt() - 1
            val maxExponent = kotlin.math.ceil(log10(priceHigh)).toInt() + 1
            for (exponent in minExponent..maxExponent) {
                val base = 10.0.pow(exponent)
                for (multiplierIndex in 0..2) {
                    val multiplier = when (multiplierIndex) {
                        0 -> 1.0
                        1 -> 2.0
                        else -> 5.0
                    }
                    val level = base * multiplier
                    if (level in priceLow..priceHigh && count < priceGridScratch.size) {
                        priceGridScratch[count++] = level
                    }
                }
            }
        } else {
            val step = niceStep(targetLines)
            if (step > 0.0) {
                val first = kotlin.math.ceil(priceLow / step) * step
                val possible = ((priceHigh - first) / step).toInt().coerceAtLeast(0) + 1
                count = possible.coerceAtMost(priceGridScratch.size)
                for (index in 0 until count) priceGridScratch[index] = first + index * step
            }
        }
        priceGridCacheHigh = priceHigh
        priceGridCacheLow = priceLow
        priceGridCacheMode = scaleMode
        priceGridCacheTarget = targetLines
        priceGridCount = count
        // Invalidate cached labels; they re-format lazily on first read.
        java.util.Arrays.fill(priceGridLabels, 0, MAX_PRICE_GRID_LEVELS, null)
        return count
    }

    fun priceGridLevel(index: Int): Double = priceGridScratch[index]

    /** Formatted label for a grid level, cached until the grid is rebuilt. */
    fun priceGridLabel(index: Int): String =
        priceGridLabels[index] ?: formatPrice(priceGridScratch[index]).also { priceGridLabels[index] = it }

    // `PERF` Single-entry memo for the live last-price tag: the close only
    // changes on a tick, but the tag redraws on every pan/zoom/crosshair frame.
    private var lastPriceMemoValue = Double.NaN
    private var lastPriceMemoLabel = ""

    /** Formatted last price, re-formatted only when the value changes. */
    fun formatPriceMemo(price: Double): String {
        if (price != lastPriceMemoValue) {
            lastPriceMemoValue = price
            lastPriceMemoLabel = formatPrice(price)
        }
        return lastPriceMemoLabel
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

    private fun scaleValue(value: Double): Double =
        if (scaleMode == ChartScaleMode.LOGARITHMIC && value > 0.0) ln(value) else value

    private fun unscaleValue(value: Double): Double =
        if (scaleMode == ChartScaleMode.LOGARITHMIC) exp(value) else value

    // ========================================================================
    // SNAPSHOT / RESTORE
    // ========================================================================

    fun snapshotState(): ChartViewportState = ChartViewportState(
        startIndex = startIndex,
        visibleBars = visibleBars,
        priceHigh = priceHigh,
        priceLow = priceLow,
        scaleMode = scaleMode,
    )

    fun restoreState(state: ChartViewportState, total: Int) {
        startIndex = state.startIndex
        visibleBars = state.visibleBars
        priceHigh = state.priceHigh
        priceLow = state.priceLow
        scaleMode = state.scaleMode
        clamp(total)
        stopFling()
    }

    fun copyState(): ChartViewport =
        ChartViewport(startIndex, visibleBars, priceHigh, priceLow).also {
            it.scaleMode = scaleMode
        }

    companion object {
        /** Max zoom-in: never fewer than this many bars on screen. */
        const val MIN_VISIBLE_BARS = 10f

        /** Max zoom-out: bounds the cull-loop cost (DEVELOPMENT.md §4.8). */
        const val MAX_VISIBLE_BARS = 100_000f

        /** Default window on first layout / after "go to now". */
        const val DEFAULT_VISIBLE_BARS = 80f

        /**
         * Where a focused index lands across the screen.
         *
         * Right of centre, so the bars that produced the marker are visible
         * alongside what price did afterwards.
         */
        const val FOCUS_ANCHOR_FRACTION = 0.6f

        /** Furthest allowed future-space pan places the newest candle at 50% width. */
        const val LAST_CANDLE_CENTER_FRACTION = 0.5f

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

        /** Preallocated scale-level capacity for normal and logarithmic modes. */
        private const val MAX_PRICE_GRID_LEVELS = 64
    }
}
