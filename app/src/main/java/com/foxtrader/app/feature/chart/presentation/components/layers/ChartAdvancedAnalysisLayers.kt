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
import com.foxtrader.app.feature.chart.presentation.chartOverlayLabelBaseline
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral60
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    if (zones.isEmpty() || !cw.isDrawableSpan() || !ch.isDrawableSpan()) return
    val originalAlign = labelPaint.textAlign
    for (zone in zones.take(6)) {
        if (!zone.upperBound.isDrawablePrice() || !zone.lowerBound.isDrawablePrice() || !zone.price.isDrawablePrice()) continue
        if (zone.upperBound < zone.lowerBound || zone.price !in zone.lowerBound..zone.upperBound) continue

        val yTopRaw = viewport.yForPrice(zone.upperBound, ch)
        val yBottomRaw = viewport.yForPrice(zone.lowerBound, ch)
        if (!yTopRaw.isFinite() || !yBottomRaw.isFinite()) continue
        val top = min(yTopRaw, yBottomRaw).coerceIn(0f, ch)
        val bottom = max(yTopRaw, yBottomRaw).coerceIn(0f, ch)
        val height = bottom - top
        if (height <= 0.25f) continue

        val baseColor = if (zone.isSupport) FoxBullish else FoxBearish
        val strength = if (zone.strength.isFinite()) zone.strength else 0.0
        val alpha = (0.05f + ((strength.coerceIn(0.0, 100.0) / 100.0) * 0.12f).toFloat()).coerceIn(0.05f, 0.18f)
        drawRect(
            color = baseColor.copy(alpha = alpha),
            topLeft = Offset(0f, top),
            size = Size(cw, height),
        )

        val centerY = viewport.yForPrice(zone.price, ch)
        if (!centerY.isFinite() || centerY !in 0f..ch) continue
        drawLine(
            color = baseColor.copy(alpha = 0.45f),
            start = Offset(0f, centerY),
            end = Offset(cw, centerY),
            strokeWidth = 1f,
            pathEffect = SupportResistanceDash,
        )

        labelPaint.color = if (zone.isSupport) SupportLabelArgb else ResistanceLabelArgb
        labelPaint.textAlign = Paint.Align.LEFT
        val labelBaseline = chartOverlayLabelBaseline(centerY - 4f, labelPaint.textSize, ch)
            ?: continue
        drawContext.canvas.nativeCanvas.drawText(
            "${if (zone.isSupport) "S" else "R"} ${zone.touches.coerceAtLeast(0)}x",
            8f,
            labelBaseline,
            labelPaint,
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
    if (profile.levels.isEmpty() || !cw.isDrawableSpan() || !ch.isDrawableSpan()) return

    val validLevels = profile.levels.filter {
        it.priceLevel.isDrawablePrice() && it.tpoCount >= 0
    }
    if (validLevels.isEmpty()) return

    val maxTpo = validLevels.maxOfOrNull { it.tpoCount }?.coerceAtLeast(1) ?: 1
    val barMaxWidth = cw * 0.18f
    if (!barMaxWidth.isDrawableSpan()) return

    val step = if (validLevels.size >= 2) {
        abs(validLevels[1].priceLevel - validLevels[0].priceLevel)
    } else if (profile.profileHigh.isDrawablePrice() && profile.profileLow.isDrawablePrice()) {
        abs(profile.profileHigh - profile.profileLow)
    } else {
        0.0
    }.takeIf { it.isFinite() && it > 1e-9 } ?: return

    val valueAreaLow = profile.valueAreaLow.takeIf { it.isDrawablePrice() }
    val valueAreaHigh = profile.valueAreaHigh.takeIf { it.isDrawablePrice() }
    val poc = profile.poc.takeIf { it.isDrawablePrice() }

    for (level in validLevels) {
        val yTopRaw = viewport.yForPrice(level.priceLevel + step / 2.0, ch)
        val yBottomRaw = viewport.yForPrice(level.priceLevel - step / 2.0, ch)
        if (!yTopRaw.isFinite() || !yBottomRaw.isFinite()) continue
        val top = min(yTopRaw, yBottomRaw).coerceIn(0f, ch)
        val bottom = max(yTopRaw, yBottomRaw).coerceIn(0f, ch)
        val height = bottom - top
        if (height <= 0.25f) continue

        val width = ((level.tpoCount.toFloat() / maxTpo.toFloat()) * barMaxWidth)
            .takeIf { it.isFinite() && it >= 0f } ?: continue
        val inValueArea = valueAreaLow?.let { low ->
            valueAreaHigh?.let { high -> low <= high && level.priceLevel in low..high }
        } ?: false
        val isPoc = poc != null && abs(level.priceLevel - poc) <= step / 2.0
        val color = when {
            isPoc -> FoxAmber50.copy(alpha = 0.36f)
            inValueArea -> FoxNeutral60.copy(alpha = 0.18f)
            else -> FoxNeutral20.copy(alpha = 0.12f)
        }
        drawRect(
            color = color,
            topLeft = Offset(0f, top),
            size = Size(width.coerceAtLeast(1f), height.coerceAtLeast(1f)),
        )
    }

    if (poc != null) {
        val pocY = viewport.yForPrice(poc, ch)
        if (pocY.isFinite() && pocY in 0f..ch) {
            drawLine(
                color = FoxAmber50.copy(alpha = 0.7f),
                start = Offset(0f, pocY),
                end = Offset(barMaxWidth, pocY),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round,
            )
        }
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
    if (
        levels.isEmpty() || direction == null || swingHigh == null || swingLow == null ||
        !swingHigh.isDrawablePrice() || !swingLow.isDrawablePrice() || swingHigh <= swingLow ||
        !cw.isDrawableSpan() || !ch.isDrawableSpan()
    ) return

    val trendColor = if (direction == Direction.BULLISH) FoxBullish else FoxBearish
    val originalAlign = labelPaint.textAlign
    for (level in levels) {
        if (!level.price.isDrawablePrice() || !level.ratio.isFinite()) continue
        val y = viewport.yForPrice(level.price, ch)
        if (!y.isFinite() || y !in 0f..ch) continue
        val keyLevel = level.ratio == 0.0 || level.ratio == 0.5 || level.ratio == 0.618 || level.ratio == 1.0
        drawLine(
            color = trendColor.copy(alpha = if (keyLevel) 0.52f else 0.32f),
            start = Offset(0f, y),
            end = Offset(cw, y),
            strokeWidth = if (keyLevel) 1.25f else 0.9f,
            pathEffect = AutoFibDash,
        )

        labelPaint.color = FibLabelArgb
        labelPaint.textAlign = Paint.Align.RIGHT
        val labelBaseline = chartOverlayLabelBaseline(y - 4f, labelPaint.textSize, ch)
            ?: continue
        drawContext.canvas.nativeCanvas.drawText(
            "${level.label} ${formatLayerPrice(level.price)}",
            (cw - 8f).coerceAtLeast(0f),
            labelBaseline,
            labelPaint,
        )
    }
    labelPaint.textAlign = originalAlign

    val highY = viewport.yForPrice(swingHigh, ch)
    val lowY = viewport.yForPrice(swingLow, ch)
    if (highY.isFinite() && lowY.isFinite() && (highY in 0f..ch || lowY in 0f..ch)) {
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
    if (abs(price) >= 1000.0) String.format(java.util.Locale.US, "%,.2f", price)
    else String.format(java.util.Locale.US, "%.5f", price)

private fun Double.isDrawablePrice(): Boolean = isFinite() && this > 0.0
private fun Float.isDrawableSpan(): Boolean = isFinite() && this > 0f
