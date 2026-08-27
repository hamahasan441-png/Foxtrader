package com.foxtrader.app.domain.usecase.virginwick

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.IfvgType
import com.foxtrader.app.domain.model.InversionFVG
import com.foxtrader.app.domain.usecase.virginwick.model.IfvgConfirmation
import com.foxtrader.app.domain.usecase.virginwick.model.VirginWickEntryType
import com.foxtrader.app.domain.usecase.virginwick.model.WickPoi

/**
 * Steps 3 and 4 — the return to the zone, and what confirms it.
 *
 * Price coming back into an untested wick is the setup, not the trade. On its
 * own it says only that the zone was reached; it says nothing about whether it
 * is holding. The inverted fair value gap is what distinguishes the two: a gap
 * that formed in the old direction and was then traded through and rejected is
 * the market's own admission that the move into the zone has failed.
 *
 * Everything is searched forward from the bar price first entered the zone.
 * Nothing later than the bar under consideration is ever read.
 */
class VirginWickEntryEngine {

    /** A confirmed entry on the execution series. */
    data class Entry(
        val type: VirginWickEntryType,
        val index: Int,
        val price: Double,
        val ifvg: IfvgConfirmation?,
        val reasons: List<String>,
    )

    /**
     * The first bar price traded far enough into [poi], at or after the POI
     * became active, or null if it never did within the search range.
     */
    fun touchIndex(
        candles: List<Candle>,
        poi: WickPoi,
        from: Int,
        to: Int,
        config: VirginWickConfig,
    ): Int? {
        if (from > to) return null
        val depth = poi.wick.height * config.poiEntryDepthFraction
        // Bullish POIs sit below price, so the return is a move down into them.
        val trigger = if (poi.direction == Direction.BULLISH) {
            poi.proximal - depth
        } else {
            poi.proximal + depth
        }

        for (i in from.coerceAtLeast(0)..minOf(to, candles.lastIndex)) {
            val reached = if (poi.direction == Direction.BULLISH) {
                candles[i].low <= trigger
            } else {
                candles[i].high >= trigger
            }
            if (reached) return i
        }
        return null
    }

    /**
     * Confirm the entry for a return that began at [touchIndex].
     *
     * @param inversions inverted fair value gaps over the execution series,
     *   supplied by the caller so one detection is shared across every POI.
     */
    fun confirm(
        candles: List<Candle>,
        poi: WickPoi,
        touchIndex: Int,
        inversions: List<InversionFVG>,
        config: VirginWickConfig,
    ): Entry? {
        val windowEnd = minOf(candles.lastIndex, touchIndex + config.confirmationWindowBars - 1)
        if (windowEnd < touchIndex) return null

        if (config.entryMode == EntryMode.POI_TOUCH) {
            return Entry(
                type = VirginWickEntryType.POI_TOUCH,
                index = touchIndex,
                price = candles[touchIndex].close,
                ifvg = null,
                reasons = listOf("Price returned to the untested wick"),
            )
        }

        val wanted = if (poi.direction == Direction.BULLISH) IfvgType.BULLISH else IfvgType.BEARISH
        val requireInside = config.entryMode == EntryMode.IFVG_IN_POI

        val candidate = inversions
            .asSequence()
            .filter { it.type == wanted }
            // The inversion must confirm inside the window, and be fresh: an
            // old inversion that happens to sit here was not this return's
            // rejection, it was some earlier move's.
            .filter { it.inversionIndex in touchIndex..windowEnd }
            .filter { it.inversionIndex - it.originIndex <= config.maxIfvgAgeBars }
            .filter { !requireInside || overlapsPoi(it, poi) }
            .minByOrNull { it.inversionIndex }
            ?: return null

        val index = candidate.inversionIndex
        val confirmation = IfvgConfirmation(
            direction = poi.direction,
            high = candidate.highPrice,
            low = candidate.lowPrice,
            originIndex = candidate.originIndex,
            inversionIndex = index,
        )

        return Entry(
            type = if (requireInside) VirginWickEntryType.IFVG_IN_POI else VirginWickEntryType.IFVG,
            index = index,
            price = candles[index].close,
            ifvg = confirmation,
            reasons = buildList {
                add("Price returned to the untested wick")
                add(
                    "Inverted FVG confirmed the rejection @ " +
                        String.format(java.util.Locale.US, "%.5f", candidate.lowPrice),
                )
                if (requireInside) add("Inversion formed inside the zone")
            },
        )
    }

    private fun overlapsPoi(ifvg: InversionFVG, poi: WickPoi): Boolean =
        ifvg.lowPrice <= poi.wick.high && ifvg.highPrice >= poi.wick.low
}
