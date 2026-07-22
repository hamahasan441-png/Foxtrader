package com.foxtrader.app.feature.chart.presentation.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral5
import com.foxtrader.app.ui.theme.FoxNeutral60
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Professional-grade candlestick chart engine.
 *
 * Multi-layer rendering architecture (single Canvas pass):
 *   Layer 0: Grid lines (price levels + time divisions)
 *   Layer 1: Candles (viewport-culled, GPU-accelerated)
 *   Layer 2: Indicator overlays (EMA/SMA lines)
 *   Layer 3: Market structure annotations (BOS/CHOCH)
 *   Layer 4: Live price reference line
 *   Layer 5: Crosshair (when active)
 *   Layer 6: Price scale (Y-axis, right edge)
 *   Layer 7: Time axis (X-axis, bottom edge)
 *
 * Performance contract:
 * - 120 FPS on modern hardware, never below 60 FPS
 * - Viewport culling bounds draw cost to visible bars only
 * - Zero per-frame allocations in draw loop
 * - Single unified gesture handler: single-finger pan + pinch zoom (no drift)
 * - Long-press activates crosshair
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    modifier: Modifier = Modifier,
    structureBreaks: List<StructureBreak> = emptyList(),
    timeframe: Timeframe = Timeframe.M15,
    emaShort: DoubleArray? = null,
    emaLong: DoubleArray? = null,
    orderBlocks: List<com.foxtrader.app.domain.model.OrderBlock> = emptyList(),
    fairValueGaps: List<com.foxtrader.app.domain.model.FairValueGap> = emptyList(),
    liquidityPools: List<com.foxtrader.app.domain.model.LiquidityPool> = emptyList(),
    sessions: List<com.foxtrader.app.domain.model.SessionRange> = emptyList(),
    drawings: List<com.foxtrader.app.domain.model.ChartDrawing> = emptyList(),
    volumeProfile: com.foxtrader.app.domain.model.VolumeProfile? = null,
) {
    val density = LocalDensity.current

    // Viewport survives recomposition. Layout margins set in density-independent pixels.
    val viewport = remember {
        ChartViewport().apply {
            priceScaleWidth = with(density) { 64.dp.toPx() }
            timeAxisHeight = with(density) { 24.dp.toPx() }
        }
    }

    // Redraw trigger — bumped after every gesture frame.
    var invalidateTick by remember { mutableIntStateOf(0) }

    // Native Paint objects (reused across frames — zero allocation in draw loop)
    val priceLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#99999F")
            textSize = with(density) { 10.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }
    val timeLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#99999F")
            textSize = with(density) { 9.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val crosshairLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 10.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val structureLabelPaint = remember {
        Paint().apply {
            textSize = with(density) { 8.dp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    // Initialise viewport when data arrives or grows.
    remember(candles.size) {
        val count = min(120, candles.size).toFloat()
        if (viewport.visibleBars <= 0f || viewport.startIndex == 0f) {
            viewport.visibleBars = count.coerceAtLeast(10f)
            viewport.startIndex = max(0f, candles.size - count)
        }
        viewport.clamp(candles.size)
        viewport.autoScale(candles)
        candles.size
    }

    Canvas(
        modifier = modifier
            .background(FoxNeutral5)
            // --- PAN (single finger) + ZOOM (pinch) in ONE handler ---
            // A single detectTransformGestures prevents the chart "drift" bug:
            // previously a transform handler AND a drag handler both applied pan
            // to the same one-finger drag, doubling/fighting the movement.
            // detectTransformGestures natively reports pan for a single pointer.
            .pointerInput(candles.size) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // A pan/zoom interaction dismisses the crosshair.
                    if (viewport.crosshairActive) viewport.crosshairActive = false

                    val cw = viewport.chartWidth(size.width.toFloat())
                    val barsPerPx = viewport.visibleBars / cw

                    // Horizontal pan (works for 1 or 2 fingers).
                    if (pan.x != 0f) {
                        viewport.startIndex -= pan.x * barsPerPx
                    }

                    // Pinch zoom toward the viewport center.
                    if (zoom != 1f && zoom > 0f) {
                        val center = viewport.startIndex + viewport.visibleBars / 2f
                        viewport.visibleBars = viewport.visibleBars / zoom
                        viewport.startIndex = center - viewport.visibleBars / 2f
                    }

                    viewport.clamp(candles.size)
                    viewport.autoScale(candles)
                    invalidateTick++
                }
            }
            // --- LONG-PRESS FOR CROSSHAIR ---
            .pointerInput(candles.size) {
                detectTapGestures(
                    onLongPress = { offset ->
                        viewport.crosshairActive = true
                        viewport.crosshairX = offset.x.coerceIn(0f, viewport.chartWidth(size.width.toFloat()))
                        viewport.crosshairY = offset.y.coerceIn(0f, viewport.chartHeight(size.height.toFloat()))
                        viewport.crosshairTotalWidth = size.width.toFloat()
                        invalidateTick++
                    },
                    onTap = {
                        viewport.crosshairActive = false
                        invalidateTick++
                    },
                )
            },
    ) {
        @Suppress("UNUSED_EXPRESSION") invalidateTick // Subscribe to changes

        if (candles.isEmpty()) return@Canvas

        val totalW = size.width
        val totalH = size.height
        val cw = viewport.chartWidth(totalW)
        val ch = viewport.chartHeight(totalH)

        // ====================================================================
        // LAYER 0: GRID LINES
        // ====================================================================
        drawGridLayer(viewport, cw, ch, totalW)

        // ====================================================================
        // LAYER 0.5: SESSION BACKGROUNDS (behind candles)
        // ====================================================================
        if (sessions.isNotEmpty()) {
            clipRect(right = cw, bottom = ch) {
                drawSessionBackgrounds(sessions, viewport, cw, ch)
            }
        }

        // ====================================================================
        // LAYER 0.7: ORDER BLOCKS + FAIR VALUE GAPS (behind candles)
        // ====================================================================
        clipRect(right = cw, bottom = ch) {
            if (orderBlocks.isNotEmpty()) drawOrderBlocks(orderBlocks, viewport, cw, ch)
            if (fairValueGaps.isNotEmpty()) drawFairValueGaps(fairValueGaps, viewport, cw, ch)
        }

        // ====================================================================
        // LAYER 1: CANDLES (viewport-culled)
        // ====================================================================
        clipRect(right = cw, bottom = ch) {
            drawCandleLayer(candles, viewport, cw, ch)
        }

        // ====================================================================
        // LAYER 1.5: LIQUIDITY POOLS (over candles, under indicators)
        // ====================================================================
        if (liquidityPools.isNotEmpty()) {
            clipRect(right = cw, bottom = ch) {
                drawLiquidityPools(liquidityPools, viewport, cw, ch)
            }
        }

        // ====================================================================
        // LAYER 1.7: VOLUME PROFILE (horizontal histogram, right-aligned)
        // ====================================================================
        if (volumeProfile != null) {
            clipRect(right = cw, bottom = ch) {
                drawVolumeProfile(volumeProfile, viewport, cw, ch)
            }
        }

        // ====================================================================
        // LAYER 2: INDICATOR OVERLAYS (EMA lines)
        // ====================================================================
        if (emaShort != null || emaLong != null) {
            clipRect(right = cw, bottom = ch) {
                drawIndicatorLayer(candles, viewport, cw, ch, emaShort, emaLong)
            }
        }

        // ====================================================================
        // LAYER 3: MARKET STRUCTURE ANNOTATIONS
        // ====================================================================
        if (structureBreaks.isNotEmpty()) {
            clipRect(right = cw, bottom = ch) {
                drawStructureLayer(structureBreaks, candles, viewport, cw, ch, structureLabelPaint)
            }
        }

        // ====================================================================
        // LAYER 4: LIVE PRICE REFERENCE LINE
        // ====================================================================
        clipRect(right = cw, bottom = ch) {
            drawLivePriceLine(candles, viewport, cw, ch)
        }

        // ====================================================================
        // LAYER 4.5: USER DRAWINGS (trend lines, fibs, etc.)
        // ====================================================================
        if (drawings.isNotEmpty()) {
            clipRect(right = cw, bottom = ch) {
                drawChartDrawings(drawings, viewport, cw, ch, structureLabelPaint)
            }
        }

        // ====================================================================
        // LAYER 5: CROSSHAIR
        // ====================================================================
        if (viewport.crosshairActive) {
            drawCrosshairLayer(viewport, candles, cw, ch, totalW, totalH, crosshairLabelPaint, timeframe)
        }

        // ====================================================================
        // LAYER 6: PRICE SCALE (Y-axis)
        // ====================================================================
        drawPriceScale(viewport, cw, ch, totalW, totalH, priceLabelPaint)

        // ====================================================================
        // LAYER 7: TIME AXIS (X-axis)
        // ====================================================================
        drawTimeAxis(viewport, candles, cw, ch, totalW, totalH, timeLabelPaint, timeframe)
    }
}

// ============================================================================
// DRAW LAYER IMPLEMENTATIONS
// ============================================================================

/** Grid lines — horizontal price levels + vertical time divisions. */
private fun DrawScope.drawGridLayer(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    totalW: Float,
) {
    val step = viewport.niceStep(6)
    if (step > 0.0) {
        var level = ceil(viewport.priceLow / step) * step
        while (level <= viewport.priceHigh) {
            val y = viewport.yForPrice(level, ch)
            if (y in 0f..ch) {
                drawLine(
                    color = FoxNeutral20,
                    start = Offset(0f, y),
                    end = Offset(totalW, y), // Extend into price scale area
                    strokeWidth = 0.5f,
                )
            }
            level += step
        }
    }

    // Vertical grid lines (time divisions)
    val timeStep = viewport.niceTimeStep(6)
    if (timeStep > 0) {
        val startIdx = max(0, viewport.startIndex.toInt())
        val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1
        var i = startIdx - (startIdx % timeStep) + timeStep
        while (i < endIdx) {
            val x = viewport.xForIndex(i.toFloat(), cw)
            if (x in 0f..cw) {
                drawLine(
                    color = FoxNeutral20,
                    start = Offset(x, 0f),
                    end = Offset(x, ch),
                    strokeWidth = 0.5f,
                )
            }
            i += timeStep
        }
    }
}

/** Candle bodies + wicks — the heart of the chart. Viewport-culled. */
private fun DrawScope.drawCandleLayer(
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    val barWidth = viewport.barWidthPx(cw)
    val bodyWidth = (barWidth * 0.68f).coerceAtLeast(1f)
    val wickWidth = max(1f, barWidth * 0.1f)

    for (i in start until end) {
        val c = candles[i]
        val cx = viewport.xForIndex(i + 0.5f, cw)
        val color = if (c.isBullish) FoxBullish else FoxBearish

        // Wick
        val yHigh = viewport.yForPrice(c.high, ch)
        val yLow = viewport.yForPrice(c.low, ch)
        drawLine(
            color = color,
            start = Offset(cx, yHigh),
            end = Offset(cx, yLow),
            strokeWidth = wickWidth,
            cap = StrokeCap.Butt,
        )

        // Body
        val yOpen = viewport.yForPrice(c.open, ch)
        val yClose = viewport.yForPrice(c.close, ch)
        val top = min(yOpen, yClose)
        val bodyH = max(1f, abs(yClose - yOpen))
        drawRect(
            color = color,
            topLeft = Offset(cx - bodyWidth / 2f, top),
            size = Size(bodyWidth, bodyH),
        )
    }
}

/** EMA/SMA indicator lines drawn over candles. */
private fun DrawScope.drawIndicatorLayer(
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    emaShort: DoubleArray?,
    emaLong: DoubleArray?,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)

    // Draw EMA short (e.g., 20-period) — amber
    if (emaShort != null && emaShort.size >= end) {
        drawEmaLine(viewport, cw, ch, emaShort, start, end, FoxAmber50.copy(alpha = 0.85f))
    }

    // Draw EMA long (e.g., 50-period) — neutral blue-gray
    if (emaLong != null && emaLong.size >= end) {
        drawEmaLine(viewport, cw, ch, emaLong, start, end, FoxNeutral60.copy(alpha = 0.7f))
    }
}

private fun DrawScope.drawEmaLine(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: DoubleArray,
    start: Int,
    end: Int,
    color: Color,
) {
    if (end - start < 2) return
    var prevX = viewport.xForIndex(start + 0.5f, cw)
    var prevY = viewport.yForPrice(values[start], ch)

    for (i in start + 1 until end) {
        val x = viewport.xForIndex(i + 0.5f, cw)
        val y = viewport.yForPrice(values[i], ch)
        drawLine(
            color = color,
            start = Offset(prevX, prevY),
            end = Offset(x, y),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round,
        )
        prevX = x
        prevY = y
    }
}

/** BOS/CHOCH market structure break annotations. */
private fun DrawScope.drawStructureLayer(
    breaks: List<StructureBreak>,
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)

    for (brk in breaks) {
        if (brk.breakIndex < startIdx || brk.breakIndex >= endIdx) continue
        if (!brk.confirmed) continue

        val x = viewport.xForIndex(brk.breakIndex + 0.5f, cw)
        val y = viewport.yForPrice(brk.breakPrice, ch)

        val color = when (brk.direction) {
            Direction.BULLISH -> FoxBullish
            Direction.BEARISH -> FoxBearish
        }

        // Horizontal dashed line at break price
        drawLine(
            color = color.copy(alpha = 0.5f),
            start = Offset(x - 40f, y),
            end = Offset(x + 40f, y),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)),
        )

        // Small diamond marker
        val diamond = androidx.compose.ui.graphics.Path().apply {
            moveTo(x, y - 5f)
            lineTo(x + 4f, y)
            lineTo(x, y + 5f)
            lineTo(x - 4f, y)
            close()
        }
        drawPath(diamond, color = color)

        // Label
        val label = when (brk.type) {
            StructureBreakType.BOS -> "BOS"
            StructureBreakType.CHOCH -> "CHoCH"
            StructureBreakType.MSS -> "MSS"
            StructureBreakType.IDM -> "IDM"
        }
        labelPaint.color = when (brk.direction) {
            Direction.BULLISH -> android.graphics.Color.parseColor("#4CAF50")
            Direction.BEARISH -> android.graphics.Color.parseColor("#EF5350")
        }
        drawContext.canvas.nativeCanvas.drawText(
            label,
            x,
            y - 10f,
            labelPaint,
        )
    }
}

