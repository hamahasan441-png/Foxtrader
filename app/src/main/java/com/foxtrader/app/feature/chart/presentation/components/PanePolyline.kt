package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import kotlin.math.max
import kotlin.math.min

/**
 * Batched polyline renderer shared by the oscillator/volume sub-panes.
 *
 * `PERF` The sub-panes redraw on EVERY pan/zoom frame (they collect the main
 * chart's viewport flow), and each previously issued one Compose drawLine —
 * with two Offset allocations — per visible bar per series. With RSI + MACD +
 * Stochastic open that was easily >1k Canvas calls per gesture frame. Each
 * series is now accumulated into a reusable Path and stroked ONCE.
 *
 * The scratch Path is shared across all panes in a frame; safe because all
 * drawing happens on the single UI/render thread (same contract as the other
 * chart-layer scratch buffers).
 */
private val paneScratchPath = Path()

/**
 * Shared dashed-guide effect for the sub-panes' reference lines (70/30, 80/20,
 * zero line…). `PERF` Hoisted: `PathEffect.dashPathEffect` allocates a native
 * effect object, and each pane previously rebuilt it every frame.
 */
internal val PaneDash: PathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))

/**
 * Stroke [series] over the visible window as a single path.
 *
 * @param yFor maps a series value to a pane-local y pixel. One small lambda
 *   per series per frame — negligible next to the per-bar allocations it
 *   replaces.
 */
internal fun DrawScope.strokePaneSeries(
    series: ImmutableDoubleSeries,
    startIndex: Float,
    visibleBars: Float,
    chartW: Float,
    color: Color,
    strokeWidth: Float,
    yFor: (Double) -> Float,
) {
    if (series.size < 2) return
    val visStart = max(0, startIndex.toInt())
    val visEnd = min(series.size, (startIndex + visibleBars).toInt() + 1)
    if (visEnd - visStart < 2) return

    val path = paneScratchPath
    path.rewind()
    var penDown = false
    for (i in visStart until visEnd) {
        val x = (i + 0.5f - startIndex) / visibleBars * chartW
        // Cull columns fully outside the pane; NaN values lift the pen so
        // partially-defined series render only where valid.
        val v = series[i]
        if (x < -strokeWidth || x > chartW + strokeWidth || v.isNaN()) {
            penDown = false
            continue
        }
        val y = yFor(v)
        if (penDown) path.lineTo(x, y) else { path.moveTo(x, y); penDown = true }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
