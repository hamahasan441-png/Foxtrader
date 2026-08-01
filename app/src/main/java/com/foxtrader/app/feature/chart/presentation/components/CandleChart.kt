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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.analysis.FibonacciEngine
import com.foxtrader.app.domain.usecase.analysis.MarketProfile
import com.foxtrader.app.domain.usecase.analysis.SupportResistanceDetector
import com.foxtrader.app.domain.usecase.chart.ChartViewportState
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.feature.chart.presentation.ImmutableIntSeries
import com.foxtrader.app.domain.usecase.performance.QualitySettings
import com.foxtrader.app.feature.chart.presentation.components.layers.autoScaleToVisibleContent
import com.foxtrader.app.feature.chart.presentation.components.layers.drawAutoFibonacciLevels
import com.foxtrader.app.feature.chart.presentation.components.layers.drawBollinger
import com.foxtrader.app.feature.chart.presentation.components.layers.drawCandleLayer
import com.foxtrader.app.feature.chart.presentation.components.layers.drawCrosshairLayer
import com.foxtrader.app.feature.chart.presentation.components.layers.drawSyncedCrosshairLayer
import com.foxtrader.app.feature.chart.presentation.components.layers.drawGridLayer
import com.foxtrader.app.feature.chart.presentation.components.layers.drawMarketProfile
import com.foxtrader.app.feature.chart.presentation.components.layers.drawIchimoku
import com.foxtrader.app.feature.chart.presentation.components.layers.drawIndicatorLayer
import com.foxtrader.app.feature.chart.presentation.components.layers.drawLineSeries
import com.foxtrader.app.feature.chart.presentation.components.layers.drawLivePriceLine
import com.foxtrader.app.feature.chart.presentation.components.layers.drawParabolicSar
import com.foxtrader.app.feature.chart.presentation.components.layers.drawPriceScale
import com.foxtrader.app.feature.chart.presentation.components.layers.drawStructureLayer
import com.foxtrader.app.feature.chart.presentation.components.layers.drawSupportResistanceZones
import com.foxtrader.app.feature.chart.presentation.components.layers.drawSuperTrend
import com.foxtrader.app.feature.chart.presentation.components.layers.drawTimeAxis
import com.foxtrader.app.ui.theme.FoxNeutral0
import kotlin.math.max

