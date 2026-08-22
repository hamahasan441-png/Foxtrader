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

/** Batched, finite-safe polyline renderer shared by oscillator/volume panes. */
private val paneScratchPath = Path()

/** Shared guide effect so the native effect is not allocated per frame. */
internal val PaneDash: PathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))

/**
 * Stroke [series] over the visible window as one path.
 *
 * Every numeric boundary is treated as untrusted. Live recomputes can briefly
 * expose partially-defined arrays, and a NaN/Infinity reaching Path/Canvas is a
 * much worse outcome than skipping one study segment for one frame.
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
    if (
        !startIndex.isFinite() ||
        !visibleBars.isFinite() || visibleBars <= 0f ||
        !chartW.isFinite() || chartW <= 0f ||
        !strokeWidth.isFinite() || strokeWidth <= 0f
    ) return

    val rawEnd = startIndex + visibleBars
    if (!rawEnd.isFinite()) return
    val visStart = max(0, startIndex.toInt())
    val visEnd = min(series.size, rawEnd.toInt() + 1)
    if (visEnd - visStart < 2) return

    val path = paneScratchPath
    path.rewind()
    var penDown = false
    var hasSegment = false

    for (i in visStart until visEnd) {
        val v = series[i]
        if (!v.isFinite()) {
            penDown = false
            continue
        }

        val x = (i + 0.5f - startIndex) / visibleBars * chartW
        if (!x.isFinite() || x < -strokeWidth || x > chartW + strokeWidth) {
            penDown = false
            continue
        }

        val y = yFor(v)
        if (!y.isFinite()) {
            penDown = false
            continue
        }

        if (penDown) {
            path.lineTo(x, y)
            hasSegment = true
        } else {
            path.moveTo(x, y)
            penDown = true
        }
    }

    if (hasSegment) {
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
