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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Drawing tools chart renderer.
 *
 * Renders user-placed drawings on the chart canvas:
 * - Trend lines (two-point line)
 * - Horizontal lines (infinite width at a price)
 * - Vertical lines (infinite height at an index)
 * - Fibonacci retracements (multiple horizontal levels between two points)
 * - Rectangles (filled zone between two points)
 * - Rays (line extending from first point through second to chart edge)
 *
 * Also renders:
 * - Anchor points (small circles at drawing endpoints for editing)
 * - Preview line during placement (from first point to cursor)
 */

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
 * Draw all visible chart drawings.
 */
fun DrawScope.drawChartDrawings(
    drawings: List<ChartDrawing>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    fibLabelPaint: Paint,
) {
    for (drawing in drawings) {
        if (!drawing.isVisible) continue
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

/**
 * Draw a preview line from the first placed point to the current cursor.
 */
fun DrawScope.drawPlacementPreview(
    firstPoint: ChartPoint,
    cursorX: Float,
    cursorY: Float,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val startX = viewport.xForIndex(firstPoint.index, cw)
    val startY = viewport.yForPrice(firstPoint.price, ch)

    drawLine(
        color = PreviewColor,
        start = Offset(startX, startY),
        end = Offset(cursorX, cursorY),
        strokeWidth = 1.5f,
        pathEffect = PreviewDash,
    )
    // Anchor at first point
    drawCircle(color = PreviewAnchorColor, radius = 5f, center = Offset(startX, startY))
}

// ============================================================================
// INDIVIDUAL DRAWING TYPE RENDERERS
// ============================================================================

private fun DrawScope.drawTrendLine(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    if (drawing.points.size < 2) return
    val p1 = drawing.points[0]
    val p2 = drawing.points[1]

    val x1 = viewport.xForIndex(p1.index, cw)
    val y1 = viewport.yForPrice(p1.price, ch)
    val x2 = viewport.xForIndex(p2.index, cw)
    val y2 = viewport.yForPrice(p2.price, ch)

    drawLine(
        color = color,
        start = Offset(x1, y1),
        end = Offset(x2, y2),
        strokeWidth = drawing.lineWidth,
    )
    // Anchors
    drawCircle(color = color, radius = 4f, center = Offset(x1, y1))
    drawCircle(color = color, radius = 4f, center = Offset(x2, y2))
}

private fun DrawScope.drawHorizontalLine(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    if (drawing.points.isEmpty()) return
    val price = drawing.points[0].price
    val y = viewport.yForPrice(price, ch)
    if (y < 0f || y > ch) return

    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(cw, y),
        strokeWidth = drawing.lineWidth,
    )
}

private fun DrawScope.drawVerticalLine(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    if (drawing.points.isEmpty()) return
    val x = viewport.xForIndex(drawing.points[0].index, cw)
    if (x < 0f || x > cw) return

    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, ch),
        strokeWidth = drawing.lineWidth,
    )
}

private fun DrawScope.drawFibRetracement(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
    labelPaint: Paint,
) {
    if (drawing.points.size < 2) return
    val p1 = drawing.points[0]
    val p2 = drawing.points[1]
    val high = maxOf(p1.price, p2.price)
    val low = minOf(p1.price, p2.price)
    val range = high - low
    if (range <= 0) return

    val x1 = viewport.xForIndex(min(p1.index, p2.index), cw).coerceAtLeast(0f)
    val x2 = viewport.xForIndex(max(p1.index, p2.index), cw).coerceAtMost(cw)

    for ((i, level) in FIB_LEVELS.withIndex()) {
        val price = high - range * level
        val y = viewport.yForPrice(price, ch)
        if (y < 0f || y > ch) continue

        val alpha = if (level == 0.5 || level == 0.618) 1f else 0.6f
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(x1, y),
            end = Offset(x2, y),
            strokeWidth = if (level == 0.5 || level == 0.618) 1.2f else 0.8f,
        )

        // Label
        labelPaint.color = android.graphics.Color.argb(
            (alpha * 200).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
        )
        drawContext.canvas.nativeCanvas.drawText(
            FIB_LABELS[i],
            x2 + 4f,
            y + labelPaint.textSize / 3f,
            labelPaint,
        )
    }

    // Fill between 0.5 and 0.618 (golden zone)
    val y50 = viewport.yForPrice(high - range * 0.5, ch)
    val y618 = viewport.yForPrice(high - range * 0.618, ch)
    drawRect(
        color = color.copy(alpha = 0.08f),
        topLeft = Offset(x1, min(y50, y618)),
        size = Size(x2 - x1, kotlin.math.abs(y618 - y50)),
    )
}

