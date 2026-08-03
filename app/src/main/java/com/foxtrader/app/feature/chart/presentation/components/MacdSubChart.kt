package com.foxtrader.app.feature.chart.presentation.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxtrader.app.feature.chart.presentation.ChartDimens
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral5
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


private val MacdLineColor = Color(0xFF2196F3)   // Blue MACD line
private val SignalLineColor = Color(0xFFFF9800)  // Orange signal line
private val HistPositive = Color(0xFF00C873)     // Green histogram (bullish)
private val HistNegative = Color(0xFFE8364F)     // Red histogram (bearish)
private val ZeroLineColor = Color(0x33FFFFFF)
private val MacdLabelArgb = android.graphics.Color.parseColor("#99999F")

/**
 * MACD oscillator sub-chart — rendered below the main candlestick chart.
 *
 * Displays:
 * - MACD histogram (green when positive/rising, red when negative) as bars
 * - MACD line (blue) and signal line (orange)
 * - Zero reference line
 *
 * Layout syncs with the main chart's viewport via [startIndex] and [visibleBars]
 * so bars align vertically with the candles above.
 */
@Composable
fun MacdSubChart(
    macdLine: ImmutableDoubleSeries,
    macdSignal: ImmutableDoubleSeries,
    macdHistogram: ImmutableDoubleSeries,
    startIndex: Float,
    visibleBars: Float,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = ChartDimens.paneDefaultHeight,
) {
    val density = LocalDensity.current
    val priceScaleWidthPx = with(density) { ChartDimens.subPaneScaleWidth.toPx() }
    val labelPaint = remember {
        Paint().apply {
            color = MacdLabelArgb
            textSize = with(density) { 9.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }

    val lastIdx = macdLine.size - 1
    val currentMacd = if (lastIdx >= 0) macdLine[lastIdx] else 0.0
    val currentSignal = if (macdSignal.size > 0) macdSignal[macdSignal.size - 1] else 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        // Header with current values
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                text = "MACD(12,26,9)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = String.format("%.5f / %.5f", currentMacd, currentSignal),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (currentMacd >= currentSignal) FoxBullish else FoxBearish,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .background(FoxNeutral5),
        ) {
            val w = size.width
            val h = size.height
            val priceScaleWidth = priceScaleWidthPx
            val chartW = (w - priceScaleWidth).coerceAtLeast(1f)
            val chartH = h

            val visStart = max(0, startIndex.toInt())
            val visEnd = min(macdLine.size, (startIndex + visibleBars).toInt() + 1)
            if (visEnd <= visStart) return@Canvas

            // Find the max absolute value across MACD, signal, and histogram in
            // the visible window to auto-scale the panel symmetrically around 0.
            var maxAbs = 1e-9
            for (i in visStart until visEnd) {
                maxAbs = max(maxAbs, abs(macdLine[i]))
                if (i < macdSignal.size) maxAbs = max(maxAbs, abs(macdSignal[i]))
                if (i < macdHistogram.size) maxAbs = max(maxAbs, abs(macdHistogram[i]))
            }
            // Pad by 12% so peaks don't touch the panel edge.
            maxAbs *= 1.12

            fun yForValue(value: Double): Float =
                (chartH / 2f - (value / maxAbs * (chartH / 2f)).toFloat())

            fun xForIndex(index: Float): Float =
                (index - startIndex) / visibleBars * chartW

            val zeroY = yForValue(0.0)

            // Zero reference line
            drawLine(
                color = ZeroLineColor,
                start = Offset(0f, zeroY),
                end = Offset(chartW, zeroY),
                strokeWidth = 1f,
            )

            // Histogram bars
            val barWidth = (chartW / visibleBars * 0.7f).coerceAtLeast(1f)
            for (i in visStart until visEnd) {
                if (i >= macdHistogram.size) continue
                val cx = xForIndex(i + 0.5f)
                if (cx < 0f || cx > chartW) continue
                val histVal = macdHistogram[i]
                val prevHist = if (i > 0 && i - 1 < macdHistogram.size) macdHistogram[i - 1] else histVal
                // Brighter when the histogram is expanding in its direction.
                val rising = abs(histVal) >= abs(prevHist)
                val baseColor = if (histVal >= 0) HistPositive else HistNegative
                val color = if (rising) baseColor else baseColor.copy(alpha = 0.5f)
                val yVal = yForValue(histVal)
                val top = min(yVal, zeroY)
                val barH = max(1f, abs(yVal - zeroY))
                drawRect(
                    color = color,
                    topLeft = Offset(cx - barWidth / 2f, top),
                    size = Size(barWidth, barH),
                )
            }

            // MACD line + signal line.
            // `PERF` Coordinates are computed inside drawSeriesLine from scalars
            // rather than via per-call `(Float)->Float` / `(Double)->Float`
            // lambdas, which previously allocated four closures every frame
            // (this pane redraws on every pan/zoom frame via the viewport flow).
            drawSeriesLine(
                series = macdLine,
                visStart = visStart, visEnd = visEnd,
                startIndex = startIndex, visibleBars = visibleBars,
                chartW = chartW, chartH = chartH, maxAbs = maxAbs,
                color = MacdLineColor,
            )
            drawSeriesLine(
                series = macdSignal,
                visStart = visStart, visEnd = visEnd,
                startIndex = startIndex, visibleBars = visibleBars,
                chartW = chartW, chartH = chartH, maxAbs = maxAbs,
                color = SignalLineColor,
            )

            // Y-axis label (max scale value)
            val canvas = drawContext.canvas.nativeCanvas
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format("%.4f", maxAbs), w - 4f, 12f, labelPaint)
            canvas.drawText("0", w - 4f, zeroY + 4f, labelPaint)
            canvas.drawText(String.format("-%.4f", maxAbs), w - 4f, chartH - 4f, labelPaint)
        }
    }
}

/**
 * Draw a value series as a connected line, viewport-culled.
 *
 * `PERF` Coordinate math is done inline from scalar params (no `xForIndex` /
 * `yForValue` lambda parameters) so no `Function` objects are allocated per
 * frame. `yForValue` mirrors the panel's symmetric-around-zero mapping.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeriesLine(
    series: ImmutableDoubleSeries,
    visStart: Int,
    visEnd: Int,
    startIndex: Float,
    visibleBars: Float,
    chartW: Float,
    chartH: Float,
    maxAbs: Double,
    color: Color,
) {
    val half = chartH / 2f
    for (i in visStart until visEnd - 1) {
        if (i + 1 >= series.size) break
        val x1 = (i + 0.5f - startIndex) / visibleBars * chartW
        val x2 = (i + 1.5f - startIndex) / visibleBars * chartW
        if (x1 > chartW && x2 > chartW) continue
        if (x1 < 0f && x2 < 0f) continue
        val y1 = half - (series[i] / maxAbs * half).toFloat()
        val y2 = half - (series[i + 1] / maxAbs * half).toFloat()
        drawLine(
            color = color,
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = 1.8f,
            cap = StrokeCap.Round,
        )
    }
}
