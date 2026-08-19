package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.feature.chart.presentation.ImmutableIntSeries
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral60
import kotlin.math.max
import kotlin.math.min

// Layer 2 — indicator overlays (EMA, Bollinger, SuperTrend, PSAR, VWAP, Ichimoku).
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure DrawScope extensions - no Compose state - which is
// what keeps them cheap enough for the 120fps budget.

private val IchimokuTenkanColor = Color(0xFFFFC107)
private val IchimokuKijunColor = Color(0xFF42A5F5)
private val IchimokuChikouColor = Color(0xFFAB47BC)
private val IchimokuBullishCloudColor = Color(0x2232CD32)
private val IchimokuBearishCloudColor = Color(0x22FF5252)
// `PERF` Hoisted from the draw pass — previously allocated per frame.
private val IchimokuSenkouAColor = Color(0xFF66BB6A)
private val IchimokuSenkouBColor = Color(0xFFEF5350)
internal val SessionVwapColor = Color(0xFF9C27B0)
private const val IchimokuPrimaryStroke = 1.2f
private const val IchimokuChikouStroke = 0.8f

/** EMA/SMA indicator lines drawn over candles. */
internal fun DrawScope.drawIndicatorLayer(
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    emaShort: ImmutableDoubleSeries?,
    emaLong: ImmutableDoubleSeries?,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)

    // `RENDER` The end index is clamped per-series instead of requiring
    // `size >= end`. The old all-or-nothing guard made the whole EMA vanish
    // whenever the overlay array was even one bar shorter than the candle list
    // — routine during live tick appends, where the candle arrives one frame
    // before the recomputed overlays — which read as "EMA doesn't show up".
    if (emaShort != null) {
        drawEmaLine(viewport, cw, ch, emaShort, start, min(end, emaShort.size), FoxAmber50.copy(alpha = 0.85f))
    }

    if (emaLong != null) {
        drawEmaLine(viewport, cw, ch, emaLong, start, min(end, emaLong.size), FoxNeutral60.copy(alpha = 0.7f))
    }
}

// ============================================================================
// Polyline batching (perf)
//
// `PERF` Every line series used to issue ONE drawLine per bar. With several
// overlays enabled at a few hundred visible bars that is thousands of Canvas
// calls per frame — the dominant draw-pass cost when indicators are stacked.
// All polylines now build a single reusable Path and stroke it ONCE, which is
// one Canvas call per series regardless of bar count.
//
// Each batched renderer owns its scratch Path(s), reused across frames. Safe
// because drawing is single-threaded (UI/render thread) — the same reasoning
// as the shared ohlcBuilder in ChartCrosshairLayer. Renderers get DEDICATED
// paths (rather than one shared scratch) so a later rewind can never touch a
// path recorded earlier in the same frame.
// ============================================================================
private val linePathScratch = Path()

/**
 * Index stride so a zoomed-out series never emits more than ~1 vertex per
 * pixel. Sub-pixel segments are invisible but still cost path-building and
 * rasterisation; skipping them is lossless at ~1px resolution.
 */
private fun lodStride(start: Int, end: Int, cw: Float): Int {
    val points = end - start
    if (points <= 0) return 1
    val maxPoints = cw.toInt().coerceAtLeast(2)
    return if (points <= maxPoints) 1 else points / maxPoints
}

/**
 * Core batched polyline: builds [linePathScratch] from the series (NaN-safe —
 * gaps split the path into subpaths) and strokes it once.
 */
