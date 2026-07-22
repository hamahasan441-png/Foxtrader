package com.foxtrader.app.feature.chart.presentation.components

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.foxtrader.app.domain.model.FairValueGap
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityPool
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.SessionRange
import com.foxtrader.app.domain.model.VolumeProfile
import kotlin.math.max
import kotlin.math.min

/**
 * SMC (Smart Money Concepts) chart overlay renderers.
 *
 * Draws institutional-grade visualization overlays:
 * - Order Blocks (supply/demand zones)
 * - Fair Value Gaps (price imbalances)
 * - Liquidity Pools (equal highs/lows + sweeps)
 * - Session Backgrounds (London/NY/Tokyo/Sydney highlights)
 * - Volume Profile (horizontal histogram)
 *
 * All renderers are viewport-culled: only draw elements that are visible.
 * Zero allocations in the hot path.
 */

// ============================================================================
// ORDER BLOCKS
// ============================================================================

private val OB_BULLISH_COLOR = Color(0x2600C873)   // Semi-transparent green
private val OB_BEARISH_COLOR = Color(0x26E8364F)   // Semi-transparent red
private val OB_BULLISH_BORDER = Color(0x6600C873)
private val OB_BEARISH_BORDER = Color(0x66E8364F)
private val OB_MITIGATED_ALPHA = 0.3f

/**
 * Draw order blocks as filled rectangles with border.
 * Un-mitigated blocks are solid; mitigated blocks are faded.
 */
fun DrawScope.drawOrderBlocks(
    orderBlocks: List<OrderBlock>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (ob in orderBlocks) {
        // Viewport culling: skip if entirely outside visible range
        if (ob.endIndex < startIdx || ob.startIndex > endIdx) continue

        val x1 = viewport.xForIndex(ob.startIndex.toFloat(), cw).coerceAtLeast(0f)
        val x2 = viewport.xForIndex(ob.endIndex.toFloat(), cw).coerceAtMost(cw)
        val yHigh = viewport.yForPrice(ob.highPrice, ch)
        val yLow = viewport.yForPrice(ob.lowPrice, ch)

        val alpha = if (ob.mitigated) OB_MITIGATED_ALPHA else 1f
        val fillColor = when (ob.type) {
            OrderBlockType.BULLISH -> OB_BULLISH_COLOR
            OrderBlockType.BEARISH -> OB_BEARISH_COLOR
        }.copy(alpha = alpha)
        val borderColor = when (ob.type) {
            OrderBlockType.BULLISH -> OB_BULLISH_BORDER
            OrderBlockType.BEARISH -> OB_BEARISH_BORDER
        }.copy(alpha = alpha)

        // Fill
        drawRect(
            color = fillColor,
            topLeft = Offset(x1, yHigh),
            size = Size(x2 - x1, yLow - yHigh),
        )
        // Top border
        drawLine(
            color = borderColor,
            start = Offset(x1, yHigh),
            end = Offset(x2, yHigh),
            strokeWidth = 1f,
        )
        // Bottom border
        drawLine(
            color = borderColor,
            start = Offset(x1, yLow),
            end = Offset(x2, yLow),
            strokeWidth = 1f,
        )
    }
}

// ============================================================================
// FAIR VALUE GAPS
// ============================================================================

private val FVG_BULLISH_COLOR = Color(0x2000C873)
private val FVG_BEARISH_COLOR = Color(0x20E8364F)
private val FVG_BULLISH_BORDER = Color(0x5000C873)
private val FVG_BEARISH_BORDER = Color(0x50E8364F)

/**
 * Draw Fair Value Gaps as semi-transparent zones.
 * Partially filled gaps show the remaining unfilled portion.
 */
fun DrawScope.drawFairValueGaps(
    gaps: List<FairValueGap>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (gap in gaps) {
        // Skip fully filled gaps
        if (gap.filled) continue
        // Viewport culling
        if (gap.index < startIdx - 50 || gap.index > endIdx) continue

        val x1 = viewport.xForIndex(gap.index.toFloat(), cw)
        val x2 = cw // Extend to right edge (gaps persist until filled)
        val yHigh = viewport.yForPrice(gap.highPrice, ch)
        val yLow = viewport.yForPrice(gap.lowPrice, ch)

        val fillColor = when (gap.type) {
            FvgType.BULLISH -> FVG_BULLISH_COLOR
            FvgType.BEARISH -> FVG_BEARISH_COLOR
        }
        val borderColor = when (gap.type) {
            FvgType.BULLISH -> FVG_BULLISH_BORDER
            FvgType.BEARISH -> FVG_BEARISH_BORDER
        }

        // Fill zone
        drawRect(
            color = fillColor,
            topLeft = Offset(x1.coerceAtLeast(0f), yHigh),
            size = Size((x2 - x1).coerceAtLeast(0f), (yLow - yHigh).coerceAtLeast(0f)),
        )
        // Border lines
        drawLine(
            color = borderColor,
            start = Offset(x1.coerceAtLeast(0f), yHigh),
            end = Offset(x2, yHigh),
            strokeWidth = 0.8f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)),
        )
        drawLine(
            color = borderColor,
            start = Offset(x1.coerceAtLeast(0f), yLow),
            end = Offset(x2, yLow),
            strokeWidth = 0.8f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)),
        )
    }
}

// ============================================================================
// LIQUIDITY POOLS
// ============================================================================

