package com.foxtrader.app.feature.chart.presentation.components.layers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
import com.foxtrader.app.ui.theme.FoxNeutral20

// Layer 0 — institutional grid (price levels + time divisions).
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure DrawScope extensions - no Compose state - which is
// what keeps them cheap enough for the 120fps budget.

/** Grid lines — horizontal price levels + vertical time divisions. */
internal fun DrawScope.drawGridLayer(
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    totalW: Float,
) {
    val step = viewport.niceStep(6)
    if (step > 0.0) {
        var level = ceil(viewport.priceLow / step) * step
        while (level <= viewport.priceHigh) {
            val y = viewport.yForPrice(level, ch)
            if (y in 0f..ch) {
                drawLine(
                    color = FoxNeutral20,
                    start = Offset(0f, y),
                    end = Offset(totalW, y), // Extend into price scale area
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
                    color = FoxNeutral20,
                    start = Offset(x, 0f),
                    end = Offset(x, ch),
                    strokeWidth = 0.5f,
                )
            }
            i += timeStep
        }
    }
}
