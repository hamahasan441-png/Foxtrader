package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * Finite-safe, density-aware SMC renderers.
 *
 * Minimal mode reduces the number and opacity of recent zones instead of
 * disabling SMC. That keeps the chart readable and the render cost bounded
 * while preserving the semantic fact that the study is enabled.
 */

private val OB_BULLISH_COLOR = Color(0x2600C873)
private val OB_BEARISH_COLOR = Color(0x26E8364F)
private val OB_BULLISH_BORDER = Color(0x6600C873)
private val OB_BEARISH_BORDER = Color(0x66E8364F)
private const val OB_FILL_ALPHA = 0.14f
private const val OB_FILL_ALPHA_MITIGATED = 0.05f
private const val OB_BORDER_ALPHA = 0.45f
private const val OB_BORDER_ALPHA_MITIGATED = 0.18f
private const val ZONE_PROJECT_BARS = 60

fun DrawScope.drawOrderBlocks(
    orderBlocks: List<OrderBlock>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    intensity: Float = 1f,
) {
    if (!cw.isDrawableSpan() || !ch.isDrawableSpan()) return
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1
    val visualIntensity = intensity.smcIntensity()
    val first = (orderBlocks.size - smcCap(visualIntensity, 8, 20, 40)).coerceAtLeast(0)

    for (i in first until orderBlocks.size) {
        val ob = orderBlocks[i]
        if (ob.startIndex < 0 || ob.endIndex < ob.startIndex) continue
        if (!ob.highPrice.isDrawablePrice() || !ob.lowPrice.isDrawablePrice() || ob.highPrice <= ob.lowPrice) continue
        if (ob.endIndex < startIdx || ob.startIndex > endIdx) continue

        val xSpan = horizontalSpan(
            viewport.xForIndex(ob.startIndex.toFloat(), cw),
            viewport.xForIndex(ob.endIndex.toFloat(), cw),
            cw,
        ) ?: continue
        val ySpan = verticalSpan(
            viewport.yForPrice(ob.highPrice, ch),
            viewport.yForPrice(ob.lowPrice, ch),
            ch,
        ) ?: continue

        val fillAlpha = ((if (ob.mitigated) OB_FILL_ALPHA_MITIGATED else OB_FILL_ALPHA) * visualIntensity)
            .coerceIn(0f, 0.22f)
        val borderAlpha = ((if (ob.mitigated) OB_BORDER_ALPHA_MITIGATED else OB_BORDER_ALPHA) * visualIntensity)
            .coerceIn(0f, 0.72f)
        val fillColor = when (ob.type) {
            OrderBlockType.BULLISH -> OB_BULLISH_COLOR
            OrderBlockType.BEARISH -> OB_BEARISH_COLOR
        }.copy(alpha = fillAlpha)
        val borderColor = when (ob.type) {
            OrderBlockType.BULLISH -> OB_BULLISH_BORDER
            OrderBlockType.BEARISH -> OB_BEARISH_BORDER
        }.copy(alpha = borderAlpha)

        drawRect(
            color = fillColor,
            topLeft = Offset(xSpan.first, ySpan.first),
            size = Size(xSpan.second - xSpan.first, ySpan.second - ySpan.first),
        )
        drawLine(borderColor, Offset(xSpan.first, ySpan.first), Offset(xSpan.second, ySpan.first), 1f)
        drawLine(borderColor, Offset(xSpan.first, ySpan.second), Offset(xSpan.second, ySpan.second), 1f)
    }
}

private val FVG_BULLISH_COLOR = Color(0x2000C873)
private val FVG_BEARISH_COLOR = Color(0x20E8364F)
private val FVG_BULLISH_BORDER = Color(0x5000C873)
private val FVG_BEARISH_BORDER = Color(0x50E8364F)
private val FvgDash = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))

