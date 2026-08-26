package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.model.ExternalKeyLevel
import com.foxtrader.app.domain.usecase.nascent.model.StructureBreak
import com.foxtrader.app.domain.usecase.nascent.model.StructurePoint
import com.foxtrader.app.domain.usecase.nascent.model.StructurePointType

/**
 * Everything a setup detector is allowed to see at one internal bar.
 *
 * The context is assembled by [NascentEngine] and is already truncated to bar
 * [atIndex]: [alternatingPivots] and [breaks] contain only events whose
 * confirmation bar is at or before it, and [keyLevel] has already passed the
 * external gate. A detector that only reads this object therefore cannot leak
 * future information, which is what makes the detectors individually testable
 * against the non-repaint contract.
 */
data class NascentInternalContext(
    val candles: List<Candle>,
    val atIndex: Int,
    /** Trade direction the candidate locations sanction. */
    val externalDirection: Direction,
    /**
     * Every usable external location supporting [externalDirection].
     *
     * A list rather than a single level: which location a setup belongs to is
     * decided by where the setup's own structure formed, not by whichever level
     * happens to be nearest on the bar being evaluated.
     */
    val candidateLevels: List<ExternalKeyLevel>,
    /** Strictly alternating HIGH/LOW pivots, oldest first, all confirmed. */
    val alternatingPivots: List<StructurePoint>,
    val breaks: List<StructureBreak>,
    val atr: Double,
    val config: NascentConfig,
) {
    val candle: Candle get() = candles[atIndex]

    /** Most recent confirmed pivots of one type, newest last. */
    fun pivotsOf(type: StructurePointType): List<StructurePoint> =
        alternatingPivots.filter { it.type == type }

    /** True when [candle] delivers with a real body in [direction]. */
    fun isDelivery(index: Int, direction: Direction): Boolean {
        val bar = candles.getOrNull(index) ?: return false
        val range = bar.range
        if (!range.isFinite() || range <= 1e-12) return false
        if (bar.bodySize / range < config.minDeliveryBodyFraction) return false
        return when (direction) {
            Direction.BULLISH -> bar.close > bar.open
            Direction.BEARISH -> bar.close < bar.open
        }
    }

    /**
     * The external location price actually worked between [fromBar] and
     * [toBar], or null when the structure formed nowhere meaningful.
     *
     * This is the Nascent key-level gate applied where it belongs — at the
     * setup's own location. An MSU-shaped geometry in the middle of nowhere
     * finds no level here and therefore produces nothing, which is the rule the
     * whole hierarchy rests on.
     *
     * The level closest to the window's extreme wins, since that is the pool
     * the delivery was reaching for.
     */
    fun levelReachedBetween(fromBar: Int, toBar: Int): ExternalKeyLevel? {
        if (candidateLevels.isEmpty()) return null
        val from = minOf(fromBar, toBar).coerceAtLeast(0)
        val to = maxOf(fromBar, toBar).coerceAtMost(candles.lastIndex)
        if (from > to) return null
        val window = candles.subList(from, to + 1)
        val low = window.minOf { it.low }
        val high = window.maxOf { it.high }
        if (!low.isFinite() || !high.isFinite()) return null
        val tolerance = if (atr.isFinite() && atr > 0.0) {
            atr * config.keyLevelToleranceAtrMultiple
        } else {
            0.0
        }
        val target = if (externalDirection == Direction.BEARISH) high else low
        return candidateLevels
            .filter { it.price.isFinite() && it.price >= low - tolerance && it.price <= high + tolerance }
            .minByOrNull { kotlin.math.abs(it.price - target) }
    }

    /** First bar in [from]..[atIndex] closing beyond [level] in [direction]. */
    fun firstCloseBeyond(level: Double, direction: Direction, from: Int): Int? {
        if (!level.isFinite()) return null
        val start = from.coerceAtLeast(0)
        if (start > atIndex) return null
        return (start..atIndex).firstOrNull { index ->
            val close = candles[index].close
            close.isFinite() && when (direction) {
                Direction.BULLISH -> close > level
                Direction.BEARISH -> close < level
            }
        }
    }
}
