package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import kotlin.math.max
import kotlin.math.min

private val ValueAreaDash = PathEffect.dashPathEffect(floatArrayOf(9f, 6f))
private val PocDash = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
private val ValueAreaFill = Color(0xFF4DD0E1)
private val ValueAreaEdge = Color(0xFF80DEEA)
private val PocColor = Color(0xFFFFC857)

/** Lightweight, viewport-clipped rendering of the latest causal VALR profile. */
internal fun DrawScope.drawValueAreaLiquidityProfile(
    profile: ValueAreaLiquidityRejectionEngine.ProfileSnapshot,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    if (!cw.isDrawableSpan() || !ch.isDrawableSpan()) return
    if (!profile.valueAreaLow.isDrawablePrice() || !profile.vah.isDrawablePrice() || !profile.poc.isDrawablePrice()) return
    val x = viewport.xForIndex(profile.appliesFromIndex.toFloat(), cw).coerceIn(0f, cw)
    val top = min(viewport.yForPrice(profile.vah, ch), viewport.yForPrice(profile.valueAreaLow, ch)).coerceIn(0f, ch)
    val bottom = max(viewport.yForPrice(profile.vah, ch), viewport.yForPrice(profile.valueAreaLow, ch)).coerceIn(0f, ch)
    if (!top.isFinite() || !bottom.isFinite() || bottom - top <= 0.5f || cw - x <= 0.5f) return

    drawRect(ValueAreaFill.copy(alpha = 0.055f), Offset(x, top), Size(cw - x, bottom - top))
    drawRect(ValueAreaFill.copy(alpha = 0.035f), Offset(x, top), Size(cw - x, min(3f, bottom - top)))
    drawRect(ValueAreaFill.copy(alpha = 0.035f), Offset(x, max(top, bottom - 3f)), Size(cw - x, min(3f, bottom - top)))

    drawLevel(profile.vah, "VAH", ValueAreaEdge, ValueAreaDash, viewport, x, cw, ch, labelPaint)
    drawLevel(profile.valueAreaLow, "VAL", ValueAreaEdge, ValueAreaDash, viewport, x, cw, ch, labelPaint)
    drawLevel(profile.poc, "POC", PocColor, PocDash, viewport, x, cw, ch, labelPaint)
}

private fun DrawScope.drawLevel(
    price: Double,
    label: String,
    color: Color,
    dash: PathEffect,
    viewport: ChartViewport,
    x: Float,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    val y = viewport.yForPrice(price, ch)
    if (!y.isFinite() || y !in 0f..ch) return
    drawLine(color.copy(alpha = 0.78f), Offset(x, y), Offset(cw, y), 1.35f, pathEffect = dash)
    labelPaint.color = color.toArgb()
    labelPaint.textAlign = Paint.Align.LEFT
    drawContext.canvas.nativeCanvas.drawText(label, (x + 6f).coerceAtMost(cw - 28f), (y - 4f).coerceIn(10f, ch), labelPaint)
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(), (red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt(),
)

private fun Double.isDrawablePrice(): Boolean = isFinite() && this > 0.0
private fun Float.isDrawableSpan(): Boolean = isFinite() && this > 0f
