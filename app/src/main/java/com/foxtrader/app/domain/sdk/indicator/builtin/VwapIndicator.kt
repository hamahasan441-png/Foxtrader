package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators

/**
 * VWAP (Volume-Weighted Average Price) SDK indicator adapter.
 *
 * No params (VWAP uses cumulative session data from candle index 0).
 * Single series: "main".
 * isOverlay = true: drawn on the price chart.
 */
class VwapIndicator : Indicator {
    override val id = "vwap"
    override val displayName = "VWAP"
    override val description = "Volume-Weighted Average Price — intraday fair value reference"
    override val isOverlay = true

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        if (candles.isEmpty()) return IndicatorResult(emptyMap())
        return IndicatorResult(series = mapOf("main" to TechnicalIndicators.calculateVWAP(candles)))
    }
}
