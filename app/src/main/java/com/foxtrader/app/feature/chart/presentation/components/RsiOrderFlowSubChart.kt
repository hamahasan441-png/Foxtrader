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
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.indicators.RsiOrderFlow
import com.foxtrader.app.feature.chart.presentation.ChartDimens
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral5
import kotlin.math.max
import kotlin.math.min

private val RsiOfOverbought = Color(0xFFE53935)
private val RsiOfOversold = Color(0xFF43A047)
private val RsiOfRsiLine = Color(0xFFFFC107)
private val RsiOfFlowLine = Color(0xFF29B6F6)
private val RsiOfBull = Color(0xFF38D996)
private val RsiOfBear = Color(0xFFFF5C6C)
private val RsiOfGrid = Color(0x33FFFFFF)
private val RsiOfFlowBuy = Color(0x3438D996)
private val RsiOfFlowSell = Color(0x34FF5C6C)
private val RsiOfZoneFill = Color(0x12FFC107)
private val RsiOfLabelArgb = android.graphics.Color.parseColor("#99999F")

/**
 * RSI OrderFlow pane.
 *
 * RSI is plotted on the same 0..100 scale as a smoothed directional-flow
 * oscillator. Flow bars are an OHLCV proxy, not exchange bid/ask footprint.
 * Confirmed dual RSI+flow divergences remain visible historically and are drawn
 * only after their right-side pivot confirmation is available.
 */
