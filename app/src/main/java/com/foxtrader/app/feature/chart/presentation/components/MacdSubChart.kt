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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

private val MacdLineColor = Color(0xFF2196F3)
private val SignalLineColor = Color(0xFFFF9800)
private val HistPositive = Color(0xFF00C873)
private val HistNegative = Color(0xFFE8364F)
private val HistPositiveFaded = HistPositive.copy(alpha = 0.5f)
private val HistNegativeFaded = HistNegative.copy(alpha = 0.5f)
private val ZeroLineColor = Color(0x33FFFFFF)
private val MacdLabelArgb = android.graphics.Color.parseColor("#99999F")
private val histPosBright = Path()
private val histPosFaded = Path()
private val histNegBright = Path()
private val histNegFaded = Path()

/** MACD pane with strict finite checks around transient live-array states. */
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

    val currentMacd = macdLine.lastFiniteOrZero()
    val currentSignal = macdSignal.lastFiniteOrZero()

    Column(modifier = modifier.fillMaxWidth()) {
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
                text = String.format(java.util.Locale.US, "%.5f / %.5f", currentMacd, currentSignal),
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
            if (!startIndex.isFinite() || !visibleBars.isFinite() || visibleBars <= 0f) return@Canvas
            val w = size.width
            val chartH = size.height
            val chartW = (w - priceScaleWidthPx).coerceAtLeast(1f)
            if (!chartW.isFinite() || chartW <= 0f || !chartH.isFinite() || chartH <= 0f) return@Canvas

            val endFloat = startIndex + visibleBars
            if (!endFloat.isFinite()) return@Canvas
            val visStart = max(0, startIndex.toInt())
            val visEnd = min(macdLine.size, endFloat.toInt() + 1)
            if (visEnd <= visStart) return@Canvas

            var maxAbs = 0.0
            var hasFinite = false
            for (i in visStart until visEnd) {
                val m = macdLine[i]
                if (m.isFinite()) {
                    maxAbs = max(maxAbs, abs(m))
                    hasFinite = true
                }
                if (i < macdSignal.size) {
                    val s = macdSignal[i]
                    if (s.isFinite()) {
                        maxAbs = max(maxAbs, abs(s))
                        hasFinite = true
                    }
                }
                if (i < macdHistogram.size) {
                    val h = macdHistogram[i]
                    if (h.isFinite()) {
                        maxAbs = max(maxAbs, abs(h))
                        hasFinite = true
                    }
                }
            }
            if (!hasFinite) return@Canvas
            maxAbs = max(maxAbs, MIN_MACD_SCALE) * 1.12
            if (!maxAbs.isFinite() || maxAbs <= 0.0) return@Canvas

            fun yForValue(value: Double): Float =
                (chartH / 2f - (value / maxAbs * (chartH / 2f)).toFloat())
            fun xForIndex(index: Float): Float =
                (index - startIndex) / visibleBars * chartW

            val zeroY = chartH / 2f
            drawLine(
                color = ZeroLineColor,
                start = Offset(0f, zeroY),
                end = Offset(chartW, zeroY),
                strokeWidth = 1f,
            )

            val barWidth = (chartW / visibleBars * 0.7f).coerceIn(1f, 24f)
            val halfBar = barWidth / 2f
            histPosBright.rewind(); histPosFaded.rewind()
            histNegBright.rewind(); histNegFaded.rewind()

            for (i in visStart until visEnd) {
                if (i >= macdHistogram.size) continue
                val histVal = macdHistogram[i]
                if (!histVal.isFinite()) continue
                val cx = xForIndex(i + 0.5f)
                if (!cx.isFinite() || cx < -barWidth || cx > chartW + barWidth) continue
                val previous = if (i > 0 && i - 1 < macdHistogram.size) macdHistogram[i - 1] else histVal
                val prevHist = if (previous.isFinite()) previous else histVal
                val yVal = yForValue(histVal)
                if (!yVal.isFinite()) continue

                val rising = abs(histVal) >= abs(prevHist)
                val top = min(yVal, zeroY).coerceIn(0f, chartH)
                val bottom = max(yVal, zeroY).coerceIn(0f, chartH)
                val barH = (bottom - top).coerceAtLeast(1f)
                val path = when {
                    histVal >= 0 && rising -> histPosBright
                    histVal >= 0 -> histPosFaded
                    rising -> histNegBright
                    else -> histNegFaded
                }
                path.addRect(Rect(cx - halfBar, top, cx + halfBar, (top + barH).coerceAtMost(chartH)))
            }
            if (!histPosBright.isEmpty) drawPath(histPosBright, HistPositive)
            if (!histPosFaded.isEmpty) drawPath(histPosFaded, HistPositiveFaded)
            if (!histNegBright.isEmpty) drawPath(histNegBright, HistNegative)
            if (!histNegFaded.isEmpty) drawPath(histNegFaded, HistNegativeFaded)

            drawSeriesLine(macdLine, startIndex, visibleBars, chartW, chartH, maxAbs, MacdLineColor)
            drawSeriesLine(macdSignal, startIndex, visibleBars, chartW, chartH, maxAbs, SignalLineColor)

            val canvas = drawContext.canvas.nativeCanvas
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(java.util.Locale.US, "%.4f", maxAbs), w - 4f, 12f, labelPaint)
            canvas.drawText("0", w - 4f, zeroY + 4f, labelPaint)
            canvas.drawText(String.format(java.util.Locale.US, "-%.4f", maxAbs), w - 4f, chartH - 4f, labelPaint)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeriesLine(
    series: ImmutableDoubleSeries,
    startIndex: Float,
    visibleBars: Float,
    chartW: Float,
    chartH: Float,
    maxAbs: Double,
    color: Color,
) {
    if (!maxAbs.isFinite() || maxAbs <= 0.0 || !chartH.isFinite() || chartH <= 0f) return
    val half = chartH / 2f
    strokePaneSeries(
        series = series,
        startIndex = startIndex,
        visibleBars = visibleBars,
        chartW = chartW,
        color = color,
        strokeWidth = 1.8f,
    ) { v -> half - (v / maxAbs * half).toFloat() }
}

private fun ImmutableDoubleSeries.lastFiniteOrZero(): Double {
    for (i in size - 1 downTo 0) {
        val value = this[i]
        if (value.isFinite()) return value
    }
    return 0.0
}

private const val MIN_MACD_SCALE = 1e-9
