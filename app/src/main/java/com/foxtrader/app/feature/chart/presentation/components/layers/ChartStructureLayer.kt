package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish

// Layers 3-4 — market-structure annotations and the live price line.
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure DrawScope extensions - no Compose state - which is
// what keeps them cheap enough for the 120fps budget.

/** BOS/CHOCH market structure break annotations. */
internal fun DrawScope.drawStructureLayer(
    breaks: List<StructureBreak>,
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)

    for (brk in breaks) {
        if (brk.breakIndex < startIdx || brk.breakIndex >= endIdx) continue
        if (!brk.confirmed) continue

        val x = viewport.xForIndex(brk.breakIndex + 0.5f, cw)
        val y = viewport.yForPrice(brk.breakPrice, ch)

        val color = when (brk.direction) {
            Direction.BULLISH -> FoxBullish
            Direction.BEARISH -> FoxBearish
        }

        // Horizontal dashed line at break price
        drawLine(
            color = color.copy(alpha = 0.5f),
            start = Offset(x - 40f, y),
            end = Offset(x + 40f, y),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)),
        )

        // Small diamond marker
        val diamond = androidx.compose.ui.graphics.Path().apply {
            moveTo(x, y - 5f)
            lineTo(x + 4f, y)
            lineTo(x, y + 5f)
            lineTo(x - 4f, y)
            close()
        }
        drawPath(diamond, color = color)

        // Label
        val label = when (brk.type) {
            StructureBreakType.BOS -> "BOS"
            StructureBreakType.CHOCH -> "CHoCH"
            StructureBreakType.MSS -> "MSS"
            StructureBreakType.IDM -> "IDM"
        }
        labelPaint.color = when (brk.direction) {
            Direction.BULLISH -> android.graphics.Color.parseColor("#4CAF50")
            Direction.BEARISH -> android.graphics.Color.parseColor("#EF5350")
        }
        drawContext.canvas.nativeCanvas.drawText(
            label,
            x,
            y - 10f,
            labelPaint,
        )
    }
}

/** Live last-price dashed reference line. */
internal fun DrawScope.drawLivePriceLine(
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val last = candles.last()
    val lastY = viewport.yForPrice(last.close, ch)
    if (lastY in 0f..ch) {
        val color = if (last.isBullish) FoxBullish else FoxBearish
        drawLine(
            color = color.copy(alpha = 0.6f),
            start = Offset(0f, lastY),
            end = Offset(cw, lastY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
        )
    }
}
