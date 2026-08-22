package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitEventType
import com.foxtrader.app.domain.model.LitLevel
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish

private val LitDash = PathEffect.dashPathEffect(floatArrayOf(7f, 6f), 0f)
private val LitIdmDash = PathEffect.dashPathEffect(floatArrayOf(3f, 6f), 0f)

private val LitLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 14f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.LEFT
    alpha = (0.84f * 255).toInt()
}

private val LitStagePaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 15f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.LEFT
    alpha = (0.90f * 255).toInt()
}

/**
 * Clean LiT Pro context layer.
 *
 * Execution arrows are intentionally NOT drawn here: validated LiT entries join
 * the unified [ChartSignal] renderer so the chart has exactly one arrow per
 * signal. This layer only explains the institutional path that produced it.
 */
internal fun DrawScope.drawLitProContext(
    analysis: LitAnalysis,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (cw <= 0f || ch <= 0f || !cw.isFinite() || !ch.isFinite()) return
    val context = analysis.context

    context.poi?.let { poi ->
        if (poi.low.isLitDrawablePrice() && poi.high.isLitDrawablePrice() && poi.high > poi.low) {
            val topY = viewport.yForPrice(poi.high, ch).coerceIn(0f, ch)
            val bottomY = viewport.yForPrice(poi.low, ch).coerceIn(0f, ch)
            val y = minOf(topY, bottomY)
            val height = kotlin.math.abs(bottomY - topY)
            val x = viewport.xForIndex(poi.originIndex + 0.5f, cw).coerceIn(0f, cw)
            if (height > 0f && x < cw) {
                val color = directionColor(poi.direction)
                drawRect(
                    color = color.copy(alpha = if (poi.mitigated) 0.055f else 0.105f),
                    topLeft = Offset(x, y),
                    size = Size((cw - x).coerceAtLeast(0f), height),
                )
                drawRect(
                    color = color.copy(alpha = 0.42f),
                    topLeft = Offset(x, y),
                    size = Size((cw - x).coerceAtLeast(0f), height),
                    style = Stroke(width = 1.15f),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "POI · ${poi.kind.name}",
                    (x + 7f).coerceAtMost(cw - 72f),
                    (y + 16f).coerceIn(14f, ch - 2f),
                    LitLabelPaint,
                )
            }
        }
    }

    context.scob?.let { scob ->
        if (scob.low.isLitDrawablePrice() && scob.high.isLitDrawablePrice() && scob.high > scob.low) {
            val topY = viewport.yForPrice(scob.high, ch).coerceIn(0f, ch)
            val bottomY = viewport.yForPrice(scob.low, ch).coerceIn(0f, ch)
            val y = minOf(topY, bottomY)
            val height = kotlin.math.abs(bottomY - topY)
            val x = viewport.xForIndex(scob.originIndex + 0.5f, cw).coerceIn(0f, cw)
            if (height > 0f && x < cw) {
                val color = directionColor(scob.direction)
                drawRect(
                    color = color.copy(alpha = 0.16f),
                    topLeft = Offset(x, y),
                    size = Size((cw - x).coerceAtLeast(0f), height),
                )
                drawLine(
                    color = FoxAmber50.copy(alpha = 0.82f),
                    start = Offset(x, y),
                    end = Offset(cw, y),
                    strokeWidth = 1.6f,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "SCOB ${scob.quality}",
                    (x + 7f).coerceAtMost(cw - 64f),
                    (y - 5f).coerceIn(14f, ch - 2f),
                    LitLabelPaint,
                )
            }
        }
    }

    drawLitLevel(context.pullback, viewport, cw, ch)
    drawLitLevel(context.inducement, viewport, cw, ch)
    drawLitLevel(context.bos, viewport, cw, ch)
    drawLitLevel(context.choch, viewport, cw, ch)

    val stageLabel = "LiT Pro · ${analysis.stage.name.replace('_', ' ')}"
    drawContext.canvas.nativeCanvas.drawText(stageLabel, 10f, 19f, LitStagePaint)
}

private fun DrawScope.drawLitLevel(
    level: LitLevel?,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    level ?: return
    if (!level.price.isLitDrawablePrice() || level.originIndex < 0 || level.confirmationIndex < level.originIndex) return

    val y = viewport.yForPrice(level.price, ch)
    if (!y.isFinite() || y < -4f || y > ch + 4f) return
    val startX = viewport.xForIndex(level.originIndex + 0.5f, cw).coerceIn(0f, cw)
    val confirmX = viewport.xForIndex(level.confirmationIndex + 0.5f, cw).coerceIn(0f, cw)
    val color = when (level.type) {
        LitEventType.PULLBACK -> FoxAmber50
        LitEventType.IDM -> FoxAmber50
        LitEventType.BOS,
        LitEventType.CHOCH -> level.direction?.let(::directionColor) ?: FoxAmber50
        LitEventType.POI,
        LitEventType.SCOB -> FoxAmber50
    }
    val path = if (level.type == LitEventType.IDM) LitIdmDash else LitDash
    val lineStart = minOf(startX, confirmX)
    drawLine(
        color = color.copy(alpha = if (level.type == LitEventType.CHOCH) 0.88f else 0.68f),
        start = Offset(lineStart, y),
        end = Offset(cw, y),
        strokeWidth = if (level.type == LitEventType.CHOCH) 1.8f else 1.25f,
        pathEffect = path,
    )
    drawCircle(
        color = color.copy(alpha = 0.88f),
        radius = if (level.type == LitEventType.IDM) 3.2f else 2.6f,
        center = Offset(confirmX, y),
    )
    val label = when (level.type) {
        LitEventType.PULLBACK -> "PB"
        LitEventType.IDM -> "IDM"
        LitEventType.BOS -> "BOS"
        LitEventType.CHOCH -> "CHOCH"
        LitEventType.POI -> "POI"
        LitEventType.SCOB -> "SCOB"
    }
    drawContext.canvas.nativeCanvas.drawText(
        label,
        (confirmX + 5f).coerceAtMost(cw - 50f),
        (y - 5f).coerceIn(14f, ch - 2f),
        LitLabelPaint,
    )
}

private fun directionColor(direction: Direction): Color = when (direction) {
    Direction.BULLISH -> FoxBullish
    Direction.BEARISH -> FoxBearish
}

private fun Double.isLitDrawablePrice(): Boolean = isFinite() && this > 0.0
