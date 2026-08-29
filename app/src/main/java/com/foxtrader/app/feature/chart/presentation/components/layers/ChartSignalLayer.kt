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
import com.foxtrader.app.domain.model.BacktestChartMarker
import com.foxtrader.app.domain.model.BacktestOutcome
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import com.foxtrader.app.feature.chart.presentation.chartOverlayLabelBaseline
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish

private val SignalDash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
private val SignalArrowScratch = Path()

// `DENSITY` `textSize` is assigned from the DrawScope's density on every draw
// pass (see [drawSignalMarkers] / [drawBacktestMarkers]); the value here is only
// a placeholder so the paint is never used unconfigured. Drawing happens on the
// single render thread, so mutating these shared paints per pass is safe.
private val LiveSignalLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
    alpha = (0.92f * 255).toInt()
}

private val BacktestOutcomeLabelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
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
            strokeWidth = 1f * density,
            pathEffect = SignalDash,
        )
        drawCircle(
            color = color.copy(alpha = 0.58f),
            radius = 2.5f * density,
            center = Offset(primaryX, primaryY),
        )
    }
}

/**
 * Unified signal renderer: every signal-capable engine lands here as an arrow
 * anchored to its confirmation bar. Most historical markers are outline-only;
 * LiT Adventure history stays filled so its signal-only indicator remains
 * legible. The newest actionable markers are filled and labelled.
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
    val scale = density
    val laneSpacing = SIGNAL_LANE_SPACING_DP * scale
    LiveSignalLabelPaint.textSize = SIGNAL_LABEL_TEXT_DP * scale

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
        val laneOffset = lane.coerceAtMost(MAX_SIGNAL_LANES - 1) * laneSpacing
        val markerY = if (signal.direction == Direction.BULLISH) baseY + laneOffset else baseY - laneOffset

        // User-facing signal language: long = green, short = amber/yellow.
        val color = if (signal.direction == Direction.BULLISH) FoxBullish else FoxAmber50
        val isPersistentLit = signal.source == SignalSource.LITX || signal.source == SignalSource.LIT
        val alpha = when {
            signal.isLive -> 0.98f
            isPersistentLit -> 0.84f
            else -> 0.72f
        }
        val arrow = signalArrowPath(x, markerY, signal.direction, scale)

        drawPath(
            path = arrow,
            color = color.copy(alpha = alpha),
            // Both LiT studies are explicitly signal-only, so their historical
            // arrows stay filled. Other history remains outline-only.
            style = if (signal.isLive || isPersistentLit) Fill else Stroke(width = 1.5f * scale),
        )

        if (signal.isLive) {
            val letter = when (signal.source) {
                SignalSource.LITX -> if (signal.direction == Direction.BULLISH) "LIT BUY" else "LIT SELL"
                SignalSource.LIT -> if (signal.direction == Direction.BULLISH) "LIT MAY BUY" else "LIT MAY SELL"
                SignalSource.SMS -> "SMS"
                SignalSource.TRADEPRO -> "TP"
                SignalSource.SMT -> "SMT"
                SignalSource.RSI_ORDERFLOW -> "ROF"
                SignalSource.RSI_REVERSAL -> "RSR"
                SignalSource.LIQUIDITY_SWEEP -> "LSW"
                SignalSource.VIRGIN_WICK -> "VW"
                SignalSource.PIVOT_SWEEP_DIVERGENCE -> "PSD"
                SignalSource.VALUE_AREA_LIQUIDITY_REJECTION -> "VALR"
                SignalSource.ACCUMULATION_MANIPULATION_DISTRIBUTION -> "AMD"
                SignalSource.NASCENT -> "NFX"
                SignalSource.APEX -> "APX"
                SignalSource.COMPASS -> "CMP"
                SignalSource.CRUCIBLE -> "CRU"
                SignalSource.KEYSTONE -> "KEY"
                SignalSource.BINARY3M -> "B3"
                SignalSource.STRATEGY -> "ST"
            }
            val confidencePercent = (signal.confidence.coerceIn(0.0, 1.0) * 100.0).toInt()
            // Clear of the arrow's full height on the entry side, sized in dp.
            val arrowExtent =
                (ARROW_GAP_DP + ARROW_HEAD_LENGTH_DP + ARROW_STEM_LENGTH_DP + SIGNAL_LABEL_OFFSET_DP) * scale
            val labelY = if (signal.direction == Direction.BULLISH) {
                markerY + arrowExtent
            } else {
                markerY - arrowExtent + SIGNAL_LABEL_TEXT_DP * scale
            }
            chartOverlayLabelBaseline(
                requested = labelY,
                textSize = SIGNAL_LABEL_TEXT_DP * scale,
                chartHeight = ch,
            )?.let { safeLabelY ->
                drawContext.canvas.nativeCanvas.drawText(
                    "$letter $confidencePercent%",
                    x,
                    safeLabelY,
                    LiveSignalLabelPaint,
                )
            }
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
    val scale = density
    val outcomeRadius = BACKTEST_OUTCOME_RADIUS_DP * scale
    BacktestOutcomeLabelPaint.textSize = BACKTEST_LABEL_TEXT_DP * scale

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
            path = signalArrowPath(entryX, entryY, marker.direction, scale),
            color = entryColor.copy(alpha = 0.78f),
            style = Fill,
        )
        if (exitY in -12f..(ch + 12f)) {
            drawCircle(
                color = outcomeColor.copy(alpha = 0.90f),
                radius = outcomeRadius,
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
                exitY + BACKTEST_LABEL_TEXT_DP * scale * 0.36f,
                BacktestOutcomeLabelPaint,
            )
        }
    }
}

/**
 * Build the entry arrow for one signal.
 *
 * `DENSITY` Every dimension is expressed in dp and converted through [density]
 * (px per dp). The previous implementation used raw pixel constants, so on a
 * modern high-density handset (a 440 dpi phone runs at ~2.75 px/dp, a 1440p
 * flagship higher still) a "7 px" arrow head measured barely 2.5 dp — the
 * marker was physically present but far too small to read. Sizing in dp makes
 * the arrow the same physical size on every display.
 */
private fun signalArrowPath(x: Float, entryY: Float, direction: Direction, density: Float): Path {
    val path = SignalArrowScratch
    path.rewind()
    val halfHead = ARROW_HALF_HEAD_DP * density
    val stemHalf = ARROW_STEM_HALF_DP * density
    val stemLength = ARROW_STEM_LENGTH_DP * density
    val headLength = ARROW_HEAD_LENGTH_DP * density
    val gap = ARROW_GAP_DP * density

    if (direction == Direction.BULLISH) {
        val tipY = entryY + gap
        val headBaseY = tipY + headLength
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
        val headBaseY = tipY - headLength
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

private const val MAX_SMT_CONTEXT_RAYS = 6
private const val MAX_SIGNAL_LANES = 3

// `DENSITY` Marker geometry in dp. Converted with the DrawScope's density at
// draw time so an arrow is the same physical size on every screen.
private const val SIGNAL_LANE_SPACING_DP = 14f
private const val ARROW_HALF_HEAD_DP = 7f
private const val ARROW_STEM_HALF_DP = 2.5f
private const val ARROW_STEM_LENGTH_DP = 9f
private const val ARROW_HEAD_LENGTH_DP = 10f
private const val ARROW_GAP_DP = 4f
private const val SIGNAL_LABEL_TEXT_DP = 10f
private const val SIGNAL_LABEL_OFFSET_DP = 12f
private const val BACKTEST_LABEL_TEXT_DP = 10f
private const val BACKTEST_OUTCOME_RADIUS_DP = 5f