fun DrawScope.drawFairValueGaps(
    gaps: List<FairValueGap>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    intensity: Float = 1f,
) {
    if (!cw.isDrawableSpan() || !ch.isDrawableSpan()) return
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1
    val visualIntensity = intensity.smcIntensity()
    val first = (gaps.size - smcCap(visualIntensity, 8, 20, 40)).coerceAtLeast(0)

    for (i in first until gaps.size) {
        val gap = gaps[i]
        if (gap.filled || gap.index < 0) continue
        if (!gap.highPrice.isDrawablePrice() || !gap.lowPrice.isDrawablePrice() || gap.highPrice <= gap.lowPrice) continue
        if (gap.index < startIdx - 50 || gap.index > endIdx) continue

        val xSpan = horizontalSpan(
            viewport.xForIndex(gap.index.toFloat(), cw),
            viewport.xForIndex((gap.index + ZONE_PROJECT_BARS).toFloat(), cw),
            cw,
        ) ?: continue
        val ySpan = verticalSpan(
            viewport.yForPrice(gap.highPrice, ch),
            viewport.yForPrice(gap.lowPrice, ch),
            ch,
        ) ?: continue

        val fillBase = if (gap.type == FvgType.BULLISH) FVG_BULLISH_COLOR else FVG_BEARISH_COLOR
        val borderBase = if (gap.type == FvgType.BULLISH) FVG_BULLISH_BORDER else FVG_BEARISH_BORDER
        val fillColor = fillBase.copy(alpha = (fillBase.alpha * visualIntensity).coerceIn(0f, 0.18f))
        val borderColor = borderBase.copy(alpha = (borderBase.alpha * visualIntensity).coerceIn(0f, 0.55f))

        drawRect(
            color = fillColor,
            topLeft = Offset(xSpan.first, ySpan.first),
            size = Size(xSpan.second - xSpan.first, ySpan.second - ySpan.first),
        )
        drawLine(
            color = borderColor,
            start = Offset(xSpan.first, ySpan.first),
            end = Offset(xSpan.second, ySpan.first),
            strokeWidth = 0.8f,
            pathEffect = FvgDash,
        )
        drawLine(
            color = borderColor,
            start = Offset(xSpan.first, ySpan.second),
            end = Offset(xSpan.second, ySpan.second),
            strokeWidth = 0.8f,
            pathEffect = FvgDash,
        )
    }
}

private val LIQ_BUY_COLOR = Color(0xCC3B8DF0)
private val LIQ_SELL_COLOR = Color(0xCCE6A030)
private const val LIQ_SWEPT_ALPHA = 0.3f
private val LiquidityDash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))

fun DrawScope.drawLiquidityPools(
    pools: List<LiquidityPool>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    intensity: Float = 1f,
) {
    if (!cw.isDrawableSpan() || !ch.isDrawableSpan()) return
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1
    val visualIntensity = intensity.smcIntensity()
    val first = (pools.size - smcCap(visualIntensity, 10, 24, 48)).coerceAtLeast(0)

    for (i in first until pools.size) {
        val pool = pools[i]
        if (pool.startIndex < 0 || pool.endIndex < pool.startIndex || !pool.price.isDrawablePrice()) continue
        if (pool.endIndex < startIdx || pool.startIndex > endIdx) continue

        val xSpan = horizontalSpan(
            viewport.xForIndex(pool.startIndex.toFloat(), cw),
            viewport.xForIndex((pool.endIndex + 10).toFloat(), cw),
            cw,
        ) ?: continue
        val y = viewport.yForPrice(pool.price, ch)
        if (!y.isFinite() || y < 0f || y > ch) continue

        val alpha = ((if (pool.swept) LIQ_SWEPT_ALPHA else 1f) * visualIntensity).coerceIn(0f, 1f)
        val color = when (pool.type) {
            LiquidityType.BUY_SIDE -> LIQ_BUY_COLOR
            LiquidityType.SELL_SIDE -> LIQ_SELL_COLOR
        }.copy(alpha = alpha)

        drawLine(
            color = color,
            start = Offset(xSpan.first, y),
            end = Offset(xSpan.second, y),
            strokeWidth = if (visualIntensity <= 0.55f) 1f else 1.2f,
            pathEffect = LiquidityDash,
        )
        if (visualIntensity > 0.55f) {
            drawCircle(color = color, radius = 2.5f, center = Offset(xSpan.first, y))
            drawCircle(color = color, radius = 2.5f, center = Offset(xSpan.second, y))
        }

        val sweepIndex = pool.sweepIndex
        if (pool.swept && sweepIndex != null && sweepIndex >= 0) {
            val sweepX = viewport.xForIndex(sweepIndex.toFloat(), cw)
            if (sweepX.isFinite() && sweepX in 0f..cw) {
                drawCircle(color = Color(0xFFFF5722).copy(alpha = 0.72f), radius = 4f, center = Offset(sweepX, y))
            }
        }
    }
}

private const val SESSION_BAND_ALPHA = 0.05f

fun DrawScope.drawSessionBackgrounds(
    sessions: List<SessionRange>,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    intensity: Float = 1f,
) {
    if (!cw.isDrawableSpan() || !ch.isDrawableSpan()) return
    val startIdx = max(0, viewport.startIndex.toInt())
    val endIdx = (viewport.startIndex + viewport.visibleBars).toInt() + 1
    val alpha = (SESSION_BAND_ALPHA * intensity.smcIntensity()).coerceIn(0f, 0.07f)

    for (session in sessions) {
        if (session.startIndex < 0 || session.endIndex < session.startIndex) continue
        if (session.endIndex < startIdx || session.startIndex > endIdx) continue
        val xSpan = horizontalSpan(
            viewport.xForIndex(session.startIndex.toFloat(), cw),
            viewport.xForIndex(session.endIndex.toFloat(), cw),
            cw,
        ) ?: continue
        val color = Color(session.session.color).copy(alpha = alpha)
        drawRect(
            color = color,
            topLeft = Offset(xSpan.first, 0f),
            size = Size(xSpan.second - xSpan.first, ch),
        )
    }
}

