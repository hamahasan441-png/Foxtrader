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

// Auto-scale: fits the visible bars and overlays into the price window.
//
// Extracted from CandleChart.kt (Sprint 8.5). Functions are `internal` rather
// than `private` so the composable can call them across files; they remain
// module-private. Pure DrawScope extensions - no Compose state - which is
// what keeps them cheap enough for the 120fps budget.

internal fun autoScaleToVisibleContent(
    viewport: ChartViewport,
    candles: List<Candle>,
    emaShort: DoubleArray?,
    emaLong: DoubleArray?,
    bollingerUpper: DoubleArray?,
    bollingerMiddle: DoubleArray?,
    bollingerLower: DoubleArray?,
    superTrendValues: DoubleArray?,
    parabolicSar: DoubleArray?,
    vwap: DoubleArray?,
    ichimokuTenkan: DoubleArray?,
    ichimokuKijun: DoubleArray?,
    ichimokuSenkouA: DoubleArray?,
    ichimokuSenkouB: DoubleArray?,
    ichimokuChikou: DoubleArray?,
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

    fun includeSeries(values: DoubleArray?) {
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

    orderBlocks.filter { it.endIndex >= start && it.startIndex < end }.forEach {
        include(it.highPrice)
        include(it.lowPrice)
    }
    fairValueGaps.filter { it.index in start until end }.forEach {
        include(it.highPrice)
        include(it.lowPrice)
    }
    liquidityPools.filter { it.endIndex >= start && it.startIndex < end }.forEach {
        include(it.price)
    }
    sessions.filter { it.endIndex >= start && it.startIndex < end }.forEach {
        include(it.highPrice)
        include(it.lowPrice)
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
