package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import kotlin.math.sqrt

/** Bollinger Bands overlay (middle + upper + lower). */
class BollingerIndicator : Indicator {
    override val id = "bollinger"
    override val displayName = "Bollinger Bands"
    override val description = "20-period SMA ± 2 standard deviations"
    override val isOverlay = true

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        val period = params["period"]?.toInt() ?: 20
        val mult = params["multiplier"] ?: 2.0
        if (candles.size < period) return IndicatorResult(emptyMap())
        val sma = TechnicalIndicators.calculateSMA(candles, period)
        val upper = DoubleArray(candles.size)
        val lower = DoubleArray(candles.size)
        for (i in period - 1 until candles.size) {
            var sum = 0.0
            for (j in i - period + 1..i) { val d = candles[j].close - sma[i]; sum += d * d }
            val std = sqrt(sum / period)
            upper[i] = sma[i] + mult * std
            lower[i] = sma[i] - mult * std
        }
        return IndicatorResult(mapOf("middle" to sma, "upper" to upper, "lower" to lower))
    }
}
