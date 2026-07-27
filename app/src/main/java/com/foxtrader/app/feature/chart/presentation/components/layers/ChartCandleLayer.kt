package com.foxtrader.app.feature.chart.presentation.components.layers

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.StructureBreak
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral5
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Layer 1 — viewport-culled candle bodies and wicks.
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure DrawScope extensions - no Compose state - which is
// what keeps them cheap enough for the 120fps budget.

/** Candle bodies + wicks — the heart of the chart. Viewport-culled. */
internal fun DrawScope.drawCandleLayer(
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    val barWidth = viewport.barWidthPx(cw)
    val bodyWidth = (barWidth * 0.68f).coerceAtLeast(1f)
    val wickWidth = max(1f, barWidth * 0.1f)

    for (i in start until end) {
        val c = candles[i]
        val cx = viewport.xForIndex(i + 0.5f, cw)
        val color = if (c.isBullish) FoxBullish else FoxBearish

        // Wick
        val yHigh = viewport.yForPrice(c.high, ch)
        val yLow = viewport.yForPrice(c.low, ch)
        drawLine(
            color = color,
            start = Offset(cx, yHigh),
            end = Offset(cx, yLow),
            strokeWidth = wickWidth,
            cap = StrokeCap.Butt,
        )

        // Body
        val yOpen = viewport.yForPrice(c.open, ch)
        val yClose = viewport.yForPrice(c.close, ch)
        val top = min(yOpen, yClose)
        val bodyH = max(1f, abs(yClose - yOpen))
        drawRect(
            color = color,
            topLeft = Offset(cx - bodyWidth / 2f, top),
            size = Size(bodyWidth, bodyH),
        )
    }
}
