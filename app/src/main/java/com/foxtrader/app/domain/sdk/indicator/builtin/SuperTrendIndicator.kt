package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.usecase.indicators.SuperTrend

/**
 * SuperTrend SDK indicator adapter.
 *
 * Params: "atrPeriod" (default 10), "multiplier" (default 3.0).
 * Exposes two series: "values" (the SuperTrend line) and "direction" (±1).
 * isOverlay = true: drawn directly on the price chart.
 */
class SuperTrendIndicator : Indicator {
    private val impl = SuperTrend()

    override val id = "supertrend"
    override val displayName = "SuperTrend"
    override val description = "ATR-based trend-following indicator (SuperTrend)"
    override val isOverlay = true

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        val atrPeriod = params["atrPeriod"]?.toInt() ?: 10
        val multiplier = params["multiplier"] ?: 3.0
        if (candles.size < atrPeriod + 1) return IndicatorResult(emptyMap())
        val result = impl.calculate(candles, atrPeriod, multiplier)
        return IndicatorResult(
            series = mapOf(
                "main" to result.values,
                "direction" to result.direction.map { it.toDouble() }.toDoubleArray(),
            )
        )
    }
}
