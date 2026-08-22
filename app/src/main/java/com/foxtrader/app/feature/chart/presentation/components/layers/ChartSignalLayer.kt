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
private val SignalArrowScratch = Path()

private val LiveSignalLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 13f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
    alpha = (0.88f * 255).toInt()
}

private val BacktestOutcomeLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 15f
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
}

/**
 * LIT X chart context.
 *
 * Final entries are deliberately NOT painted here. The unified signal layer is
 * the single source of truth for LiTX/LiT/SMS/SMT/TradePro/strategy arrows, so a
 * validated setup cannot be drawn twice as full-width entry/SL/TP lines plus an
 * arrow. This function only paints lightweight institutional context.
 */
internal fun DrawScope.drawLitXSignals(
    analysis: LitXAnalysis,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    drawLitXContext(analysis, viewport, cw, ch)
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
            if (highY.isFinite() && eqY.isFinite() && lowY.isFinite()) {
                val premiumTop = minOf(highY, eqY).coerceIn(0f, ch)
                val premiumBottom = maxOf(highY, eqY).coerceIn(0f, ch)
                val discountTop = minOf(eqY, lowY).coerceIn(0f, ch)
                val discountBottom = maxOf(eqY, lowY).coerceIn(0f, ch)
                val premiumAlpha = if (zone.currentZone == PriceZoneKind.PREMIUM) 0.065f else 0.025f
                val discountAlpha = if (zone.currentZone == PriceZoneKind.DISCOUNT) 0.065f else 0.025f

                if (premiumBottom > premiumTop) {
                    drawRect(
                        color = FoxBearish.copy(alpha = premiumAlpha),
                        topLeft = Offset(0f, premiumTop),
                        size = Size(cw.coerceAtLeast(0f), premiumBottom - premiumTop),
                    )
                }
                if (discountBottom > discountTop) {
                    drawRect(
                        color = FoxBullish.copy(alpha = discountAlpha),
                        topLeft = Offset(0f, discountTop),
                        size = Size(cw.coerceAtLeast(0f), discountBottom - discountTop),
                    )
                }
                if (eqY in 0f..ch) {
                    drawLine(
                        color = FoxAmber50.copy(alpha = 0.34f),
                        start = Offset(0f, eqY),
                        end = Offset(cw, eqY),
                        strokeWidth = 1f,
                        pathEffect = ContextDash,
                    )
                }
            }
        }
    }

    analysis.mitigationBlocks.takeLast(MAX_CONTEXT_BLOCKS).forEach { block ->
        if (!block.highPrice.isDrawablePrice() || !block.lowPrice.isDrawablePrice() || block.highPrice <= block.lowPrice) {
            return@forEach
        }
        val topYRaw = viewport.yForPrice(block.highPrice, ch)
        val bottomYRaw = viewport.yForPrice(block.lowPrice, ch)
        val xRaw = viewport.xForIndex(block.originIndex.toFloat(), cw)
        if (!topYRaw.isFinite() || !bottomYRaw.isFinite() || !xRaw.isFinite()) return@forEach

        val topY = topYRaw.coerceIn(0f, ch)
        val bottomY = bottomYRaw.coerceIn(0f, ch)
        val y = minOf(topY, bottomY)
        val height = kotlin.math.abs(bottomY - topY)
        if (height <= 0f) return@forEach
        val x = xRaw.coerceIn(0f, cw)
        val color = if (block.direction == Direction.BULLISH) FoxBullish else FoxBearish
        drawRect(
            color = color.copy(alpha = 0.07f),
            topLeft = Offset(x, y),
            size = Size((cw - x).coerceAtLeast(0f), height),
        )
        drawLine(
            color = color.copy(alpha = 0.32f),
            start = Offset(x, y),
            end = Offset(cw, y),
            strokeWidth = 1f,
        )
    }

    analysis.displacement?.let { displacement ->
        if (displacement.startPrice.isDrawablePrice() && displacement.endPrice.isDrawablePrice()) {
            val startX = viewport.xForIndex(displacement.startIndex + 0.5f, cw)
            val endX = viewport.xForIndex(displacement.endIndex + 0.5f, cw)
            val startY = viewport.yForPrice(displacement.startPrice, ch)
            val endY = viewport.yForPrice(displacement.endPrice, ch)
            if (startX.isFinite() && endX.isFinite() && startY.isFinite() && endY.isFinite()) {
                val color = if (displacement.direction == Direction.BULLISH) FoxBullish else FoxBearish
                drawLine(
                    color = color.copy(alpha = 0.54f),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 1.7f,
                )
            }
        }
    }
}

