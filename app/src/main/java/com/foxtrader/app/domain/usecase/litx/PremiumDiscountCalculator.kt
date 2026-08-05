package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.PremiumDiscountZone
import com.foxtrader.app.domain.model.PriceZoneKind
import javax.inject.Inject

/**
 * Computes the premium/discount (equilibrium) zoning of the current dealing
 * range. Longs are favoured from discount, shorts from premium.
 *
 * Thresholds (≥0.66 premium, ≤0.34 discount) match the existing classification
 * used privately in `MarketExplanationEngine`, promoted here to a reusable API.
 */
class PremiumDiscountCalculator @Inject constructor() {

    fun calculate(candles: List<Candle>, lookback: Int = 50): PremiumDiscountZone? {
        if (candles.size < MIN_BARS) return null
        val window = candles.takeLast(lookback)
        val high = window.maxOf { it.high }
        val low = window.minOf { it.low }
        val span = high - low
        if (span <= 0.0) return null

        val price = candles.last().close
        val pct = ((price - low) / span).coerceIn(0.0, 1.0)
        val zone = when {
            pct >= PREMIUM_THRESHOLD -> PriceZoneKind.PREMIUM
            pct <= DISCOUNT_THRESHOLD -> PriceZoneKind.DISCOUNT
            else -> PriceZoneKind.EQUILIBRIUM
        }
        return PremiumDiscountZone(
            rangeHigh = high,
            rangeLow = low,
            equilibrium = (high + low) / 2.0,
            currentZone = zone,
            currentPositionPct = pct,
        )
    }

    private companion object {
        const val MIN_BARS = 10
        const val PREMIUM_THRESHOLD = 0.66
        const val DISCOUNT_THRESHOLD = 0.34
    }
}
