package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish

private val SignalDash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)

/**
 * Draws LIT X signal overlays on the chart when a validated signal exists.
 *
 * Renders:
 * - Entry line (horizontal, full width) in directional color
 * - Stop-loss line (dashed, bearish color)
 * - Take-profit lines (dashed, bullish color)
 * - Direction arrow (triangle) at the right edge near entry price
 */
internal fun DrawScope.drawLitXSignals(
    analysis: LitXAnalysis,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val signal = analysis.signal ?: return

    val entryY = viewport.yForPrice(signal.entry, ch)
    val stopY = viewport.yForPrice(signal.stopLoss, ch)
    val tp1Y = viewport.yForPrice(signal.takeProfit1, ch)
    val tp2Y = viewport.yForPrice(signal.takeProfit2, ch)

    val dirColor = if (signal.direction == Direction.BULLISH) FoxBullish else FoxBearish

    // Entry line (solid, full width)
    drawLine(
        color = dirColor.copy(alpha = 0.9f),
        start = Offset(0f, entryY),
        end = Offset(cw, entryY),
        strokeWidth = 2.5f,
    )

    // Stop-loss line (dashed, bearish)
    drawLine(
        color = FoxBearish.copy(alpha = 0.8f),
        start = Offset(0f, stopY),
        end = Offset(cw, stopY),
        strokeWidth = 1.5f,
        pathEffect = SignalDash,
    )

    // Take-profit 1 (dashed, bullish)
    drawLine(
        color = FoxBullish.copy(alpha = 0.7f),
        start = Offset(0f, tp1Y),
        end = Offset(cw, tp1Y),
        strokeWidth = 1.5f,
        pathEffect = SignalDash,
    )

    // Take-profit 2 (dashed, bullish)
    drawLine(
        color = FoxBullish.copy(alpha = 0.7f),
        start = Offset(0f, tp2Y),
        end = Offset(cw, tp2Y),
        strokeWidth = 1.5f,
        pathEffect = SignalDash,
    )

    // Direction arrow (triangle) at the right edge near entry price
    val arrowSize = 10f
    val arrowX = cw - 16f
    val arrowPath = Path().apply {
        if (signal.direction == Direction.BULLISH) {
            moveTo(arrowX, entryY - arrowSize)
            lineTo(arrowX - arrowSize / 2f, entryY + arrowSize / 2f)
            lineTo(arrowX + arrowSize / 2f, entryY + arrowSize / 2f)
        } else {
            moveTo(arrowX, entryY + arrowSize)
            lineTo(arrowX - arrowSize / 2f, entryY - arrowSize / 2f)
            lineTo(arrowX + arrowSize / 2f, entryY - arrowSize / 2f)
        }
        close()
    }
    drawPath(arrowPath, color = dirColor.copy(alpha = 0.95f), style = Fill)
}

/**
 * Draws SMT (Smart Money Technique) divergence markers on the chart.
 *
 * For each divergence in the visible range:
 * - Circle marker at the primary swing index at primaryPrice
 * - Dashed connecting line from (primaryIndex, primaryPrice) to (peerIndex, peerPrice)
 * - Colored based on direction (bullish = FoxBullish, bearish = FoxBearish)
 */
internal fun DrawScope.drawSmtDivergences(
    divergences: List<SmtDivergenceDetector.SmtDivergence>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (divergences.isEmpty()) return

    val startIdx = viewport.startIndex.toInt().coerceAtLeast(0)
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (div in divergences) {
        // Only draw divergences that are at least partially visible
        if (div.primaryIndex > endIdx && div.peerIndex > endIdx) continue
        if (div.primaryIndex < startIdx && div.peerIndex < startIdx) continue

        val color = if (div.direction == Direction.BULLISH) FoxBullish else FoxBearish

        val primaryX = viewport.xForIndex(div.primaryIndex + 0.5f, cw)
        val primaryY = viewport.yForPrice(div.primaryPrice, ch)
        val peerX = viewport.xForIndex(div.peerIndex + 0.5f, cw)
        val peerY = viewport.yForPrice(div.peerPrice, ch)

        // Circle marker at primary swing point
        drawCircle(
            color = color.copy(alpha = 0.9f),
            radius = 6f,
            center = Offset(primaryX, primaryY),
        )

        // Dashed connecting line from primary to peer level
        drawLine(
            color = color.copy(alpha = 0.6f),
            start = Offset(primaryX, primaryY),
            end = Offset(peerX, peerY),
            strokeWidth = 1.5f,
            pathEffect = SignalDash,
        )

        // Small circle at peer point
        drawCircle(
            color = color.copy(alpha = 0.6f),
            radius = 4f,
            center = Offset(peerX, peerY),
        )
    }
}

