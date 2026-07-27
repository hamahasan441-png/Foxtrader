package com.foxtrader.app.feature.chart.presentation.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.performance.QualitySettings
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral5
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private val IchimokuTenkanColor = Color(0xFFFFC107)
private val IchimokuKijunColor = Color(0xFF42A5F5)
private val IchimokuChikouColor = Color(0xFFAB47BC)
private val IchimokuBullishCloudColor = Color(0x2232CD32)
private val IchimokuBearishCloudColor = Color(0x22FF5252)
private const val IchimokuPrimaryStroke = 1.2f
private const val IchimokuChikouStroke = 0.8f

// Pre-resolved ARGB ints for native Paint colouring (parsing a colour string
// inside the draw pass would allocate and cost a frame).
private val BullishTextArgb = android.graphics.Color.parseColor("#4CAF50")
private val BearishTextArgb = android.graphics.Color.parseColor("#EF5350")

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
 * - Momentum fling with frame-rate-independent friction (§4.9)
 * - Adaptive quality: layers are skipped before the frame budget is blown (§4.14)
 *
 * Gestures:
 * - Drag: pan; lift-off with velocity starts a fling
 * - Pinch: zoom anchored to the gesture centroid
 * - Long-press + drag: crosshair with OHLC readout, tracks the finger
 * - Double-tap: reset the camera to the most recent bars
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    modifier: Modifier = Modifier,
    structureBreaks: List<StructureBreak> = emptyList(),
    timeframe: Timeframe = Timeframe.M15,
    performanceMonitor: ChartPerformanceMonitor? = null,
    emaShort: DoubleArray? = null,
    emaLong: DoubleArray? = null,
    bollingerUpper: DoubleArray? = null,
    bollingerMiddle: DoubleArray? = null,
    bollingerLower: DoubleArray? = null,
    superTrendValues: DoubleArray? = null,
    superTrendDir: IntArray? = null,
    parabolicSar: DoubleArray? = null,
    vwap: DoubleArray? = null,
    ichimokuTenkan: DoubleArray? = null,
    ichimokuKijun: DoubleArray? = null,
    ichimokuSenkouA: DoubleArray? = null,
    ichimokuSenkouB: DoubleArray? = null,
    ichimokuChikou: DoubleArray? = null,
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

    // Bumped when a fling starts, which is what launches the animation loop.
    var flingTick by remember { mutableIntStateOf(0) }

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
    // OHLC readout shown next to the crosshair (left-aligned monospace).
    val ohlcLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#C4C9D4")
            textSize = with(density) { 9.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
    }

    // Whether the camera was pinned to the newest bar before this data update.
    // Used to keep the chart "following" live bars without fighting the user.
    val followLiveEdge = remember { booleanArrayOf(true) }

    /**
     * Re-run auto-scaling for the current viewport window.
     *
     * `PERF` Hoisted into a single local lambda so the (long) argument list is
     * declared once instead of being duplicated at every camera mutation site.
     */
    val rescaleCurrent: () -> Unit = {
        autoScaleToVisibleContent(
            viewport = viewport,
            candles = candles,
            emaShort = emaShort,
            emaLong = emaLong,
            bollingerUpper = bollingerUpper,
            bollingerMiddle = bollingerMiddle,
            bollingerLower = bollingerLower,
            superTrendValues = superTrendValues,
            parabolicSar = parabolicSar,
            vwap = vwap,
            ichimokuTenkan = ichimokuTenkan,
            ichimokuKijun = ichimokuKijun,
            ichimokuSenkouA = ichimokuSenkouA,
            ichimokuSenkouB = ichimokuSenkouB,
            ichimokuChikou = ichimokuChikou,
            orderBlocks = orderBlocks,
            fairValueGaps = fairValueGaps,
            liquidityPools = liquidityPools,
            sessions = sessions,
            volumeProfile = volumeProfile,
        )
    }

    // `WARNING` The gesture handlers below are keyed on `candles.size`, so they
    // capture their lambdas until the bar count changes. Indicator arrays can be
    // swapped without the count changing (e.g. toggling Bollinger), which would
    // leave a gesture holding a stale auto-scale closure and scaling the chart
    // to overlays that are no longer drawn. rememberUpdatedState keeps a single
    // stable reference that always points at the current frame's data.
    // Held as an explicit State (not a `by` delegate) so it is unambiguous that
    // each call site reads the *latest* lambda rather than invoking a captured one.
    val rescaleState = rememberUpdatedState(rescaleCurrent)
    val rescale: () -> Unit = { rescaleState.value() }

    // Initialise the viewport when data arrives or grows.
    remember(candles.size) {
        if (viewport.visibleBars <= 0f || viewport.startIndex == 0f) {
            viewport.resetToLatest(candles.size)
        } else if (followLiveEdge[0]) {
            // `RULE` (DEVELOPMENT.md §4.8) When new bars arrive while the user
            // is parked at the live edge, follow them. If the user has scrolled
            // back into history, their position is left untouched.
            viewport.startIndex = max(0f, candles.size - viewport.visibleBars)
        }
        viewport.clamp(candles.size)
        rescale()
        candles.size
    }

    // ------------------------------------------------------------------------
    // FLING ANIMATION LOOP (DEVELOPMENT.md §4.9)
    // ------------------------------------------------------------------------
    // `PERF` The loop is *started by a fling*, not left running permanently:
    // `flingTick` is bumped only on a qualifying lift-off, and the effect exits
    // as soon as the fling settles. An always-on `withFrameNanos` loop would
    // wake the chart on every vsync even while the user is doing nothing,
    // burning battery for no reason.
    LaunchedEffect(flingTick, candles.size) {
        if (flingTick == 0 || !viewport.isFling) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (viewport.isFling) {
            withFrameNanos { now ->
                val dt = (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                // advanceFling returns false on the settling frame; either way
                // the camera moved, so clamp/rescale/redraw exactly once.
                viewport.advanceFling(dt, candles.size)
                viewport.clamp(candles.size)
                rescale()
                followLiveEdge[0] = viewport.isAtRightEdge(candles.size)
                invalidateTick++
            }
        }
    }

    // Stop profiling when the chart leaves composition.
    DisposableEffect(performanceMonitor) {
        onDispose { performanceMonitor?.stop() }
    }

    Canvas(
        modifier = modifier
            .background(FoxNeutral5)
            // --- FLING VELOCITY TRACKING ---
            // Runs before the transform handler in the same pass: it observes
            // pointer positions to compute lift-off velocity without consuming
            // any event, so pan/zoom behaviour is completely unaffected.
            .pointerInput(candles.size) {
                val tracker = VelocityTracker()
                awaitEachGesture {
                    // `WARNING` Suspend for the touch FIRST. Cancelling the
                    // fling before awaitFirstDown would kill every fling the
                    // instant it started, because this loop re-enters as soon
                    // as the previous gesture ends.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    viewport.stopFling()
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    while (true) {
                        val changes = awaitPointerEvent().changes
                        val pressed = changes.count { it.pressed }
                        if (pressed == 0) break
                        // Only single-pointer drags fling; a pinch must settle
                        // exactly where the fingers left it.
                        if (pressed == 1) {
                            changes.firstOrNull { it.pressed }?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                            }
                        } else {
                            tracker.resetTracking()
                        }
                    }

                    // Crosshair mode consumes the drag; never fling out of it.
                    if (!viewport.crosshairActive) {
                        val velocityX = tracker.calculateVelocity().x
                        val chartAreaWidth = viewport.chartWidth(size.width.toFloat())
                        if (viewport.startFling(velocityX, chartAreaWidth)) {
                            flingTick++
                        }
                    }
                }
            }
            // --- PAN (single finger) + ZOOM (pinch) in ONE handler ---
            // A single detectTransformGestures prevents the chart "drift" bug:
            // previously a transform handler AND a drag handler both applied pan
            // to the same one-finger drag, doubling/fighting the movement.
            // detectTransformGestures natively reports pan for a single pointer.
            .pointerInput(candles.size) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val cw = viewport.chartWidth(size.width.toFloat())

                    // While the crosshair is up, a drag moves the crosshair
                    // instead of the camera — this is how desktop terminals
                    // behave and it makes precise bar inspection possible.
                    if (viewport.crosshairActive) {
                        viewport.crosshairX = (viewport.crosshairX + pan.x)
                            .coerceIn(0f, cw)
                        viewport.crosshairY = (viewport.crosshairY + pan.y)
                            .coerceIn(0f, viewport.chartHeight(size.height.toFloat()))
                        viewport.crosshairTotalWidth = size.width.toFloat()
                        invalidateTick++
                        return@detectTransformGestures
                    }

                    viewport.panByPixels(pan.x, cw)
                    viewport.zoomBy(zoom, centroid.x, cw, candles.size)

                    viewport.clamp(candles.size)
                    rescale()
                    followLiveEdge[0] = viewport.isAtRightEdge(candles.size)
                    invalidateTick++
                }
            }
            // --- TAP GESTURES: crosshair + double-tap reset ---
            .pointerInput(candles.size) {
                detectTapGestures(
                    onLongPress = { offset ->
                        viewport.stopFling()
                        viewport.crosshairActive = true
                        viewport.crosshairX = offset.x.coerceIn(0f, viewport.chartWidth(size.width.toFloat()))
                        viewport.crosshairY = offset.y.coerceIn(0f, viewport.chartHeight(size.height.toFloat()))
                        viewport.crosshairTotalWidth = size.width.toFloat()
                        invalidateTick++
                    },
                    onDoubleTap = {
                        // "Go to now" — snap back to the most recent bars.
                        viewport.crosshairActive = false
                        viewport.resetToLatest(candles.size)
                        viewport.clamp(candles.size)
                        rescale()
                        followLiveEdge[0] = true
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

        // Adaptive quality (DEVELOPMENT.md §4.14): the settings were computed at
        // the end of the previous frame, so what this frame draws is decided
        // before a single pixel is emitted. Without a monitor attached (previews,
        // screenshot tests) the chart always renders at full detail.
        performanceMonitor?.beginFrame()
        val quality = performanceMonitor?.quality ?: QualitySettings.FULL

        val totalW = size.width
        val totalH = size.height
        val cw = viewport.chartWidth(totalW)
        val ch = viewport.chartHeight(totalH)

        // ====================================================================
        // LAYER 0: GRID LINES
        // ====================================================================
        if (quality.gridLines) {
            drawGridLayer(viewport, cw, ch, totalW)
        }

        // ====================================================================
        // LAYER 0.5: SESSION BACKGROUNDS (behind candles)
        // ====================================================================
        if (sessions.isNotEmpty() && quality.sessions) {
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
        if (volumeProfile != null && quality.volumeProfile) {
            clipRect(right = cw, bottom = ch) {
                drawVolumeProfile(volumeProfile, viewport, cw, ch)
            }
        }

        // ====================================================================
        // LAYER 2: INDICATOR OVERLAYS (EMA / Bollinger / SuperTrend / PSAR / VWAP)
        // ====================================================================
        if (quality.indicators) clipRect(right = cw, bottom = ch) {
            if (emaShort != null || emaLong != null) {
                drawIndicatorLayer(candles, viewport, cw, ch, emaShort, emaLong)
            }
            if (bollingerUpper != null && bollingerMiddle != null && bollingerLower != null) {
                drawBollinger(viewport, cw, ch, bollingerUpper, bollingerMiddle, bollingerLower)
            }
            if (vwap != null) {
                drawLineSeries(viewport, cw, ch, vwap, Color(0xFF9C27B0), 1.5f)
            }
            if (superTrendValues != null && superTrendDir != null) {
                drawSuperTrend(viewport, cw, ch, superTrendValues, superTrendDir)
            }
            if (parabolicSar != null) {
                drawParabolicSar(viewport, cw, ch, parabolicSar)
            }
            if (ichimokuTenkan != null && ichimokuKijun != null && ichimokuSenkouA != null && ichimokuSenkouB != null && ichimokuChikou != null) {
                drawIchimoku(viewport, cw, ch, ichimokuTenkan, ichimokuKijun, ichimokuSenkouA, ichimokuSenkouB, ichimokuChikou)
            }
        }

        // ====================================================================
        // LAYER 3: MARKET STRUCTURE ANNOTATIONS
        // ====================================================================
        if (structureBreaks.isNotEmpty() && quality.structureAnnotations) {
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
            drawCrosshairLayer(
                viewport = viewport,
                candles = candles,
                cw = cw,
                ch = ch,
                labelPaint = crosshairLabelPaint,
                ohlcPaint = ohlcLabelPaint,
                timeframe = timeframe,
            )
        }

        // ====================================================================
        // LAYER 6: PRICE SCALE (Y-axis) + live last-price tag
        // ====================================================================
        drawPriceScale(
            viewport = viewport,
            candles = candles,
            cw = cw,
            ch = ch,
            totalW = totalW,
            totalH = totalH,
            paint = priceLabelPaint,
            tagPaint = crosshairLabelPaint,
        )

        // ====================================================================
        // LAYER 7: TIME AXIS (X-axis)
        // ====================================================================
        drawTimeAxis(viewport, candles, cw, ch, totalW, totalH, timeLabelPaint, timeframe)

        // Close the frame: records the duration, recomputes adaptive quality for
        // the next frame, and publishes a throttled snapshot to the debug HUD.
        performanceMonitor?.endFrame()
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

/** Generic single-line series renderer (viewport-culled). Used for VWAP etc. */
private fun DrawScope.drawLineSeries(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: DoubleArray,
    color: Color,
    strokeWidth: Float,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(values.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (end - start < 2) return
    var prevX = viewport.xForIndex(start + 0.5f, cw)
    var prevY = viewport.yForPrice(values[start], ch)
    for (i in start + 1 until end) {
        val x = viewport.xForIndex(i + 0.5f, cw)
        val y = viewport.yForPrice(values[i], ch)
        drawLine(color, Offset(prevX, prevY), Offset(x, y), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        prevX = x; prevY = y
    }
}

/** Bollinger Bands: upper/lower channel + middle line. */
private fun DrawScope.drawBollinger(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    upper: DoubleArray,
    middle: DoubleArray,
    lower: DoubleArray,
) {
    val bandColor = Color(0x663B8DF0)
    val midColor = Color(0xAA3B8DF0)
    drawLineSeries(viewport, cw, ch, upper, bandColor, 1.2f)
    drawLineSeries(viewport, cw, ch, lower, bandColor, 1.2f)
    drawLineSeries(viewport, cw, ch, middle, midColor, 1f)
}

/** SuperTrend line: green segment when bullish, red when bearish. */
private fun DrawScope.drawSuperTrend(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: DoubleArray,
    dir: IntArray,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(minOf(values.size, dir.size), (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (end - start < 2) return
    for (i in start + 1 until end) {
        val x1 = viewport.xForIndex((i - 1) + 0.5f, cw)
        val y1 = viewport.yForPrice(values[i - 1], ch)
        val x2 = viewport.xForIndex(i + 0.5f, cw)
        val y2 = viewport.yForPrice(values[i], ch)
        val color = if (dir[i] == 1) FoxBullish else FoxBearish
        drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f, cap = StrokeCap.Round)
    }
}

/** Parabolic SAR: dots above/below price. */
private fun DrawScope.drawParabolicSar(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    sar: DoubleArray,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(sar.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    val dotColor = Color(0xCCD4A84E)
    for (i in start until end) {
        val x = viewport.xForIndex(i + 0.5f, cw)
        val y = viewport.yForPrice(sar[i], ch)
        if (y in 0f..ch) drawCircle(dotColor, radius = 2f, center = Offset(x, y))
    }
}

private fun DrawScope.drawIchimoku(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    tenkan: DoubleArray,
    kijun: DoubleArray,
    senkouA: DoubleArray,
    senkouB: DoubleArray,
    chikou: DoubleArray,
) {
    drawLineSeries(viewport, cw, ch, tenkan, IchimokuTenkanColor, IchimokuPrimaryStroke)
    drawLineSeries(viewport, cw, ch, kijun, IchimokuKijunColor, IchimokuPrimaryStroke)
    drawLineSeries(viewport, cw, ch, chikou, IchimokuChikouColor, IchimokuChikouStroke)

    val start = max(0, viewport.startIndex.toInt())
    val end = min(minOf(senkouA.size, senkouB.size), (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    for (i in start until end) {
        val top = max(senkouA[i], senkouB[i])
        val bottom = min(senkouA[i], senkouB[i])
        val x = viewport.xForIndex(i.toFloat(), cw)
        val nextX = viewport.xForIndex((i + 1).toFloat(), cw)
        val yTop = viewport.yForPrice(top, ch)
        val yBottom = viewport.yForPrice(bottom, ch)
        val cloudColor = if (senkouA[i] >= senkouB[i]) IchimokuBullishCloudColor else IchimokuBearishCloudColor
        drawRect(
            color = cloudColor,
            topLeft = Offset(x, min(yTop, yBottom)),
            size = Size((nextX - x).coerceAtLeast(1f), abs(yBottom - yTop).coerceAtLeast(1f)),
        )
    }
    drawLineSeries(viewport, cw, ch, senkouA, Color(0xFF66BB6A), 1f)
    drawLineSeries(viewport, cw, ch, senkouB, Color(0xFFEF5350), 1f)
}

private fun autoScaleToVisibleContent(
    viewport: ChartViewport,
    candles: List<Candle>,
    emaShort: DoubleArray?,
    emaLong: DoubleArray?,
    bollingerUpper: DoubleArray?,
    bollingerMiddle: DoubleArray?,
    bollingerLower: DoubleArray?,
    superTrendValues: DoubleArray?,
    parabolicSar: DoubleArray?,
    vwap: DoubleArray?,
    ichimokuTenkan: DoubleArray?,
    ichimokuKijun: DoubleArray?,
    ichimokuSenkouA: DoubleArray?,
    ichimokuSenkouB: DoubleArray?,
    ichimokuChikou: DoubleArray?,
    orderBlocks: List<com.foxtrader.app.domain.model.OrderBlock>,
    fairValueGaps: List<com.foxtrader.app.domain.model.FairValueGap>,
    liquidityPools: List<com.foxtrader.app.domain.model.LiquidityPool>,
    sessions: List<com.foxtrader.app.domain.model.SessionRange>,
    volumeProfile: com.foxtrader.app.domain.model.VolumeProfile?,
    pad: Double = 0.08,
) {
    if (candles.isEmpty()) return
    val start = max(0, viewport.startIndex.toInt())
    val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (start >= end) return

    var hi = Double.NEGATIVE_INFINITY
    var lo = Double.POSITIVE_INFINITY

    fun include(price: Double?) {
        if (price == null || price.isNaN() || price.isInfinite()) return
        if (price > hi) hi = price
        if (price < lo) lo = price
    }

    fun includeSeries(values: DoubleArray?) {
        if (values == null) return
        val seriesEnd = min(values.size, end)
        for (i in start until seriesEnd) include(values[i])
    }

    for (i in start until end) {
        include(candles[i].high)
        include(candles[i].low)
    }

    includeSeries(emaShort)
    includeSeries(emaLong)
    includeSeries(bollingerUpper)
    includeSeries(bollingerMiddle)
    includeSeries(bollingerLower)
    includeSeries(superTrendValues)
    includeSeries(parabolicSar)
    includeSeries(vwap)
    includeSeries(ichimokuTenkan)
    includeSeries(ichimokuKijun)
    includeSeries(ichimokuSenkouA)
    includeSeries(ichimokuSenkouB)
    includeSeries(ichimokuChikou)

    orderBlocks.filter { it.endIndex >= start && it.startIndex < end }.forEach {
        include(it.highPrice)
        include(it.lowPrice)
    }
    fairValueGaps.filter { it.index in start until end }.forEach {
        include(it.highPrice)
        include(it.lowPrice)
    }
    liquidityPools.filter { it.endIndex >= start && it.startIndex < end }.forEach {
        include(it.price)
    }
    sessions.filter { it.endIndex >= start && it.startIndex < end }.forEach {
        include(it.highPrice)
        include(it.lowPrice)
    }
    volumeProfile?.levels?.forEach { include(it.priceLevel) }

    if (hi == Double.NEGATIVE_INFINITY || lo == Double.POSITIVE_INFINITY) {
        viewport.autoScale(candles)
        return
    }

    val range = (hi - lo).coerceAtLeast(1e-9)
    val padding = range * pad
    viewport.priceHigh = hi + padding
    viewport.priceLow = lo - padding
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

/**
 * Professional crosshair with price/time readouts and a snapped OHLC panel.
 *
 * The vertical line snaps to the centre of the bar under the finger (§4.7) so
 * the readout always describes exactly one candle — a floating line between two
 * bars would make the OHLC values ambiguous.
 */
private fun DrawScope.drawCrosshairLayer(
    viewport: ChartViewport,
    candles: List<Candle>,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
    ohlcPaint: Paint,
    timeframe: Timeframe,
) {
    val barIdx = viewport.snappedCrosshairIndex(candles.size, cw)
    if (barIdx < 0) return

    // Snap the vertical line to the bar centre; the horizontal line tracks the
    // finger freely so the user can read any price level.
    val snappedX = viewport.xForIndex(barIdx + 0.5f, cw).coerceIn(0f, cw)
    val cy = viewport.crosshairY.coerceIn(0f, ch)

    val crossColor = FoxNeutral60.copy(alpha = 0.7f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))

    drawLine(
        color = crossColor,
        start = Offset(snappedX, 0f),
        end = Offset(snappedX, ch),
        strokeWidth = 0.8f,
        pathEffect = dash,
    )
    drawLine(
        color = crossColor,
        start = Offset(0f, cy),
        end = Offset(cw, cy),
        strokeWidth = 0.8f,
        pathEffect = dash,
    )

    // --- Price label on the right scale ---
    val price = viewport.priceForY(cy, ch)
    val priceText = viewport.formatPrice(price)
    val labelH = labelPaint.textSize + 8f
    drawRect(
        color = FoxAmber50,
        topLeft = Offset(cw + 2f, cy - labelH / 2f),
        size = Size(viewport.priceScaleWidth - 4f, labelH),
    )
    labelPaint.textAlign = Paint.Align.CENTER
    drawContext.canvas.nativeCanvas.drawText(
        priceText,
        cw + viewport.priceScaleWidth / 2f,
        cy + labelPaint.textSize / 3f,
        labelPaint,
    )

    // --- Time label on the bottom axis ---
    val bar = candles[barIdx]
    val timeText = viewport.formatTime(bar.timestamp, timeframe)
    val timeLabelW = labelPaint.measureText(timeText) + 12f
    val timeLabelH = labelPaint.textSize + 6f
    // Keep the label fully on screen at the chart edges.
    val timeLabelX = snappedX.coerceIn(timeLabelW / 2f, (cw - timeLabelW / 2f).coerceAtLeast(timeLabelW / 2f))
    drawRect(
        color = FoxAmber50,
        topLeft = Offset(timeLabelX - timeLabelW / 2f, ch + 2f),
        size = Size(timeLabelW, timeLabelH),
    )
    drawContext.canvas.nativeCanvas.drawText(
        timeText,
        timeLabelX,
        ch + 2f + timeLabelH * 0.7f,
        labelPaint,
    )

    // --- OHLC readout panel ---
    drawOhlcReadout(viewport, bar, snappedX, cw, ohlcPaint)
}

/**
 * Compact O/H/L/C + change panel for the bar under the crosshair.
 *
 * Anchored to the top of the chart and flipped to the opposite side when the
 * crosshair would otherwise cover it.
 */
private fun DrawScope.drawOhlcReadout(
    viewport: ChartViewport,
    bar: Candle,
    crosshairX: Float,
    cw: Float,
    paint: Paint,
) {
    val changeAbs = bar.close - bar.open
    val changePct = if (bar.open != 0.0) (changeAbs / bar.open) * 100.0 else 0.0
    val text = "O ${viewport.formatPrice(bar.open)}  " +
        "H ${viewport.formatPrice(bar.high)}  " +
        "L ${viewport.formatPrice(bar.low)}  " +
        "C ${viewport.formatPrice(bar.close)}  " +
        String.format(Locale.US, "%+.2f%%", changePct)

    val padding = 6f
    val textW = paint.measureText(text)
    val boxW = textW + padding * 2f
    val boxH = paint.textSize + padding * 2f

    // Flip to the left of the crosshair when there is no room on the right.
    val boxX = if (crosshairX + 8f + boxW <= cw) crosshairX + 8f
    else (crosshairX - 8f - boxW).coerceAtLeast(0f)

    drawRect(
        color = FoxNeutral10.copy(alpha = 0.92f),
        topLeft = Offset(boxX, 4f),
        size = Size(boxW.coerceAtMost(cw), boxH),
    )
    paint.color = if (changeAbs >= 0) BullishTextArgb else BearishTextArgb
    paint.textAlign = Paint.Align.LEFT
    drawContext.canvas.nativeCanvas.drawText(
        text,
        boxX + padding,
        4f + padding + paint.textSize * 0.8f,
        paint,
    )
}

/**
 * Price scale — Y-axis labels on the right edge, plus the always-visible
 * live last-price tag.
 */
private fun DrawScope.drawPriceScale(
    viewport: ChartViewport,
    candles: List<Candle>,
    cw: Float,
    ch: Float,
    totalW: Float,
    totalH: Float,
    paint: Paint,
    tagPaint: Paint,
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

    // --- Live last-price tag ---
    // A filled, always-visible marker at the current close. This is what makes
    // the scale readable at a glance: the trader never has to hunt for "where
    // is price right now" among the round grid levels.
    val last = candles.lastOrNull() ?: return
    val lastY = viewport.yForPrice(last.close, ch)
    if (lastY !in 0f..ch) return

    val tagColor = if (last.isBullish) FoxBullish else FoxBearish
    val tagH = tagPaint.textSize + 7f
    drawRect(
        color = tagColor,
        topLeft = Offset(cw + 1f, (lastY - tagH / 2f).coerceIn(0f, ch - tagH)),
        size = Size(viewport.priceScaleWidth - 2f, tagH),
    )
    tagPaint.textAlign = Paint.Align.CENTER
    drawContext.canvas.nativeCanvas.drawText(
        viewport.formatPrice(last.close),
        cw + viewport.priceScaleWidth / 2f,
        (lastY - tagH / 2f).coerceIn(0f, ch - tagH) + tagH * 0.72f,
        tagPaint,
    )
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
