package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.feature.chart.presentation.chartOverlayLabelBaseline
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import kotlin.math.max
import kotlin.math.min

private val ValueAreaDash = PathEffect.dashPathEffect(floatArrayOf(9f, 6f))
private val PocDash = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
private val ValueAreaFill = Color(0xFF4DD0E1)
private val ValueAreaEdge = Color(0xFF80DEEA)
private val PocColor = Color(0xFFFFC857)

/** Label text height in dp — converted with the DrawScope density at draw time. */
private const val VALUE_AREA_LABEL_DP = 8.5f
private const val VALUE_AREA_LINE_DP = 1.1f
private const val SESSION_DIVIDER_DP = 0.8f

/**
 * Render every completed session's value-area profile, each clipped to the
 * session it actually governed.
 *
 * The previous implementation drew only the newest profile and stretched it
 * from its start index all the way to the right edge, so a trader could see the
 * current day's value area but had no way to audit any earlier session. Levels
 * are derived from the *previous* completed session, so drawing them over the
 * day they applied to is both more useful and more honest about what the engine
 * measured.
 */
internal fun DrawScope.drawValueAreaLiquidityProfiles(
    profiles: List<ValueAreaLiquidityRejectionEngine.ProfileSnapshot>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    if (profiles.isEmpty()) return
    if (!cw.isDrawableSpan() || !ch.isDrawableSpan()) return

    val scale = density
    labelPaint.textSize = VALUE_AREA_LABEL_DP * scale
    val lineWidth = VALUE_AREA_LINE_DP * scale

    // Viewport culling: skip whole sessions that cannot contribute a pixel.
    val firstVisible = viewport.startIndex - 1f
    val lastVisible = viewport.startIndex + viewport.visibleBars + 1f
    val newestIndex = profiles.maxOf { it.appliesToIndex }

    for (profile in profiles) {
        if (profile.appliesToIndex < firstVisible || profile.appliesFromIndex > lastVisible) continue
        // The still-open session runs to the right edge; completed sessions end
        // with their own last bar so each day's area is visually separate.
        val extendToEdge = profile.appliesToIndex >= newestIndex
        drawSessionProfile(profile, viewport, cw, ch, labelPaint, lineWidth, extendToEdge)
    }
}

private fun DrawScope.drawSessionProfile(
    profile: ValueAreaLiquidityRejectionEngine.ProfileSnapshot,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
    lineWidth: Float,
    extendToEdge: Boolean,
) {
    if (!profile.valueAreaLow.isDrawablePrice() ||
        !profile.vah.isDrawablePrice() ||
        !profile.poc.isDrawablePrice()
    ) return

    val left = viewport.xForIndex(profile.appliesFromIndex.toFloat(), cw).coerceIn(0f, cw)
    val right = if (extendToEdge) {
        cw
    } else {
        viewport.xForIndex(profile.appliesToIndex + 1f, cw).coerceIn(0f, cw)
    }
    val width = right - left
    if (!width.isFinite() || width <= 0.5f) return

    val top = min(viewport.yForPrice(profile.vah, ch), viewport.yForPrice(profile.valueAreaLow, ch))
        .coerceIn(0f, ch)
    val bottom = max(viewport.yForPrice(profile.vah, ch), viewport.yForPrice(profile.valueAreaLow, ch))
        .coerceIn(0f, ch)
    if (!top.isFinite() || !bottom.isFinite() || bottom - top <= 0.5f) return

    drawRect(ValueAreaFill.copy(alpha = 0.055f), Offset(left, top), Size(width, bottom - top))
    val edgeBand = min(2f * density, bottom - top)
    drawRect(ValueAreaFill.copy(alpha = 0.035f), Offset(left, top), Size(width, edgeBand))
    drawRect(
        ValueAreaFill.copy(alpha = 0.035f),
        Offset(left, max(top, bottom - edgeBand)),
        Size(width, edgeBand),
    )

    // Session boundary so consecutive days read as distinct profiles.
    if (!extendToEdge && right < cw) {
        drawLine(
            ValueAreaEdge.copy(alpha = 0.22f),
            Offset(right, top),
            Offset(right, bottom),
            SESSION_DIVIDER_DP * density,
        )
    }

    drawLevel(profile.vah, "VAH", ValueAreaEdge, ValueAreaDash, viewport, left, right, ch, labelPaint, lineWidth)
    drawLevel(profile.valueAreaLow, "VAL", ValueAreaEdge, ValueAreaDash, viewport, left, right, ch, labelPaint, lineWidth)
    drawLevel(profile.poc, "POC", PocColor, PocDash, viewport, left, right, ch, labelPaint, lineWidth)
}

private fun DrawScope.drawLevel(
    price: Double,
    label: String,
    color: Color,
    dash: PathEffect,
    viewport: ChartViewport,
    left: Float,
    right: Float,
    ch: Float,
    labelPaint: Paint,
    lineWidth: Float,
) {
    val y = viewport.yForPrice(price, ch)
    if (!y.isFinite() || y !in 0f..ch) return
    drawLine(color.copy(alpha = 0.78f), Offset(left, y), Offset(right, y), lineWidth, pathEffect = dash)

    // Only label a session wide enough to hold the text, so zoomed-out charts
    // do not turn into a wall of overlapping VAH/VAL/POC tags.
    val textWidth = labelPaint.measureText(label)
    if (right - left < textWidth * 2.2f) return
    labelPaint.color = color.toArgb()
    labelPaint.textAlign = Paint.Align.LEFT
    val labelX = (left + 4f * density).coerceAtMost(right - textWidth - 2f * density)
    val labelY = chartOverlayLabelBaseline(
        requested = y - 3f * density,
        textSize = labelPaint.textSize,
        chartHeight = ch,
    ) ?: return
    drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, labelPaint)
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(), (red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt(),
)

private fun Double.isDrawablePrice(): Boolean = isFinite() && this > 0.0
private fun Float.isDrawableSpan(): Boolean = isFinite() && this > 0f
