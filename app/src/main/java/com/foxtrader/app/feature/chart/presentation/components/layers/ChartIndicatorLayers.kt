package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
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
// Renderers are deliberately tolerant of short/partially-defined series because
// live candles can arrive one frame before their indicator arrays are refreshed.

private val IchimokuTenkanColor = Color(0xFFFFC107)
private val IchimokuKijunColor = Color(0xFF42A5F5)
private val IchimokuChikouColor = Color(0xFFAB47BC)
private val IchimokuBullishCloudColor = Color(0x2232CD32)
private val IchimokuBearishCloudColor = Color(0x22FF5252)
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
    if (emaShort != null) {
        drawEmaLine(viewport, cw, ch, emaShort, start, min(end, emaShort.size), FoxAmber50.copy(alpha = 0.85f))
    }
    if (emaLong != null) {
        drawEmaLine(viewport, cw, ch, emaLong, start, min(end, emaLong.size), FoxNeutral60.copy(alpha = 0.7f))
    }
}

private val linePathScratch = Path()

private fun lodStride(start: Int, end: Int, cw: Float): Int {
    val points = end - start
    if (points <= 0) return 1
    val maxPoints = cw.toInt().coerceAtLeast(2)
    return if (points <= maxPoints) 1 else points / maxPoints
}

/**
 * Core batched polyline. NaN, Infinity, zero and negative prices split the path
 * rather than being forwarded to the viewport transform/native Canvas.
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
    val safeStart = start.coerceIn(0, values.size)
    val safeEnd = end.coerceIn(safeStart, values.size)
    if (safeEnd - safeStart < 2) return
    val stride = lodStride(safeStart, safeEnd, cw)
    val path = linePathScratch
    path.rewind()
    var penDown = false
    var hasSegment = false
    var i = safeStart
    while (i < safeEnd) {
        val v = values[i]
        if (!v.isDrawableIndicatorPrice()) {
            penDown = false
        } else {
            val x = viewport.xForIndex(i + 0.5f, cw)
            val y = viewport.yForPrice(v, ch)
            if (x.isFinite() && y.isFinite()) {
                if (penDown) {
                    path.lineTo(x, y)
                    hasSegment = true
                } else {
                    path.moveTo(x, y)
                    penDown = true
                }
            } else {
                penDown = false
            }
        }
        i += if (i + stride >= safeEnd && i < safeEnd - 1) safeEnd - 1 - i else stride
    }
    if (hasSegment) {
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
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
    strokeSeriesPath(viewport, cw, ch, values, start, end, color, strokeWidth = 1.5f)
}

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

internal fun DrawScope.drawNaNSafeLineSeries(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    values: ImmutableDoubleSeries,
    color: Color,
    strokeWidth: Float,
) = drawLineSeries(viewport, cw, ch, values, color, strokeWidth)

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

private val bucketPathA = Path()
private val bucketPathB = Path()

/** SuperTrend line with invalid-point containment and short-array culling. */
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
    var bullDown = false
    var bearDown = false
    var hasBull = false
    var hasBear = false
    var previousIndex: Int? = null

    for (i in start until end) {
        val value = values[i]
        if (!value.isDrawableIndicatorPrice() || (dir[i] != 1 && dir[i] != -1)) {
            previousIndex = null
            bullDown = false
            bearDown = false
            continue
        }
        val x = viewport.xForIndex(i + 0.5f, cw)
        val y = viewport.yForPrice(value, ch)
        if (!x.isFinite() || !y.isFinite()) {
            previousIndex = null
            bullDown = false
            bearDown = false
            continue
        }

        val prev = previousIndex
        if (prev != null) {
            val prevValue = values[prev]
            val prevX = viewport.xForIndex(prev + 0.5f, cw)
            val prevY = viewport.yForPrice(prevValue, ch)
            if (dir[i] == 1) {
                if (!bullDown) { bullPath.moveTo(prevX, prevY); bullDown = true }
                bullPath.lineTo(x, y)
                hasBull = true
                bearDown = false
            } else {
                if (!bearDown) { bearPath.moveTo(prevX, prevY); bearDown = true }
                bearPath.lineTo(x, y)
                hasBear = true
                bullDown = false
            }
        }
        previousIndex = i
    }

    val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (hasBull) drawPath(bullPath, FoxBullish, style = stroke)
    if (hasBear) drawPath(bearPath, FoxBearish, style = stroke)
}

