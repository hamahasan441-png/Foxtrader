package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalSetup
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport

private val RsiReversalBuyPoint = Color(0xFF4FC3A1)
private val RsiReversalSellPoint = Color(0xFFE8836B)
private val RsiReversalLegDash = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))

private const val LABEL_DP = 8.5f
private const val MARKER_RADIUS_DP = 2.6f
private const val MAX_DRAWN_SETUPS = 3

/**
 * Developer/debug view of the pattern points (§23).
 *
 * Off by default, and deliberately so: the chart's job is to show the one thing
 * a trader acts on, which is the entry arrow drawn by the shared signal layer.
 * This exists to answer "why did that arrow appear" — it marks P1, P2, the
 * final extreme and any recursive extremes between them, joined by the leg that
 * carried the divergence.
 *
 * Only the most recent few setups are drawn. Older ones are already resolved,
 * and painting every setup in the loaded history would bury the live one.
 */
internal fun DrawScope.drawRsiReversalDebugPoints(
    setups: List<RsiReversalSetup>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    if (setups.isEmpty()) return
    if (!cw.isFinite() || cw <= 0f || !ch.isFinite() || ch <= 0f) return

    val scale = density
    labelPaint.textSize = LABEL_DP * scale
    labelPaint.textAlign = Paint.Align.CENTER
    val radius = MARKER_RADIUS_DP * scale

    for (setup in setups.takeLast(MAX_DRAWN_SETUPS)) {
        val color = if (setup.direction == Direction.BULLISH) RsiReversalBuyPoint else RsiReversalSellPoint
        labelPaint.color = color.toArgb()

        val points = buildList {
            add(setup.p1)
            add(setup.p2)
            addAll(setup.recursiveExtremes)
            add(setup.finalExtreme)
        }

        var previous: Offset? = null
        for (point in points) {
            val x = viewport.xForIndex(point.index.toFloat(), cw)
            val y = viewport.yForPrice(point.price, ch)
            if (!x.isFinite() || !y.isFinite()) {
                previous = null
                continue
            }
            // Off-screen points still anchor the leg, so the line entering the
            // viewport from outside is drawn at the correct angle.
            val onScreen = x >= -radius && x <= cw + radius

            previous?.let { from ->
                drawLine(
                    color = color.copy(alpha = 0.55f),
                    start = from,
                    end = Offset(x, y),
                    strokeWidth = scale,
                    pathEffect = RsiReversalLegDash,
                )
            }
            previous = Offset(x, y)

            if (!onScreen) continue
            drawCircle(color = color, radius = radius, center = Offset(x, y))
            drawCircle(
                color = color,
                radius = radius + scale,
                center = Offset(x, y),
                style = Stroke(width = scale * 0.8f),
            )

            // Below a bullish low, above a bearish high, so the label never
            // covers the candle it is describing.
            val labelY = if (setup.direction == Direction.BULLISH) {
                y + radius + LABEL_DP * scale
            } else {
                y - radius - LABEL_DP * scale * 0.4f
            }
            drawContext.canvas.nativeCanvas.drawText("P${point.ordinal}", x, labelY, labelPaint)
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
