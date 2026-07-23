package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.usecase.indicators.StochasticOscillator

/**
 * Stochastic Oscillator SDK indicator adapter.
 *
 * Params: "kPeriod" (default 14), "dPeriod" (default 3).
 * Series: "k" (%K line), "d" (%D signal line).
 * isOverlay = false: displayed in a sub-panel (0–100 range).
 */
class StochasticIndicator : Indicator {
    private val impl = StochasticOscillator()

    override val id = "stochastic"
    override val displayName = "Stochastic"
    override val description = "Stochastic Oscillator (%K/%D) — momentum indicator (0–100)"
    override val isOverlay = false

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        val kPeriod = params["kPeriod"]?.toInt() ?: 14
        val dPeriod = params["dPeriod"]?.toInt() ?: 3
        if (candles.size < kPeriod) return IndicatorResult(emptyMap())
        val result = impl.calculate(candles, kPeriod, dPeriod)
        return IndicatorResult(
            series = mapOf(
                "k" to result.percentK,
                "d" to result.percentD,
            )
        )
    }
}