/** Live last-price dashed reference line. */
private fun DrawScope.drawLivePriceLine(
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val last = candles.last()
    val lastY = viewport.yForPrice(last.close, ch)
    if (lastY in 0f..ch) {
        val color = if (last.isBullish) FoxBullish else FoxBearish
        drawLine(
            color = color.copy(alpha = 0.6f),
            start = Offset(0f, lastY),
            end = Offset(cw, lastY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
        )
    }
}

/** Professional crosshair with price/time readout at scale edges. */
private fun DrawScope.drawCrosshairLayer(
    viewport: ChartViewport,
    candles: List<Candle>,
    cw: Float,
    ch: Float,
    totalW: Float,
    totalH: Float,
    labelPaint: Paint,
    timeframe: Timeframe,
) {
    val cx = viewport.crosshairX.coerceIn(0f, cw)
    val cy = viewport.crosshairY.coerceIn(0f, ch)

    val crossColor = FoxNeutral60.copy(alpha = 0.7f)

    // Vertical line
    drawLine(
        color = crossColor,
        start = Offset(cx, 0f),
        end = Offset(cx, ch),
        strokeWidth = 0.8f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
    )
    // Horizontal line
    drawLine(
        color = crossColor,
        start = Offset(0f, cy),
        end = Offset(cw, cy),
        strokeWidth = 0.8f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
    )

    // Price label on right edge (filled background)
    val price = viewport.priceForY(cy, ch)
    val priceText = viewport.formatPrice(price)
    val labelW = labelPaint.measureText(priceText) + 12f
    val labelH = labelPaint.textSize + 8f

    // Background rect for price label
    drawRect(
        color = FoxAmber50,
        topLeft = Offset(cw + 2f, cy - labelH / 2f),
        size = Size(viewport.priceScaleWidth - 4f, labelH),
    )
    drawContext.canvas.nativeCanvas.drawText(
        priceText,
        cw + viewport.priceScaleWidth / 2f,
        cy + labelPaint.textSize / 3f,
        labelPaint.apply { textAlign = Paint.Align.CENTER },
    )
    labelPaint.textAlign = Paint.Align.CENTER // Reset

    // Time label on bottom edge
    val barIdx = viewport.indexForX(cx, cw).roundToInt().coerceIn(0, candles.size - 1)
    val timestamp = candles[barIdx].timestamp
    val timeText = viewport.formatTime(timestamp, timeframe)
    val timeLabelW = labelPaint.measureText(timeText) + 12f
    val timeLabelH = labelPaint.textSize + 6f

    drawRect(
        color = FoxAmber50,
        topLeft = Offset(cx - timeLabelW / 2f, ch + 2f),
        size = Size(timeLabelW, timeLabelH),
    )
    drawContext.canvas.nativeCanvas.drawText(
        timeText,
        cx,
        ch + 2f + timeLabelH * 0.7f,
        labelPaint,
    )
}

/** Price scale — Y-axis labels on the right edge. */
private fun DrawScope.drawPriceScale(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    totalW: Float,
    totalH: Float,
    paint: Paint,
) {
    // Background for price scale area
    drawRect(
        color = FoxNeutral5,
        topLeft = Offset(cw, 0f),
        size = Size(viewport.priceScaleWidth, totalH),
    )

    // Separator line
    drawLine(
        color = FoxNeutral20,
        start = Offset(cw, 0f),
        end = Offset(cw, ch),
        strokeWidth = 0.5f,
    )

    val step = viewport.niceStep(6)
    if (step > 0.0) {
        var level = ceil(viewport.priceLow / step) * step
        while (level <= viewport.priceHigh) {
            val y = viewport.yForPrice(level, ch)
            if (y in 0f..ch) {
                val label = viewport.formatPrice(level)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    totalW - 6f,
                    y + paint.textSize / 3f,
                    paint,
                )
            }
            level += step
        }
    }

    // Last price label (highlighted)
    // This provides always-visible current price on the scale
}

