package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneDisplacement
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneGap
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneSweep

/**
 * Steps 4 to 7 — confirmation, entry, stop and exit.
 *
 * **Confirmation never happens intrabar.** The displacement candle is judged on
 * its close and nothing else. A bar that looks like a two-ATR impulse halfway
 * through can finish as a doji, and an engine that acted on the first version
 * would produce a signal that later ceases to exist. Every method here reads
 * completed bars only.
 *
 * **Confirmation is a break, not a big candle.** Size alone is volatility;
 * volatility that takes out the structure standing against the trade is intent.
 * The close has to clear the last internal swing on the opposing side.
 *
 * **The entry waits for the retracement.** Entering on the displacement itself
 * buys the top of the impulse, which is the worst price the move offers and
 * puts the stop a full leg away. The first return into the gap the impulse left
 * — or, absent a gap, into the middle of it — is the same trade at a fraction
 * of the risk, and the reward floor is measured from there.
 */
class KeystoneTrigger {

    /** A confirmed setup waiting for price to come back to it. */
    data class Pending(
        val sweep: KeystoneSweep,
        val displacement: KeystoneDisplacement,
        val direction: Direction,
        /** Bar from which the entry zone was knowable. */
        val armedIndex: Int,
        /** The price at which the entry fills on first touch. */
        val entry: Double,
        val stopLoss: Double,
        val fromGap: Boolean,
        val impulseLow: Double,
        val impulseHigh: Double,
    )

    /**
     * Whether the closed candle at [index] is the displacement [sweep] was
     * waiting for.
     *
     * Per-bar rather than per-sweep, because the engine walks the series once
     * and asks this question of the bar in front of it. Nothing here reads past
     * [index], which is what lets the same answer come back whether the series
     * ends at [index] or continues for another thousand bars.
     */
    fun displacementAt(
        candles: List<Candle>,
        index: Int,
        sweep: KeystoneSweep,
        atr: DoubleArray,
        config: KeystoneConfig,
    ): KeystoneDisplacement? {
        if (index <= sweep.index || index > candles.lastIndex) return null
        if (index - sweep.index > config.maxSweepToDisplacementBars) return null

        val direction = sweep.direction
        val bar = candles[index]
        if (bar.range <= 0.0) return null
        val volatility = atr.getOrElse(index) { 0.0 }
        if (volatility <= 0.0) return null

        val candleDirection = if (bar.isBullish) Direction.BULLISH else Direction.BEARISH
        if (candleDirection != direction) return null
        val bodyRatio = bar.bodySize / bar.range
        val atrMultiple = bar.bodySize / volatility
        if (bodyRatio < config.displacementBodyRatio) return null
        if (atrMultiple < config.displacementAtrMultiple) return null

        val broken = internalBreakLevel(candles, index, direction, sweep.index, config)
        if (config.requireInternalBreak && broken == null) return null

        return KeystoneDisplacement(
            index = index,
            direction = direction,
            startPrice = bar.open,
            endPrice = bar.close,
            bodyToRangeRatio = bodyRatio,
            atrMultiple = atrMultiple,
            // Deliberately absent here. The gap is a three-candle pattern
            // whose last candle has not closed yet at [index], so attaching it
            // now would read one bar into the future. [arm] fills it in on the
            // next bar, which is also the first bar a retracement could occur
            // on — so nothing is lost by waiting and a look-ahead is avoided.
            fairValueGap = null,
            brokenStructureLevel = broken,
        )
    }

    /**
     * Arm the pending entry for a confirmed displacement, or null when the
     * chosen entry mode has nothing to offer or the geometry is inverted.
     *
     * The armed bar is one past the displacement, because the fair-value gap is
     * only defined once the following bar has closed. That is also the first
     * bar on which a retracement could be observed, so nothing is lost.
     */
    fun arm(
        candles: List<Candle>,
        sweep: KeystoneSweep,
        displacement: KeystoneDisplacement,
        atr: DoubleArray,
        config: KeystoneConfig,
    ): Pending? {
        val armed = displacement.index + 1
        if (armed > candles.lastIndex) return null
        val direction = displacement.direction

        val span = candles.subList(sweep.index, displacement.index + 1)
        val impulseLow = span.minOf { it.low }
        val impulseHigh = span.maxOf { it.high }
        if (impulseHigh <= impulseLow) return null

        // Now that the bar after the impulse has closed, the gap it left is
        // defined and may be read.
        val gap = gapAt(candles, displacement.index, direction)
        val confirmed = displacement.copy(fairValueGap = gap)
        val useGap = gap != null && config.entryMode != KeystoneEntryMode.EQUILIBRIUM_ONLY
        if (!useGap && config.entryMode == KeystoneEntryMode.FVG_ONLY) return null

        // The proximal edge is the side price reaches first on its way back, so
        // it is the fill a resting order would actually get.
        val entry = if (useGap && gap != null) {
            if (direction == Direction.BULLISH) gap.high else gap.low
        } else {
            val depth = impulseHigh - impulseLow
            if (direction == Direction.BULLISH) {
                impulseHigh - depth * config.retracementMin
            } else {
                impulseLow + depth * config.retracementMin
            }
        }

        val buffer = atr.getOrElse(sweep.index) { 0.0 } * config.stopAtrBuffer
        val stop = if (direction == Direction.BULLISH) {
            minOf(sweep.extreme, impulseLow) - buffer
        } else {
            maxOf(sweep.extreme, impulseHigh) + buffer
        }

        // A stop on the wrong side of the entry is not a tight stop, it is a
        // broken setup — it happens when the sweep extreme sits inside the
        // retracement band on a very shallow impulse.
        val risk = if (direction == Direction.BULLISH) entry - stop else stop - entry
        if (risk <= 0.0) return null

        return Pending(
            sweep = sweep,
            displacement = confirmed,
            direction = direction,
            armedIndex = armed,
            entry = entry,
            stopLoss = stop,
            fromGap = useGap,
            impulseLow = impulseLow,
            impulseHigh = impulseHigh,
        )
    }