private fun DrawScope.drawRectangleDrawing(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    if (drawing.points.size < 2) return
    val p1 = drawing.points[0]
    val p2 = drawing.points[1]

    val x1 = viewport.xForIndex(min(p1.index, p2.index), cw)
    val x2 = viewport.xForIndex(max(p1.index, p2.index), cw)
    val y1 = viewport.yForPrice(maxOf(p1.price, p2.price), ch)
    val y2 = viewport.yForPrice(minOf(p1.price, p2.price), ch)

    // Fill
    drawRect(
        color = color.copy(alpha = 0.1f),
        topLeft = Offset(x1, y1),
        size = Size(x2 - x1, y2 - y1),
    )
    // Border
    drawRect(
        color = color.copy(alpha = 0.7f),
        topLeft = Offset(x1, y1),
        size = Size(x2 - x1, y2 - y1),
        style = Stroke(width = drawing.lineWidth),
    )
}

private fun DrawScope.drawRay(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
) {
    if (drawing.points.size < 2) return
    val p1 = drawing.points[0]
    val p2 = drawing.points[1]

    val x1 = viewport.xForIndex(p1.index, cw)
    val y1 = viewport.yForPrice(p1.price, ch)
    val x2 = viewport.xForIndex(p2.index, cw)
    val y2 = viewport.yForPrice(p2.price, ch)

    // Extend the line from p1 through p2 to the edge of the chart
    val dx = x2 - x1
    val dy = y2 - y1
    val endX = if (dx > 0) cw else 0f
    val t = if (dx != 0f) (endX - x1) / dx else 10f
    val endY = y1 + dy * t

    drawLine(
        color = color,
        start = Offset(x1, y1),
        end = Offset(endX, endY.coerceIn(0f, ch)),
        strokeWidth = drawing.lineWidth,
    )
    drawCircle(color = color, radius = 4f, center = Offset(x1, y1))
}

/**
 * Fibonacci extension: horizontal levels projected along the 2-point move
 * (127.2% / 161.8% / … beyond the swing) as forward profit targets.
 */
private fun DrawScope.drawFibExtension(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
    labelPaint: Paint,
) {
    val levels = drawing.fibExtensionLevels
    if (levels.isEmpty()) return
    val p1 = drawing.points[0]
    val p2 = drawing.points[1]
    val x1 = viewport.xForIndex(min(p1.index, p2.index), cw).coerceAtLeast(0f)
    val x2 = viewport.xForIndex(max(p1.index, p2.index), cw).coerceAtMost(cw)

    for ((i, price) in levels.withIndex()) {
        val y = viewport.yForPrice(price, ch)
        if (y < 0f || y > ch) continue
        val label = FIB_EXT_LABELS.getOrElse(i) { "" }
        val emphasize = label == "100%" || label == "161.8%"
        drawLine(
            color = color.copy(alpha = if (emphasize) 1f else 0.6f),
            start = Offset(x1, y),
            end = Offset(x2, y),
            strokeWidth = if (emphasize) 1.2f else 0.8f,
        )
        labelPaint.color = android.graphics.Color.argb(
            200,
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
        )
        drawContext.canvas.nativeCanvas.drawText(label, x2 + 4f, y + labelPaint.textSize / 3f, labelPaint)
    }
}

