package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.BacktestChartMarker
import com.foxtrader.app.domain.model.BacktestOutcome
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish

private val SignalDash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)

// Reusable native Paint instances for zero-allocation rendering in draw loop
private val LiveSignalLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 18f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
    alpha = (0.9f * 255).toInt()
}

private val HistorySignalLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 18f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
    alpha = (0.3f * 255).toInt()
}

private val BacktestOutcomeLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 15f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
}

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
    val validPrices = signal.entry.isDrawablePrice() &&
        signal.stopLoss.isDrawablePrice() &&
        signal.takeProfit1.isDrawablePrice() &&
        signal.takeProfit2.isDrawablePrice()
    val validGeometry = when (signal.direction) {
        Direction.BULLISH -> signal.stopLoss < signal.entry &&
            signal.takeProfit1 > signal.entry && signal.takeProfit2 > signal.entry
        Direction.BEARISH -> signal.stopLoss > signal.entry &&
            signal.takeProfit1 < signal.entry && signal.takeProfit2 < signal.entry
    }
    if (!validPrices || !validGeometry) return

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
    val arrowPath = Path()
    if (signal.direction == Direction.BULLISH) {
        arrowPath.moveTo(arrowX, entryY - arrowSize)
        arrowPath.lineTo(arrowX - arrowSize / 2f, entryY + arrowSize / 2f)
        arrowPath.lineTo(arrowX + arrowSize / 2f, entryY + arrowSize / 2f)
    } else {
        arrowPath.moveTo(arrowX, entryY + arrowSize)
        arrowPath.lineTo(arrowX - arrowSize / 2f, entryY - arrowSize / 2f)
        arrowPath.lineTo(arrowX + arrowSize / 2f, entryY - arrowSize / 2f)
    }
    arrowPath.close()
    drawPath(arrowPath, color = dirColor.copy(alpha = 0.95f), style = Fill)
}

/**
 * Draws SMT (Smart Money Technique) divergence markers on the chart.
 *
 * For each divergence in the visible range:
 * - Circle marker at the primary swing index at primaryPrice
 * - Dashed confirmation ray from the swing to the bar where it became knowable
 * - Colored based on direction (bullish = FoxBullish, bearish = FoxBearish)
 *
 * Peer prices are intentionally not projected onto the primary chart's Y axis:
 * EURUSD, DXY, BTC, and an index can have completely different price scales.
 * Plotting [SmtDivergenceDetector.SmtDivergence.peerPrice] here produced an
 * invalid line (and frequently sent the endpoint off-screen).
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
        if (
            div.primaryIndex < 0 ||
            div.confirmationIndex < div.primaryIndex ||
            !div.primaryPrice.isDrawablePrice()
        ) continue
        // The primary swing and its confirmation span are the only coordinates
        // that belong to this chart's time/price axes.
        if (div.primaryIndex > endIdx && div.confirmationIndex > endIdx) continue
        if (div.primaryIndex < startIdx && div.confirmationIndex < startIdx) continue

        val color = if (div.direction == Direction.BULLISH) FoxBullish else FoxBearish

        val primaryX = viewport.xForIndex(div.primaryIndex + 0.5f, cw)
        val primaryY = viewport.yForPrice(div.primaryPrice, ch)
        val confirmationX = viewport.xForIndex(div.confirmationIndex + 0.5f, cw)

        // Circle marker at primary swing point
        drawCircle(
            color = color.copy(alpha = 0.9f),
            radius = 6f,
            center = Offset(primaryX, primaryY),
        )

        // Horizontal ray shows the non-repainting confirmation delay without
        // mixing a correlated asset's incompatible price scale into this chart.
        drawLine(
            color = color.copy(alpha = 0.6f),
            start = Offset(primaryX, primaryY),
            end = Offset(confirmationX, primaryY),
            strokeWidth = 1.5f,
            pathEffect = SignalDash,
        )

        // Small ring at the confirmation bar.
        drawCircle(
            color = color.copy(alpha = 0.6f),
            radius = 4f,
            center = Offset(confirmationX, primaryY),
            style = Stroke(width = 1.5f),
        )
    }
}

/**
 * Draws unified strategy/engine signals as directional arrows.
 *
 * - Bullish = green up-arrow below the entry.
 * - Bearish = amber/yellow down-arrow above the entry.
 * - Live/confirmed-now signals are filled and carry a confirmation dot.
 * - Historical signals are outlined/faded so they cannot be mistaken for a
 *   current actionable setup.
 * - The source letter stays visible beside the arrow (L/T/S/X).
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
        if (signal.barIndex < 0 || !signal.entry.isDrawablePrice()) continue
        if (signal.barIndex < startIdx || signal.barIndex > endIdx) continue

        val color = if (signal.direction == Direction.BULLISH) FoxBullish else FoxAmber50
        val alpha = if (signal.isLive) 0.95f else 0.34f
        val x = viewport.xForIndex(signal.barIndex + 0.5f, cw)
        val entryY = viewport.yForPrice(signal.entry, ch)
        val arrow = signalArrowPath(x, entryY, signal.direction)

        drawPath(
            path = arrow,
            color = color.copy(alpha = alpha),
            style = if (signal.isLive) Fill else Stroke(width = 2f),
        )

        // Confirmation dot anchors the signal to its actual entry price. It is
        // intentionally tiny so multiple strategies on one bar remain legible.
        if (signal.isLive) {
            drawCircle(
                color = color.copy(alpha = 0.95f),
                radius = 3.5f,
                center = Offset(x, entryY),
            )
        }

        val letter = when (signal.source) {
            SignalSource.LITX -> "LX"
            SignalSource.LIT -> "L"
            SignalSource.SMS -> "M"
            SignalSource.TRADEPRO -> "T"
            SignalSource.SMT -> "S"
            SignalSource.BINARY3M -> "B3"
            SignalSource.STRATEGY -> "X"
        }
        val labelPaint = if (signal.isLive) LiveSignalLabelPaint else HistorySignalLabelPaint
        val labelY = if (signal.direction == Direction.BULLISH) entryY + 30f else entryY - 22f
        drawContext.canvas.nativeCanvas.drawText(letter, x, labelY, labelPaint)
    }
}

/**
 * Draws completed backtest trades directly on the price chart. Entry arrows use
 * the same green/amber directional language as live signals; exits receive a
 * compact W/L/B badge so a trader can visually audit which historical signals
 * won, lost, or broke even without leaving the chart.
 */