/**
 * Creates a fresh [Paint] for signal marker labels on each call to avoid
 * shared mutable state across concurrent DrawScope invocations. The alpha
 * is set at creation time so no mutation occurs after construction.
 */
private fun signalMarkerLabelPaint(alpha: Float): Paint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 18f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
    this.alpha = (alpha * 255).toInt()
}

/**
 * Draws unified signal markers on the chart for both live and history signals.
 *
 * - Live signals: filled circle at full opacity (alpha=0.9)
 * - History signals: hollow circle at faded opacity (alpha=0.3)
 * - Color based on direction (FoxBullish/FoxBearish)
 * - Source letter drawn inside: L (LITX), T (TRADEPRO), S (SMT)
 */
internal fun DrawScope.drawSignalMarkers(
    signals: List<ChartSignal>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (signals.isEmpty()) return

    val startIdx = viewport.startIndex.toInt().coerceAtLeast(0)
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (signal in signals) {
        // Viewport culling: skip signals outside the visible range
        if (signal.barIndex < startIdx || signal.barIndex > endIdx) continue

        val color = if (signal.direction == Direction.BULLISH) FoxBullish else FoxBearish
        val alpha = if (signal.isLive) 0.9f else 0.3f
        val radius = 10f

        val x = viewport.xForIndex(signal.barIndex + 0.5f, cw)
        val y = viewport.yForPrice(signal.entry, ch)
        val center = Offset(x, y)

        if (signal.isLive) {
            // Filled circle for live signals
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Fill,
            )
        } else {
            // Hollow circle for history signals
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = 2f),
            )
        }

        // Draw source letter inside the marker
        val letter = when (signal.source) {
            SignalSource.LITX -> "L"
            SignalSource.TRADEPRO -> "T"
            SignalSource.SMT -> "S"
            SignalSource.STRATEGY -> "X"
        }
        val labelPaint = signalMarkerLabelPaint(alpha)
        drawContext.canvas.nativeCanvas.drawText(
            letter,
            x,
            y + labelPaint.textSize / 3f,
            labelPaint,
        )

        // For the live signal, project the actual trade levels to the right of
        // the marker. Without these a marker tells the trader a setup exists
        // but not where to enter, stop, or target — which is the whole point.
        if (signal.isLive && signal.sl != 0.0 && signal.tp != 0.0) {
            drawSignalLevels(signal, viewport, x, cw, ch, color)
        }
    }
}

/**
 * Draws the entry / stop / target rays for a live signal, starting at the
 * signal bar and extending to the right edge, plus tinted risk and reward
 * zones so the R:R is readable at a glance.
 */
private fun DrawScope.drawSignalLevels(
    signal: ChartSignal,
    viewport: ChartViewport,
    markerX: Float,
    cw: Float,
    ch: Float,
    dirColor: androidx.compose.ui.graphics.Color,
) {
    val entryY = viewport.yForPrice(signal.entry, ch)
    val slY = viewport.yForPrice(signal.sl, ch)
    val tpY = viewport.yForPrice(signal.tp, ch)
    val left = markerX.coerceIn(0f, cw)
    val width = (cw - left).coerceAtLeast(0f)
    if (width <= 0f) return

    // Reward zone (entry → target) and risk zone (entry → stop).
    drawRect(
        color = FoxBullish.copy(alpha = 0.10f),
        topLeft = Offset(left, minOf(entryY, tpY)),
        size = Size(width, kotlin.math.abs(tpY - entryY)),
    )
    drawRect(
        color = FoxBearish.copy(alpha = 0.10f),
        topLeft = Offset(left, minOf(entryY, slY)),
        size = Size(width, kotlin.math.abs(slY - entryY)),
    )

    drawLine(dirColor.copy(alpha = 0.95f), Offset(left, entryY), Offset(cw, entryY), strokeWidth = 2f)
    drawLine(FoxBearish.copy(alpha = 0.85f), Offset(left, slY), Offset(cw, slY), strokeWidth = 1.4f, pathEffect = SignalDash)
    drawLine(FoxBullish.copy(alpha = 0.85f), Offset(left, tpY), Offset(cw, tpY), strokeWidth = 1.4f, pathEffect = SignalDash)
}
