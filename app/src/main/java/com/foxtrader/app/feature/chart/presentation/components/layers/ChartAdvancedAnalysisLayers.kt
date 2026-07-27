package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.analysis.FibonacciEngine
import com.foxtrader.app.domain.usecase.analysis.MarketProfile
import com.foxtrader.app.domain.usecase.analysis.SupportResistanceDetector
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral60
import kotlin.math.abs

// Advanced analysis layers added in Sprint 8.3.
//
// - Market Profile (TPO) histogram, left-aligned
// - Support / Resistance auto-zones
// - Auto Fibonacci retracement grid on the dominant recent swing

private val SupportResistanceDash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
private val AutoFibDash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
private val SupportLabelArgb = android.graphics.Color.parseColor("#66BB6A")
private val ResistanceLabelArgb = android.graphics.Color.parseColor("#EF5350")
private val FibLabelArgb = android.graphics.Color.parseColor("#D4A84E")

internal fun DrawScope.drawSupportResistanceZones(
    zones: List<SupportResistanceDetector.SRZone>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    if (zones.isEmpty()) return
    val originalAlign = labelPaint.textAlign
    for (zone in zones.take(6)) {
        val yTop = viewport.yForPrice(zone.upperBound, ch)
        val yBottom = viewport.yForPrice(zone.lowerBound, ch)
        val top = minOf(yTop, yBottom)
        val bottom = maxOf(yTop, yBottom)
        if (bottom < 0f || top > ch) continue

        val baseColor = if (zone.isSupport) FoxBullish else FoxBearish
        val alpha = (0.05f + ((zone.strength / 100.0) * 0.12f).toFloat()).coerceIn(0.05f, 0.18f)
        drawRect(
            color = baseColor.copy(alpha = alpha),
            topLeft = Offset(0f, top),
            size = Size(cw, (bottom - top).coerceAtLeast(1f)),
        )

        val centerY = viewport.yForPrice(zone.price, ch)
        drawLine(
            color = baseColor.copy(alpha = 0.45f),
            start = Offset(0f, centerY),
            end = Offset(cw, centerY),
            strokeWidth = 1f,
            pathEffect = SupportResistanceDash,
        )

        labelPaint.color = if (zone.isSupport) SupportLabelArgb else ResistanceLabelArgb
        drawContext.canvas.nativeCanvas.drawText(
            "${if (zone.isSupport) "S" else "R"} ${zone.touches}x",
            8f,
            centerY - 4f,
            labelPaint.apply { textAlign = Paint.Align.LEFT },
        )
    }
    labelPaint.textAlign = originalAlign
}

internal fun DrawScope.drawMarketProfile(
    profile: MarketProfile.ProfileResult,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (profile.levels.isEmpty()) return

    val maxTpo = profile.levels.maxOfOrNull { it.tpoCount }?.coerceAtLeast(1) ?: 1
    val barMaxWidth = cw * 0.18f
    val step = if (profile.levels.size >= 2) {
        abs(profile.levels[1].priceLevel - profile.levels[0].priceLevel).coerceAtLeast(1e-9)
    } else {
        (profile.profileHigh - profile.profileLow).coerceAtLeast(1e-9)
    }

    for (level in profile.levels) {
        val yTop = viewport.yForPrice(level.priceLevel + step / 2.0, ch)
        val yBottom = viewport.yForPrice(level.priceLevel - step / 2.0, ch)
        val top = minOf(yTop, yBottom)
        val bottom = maxOf(yTop, yBottom)
        if (bottom < 0f || top > ch) continue

        val width = (level.tpoCount.toFloat() / maxTpo.toFloat()) * barMaxWidth
        val inValueArea = level.priceLevel in profile.valueAreaLow..profile.valueAreaHigh
        val isPoc = abs(level.priceLevel - profile.poc) <= step / 2.0
        val color = when {
            isPoc -> FoxAmber50.copy(alpha = 0.36f)
            inValueArea -> FoxNeutral60.copy(alpha = 0.18f)
            else -> FoxNeutral20.copy(alpha = 0.12f)
        }
        drawRect(
            color = color,
            topLeft = Offset(0f, top),
            size = Size(width.coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
        )
    }

    val pocY = viewport.yForPrice(profile.poc, ch)
    if (pocY in 0f..ch) {
        drawLine(
            color = FoxAmber50.copy(alpha = 0.7f),
            start = Offset(0f, pocY),
            end = Offset(barMaxWidth, pocY),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round,
        )
    }
}

internal fun DrawScope.drawAutoFibonacciLevels(
    levels: List<FibonacciEngine.FibLevel>,
    direction: Direction?,
    swingHigh: Double?,
    swingLow: Double?,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    if (levels.isEmpty() || direction == null || swingHigh == null || swingLow == null) return

    val trendColor = if (direction == Direction.BULLISH) FoxBullish else FoxBearish
    val originalAlign = labelPaint.textAlign
    for (level in levels) {
        val y = viewport.yForPrice(level.price, ch)
        if (y !in 0f..ch) continue
        val keyLevel = level.ratio == 0.0 || level.ratio == 0.5 || level.ratio == 0.618 || level.ratio == 1.0
        drawLine(
            color = trendColor.copy(alpha = if (keyLevel) 0.52f else 0.32f),
            start = Offset(0f, y),
            end = Offset(cw, y),
            strokeWidth = if (keyLevel) 1.25f else 0.9f,
            pathEffect = AutoFibDash,
        )

        labelPaint.color = FibLabelArgb
        drawContext.canvas.nativeCanvas.drawText(
            "${level.label} ${formatLayerPrice(level.price)}",
            cw - 8f,
            y - 4f,
            labelPaint.apply { textAlign = Paint.Align.RIGHT },
        )
    }
    labelPaint.textAlign = originalAlign

    val highY = viewport.yForPrice(swingHigh, ch)
    val lowY = viewport.yForPrice(swingLow, ch)
    if (highY in 0f..ch || lowY in 0f..ch) {
        drawLine(
            color = trendColor.copy(alpha = 0.55f),
            start = Offset(cw * 0.06f, highY.coerceIn(0f, ch)),
            end = Offset(cw * 0.06f, lowY.coerceIn(0f, ch)),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
    }
}

private fun formatLayerPrice(price: Double): String =
    if (abs(price) >= 1000.0) String.format("%,.2f", price)
    else String.format("%.5f", price)