@Composable
fun RsiOrderFlowSubChart(
    candles: List<Candle>,
    startIndex: Float,
    visibleBars: Float,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = ChartDimens.paneDefaultHeight,
) {
    val analysis = remember(candles) {
        runCatching { RsiOrderFlow.calculate(candles) }.getOrNull()
    } ?: return
    if (analysis.rsi.isEmpty()) return

    val density = LocalDensity.current
    val priceScaleWidthPx = with(density) { ChartDimens.subPaneScaleWidth.toPx() }
    val labelPaint = remember {
        Paint().apply {
            color = RsiOfLabelArgb
            textSize = with(density) { 9.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }

    val currentRsi = analysis.rsi.last().takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 50.0
    val currentFlow = analysis.flow.last().takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 50.0
    val latestDivergence = analysis.divergences.lastOrNull()

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                Text(
                    text = "RSI OrderFlow",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FoxAmber50,
                )
                Text(
                    text = "RSI · OF proxy · confirmed divergence",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(
                    text = String.format(java.util.Locale.US, "RSI %.1f · OF %.1f", currentRsi, currentFlow),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (currentFlow >= 50.0) RsiOfBull else RsiOfBear,
                )
                latestDivergence?.let { div ->
                    Text(
                        text = "${if (div.bullish) "BULL DIV" else "BEAR DIV"} ${div.strength}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (div.bullish) RsiOfBull else RsiOfBear,
                    )
                }
            }
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

            fun yFor(value: Double): Float =
                ((100.0 - value.coerceIn(0.0, 100.0)) / 100.0 * h).toFloat()
            fun xFor(index: Float): Float =
                (index - startIndex) / visibleBars * chartW

            val y70 = yFor(70.0)
            val y50 = yFor(50.0)
            val y30 = yFor(30.0)

            drawRect(
                color = RsiOfZoneFill,
                topLeft = Offset(0f, y70),
                size = Size(chartW, (y30 - y70).coerceAtLeast(0f)),
            )
            drawLine(RsiOfOverbought.copy(alpha = 0.48f), Offset(0f, y70), Offset(chartW, y70), 1f, pathEffect = PaneDash)
            drawLine(RsiOfGrid, Offset(0f, y50), Offset(chartW, y50), 0.7f, pathEffect = PaneDash)
            drawLine(RsiOfOversold.copy(alpha = 0.48f), Offset(0f, y30), Offset(chartW, y30), 1f, pathEffect = PaneDash)

            val rawEnd = startIndex + visibleBars
            if (!rawEnd.isFinite()) return@Canvas
            val visStart = max(0, startIndex.toInt())
            val visEnd = min(analysis.rsi.size, rawEnd.toInt() + 2)
            val pxPerBar = chartW / visibleBars.coerceAtLeast(2f)
            val flowBarWidth = (pxPerBar * 0.62f).coerceIn(1f, 8f)

            // Order-flow pressure histogram around the neutral 50 line.
            for (i in visStart until visEnd) {
                val value = analysis.flow[i]
                if (!value.isFinite()) continue
                val x = xFor(i + 0.5f)
                if (!x.isFinite() || x < -flowBarWidth || x > chartW + flowBarWidth) continue
                val y = yFor(value)
                val top = min(y50, y)
                val height = kotlin.math.abs(y - y50).coerceAtLeast(1f)
                drawRect(
                    color = if (value >= 50.0) RsiOfFlowBuy else RsiOfFlowSell,
                    topLeft = Offset(x - flowBarWidth / 2f, top),
                    size = Size(flowBarWidth, height),
                )
            }

            fun drawSeries(values: DoubleArray, color: Color, width: Float) {
                val path = Path()
                var started = false
                for (i in visStart until min(visEnd, values.size)) {
                    val value = values[i]
                    if (!value.isFinite()) {
                        started = false
                        continue
                    }
                    val x = xFor(i + 0.5f)
                    val y = yFor(value)
                    if (!x.isFinite() || !y.isFinite()) continue
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                if (started) {
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }

            drawSeries(analysis.flow, RsiOfFlowLine.copy(alpha = 0.9f), 1.5f)
            drawSeries(analysis.rsi, RsiOfRsiLine, 2.2f)

            // Historical confirmed divergences. The line is attached to the
            // actual oscillator pivots; the small dot is on confirmedIndex so
            // the user can see when the setup became knowable in real time.
            analysis.divergences.forEach { div ->
                if (div.confirmedIndex >= analysis.rsi.size) return@forEach
                if (div.endIndex < visStart - 2 || div.startIndex > visEnd + 2) return@forEach

                val color = if (div.bullish) RsiOfBull else RsiOfBear
                val start = Offset(xFor(div.startIndex + 0.5f), yFor(div.startRsi))
                val end = Offset(xFor(div.endIndex + 0.5f), yFor(div.endRsi))
                if (start.x.isFinite() && start.y.isFinite() && end.x.isFinite() && end.y.isFinite()) {
                    drawLine(
                        color = color,
                        start = start,
                        end = end,
                        strokeWidth = 2.4f,
                        pathEffect = if (
                            div.type == RsiOrderFlow.DivergenceType.HIDDEN_BULLISH ||
                            div.type == RsiOrderFlow.DivergenceType.HIDDEN_BEARISH
                        ) PaneDash else null,
                    )

                    val triangle = Path()
                    val s = 4.5f
                    if (div.bullish) {
                        triangle.moveTo(end.x, end.y - s)
                        triangle.lineTo(end.x - s, end.y + s)
                        triangle.lineTo(end.x + s, end.y + s)
                    } else {
                        triangle.moveTo(end.x, end.y + s)
                        triangle.lineTo(end.x - s, end.y - s)
                        triangle.lineTo(end.x + s, end.y - s)
                    }
                    triangle.close()
                    drawPath(triangle, color)
                }

                val confirmX = xFor(div.confirmedIndex + 0.5f)
                val confirmY = yFor(div.endRsi)
                if (confirmX in -4f..(chartW + 4f) && confirmY.isFinite()) {
                    drawCircle(color = color, radius = 2.4f, center = Offset(confirmX, confirmY))
                }
            }

            val canvas = drawContext.canvas.nativeCanvas
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("70", w - 4f, y70 + 4f, labelPaint)
            canvas.drawText("50", w - 4f, y50 + 4f, labelPaint)
            canvas.drawText("30", w - 4f, y30 + 4f, labelPaint)
        }
    }
}
