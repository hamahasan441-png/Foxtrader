package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral5

// Layers 6-7 — price scale (Y) and time axis (X).
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure DrawScope extensions - no Compose state - which is
// what keeps them cheap enough for the 120fps budget.

/**
 * Price scale — Y-axis labels on the right edge, plus the always-visible
 * live last-price tag.
 */
internal fun DrawScope.drawPriceScale(
    viewport: ChartViewport,
    candles: List<Candle>,
    cw: Float,
    ch: Float,
    totalW: Float,
    totalH: Float,
    paint: Paint,
    tagPaint: Paint,
) {
    // Background for price scale area
    drawRect(
        color = FoxNeutral5,
        topLeft = Offset(cw, 0f),
        size = Size(viewport.priceScaleWidth, totalH),
    )

    // Separator line
    drawLine(
        color = FoxNeutral20,
        start = Offset(cw, 0f),
        end = Offset(cw, ch),
        strokeWidth = 0.5f,
    )

    val step = viewport.niceStep(6)
    if (step > 0.0) {
        var level = ceil(viewport.priceLow / step) * step
        while (level <= viewport.priceHigh) {
            val y = viewport.yForPrice(level, ch)
            if (y in 0f..ch) {
                val label = viewport.formatPrice(level)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    totalW - 6f,
                    y + paint.textSize / 3f,
                    paint,
                )
            }
            level += step
        }
    }

    // --- Live last-price tag ---
    // A filled, always-visible marker at the current close. This is what makes
    // the scale readable at a glance: the trader never has to hunt for "where
    // is price right now" among the round grid levels.
    val last = candles.lastOrNull() ?: return
    val lastY = viewport.yForPrice(last.close, ch)
    if (lastY !in 0f..ch) return

    val tagColor = if (last.isBullish) FoxBullish else FoxBearish
    val tagH = tagPaint.textSize + 7f
    drawRect(
        color = tagColor,
        topLeft = Offset(cw + 1f, (lastY - tagH / 2f).coerceIn(0f, ch - tagH)),
        size = Size(viewport.priceScaleWidth - 2f, tagH),
    )
    tagPaint.textAlign = Paint.Align.CENTER
    drawContext.canvas.nativeCanvas.drawText(
        viewport.formatPrice(last.close),
        cw + viewport.priceScaleWidth / 2f,
        (lastY - tagH / 2f).coerceIn(0f, ch - tagH) + tagH * 0.72f,
        tagPaint,
    )
}

/** Time axis — X-axis labels at the bottom edge. */
internal fun DrawScope.drawTimeAxis(
    viewport: ChartViewport,
    candles: List<Candle>,
    cw: Float,
    ch: Float,
    totalW: Float,
    totalH: Float,
    paint: Paint,
    timeframe: Timeframe,
) {
    // Background for time axis area
    drawRect(
        color = FoxNeutral5,
        topLeft = Offset(0f, ch),
        size = Size(totalW, viewport.timeAxisHeight),
    )

    // Separator line
    drawLine(
        color = FoxNeutral20,
        start = Offset(0f, ch),
        end = Offset(cw, ch),
        strokeWidth = 0.5f,
    )

    val timeStep = viewport.niceTimeStep(6)
    if (timeStep > 0 && candles.isNotEmpty()) {
        val startIdx = max(0, viewport.startIndex.toInt())
        val endIdx = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
        var i = startIdx - (startIdx % timeStep) + timeStep
        while (i < endIdx && i < candles.size) {
            val x = viewport.xForIndex(i.toFloat(), cw)
            if (x in 0f..cw) {
                val label = viewport.formatTime(candles[i].timestamp, timeframe)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    x,
                    ch + viewport.timeAxisHeight * 0.7f,
                    paint,
                )
            }
            i += timeStep
        }
    }
}
