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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
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
private val BullVolumeColor = FoxBullish.copy(alpha = 0.55f)
private val BearVolumeColor = FoxBearish.copy(alpha = 0.55f)
private val bullVolPath = Path()
private val bearVolPath = Path()

/** Volume histogram pane with finite-safe viewport and bar geometry. */
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

    val latestVolume = candles.lastOrNull()?.volume?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

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
            if (candles.isEmpty()) return@Canvas
            if (!startIndex.isFinite() || !visibleBars.isFinite() || visibleBars <= 0f) return@Canvas
            val w = size.width
            val chartH = size.height
            val chartW = (w - priceScaleWidthPx).coerceAtLeast(1f)
            if (!chartW.isFinite() || chartW <= 0f || !chartH.isFinite() || chartH <= 0f) return@Canvas

            val rawEnd = startIndex + visibleBars
            if (!rawEnd.isFinite()) return@Canvas
            val visStart = max(0, startIndex.toInt())
            val visEnd = min(candles.size, rawEnd.toInt() + 1)
            if (visEnd <= visStart) return@Canvas

            var maxVol = 0.0
            for (i in visStart until visEnd) {
                val volume = candles[i].volume
                if (volume.isFinite() && volume > maxVol) maxVol = volume
            }
            if (!maxVol.isFinite() || maxVol <= 0.0) return@Canvas

            val barWidth = (chartW / visibleBars * 0.7f).coerceIn(1f, 24f)
            val halfBar = barWidth / 2f
            bullVolPath.rewind()
            bearVolPath.rewind()
            for (i in visStart until visEnd) {
                val candle = candles[i]
                val volume = candle.volume
                if (!volume.isFinite() || volume < 0.0) continue
                val cx = (i + 0.5f - startIndex) / visibleBars * chartW
                if (!cx.isFinite() || cx < -barWidth || cx > chartW + barWidth) continue
                val rawHeight = (volume / maxVol * chartH).toFloat()
                if (!rawHeight.isFinite()) continue
                val volH = rawHeight.coerceIn(1f, chartH)
                val top = (chartH - volH).coerceIn(0f, chartH)
                val path = if (candle.isBullish) bullVolPath else bearVolPath
                path.addRect(Rect(cx - halfBar, top, cx + halfBar, (top + volH).coerceAtMost(chartH)))
            }
            if (!bullVolPath.isEmpty) drawPath(bullVolPath, BullVolumeColor)
            if (!bearVolPath.isEmpty) drawPath(bearVolPath, BearVolumeColor)

            val canvas = drawContext.canvas.nativeCanvas
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(formatVolume(maxVol), w - 4f, 12f, labelPaint)
        }
    }
}

private fun formatVolume(v: Double): String {
    if (!v.isFinite() || v < 0.0) return "—"
    return when {
        v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000)
        v >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1_000)
        else -> String.format(Locale.US, "%.0f", v)
    }
}
