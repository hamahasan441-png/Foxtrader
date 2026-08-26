package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.model.StructureBreak
import com.foxtrader.app.domain.usecase.nascent.model.StructureBreakType
import com.foxtrader.app.domain.usecase.nascent.model.StructurePoint
import com.foxtrader.app.domain.usecase.nascent.model.StructurePointType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confirmed-bar structure mapper shared by the external and internal passes.
 *
 * Two properties matter more than anything else here, and both are load-bearing
 * for the non-repaint contract:
 *
 * 1. **Every event carries its confirmation bar.** A pivot at bar *i* with a
 *    right-hand width of *R* is not knowable until bar *i + R*. Consumers gate
 *    on [StructurePoint.confirmationBarIndex], never on the pivot itself.
 *
 * 2. **No window is measured from the end of the series.** This is the subtle
 *    one. A lookback expressed as `lastIndex - N` silently redefines which
 *    events exist every time a candle arrives, so the same bar can produce a
 *    different structure in live mode than it did in replay. Every window below
 *    is anchored to the pivot that owns it, which makes the output a pure
 *    function of the prefix: analysing `candles[0..t]` yields exactly the events
 *    that analysing the full series reports with a confirmation index `<= t`.
 */
@Singleton
class NascentStructureEngine @Inject constructor() {

    /**
     * Confirmed swing highs and lows.
     *
     * Equal-price plateaus are attributed to their *first* extreme so that a
     * later equal print cannot retroactively move an already-confirmed pivot.
     */
    fun swings(candles: List<Candle>, left: Int, right: Int): List<StructurePoint> {
        if (candles.size <= left + right) return emptyList()
        val out = ArrayList<StructurePoint>(candles.size / 4 + 1)
        for (index in left until candles.size - right) {
            val candle = candles[index]
            if (!candle.high.isFinite() || !candle.low.isFinite()) continue
            var isHigh = true
            var isLow = true
            for (offset in 1..left) {
                if (candle.high <= candles[index - offset].high) isHigh = false
                if (candle.low >= candles[index - offset].low) isLow = false
            }
            for (offset in 1..right) {
                if (candle.high < candles[index + offset].high) isHigh = false
                if (candle.low > candles[index + offset].low) isLow = false
            }
            val confirmation = index + right
            if (isHigh) out += StructurePoint(StructurePointType.HIGH, candle.high, index, confirmation)
            if (isLow) out += StructurePoint(StructurePointType.LOW, candle.low, index, confirmation)
        }
        return out
    }

    /**
     * Closed-body breaks of confirmed swings, classified as BOS or CHOCH.
     *
     * A break is searched only from the bar the broken pivot became confirmable,
     * and only within [searchBars] of it, so the event's existence depends on
     * the pivot's own neighbourhood rather than on how much history happens to
     * be loaded.
     */
    fun breaks(
        candles: List<Candle>,
        swings: List<StructurePoint>,
        searchBars: Int = BREAK_SEARCH_BARS,
    ): List<StructureBreak> {
        if (candles.isEmpty() || swings.isEmpty()) return emptyList()
        val last = candles.lastIndex
        val raw = swings.mapNotNull { swing ->
            val direction =
                if (swing.type == StructurePointType.HIGH) Direction.BULLISH else Direction.BEARISH
            val start = maxOf(swing.confirmationBarIndex, swing.pivotBarIndex + 1)
            if (start > last) return@mapNotNull null
            val end = minOf(last, swing.pivotBarIndex + searchBars)
            if (start > end) return@mapNotNull null
            val confirmation = (start..end).firstOrNull { index ->
                breaksLevel(candles[index], swing.price, direction)
            } ?: return@mapNotNull null
            StructureBreak(
                type = StructureBreakType.BOS,
                direction = direction,
                level = swing.price,
                originIndex = swing.pivotBarIndex,
                confirmationIndex = confirmation,
            )
        }
            // Nested pivots frequently break on one candle. The most recently
            // formed of them is the structurally meaningful level.
            .groupBy { it.direction to it.confirmationIndex }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.originIndex } }
            .sortedWith(compareBy<StructureBreak> { it.confirmationIndex }.thenBy { it.originIndex })

        var trend: Direction? = null
        val classified = ArrayList<StructureBreak>(raw.size)
        for (event in raw) {
            val type = if (trend == null || trend == event.direction) {
                StructureBreakType.BOS
            } else {
                StructureBreakType.CHOCH
            }
            classified += event.copy(type = type)
            trend = event.direction
        }
        return classified
    }

    /** Body-close break. Wick-only excursions are sweeps, not structure. */
    private fun breaksLevel(candle: Candle, level: Double, direction: Direction): Boolean {
        if (!level.isFinite() || !candle.close.isFinite()) return false
        return when (direction) {
            Direction.BULLISH -> candle.close > level
            Direction.BEARISH -> candle.close < level
        }
    }

    /** Directional bias implied by the most recent confirmed break at or before [atIndex]. */
    fun trendAt(breaks: List<StructureBreak>, atIndex: Int): Direction? =
        breaks.lastOrNull { it.confirmationIndex <= atIndex }?.direction

    private companion object {
        /**
         * Bars after a pivot in which its break still counts as that pivot's
         * break. Anchored to the pivot, never to the end of the series.
         */
        const val BREAK_SEARCH_BARS = 400
    }
}
