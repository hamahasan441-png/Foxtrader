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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val StochOverbought = Color(0xFFE53935)
private val StochOversold = Color(0xFF43A047)
private val StochKLine = Color(0xFF42A5F5)
private val StochDLine = Color(0xFFFF9F43)
private val StochZoneFill = Color(0x1A42A5F5)
private val GridLineColor = Color(0x33FFFFFF)
private val LabelArgb = android.graphics.Color.parseColor("#99999F")

/**
 * Stochastic oscillator sub-chart (%K and %D), rendered below the price chart.
 *
 * Mirrors [RsiSubChart]'s layout and viewport contract: the pane is driven by
 * the primary chart's [startIndex] / [visibleBars] so bars stay aligned with
 * the candles above as the user pans and zooms.
 *
 * The 80 / 20 bands are the conventional overbought / oversold thresholds for
 * Stochastic (RSI uses 70 / 30), and the crossover of %K through %D is the
 * signal traders read from this study.
 */
@Composable
fun StochasticSubChart(
    percentK: ImmutableDoubleSeries,
    percentD: ImmutableDoubleSeries,
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

    val currentK = if (percentK.size > 0) percentK[percentK.size - 1] else 50.0
    val currentD = if (percentD.size > 0) percentD[percentD.size - 1] else 50.0
    val kColor = when {
        currentK >= 80 -> StochOverbought
        currentK <= 20 -> StochOversold
        else -> StochKLine
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                text = "Stoch(14,3)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = String.format(Locale.US, "%.1f / %.1f", currentK, currentD),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = kColor,
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
            val chartW = (w - priceScaleWidthPx).coerceAtLeast(1f)
            val chartH = size.height

            fun yFor(value: Double): Float = ((100.0 - value) / 100.0 * chartH).toFloat()
            fun xForIndex(index: Float): Float = (index - startIndex) / visibleBars * chartW

            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            val y80 = yFor(80.0)
            val y20 = yFor(20.0)
            val y50 = yFor(50.0)

            drawRect(
                color = StochZoneFill,
                topLeft = Offset(0f, y80),
                size = Size(chartW, y20 - y80),
            )
            drawLine(StochOverbought.copy(alpha = 0.5f), Offset(0f, y80), Offset(chartW, y80), strokeWidth = 1f, pathEffect = dashEffect)
            drawLine(StochOversold.copy(alpha = 0.5f), Offset(0f, y20), Offset(chartW, y20), strokeWidth = 1f, pathEffect = dashEffect)
            drawLine(GridLineColor, Offset(0f, y50), Offset(chartW, y50), strokeWidth = 0.5f, pathEffect = dashEffect)

            /** Plots one series, culled to the visible window. */
            fun drawSeries(values: ImmutableDoubleSeries, color: Color, stroke: Float) {
                if (values.size < 2) return
                val visStart = max(0, startIndex.toInt())
                val visEnd = min(values.size, (startIndex + visibleBars).toInt() + 1)
                for (i in visStart until visEnd - 1) {
                    val x1 = xForIndex(i + 0.5f)
                    val x2 = xForIndex(i + 1.5f)
                    if (x1 > chartW && x2 > chartW) continue
                    if (x1 < 0f && x2 < 0f) continue
                    drawLine(
                        color = color,
                        start = Offset(x1, yFor(values[i])),
                        end = Offset(x2, yFor(values[i + 1])),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // %D (signal) sits under %K so the faster line stays readable.
            drawSeries(percentD, StochDLine, 1.4f)
            drawSeries(percentK, StochKLine, 2f)

            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText("80", w - 4f, y80 + 4f, labelPaint)
            canvas.drawText("20", w - 4f, y20 + 4f, labelPaint)
        }
    }
}
