package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.HoldZone
import com.foxtrader.app.domain.model.tradepro.HoldZoneType
import com.foxtrader.app.domain.model.tradepro.Imbalance
import com.foxtrader.app.domain.model.tradepro.OrderFlowBar
import javax.inject.Inject

/**
 * Builds "Buy Hold" / "Sell Hold" zones by grouping *stacked* same-direction imbalances.
 *
 * A single imbalance is only information; stacked imbalances (multiple nearby bars, same side, little
 * opposition) show sustained commitment. This engine clusters them, draws a box around the cluster
 * (min low..max high of the member bars), scores its strength, and marks whether price has since
 * re-tested the zone and held (defended). These zones are where TRADEPRO waits for a pullback to enter.
 */
class HoldZoneEngine @Inject constructor() {

    fun build(
        bars: List<OrderFlowBar>,
        imbalances: List<Imbalance>,
        minStack: Int = 2,
        maxGap: Int = 1,
    ): List<HoldZone> {
        if (bars.isEmpty() || imbalances.size < minStack) return emptyList()
        val barByIndex = bars.associateBy { it.index }
        val ordered = imbalances.sortedBy { it.index }

        val zones = ArrayList<HoldZone>()
        var group = ArrayList<Imbalance>()

        fun flush() {
            if (group.size >= minStack) {
                buildZone(group, barByIndex, bars)?.let { zones += it }
            }
            group = ArrayList()
        }

        for (imb in ordered) {
            if (group.isEmpty()) {
                group.add(imb)
                continue
            }
            val last = group.last()
            val sameSide = imb.direction == last.direction
            val close = imb.index - last.index in 1..(maxGap + 1) || imb.index == last.index
            if (sameSide && close) {
                group.add(imb)
            } else {
                flush()
                group.add(imb)
            }
        }
        flush()
        return zones
    }

    private fun buildZone(
        group: List<Imbalance>,
        barByIndex: Map<Int, OrderFlowBar>,
        allBars: List<OrderFlowBar>,
    ): HoldZone? {
        val direction = group.first().direction
        val memberBars = group.mapNotNull { barByIndex[it.index] }
        if (memberBars.isEmpty()) return null

        val high = memberBars.maxOf { it.high }
        val low = memberBars.minOf { it.low }
        if (!high.isFinite() || !low.isFinite() || high < low) return null

        val startIndex = memberBars.minOf { it.index }
        val endIndex = memberBars.maxOf { it.index }
        val startBar = barByIndex[startIndex] ?: memberBars.first()
        val endBar = barByIndex[endIndex] ?: memberBars.last()

        val avgRatio = group.map { if (it.ratio.isFinite()) it.ratio else 6.0 }.average()
        val strength = (group.size * 12.0 + (avgRatio.coerceAtMost(6.0)) * 8.0).coerceIn(0.0, 100.0)

        val bull = direction == Direction.BULLISH
        val defended = allBars.any { bar ->
            bar.index > endIndex &&
                bar.low <= high && bar.high >= low && // traded into the zone
                (if (bull) bar.close > bar.open else bar.close < bar.open) // and reacted in-direction
        }

        return HoldZone(
            type = if (bull) HoldZoneType.BUY_HOLD else HoldZoneType.SELL_HOLD,
            high = high,
            low = low,
            startIndex = startIndex,
            endIndex = endIndex,
            startTimestamp = startBar.timestamp,
            endTimestamp = endBar.timestamp,
            stackedCount = group.size,
            strength = strength,
            defended = defended,
        )
    }
}
