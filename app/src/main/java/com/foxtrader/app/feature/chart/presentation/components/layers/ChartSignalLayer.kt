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
import com.foxtrader.app.domain.model.BacktestChartMarker
import com.foxtrader.app.domain.model.BacktestOutcome
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.PriceZoneKind
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish

private val SignalDash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
private val ContextDash = PathEffect.dashPathEffect(floatArrayOf(5f, 7f), 0f)

private val LiveSignalLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 18f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
    alpha = (0.92f * 255).toInt()
}

private val HistorySignalLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 17f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
    alpha = (0.62f * 255).toInt()
}

private val InstitutionalContextPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 15f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.LEFT
    alpha = (0.72f * 255).toInt()
}

private val SmtLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 14f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
    alpha = (0.80f * 255).toInt()
}

private val BacktestOutcomeLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 15f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
}

/**
 * Premium LIT X projection.
 *
 * The previous renderer returned immediately when no final signal existed. That
 * made a healthy LIT X pipeline look broken during SCANNING/SHIFT/POI stages.
 * We now render the institutional context first (dealing range, equilibrium,
 * mitigation blocks and displacement), then add entry/risk geometry only when a
 * fully validated non-repainting signal exists.
 */
internal fun DrawScope.drawLitXSignals(
    analysis: LitXAnalysis,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    drawLitXContext(analysis, viewport, cw, ch)

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

    drawLine(
        color = dirColor.copy(alpha = 0.95f),
        start = Offset(0f, entryY),
        end = Offset(cw, entryY),
        strokeWidth = 2.8f,
    )
    drawLine(
        color = FoxBearish.copy(alpha = 0.82f),
        start = Offset(0f, stopY),
        end = Offset(cw, stopY),
        strokeWidth = 1.6f,
        pathEffect = SignalDash,
    )
    drawLine(
        color = FoxBullish.copy(alpha = 0.75f),
        start = Offset(0f, tp1Y),
        end = Offset(cw, tp1Y),
        strokeWidth = 1.6f,
        pathEffect = SignalDash,
    )
    drawLine(
        color = FoxBullish.copy(alpha = 0.65f),
        start = Offset(0f, tp2Y),
        end = Offset(cw, tp2Y),
        strokeWidth = 1.4f,
        pathEffect = SignalDash,
    )

    val arrowSize = 11f
    val arrowX = cw - 18f
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
    drawPath(arrowPath, color = dirColor.copy(alpha = 0.98f), style = Fill)

    val signalLabel = "LiTX ${signal.confidence.grade.name.replace('_', '+')} · ${signal.confidence.score}%"
    val signalLabelY = if (signal.direction == Direction.BULLISH) entryY - 9f else entryY + 19f
    drawContext.canvas.nativeCanvas.drawText(signalLabel, 10f, signalLabelY, InstitutionalContextPaint)
}

private fun DrawScope.drawLitXContext(
    analysis: LitXAnalysis,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    analysis.premiumDiscount?.let { zone ->
        if (
            zone.rangeHigh.isDrawablePrice() &&
            zone.rangeLow.isDrawablePrice() &&
            zone.equilibrium.isDrawablePrice() &&
            zone.rangeHigh > zone.equilibrium &&
            zone.equilibrium > zone.rangeLow
        ) {
            val highY = viewport.yForPrice(zone.rangeHigh, ch)
            val eqY = viewport.yForPrice(zone.equilibrium, ch)
            val lowY = viewport.yForPrice(zone.rangeLow, ch)
            val premiumTop = minOf(highY, eqY).coerceIn(0f, ch)
            val premiumBottom = maxOf(highY, eqY).coerceIn(0f, ch)
            val discountTop = minOf(eqY, lowY).coerceIn(0f, ch)
            val discountBottom = maxOf(eqY, lowY).coerceIn(0f, ch)
            val premiumAlpha = if (zone.currentZone == PriceZoneKind.PREMIUM) 0.10f else 0.045f
            val discountAlpha = if (zone.currentZone == PriceZoneKind.DISCOUNT) 0.10f else 0.045f

            if (premiumBottom > premiumTop) {
                drawRect(
                    color = FoxBearish.copy(alpha = premiumAlpha),
                    topLeft = Offset(0f, premiumTop),
                    size = Size(cw, premiumBottom - premiumTop),
                )
            }
            if (discountBottom > discountTop) {
                drawRect(
                    color = FoxBullish.copy(alpha = discountAlpha),
                    topLeft = Offset(0f, discountTop),
                    size = Size(cw, discountBottom - discountTop),
                )
            }
            drawLine(
                color = FoxAmber50.copy(alpha = 0.55f),
                start = Offset(0f, eqY),
                end = Offset(cw, eqY),
                strokeWidth = 1.3f,
                pathEffect = ContextDash,
            )
            drawContext.canvas.nativeCanvas.drawText("PREMIUM", 10f, (premiumTop + 17f).coerceAtMost(ch), InstitutionalContextPaint)
            drawContext.canvas.nativeCanvas.drawText("EQ", 10f, (eqY - 5f).coerceIn(14f, ch), InstitutionalContextPaint)
            drawContext.canvas.nativeCanvas.drawText("DISCOUNT", 10f, (discountTop + 17f).coerceAtMost(ch), InstitutionalContextPaint)
        }
    }

    analysis.mitigationBlocks.takeLast(MAX_CONTEXT_BLOCKS).forEach { block ->
        if (!block.highPrice.isDrawablePrice() || !block.lowPrice.isDrawablePrice() || block.highPrice <= block.lowPrice) {
            return@forEach
        }
        val topY = viewport.yForPrice(block.highPrice, ch).coerceIn(0f, ch)
        val bottomY = viewport.yForPrice(block.lowPrice, ch).coerceIn(0f, ch)
        val y = minOf(topY, bottomY)
        val height = kotlin.math.abs(bottomY - topY)
        if (height <= 0f) return@forEach
        val x = viewport.xForIndex(block.originIndex.toFloat(), cw).coerceIn(0f, cw)
        val color = if (block.direction == Direction.BULLISH) FoxBullish else FoxBearish
        drawRect(
            color = color.copy(alpha = 0.10f),
            topLeft = Offset(x, y),
            size = Size((cw - x).coerceAtLeast(0f), height),
        )
        drawLine(
            color = color.copy(alpha = 0.46f),
            start = Offset(x, y),
            end = Offset(cw, y),
            strokeWidth = 1.1f,
        )
    }

    analysis.displacement?.let { displacement ->
        if (displacement.startPrice.isDrawablePrice() && displacement.endPrice.isDrawablePrice()) {
            val startX = viewport.xForIndex(displacement.startIndex + 0.5f, cw)
            val endX = viewport.xForIndex(displacement.endIndex + 0.5f, cw)
            val startY = viewport.yForPrice(displacement.startPrice, ch)
            val endY = viewport.yForPrice(displacement.endPrice, ch)
            val color = if (displacement.direction == Direction.BULLISH) FoxBullish else FoxBearish
            drawLine(
                color = color.copy(alpha = 0.72f),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 2.2f,
            )
        }
    }

    val stageText = "LiTX · ${analysis.stage.name.replace('_', ' ')}"
    drawContext.canvas.nativeCanvas.drawText(stageText, 10f, 18f, InstitutionalContextPaint)
}

