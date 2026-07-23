package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.sdk.indicator.IndicatorSignal
import com.foxtrader.app.domain.sdk.indicator.SignalType
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators

/** RSI sub-panel indicator with overbought/oversold signal markers. */
class RsiIndicator(private val defaultPeriod: Int = 14) : Indicator {
    override val id = "rsi_$defaultPeriod"
    override val displayName = "RSI $defaultPeriod"
    override val description = "Relative Strength Index ($defaultPeriod)"
    override val isOverlay = false

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        val period = params["period"]?.toInt() ?: defaultPeriod
        if (candles.size < period + 1) return IndicatorResult(emptyMap())
        val rsi = TechnicalIndicators.calculateRSI(candles, period)
        val signals = mutableListOf<IndicatorSignal>()
        for (i in rsi.indices) {
            if (rsi[i] < 30.0) signals += IndicatorSignal(i, SignalType.BUY, candles[i].close, "Oversold")
            else if (rsi[i] > 70.0) signals += IndicatorSignal(i, SignalType.SELL, candles[i].close, "Overbought")
        }
        return IndicatorResult(mapOf("main" to rsi), signals)
    }
}