private val VP_BUY_COLOR = Color(0x6600C873)
private val VP_SELL_COLOR = Color(0x66E8364F)
private val VP_POC_COLOR = Color(0xCCD4A84E)
private val ValueAreaDash = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))

fun DrawScope.drawVolumeProfile(
    profile: VolumeProfile,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
) {
    if (profile.levels.isEmpty() || !cw.isDrawableSpan() || !ch.isDrawableSpan()) return
    val validLevels = profile.levels.filter {
        it.priceLevel.isDrawablePrice() && it.volume.isFinite() && it.volume >= 0.0 &&
            it.buyVolume.isFinite() && it.buyVolume >= 0.0
    }
    if (validLevels.isEmpty()) return

    val maxVol = validLevels.maxOf { it.volume }
    if (!maxVol.isFinite() || maxVol <= 0.0) return
    val maxBarWidth = cw * 0.15f
    if (!maxBarWidth.isDrawableSpan()) return

    for (level in validLevels) {
        val y = viewport.yForPrice(level.priceLevel, ch)
        if (!y.isFinite() || y < 0f || y > ch) continue

        val totalWidth = (level.volume / maxVol * maxBarWidth).toFloat()
        if (!totalWidth.isFinite() || totalWidth < 0f) continue
        val buyRatio = if (level.volume > 0.0) (level.buyVolume / level.volume).coerceIn(0.0, 1.0) else 0.0
        val buyWidth = (buyRatio * totalWidth).toFloat()
        val sellWidth = (totalWidth - buyWidth).coerceAtLeast(0f)
        val barHeight = (ch / validLevels.size * 0.8f).coerceIn(0.5f, 8f)
        val baseX = cw - 4f

        if (buyWidth > 0.5f) {
            drawRect(
                color = VP_BUY_COLOR,
                topLeft = Offset(baseX - buyWidth, y - barHeight / 2f),
                size = Size(buyWidth, barHeight),
            )
        }
        if (sellWidth > 0.5f) {
            drawRect(
                color = VP_SELL_COLOR,
                topLeft = Offset(baseX - totalWidth, y - barHeight / 2f),
                size = Size(sellWidth, barHeight),
            )
        }
    }

    drawProfileLevel(profile.pocPrice, viewport, cw, ch, VP_POC_COLOR, 1.5f, null)
    val vaColor = Color(0x40D4A84E)
    drawProfileLevel(profile.vahPrice, viewport, cw, ch, vaColor, 0.8f, ValueAreaDash)
    drawProfileLevel(profile.valPrice, viewport, cw, ch, vaColor, 0.8f, ValueAreaDash)
}

private fun DrawScope.drawProfileLevel(
    price: Double,
    viewport: ChartViewport,
    cw: Float,
    ch: Float,
    color: Color,
    strokeWidth: Float,
    pathEffect: PathEffect?,
) {
    if (!price.isDrawablePrice()) return
    val y = viewport.yForPrice(price, ch)
    if (!y.isFinite() || y !in 0f..ch) return
    drawLine(
        color = color,
        start = Offset(cw * 0.7f, y),
        end = Offset(cw, y),
        strokeWidth = strokeWidth,
        pathEffect = pathEffect,
    )
}

private fun horizontalSpan(a: Float, b: Float, width: Float): Pair<Float, Float>? {
    if (!a.isFinite() || !b.isFinite() || !width.isDrawableSpan()) return null
    val left = min(a, b).coerceIn(0f, width)
    val right = max(a, b).coerceIn(0f, width)
    return if (right - left > 0.25f) left to right else null
}

private fun verticalSpan(a: Float, b: Float, height: Float): Pair<Float, Float>? {
    if (!a.isFinite() || !b.isFinite() || !height.isDrawableSpan()) return null
    val top = min(a, b).coerceIn(0f, height)
    val bottom = max(a, b).coerceIn(0f, height)
    return if (bottom - top > 0.25f) top to bottom else null
}

private fun Float.smcIntensity(): Float = when {
    !isFinite() -> 1f
    this < 0.25f -> 0.25f
    this > 1.5f -> 1.5f
    else -> this
}

private fun smcCap(intensity: Float, minimal: Int, professional: Int, full: Int): Int = when {
    intensity <= 0.60f -> minimal
    intensity <= 1.10f -> professional
    else -> full
}

private fun Double.isDrawablePrice(): Boolean = isFinite() && this > 0.0
private fun Float.isDrawableSpan(): Boolean = isFinite() && this > 0f
