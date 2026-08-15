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

/** Candle bodies + wicks — the heart of the chart. Viewport-culled.
 *
 * TradingView-style rendering:
 * - Clean, high-contrast green/red candles
 * - Wider bodies with thinner wicks for clear price action
 * - Doji candles rendered as visible horizontal lines
 * - Body width scales smoothly with zoom level
 * - Below ~3px/bar, candles degrade to single high-low bars (never the
 *   ambiguous "two thin lines" artefact of a body drawn at wick width)
 */
internal fun DrawScope.drawCandleLayer(
    candles: List<Candle>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val start = max(0, viewport.startIndex.toInt())
    val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    val barWidth = viewport.barWidthPx(cw)

    // `RENDER` Below ~3px/bar a body rectangle cannot be drawn distinctly from
    // its own wick: the old code floored the body at 2px while the bar slot was
    // narrower, so every candle degenerated into two overlapping thin lines and
    // neighbouring candles smeared into each other ("I see 2 lines instead of a
    // candlestick"). In that regime TradingView-style engines switch to a clean
    // single high-low bar per candle — one crisp line, colored by direction.
    if (barWidth < THIN_BAR_THRESHOLD_PX) {
        val lineWidth = barWidth.coerceIn(0.5f, 1.5f)
        for (i in start until end) {
            val c = candles[i]
            val cx = viewport.xForIndex(i + 0.5f, cw)
            val color = if (c.isBullish) FoxBullish else FoxBearish
            drawLine(
                color = color,
                start = Offset(cx, viewport.yForPrice(c.high, ch)),
                end = Offset(cx, viewport.yForPrice(c.low, ch)),
                strokeWidth = lineWidth,
                cap = StrokeCap.Butt,
            )
        }
        return
    }

    // TradingView-style proportions:
    // - Body takes ~80% of the bar slot but always leaves a >=1px gap to the
    //   next candle so adjacent bodies never fuse into a solid block
    // - Wick is always thin (1-2px) regardless of zoom
    val bodyWidth = min(barWidth * 0.8f, barWidth - 1f).coerceAtLeast(2f)
    val wickWidth = (barWidth * 0.08f).coerceIn(1f, 2.5f)

    // Minimum body height so doji and very-small-range candles are visible
    val minBodyHeight = 1.5f

    for (i in start until end) {
        val c = candles[i]
        val cx = viewport.xForIndex(i + 0.5f, cw)
        val bullish = c.isBullish

        // TradingView colors: solid green/red with slight variation for visual depth
        val bodyColor = if (bullish) FoxBullish else FoxBearish
        val wickColor = if (bullish) FoxBullish else FoxBearish

        // Wick (high to low) - always behind body
        val yHigh = viewport.yForPrice(c.high, ch)
        val yLow = viewport.yForPrice(c.low, ch)
        drawLine(
            color = wickColor,
            start = Offset(cx, yHigh),
            end = Offset(cx, yLow),
            strokeWidth = wickWidth,
            cap = StrokeCap.Butt,
        )

        // Body (open to close)
        val yOpen = viewport.yForPrice(c.open, ch)
        val yClose = viewport.yForPrice(c.close, ch)
        val top = min(yOpen, yClose)
        val rawBodyH = abs(yClose - yOpen)
        val bodyH = max(minBodyHeight, rawBodyH)

        // For very zoomed-in views, round the body corners slightly
        drawRect(
            color = bodyColor,
            topLeft = Offset(cx - bodyWidth / 2f, top),
            size = Size(bodyWidth, bodyH),
        )
    }
}

/**
 * Bar width (px) below which body+wick rendering is replaced by single-line
 * bars. At 3px there is no visual room for a body rectangle distinct from the
 * wick, so drawing both only produces the "two thin lines" artefact.
 */
private const val THIN_BAR_THRESHOLD_PX = 3f