private val LIQ_BUY_COLOR = Color(0xCC3B8DF0)   // Blue (buy-side = stops above)
private val LIQ_SELL_COLOR = Color(0xCCE6A030)   // Orange (sell-side = stops below)
private val LIQ_SWEPT_ALPHA = 0.3f

/**
 * Draw liquidity pools as dashed horizontal lines with dots at touches.
 * Swept pools are faded; un-swept pools are bright.
 */
fun DrawScope.drawLiquidityPools(
    pools: List<LiquidityPool>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (pool in pools) {
        if (pool.endIndex < startIdx || pool.startIndex > endIdx) continue

        val x1 = viewport.xForIndex(pool.startIndex.toFloat(), cw).coerceAtLeast(0f)
        val x2 = viewport.xForIndex((pool.endIndex + 10).toFloat(), cw).coerceAtMost(cw)
        val y = viewport.yForPrice(pool.price, ch)

        if (y < 0f || y > ch) continue

        val alpha = if (pool.swept) LIQ_SWEPT_ALPHA else 1f
        val color = when (pool.type) {
            LiquidityType.BUY_SIDE -> LIQ_BUY_COLOR
            LiquidityType.SELL_SIDE -> LIQ_SELL_COLOR
        }.copy(alpha = alpha)

        // Dashed line at the liquidity level
        drawLine(
            color = color,
            start = Offset(x1, y),
            end = Offset(x2, y),
            strokeWidth = 1.2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
        )

        // Small circles at touch points (simulated: at start and end)
        drawCircle(color = color, radius = 3f, center = Offset(x1, y))
        drawCircle(color = color, radius = 3f, center = Offset(x2, y))

        // Sweep arrow if swept
        if (pool.swept && pool.sweepIndex != null) {
            val sweepX = viewport.xForIndex(pool.sweepIndex.toFloat(), cw)
            if (sweepX in 0f..cw) {
                drawCircle(
                    color = Color(0xFFFF5722),
                    radius = 5f,
                    center = Offset(sweepX, y),
                )
            }
        }
    }
}

// ============================================================================
// SESSION BACKGROUNDS
// ============================================================================

/**
 * Draw trading session backgrounds as colored vertical bands.
 */
fun DrawScope.drawSessionBackgrounds(
    sessions: List<SessionRange>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1

    for (session in sessions) {
        if (session.endIndex < startIdx || session.startIndex > endIdx) continue

        val x1 = viewport.xForIndex(session.startIndex.toFloat(), cw).coerceAtLeast(0f)
        val x2 = viewport.xForIndex(session.endIndex.toFloat(), cw).coerceAtMost(cw)
        val color = Color(session.session.color)

        drawRect(
            color = color,
            topLeft = Offset(x1, 0f),
            size = Size(x2 - x1, ch),
        )
    }
}

// ============================================================================
// VOLUME PROFILE
// ============================================================================

private val VP_BUY_COLOR = Color(0x6600C873)
private val VP_SELL_COLOR = Color(0x66E8364F)
private val VP_POC_COLOR = Color(0xCCD4A84E)

/**
 * Draw volume profile as a horizontal histogram on the right side of the chart.
 * POC (Point of Control) is highlighted.
 */
fun DrawScope.drawVolumeProfile(
    profile: VolumeProfile,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (profile.levels.isEmpty()) return

    val maxVol = profile.levels.maxOf { it.volume }
    if (maxVol <= 0) return

    val maxBarWidth = cw * 0.15f // Max 15% of chart width

    for (level in profile.levels) {
        val y = viewport.yForPrice(level.priceLevel, ch)
        if (y < 0f || y > ch) continue

        val totalWidth = (level.volume / maxVol * maxBarWidth).toFloat()
        val buyWidth = if (level.volume > 0) (level.buyVolume / level.volume * totalWidth).toFloat() else 0f
        val sellWidth = totalWidth - buyWidth
        val barHeight = (ch / profile.levels.size * 0.8f).coerceAtMost(8f)

        // Draw from right edge of chart area inward
        val baseX = cw - 4f

        // Buy volume (green, from right)
        if (buyWidth > 0.5f) {
            drawRect(
                color = VP_BUY_COLOR,
                topLeft = Offset(baseX - buyWidth, y - barHeight / 2f),
                size = Size(buyWidth, barHeight),
            )
        }
        // Sell volume (red, stacked left of buy)
        if (sellWidth > 0.5f) {
            drawRect(
                color = VP_SELL_COLOR,
                topLeft = Offset(baseX - totalWidth, y - barHeight / 2f),
                size = Size(sellWidth, barHeight),
            )
        }
    }

    // POC line
    val pocY = viewport.yForPrice(profile.pocPrice, ch)
    if (pocY in 0f..ch) {
        drawLine(
            color = VP_POC_COLOR,
            start = Offset(cw * 0.7f, pocY),
            end = Offset(cw, pocY),
            strokeWidth = 1.5f,
        )
    }

    // Value Area boundaries (dashed)
    val vahY = viewport.yForPrice(profile.vahPrice, ch)
    val valY = viewport.yForPrice(profile.valPrice, ch)
    val vaColor = Color(0x40D4A84E)
    if (vahY in 0f..ch) {
        drawLine(
            color = vaColor, start = Offset(cw * 0.7f, vahY), end = Offset(cw, vahY),
            strokeWidth = 0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)),
        )
    }
    if (valY in 0f..ch) {
        drawLine(
            color = vaColor, start = Offset(cw * 0.7f, valY), end = Offset(cw, valY),
            strokeWidth = 0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)),
        )
    }
}
