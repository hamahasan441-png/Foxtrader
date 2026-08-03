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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foxtrader.app.R
import com.foxtrader.app.feature.chart.presentation.CandleSeries
import com.foxtrader.app.feature.chart.presentation.ChartDimens
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral5
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val VolumeLabelArgb = android.graphics.Color.parseColor("#99999F")

/**
 * Volume histogram sub-pane — a separate resizable pane below the price chart
 * (TradingView-style), distinct from the Volume Profile overlay.
 *
 * Bars are coloured by candle direction (bullish/bearish), viewport-culled, and
 * auto-scaled to the max volume in the visible window. Horizontally synced with
 * the main chart via [startIndex] / [visibleBars].
 */
@Composable
fun VolumePane(
    candles: CandleSeries,
    startIndex: Float,
    visibleBars: Float,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = ChartDimens.paneDefaultHeight,
) {
    val density = LocalDensity.current
    val priceScaleWidthPx = with(density) { ChartDimens.subPaneScaleWidth.toPx() }
    val labelPaint = remember {
        Paint().apply {
            color = VolumeLabelArgb
            textSize = with(density) { 9.dp.toPx() }
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }

    val latestVolume = if (candles.isNotEmpty()) candles[candles.size - 1].volume else 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.chart_pane_volume_title),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = formatVolume(latestVolume),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
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
            val chartH = size.height
            val chartW = (w - priceScaleWidthPx).coerceAtLeast(1f)

            val visStart = max(0, startIndex.toInt())
            val visEnd = min(candles.size, (startIndex + visibleBars).toInt() + 1)
            if (visEnd <= visStart) return@Canvas

            var maxVol = 1e-9
            for (i in visStart until visEnd) {
                if (candles[i].volume > maxVol) maxVol = candles[i].volume
            }

            val barWidth = (chartW / visibleBars * 0.7f).coerceAtLeast(1f)
            for (i in visStart until visEnd) {
                val cx = (i + 0.5f - startIndex) / visibleBars * chartW
                if (cx < 0f || cx > chartW) continue
                val candle = candles[i]
                val volH = (candle.volume / maxVol * chartH).toFloat().coerceAtLeast(1f)
                val top = chartH - volH
                val color = if (candle.isBullish) {
                    FoxBullish.copy(alpha = 0.55f)
                } else {
                    FoxBearish.copy(alpha = 0.55f)
                }
                drawRect(
                    color = color,
                    topLeft = Offset(cx - barWidth / 2f, top),
                    size = Size(barWidth, volH),
                )
            }

            val canvas = drawContext.canvas.nativeCanvas
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(formatVolume(maxVol), w - 4f, 12f, labelPaint)
        }
    }
}

private fun formatVolume(v: Double): String = when {
    v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000)
    v >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1_000)
    else -> String.format(Locale.US, "%.0f", v)
}
