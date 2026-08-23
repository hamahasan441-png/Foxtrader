package com.foxtrader.app.feature.backtest.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale
import kotlin.math.abs

/**
 * TradingView/MetaTrader-style visual strategy tester for Backtest Lab.
 *
 * The canvas is deliberately bound to [BacktestLabUiState.replayCursor]. It never
 * draws a candle after that cursor, and [projectBacktestReplay] withholds an
 * exit/expiry outcome until its actual bar has been revealed.
 */
@Composable
fun BacktestVisualTester(
    state: BacktestLabUiState,
    viewModel: BacktestLabViewModel,
) {
    val candles = state.replayCandles
    if (candles.isEmpty()) return
    val cursor = state.replayCursor.coerceIn(0, candles.lastIndex)
    val projection = remember(candles, state.result, state.binaryResult, cursor) {
        projectBacktestReplay(candles, state.result, state.binaryResult, cursor)
    }

    VisualTesterCard {
        VisualTesterTitle("Visual Strategy Tester")
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Candle ${cursor + 1}/${candles.size} · ${state.symbol} ${state.timeframe.label} · causal replay",
            color = FoxNeutral60,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))

        ReplayCandleCanvas(
            candles = candles,
            cursor = cursor,
            markers = projection.markers,
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp),
        )

        Slider(
            value = cursor.toFloat(),
            onValueChange = { viewModel.seekVisualReplay(it.toInt()) },
            valueRange = 0f..candles.lastIndex.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = FoxAmber50,
                activeTrackColor = FoxAmber50,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReplayButton("Restart", Modifier.weight(1f), viewModel::restartVisualReplay)
            ReplayButton("-1", Modifier.weight(0.7f)) { viewModel.stepVisualReplay(-1) }
            ReplayButton(
                if (state.replayPlaying) "Pause" else "Play",
                Modifier.weight(0.9f),
                viewModel::toggleVisualReplayPlay,
            )
            ReplayButton("+1", Modifier.weight(0.7f)) { viewModel.stepVisualReplay(1) }
            ReplayButton("+10", Modifier.weight(0.8f)) { viewModel.stepVisualReplay(10) }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "W ${projection.wins} · L ${projection.losses} · BE/T ${projection.ties}",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
            )
            Text(
                String.format(Locale.US, "Win %.1f%%", projection.winRate),
                color = FoxAmber50,
                fontSize = 11.sp,
            )
            Text(
                String.format(Locale.US, "P&L %.2f", projection.netPnL),
                color = if (projection.netPnL >= 0.0) FoxBullishText else FoxBearishText,
                fontSize = 11.sp,
            )
        }
        Text(
            text = "Only candles through the cursor are drawn. Exit/expiry outcome stays hidden until that bar is revealed.",
            color = FoxNeutral60,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun VisualTesterCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun VisualTesterTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = FoxAmber50,
    )
}

@Composable
private fun ReplayButton(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = FoxNeutral10),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReplayCandleCanvas(
    candles: List<Candle>,
    cursor: Int,
    markers: List<ReplayTradeMarker>,
    modifier: Modifier = Modifier,
) {
    val safeCursor = cursor.coerceIn(0, candles.lastIndex)
    val start = (safeCursor - MAX_VISIBLE_BARS + 1).coerceAtLeast(0)
    val visible = candles.subList(start, safeCursor + 1)
    val visibleMarkers = markers.filter { marker ->
        marker.entryIndex in start..safeCursor ||
            (marker.exitIndex != null && marker.exitIndex in start..safeCursor)
    }

    Canvas(modifier = modifier.background(Color(0xFF0D1015))) {
        if (visible.isEmpty() || size.width <= 1f || size.height <= 1f) return@Canvas

        val rawMin = visible.minOf { it.low }
        val rawMax = visible.maxOf { it.high }
        val span = (rawMax - rawMin).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val pad = span * 0.08
        val minPrice = rawMin - pad
        val maxPrice = rawMax + pad
        val priceSpan = (maxPrice - minPrice).coerceAtLeast(1e-12)
        val slot = size.width / visible.size.coerceAtLeast(1)
        val bodyW = (slot * 0.62f).coerceIn(2f, 12f)

        fun x(index: Int): Float = ((index - start) + 0.5f) * slot
        fun y(price: Double): Float =
            ((maxPrice - price) / priceSpan * size.height)
                .toFloat()
                .coerceIn(0f, size.height)

        repeat(4) { row ->
            val gy = size.height * row / 4f
            drawLine(
                color = Color(0x223A4657),
                start = Offset(0f, gy),
                end = Offset(size.width, gy),
                strokeWidth = 1f,
            )
        }

        visible.forEachIndexed { local, candle ->
            val global = start + local
            val cx = x(global)
            val bullish = candle.close >= candle.open
            val color = if (bullish) FoxBullishText else FoxBearishText
            val highY = y(candle.high)
            val lowY = y(candle.low)
            val openY = y(candle.open)
            val closeY = y(candle.close)

            drawLine(
                color = color,
                start = Offset(cx, highY),
                end = Offset(cx, lowY),
                strokeWidth = 1.2f,
            )
            val top = minOf(openY, closeY)
            val h = abs(closeY - openY).coerceAtLeast(1.5f)
            drawRect(
                color = color,
                topLeft = Offset(cx - bodyW / 2f, top),
                size = Size(bodyW, h),
            )
        }

        visibleMarkers.forEach { marker ->
            if (marker.entryIndex in start..safeCursor) {
                val cx = x(marker.entryIndex)
                val cy = y(marker.entryPrice)
                val color = if (marker.direction == Direction.BULLISH) FoxBullishText else FoxBearishText
                val path = Path().apply {
                    if (marker.direction == Direction.BULLISH) {
                        moveTo(cx, cy - 8f)
                        lineTo(cx - 6f, cy + 3f)
                        lineTo(cx + 6f, cy + 3f)
                    } else {
                        moveTo(cx, cy + 8f)
                        lineTo(cx - 6f, cy - 3f)
                        lineTo(cx + 6f, cy - 3f)
                    }
                    close()
                }
                drawPath(path, color)
            }

            val exit = marker.exitIndex
            val exitPrice = marker.exitPrice
            if (exit != null && exitPrice != null && exit in start..safeCursor) {
                val color = when (marker.outcome) {
                    "WIN" -> FoxBullishText
                    "LOSS" -> FoxBearishText
                    else -> FoxAmber50
                }
                drawCircle(
                    color = color,
                    radius = 4.5f,
                    center = Offset(x(exit), y(exitPrice)),
                )
            }
        }

        drawLine(
            color = FoxAmber50.copy(alpha = 0.75f),
            start = Offset(x(safeCursor), 0f),
            end = Offset(x(safeCursor), size.height),
            strokeWidth = 1.2f,
        )
    }
}

private const val MAX_VISIBLE_BARS = 100