/**
 * Draws SMT divergence markers without projecting the peer instrument onto the
 * primary chart's incompatible price scale.
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
        if (div.primaryIndex > endIdx && div.confirmationIndex > endIdx) continue
        if (div.primaryIndex < startIdx && div.confirmationIndex < startIdx) continue

        val color = if (div.direction == Direction.BULLISH) FoxBullish else FoxBearish
        val primaryX = viewport.xForIndex(div.primaryIndex + 0.5f, cw)
        val primaryY = viewport.yForPrice(div.primaryPrice, ch)
        val confirmationX = viewport.xForIndex(div.confirmationIndex + 0.5f, cw)

        drawCircle(
            color = color.copy(alpha = 0.94f),
            radius = 7f,
            center = Offset(primaryX, primaryY),
        )
        drawCircle(
            color = color.copy(alpha = 0.30f),
            radius = 11f,
            center = Offset(primaryX, primaryY),
            style = Stroke(width = 1.5f),
        )
        drawLine(
            color = color.copy(alpha = 0.68f),
            start = Offset(primaryX, primaryY),
            end = Offset(confirmationX, primaryY),
            strokeWidth = 1.8f,
            pathEffect = SignalDash,
        )
        drawCircle(
            color = color.copy(alpha = 0.78f),
            radius = 5f,
            center = Offset(confirmationX, primaryY),
            style = Stroke(width = 1.8f),
        )

        val labelY = if (div.direction == Direction.BULLISH) primaryY + 22f else primaryY - 13f
        drawContext.canvas.nativeCanvas.drawText(
            "SMT ${div.peerSymbol} ${div.confidence.toInt()}%",
            confirmationX,
            labelY.coerceIn(14f, ch - 2f),
            SmtLabelPaint,
        )
    }
}

/**
 * Draws unified strategy/engine signals as directional arrows.
 * Live markers remain visually dominant; historical/template projections stay
 * clearly distinct while being readable enough to audit without a second view.
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
        val alpha = if (signal.isLive) 0.98f else 0.58f
        val x = viewport.xForIndex(signal.barIndex + 0.5f, cw)
        val entryY = viewport.yForPrice(signal.entry, ch)
        val arrow = signalArrowPath(x, entryY, signal.direction)

        drawPath(
            path = arrow,
            color = color.copy(alpha = alpha),
            style = if (signal.isLive) Fill else Stroke(width = 2.2f),
        )

        if (signal.isLive) {
            drawCircle(
                color = color.copy(alpha = 0.98f),
                radius = 3.8f,
                center = Offset(x, entryY),
            )
        }

        val letter = when (signal.source) {
            SignalSource.LITX -> "LX"
            SignalSource.LIT -> "LIT"
            SignalSource.SMS -> "SMS"
            SignalSource.TRADEPRO -> "TP"
            SignalSource.SMT -> "SMT"
            SignalSource.BINARY3M -> "B3"
            SignalSource.STRATEGY -> "ST"
        }
        val labelPaint = if (signal.isLive) LiveSignalLabelPaint else HistorySignalLabelPaint
        val labelY = if (signal.direction == Direction.BULLISH) entryY + 31f else entryY - 22f
        drawContext.canvas.nativeCanvas.drawText(letter, x, labelY.coerceIn(14f, ch - 2f), labelPaint)
    }
}

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
        drawContext.canvas.nativeCanvas.drawText(label, exitX, exitY + 5f, BacktestOutcomeLabelPaint)
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

private const val MAX_CONTEXT_BLOCKS = 4
