package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators

/**
 * ADX (Average Directional Index) SDK indicator adapter.
 *
 * Params: "period" (default 14).
 * Series: "adx" (trend strength 0–100), "plusDI" (+DI), "minusDI" (-DI).
 * isOverlay = false: displayed in a sub-panel (0–100 range).
 *
 * ADX > 25 → trending; ADX < 20 → ranging/sideways.
 */
class AdxIndicator : Indicator {
    override val id = "adx"
    override val displayName = "ADX"
    override val description = "Average Directional Index — trend strength (0–100)"
    override val isOverlay = false

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        val period = params["period"]?.toInt() ?: 14
        if (candles.size < period * 2) return IndicatorResult(emptyMap())
        val result = TechnicalIndicators.calculateADX(candles, period)
        return IndicatorResult(
            series = mapOf(
                "adx" to result.adx,
                "plusDI" to result.plusDI,
                "minusDI" to result.minusDI,
            )
        )
    }
}
