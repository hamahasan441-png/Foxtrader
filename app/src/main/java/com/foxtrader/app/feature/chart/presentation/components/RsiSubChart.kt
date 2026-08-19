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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

// `PERF` Hoisted guide-line colors — `.copy(alpha=)` allocated per frame.
private val RsiOverboughtGuide = RsiOverbought.copy(alpha = 0.5f)
private val RsiOversoldGuide = RsiOversold.copy(alpha = 0.5f)

// `PERF` Reusable zone paths for the batched RSI line — rebuilt (rewind) every
// frame, never reallocated. Safe: all drawing is on the single render thread.
private val overboughtPath = Path()
private val oversoldPath = Path()
private val neutralPath = Path()

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

            val dashEffect = PaneDash

            // Draw overbought line (70)
            val y70 = yForRsi(70.0)
            drawLine(
                color = RsiOverboughtGuide,
                start = Offset(0f, y70),
                end = Offset(chartW, y70),
                strokeWidth = 1f,
                pathEffect = dashEffect,
            )

            // Draw oversold line (30)
            val y30 = yForRsi(30.0)
            drawLine(
                color = RsiOversoldGuide,
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

            // Draw RSI line.
            // `PERF` Batched: segments are accumulated into three zone paths
            // (overbought / oversold / neutral) and each is stroked once —
            // 3 Canvas calls instead of one drawLine (+2 Offset allocations)
            // per visible bar on every pan/zoom frame. Paths are module-level
            // scratch objects reused across frames (single-threaded render).
            val rsiData = rsiValues
            if (rsiData.size > 1) {
                val visStart = max(0, startIndex.toInt())
                val visEnd = min(rsiData.size, (startIndex + visibleBars).toInt() + 1)

                overboughtPath.rewind()
                oversoldPath.rewind()
                neutralPath.rewind()
                // Pen state per zone path: a zone change or cull gap starts a
                // fresh subpath anchored at the previous vertex.
                var lastZone = -1
                var prevX = 0f
                var prevY = 0f
                var hasPrev = false

                for (i in visStart until visEnd) {
                    val x = xForIndex(i + 0.5f)
                    if (x < -2f || x > chartW + 2f) {
                        hasPrev = false
                        // Force a moveTo on re-entry so the path never bridges
                        // across the culled gap.
                        lastZone = -1
                        continue
                    }
                    val v = rsiData[i]
                    val y = yForRsi(v)
                    if (hasPrev) {
                        val midVal = (rsiData[i - 1] + v) / 2.0
                        val zone = when {
                            midVal >= 70 -> 0
                            midVal <= 30 -> 1
                            else -> 2
                        }
                        val path = when (zone) {
                            0 -> overboughtPath
                            1 -> oversoldPath
                            else -> neutralPath
                        }
                        if (zone != lastZone) path.moveTo(prevX, prevY)
                        path.lineTo(x, y)
                        lastZone = zone
                    }
                    prevX = x
                    prevY = y
                    hasPrev = true
                }

                val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                if (!neutralPath.isEmpty) drawPath(neutralPath, RsiLine, style = stroke)
                if (!overboughtPath.isEmpty) drawPath(overboughtPath, RsiOverbought, style = stroke)
                if (!oversoldPath.isEmpty) drawPath(oversoldPath, RsiOversold, style = stroke)
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
