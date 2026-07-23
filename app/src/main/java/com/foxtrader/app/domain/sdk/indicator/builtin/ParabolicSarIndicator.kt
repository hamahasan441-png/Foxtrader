package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.usecase.indicators.ParabolicSar

/**
 * Parabolic SAR SDK indicator adapter.
 *
 * Params: "step" (default 0.02), "maxStep" (default 0.2).
 * Single series: "main" — the SAR dot prices (NaN where below minimum bars).
 * isOverlay = true: drawn directly on the price chart.
 */
class ParabolicSarIndicator : Indicator {
    private val impl = ParabolicSar()

    override val id = "psar"
    override val displayName = "Parabolic SAR"
    override val description = "Parabolic Stop-and-Reverse momentum reversal indicator"
    override val isOverlay = true

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        val step = params["step"] ?: 0.02
        val maxStep = params["maxStep"] ?: 0.2
        if (candles.size < 2) return IndicatorResult(emptyMap())
        val result = impl.calculate(
            candles,
            accelerationStart = step,
            accelerationStep = step,
            accelerationMax = maxStep,
        )
        return IndicatorResult(series = mapOf("main" to result.sar))
    }
}
