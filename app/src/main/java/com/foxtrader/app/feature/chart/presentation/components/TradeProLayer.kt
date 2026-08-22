package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.HoldZoneType
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import kotlin.math.max
import kotlin.math.min

/**
 * TRADEPRO context layer with strict geometry validation.
 *
 * Executable setups are rendered by the unified signal-arrow layer. Keeping
 * entry/SL/T1/T2 full-width lines here as well duplicated the same setup and
 * quickly turned the chart into a wall of levels. This layer now paints only
 * the contextual hold/flip/absorption information needed to understand the
 * arrow.
 */
fun DrawScope.drawTradeProOverlays(
    analysis: TradeProAnalysis,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (!cw.isFinite() || !ch.isFinite() || cw <= 0f || ch <= 0f) return
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (zone in analysis.holdZones.takeLast(MAX_HOLD_ZONES)) {
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
            color = base.copy(alpha = 0.07f),
            topLeft = Offset(x1, top),
            size = Size(width, height),
        )
        drawLine(base.copy(alpha = 0.30f), Offset(x1, top), Offset(cw, top), strokeWidth = 1f)
        drawLine(base.copy(alpha = 0.30f), Offset(x1, bottom), Offset(cw, bottom), strokeWidth = 1f)
    }

    analysis.flipZone?.takeIf { it.price.isDrawablePrice() }?.let { fz ->
        val y = viewport.yForPrice(fz.price, ch)
        if (y.isFinite() && y in 0f..ch) {
            drawLine(
                color = FoxAmber50.copy(alpha = 0.52f),
                start = Offset(0f, y),
                end = Offset(cw, y),
                strokeWidth = 1.3f,
                pathEffect = FlipDash,
            )
        }
    }

    for (event in analysis.absorptions.takeLast(MAX_ABSORPTIONS)) {
        if (event.index < 0 || !event.price.isDrawablePrice()) continue
        if (event.index < startIdx || event.index > endIdx) continue
        val x = viewport.xForIndex(event.index + 0.5f, cw)
        val y = viewport.yForPrice(event.price, ch)
        if (!x.isFinite() || !y.isFinite() || x !in 0f..cw || y !in 0f..ch) continue
        val reversalColor = if (event.absorbedSide == Direction.BULLISH) FoxBearish else FoxBullish
        drawCircle(color = reversalColor.copy(alpha = 0.68f), radius = 3.5f, center = Offset(x, y))
    }
}

private fun Double.isDrawablePrice(): Boolean = isFinite() && this > 0.0

private val FlipDash = PathEffect.dashPathEffect(floatArrayOf(12f, 9f), 0f)
private const val MAX_HOLD_ZONES = 3
private const val MAX_ABSORPTIONS = 10
