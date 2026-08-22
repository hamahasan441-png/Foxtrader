package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.HoldZoneType
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import kotlin.math.max
import kotlin.math.min

/** TRADEPRO overlays with strict geometry validation before Canvas calls. */
fun DrawScope.drawTradeProOverlays(
    analysis: TradeProAnalysis,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (!cw.isFinite() || !ch.isFinite() || cw <= 0f || ch <= 0f) return
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (zone in analysis.holdZones) {
        if (
            zone.startIndex < 0 ||
            zone.endIndex < zone.startIndex ||
            !zone.high.isDrawablePrice() ||
            !zone.low.isDrawablePrice() ||
            zone.high <= zone.low
        ) continue
        if (zone.endIndex < startIdx) continue

        val rawX = viewport.xForIndex(zone.startIndex.toFloat(), cw)
        val rawHigh = viewport.yForPrice(zone.high, ch)
        val rawLow = viewport.yForPrice(zone.low, ch)
        if (!rawX.isFinite() || !rawHigh.isFinite() || !rawLow.isFinite()) continue

        val x1 = rawX.coerceIn(0f, cw)
        val top = min(rawHigh, rawLow).coerceIn(0f, ch)
        val bottom = max(rawHigh, rawLow).coerceIn(0f, ch)
        val width = (cw - x1).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        if (width <= 0.25f || height <= 0.25f) continue

        val base = if (zone.type == HoldZoneType.BUY_HOLD) FoxBullish else FoxBearish
        drawRect(
            color = base.copy(alpha = 0.12f),
            topLeft = Offset(x1, top),
            size = Size(width, height),
        )
        drawLine(base.copy(alpha = 0.55f), Offset(x1, top), Offset(cw, top), strokeWidth = 2f)
        drawLine(base.copy(alpha = 0.55f), Offset(x1, bottom), Offset(cw, bottom), strokeWidth = 2f)
    }

    analysis.flipZone?.takeIf { it.price.isDrawablePrice() }?.let { fz ->
        val y = viewport.yForPrice(fz.price, ch)
        if (y.isFinite() && y in 0f..ch) {
            drawLine(
                color = FoxAmber50.copy(alpha = 0.9f),
                start = Offset(0f, y),
                end = Offset(cw, y),
                strokeWidth = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f),
            )
        }
    }

    analysis.setup?.takeIf { it.stage == SetupStage.EXECUTE && it.hasDrawableGeometry() }?.let { s ->
        val entryColor = if (s.direction == Direction.BULLISH) FoxBullish else FoxBearish
        drawPriceLine(viewport, cw, ch, s.entry, entryColor.copy(alpha = 0.95f), 2.5f)
        drawPriceLine(viewport, cw, ch, s.stopLoss, FoxBearish.copy(alpha = 0.85f), 1.5f, dashed = true)
        drawPriceLine(viewport, cw, ch, s.target1, FoxBullish.copy(alpha = 0.7f), 1.5f, dashed = true)
        drawPriceLine(viewport, cw, ch, s.target2, FoxBullish.copy(alpha = 0.7f), 1.5f, dashed = true)
    }

    for (event in analysis.absorptions) {
        if (event.index < 0 || !event.price.isDrawablePrice()) continue
        if (event.index < startIdx || event.index > endIdx) continue
        val x = viewport.xForIndex(event.index + 0.5f, cw)
        val y = viewport.yForPrice(event.price, ch)
        if (!x.isFinite() || !y.isFinite() || x !in 0f..cw || y !in 0f..ch) continue
        val reversalColor = if (event.absorbedSide == Direction.BULLISH) FoxBearish else FoxBullish
        drawCircle(color = reversalColor.copy(alpha = 0.9f), radius = 5f, center = Offset(x, y))
    }
}

private fun DrawScope.drawPriceLine(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    price: Double,
    color: Color,
    width: Float,
    dashed: Boolean = false,
) {
    if (!price.isDrawablePrice()) return
    val y = viewport.yForPrice(price, ch)
    if (!y.isFinite() || y !in 0f..ch) return
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(cw, y),
        strokeWidth = width.coerceAtLeast(0.5f),
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) else null,
    )
}

private fun com.foxtrader.app.domain.model.tradepro.TradeProSetup.hasDrawableGeometry(): Boolean {
    if (
        !entry.isDrawablePrice() ||
        !stopLoss.isDrawablePrice() ||
        !target1.isDrawablePrice() ||
        !target2.isDrawablePrice()
    ) return false
    return when (direction) {
        Direction.BULLISH -> stopLoss < entry && target1 > entry && target2 > entry
        Direction.BEARISH -> stopLoss > entry && target1 < entry && target2 < entry
    }
}

private fun Double.isDrawablePrice(): Boolean = isFinite() && this > 0.0
