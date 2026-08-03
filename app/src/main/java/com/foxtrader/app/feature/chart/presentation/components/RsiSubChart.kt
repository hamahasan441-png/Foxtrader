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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxtrader.app.feature.chart.presentation.ChartDimens
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral5
import kotlin.math.max
import kotlin.math.min


private val RsiOverbought = Color(0xFFE53935) // Red zone > 70
private val RsiOversold = Color(0xFF43A047)   // Green zone < 30
private val RsiLine = Color(0xFFFFC107)       // Amber RSI line
private val RsiZoneFill = Color(0x1AFFC107)   // Subtle fill between 30-70
private val GridLineColor = Color(0x33FFFFFF)
private val LabelArgb = android.graphics.Color.parseColor("#99999F")

/**
 * RSI oscillator sub-chart — rendered below the main candlestick chart.
 *
 * Displays:
 * - RSI(14) line in amber
 * - Overbought (70) and oversold (30) horizontal reference lines
 * - Shaded zone between 30-70
 * - Color-coded current RSI value label
 *
 * Layout syncs with the main chart's viewport via [startIndex] and [visibleBars].
 */
@Composable
fun RsiSubChart(
    rsiValues: ImmutableDoubleSeries,
    startIndex: Float,
    visibleBars: Float,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = ChartDimens.paneDefaultHeight,
) {
    val density = LocalDensity.current
    val priceScaleWidthPx = with(density) { ChartDimens.subPaneScaleWidth.toPx() }
    val labelPaint = remember {
        Paint().apply {
            color = LabelArgb
            textSize = with(density) { 9.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }

    // Current RSI value for the label
    val currentRsi = if (rsiValues.size > 0) rsiValues[rsiValues.size - 1] else 50.0
    val rsiColor = when {
        currentRsi >= 70 -> RsiOverbought
        currentRsi <= 30 -> RsiOversold
        else -> RsiLine
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                text = "RSI(14)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = String.format("%.1f", currentRsi),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = rsiColor,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // Chart canvas (height is driven by the resizable pane stack, R3)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .background(FoxNeutral5),
        ) {
            val w = size.width
            val h = size.height
            val priceScaleWidth = priceScaleWidthPx // Right margin for labels

            val chartW = (w - priceScaleWidth).coerceAtLeast(1f)
            val chartH = h

            // RSI range is always 0-100
            val rsiHigh = 100.0
            val rsiLow = 0.0

            fun yForRsi(value: Double): Float =
                ((rsiHigh - value) / (rsiHigh - rsiLow) * chartH).toFloat()

            fun xForIndex(index: Float): Float =
                (index - startIndex) / visibleBars * chartW

            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))

            // Draw overbought line (70)
            val y70 = yForRsi(70.0)
            drawLine(
                color = RsiOverbought.copy(alpha = 0.5f),
                start = Offset(0f, y70),
                end = Offset(chartW, y70),
                strokeWidth = 1f,
                pathEffect = dashEffect,
            )

            // Draw oversold line (30)
            val y30 = yForRsi(30.0)
            drawLine(
                color = RsiOversold.copy(alpha = 0.5f),
                start = Offset(0f, y30),
                end = Offset(chartW, y30),
                strokeWidth = 1f,
                pathEffect = dashEffect,
            )

            // Draw middle line (50)
            val y50 = yForRsi(50.0)
            drawLine(
                color = GridLineColor,
                start = Offset(0f, y50),
                end = Offset(chartW, y50),
                strokeWidth = 0.5f,
                pathEffect = dashEffect,
            )

            // Draw shaded zone between 30-70
            drawRect(
                color = RsiZoneFill,
                topLeft = Offset(0f, y70),
                size = Size(chartW, y30 - y70),
            )

            // Draw RSI line
            val rsiData = rsiValues
            if (rsiData.size > 1) {
                val visStart = max(0, startIndex.toInt())
                val visEnd = min(rsiData.size, (startIndex + visibleBars).toInt() + 1)

                for (i in visStart until visEnd - 1) {
                    val x1 = xForIndex(i + 0.5f)
                    val x2 = xForIndex(i + 1.5f)
                    val y1 = yForRsi(rsiData[i])
                    val y2 = yForRsi(rsiData[i + 1])

                    if (x1 > chartW && x2 > chartW) continue
                    if (x1 < 0f && x2 < 0f) continue

                    // Color the line based on zone
                    val midVal = (rsiData[i] + rsiData[i + 1]) / 2.0
                    val lineColor = when {
                        midVal >= 70 -> RsiOverbought
                        midVal <= 30 -> RsiOversold
                        else -> RsiLine
                    }

                    drawLine(
                        color = lineColor,
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // Draw Y-axis labels
            val canvas = drawContext.canvas.nativeCanvas
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("70", w - 4f, y70 + 4f, labelPaint)
            canvas.drawText("30", w - 4f, y30 + 4f, labelPaint)
            canvas.drawText("50", w - 4f, y50 + 4f, labelPaint)
        }
    }
}
