package com.foxtrader.app.feature.chart.presentation.components.layers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXAnalysis
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
