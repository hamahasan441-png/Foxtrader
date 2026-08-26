package com.foxtrader.app.feature.chart.presentation.components

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.ChartDrawing
import com.foxtrader.app.domain.model.ChartPoint
import com.foxtrader.app.domain.model.DrawingToolType
import com.foxtrader.app.feature.chart.presentation.chartOverlayLabelBaseline
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val FIB_LEVELS = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)
private val FIB_LABELS = listOf("0%", "23.6%", "38.2%", "50%", "61.8%", "78.6%", "100%")
private val FIB_EXT_LABELS = listOf("0%", "61.8%", "100%", "127.2%", "161.8%", "200%", "261.8%")
private val PositionRewardColor = Color(0xFF26A69A)
private val PositionRiskColor = Color(0xFFEF5350)
private val PositionEntryColor = Color(0xFFB0BEC5)
private val PreviewDash = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))
private val PreviewColor = Color(0xCCD4A84E)
private val PreviewAnchorColor = Color(0xFFD4A84E)

/**
 * User drawing renderer with a strict fail-closed boundary.
 *
 * Drawings are persisted and can outlive chart/provider versions. Treat every
 * point, line width and derived target as untrusted: a corrupted/legacy NaN or
 * reversed rectangle must never be allowed to crash the Canvas render thread.
 */
fun DrawScope.drawChartDrawings(
    drawings: List<ChartDrawing>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    fibLabelPaint: Paint,
) {
    if (!cw.isDrawableSpan() || !ch.isDrawableSpan()) return
    for (drawing in drawings) {
        if (!drawing.isVisible || !drawing.safeLineWidth().isFinite()) continue
        val color = Color(drawing.color)
        when (drawing.type) {
            DrawingToolType.TREND_LINE -> drawTrendLine(drawing, viewport, cw, ch, color)
            DrawingToolType.HORIZONTAL_LINE -> drawHorizontalLine(drawing, viewport, cw, ch, color)
            DrawingToolType.VERTICAL_LINE -> drawVerticalLine(drawing, viewport, cw, ch, color)
            DrawingToolType.FIBONACCI_RETRACEMENT -> drawFibRetracement(drawing, viewport, cw, ch, color, fibLabelPaint)
            DrawingToolType.RECTANGLE -> drawRectangleDrawing(drawing, viewport, cw, ch, color)
            DrawingToolType.RAY -> drawRay(drawing, viewport, cw, ch, color)
            DrawingToolType.FIBONACCI_EXTENSION -> drawFibExtension(drawing, viewport, cw, ch, color, fibLabelPaint)
            DrawingToolType.MEASURED_MOVE -> drawMeasuredMove(drawing, viewport, cw, ch, color, fibLabelPaint)
            DrawingToolType.LONG_POSITION, DrawingToolType.SHORT_POSITION ->
                drawPosition(drawing, viewport, cw, ch, fibLabelPaint)
        }
    }
}

fun DrawScope.drawPlacementPreview(
    firstPoint: ChartPoint,
    cursorX: Float,
    cursorY: Float,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (!firstPoint.isDrawablePoint() || !cursorX.isFinite() || !cursorY.isFinite()) return
    if (!cw.isDrawableSpan() || !ch.isDrawableSpan()) return
    val startX = viewport.xForIndex(firstPoint.index, cw)
    val startY = viewport.yForPrice(firstPoint.price, ch)
    if (!startX.isFinite() || !startY.isFinite()) return

    drawLine(
        color = PreviewColor,
        start = Offset(startX, startY),
        end = Offset(cursorX.coerceIn(0f, cw), cursorY.coerceIn(0f, ch)),
        strokeWidth = 1.5f,
        pathEffect = PreviewDash,
    )
    drawCircle(color = PreviewAnchorColor, radius = 5f, center = Offset(startX, startY))
}

private fun DrawScope.drawTrendLine(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    val pair = drawing.drawablePair() ?: return
    val (p1, p2) = pair
    val a = pointOffset(p1, viewport, cw, ch) ?: return
    val b = pointOffset(p2, viewport, cw, ch) ?: return
    val width = drawing.safeLineWidth()
    drawLine(color = color, start = a, end = b, strokeWidth = width)
    drawCircle(color = color, radius = 4f, center = a)
    drawCircle(color = color, radius = 4f, center = b)
}

