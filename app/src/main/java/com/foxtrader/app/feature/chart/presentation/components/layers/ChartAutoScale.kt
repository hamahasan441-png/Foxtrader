package com.foxtrader.app.feature.chart.presentation.components.layers

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.feature.chart.presentation.ImmutableDoubleSeries
import com.foxtrader.app.feature.chart.presentation.components.ChartViewport
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
    anchoredVwap: ImmutableDoubleSeries?,
    anchoredVwapUpper: ImmutableDoubleSeries?,
    anchoredVwapLower: ImmutableDoubleSeries?,
    ichimokuTenkan: ImmutableDoubleSeries?,
    ichimokuKijun: ImmutableDoubleSeries?,
    ichimokuSenkouA: ImmutableDoubleSeries?,
    ichimokuSenkouB: ImmutableDoubleSeries?,
    ichimokuChikou: ImmutableDoubleSeries?,
    keltnerUpper: ImmutableDoubleSeries?,
    keltnerMiddle: ImmutableDoubleSeries?,
    keltnerLower: ImmutableDoubleSeries?,
    donchianUpper: ImmutableDoubleSeries?,
    donchianMiddle: ImmutableDoubleSeries?,
    donchianLower: ImmutableDoubleSeries?,
    pivotLevels: com.foxtrader.app.domain.usecase.indicators.PivotPoints.PivotLevels?,
    orderBlocks: List<com.foxtrader.app.domain.model.OrderBlock>,
    fairValueGaps: List<com.foxtrader.app.domain.model.FairValueGap>,
    liquidityPools: List<com.foxtrader.app.domain.model.LiquidityPool>,
    sessions: List<com.foxtrader.app.domain.model.SessionRange>,
    volumeProfile: com.foxtrader.app.domain.model.VolumeProfile?,
    marketProfile: com.foxtrader.app.domain.usecase.analysis.MarketProfile.ProfileResult?,
    supportResistanceZones: List<com.foxtrader.app.domain.usecase.analysis.SupportResistanceDetector.SRZone>,
    autoFibLevels: List<com.foxtrader.app.domain.usecase.analysis.FibonacciEngine.FibLevel>,
    signals: List<ChartSignal>,
    pad: Double = 0.08,
) {
    if (candles.isEmpty()) return
    val start = max(0, viewport.startIndex.toInt())
    val end = min(candles.size, (viewport.startIndex + viewport.visibleBars).toInt() + 1)
    if (start >= end) return

    var hi = Double.NEGATIVE_INFINITY
    var lo = Double.POSITIVE_INFINITY

    fun include(price: Double?) {
        // Financial-price overlays must never inject NaN, infinity, or an
        // uninitialised zero into the camera range. One bad prefix value would
        // otherwise flatten real candles into the reported "two lines" view.
        if (price == null || !price.isFinite() || price <= 0.0) return
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
    includeSeries(anchoredVwap)
    includeSeries(anchoredVwapUpper)
    includeSeries(anchoredVwapLower)
    includeSeries(ichimokuTenkan)
    includeSeries(ichimokuKijun)
    includeSeries(ichimokuSenkouA)
    includeSeries(ichimokuSenkouB)
    includeSeries(ichimokuChikou)
    includeSeries(keltnerUpper)
    includeSeries(keltnerMiddle)
    includeSeries(keltnerLower)
    includeSeries(donchianUpper)
    includeSeries(donchianMiddle)
    includeSeries(donchianLower)

    pivotLevels?.let { levels ->
        include(levels.pivot)
        include(levels.r1)
        include(levels.r2)
        include(levels.r3)
        include(levels.s1)
        include(levels.s2)
        include(levels.s3)
    }

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
    marketProfile?.levels?.forEach { include(it.priceLevel) }
    supportResistanceZones.forEach { zone ->
        include(zone.upperBound)
        include(zone.lowerBound)
    }
    autoFibLevels.forEach { include(it.price) }
    signals.forEach { signal ->
        if (signal.barIndex in start until end) {
            include(signal.entry)
            // Historical markers do not render their old trade rays; including
            // every old SL/TP would unnecessarily squash current price action.
            if (signal.isLive) {
                if (signal.sl != 0.0) include(signal.sl)
                if (signal.tp != 0.0) include(signal.tp)
            }
        }
    }

    if (hi == Double.NEGATIVE_INFINITY || lo == Double.POSITIVE_INFINITY) {
        viewport.autoScale(candles)
        return
    }

    val range = (hi - lo).coerceAtLeast(1e-9)
    val padding = range * pad
    viewport.priceHigh = hi + padding
    viewport.priceLow = lo - padding
}
