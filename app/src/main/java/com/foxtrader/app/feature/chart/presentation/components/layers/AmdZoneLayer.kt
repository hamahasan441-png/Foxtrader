package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.feature.chart.presentation.chartOverlayLabelBaseline
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport

private val AmdAccumulationFill = Color(0xFF9B7BFF)
private val AmdAccumulationEdge = Color(0xFFC7B3FF)
private val AmdSweepColor = Color(0xFFFFD166)
private val AmdSweepDash = PathEffect.dashPathEffect(floatArrayOf(4f, 5f))

private const val AMD_LABEL_DP = 8.5f
private const val AMD_ZONE_CORNER_DP = 3f
private const val AMD_ZONE_BORDER_DP = 1.0f
private const val AMD_SWEEP_MARKER_RADIUS_DP = 3.2f
private const val AMD_GLOW_LAYERS = 3

/**
 * Premium context for the unified AMD confirmation arrow drawn by
 * [drawSignalMarkers]: the accumulation range as a glassy violet box and the
 * manipulation sweep as a gold marker with a dashed ray back into the range it
 * violated. The confirmation/distribution arrow itself is intentionally NOT
 * repainted here — it already comes from the single shared signal layer so
 * every engine's arrow stays visually consistent.
 *
 * The most recently confirmed cycle in the supplied list is drawn at full
 * strength; older ones are dimmed, mirroring how historical vs. live arrows
 * are styled everywhere else on the chart.
 */
internal fun DrawScope.drawAmdZones(
    signals: List<AccumulationManipulationDistributionEngine.Signal>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    if (signals.isEmpty()) return
    if (!cw.isDrawableSpan() || !ch.isDrawableSpan()) return

    val scale = density
    labelPaint.textSize = AMD_LABEL_DP * scale
    val borderWidth = AMD_ZONE_BORDER_DP * scale
    val cornerRadius = CornerRadius(AMD_ZONE_CORNER_DP * scale, AMD_ZONE_CORNER_DP * scale)
    val markerRadius = AMD_SWEEP_MARKER_RADIUS_DP * scale
    val newestConfirmation = signals.maxOf { it.confirmationIndex }

    val firstVisible = viewport.startIndex - 1f
    val lastVisible = viewport.startIndex + viewport.visibleBars + 1f

    for (signal in signals) {
        if (signal.sweepIndex < firstVisible || signal.accumulationStartIndex > lastVisible) continue
        if (!signal.accumulationHigh.isDrawablePrice() || !signal.accumulationLow.isDrawablePrice()) continue

        val isLive = signal.confirmationIndex == newestConfirmation
        val zoneAlpha = if (isLive) 0.16f else 0.08f
        val edgeAlpha = if (isLive) 0.55f else 0.28f

        val left = viewport.xForIndex(signal.accumulationStartIndex.toFloat(), cw).coerceIn(0f, cw)
        val right = viewport.xForIndex(signal.sweepIndex.toFloat(), cw).coerceIn(0f, cw)
        val width = right - left
        val top = viewport.yForPrice(signal.accumulationHigh, ch).coerceIn(0f, ch)
        val bottom = viewport.yForPrice(signal.accumulationLow, ch).coerceIn(0f, ch)
        val height = bottom - top
        if (!width.isFinite() || width <= 0.5f || !height.isFinite() || height <= 0.5f) continue

        // Glassy accumulation range: soft fill plus a layered glow border
        // (successive strokes, widening and fading) for a premium look.
        drawRoundRect(
            color = AmdAccumulationFill.copy(alpha = zoneAlpha),
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = cornerRadius,
        )
        for (layer in 0 until AMD_GLOW_LAYERS) {
            val glowAlpha = (edgeAlpha * (1f - layer * 0.32f)).coerceIn(0f, 1f)
            if (glowAlpha <= 0f) continue
            drawRoundRect(
                color = AmdAccumulationEdge.copy(alpha = glowAlpha),
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = cornerRadius,
                style = Stroke(width = borderWidth + layer * scale * 1.1f),
            )
        }

        val accLabel = "ACC"
        if (width >= labelPaint.measureText(accLabel) * 2.2f) {
            labelPaint.color = AmdAccumulationEdge.copy(alpha = if (isLive) 0.95f else 0.55f).toArgb()
            labelPaint.textAlign = Paint.Align.LEFT
            chartOverlayLabelBaseline(
                requested = top + labelPaint.textSize + 2f * scale,
                textSize = labelPaint.textSize,
                chartHeight = ch,
            )?.let { labelY ->
                drawContext.canvas.nativeCanvas.drawText(
                    accLabel,
                    left + 4f * scale,
                    labelY,
                    labelPaint,
                )
            }
        }

        // Manipulation marker: a gold "sweep" pip at the violated extreme, with
        // a dashed ray tying it back to the range edge it pierced.
        val sweepPrice = if (signal.direction == Direction.BULLISH) signal.accumulationLow else signal.accumulationHigh
        val sweepX = viewport.xForIndex(signal.sweepIndex + 0.5f, cw)
        val sweepY = viewport.yForPrice(sweepPrice, ch)
        if (sweepX.isFinite() && sweepY.isFinite() && sweepY in -12f..(ch + 12f)) {
            val markerAlpha = if (isLive) 0.95f else 0.55f
            drawCircle(
                color = AmdSweepColor.copy(alpha = markerAlpha * 0.35f),
                radius = markerRadius * 1.8f,
                center = Offset(sweepX, sweepY),
            )
            drawCircle(
                color = AmdSweepColor.copy(alpha = markerAlpha),
                radius = markerRadius,
                center = Offset(sweepX, sweepY),
            )
            val edgeY = if (signal.direction == Direction.BULLISH) bottom else top
            drawLine(
                color = AmdSweepColor.copy(alpha = markerAlpha * 0.6f),
                start = Offset(sweepX, sweepY),
                end = Offset(right, edgeY),
                strokeWidth = 1f * scale,
                pathEffect = AmdSweepDash,
            )
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(), (red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt(),
)

private fun Double.isDrawablePrice(): Boolean = isFinite() && this > 0.0
private fun Float.isDrawableSpan(): Boolean = isFinite() && this > 0f
