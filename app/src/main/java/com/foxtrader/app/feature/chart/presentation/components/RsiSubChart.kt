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

private val RsiOverbought = Color(0xFFE53935)
private val RsiOversold = Color(0xFF43A047)
private val RsiLine = Color(0xFFFFC107)
private val RsiZoneFill = Color(0x1AFFC107)
private val GridLineColor = Color(0x33FFFFFF)
private val LabelArgb = android.graphics.Color.parseColor("#99999F")
private val RsiOverboughtGuide = RsiOverbought.copy(alpha = 0.5f)
private val RsiOversoldGuide = RsiOversold.copy(alpha = 0.5f)
private val overboughtPath = Path()
private val oversoldPath = Path()
private val neutralPath = Path()

/** RSI oscillator pane with finite-safe rendering for rapid indicator toggles. */
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

    val currentRsi = rsiValues.lastFiniteOrDefault(50.0).coerceIn(0.0, 100.0)
    val rsiColor = when {
        currentRsi >= 70 -> RsiOverbought
        currentRsi <= 30 -> RsiOversold
        else -> RsiLine
    }

    Column(modifier = modifier.fillMaxWidth()) {
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
                text = String.format(java.util.Locale.US, "%.1f", currentRsi),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = rsiColor,
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
            val h = size.height
            val chartW = (w - priceScaleWidthPx).coerceAtLeast(1f)
            if (!chartW.isFinite() || chartW <= 0f || !h.isFinite() || h <= 0f) return@Canvas

            fun yForRsi(value: Double): Float =
                ((100.0 - value.coerceIn(0.0, 100.0)) / 100.0 * h).toFloat()
            fun xForIndex(index: Float): Float =
                (index - startIndex) / visibleBars * chartW

            val y70 = yForRsi(70.0)
            val y30 = yForRsi(30.0)
            val y50 = yForRsi(50.0)
            drawLine(RsiOverboughtGuide, Offset(0f, y70), Offset(chartW, y70), 1f, PaneDash)
            drawLine(RsiOversoldGuide, Offset(0f, y30), Offset(chartW, y30), 1f, PaneDash)
            drawLine(GridLineColor, Offset(0f, y50), Offset(chartW, y50), 0.5f, PaneDash)
            drawRect(RsiZoneFill, Offset(0f, y70), Size(chartW, (y30 - y70).coerceAtLeast(0f)))

            if (rsiValues.size > 1) {
                val rawEnd = startIndex + visibleBars
                if (!rawEnd.isFinite()) return@Canvas
                val visStart = max(0, startIndex.toInt())
                val visEnd = min(rsiValues.size, rawEnd.toInt() + 1)

                overboughtPath.rewind(); oversoldPath.rewind(); neutralPath.rewind()
                var previousValue: Double? = null
                var prevX = 0f
                var prevY = 0f
                var lastZone = -1
                var hasOverbought = false
                var hasOversold = false
                var hasNeutral = false

                for (i in visStart until visEnd) {
                    val raw = rsiValues[i]
                    if (!raw.isFinite()) {
                        previousValue = null
                        lastZone = -1
                        continue
                    }
                    val value = raw.coerceIn(0.0, 100.0)
                    val x = xForIndex(i + 0.5f)
                    val y = yForRsi(value)
                    if (!x.isFinite() || !y.isFinite() || x < -2f || x > chartW + 2f) {
                        previousValue = null
                        lastZone = -1
                        continue
                    }

                    val prev = previousValue
                    if (prev != null) {
                        val zone = when {
                            (prev + value) / 2.0 >= 70.0 -> 0
                            (prev + value) / 2.0 <= 30.0 -> 1
                            else -> 2
                        }
                        val path = when (zone) {
                            0 -> overboughtPath
                            1 -> oversoldPath
                            else -> neutralPath
                        }
                        if (zone != lastZone) path.moveTo(prevX, prevY)
                        path.lineTo(x, y)
                        when (zone) {
                            0 -> hasOverbought = true
                            1 -> hasOversold = true
                            else -> hasNeutral = true
                        }
                        lastZone = zone
                    }
                    previousValue = value
                    prevX = x
                    prevY = y
                }

                val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                if (hasNeutral) drawPath(neutralPath, RsiLine, style = stroke)
                if (hasOverbought) drawPath(overboughtPath, RsiOverbought, style = stroke)
                if (hasOversold) drawPath(oversoldPath, RsiOversold, style = stroke)
            }

            val canvas = drawContext.canvas.nativeCanvas
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("70", w - 4f, y70 + 4f, labelPaint)
            canvas.drawText("30", w - 4f, y30 + 4f, labelPaint)
            canvas.drawText("50", w - 4f, y50 + 4f, labelPaint)
        }
    }
}

private fun ImmutableDoubleSeries.lastFiniteOrDefault(default: Double): Double {
    for (i in size - 1 downTo 0) {
        val value = this[i]
        if (value.isFinite()) return value
    }
    return default
}
