package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral20
import com.foxtrader.app.ui.theme.FoxNeutral5
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Hardware-accelerated candlestick chart.
 *
 * Renders through Compose [Canvas], which draws on Android's GPU-backed
 * RenderThread (Skia). Key performance techniques:
 *  - Viewport culling: only candles in the visible index range are drawn
 *  - No per-frame allocations in the draw loop
 *  - Gesture-driven pan (drag) + zoom (pinch) mutate the viewport only
 *
 * Scales to very large series because draw cost is bounded by visible bars,
 * not total candle count.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    modifier: Modifier = Modifier,
) {
    // Viewport survives recomposition; initialised to the most recent bars.
    val viewport = remember { ChartViewport() }
    // Bump this to force a redraw after a gesture mutates the (non-snapshot)
    // viewport. Only mutated from gesture callbacks (never during composition).
    var invalidateTick by remember { mutableStateOf(0) }

    // Initialise / follow the right edge when data first arrives or grows.
    // NOTE: this block must NOT write snapshot state (e.g. invalidateTick) —
    // it runs during composition. A change in candles.size already triggers a
    // recomposition that re-executes the Canvas draw, so no manual tick needed.
    remember(candles.size) {
        val count = min(120, candles.size).toFloat()
        if (viewport.visibleBars <= 0f || viewport.startIndex == 0f) {
            viewport.visibleBars = count.coerceAtLeast(10f)
            viewport.startIndex = max(0f, candles.size - count)
        }
        viewport.clamp(candles.size)
        viewport.autoScale(candles)
        candles.size
    }

    Canvas(
        modifier = modifier
            .background(FoxNeutral5)
            .pointerInput(candles.size) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // Pan: horizontal drag moves the index window
                    val barsPerPx = viewport.visibleBars / size.width
                    viewport.startIndex -= pan.x * barsPerPx

                    // Zoom: pinch changes the number of visible bars (toward center)
                    if (zoom != 1f) {
                        val center = viewport.startIndex + viewport.visibleBars / 2f
                        viewport.visibleBars = (viewport.visibleBars / zoom)
                        val ratio = 1f / zoom
                        viewport.startIndex = center - (center - viewport.startIndex) * ratio
                    }

                    viewport.clamp(candles.size)
                    viewport.autoScale(candles)
                    invalidateTick++
                }
            },
    ) {
        @Suppress("UNUSED_EXPRESSION") invalidateTick // read to subscribe to changes
        if (candles.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height

        // Grid lines aligned to "nice" round price levels (institutional look)
        // rather than arbitrary geometric divisions.
        val step = viewport.niceStep(5)
        if (step > 0.0) {
            var level = ceil(viewport.priceLow / step) * step
            while (level <= viewport.priceHigh) {
                val y = viewport.yForPrice(level, h)
                drawLine(
                    color = FoxNeutral20,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
                level += step
            }
        }

        // --- Viewport culling: only draw visible candles ---
        val start = max(0, viewport.startIndex.toInt())
        val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
        val barWidth = viewport.barWidthPx(w)
        val bodyWidth = (barWidth * 0.7f).coerceAtLeast(1f)

        for (i in start until end) {
            val c = candles[i]
            val cx = viewport.xForIndex(i + 0.5f, w)
            val color = if (c.isBullish) FoxBullish else FoxBearish

            // Wick (high → low)
            drawLine(
                color = color,
                start = Offset(cx, viewport.yForPrice(c.high, h)),
                end = Offset(cx, viewport.yForPrice(c.low, h)),
                strokeWidth = max(1f, barWidth * 0.12f),
                cap = StrokeCap.Round,
            )

            // Body (open ↔ close)
            val yOpen = viewport.yForPrice(c.open, h)
            val yClose = viewport.yForPrice(c.close, h)
            val top = min(yOpen, yClose)
            val bodyH = max(1f, abs(yClose - yOpen))
            drawRect(
                color = color,
                topLeft = Offset(cx - bodyWidth / 2f, top),
                size = androidx.compose.ui.geometry.Size(bodyWidth, bodyH),
            )
        }

        // --- Live last-price reference line (dashed, in the trend colour) ---
        val last = candles[candles.size - 1]
        val lastY = viewport.yForPrice(last.close, h)
        if (lastY in 0f..h) {
            val lastColor = if (last.isBullish) FoxBullish else FoxBearish
            drawLine(
                color = lastColor.copy(alpha = 0.65f),
                start = Offset(0f, lastY),
                end = Offset(w, lastY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
    }
}
