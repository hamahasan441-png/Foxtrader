package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.Imbalance
import com.foxtrader.app.domain.model.tradepro.OrderFlowBar
import javax.inject.Inject

/**
 * Detects bar-level order-flow imbalances: one side's aggressive volume overwhelmingly exceeds the
 * other. A single imbalance is only information; stacked imbalances (see [HoldZoneEngine]) show
 * sustained commitment.
 */
class ImbalanceDetector @Inject constructor() {

    /**
     * @param ratio dominant volume must be >= [ratio] * opposing volume to qualify.
     * @param minVolume ignore bars whose total volume is below this (thin/dead bars carry no signal).
     */
    fun detect(bars: List<OrderFlowBar>, ratio: Double, minVolume: Double = 0.0): List<Imbalance> {
        if (bars.isEmpty() || ratio <= 0.0) return emptyList()
        val out = ArrayList<Imbalance>()
        for (bar in bars) {
            if (bar.totalVolume <= 0.0 || bar.totalVolume < minVolume) continue
            val buy = bar.buyVolume
            val sell = bar.sellVolume
            when {
                sell <= 0.0 && buy > 0.0 -> out += imbalance(bar, Direction.BULLISH, Double.POSITIVE_INFINITY)
                buy <= 0.0 && sell > 0.0 -> out += imbalance(bar, Direction.BEARISH, Double.POSITIVE_INFINITY)
                buy >= sell * ratio -> out += imbalance(bar, Direction.BULLISH, buy / sell)
                sell >= buy * ratio -> out += imbalance(bar, Direction.BEARISH, sell / buy)
            }
        }
        return out
    }

    private fun imbalance(bar: OrderFlowBar, direction: Direction, ratio: Double) = Imbalance(
        index = bar.index,
        timestamp = bar.timestamp,
        direction = direction,
        ratio = ratio,
        price = bar.close,
        volume = bar.totalVolume,
    )
}