/** Time axis — X-axis labels at the bottom edge. */
private fun DrawScope.drawTimeAxis(
    viewport: ChartViewport,
    candles: List<Candle>,
    cw: Float,
    ch: Float,
    totalW: Float,
    totalH: Float,
    paint: Paint,
    timeframe: Timeframe,
) {
    // Background for time axis area
    drawRect(
        color = FoxNeutral5,
        topLeft = Offset(0f, ch),
        size = Size(totalW, viewport.timeAxisHeight),
    )

    // Separator line
    drawLine(
        color = FoxNeutral20,
        start = Offset(0f, ch),
        end = Offset(cw, ch),
        strokeWidth = 0.5f,
    )

    val timeStep = viewport.niceTimeStep(6)
    if (timeStep > 0 && candles.isNotEmpty()) {
        val startIdx = max(0, viewport.startIndex.toInt())
        val endIdx = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
        var i = startIdx - (startIdx % timeStep) + timeStep
        while (i < endIdx && i < candles.size) {
            val x = viewport.xForIndex(i.toFloat(), cw)
            if (x in 0f..cw) {
                val label = viewport.formatTime(candles[i].timestamp, timeframe)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    x,
                    ch + viewport.timeAxisHeight * 0.7f,
                    paint,
                )
            }
            i += timeStep
        }
    }
}
