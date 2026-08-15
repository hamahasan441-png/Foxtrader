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
import androidx.compose.ui.graphics.Color
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val ObvLine = Color(0xFF20C997)
private val MfiLine = Color(0xFFAB47BC)
private val MfiOverbought = Color(0xFFE53935)
private val MfiOversold = Color(0xFF43A047)
// `PERF` Hoisted guide colors — previously allocated per frame via copy(alpha=).
private val MfiOverboughtGuide = MfiOverbought.copy(alpha = 0.5f)
private val MfiOversoldGuide = MfiOversold.copy(alpha = 0.5f)
private val GridLineColor = Color(0x33FFFFFF)
private val LabelArgb = android.graphics.Color.parseColor("#99999F")

/**
 * On-Balance Volume pane.
 *
 * Unlike RSI/Stochastic/MFI, OBV is an *unbounded cumulative* series, so it has
 * no fixed 0-100 axis. The pane therefore auto-scales to the min/max of the
 * currently visible window — which is also what makes it useful: the shape of
 * the line against price (divergence) is the signal, not its absolute value.
 */
@Composable
fun ObvSubChart(
    obv: ImmutableDoubleSeries,
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

    val current = if (obv.size > 0) obv[obv.size - 1] else 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        PaneHeader(title = "OBV", value = formatCompact(current), valueColor = ObvLine)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .background(FoxNeutral5),
        ) {
            val w = size.width
            val chartW = (w - priceScaleWidthPx).coerceAtLeast(1f)
            val chartH = size.height
            if (obv.size < 2) return@Canvas

            val visStart = max(0, startIndex.toInt())
            val visEnd = min(obv.size, (startIndex + visibleBars).toInt() + 1)
            if (visEnd - visStart < 2) return@Canvas

            // Auto-scale to the visible window so the line always fills the pane.
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            for (i in visStart until visEnd) {
                val v = obv[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            val range = (hi - lo).takeIf { it > 1e-9 } ?: 1.0

            fun yFor(value: Double): Float = ((hi - value) / range * chartH).toFloat()
            val midY = yFor(lo + range / 2.0)
            drawLine(
                GridLineColor,
                Offset(0f, midY),
                Offset(chartW, midY),
                strokeWidth = 0.5f,
                pathEffect = PaneDash,
            )

            // `PERF` One batched Path stroke instead of a drawLine per bar.
            strokePaneSeries(obv, startIndex, visibleBars, chartW, ObvLine, 1.8f) { v -> yFor(v) }

            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText(formatCompact(hi), w - 4f, 10f, labelPaint)
            canvas.drawText(formatCompact(lo), w - 4f, chartH - 2f, labelPaint)
        }
    }
}

/**
 * Money Flow Index pane — a volume-weighted RSI on a fixed 0-100 axis, with the
 * conventional 80 / 20 overbought and oversold bands.
 */
@Composable
fun MoneyFlowSubChart(
    mfi: ImmutableDoubleSeries,
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

    val current = if (mfi.size > 0) mfi[mfi.size - 1] else 50.0
    val valueColor = when {
        current >= 80 -> MfiOverbought
        current <= 20 -> MfiOversold
        else -> MfiLine
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PaneHeader(
            title = "MFI(14)",
            value = String.format(Locale.US, "%.1f", current),
            valueColor = valueColor,
        )

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
            val dashEffect = PaneDash
            val y80 = yFor(80.0)
            val y20 = yFor(20.0)
            drawLine(MfiOverboughtGuide, Offset(0f, y80), Offset(chartW, y80), strokeWidth = 1f, pathEffect = dashEffect)
            drawLine(MfiOversoldGuide, Offset(0f, y20), Offset(chartW, y20), strokeWidth = 1f, pathEffect = dashEffect)

            // `PERF` One batched Path stroke instead of a drawLine per bar.
            strokePaneSeries(mfi, startIndex, visibleBars, chartW, MfiLine, 1.8f) { v -> yFor(v) }

            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText("80", w - 4f, y80 + 4f, labelPaint)
            canvas.drawText("20", w - 4f, y20 + 4f, labelPaint)
        }
    }
}

/** Shared title/value strip used by the volume study panes. */
@Composable
private fun PaneHeader(title: String, value: String, valueColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = FoxAmber50,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/**
 * Compact SI-style formatting (1.2M, 3.4K). OBV values run to millions on
 * liquid instruments and would otherwise overflow the narrow scale gutter.
 */
private fun formatCompact(value: Double): String {
    val a = abs(value)
    return when {
        a >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1_000_000_000)
        a >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000)
        a >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
        else -> String.format(Locale.US, "%.0f", value)
    }
}