private var sarPointScratch = FloatArray(512)
private val sarPointPaint = android.graphics.Paint().apply {
    color = android.graphics.Color.argb(0xCC, 0xD4, 0xA8, 0x4E)
    strokeWidth = 4f
    strokeCap = android.graphics.Paint.Cap.ROUND
    style = android.graphics.Paint.Style.STROKE
    isAntiAlias = true
}

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
        val price = sar[i]
        if (!price.isDrawableIndicatorPrice()) continue
        val y = viewport.yForPrice(price, ch)
        if (!y.isFinite() || y < 0f || y > ch) continue
        val x = viewport.xForIndex(i + 0.5f, cw)
        if (!x.isFinite()) continue
        pts[count++] = x
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

    val start = max(0, viewport.startIndex.toInt())
    val end = min(minOf(senkouA.size, senkouB.size), (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (start < end) {
        val bullCloud = bucketPathA
        val bearCloud = bucketPathB
        bullCloud.rewind()
        bearCloud.rewind()
        var hasBullCloud = false
        var hasBearCloud = false
        var i = start

        while (i < end) {
            while (i < end && !isValidCloudPoint(senkouA[i], senkouB[i])) i++
            if (i >= end) break

            val runStart = i
            val runBullish = senkouA[i] >= senkouB[i]
            var runEnd = i
            i++
            while (
                i < end &&
                isValidCloudPoint(senkouA[i], senkouB[i]) &&
                (senkouA[i] >= senkouB[i]) == runBullish
            ) {
                runEnd = i
                i++
            }

            val path = if (runBullish) bullCloud else bearCloud
            val firstX = viewport.xForIndex(runStart.toFloat(), cw)
            val firstTop = viewport.yForPrice(max(senkouA[runStart], senkouB[runStart]), ch)
            if (!firstX.isFinite() || !firstTop.isFinite()) continue
            path.moveTo(firstX, firstTop)
            for (j in runStart..runEnd) {
                path.lineTo(
                    viewport.xForIndex((j + 1).toFloat(), cw),
                    viewport.yForPrice(max(senkouA[j], senkouB[j]), ch),
                )
            }
            for (j in runEnd downTo runStart) {
                path.lineTo(
                    viewport.xForIndex((j + 1).toFloat(), cw),
                    viewport.yForPrice(min(senkouA[j], senkouB[j]), ch),
                )
            }
            path.lineTo(
                viewport.xForIndex(runStart.toFloat(), cw),
                viewport.yForPrice(min(senkouA[runStart], senkouB[runStart]), ch),
            )
            path.close()
            if (runBullish) hasBullCloud = true else hasBearCloud = true
        }

        if (hasBullCloud) drawPath(bullCloud, IchimokuBullishCloudColor)
        if (hasBearCloud) drawPath(bearCloud, IchimokuBearishCloudColor)
    }
    drawLineSeries(viewport, cw, ch, senkouA, IchimokuSenkouAColor, 1f)
    drawLineSeries(viewport, cw, ch, senkouB, IchimokuSenkouBColor, 1f)
}

private val KeltnerBandColor = Color(0x6620C997)
private val KeltnerMidColor = Color(0xAA20C997)
private val DonchianBandColor = Color(0x66FF9F43)
private val DonchianMidColor = Color(0x88FF9F43)

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
        if (!price.isDrawableIndicatorPrice()) continue
        val y = viewport.yForPrice(price, ch)
        if (!y.isFinite() || y < 0f || y > ch) continue

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

private fun Double.isDrawableIndicatorPrice(): Boolean = isFinite() && this > 0.0
private fun isValidCloudPoint(a: Double, b: Double): Boolean =
    a.isDrawableIndicatorPrice() && b.isDrawableIndicatorPrice()

private val PivotDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
