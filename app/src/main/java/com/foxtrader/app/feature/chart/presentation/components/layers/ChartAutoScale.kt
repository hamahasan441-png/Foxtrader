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
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.feature.chart.presentation.ImmutableIntSeries
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

// Auto-scale: fits the visible bars and overlays into the price window.
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure DrawScope extensions - no Compose state - which is
// what keeps them cheap enough for the 120fps budget.

internal fun autoScaleToVisibleContent(
    viewport: ChartViewport,
    candles: List<Candle>,
    emaShort: ImmutableDoubleSeries?,
    emaLong: ImmutableDoubleSeries?,
    bollingerUpper: ImmutableDoubleSeries?,
    bollingerMiddle: ImmutableDoubleSeries?,
    bollingerLower: ImmutableDoubleSeries?,
    superTrendValues: ImmutableDoubleSeries?,
    parabolicSar: ImmutableDoubleSeries?,
    vwap: ImmutableDoubleSeries?,
    ichimokuTenkan: ImmutableDoubleSeries?,
    ichimokuKijun: ImmutableDoubleSeries?,
    ichimokuSenkouA: ImmutableDoubleSeries?,
    ichimokuSenkouB: ImmutableDoubleSeries?,
    ichimokuChikou: ImmutableDoubleSeries?,
    anchoredVwap: ImmutableDoubleSeries? = null,
    anchoredVwapUpper: ImmutableDoubleSeries? = null,
    anchoredVwapLower: ImmutableDoubleSeries? = null,
    keltnerUpper: ImmutableDoubleSeries? = null,
    keltnerLower: ImmutableDoubleSeries? = null,
    donchianUpper: ImmutableDoubleSeries? = null,
    donchianLower: ImmutableDoubleSeries? = null,
    orderBlocks: List<com.foxtrader.app.domain.model.OrderBlock>,
    fairValueGaps: List<com.foxtrader.app.domain.model.FairValueGap>,
    liquidityPools: List<com.foxtrader.app.domain.model.LiquidityPool>,
    sessions: List<com.foxtrader.app.domain.model.SessionRange>,
    volumeProfile: com.foxtrader.app.domain.model.VolumeProfile?,
    pad: Double = 0.08,
) {
    if (candles.isEmpty()) return
    val start = max(0, viewport.startIndex.toInt())
    val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (start >= end) return

    var hi = Double.NEGATIVE_INFINITY
    var lo = Double.POSITIVE_INFINITY

    fun include(price: Double?) {
        if (price == null || price.isNaN() || price.isInfinite()) return
        if (price > hi) hi = price
        if (price < lo) lo = price
    }

    fun includeSeries(values: ImmutableDoubleSeries?) {
        if (values == null) return
        val seriesEnd = min(values.size, end)
        for (i in start until seriesEnd) include(values[i])
    }

    for (i in start until end) {
        include(candles[i].high)
        include(candles[i].low)
    }

    includeSeries(emaShort)
    includeSeries(emaLong)
    includeSeries(bollingerUpper)
    includeSeries(bollingerMiddle)
    includeSeries(bollingerLower)
    includeSeries(superTrendValues)
    includeSeries(parabolicSar)
    includeSeries(vwap)
    includeSeries(ichimokuTenkan)
    includeSeries(ichimokuKijun)
    includeSeries(ichimokuSenkouA)
    includeSeries(ichimokuSenkouB)
    includeSeries(ichimokuChikou)
    // Newer overlays (R8): a toggled-on channel or anchored VWAP band must pull
    // the price window with it, otherwise it renders off-screen and the user
    // reads it as "the indicator never appeared". `include` is NaN-safe, so the
    // anchored VWAP's pre-anchor NaN prefix is skipped naturally.
    includeSeries(anchoredVwap)
    includeSeries(anchoredVwapUpper)
    includeSeries(anchoredVwapLower)
    includeSeries(keltnerUpper)
    includeSeries(keltnerLower)
    includeSeries(donchianUpper)
    includeSeries(donchianLower)

    orderBlocks.forEach { block ->
        if (block.endIndex >= start && block.startIndex < end) {
            include(block.highPrice)
            include(block.lowPrice)
        }
    }
    fairValueGaps.forEach { gap ->
        if (gap.index in start until end) {
            include(gap.highPrice)
            include(gap.lowPrice)
        }
    }
    liquidityPools.forEach { pool ->
        if (pool.endIndex >= start && pool.startIndex < end) {
            include(pool.price)
        }
    }
    sessions.forEach { session ->
        if (session.endIndex >= start && session.startIndex < end) {
            include(session.highPrice)
            include(session.lowPrice)
        }
    }
    volumeProfile?.levels?.forEach { include(it.priceLevel) }

    if (hi == Double.NEGATIVE_INFINITY || lo == Double.POSITIVE_INFINITY) {
        viewport.autoScale(candles)
        return
    }

    val range = (hi - lo).coerceAtLeast(1e-9)
    val padding = range * pad
    viewport.priceHigh = hi + padding
    viewport.priceLow = lo - padding
}
