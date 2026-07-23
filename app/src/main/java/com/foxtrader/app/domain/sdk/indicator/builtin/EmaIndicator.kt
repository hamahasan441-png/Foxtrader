package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators

/** EMA overlay — configurable period via params["period"] (default 20). */
class EmaIndicator(private val defaultPeriod: Int = 20) : Indicator {
    override val id = "ema_$defaultPeriod"
    override val displayName = "EMA $defaultPeriod"
    override val description = "Exponential Moving Average ($defaultPeriod period)"
    override val isOverlay = true

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        val period = params["period"]?.toInt() ?: defaultPeriod
        if (candles.size < period) return IndicatorResult(emptyMap())
        return IndicatorResult(mapOf("main" to TechnicalIndicators.calculateEMA(candles, period)))
    }
}
