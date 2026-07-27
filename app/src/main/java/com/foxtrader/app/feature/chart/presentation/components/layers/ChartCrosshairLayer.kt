package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale
import kotlin.math.abs

// Layer 5 — bar-snapped crosshair and its OHLC readout panel.
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure DrawScope extensions - no Compose state - which is
// what keeps them cheap enough for the 120fps budget.

// Pre-resolved ARGB ints for native Paint colouring (parsing a colour string
// inside the draw pass would allocate and cost a frame).
private val BullishTextArgb = android.graphics.Color.parseColor("#4CAF50")
private val BearishTextArgb = android.graphics.Color.parseColor("#EF5350")

/**
 * Professional crosshair with price/time readouts and a snapped OHLC panel.
 *
 * The vertical line snaps to the centre of the bar under the finger (§4.7) so
 * the readout always describes exactly one candle — a floating line between two
 * bars would make the OHLC values ambiguous.
 */
internal fun DrawScope.drawCrosshairLayer(
    viewport: ChartViewport,
    candles: List<Candle>,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
    ohlcPaint: Paint,
    timeframe: Timeframe,
) {
    val barIdx = viewport.snappedCrosshairIndex(candles.size, cw)
    if (barIdx < 0) return

    // Snap the vertical line to the bar centre; the horizontal line tracks the
    // finger freely so the user can read any price level.
    val snappedX = viewport.xForIndex(barIdx + 0.5f, cw).coerceIn(0f, cw)
    val cy = viewport.crosshairY.coerceIn(0f, ch)

    val crossColor = FoxNeutral60.copy(alpha = 0.7f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))

    drawLine(
        color = crossColor,
        start = Offset(snappedX, 0f),
        end = Offset(snappedX, ch),
        strokeWidth = 0.8f,
        pathEffect = dash,
    )
    drawLine(
        color = crossColor,
        start = Offset(0f, cy),
        end = Offset(cw, cy),
        strokeWidth = 0.8f,
        pathEffect = dash,
    )

    // --- Price label on the right scale ---
    val price = viewport.priceForY(cy, ch)
    val priceText = viewport.formatPrice(price)
    val labelH = labelPaint.textSize + 8f
    drawRect(
        color = FoxAmber50,
        topLeft = Offset(cw + 2f, cy - labelH / 2f),
        size = Size(viewport.priceScaleWidth - 4f, labelH),
    )
    labelPaint.textAlign = Paint.Align.CENTER
    drawContext.canvas.nativeCanvas.drawText(
        priceText,
        cw + viewport.priceScaleWidth / 2f,
        cy + labelPaint.textSize / 3f,
        labelPaint,
    )

    // --- Time label on the bottom axis ---
    val bar = candles[barIdx]
    val timeText = viewport.formatTime(bar.timestamp, timeframe)
    val timeLabelW = labelPaint.measureText(timeText) + 12f
    val timeLabelH = labelPaint.textSize + 6f
    // Keep the label fully on screen at the chart edges.
    val timeLabelX = snappedX.coerceIn(timeLabelW / 2f, (cw - timeLabelW / 2f).coerceAtLeast(timeLabelW / 2f))
    drawRect(
        color = FoxAmber50,
        topLeft = Offset(timeLabelX - timeLabelW / 2f, ch + 2f),
        size = Size(timeLabelW, timeLabelH),
    )
    drawContext.canvas.nativeCanvas.drawText(
        timeText,
        timeLabelX,
        ch + 2f + timeLabelH * 0.7f,
        labelPaint,
    )

    // --- OHLC readout panel ---
    drawOhlcReadout(viewport, bar, snappedX, cw, ohlcPaint)
}

/**
 * Compact O/H/L/C + change panel for the bar under the crosshair.
 *
 * Anchored to the top of the chart and flipped to the opposite side when the
 * crosshair would otherwise cover it.
 */
internal fun DrawScope.drawOhlcReadout(
    viewport: ChartViewport,
    bar: Candle,
    crosshairX: Float,
    cw: Float,
    paint: Paint,
) {
    val changeAbs = bar.close - bar.open
    val changePct = if (bar.open != 0.0) (changeAbs / bar.open) * 100.0 else 0.0
    val text = "O ${viewport.formatPrice(bar.open)}  " +
        "H ${viewport.formatPrice(bar.high)}  " +
        "L ${viewport.formatPrice(bar.low)}  " +
        "C ${viewport.formatPrice(bar.close)}  " +
        String.format(Locale.US, "%+.2f%%", changePct)

    val padding = 6f
    val textW = paint.measureText(text)
    val boxW = textW + padding * 2f
    val boxH = paint.textSize + padding * 2f

    // Flip to the left of the crosshair when there is no room on the right.
    val boxX = if (crosshairX + 8f + boxW <= cw) crosshairX + 8f
    else (crosshairX - 8f - boxW).coerceAtLeast(0f)

    drawRect(
        color = FoxNeutral10.copy(alpha = 0.92f),
        topLeft = Offset(boxX, 4f),
        size = Size(boxW.coerceAtMost(cw), boxH),
    )
    paint.color = if (changeAbs >= 0) BullishTextArgb else BearishTextArgb
    paint.textAlign = Paint.Align.LEFT
    drawContext.canvas.nativeCanvas.drawText(
        text,
        boxX + padding,
        4f + padding + paint.textSize * 0.8f,
        paint,
    )
}