private fun DrawScope.drawHorizontalLine(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    val point = drawing.points.firstOrNull()?.takeIf { it.isDrawablePoint() } ?: return
    val y = viewport.yForPrice(point.price, ch)
    if (!y.isFinite() || y !in 0f..ch) return
    drawLine(color, Offset(0f, y), Offset(cw, y), strokeWidth = drawing.safeLineWidth())
}

private fun DrawScope.drawVerticalLine(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    val point = drawing.points.firstOrNull()?.takeIf { it.isDrawablePoint() } ?: return
    val x = viewport.xForIndex(point.index, cw)
    if (!x.isFinite() || x !in 0f..cw) return
    drawLine(color, Offset(x, 0f), Offset(x, ch), strokeWidth = drawing.safeLineWidth())
}

private fun DrawScope.drawFibRetracement(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
    labelPaint: Paint,
) {
    val pair = drawing.drawablePair() ?: return
    val (p1, p2) = pair
    val high = max(p1.price, p2.price)
    val low = min(p1.price, p2.price)
    val range = high - low
    if (!range.isFinite() || range <= MIN_PRICE_SPAN) return
    val xSpan = drawingXSpan(p1, p2, viewport, cw) ?: return

    for ((i, level) in FIB_LEVELS.withIndex()) {
        val price = high - range * level
        if (!price.isDrawablePrice()) continue
        val y = viewport.yForPrice(price, ch)
        if (!y.isFinite() || y !in 0f..ch) continue
        val alpha = if (level == 0.5 || level == 0.618) 1f else 0.6f
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(xSpan.first, y),
            end = Offset(xSpan.second, y),
            strokeWidth = if (level == 0.5 || level == 0.618) 1.2f else 0.8f,
        )
        labelPaint.setDrawingColor(color, alpha)
        val labelBaseline = chartOverlayLabelBaseline(
            y + labelPaint.textSize / 3f,
            labelPaint.textSize,
            ch,
            bottomPadding = 0f,
        ) ?: continue
        drawContext.canvas.nativeCanvas.drawText(
            FIB_LABELS[i],
            (xSpan.second + 4f).coerceAtMost(cw),
            labelBaseline,
            labelPaint,
        )
    }

    val y50 = viewport.yForPrice(high - range * 0.5, ch)
    val y618 = viewport.yForPrice(high - range * 0.618, ch)
    val ySpan = verticalSpan(y50, y618, ch) ?: return
    drawRect(
        color = color.copy(alpha = 0.08f),
        topLeft = Offset(xSpan.first, ySpan.first),
        size = Size(xSpan.second - xSpan.first, ySpan.second - ySpan.first),
    )
}

private fun DrawScope.drawRectangleDrawing(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    val pair = drawing.drawablePair() ?: return
    val (p1, p2) = pair
    val xSpan = drawingXSpan(p1, p2, viewport, cw) ?: return
    val ySpan = verticalSpan(
        viewport.yForPrice(max(p1.price, p2.price), ch),
        viewport.yForPrice(min(p1.price, p2.price), ch),
        ch,
    ) ?: return
    val size = Size(xSpan.second - xSpan.first, ySpan.second - ySpan.first)
    drawRect(color.copy(alpha = 0.1f), Offset(xSpan.first, ySpan.first), size)
    drawRect(
        color = color.copy(alpha = 0.7f),
        topLeft = Offset(xSpan.first, ySpan.first),
        size = size,
        style = Stroke(width = drawing.safeLineWidth()),
    )
}

private fun DrawScope.drawRay(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    val pair = drawing.drawablePair() ?: return
    val a = pointOffset(pair.first, viewport, cw, ch) ?: return
    val b = pointOffset(pair.second, viewport, cw, ch) ?: return
    val dx = b.x - a.x
    val dy = b.y - a.y
    if (!dx.isFinite() || !dy.isFinite()) return

    val endX: Float
    val endY: Float
    if (abs(dx) < MIN_PIXEL_SPAN) {
        endX = a.x
        endY = if (dy >= 0f) ch else 0f
    } else {
        endX = if (dx > 0f) cw else 0f
        val t = (endX - a.x) / dx
        val projected = a.y + dy * t
        if (!t.isFinite() || !projected.isFinite()) return
        endY = projected.coerceIn(0f, ch)
    }
    drawLine(color, a, Offset(endX, endY), strokeWidth = drawing.safeLineWidth())
    drawCircle(color = color, radius = 4f, center = a)
}

