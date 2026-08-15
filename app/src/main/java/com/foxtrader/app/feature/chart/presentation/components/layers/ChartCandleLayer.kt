package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Layer 1 — viewport-culled candle bodies and wicks.
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure draw code - no Compose state - which is what keeps it
// cheap enough for the 120fps budget.
//
// `PERF` (perf pass) The layer previously issued one Compose drawLine + one
// drawRect per candle: ~2 Canvas calls and several small allocations
// (Offset/Size/Stroke) per bar, i.e. thousands of calls per frame when zoomed
// out. It now batches everything into reusable float buffers and issues at
// most FOUR native Canvas calls per frame:
//   drawLines(bullish wicks) + drawLines(bearish wicks)
//   + one drawRect loop replaced by nativeCanvas.drawRect via shared Paint
// Buffers and Paints are retained across frames — zero allocation on the hot
// path (matching the header contract) — safe because all drawing happens on
// the single UI/render thread.

// Reusable coordinate buffers (grown geometrically, never shrunk).
private var bullLineScratch = FloatArray(1024)
private var bearLineScratch = FloatArray(1024)

// Native paints hoisted once; colors resolved lazily from the theme colors.
private val bullPaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
private val bearPaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
private var paintsInitialised = false

private fun ensurePaints() {
    if (paintsInitialised) return
    bullPaint.color = FoxBullish.toArgb()
    bearPaint.color = FoxBearish.toArgb()
    paintsInitialised = true
}

private fun ensureCapacity(buffer: FloatArray, needed: Int): FloatArray =
    if (buffer.size >= needed) buffer
    else FloatArray(Integer.highestOneBit(needed - 1) shl 1)

/** Candle bodies + wicks — the heart of the chart. Viewport-culled.
 *
 * TradingView-style rendering:
 * - Clean, high-contrast green/red candles
 * - Wider bodies with thinner wicks for clear price action
 * - Doji candles rendered as visible horizontal lines
 * - Body width scales smoothly with zoom level
 * - Below ~3px/bar, candles degrade to single high-low bars (never the
 *   ambiguous "two thin lines" artefact of a body drawn at wick width)
 */
internal fun DrawScope.drawCandleLayer(
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (end <= start) return
    val barWidth = viewport.barWidthPx(cw)
    ensurePaints()

    val canvas = drawContext.canvas.nativeCanvas
    val visible = end - start
    bullLineScratch = ensureCapacity(bullLineScratch, visible * 4)
    bearLineScratch = ensureCapacity(bearLineScratch, visible * 4)
    val bullPts = bullLineScratch
    val bearPts = bearLineScratch
    var bullCount = 0
    var bearCount = 0

    // `RENDER` Below ~3px/bar a body rectangle cannot be drawn distinctly from
    // its own wick, so the layer switches to a clean single high-low bar per
    // candle.
    //
    // `PERF` Min-max pixel-column downsampling: when multiple bars land on the
    // same pixel column (deep zoom-out — up to MAX_VISIBLE_BARS = 100k), they
    // are merged into ONE column line spanning the column's total high-low
    // range, colored by the column's net direction (first open vs last close).
    // This is visually lossless at 1px resolution and bounds the emitted line
    // count by chart width instead of bar count. Everything then goes out in
    // at most two drawLines calls.
    if (barWidth < THIN_BAR_THRESHOLD_PX) {
        val lineWidth = barWidth.coerceIn(0.5f, 1.5f)
        var colX = Float.NaN
        var colHigh = 0f
        var colLow = 0f
        var colOpen = 0.0
        var colClose = 0.0

        fun flushColumn() {
            if (colX.isNaN()) return
            if (colClose >= colOpen) {
                bullPts[bullCount++] = colX; bullPts[bullCount++] = colHigh
                bullPts[bullCount++] = colX; bullPts[bullCount++] = colLow
            } else {
                bearPts[bearCount++] = colX; bearPts[bearCount++] = colHigh
                bearPts[bearCount++] = colX; bearPts[bearCount++] = colLow
            }
        }

        for (i in start until end) {
            val c = candles[i]
            val cx = viewport.xForIndex(i + 0.5f, cw)
            val yHigh = viewport.yForPrice(c.high, ch)
            val yLow = viewport.yForPrice(c.low, ch)
            // Same pixel column (within half a px) → merge into the running range.
            if (!colX.isNaN() && cx - colX < 0.5f) {
                if (yHigh < colHigh) colHigh = yHigh // y grows downward
                if (yLow > colLow) colLow = yLow
                colClose = c.close
            } else {
                flushColumn()
                colX = cx
                colHigh = yHigh
                colLow = yLow
                colOpen = c.open
                colClose = c.close
            }
        }
        flushColumn()

        bullPaint.strokeWidth = lineWidth
        bearPaint.strokeWidth = lineWidth
        if (bullCount > 0) canvas.drawLines(bullPts, 0, bullCount, bullPaint)
        if (bearCount > 0) canvas.drawLines(bearPts, 0, bearCount, bearPaint)
        return
    }

    // TradingView-style proportions:
    // - Body takes ~80% of the bar slot but always leaves a >=1px gap to the
    //   next candle so adjacent bodies never fuse into a solid block
    // - Wick is always thin (1-2px) regardless of zoom
    val bodyWidth = min(barWidth * 0.8f, barWidth - 1f).coerceAtLeast(2f)
    val wickWidth = (barWidth * 0.08f).coerceIn(1f, 2.5f)
    val halfBody = bodyWidth / 2f

    // Minimum body height so doji and very-small-range candles are visible
    val minBodyHeight = 1.5f

    // Pass 1: collect wicks per direction (drawn behind bodies).
    for (i in start until end) {
        val c = candles[i]
        val cx = viewport.xForIndex(i + 0.5f, cw)
        val yHigh = viewport.yForPrice(c.high, ch)
        val yLow = viewport.yForPrice(c.low, ch)
        if (c.isBullish) {
            bullPts[bullCount++] = cx; bullPts[bullCount++] = yHigh
            bullPts[bullCount++] = cx; bullPts[bullCount++] = yLow
        } else {
            bearPts[bearCount++] = cx; bearPts[bearCount++] = yHigh
            bearPts[bearCount++] = cx; bearPts[bearCount++] = yLow
        }
    }
    bullPaint.strokeWidth = wickWidth
    bearPaint.strokeWidth = wickWidth
    if (bullCount > 0) canvas.drawLines(bullPts, 0, bullCount, bullPaint)
    if (bearCount > 0) canvas.drawLines(bearPts, 0, bearCount, bearPaint)

    // Pass 2: bodies. drawRect on the native canvas with a shared Paint avoids
    // the per-call Offset/Size/SolidColor brush allocations of the Compose
    // overload while keeping exactly one rect per candle (rects can't batch
    // into a single call, but the per-call cost is now allocation-free).
    for (i in start until end) {
        val c = candles[i]
        val cx = viewport.xForIndex(i + 0.5f, cw)
        val yOpen = viewport.yForPrice(c.open, ch)
        val yClose = viewport.yForPrice(c.close, ch)
        val top = min(yOpen, yClose)
        val bodyH = max(minBodyHeight, abs(yClose - yOpen))
        canvas.drawRect(
            cx - halfBody,
            top,
            cx + halfBody,
            top + bodyH,
            if (c.isBullish) bullPaint else bearPaint,
        )
    }
}

/**
 * Bar width (px) below which body+wick rendering is replaced by single-line
 * bars. At 3px there is no visual room for a body rectangle distinct from the
 * wick, so drawing both only produces the "two thin lines" artefact.
 */
private const val THIN_BAR_THRESHOLD_PX = 3f
