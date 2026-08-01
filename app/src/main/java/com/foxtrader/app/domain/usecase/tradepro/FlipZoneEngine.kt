package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.MarketStructure
import com.foxtrader.app.domain.model.SwingPoint
import com.foxtrader.app.domain.model.tradepro.FlipZone
import com.foxtrader.app.domain.model.tradepro.FlipZoneKind
import javax.inject.Inject

/**
 * Computes the Flip Zone — the single bias-defining line for the session — from confirmed market
 * structure.
 *
 * Bullish structure: the Flip Zone is the last defended *higher-low* (a swing low above the prior swing
 * low). Bearish structure: the last defended *lower-high*. The zone is derived purely from confirmed
 * swings, so it moves only when new structure forms — it is never nudged for comfort. When structure is
 * neutral or there aren't enough swings, there is no valid Flip Zone (returns null).
 */
class FlipZoneEngine @Inject constructor() {

    fun compute(structure: MarketStructure): FlipZone? = when (structure.bias) {
        Bias.BULLISH -> lastHigherLow(structure.swingLows)?.let {
            FlipZone(
                price = it.price,
                bias = Bias.BULLISH,
                kind = FlipZoneKind.LAST_HIGHER_LOW,
                anchorIndex = it.index,
                anchorTimestamp = it.timestamp,
            )
        }
        Bias.BEARISH -> lastLowerHigh(structure.swingHighs)?.let {
            FlipZone(
                price = it.price,
                bias = Bias.BEARISH,
                kind = FlipZoneKind.LAST_LOWER_HIGH,
                anchorIndex = it.index,
                anchorTimestamp = it.timestamp,
            )
        }
        Bias.NEUTRAL -> null
    }

    /** The most recent swing low that is higher than the swing low before it; fallback to the last low. */
    private fun lastHigherLow(swingLows: List<SwingPoint>): SwingPoint? {
        if (swingLows.isEmpty()) return null
        val ordered = swingLows.sortedBy { it.index }
        var result: SwingPoint? = null
        for (i in 1 until ordered.size) {
            if (ordered[i].price > ordered[i - 1].price) result = ordered[i]
        }
        return result ?: ordered.last()
    }

    /** The most recent swing high that is lower than the swing high before it; fallback to the last high. */
    private fun lastLowerHigh(swingHighs: List<SwingPoint>): SwingPoint? {
        if (swingHighs.isEmpty()) return null
        val ordered = swingHighs.sortedBy { it.index }
        var result: SwingPoint? = null
        for (i in 1 until ordered.size) {
            if (ordered[i].price < ordered[i - 1].price) result = ordered[i]
        }
        return result ?: ordered.last()
    }
}
