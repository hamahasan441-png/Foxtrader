package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import kotlin.math.max

// Layer — strategy backtest trades + live signal levels drawn over the candles.
//
// Pure DrawScope extensions (no Compose state) so they stay cheap in the render
// loop. Everything is viewport-culled and null/empty-safe.

private val WinColor = FoxBullish
private val LossColor = FoxBearish

/**
 * Draw completed backtest trades: an entry marker, an exit marker, and a
 * connecting line coloured by outcome (green = profit, red = loss).
 */
internal fun DrawScope.drawStrategyTrades(
    trades: List<BacktestTrade>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (trades.isEmpty()) return
    val firstVisible = viewport.startIndex.toInt()
    val lastVisible = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (trade in trades) {
        // Cull trades entirely outside the visible window.
        if (trade.exitIndex < firstVisible || trade.entryIndex > lastVisible) continue

        val win = trade.netPnL >= 0.0
        val color = if (win) WinColor else LossColor

        val ex = viewport.xForIndex(trade.entryIndex + 0.5f, cw)
        val ey = viewport.yForPrice(trade.entryPrice, ch)
        val xx = viewport.xForIndex(trade.exitIndex + 0.5f, cw)
        val xy = viewport.yForPrice(trade.exitPrice, ch)

        // Connecting line entry -> exit (dashed, faint).
        drawLine(
            color = color.copy(alpha = 0.55f),
            start = Offset(ex, ey),
            end = Offset(xx, xy),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
            cap = StrokeCap.Round,
        )

        // Entry marker: triangle pointing in the trade direction.
        if (trade.entryIndex in firstVisible..lastVisible) {
            drawDirectionTriangle(ex, ey, trade.direction, color)
        }
        // Exit marker: small hollow circle (filled by outcome colour).
        if (trade.exitIndex in firstVisible..lastVisible) {
            drawCircle(color = color, radius = 3.5f, center = Offset(xx, xy))
            drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 1.3f, center = Offset(xx, xy))
        }
    }
}

/** Filled triangle marker: up for a long entry, down for a short entry. */
private fun DrawScope.drawDirectionTriangle(
    x: Float,
    y: Float,
    direction: Direction,
    color: Color,
) {
    val s = 6f
    val up = direction == Direction.BULLISH
    // Offset the marker slightly away from the candle for legibility.
    val cy = if (up) y + 10f else y - 10f
    val path = Path().apply {
        if (up) {
            moveTo(x, cy - s)
            lineTo(x - s, cy + s)
            lineTo(x + s, cy + s)
        } else {
            moveTo(x, cy + s)
            lineTo(x - s, cy - s)
            lineTo(x + s, cy - s)
        }
        close()
    }
    drawPath(path, color)
}

/**
 * Draw the live signal's entry / stop-loss / take-profit levels as horizontal
 * lines extending from the signal bar to the right edge, with price tags.
 */
internal fun DrawScope.drawLiveSignalLevels(
    signal: StrategySignal,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    labelPaint: Paint,
) {
    val startX = max(0f, viewport.xForIndex(signal.index + 0.5f, cw)).coerceAtMost(cw)

    fun level(price: Double, color: Color, label: String) {
        val y = viewport.yForPrice(price, ch)
        if (y < 0f || y > ch) return
        drawLine(
            color = color,
            start = Offset(startX, y),
            end = Offset(cw, y),
            strokeWidth = 1.6f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
        )
        val nativePaint = labelPaint
        nativePaint.color = color.toArgb()
        nativePaint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(label, startX + 4f, y - 3f, nativePaint)
    }

    level(signal.entry, FoxAmber50, "Entry ${fmt(signal.entry)}")
    level(signal.stopLoss, FoxBearish, "SL ${fmt(signal.stopLoss)}")
    level(signal.takeProfit, FoxBullish, "TP ${fmt(signal.takeProfit)}")
}

private fun fmt(price: Double): String =
    if (price >= 1000) String.format("%,.2f", price) else String.format("%.5f", price)