private fun DrawScope.drawFibExtension(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
    labelPaint: Paint,
) {
    val pair = drawing.drawablePair() ?: return
    val xSpan = drawingXSpan(pair.first, pair.second, viewport, cw) ?: return
    val levels = drawing.fibExtensionLevels
    if (levels.isEmpty()) return

    for ((i, price) in levels.withIndex()) {
        if (!price.isDrawablePrice()) continue
        val y = viewport.yForPrice(price, ch)
        if (!y.isFinite() || y !in 0f..ch) continue
        val label = FIB_EXT_LABELS.getOrElse(i) { "" }
        val emphasize = label == "100%" || label == "161.8%"
        drawLine(
            color = color.copy(alpha = if (emphasize) 1f else 0.6f),
            start = Offset(xSpan.first, y),
            end = Offset(xSpan.second, y),
            strokeWidth = if (emphasize) 1.2f else 0.8f,
        )
        labelPaint.setDrawingColor(color, 1f)
        val labelBaseline = chartOverlayLabelBaseline(
            y + labelPaint.textSize / 3f,
            labelPaint.textSize,
            ch,
            bottomPadding = 0f,
        ) ?: continue
        drawContext.canvas.nativeCanvas.drawText(
            label,
            (xSpan.second + 4f).coerceAtMost(cw),
            labelBaseline,
            labelPaint,
        )
    }
}

private fun DrawScope.drawPosition(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    val pair = drawing.drawablePair() ?: return
    val levels = drawing.positionLevels ?: return
    val (entry, stop, target) = levels
    if (!entry.isDrawablePrice() || !stop.isDrawablePrice() || !target.isDrawablePrice()) return
    val correctSide = when (drawing.type) {
        DrawingToolType.LONG_POSITION -> stop < entry && target > entry
        DrawingToolType.SHORT_POSITION -> stop > entry && target < entry
        else -> false
    }
    if (!correctSide || abs(entry - stop) <= MIN_PRICE_SPAN) return

    val xSpan = drawingXSpan(pair.first, pair.second, viewport, cw) ?: return
    val yEntry = viewport.yForPrice(entry, ch)
    val yStop = viewport.yForPrice(stop, ch)
    val yTarget = viewport.yForPrice(target, ch)
    if (!yEntry.isFinite() || !yStop.isFinite() || !yTarget.isFinite()) return
    val reward = verticalSpan(yEntry, yTarget, ch)
    val risk = verticalSpan(yEntry, yStop, ch)

    reward?.let {
        drawRect(
            PositionRewardColor.copy(alpha = 0.15f),
            Offset(xSpan.first, it.first),
            Size(xSpan.second - xSpan.first, it.second - it.first),
        )
    }
    risk?.let {
        drawRect(
            PositionRiskColor.copy(alpha = 0.15f),
            Offset(xSpan.first, it.first),
            Size(xSpan.second - xSpan.first, it.second - it.first),
        )
    }
    drawVisibleHorizontal(PositionRewardColor, xSpan, yTarget, ch, 1f)
    drawVisibleHorizontal(PositionRiskColor, xSpan, yStop, ch, 1f)
    drawVisibleHorizontal(PositionEntryColor, xSpan, yEntry, ch, 1.4f)

    if (yTarget in 0f..ch) {
        labelPaint.color = android.graphics.Color.argb(220, 176, 190, 197)
        chartOverlayLabelBaseline(
            yTarget + labelPaint.textSize / 3f,
            labelPaint.textSize,
            ch,
            bottomPadding = 0f,
        )?.let { labelBaseline ->
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.US, "%.1fR", ChartDrawing.POSITION_RR),
                (xSpan.second + 4f).coerceAtMost(cw),
                labelBaseline,
                labelPaint,
            )
        }
    }
}

