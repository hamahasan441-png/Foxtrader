package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.feature.chart.presentation.ImmutableIntSeries
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
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

internal fun DrawScope.drawEmaLine(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: ImmutableDoubleSeries,
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

/**
 * NaN-safe single-line series renderer. Segments across NaN gaps are skipped, so
 * a partially-defined series (e.g. anchored VWAP before its anchor bar) renders
 * only where it is valid. Viewport-culled.
 */
internal fun DrawScope.drawNaNSafeLineSeries(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: ImmutableDoubleSeries,
    color: Color,
    strokeWidth: Float,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(values.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (end - start < 2) return
    var hasPrev = false
    var prevX = 0f
    var prevY = 0f
    for (i in start until end) {
        val v = values[i]
        if (v.isNaN()) {
            hasPrev = false
            continue
        }
        val x = viewport.xForIndex(i + 0.5f, cw)
        val y = viewport.yForPrice(v, ch)
        if (hasPrev) {
            drawLine(color, Offset(prevX, prevY), Offset(x, y), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
        prevX = x
        prevY = y
        hasPrev = true
    }
}

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

/** SuperTrend line: green segment when bullish, red when bearish. */
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
internal fun DrawScope.drawParabolicSar(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    sar: ImmutableDoubleSeries,
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