/**
 * Long/short position tool: a green reward box (entry → target) stacked on a red
 * risk box (entry → stop), with the entry line drawn between them. Target is
 * projected at [ChartDrawing.POSITION_RR] reward:risk and shown with its R value.
 */
private fun DrawScope.drawPosition(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    val levels = drawing.positionLevels ?: return
    val (entry, stop, target) = levels
    val p1 = drawing.points[0]
    val p2 = drawing.points[1]
    val x1 = viewport.xForIndex(min(p1.index, p2.index), cw)
    val x2 = viewport.xForIndex(max(p1.index, p2.index), cw)
    val width = (x2 - x1).coerceAtLeast(1f)
    val yEntry = viewport.yForPrice(entry, ch)
    val yStop = viewport.yForPrice(stop, ch)
    val yTarget = viewport.yForPrice(target, ch)

    // Reward zone (entry → target).
    drawRect(
        color = PositionRewardColor.copy(alpha = 0.15f),
        topLeft = Offset(x1, min(yEntry, yTarget)),
        size = Size(width, abs(yTarget - yEntry)),
    )
    // Risk zone (entry → stop).
    drawRect(
        color = PositionRiskColor.copy(alpha = 0.15f),
        topLeft = Offset(x1, min(yEntry, yStop)),
        size = Size(width, abs(yStop - yEntry)),
    )
    drawLine(PositionRewardColor, Offset(x1, yTarget), Offset(x2, yTarget), strokeWidth = 1f)
    drawLine(PositionRiskColor, Offset(x1, yStop), Offset(x2, yStop), strokeWidth = 1f)
    drawLine(PositionEntryColor, Offset(x1, yEntry), Offset(x2, yEntry), strokeWidth = 1.4f)

    labelPaint.color = android.graphics.Color.argb(220, 176, 190, 197)
    drawContext.canvas.nativeCanvas.drawText(
        String.format(Locale.US, "%.1fR", ChartDrawing.POSITION_RR),
        x2 + 4f,
        yTarget + labelPaint.textSize / 3f,
        labelPaint,
    )
}

/**
 * Measured move: the base A→B leg plus a dashed projection of the same signed
 * displacement from B, labelled with the move size — the classic "expect an
 * equal leg" target projection.
 */
private fun DrawScope.drawMeasuredMove(
    drawing: ChartDrawing,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
    labelPaint: Paint,
) {
    if (drawing.points.size < 2) return
    val a = drawing.points[0]
    val b = drawing.points[1]
    val xa = viewport.xForIndex(a.index, cw)
    val ya = viewport.yForPrice(a.price, ch)
    val xb = viewport.xForIndex(b.index, cw)
    val yb = viewport.yForPrice(b.price, ch)

    // Base leg A → B.
    drawLine(color, Offset(xa, ya), Offset(xb, yb), strokeWidth = drawing.lineWidth)
    drawCircle(color, radius = 4f, center = Offset(xa, ya))
    drawCircle(color, radius = 4f, center = Offset(xb, yb))

    // Projected equal leg from B.
    val cIndex = b.index + (b.index - a.index)
    val cPrice = b.price + (b.price - a.price)
    val xc = viewport.xForIndex(cIndex, cw)
    val yc = viewport.yForPrice(cPrice, ch)
    drawLine(
        color = color.copy(alpha = 0.6f),
        start = Offset(xb, yb),
        end = Offset(xc, yc),
        strokeWidth = drawing.lineWidth,
        pathEffect = PreviewDash,
    )
    drawCircle(color = color.copy(alpha = 0.6f), radius = 4f, center = Offset(xc, yc))

    labelPaint.color = android.graphics.Color.argb(
        220,
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt(),
    )
    drawContext.canvas.nativeCanvas.drawText(
        String.format(Locale.US, "%.5f", abs(b.price - a.price)),
        xc + 4f,
        yc + labelPaint.textSize / 3f,
        labelPaint,
    )
}