private fun DrawScope.drawMeasuredMove(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
    labelPaint: Paint,
) {
    val pair = drawing.drawablePair() ?: return
    val (a, b) = pair
    val oa = pointOffset(a, viewport, cw, ch) ?: return
    val ob = pointOffset(b, viewport, cw, ch) ?: return
    val width = drawing.safeLineWidth()
    drawLine(color, oa, ob, strokeWidth = width)
    drawCircle(color, radius = 4f, center = oa)
    drawCircle(color, radius = 4f, center = ob)

    val cIndex = b.index + (b.index - a.index)
    val cPrice = b.price + (b.price - a.price)
    if (!cIndex.isFinite() || !cPrice.isDrawablePrice()) return
    val xc = viewport.xForIndex(cIndex, cw)
    val yc = viewport.yForPrice(cPrice, ch)
    if (!xc.isFinite() || !yc.isFinite()) return
    drawLine(
        color = color.copy(alpha = 0.6f),
        start = ob,
        end = Offset(xc, yc),
        strokeWidth = width,
        pathEffect = PreviewDash,
    )
    if (xc in 0f..cw && yc in 0f..ch) {
        drawCircle(color = color.copy(alpha = 0.6f), radius = 4f, center = Offset(xc, yc))
        labelPaint.setDrawingColor(color, 1f)
        chartOverlayLabelBaseline(
            yc + labelPaint.textSize / 3f,
            labelPaint.textSize,
            ch,
            bottomPadding = 0f,
        )?.let { labelBaseline ->
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.US, "%.5f", abs(b.price - a.price)),
                (xc + 4f).coerceIn(0f, cw),
                labelBaseline,
                labelPaint,
            )
        }
    }
}

private fun DrawScope.drawVisibleHorizontal(
    color: Color,
    xSpan: Pair<Float, Float>,
    y: Float,
    ch: Float,
    width: Float,
) {
    if (!y.isFinite() || y !in 0f..ch) return
    drawLine(color, Offset(xSpan.first, y), Offset(xSpan.second, y), strokeWidth = width)
}

private fun pointOffset(
    point: ChartPoint,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
): Offset? {
    if (!point.isDrawablePoint()) return null
    val x = viewport.xForIndex(point.index, cw)
    val y = viewport.yForPrice(point.price, ch)
    return if (x.isFinite() && y.isFinite()) Offset(x, y) else null
}

private fun drawingXSpan(
    p1: ChartPoint,
    p2: ChartPoint,
    viewport: ChartViewport,
    cw: Float,
): Pair<Float, Float>? {
    val raw1 = viewport.xForIndex(min(p1.index, p2.index), cw)
    val raw2 = viewport.xForIndex(max(p1.index, p2.index), cw)
    if (!raw1.isFinite() || !raw2.isFinite()) return null
    val left = min(raw1, raw2).coerceIn(0f, cw)
    val right = max(raw1, raw2).coerceIn(0f, cw)
    return if (right - left >= MIN_PIXEL_SPAN) left to right else null
}

private fun verticalSpan(a: Float, b: Float, height: Float): Pair<Float, Float>? {
    if (!a.isFinite() || !b.isFinite() || !height.isDrawableSpan()) return null
    val top = min(a, b).coerceIn(0f, height)
    val bottom = max(a, b).coerceIn(0f, height)
    return if (bottom - top >= MIN_PIXEL_SPAN) top to bottom else null
}

private fun ChartDrawing.drawablePair(): Pair<ChartPoint, ChartPoint>? {
    if (points.size < 2) return null
    val p1 = points[0]
    val p2 = points[1]
    return if (p1.isDrawablePoint() && p2.isDrawablePoint()) p1 to p2 else null
}

private fun ChartDrawing.safeLineWidth(): Float =
    lineWidth.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.5f, 8f) ?: 1.5f

private fun ChartPoint.isDrawablePoint(): Boolean =
    index.isFinite() && price.isDrawablePrice()

private fun Double.isDrawablePrice(): Boolean = isFinite() && this > 0.0
private fun Float.isDrawableSpan(): Boolean = isFinite() && this > 0f

private fun Paint.setDrawingColor(color: Color, alpha: Float) {
    val safeAlpha = alpha.coerceIn(0f, 1f)
    this.color = android.graphics.Color.argb(
        (safeAlpha * 200).toInt(),
        (color.red.coerceIn(0f, 1f) * 255).toInt(),
        (color.green.coerceIn(0f, 1f) * 255).toInt(),
        (color.blue.coerceIn(0f, 1f) * 255).toInt(),
    )
}

private const val MIN_PRICE_SPAN = 1e-12
private const val MIN_PIXEL_SPAN = 0.25f