private val AxisLabelArgb = android.graphics.Color.parseColor("#99999F")
private val OhlcLabelArgb = android.graphics.Color.parseColor("#C4C9D4")
private val LoadingHistoryArgb = android.graphics.Color.parseColor("#D4A84E")

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
    seriesKey: String = "",
    initialViewportState: ChartViewportState? = null,
    onViewportStateChange: (ChartViewportState) -> Unit = {},
    performanceMonitor: ChartPerformanceMonitor? = null,
    emaShort: ImmutableDoubleSeries? = null,
    emaLong: ImmutableDoubleSeries? = null,
    bollingerUpper: ImmutableDoubleSeries? = null,
    bollingerMiddle: ImmutableDoubleSeries? = null,
    bollingerLower: ImmutableDoubleSeries? = null,
    superTrendValues: ImmutableDoubleSeries? = null,
    superTrendDir: ImmutableIntSeries? = null,
    parabolicSar: ImmutableDoubleSeries? = null,
    vwap: ImmutableDoubleSeries? = null,
    ichimokuTenkan: ImmutableDoubleSeries? = null,
    ichimokuKijun: ImmutableDoubleSeries? = null,
    ichimokuSenkouA: ImmutableDoubleSeries? = null,
    ichimokuSenkouB: ImmutableDoubleSeries? = null,
    ichimokuChikou: ImmutableDoubleSeries? = null,
    orderBlocks: List<com.foxtrader.app.domain.model.OrderBlock> = emptyList(),
    fairValueGaps: List<com.foxtrader.app.domain.model.FairValueGap> = emptyList(),
    liquidityPools: List<com.foxtrader.app.domain.model.LiquidityPool> = emptyList(),
    tradeProAnalysis: com.foxtrader.app.domain.model.tradepro.TradeProAnalysis? = null,
    sessions: List<com.foxtrader.app.domain.model.SessionRange> = emptyList(),
    drawings: List<com.foxtrader.app.domain.model.ChartDrawing> = emptyList(),
    volumeProfile: com.foxtrader.app.domain.model.VolumeProfile? = null,
    marketProfile: MarketProfile.ProfileResult? = null,
    supportResistanceZones: List<SupportResistanceDetector.SRZone> = emptyList(),
    autoFibLevels: List<FibonacciEngine.FibLevel> = emptyList(),
    autoFibDirection: Direction? = null,
    autoFibSwingHigh: Double? = null,
    autoFibSwingLow: Double? = null,
    canLoadOlder: Boolean = false,
    isLoadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {},
    syncedCrosshairTimestamp: Long? = null,
    onCrosshairTimestampChange: (Long?) -> Unit = {},
) {
    val density = LocalDensity.current
    val context = LocalContext.current

    // Viewport survives recomposition. Layout margins set in density-independent pixels.
    val viewport = remember {
        ChartViewport().apply {
            priceScaleWidth = with(density) { 64.dp.toPx() }
            timeAxisHeight = with(density) { 24.dp.toPx() }
        }
    }
    val publishViewportState: () -> Unit = { onViewportStateChange(viewport.snapshotState()) }

    // Redraw trigger — bumped after every gesture frame.
    var invalidateTick by remember { mutableIntStateOf(0) }

    // Bumped when a fling starts, which is what launches the animation loop.
    var flingTick by remember { mutableIntStateOf(0) }

    // Native Paint objects (reused across frames — zero allocation in draw loop)
    val priceLabelPaint = remember {
        Paint().apply {
            color = AxisLabelArgb
            textSize = with(density) { 10.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }
    val timeLabelPaint = remember {
        Paint().apply {
            color = AxisLabelArgb
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
            color = OhlcLabelArgb
            textSize = with(density) { 9.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
    }

    // Whether the camera was pinned to the newest bar before this data update.
    // Used to keep the chart "following" live bars without fighting the user.
    val followLiveEdge = remember { booleanArrayOf(true) }
    var previousFirstTimestamp by remember { mutableStateOf<Long?>(null) }
    var previousSize by remember { mutableIntStateOf(0) }
    var lastRequestedBefore by remember { mutableStateOf<Long?>(null) }
    var viewportSeriesKey by remember { mutableStateOf<String?>(null) }

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
    remember(candles.size, seriesKey, initialViewportState) {
        if (viewport.visibleBars <= 0f || viewportSeriesKey != seriesKey) {
            if (initialViewportState != null) {
                viewport.restoreState(initialViewportState, candles.size)
            } else {
                viewport.resetToLatest(candles.size)
            }
            viewportSeriesKey = seriesKey
            followLiveEdge[0] = viewport.isAtRightEdge(candles.size)
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

    LaunchedEffect(seriesKey, candles.size) {
        publishViewportState()
    }

    LaunchedEffect(seriesKey) {
        previousFirstTimestamp = candles.firstOrNull()?.timestamp
        previousSize = candles.size
        lastRequestedBefore = null
    }

    LaunchedEffect(candles.firstOrNull()?.timestamp, candles.size) {
        val firstTimestamp = candles.firstOrNull()?.timestamp
        val previousFirst = previousFirstTimestamp
        if (
            previousFirst != null &&
            firstTimestamp != null &&
            firstTimestamp < previousFirst &&
            candles.size > previousSize
        ) {
            viewport.shiftForPrependedBars(candles.size - previousSize)
            viewport.clamp(candles.size)
            rescale()
            publishViewportState()
            invalidateTick++
        }
        previousFirstTimestamp = firstTimestamp
        previousSize = candles.size
        if (firstTimestamp != null && firstTimestamp != lastRequestedBefore) {
            lastRequestedBefore = null
        }
    }

    LaunchedEffect(invalidateTick, candles.firstOrNull()?.timestamp, canLoadOlder, isLoadingOlder) {
        val firstTimestamp = candles.firstOrNull()?.timestamp ?: return@LaunchedEffect
        if (
            canLoadOlder &&
            !isLoadingOlder &&
            viewport.startIndex <= HISTORY_PREFETCH_THRESHOLD_BARS &&
            lastRequestedBefore != firstTimestamp
        ) {
            lastRequestedBefore = firstTimestamp
            onLoadOlder()
        }
    }

    // Keep candles reference fresh for gesture/fling handlers without restarting them.
    val currentCandles = rememberUpdatedState(candles)

    // ------------------------------------------------------------------------
    // FLING ANIMATION LOOP (DEVELOPMENT.md §4.9)
    // ------------------------------------------------------------------------
    // `PERF` The loop is *started by a fling*, not left running permanently:
    // `flingTick` is bumped only on a qualifying lift-off, and the effect exits
    // as soon as the fling settles. An always-on `withFrameNanos` loop would
    // wake the chart on every vsync even while the user is doing nothing,
    // burning battery for no reason.
    LaunchedEffect(flingTick) {
        if (flingTick == 0 || !viewport.isFling) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (viewport.isFling) {
            withFrameNanos { now ->
                val dt = (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                val currentCount = currentCandles.value.size
                if (currentCount == 0) {
                    viewport.stopFling()
                    return@withFrameNanos
                }
                viewport.advanceFling(dt, currentCount)
                viewport.clamp(currentCount)
                rescale()
                followLiveEdge[0] = viewport.isAtRightEdge(currentCount)
                publishViewportState()
                invalidateTick++
            }
        }
    }

    // Stop profiling when the chart leaves composition.
    DisposableEffect(performanceMonitor) {
        onDispose { performanceMonitor?.stop() }
    }

    // Use a stable key for pointerInput to prevent gesture handler restarts
    // during data transitions (timeframe changes, indicator toggles). The
    // seriesKey changes only on meaningful context switches (symbol+timeframe),
    // not on every tick or indicator recompute. This eliminates the crash where
    // mid-gesture handler restart causes NPE/IndexOutOfBounds.
    val stableGestureKey = remember(seriesKey) { seriesKey }

    Canvas(
        modifier = modifier
            .background(FoxNeutral0)
            // --- FLING VELOCITY TRACKING ---
            .pointerInput(stableGestureKey) {
                val tracker = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    viewport.stopFling()
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    while (true) {
                        val changes = awaitPointerEvent().changes
                        val pressed = changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed == 1) {
                            changes.firstOrNull { it.pressed }?.let {
                                tracker.addPosition(it.uptimeMillis, it.position)
                            }
                        } else {
                            tracker.resetTracking()
                        }
                    }

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
            .pointerInput(stableGestureKey) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val candleList = currentCandles.value
                    val candleCount = candleList.size
                    if (candleCount == 0) return@detectTransformGestures

                    val cw = viewport.chartWidth(size.width.toFloat())

                    if (viewport.crosshairActive) {
                        viewport.crosshairX = (viewport.crosshairX + pan.x)
                            .coerceIn(0f, cw)
                        viewport.crosshairY = (viewport.crosshairY + pan.y)
                            .coerceIn(0f, viewport.chartHeight(size.height.toFloat()))
                        viewport.crosshairTotalWidth = size.width.toFloat()
                        val crosshairIndex = viewport.snappedCrosshairIndex(candleCount, cw)
                        onCrosshairTimestampChange(candleList.getOrNull(crosshairIndex)?.timestamp)
                        invalidateTick++
                        return@detectTransformGestures
                    }

                    viewport.panByPixels(pan.x, cw)
                    viewport.zoomBy(zoom, centroid.x, cw, candleCount)

                    viewport.clamp(candleCount)
                    rescale()
                    followLiveEdge[0] = viewport.isAtRightEdge(candleCount)
                    publishViewportState()
                    invalidateTick++
                }
            }
            // --- TAP GESTURES: crosshair + double-tap reset ---
            .pointerInput(stableGestureKey) {
                detectTapGestures(
                    onLongPress = { offset ->
                        val candleList = currentCandles.value
                        val candleCount = candleList.size
                        if (candleCount == 0) return@detectTapGestures

                        viewport.stopFling()
                        viewport.crosshairActive = true
                        viewport.crosshairX = offset.x.coerceIn(0f, viewport.chartWidth(size.width.toFloat()))
                        viewport.crosshairY = offset.y.coerceIn(0f, viewport.chartHeight(size.height.toFloat()))
                        viewport.crosshairTotalWidth = size.width.toFloat()
                        val crosshairIndex = viewport.snappedCrosshairIndex(candleCount, viewport.chartWidth(size.width.toFloat()))
                        onCrosshairTimestampChange(candleList.getOrNull(crosshairIndex)?.timestamp)
                        invalidateTick++
                    },
                    onDoubleTap = {
                        val candleCount = currentCandles.value.size
                        viewport.crosshairActive = false
                        onCrosshairTimestampChange(null)
                        if (candleCount > 0) {
                            viewport.resetToLatest(candleCount)
                            viewport.clamp(candleCount)
                            rescale()
                        }
                        followLiveEdge[0] = true
                        publishViewportState()
                        invalidateTick++
                    },
                    onTap = {
                        viewport.crosshairActive = false
                        onCrosshairTimestampChange(null)
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

        if (marketProfile != null && quality.volumeProfile) {
            clipRect(right = cw, bottom = ch) {
                drawMarketProfile(marketProfile, viewport, cw, ch)
            }
        }

        if (supportResistanceZones.isNotEmpty() && quality.structureAnnotations) {
            clipRect(right = cw, bottom = ch) {
                drawSupportResistanceZones(supportResistanceZones, viewport, cw, ch, structureLabelPaint)
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
        // LAYER 1.4: TRADEPRO overlays (Flip Zone, Buy/Sell-Hold, setup, absorption)
        // ====================================================================
        clipRect(right = cw, bottom = ch) {
            tradeProAnalysis?.let { drawTradeProOverlays(it, viewport, cw, ch) }
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

        if (autoFibLevels.isNotEmpty()) {
            clipRect(right = cw, bottom = ch) {
                drawAutoFibonacciLevels(
                    levels = autoFibLevels,
                    direction = autoFibDirection,
                    swingHigh = autoFibSwingHigh,
                    swingLow = autoFibSwingLow,
                    viewport = viewport,
                    cw = cw,
                    ch = ch,
                    labelPaint = structureLabelPaint,
                )
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
        } else if (syncedCrosshairTimestamp != null) {
            drawSyncedCrosshairLayer(
                syncedTimestamp = syncedCrosshairTimestamp,
                candles = candles,
                viewport = viewport,
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

        if (isLoadingOlder) {
            timeLabelPaint.textAlign = Paint.Align.LEFT
            timeLabelPaint.color = LoadingHistoryArgb
            drawContext.canvas.nativeCanvas.drawText(
                context.getString(R.string.chart_loading_history),
                8f,
                16f,
                timeLabelPaint,
            )
            timeLabelPaint.textAlign = Paint.Align.CENTER
            timeLabelPaint.color = AxisLabelArgb
        }

        // Close the frame: records the duration, recomputes adaptive quality for
        // the next frame, and publishes a throttled snapshot to the debug HUD.
        performanceMonitor?.endFrame()
    }
}

private const val HISTORY_PREFETCH_THRESHOLD_BARS = 24f
