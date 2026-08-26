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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.rsireversal.RsiCandleEngine
import com.foxtrader.app.feature.chart.presentation.ChartDimens
import com.foxtrader.app.feature.chart.presentation.RsiReversalStudySettings
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral5
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val RsiCandleBull = Color(0xFF26A69A)
private val RsiCandleBear = Color(0xFFEF5350)
private val RsiCandleGrid = Color(0x33FFFFFF)
private val RsiCandleOversoldLine = Color(0xFF43A047)
private val RsiCandleOverboughtLine = Color(0xFFE53935)
private val RsiCandleLabelArgb = android.graphics.Color.parseColor("#99999F")

/**
 * RSI Orderflow Candle pane.
 *
 * Renders RSI as candlesticks rather than a line: each bar's RSI open/high/low/
 * close drawn as a body with wicks, on the 30/50/70 scale, horizontally
 * synchronised with the price chart above.
 *
 * Rendering only. The pane reads the same engine the signal path reads and
 * draws what it returns; it computes nothing of its own, so what a trader sees
 * and what armed a setup cannot disagree.
 */
@Composable
fun RsiCandleSubChart(
    candles: List<Candle>,
    startIndex: Float,
    visibleBars: Float,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = ChartDimens.paneDefaultHeight,
    settings: RsiReversalStudySettings = RsiReversalStudySettings(),
) {
    val cfg = settings.sanitized()
    val rsiCandles = remember(candles, cfg.rsiLength) {
        runCatching { RsiCandleEngine.calculate(candles, cfg.rsiLength) }.getOrNull()
    } ?: return
    if (rsiCandles.isEmpty()) return

    val density = LocalDensity.current
    val priceScaleWidthPx = with(density) { ChartDimens.subPaneScaleWidth.toPx() }
    val labelPaint = remember {
        Paint().apply {
            color = RsiCandleLabelArgb
            textSize = with(density) { 9.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }

    val latest = rsiCandles.last()

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                text = "RSI Orderflow Candle (${cfg.rsiLength})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = String.format(java.util.Locale.US, "%.2f", latest.close),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (latest.isBullish) RsiCandleBull else RsiCandleBear,
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

            fun yFor(value: Double): Float =
                ((100.0 - value.coerceIn(0.0, 100.0)) / 100.0 * h).toFloat()

            fun xFor(index: Float): Float = (index - startIndex) / visibleBars * chartW

            val y70 = yFor(70.0)
            val y50 = yFor(50.0)
            val y30 = yFor(30.0)
            drawLine(RsiCandleOverboughtLine.copy(alpha = 0.45f), Offset(0f, y70), Offset(chartW, y70), 1f, pathEffect = PaneDash)
            drawLine(RsiCandleGrid, Offset(0f, y50), Offset(chartW, y50), 0.7f, pathEffect = PaneDash)
            drawLine(RsiCandleOversoldLine.copy(alpha = 0.45f), Offset(0f, y30), Offset(chartW, y30), 1f, pathEffect = PaneDash)

            val rawEnd = startIndex + visibleBars
            if (!rawEnd.isFinite()) return@Canvas
            val visStart = max(0, startIndex.toInt())
            val visEnd = min(rsiCandles.size, rawEnd.toInt() + 2)
            val pxPerBar = chartW / visibleBars.coerceAtLeast(2f)
            // Leave a gap between candles so the pane stays readable when the
            // price chart above is zoomed out; never thinner than one pixel.
            val bodyWidth = (pxPerBar * 0.7f).coerceIn(1f, 14f)
            val wickWidth = (bodyWidth * 0.18f).coerceIn(0.8f, 2.5f)

            for (i in visStart until visEnd) {
                val candle = rsiCandles[i]
                if (!candle.open.isFinite() || !candle.close.isFinite()) continue
                val x = xFor(i + 0.5f)
                if (!x.isFinite() || x < -bodyWidth || x > chartW + bodyWidth) continue

                val color = if (candle.isBullish) RsiCandleBull else RsiCandleBear
                val yHigh = yFor(candle.high)
                val yLow = yFor(candle.low)
                val yOpen = yFor(candle.open)
                val yClose = yFor(candle.close)

                drawRect(
                    color = color,
                    topLeft = Offset(x - wickWidth / 2f, min(yHigh, yLow)),
                    size = Size(wickWidth, abs(yLow - yHigh).coerceAtLeast(1f)),
                )
                drawRect(
                    color = color,
                    topLeft = Offset(x - bodyWidth / 2f, min(yOpen, yClose)),
                    // A doji still needs a visible mark, so the body floors at
                    // one pixel rather than vanishing.
                    size = Size(bodyWidth, abs(yClose - yOpen).coerceAtLeast(1f)),
                )
            }

            val canvas = drawContext.canvas.nativeCanvas
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("70", w - 4f, y70 + 4f, labelPaint)
            canvas.drawText("50", w - 4f, y50 + 4f, labelPaint)
            canvas.drawText("30", w - 4f, y30 + 4f, labelPaint)
        }
    }
}
