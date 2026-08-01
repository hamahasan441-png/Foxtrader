package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.AbsorptionEvent
import com.foxtrader.app.domain.model.tradepro.OrderFlowBar
import kotlin.math.abs
import javax.inject.Inject

/**
 * Detects absorption: strong one-sided aggression that fails to move price, meaning a large passive
 * player is soaking it up. A reversal warning — traders pushing into it get trapped and their exits
 * fuel the opposite move.
 *
 * Heuristic (works on either real or candle-derived flow): a bar qualifies when its total volume is a
 * multiple of the recent average AND its net price progress is small relative to the recent average
 * range AND the flow is meaningfully one-sided. The absorbed side is the dominant-delta side; the
 * anticipated reversal is opposite to it.
 */
class AbsorptionDetector @Inject constructor() {

    fun detect(
        bars: List<OrderFlowBar>,
        lookback: Int = 20,
        volumeMultiple: Double = 1.8,
        maxProgressFraction: Double = 0.35,
        minDominance: Double = 0.15,
    ): List<AbsorptionEvent> {
        if (bars.size <= lookback || lookback < 1) return emptyList()
        val out = ArrayList<AbsorptionEvent>()
        for (i in lookback until bars.size) {
            val bar = bars[i]
            if (bar.totalVolume <= 0.0) continue
            val window = bars.subList(i - lookback, i)
            val avgVol = window.sumOf { it.totalVolume } / lookback
            val avgRange = window.sumOf { it.range } / lookback
            if (avgVol <= 0.0 || avgRange <= 0.0) continue

            val volMultiple = bar.totalVolume / avgVol
            val progressFraction = abs(bar.priceProgress) / avgRange
            val heavy = volMultiple >= volumeMultiple
            val stalled = progressFraction <= maxProgressFraction
            val oneSided = bar.dominance >= minDominance
            if (heavy && stalled && oneSided) {
                val absorbedSide = if (bar.delta >= 0.0) Direction.BULLISH else Direction.BEARISH
                val strength = ((volMultiple - 1.0) * (1.0 - progressFraction) * (0.5 + bar.dominance) * 40.0)
                    .coerceIn(0.0, 100.0)
                out += AbsorptionEvent(
                    index = bar.index,
                    timestamp = bar.timestamp,
                    price = bar.close,
                    absorbedSide = absorbedSide,
                    aggressionVolume = bar.totalVolume,
                    priceProgress = bar.priceProgress,
                    strength = strength,
                    detail = "Vol ${"%.1f".format(volMultiple)}x avg but only " +
                        "${"%.0f".format(progressFraction * 100)}% of avg range progress — " +
                        "$absorbedSide aggression absorbed.",
                )
            }
        }
        return out
    }
}