    /**
     * True when the closed bar at [index] traded into the pending entry.
     *
     * The zone is also treated as missed once price has run away far enough
     * that the retracement is no longer the trade that was planned; the caller
     * expires it on [KeystoneConfig.maxEntryWaitBars].
     */
    fun filled(pending: Pending, candles: List<Candle>, index: Int): Boolean {
        val bar = candles.getOrNull(index) ?: return false
        return if (pending.direction == Direction.BULLISH) {
            bar.low <= pending.entry
        } else {
            bar.high >= pending.entry
        }
    }

    /** True once price has invalidated the setup by taking the stop first. */
    fun invalidated(pending: Pending, candles: List<Candle>, index: Int): Boolean {
        val bar = candles.getOrNull(index) ?: return false
        return if (pending.direction == Direction.BULLISH) {
            bar.low <= pending.stopLoss
        } else {
            bar.high >= pending.stopLoss
        }
    }

    /**
     * The exit for a pending setup: opposing liquidity when it sits far enough
     * away, otherwise a fixed multiple of the risk.
     *
     * Returns null when neither can reach [KeystoneConfig.minRewardMultiple],
     * which drops the setup instead of taking it at terms the model does not
     * claim an edge at.
     */
    fun target(
        pending: Pending,
        opposingPrice: Double?,
        config: KeystoneConfig,
    ): Double? {
        val risk = if (pending.direction == Direction.BULLISH) {
            pending.entry - pending.stopLoss
        } else {
            pending.stopLoss - pending.entry
        }
        if (risk <= 0.0) return null

        if (config.targetOpposingLiquidity && opposingPrice != null) {
            val reward = if (pending.direction == Direction.BULLISH) {
                opposingPrice - pending.entry
            } else {
                pending.entry - opposingPrice
            }
            if (reward / risk >= config.minRewardMultiple) return opposingPrice
        }

        val fixed = if (pending.direction == Direction.BULLISH) {
            pending.entry + risk * config.defaultRewardMultiple
        } else {
            pending.entry - risk * config.defaultRewardMultiple
        }
        return if (config.defaultRewardMultiple >= config.minRewardMultiple) fixed else null
    }

    /**
     * The internal swing the displacement's close took out, or null if it took
     * out none.
     *
     * "Internal" means the minor high or low the market built on its way into
     * the trap, plus everything since: a handful of bars before the sweep
     * through to the displacement itself. The width matters more than it looks
     * — read it too wide and the test silently becomes a multi-leg breakout,
     * which is a far stronger demand than the model makes and which refuses
     * most real sequences.
     */
    private fun internalBreakLevel(
        candles: List<Candle>,
        index: Int,
        direction: Direction,
        sweepIndex: Int,
        config: KeystoneConfig,
    ): Double? {
        val from = (sweepIndex - config.internalStructureBars).coerceAtLeast(0)
        if (index - from < 2) return null
        val window = candles.subList(from, index)
        val level = if (direction == Direction.BULLISH) {
            window.maxOf { it.high }
        } else {
            window.minOf { it.low }
        }
        val close = candles[index].close
        val broke = if (direction == Direction.BULLISH) close > level else close < level
        return if (broke) level else null
    }

    /** The three-candle fair value gap left by the impulse at [index]. */
    private fun gapAt(candles: List<Candle>, index: Int, direction: Direction): KeystoneGap? {
        if (index - 1 < 0 || index + 1 > candles.lastIndex) return null
        return if (direction == Direction.BULLISH) {
            val low = candles[index - 1].high
            val high = candles[index + 1].low
            if (high > low) KeystoneGap(low, high) else null
        } else {
            val high = candles[index - 1].low
            val low = candles[index + 1].high
            if (high > low) KeystoneGap(low, high) else null
        }
    }
}
