package com.foxtrader.app.domain.sdk.indicator.builtin

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud

/**
 * Ichimoku Kinko Hyo SDK indicator adapter.
 *
 * Params: "tenkan" (default 9), "kijun" (default 26), "senkouB" (default 52).
 * Series: "tenkan", "kijun", "senkouA", "senkouB", "chikou".
 * isOverlay = true: all lines are drawn on the price chart.
 */
class IchimokuIndicator : Indicator {
    private val impl = IchimokuCloud()

    override val id = "ichimoku"
    override val displayName = "Ichimoku Cloud"
    override val description = "Ichimoku Kinko Hyo — complete trend, momentum and support/resistance system"
    override val isOverlay = true

    override fun compute(candles: List<Candle>, params: Map<String, Double>): IndicatorResult {
        val tenkan = params["tenkan"]?.toInt() ?: 9
        val kijun = params["kijun"]?.toInt() ?: 26
        val senkouB = params["senkouB"]?.toInt() ?: 52
        if (candles.size < senkouB) return IndicatorResult(emptyMap())
        val result = impl.calculate(candles, tenkan, kijun, senkouB)
        return IndicatorResult(
            series = mapOf(
                "tenkan" to result.tenkan,
                "kijun" to result.kijun,
                "senkouA" to result.senkouA,
                "senkouB" to result.senkouB,
                "chikou" to result.chikou,
            )
        )
    }
}