/**
 * SMT context stays subtle: the actionable confirmation is already represented
 * by the unified SMT arrow. We keep only a faint swing-to-confirmation ray so
 * the divergence can be audited without duplicating circles, labels and arrows.
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

    for (div in divergences.takeLast(MAX_SMT_CONTEXT_RAYS)) {
        if (
            div.primaryIndex < 0 ||
            div.confirmationIndex < div.primaryIndex ||
            !div.primaryPrice.isDrawablePrice()
        ) continue
        if (div.primaryIndex > endIdx && div.confirmationIndex > endIdx) continue
        if (div.primaryIndex < startIdx && div.confirmationIndex < startIdx) continue

        val primaryX = viewport.xForIndex(div.primaryIndex + 0.5f, cw)
        val primaryY = viewport.yForPrice(div.primaryPrice, ch)
        val confirmationX = viewport.xForIndex(div.confirmationIndex + 0.5f, cw)
        if (!primaryX.isFinite() || !primaryY.isFinite() || !confirmationX.isFinite()) continue
        if (primaryY !in -8f..(ch + 8f)) continue

        val color = if (div.direction == Direction.BULLISH) FoxBullish else FoxBearish
        drawLine(
            color = color.copy(alpha = 0.36f),
            start = Offset(primaryX, primaryY),
            end = Offset(confirmationX, primaryY),
            strokeWidth = 1.2f,
            pathEffect = SignalDash,
        )
        drawCircle(
            color = color.copy(alpha = 0.58f),
            radius = 3.2f,
            center = Offset(primaryX, primaryY),
        )
    }
}

/**
 * Unified signal renderer: every signal-capable engine lands here as an arrow
 * anchored to its confirmation bar. Historical markers are outline-only and do
 * not carry text; the newest actionable markers are filled and labelled.
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
    val laneCounts = HashMap<Long, Int>(8)

    for (signal in signals) {
        if (signal.barIndex < 0 || !signal.entry.isDrawablePrice()) continue
        if (signal.barIndex < startIdx || signal.barIndex > endIdx) continue

        val x = viewport.xForIndex(signal.barIndex + 0.5f, cw)
        val baseY = viewport.yForPrice(signal.entry, ch)
        if (!x.isFinite() || !baseY.isFinite()) continue
        if (baseY !in -48f..(ch + 48f)) continue

        val directionBit = if (signal.direction == Direction.BULLISH) 0L else 1L
        val laneKey = (signal.barIndex.toLong() shl 1) or directionBit
        val lane = laneCounts[laneKey] ?: 0
        laneCounts[laneKey] = lane + 1
        val laneOffset = lane.coerceAtMost(MAX_SIGNAL_LANES - 1) * SIGNAL_LANE_SPACING
        val markerY = if (signal.direction == Direction.BULLISH) baseY + laneOffset else baseY - laneOffset

        // User-facing signal language: long = green, short = amber/yellow.
        val color = if (signal.direction == Direction.BULLISH) FoxBullish else FoxAmber50
        val alpha = if (signal.isLive) 0.98f else 0.58f
        val arrow = signalArrowPath(x, markerY, signal.direction)

        drawPath(
            path = arrow,
            color = color.copy(alpha = alpha),
            style = if (signal.isLive) Fill else Stroke(width = 2f),
        )

        if (signal.isLive) {
            val letter = when (signal.source) {
                SignalSource.LITX -> "LX"
                SignalSource.LIT -> "LIT"
                SignalSource.SMS -> "SMS"
                SignalSource.TRADEPRO -> "TP"
                SignalSource.SMT -> "SMT"
                SignalSource.BINARY3M -> "B3"
                SignalSource.STRATEGY -> "ST"
            }
            val confidencePercent = (signal.confidence.coerceIn(0.0, 1.0) * 100.0).toInt()
            val labelY = if (signal.direction == Direction.BULLISH) markerY + 29f else markerY - 21f
            drawContext.canvas.nativeCanvas.drawText(
                "$letter $confidencePercent%",
                x,
                labelY.coerceIn(13f, ch - 2f),
                LiveSignalLabelPaint,
            )
        }
    }
}

internal fun DrawScope.drawBacktestMarkers(
    markers: List<BacktestChartMarker>,
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (markers.isEmpty() || candles.isEmpty()) return

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
        if (!entryX.isFinite() || !entryY.isFinite() || !exitX.isFinite() || !exitY.isFinite()) continue

        val entryColor = if (marker.direction == Direction.BULLISH) FoxBullish else FoxAmber50
        val outcomeColor = when (marker.outcome) {
            BacktestOutcome.WIN -> FoxBullish
            BacktestOutcome.LOSS -> FoxBearish
            BacktestOutcome.BREAKEVEN -> FoxAmber50
        }

        drawLine(
            color = outcomeColor.copy(alpha = 0.24f),
            start = Offset(entryX, entryY),
            end = Offset(exitX, exitY),
            strokeWidth = 1f,
            pathEffect = SignalDash,
        )
        drawPath(
            path = signalArrowPath(entryX, entryY, marker.direction),
            color = entryColor.copy(alpha = 0.78f),
            style = Fill,
        )
        if (exitY in -12f..(ch + 12f)) {
            drawCircle(
                color = outcomeColor.copy(alpha = 0.90f),
                radius = 7f,
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
}

private fun signalArrowPath(x: Float, entryY: Float, direction: Direction): Path {
    val path = SignalArrowScratch
    path.rewind()
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

private const val MAX_CONTEXT_BLOCKS = 2
private const val MAX_SMT_CONTEXT_RAYS = 6
private const val MAX_SIGNAL_LANES = 3
private const val SIGNAL_LANE_SPACING = 16f
