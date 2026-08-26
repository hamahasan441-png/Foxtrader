package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.model.ExternalKeyLevel
import com.foxtrader.app.domain.usecase.nascent.model.KeyLevelType
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport

private val NascentBuyLevel = Color(0xFF4FC3A1)
private val NascentSellLevel = Color(0xFFE8836B)
private val NascentLevelDash = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))

private const val NASCENT_LABEL_DP = 8.5f
private const val NASCENT_LINE_DP = 1.1f
private const val MAX_DRAWN_LEVELS = 8

/**
 * External Nascent context: the key levels a setup is permitted to form at.
 *
 * This is deliberately the *only* Nascent context drawn on the price chart, and
 * it is off by default. The entry itself is an arrow from the shared signal
 * layer; painting every liquidity point, internal pivot, transaction box and EPA
 * range as well would bury the one thing a trader has to act on. Everything
 * else stays available through the diagnostics rather than on the canvas.
 *
 * Levels are drawn from the right edge back only as far as they have been in
 * force, so a line never implies it existed before the external bar that
 * confirmed it had closed.
 */
internal fun DrawScope.drawNascentKeyLevels(
    levels: List<ExternalKeyLevel>,
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    if (levels.isEmpty() || candles.isEmpty()) return
    if (!cw.isFinite() || cw <= 0f || !ch.isFinite() || ch <= 0f) return

    val scale = density
    labelPaint.textSize = NASCENT_LABEL_DP * scale
    labelPaint.textAlign = Paint.Align.LEFT
    val lineWidth = NASCENT_LINE_DP * scale

    // The most recent levels are the ones still in play; older shelves would
    // only add clutter.
    val drawn = levels
        .sortedByDescending { it.externalCloseTimestamp }
        .distinctBy { it.type to it.price }
        .take(MAX_DRAWN_LEVELS)

    for (level in drawn) {
        if (!level.price.isFinite() || level.price <= 0.0) continue
        val y = viewport.yForPrice(level.price, ch)
        if (!y.isFinite() || y !in 0f..ch) continue

        val startIndex = candles.indexOfFirst { it.timestamp >= level.externalCloseTimestamp }
        val left = if (startIndex < 0) {
            cw
        } else {
            viewport.xForIndex(startIndex.toFloat(), cw).coerceIn(0f, cw)
        }
        if (left >= cw) continue

        val color = if (level.direction == Direction.BULLISH) NascentBuyLevel else NascentSellLevel
        drawLine(
            color = color.copy(alpha = 0.62f),
            start = Offset(left, y),
            end = Offset(cw, y),
            strokeWidth = lineWidth,
            pathEffect = NascentLevelDash,
        )

        val label = level.type.shortLabel()
        val textWidth = labelPaint.measureText(label)
        if (cw - left < textWidth * 2.2f) continue
        labelPaint.color = color.copy(alpha = 0.95f).toArgb()
        drawContext.canvas.nativeCanvas.drawText(
            label,
            (left + 4f * scale).coerceAtMost(cw - textWidth - 2f * scale),
            (y - 3f * scale).coerceIn(labelPaint.textSize, ch - 2f),
            labelPaint,
        )
    }
}

private fun KeyLevelType.shortLabel(): String = when (this) {
    KeyLevelType.ILQ -> "ILQ"
    KeyLevelType.SLQ -> "SLQ"
    KeyLevelType.DECISIONAL_SLQ -> "D-SLQ"
    KeyLevelType.TLQ -> "TLQ"
    KeyLevelType.EPA_DP -> "EPA+DP"
    KeyLevelType.EPA_DP_TOM -> "EPA+DP+TOM"
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(), (red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt(),
)
