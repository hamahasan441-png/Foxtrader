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

/**
 * TRADEPRO chart overlays — the visual language of the order-flow/auction framework:
 *  - Buy/Sell-Hold zones (filled boxes extended to the live edge — where price pulls back to enter)
 *  - the Flip Zone (a dashed amber bias line across the whole chart)
 *  - the active setup's entry / stop / T1 / T2 (only when a setup reaches EXECUTE)
 *  - absorption markers (dots at bars where aggression was absorbed — reversal warnings)
 *
 * Same conventions as [drawOrderBlocks] in SmcRenderer: pure DrawScope, viewport-culled, zero
 * Compose-state reads. Indices are candle-list positions, so the analysis MUST be computed on the
 * same candle series the chart renders.
 */
fun DrawScope.drawTradeProOverlays(
    analysis: TradeProAnalysis,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    // 1) Buy/Sell-Hold zones — filled rectangles extended to the live (right) edge.
    for (zone in analysis.holdZones) {
        if (
            zone.startIndex < 0 ||
            !zone.high.isDrawablePrice() ||
            !zone.low.isDrawablePrice() ||
            zone.high < zone.low
        ) continue
        if (zone.endIndex < startIdx) continue
        val x1 = viewport.xForIndex(zone.startIndex.toFloat(), cw).coerceIn(0f, cw)
        val yHigh = viewport.yForPrice(zone.high, ch)
        val yLow = viewport.yForPrice(zone.low, ch)
        val base = if (zone.type == HoldZoneType.BUY_HOLD) FoxBullish else FoxBearish
        drawRect(
            color = base.copy(alpha = 0.12f),
            topLeft = Offset(x1, yHigh),
            size = Size((cw - x1).coerceAtLeast(0f), yLow - yHigh),
        )
        drawLine(base.copy(alpha = 0.55f), Offset(x1, yHigh), Offset(cw, yHigh), strokeWidth = 2f)
        drawLine(base.copy(alpha = 0.55f), Offset(x1, yLow), Offset(cw, yLow), strokeWidth = 2f)
    }

    // 2) Flip Zone — the day's single bias line.
    analysis.flipZone?.takeIf { it.price.isDrawablePrice() }?.let { fz ->
        val y = viewport.yForPrice(fz.price, ch)
        drawLine(
            color = FoxAmber50.copy(alpha = 0.9f),
            start = Offset(0f, y),
            end = Offset(cw, y),
            strokeWidth = 2.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f),
        )
    }

    // 3) Active setup: entry / stop / T1 / T2 — only when confirmed (EXECUTE).
    analysis.setup?.takeIf { it.stage == SetupStage.EXECUTE && it.hasDrawableGeometry() }?.let { s ->
        val entryColor = if (s.direction == Direction.BULLISH) FoxBullish else FoxBearish
        drawPriceLine(viewport, cw, ch, s.entry, entryColor.copy(alpha = 0.95f), 2.5f)
        drawPriceLine(viewport, cw, ch, s.stopLoss, FoxBearish.copy(alpha = 0.85f), 1.5f, dashed = true)
        drawPriceLine(viewport, cw, ch, s.target1, FoxBullish.copy(alpha = 0.7f), 1.5f, dashed = true)
        drawPriceLine(viewport, cw, ch, s.target2, FoxBullish.copy(alpha = 0.7f), 1.5f, dashed = true)
    }

    // 4) Absorption markers — dot coloured by the anticipated reversal direction.
    for (event in analysis.absorptions) {
        if (event.index < 0 || !event.price.isDrawablePrice()) continue
        if (event.index < startIdx || event.index > endIdx) continue
        val x = viewport.xForIndex(event.index + 0.5f, cw)
        val y = viewport.yForPrice(event.price, ch)
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
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(cw, y),
        strokeWidth = width,
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