private fun DrawScope.strokeSeriesPath(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: ImmutableDoubleSeries,
    start: Int,
    end: Int,
    color: Color,
    strokeWidth: Float,
) {
    if (end - start < 2) return
    val stride = lodStride(start, end, cw)
    val path = linePathScratch
    path.rewind()
    var penDown = false
    var i = start
    while (i < end) {
        val v = values[i]
        if (v.isNaN()) {
            penDown = false
        } else {
            val x = viewport.xForIndex(i + 0.5f, cw)
            val y = viewport.yForPrice(v, ch)
            if (penDown) path.lineTo(x, y) else { path.moveTo(x, y); penDown = true }
        }
        // Always include the final bar so the line reaches the live edge even
        // when the stride would step past it.
        i += if (i + stride >= end && i < end - 1) end - 1 - i else stride
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

internal fun DrawScope.drawEmaLine(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: ImmutableDoubleSeries,
    start: Int,
    end: Int,
    color: Color,
) {
    strokeSeriesPath(viewport, cw, ch, values, start, end, color, strokeWidth = 1.5f)
}

/** Generic single-line series renderer (viewport-culled, batched). */
internal fun DrawScope.drawLineSeries(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: ImmutableDoubleSeries,
    color: Color,
    strokeWidth: Float,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(values.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    strokeSeriesPath(viewport, cw, ch, values, start, end, color, strokeWidth)
}

/**
 * NaN-safe single-line series renderer. Segments across NaN gaps are skipped, so
 * a partially-defined series (e.g. anchored VWAP before its anchor bar) renders
 * only where it is valid. Viewport-culled; batched into a single path stroke
 * (NaN handling is built into [strokeSeriesPath], so this is now an alias kept
 * for call-site clarity).
 */
internal fun DrawScope.drawNaNSafeLineSeries(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: ImmutableDoubleSeries,
    color: Color,
    strokeWidth: Float,
) = drawLineSeries(viewport, cw, ch, values, color, strokeWidth)

/**
 * Anchored VWAP: a cyan mid line plus symmetric standard-deviation bands. Drawn
 * NaN-safe so nothing renders before the anchor bar. Distinct cyan hue keeps it
 * readable alongside the purple session VWAP.
 */
internal fun DrawScope.drawAnchoredVwap(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    vwap: ImmutableDoubleSeries,
    upper: ImmutableDoubleSeries?,
    lower: ImmutableDoubleSeries?,
) {
    val lineColor = Color(0xFF00BCD4)
    val bandColor = Color(0x8000BCD4)
    drawNaNSafeLineSeries(viewport, cw, ch, vwap, lineColor, 1.8f)
    if (upper != null) drawNaNSafeLineSeries(viewport, cw, ch, upper, bandColor, 1f)
    if (lower != null) drawNaNSafeLineSeries(viewport, cw, ch, lower, bandColor, 1f)
}

/** Bollinger Bands: upper/lower channel + middle line. */
internal fun DrawScope.drawBollinger(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    upper: ImmutableDoubleSeries,
    middle: ImmutableDoubleSeries,
    lower: ImmutableDoubleSeries,
) {
    val bandColor = Color(0x663B8DF0)
    val midColor = Color(0xAA3B8DF0)
    drawLineSeries(viewport, cw, ch, upper, bandColor, 1.2f)
    drawLineSeries(viewport, cw, ch, lower, bandColor, 1.2f)
    drawLineSeries(viewport, cw, ch, middle, midColor, 1f)
}

// Dedicated two-bucket scratch paths for the direction-colored renderers
// (SuperTrend runs, Ichimoku cloud quads). Both draw their paths before
// returning, so reuse across renderers within a frame is safe.
private val bucketPathA = Path()
private val bucketPathB = Path()

/** SuperTrend line: green segment when bullish, red when bearish.
 *
 * `PERF` Batched: instead of one drawLine per bar (with a Color branch each),
 * consecutive same-direction runs are accumulated into two Paths — one per
 * direction — and each is stroked once. Two Canvas calls total per frame.
 */
internal fun DrawScope.drawSuperTrend(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: ImmutableDoubleSeries,
    dir: ImmutableIntSeries,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(minOf(values.size, dir.size), (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (end - start < 2) return

    val bullPath = bucketPathA
    val bearPath = bucketPathB
    bullPath.rewind()
    bearPath.rewind()
    // Track pen state per path so a direction flip starts a fresh subpath
    // anchored at the previous vertex (keeps the line visually continuous).
    var bullDown = false
    var bearDown = false

    var prevX = viewport.xForIndex(start + 0.5f, cw)
    var prevY = viewport.yForPrice(values[start], ch)
    for (i in start + 1 until end) {
        val x = viewport.xForIndex(i + 0.5f, cw)
        val y = viewport.yForPrice(values[i], ch)
        if (dir[i] == 1) {
            if (!bullDown) { bullPath.moveTo(prevX, prevY); bullDown = true }
            bullPath.lineTo(x, y)
            bearDown = false
        } else {
            if (!bearDown) { bearPath.moveTo(prevX, prevY); bearDown = true }
            bearPath.lineTo(x, y)
            bullDown = false
        }
        prevX = x
        prevY = y
    }
    val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (!bullPath.isEmpty) drawPath(bullPath, FoxBullish, style = stroke)
    if (!bearPath.isEmpty) drawPath(bearPath, FoxBearish, style = stroke)
}

// `PERF` PSAR batching: dots are collected into a reusable coord buffer and
// submitted as ONE native drawPoints call instead of one drawCircle per bar.
// Round stroke cap makes each point render as a filled dot. The buffer grows
// geometrically and is retained across frames (single-threaded render).
private var sarPointScratch = FloatArray(512)
private val sarPointPaint = android.graphics.Paint().apply {
    color = android.graphics.Color.argb(0xCC, 0xD4, 0xA8, 0x4E)
    strokeWidth = 4f
    strokeCap = android.graphics.Paint.Cap.ROUND
    style = android.graphics.Paint.Style.STROKE
    isAntiAlias = true
}

/** Parabolic SAR: dots above/below price (batched into one Canvas call). */
internal fun DrawScope.drawParabolicSar(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    sar: ImmutableDoubleSeries,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(sar.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (end <= start) return

    val needed = (end - start) * 2
    if (sarPointScratch.size < needed) {
        sarPointScratch = FloatArray(Integer.highestOneBit(needed - 1) shl 1)
    }
    val pts = sarPointScratch
    var count = 0
    for (i in start until end) {
        val y = viewport.yForPrice(sar[i], ch)
        if (y < 0f || y > ch) continue
        pts[count++] = viewport.xForIndex(i + 0.5f, cw)
        pts[count++] = y
    }
    if (count > 0) {
        drawContext.canvas.nativeCanvas.drawPoints(pts, 0, count, sarPointPaint)
    }
}

internal fun DrawScope.drawIchimoku(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    tenkan: ImmutableDoubleSeries,
    kijun: ImmutableDoubleSeries,
    senkouA: ImmutableDoubleSeries,
    senkouB: ImmutableDoubleSeries,
    chikou: ImmutableDoubleSeries,
) {
    drawLineSeries(viewport, cw, ch, tenkan, IchimokuTenkanColor, IchimokuPrimaryStroke)
    drawLineSeries(viewport, cw, ch, kijun, IchimokuKijunColor, IchimokuPrimaryStroke)
    drawLineSeries(viewport, cw, ch, chikou, IchimokuChikouColor, IchimokuChikouStroke)

    // `PERF` The Kumo cloud was one drawRect per visible bar (hundreds of
    // Canvas calls + per-bar Offset/Size allocations). Consecutive same-color
    // runs are now accumulated into closed quads inside two shared Paths —
    // one per cloud color — and each is filled once.
    val start = max(0, viewport.startIndex.toInt())
    val end = min(minOf(senkouA.size, senkouB.size), (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (start < end) {
        val bullCloud = bucketPathA
        val bearCloud = bucketPathB
        bullCloud.rewind()
        bearCloud.rewind()
        var runStart = start
        var runBullish = senkouA[start] >= senkouB[start]
        fun flushRun(lastIndexInclusive: Int) {
            // Emit the run [runStart, lastIndexInclusive] as a quad strip: top
            // edge left→right along max(A,B), then bottom edge right→left.
            val path = if (runBullish) bullCloud else bearCloud
            val first = runStart
            val lastIdx = lastIndexInclusive.coerceAtMost(end - 1)
            path.moveTo(viewport.xForIndex(first.toFloat(), cw), viewport.yForPrice(max(senkouA[first], senkouB[first]), ch))
            for (i in first..lastIdx) {
                val xr = viewport.xForIndex((i + 1).toFloat(), cw)
                path.lineTo(xr, viewport.yForPrice(max(senkouA[i], senkouB[i]), ch))
            }
            for (i in lastIdx downTo first) {
                val xr = viewport.xForIndex((i + 1).toFloat(), cw)
                path.lineTo(xr, viewport.yForPrice(min(senkouA[i], senkouB[i]), ch))
            }
            path.lineTo(viewport.xForIndex(first.toFloat(), cw), viewport.yForPrice(min(senkouA[first], senkouB[first]), ch))
            path.close()
        }
        for (i in start + 1 until end) {
            val bullish = senkouA[i] >= senkouB[i]
            if (bullish != runBullish) {
                flushRun(i - 1)
                runStart = i
                runBullish = bullish
            }
        }
        flushRun(end - 1)
        if (!bullCloud.isEmpty) drawPath(bullCloud, IchimokuBullishCloudColor)
        if (!bearCloud.isEmpty) drawPath(bearCloud, IchimokuBearishCloudColor)
    }
    drawLineSeries(viewport, cw, ch, senkouA, IchimokuSenkouAColor, 1f)
    drawLineSeries(viewport, cw, ch, senkouB, IchimokuSenkouBColor, 1f)
}

// ============================================================================
// Channel overlays (Keltner / Donchian) and daily pivot levels.
//
// These reuse drawLineSeries so they inherit the same viewport culling and
// per-frame cost profile as the existing overlays.
// ============================================================================

private val KeltnerBandColor = Color(0x6620C997)
private val KeltnerMidColor = Color(0xAA20C997)
private val DonchianBandColor = Color(0x66FF9F43)
private val DonchianMidColor = Color(0x88FF9F43)

/**
 * Keltner Channels — EMA midline with ATR-scaled envelope.
 *
 * Drawn in teal so it stays visually distinct from Bollinger's blue: the two
 * are frequently displayed together in a squeeze setup, and identical colours
 * would make the bands impossible to tell apart.
 */
internal fun DrawScope.drawKeltnerChannel(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    upper: ImmutableDoubleSeries,
    middle: ImmutableDoubleSeries,
    lower: ImmutableDoubleSeries,
) {
    drawLineSeries(viewport, cw, ch, upper, KeltnerBandColor, 1.2f)
    drawLineSeries(viewport, cw, ch, lower, KeltnerBandColor, 1.2f)
    drawLineSeries(viewport, cw, ch, middle, KeltnerMidColor, 1f)
}

/** Donchian Channels — highest-high / lowest-low breakout envelope (amber). */
internal fun DrawScope.drawDonchianChannel(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    upper: ImmutableDoubleSeries,
    middle: ImmutableDoubleSeries,
    lower: ImmutableDoubleSeries,
) {
    drawLineSeries(viewport, cw, ch, upper, DonchianBandColor, 1.2f)
    drawLineSeries(viewport, cw, ch, lower, DonchianBandColor, 1.2f)
    drawLineSeries(viewport, cw, ch, middle, DonchianMidColor, 0.9f)
}

/**
 * Daily pivot levels (P / R1-R3 / S1-S3) as labelled horizontal rays.
 *
 * Levels are price-absolute rather than per-bar, so each is a single full-width
 * line. Off-screen levels are skipped so a wide pivot range on a zoomed-in
 * chart costs nothing.
 */
internal fun DrawScope.drawPivotLevels(
    levels: com.foxtrader.app.domain.usecase.indicators.PivotPoints.PivotLevels,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    val pivotColor = Color(0xCCFFD166)
    val resistanceColor = Color(0x99FF6B6B)
    val supportColor = Color(0x9951CF66)

    val rows = listOf(
        Triple("R3", levels.r3, resistanceColor),
        Triple("R2", levels.r2, resistanceColor),
        Triple("R1", levels.r1, resistanceColor),
        Triple("P", levels.pivot, pivotColor),
        Triple("S1", levels.s1, supportColor),
        Triple("S2", levels.s2, supportColor),
        Triple("S3", levels.s3, supportColor),
    )

    for ((label, price, color) in rows) {
        if (!price.isFinite()) continue
        val y = viewport.yForPrice(price, ch)
        // Cull levels outside the canvas so zoomed-in charts stay cheap.
        if (y < 0f || y > ch) continue

        val isPivot = label == "P"
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(cw, y),
            strokeWidth = if (isPivot) 1.6f else 1f,
            pathEffect = if (isPivot) null else PivotDash,
        )
        drawContext.canvas.nativeCanvas.drawText(label, 6f, y - 4f, labelPaint)
    }
}

private val PivotDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
