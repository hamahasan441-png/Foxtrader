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
// `PERF` The layer batches wick coordinates into reusable float buffers and
// uses native Canvas paints for bodies. This keeps the hot path allocation-free.

private var bullLineScratch = FloatArray(1024)
private var bearLineScratch = FloatArray(1024)

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

/**
 * Candle bodies + wicks — viewport culled and tuned for phone readability.
 *
 * The body now uses a slightly larger share of each bar slot than before while
 * preserving a visible inter-candle gap. This makes candles easier to read on a
 * small Android screen without changing viewport math or reducing history depth.
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
            if (!colX.isNaN() && cx - colX < 0.5f) {
                if (yHigh < colHigh) colHigh = yHigh
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

    // Slightly larger phone-first proportions. A >=0.8px slot gap remains so
    // adjacent bodies never visually fuse into one solid block.
    val bodyWidth = min(barWidth * 0.86f, barWidth - 0.8f).coerceAtLeast(2.4f)
    val wickWidth = (barWidth * 0.09f).coerceIn(1f, 2.5f)
    val halfBody = bodyWidth / 2f
    val minBodyHeight = 1.7f

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

private const val THIN_BAR_THRESHOLD_PX = 3f
