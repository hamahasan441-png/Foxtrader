package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators

/**
 * MACD (Moving Average Convergence Divergence) SDK indicator adapter.
 *
 * Params: "fast" (default 12), "slow" (default 26), "signal" (default 9).
 * Series: "macd" (MACD line), "signal" (signal line), "histogram".
 * isOverlay = false: displayed in a sub-panel below the chart.
 */
class MacdIndicator : Indicator {
    override val id = "macd"
    override val displayName = "MACD"
    override val description = "MACD (12,26,9) momentum and trend indicator"
    override val isOverlay = false

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        val fast = params["fast"]?.toInt() ?: 12
        val slow = params["slow"]?.toInt() ?: 26
        val signal = params["signal"]?.toInt() ?: 9
        if (candles.size < slow + signal) return IndicatorResult(emptyMap())
        val result = TechnicalIndicators.calculateMACD(candles, fast, slow, signal)
        return IndicatorResult(
            series = mapOf(
                "macd" to result.macd,
                "signal" to result.signal,
                "histogram" to result.histogram,
            )
        )
    }
}