internal fun DrawScope.drawBacktestMarkers(
    markers: List<BacktestChartMarker>,
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (markers.isEmpty()) return

    val startIdx = viewport.startIndex.toInt().coerceAtLeast(0)
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (marker in markers) {
        if (!marker.entryPrice.isDrawablePrice() || !marker.exitPrice.isDrawablePrice()) continue
        val entryIndex = marker.resolveIndex(candles, isEntry = true)
        val exitIndex = marker.resolveIndex(candles, isEntry = false)
        if (entryIndex < 0 || exitIndex < entryIndex) continue
        if (exitIndex < startIdx || entryIndex > endIdx) continue

        val entryX = viewport.xForIndex(entryIndex + 0.5f, cw)
        val entryY = viewport.yForPrice(marker.entryPrice, ch)
        val exitX = viewport.xForIndex(exitIndex + 0.5f, cw)
        val exitY = viewport.yForPrice(marker.exitPrice, ch)
        val entryColor = if (marker.direction == Direction.BULLISH) FoxBullish else FoxAmber50
        val outcomeColor = when (marker.outcome) {
            BacktestOutcome.WIN -> FoxBullish
            BacktestOutcome.LOSS -> FoxBearish
            BacktestOutcome.BREAKEVEN -> FoxAmber50
        }

        // Thin dashed connector makes holding duration and exit location obvious
        // while staying subordinate to price action.
        drawLine(
            color = outcomeColor.copy(alpha = 0.30f),
            start = Offset(entryX, entryY),
            end = Offset(exitX, exitY),
            strokeWidth = 1.25f,
            pathEffect = SignalDash,
        )

        drawPath(
            path = signalArrowPath(entryX, entryY, marker.direction),
            color = entryColor.copy(alpha = 0.85f),
            style = Fill,
        )

        drawCircle(
            color = outcomeColor.copy(alpha = 0.92f),
            radius = 8f,
            center = Offset(exitX, exitY),
        )
        val label = when (marker.outcome) {
            BacktestOutcome.WIN -> "W"
            BacktestOutcome.LOSS -> "L"
            BacktestOutcome.BREAKEVEN -> "B"
        }
        drawContext.canvas.nativeCanvas.drawText(
            label,
            exitX,
            exitY + 5f,
            BacktestOutcomeLabelPaint,
        )
    }
}

private fun signalArrowPath(x: Float, entryY: Float, direction: Direction): Path {
    val path = Path()
    val halfHead = 7f
    val stemHalf = 2.5f
    val stemLength = 9f
    val gap = 4f

    if (direction == Direction.BULLISH) {
        val tipY = entryY + gap
        val headBaseY = tipY + 10f
        val stemBottomY = headBaseY + stemLength
        path.moveTo(x, tipY)
        path.lineTo(x - halfHead, headBaseY)
        path.lineTo(x - stemHalf, headBaseY)
        path.lineTo(x - stemHalf, stemBottomY)
        path.lineTo(x + stemHalf, stemBottomY)
        path.lineTo(x + stemHalf, headBaseY)
        path.lineTo(x + halfHead, headBaseY)
    } else {
        val tipY = entryY - gap
        val headBaseY = tipY - 10f
        val stemTopY = headBaseY - stemLength
        path.moveTo(x, tipY)
        path.lineTo(x - halfHead, headBaseY)
        path.lineTo(x - stemHalf, headBaseY)
        path.lineTo(x - stemHalf, stemTopY)
        path.lineTo(x + stemHalf, stemTopY)
        path.lineTo(x + stemHalf, headBaseY)
        path.lineTo(x + halfHead, headBaseY)
    }
    path.close()
    return path
}

private fun BacktestChartMarker.resolveIndex(candles: List<Candle>, isEntry: Boolean): Int {
    val hintedIndex = if (isEntry) entryIndex else exitIndex
    val timestamp = if (isEntry) entryTime else exitTime
    if (hintedIndex in candles.indices && candles[hintedIndex].timestamp == timestamp) return hintedIndex

    var low = 0
    var high = candles.lastIndex
    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midTs = candles[mid].timestamp
        when {
            midTs < timestamp -> low = mid + 1
            midTs > timestamp -> high = mid - 1
            else -> return mid
        }
    }
    return -1
}

private fun Double.isDrawablePrice(): Boolean = isFinite() && this > 0.0
