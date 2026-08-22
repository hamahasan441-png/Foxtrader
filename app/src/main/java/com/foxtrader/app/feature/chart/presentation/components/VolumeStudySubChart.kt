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
private val MfiOverboughtGuide = MfiOverbought.copy(alpha = 0.5f)
private val MfiOversoldGuide = MfiOversold.copy(alpha = 0.5f)
private val GridLineColor = Color(0x33FFFFFF)
private val LabelArgb = android.graphics.Color.parseColor("#99999F")

/** OBV pane with visible-window finite filtering. */
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
    val labelPaint = rememberLabelPaint(density, LabelArgb)
    val current = obv.lastFiniteStudyValue(0.0)

    Column(modifier = modifier.fillMaxWidth()) {
        PaneHeader(title = "OBV", value = formatCompact(current), valueColor = ObvLine)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .background(FoxNeutral5),
        ) {
            if (!startIndex.isFinite() || !visibleBars.isFinite() || visibleBars <= 0f) return@Canvas
            val w = size.width
            val chartW = (w - priceScaleWidthPx).coerceAtLeast(1f)
            val chartH = size.height
            if (!chartW.isFinite() || chartW <= 0f || !chartH.isFinite() || chartH <= 0f || obv.size < 2) return@Canvas

            val rawEnd = startIndex + visibleBars
            if (!rawEnd.isFinite()) return@Canvas
            val visStart = max(0, startIndex.toInt())
            val visEnd = min(obv.size, rawEnd.toInt() + 1)
            if (visEnd - visStart < 2) return@Canvas

            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            var finiteCount = 0
            for (i in visStart until visEnd) {
                val v = obv[i]
                if (!v.isFinite()) continue
                if (v < lo) lo = v
                if (v > hi) hi = v
                finiteCount++
            }
            if (finiteCount < 2 || !lo.isFinite() || !hi.isFinite()) return@Canvas
            val rawRange = hi - lo
            val range = rawRange.takeIf { it.isFinite() && it > MIN_OBV_RANGE } ?: 1.0

            fun yFor(value: Double): Float = ((hi - value) / range * chartH).toFloat()
            val midY = yFor(lo + range / 2.0)
            if (midY.isFinite()) {
                drawLine(
                    color = GridLineColor,
                    start = Offset(0f, midY),
                    end = Offset(chartW, midY),
                    strokeWidth = 0.5f,
                    pathEffect = PaneDash,
                )
            }

            strokePaneSeries(obv, startIndex, visibleBars, chartW, ObvLine, 1.8f) { v -> yFor(v) }

            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText(formatCompact(hi), w - 4f, 10f, labelPaint)
            canvas.drawText(formatCompact(lo), w - 4f, chartH - 2f, labelPaint)
        }
    }
}

/** MFI pane on its fixed 0..100 scale, finite-safe during live recomputation. */
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
    val labelPaint = rememberLabelPaint(density, LabelArgb)

    val current = mfi.lastFiniteStudyValue(50.0).coerceIn(0.0, 100.0)
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
            if (!startIndex.isFinite() || !visibleBars.isFinite() || visibleBars <= 0f) return@Canvas
            val w = size.width
            val chartW = (w - priceScaleWidthPx).coerceAtLeast(1f)
            val chartH = size.height
            if (!chartW.isFinite() || chartW <= 0f || !chartH.isFinite() || chartH <= 0f) return@Canvas

            fun yFor(value: Double): Float =
                ((100.0 - value.coerceIn(0.0, 100.0)) / 100.0 * chartH).toFloat()
            val y80 = yFor(80.0)
            val y20 = yFor(20.0)
            drawLine(
                color = MfiOverboughtGuide,
                start = Offset(0f, y80),
                end = Offset(chartW, y80),
                strokeWidth = 1f,
                pathEffect = PaneDash,
            )
            drawLine(
                color = MfiOversoldGuide,
                start = Offset(0f, y20),
                end = Offset(chartW, y20),
                strokeWidth = 1f,
                pathEffect = PaneDash,
            )

            strokePaneSeries(mfi, startIndex, visibleBars, chartW, MfiLine, 1.8f) { v -> yFor(v) }

            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText("80", w - 4f, y80 + 4f, labelPaint)
            canvas.drawText("20", w - 4f, y20 + 4f, labelPaint)
        }
    }
}

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

@Composable
private fun rememberLabelPaint(
    density: androidx.compose.ui.unit.Density,
    color: Int,
): Paint = remember(density, color) {
    Paint().apply {
        this.color = color
        textSize = with(density) { 9.dp.toPx() }
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
}

private fun ImmutableDoubleSeries.lastFiniteStudyValue(default: Double): Double {
    for (i in size - 1 downTo 0) {
        val value = this[i]
        if (value.isFinite()) return value
    }
    return default
}

private fun formatCompact(value: Double): String {
    if (!value.isFinite()) return "—"
    val a = abs(value)
    return when {
        a >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1_000_000_000)
        a >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000)
        a >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
        else -> String.format(Locale.US, "%.0f", value)
    }
}

private const val MIN_OBV_RANGE = 1e-9
