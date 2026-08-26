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
import com.foxtrader.app.feature.chart.presentation.chartOverlayLabelBaseline
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import kotlin.math.max
import kotlin.math.min

private val StructureDash = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
private val LivePriceDash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
private val StructureBullishLabelArgb = android.graphics.Color.parseColor("#4CAF50")
private val StructureBearishLabelArgb = android.graphics.Color.parseColor("#EF5350")
private const val DiamondHalfHeight = 5f
private const val DiamondHalfWidth = 4f

internal fun DrawScope.drawStructureLayer(
    breaks: List<StructureBreak>,
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    if (!cw.isFinite() || !ch.isFinite() || cw <= 0f || ch <= 0f || candles.isEmpty()) return
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)

    for (brk in breaks) {
        if (brk.breakIndex < startIdx || brk.breakIndex >= endIdx || !brk.confirmed) continue
        if (!brk.breakPrice.isFinite() || brk.breakPrice <= 0.0) continue

        val x = viewport.xForIndex(brk.breakIndex + 0.5f, cw)
        val y = viewport.yForPrice(brk.breakPrice, ch)
        if (!x.isFinite() || !y.isFinite() || x !in 0f..cw || y !in 0f..ch) continue

        val color = when (brk.direction) {
            Direction.BULLISH -> FoxBullish
            Direction.BEARISH -> FoxBearish
        }
        drawLine(
            color = color.copy(alpha = 0.5f),
            start = Offset((x - 40f).coerceAtLeast(0f), y),
            end = Offset((x + 40f).coerceAtMost(cw), y),
            strokeWidth = 1f,
            pathEffect = StructureDash,
        )

        drawLine(color, Offset(x, y - DiamondHalfHeight), Offset(x + DiamondHalfWidth, y), strokeWidth = 1.5f)
        drawLine(color, Offset(x + DiamondHalfWidth, y), Offset(x, y + DiamondHalfHeight), strokeWidth = 1.5f)
        drawLine(color, Offset(x, y + DiamondHalfHeight), Offset(x - DiamondHalfWidth, y), strokeWidth = 1.5f)
        drawLine(color, Offset(x - DiamondHalfWidth, y), Offset(x, y - DiamondHalfHeight), strokeWidth = 1.5f)

        val defaultLabel = when (brk.type) {
            StructureBreakType.BOS -> "BOS"
            StructureBreakType.CHOCH -> "CHoCH"
            StructureBreakType.MSS -> "MSS"
            StructureBreakType.IDM -> "IDM"
        }
        val label = brk.labelOverride
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_LABEL_LENGTH)
            ?: defaultLabel
        labelPaint.color = when (brk.direction) {
            Direction.BULLISH -> StructureBullishLabelArgb
            Direction.BEARISH -> StructureBearishLabelArgb
        }
        val labelBaseline = chartOverlayLabelBaseline(y - 10f, labelPaint.textSize, ch)
            ?: continue
        drawContext.canvas.nativeCanvas.drawText(
            label,
            x,
            labelBaseline,
            labelPaint,
        )
    }
}

internal fun DrawScope.drawLivePriceLine(
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val last = candles.lastOrNull() ?: return
    if (!last.close.isFinite() || last.close <= 0.0 || !cw.isFinite() || !ch.isFinite() || cw <= 0f || ch <= 0f) return
    val lastY = viewport.yForPrice(last.close, ch)
    if (lastY.isFinite() && lastY in 0f..ch) {
        val color = if (last.isBullish) FoxBullish else FoxBearish
        drawLine(
            color = color.copy(alpha = 0.6f),
            start = Offset(0f, lastY),
            end = Offset(cw, lastY),
            strokeWidth = 1f,
            pathEffect = LivePriceDash,
        )
    }
}

private const val MAX_LABEL_LENGTH = 18
