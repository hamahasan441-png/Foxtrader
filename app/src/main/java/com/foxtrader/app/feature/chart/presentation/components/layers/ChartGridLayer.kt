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

// Layer 0 — institutional grid (price levels + time divisions).
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure DrawScope extensions - no Compose state - which is
// what keeps them cheap enough for the 120fps budget.

/** Grid lines — horizontal price levels + vertical time divisions.
 *
 * TradingView-style: very subtle, almost invisible grid that doesn't
 * distract from the price action but provides orientation reference.
 */
internal fun DrawScope.drawGridLayer(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    totalW: Float,
) {
    // TradingView uses very subtle grid lines (~8% opacity)
    val gridColor = Color(0x14FFFFFF)

    val step = viewport.niceStep(6)
    if (step > 0.0) {
        var level = ceil(viewport.priceLow / step) * step
        while (level <= viewport.priceHigh) {
            val y = viewport.yForPrice(level, ch)
            if (y in 0f..ch) {
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(totalW, y),
                    strokeWidth = 0.5f,
                )
            }
            level += step
        }
    }

    // Vertical grid lines (time divisions)
    val timeStep = viewport.niceTimeStep(6)
    if (timeStep > 0) {
        val startIdx = max(0, viewport.startIndex.toInt())
        val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1
        var i = startIdx - (startIdx % timeStep) + timeStep
        while (i < endIdx) {
            val x = viewport.xForIndex(i.toFloat(), cw)
            if (x in 0f..cw) {
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, ch),
                    strokeWidth = 0.5f,
                )
            }
            i += timeStep
        }
    }
}
