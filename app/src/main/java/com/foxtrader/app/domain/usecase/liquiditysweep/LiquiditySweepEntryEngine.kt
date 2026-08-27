package com.foxtrader.app.domain.usecase.liquiditysweep

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquidityLevel
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquiditySweep
import com.foxtrader.app.domain.usecase.liquiditysweep.model.SweepEntryType
import kotlin.math.abs

/**
 * Steps 4 and 5 — the entry, the stop and the target.
 *
 * Entering on the reclaim close is the earliest the model can act. Waiting for
 * a retest gives a tighter stop and filters reclaims that were themselves just
 * noise, at the cost of the entries where price never comes back. Which of
 * those a trader wants is a real preference, so it is a setting rather than a
 * decision baked into the engine.
 */
class LiquiditySweepEntryEngine {

    /** A confirmed entry on the execution series. */
    data class Entry(
        val type: SweepEntryType,
        val index: Int,
        val price: Double,
        val reasons: List<String>,
    )

    /** Resolved trade geometry. */
    data class Geometry(val entry: Double, val stop: Double, val target: Double)

    /**
     * Find the entry for [sweep], searching only bars at or after its reclaim.
     *
     * Returns null when the window closed without one, which is a setup that
     * expired rather than a setup that failed.
     */
    fun findEntry(
        candles: List<Candle>,
        sweep: LiquiditySweep,
        config: LiquiditySweepConfig,
    ): Entry? {
        val start = sweep.reclaimIndex
        if (start !in candles.indices) return null
        val end = minOf(candles.lastIndex, start + config.entryWindowBars - 1)

        return when (config.entryMode) {
            EntryMode.RECLAIM -> Entry(
                type = SweepEntryType.RECLAIM_CLOSE,
                index = start,
                price = candles[start].close,
                reasons = listOf("Entered on the reclaim close"),
            )

            EntryMode.RETEST -> retest(candles, sweep, start, end, config)
                ?.let { Entry(SweepEntryType.RETEST, it, candles[it].close, listOf("Retest of the reclaimed level held")) }

            EntryMode.CHOCH_RETEST -> {
                val choch = chochAfter(candles, sweep, start, end, config) ?: return null
                val retest = retest(candles, sweep, choch, end, config) ?: return null
                Entry(
                    type = SweepEntryType.CHOCH_RETEST,
                    index = retest,
                    price = candles[retest].close,
                    reasons = listOf(
                        "Change of character confirmed the reversal",
                        "Retest of the reclaimed level held",
                    ),
                )
            }
        }
    }

    /**
     * Stop behind the swept extreme, target at opposing liquidity or a fixed
     * multiple.
     *
     * The stop belongs beyond the extreme the sweep reached, because that is
     * the price the market has already proved it rejected. Anything tighter is
     * inside the noise the sweep just created.
     */
    fun geometry(
        direction: Direction,
        entry: Double,
        sweep: LiquiditySweep,
        opposingLevels: List<LiquidityLevel>,
        config: LiquiditySweepConfig,
    ): Geometry? {
        if (!entry.isFinite() || !sweep.extreme.isFinite()) return null

        val buffer = abs(sweep.extreme) * config.stopBufferFraction
        val stop = if (direction == Direction.BULLISH) sweep.extreme - buffer else sweep.extreme + buffer

        val risk = if (direction == Direction.BULLISH) entry - stop else stop - entry
        if (risk <= 0.0 || !risk.isFinite()) return null

        val fixed = if (direction == Direction.BULLISH) {
            entry + config.riskReward * risk
        } else {
            entry - config.riskReward * risk
        }

        val target = when (config.targetMode) {
            TargetMode.FIXED_R -> fixed
            TargetMode.OPPOSING_LIQUIDITY -> nearestOpposing(direction, entry, opposingLevels) ?: fixed
        }
        if (!target.isFinite()) return null

        val reward = if (direction == Direction.BULLISH) target - entry else entry - target
        if (reward <= 0.0 || reward / risk < config.minRiskReward) return null

        return Geometry(entry = entry, stop = stop, target = target)
    }

    // ------------------------------------------------------------------

    /**
     * The first bar that comes back toward the reclaimed level and closes on
     * the correct side of it.
     *
     * Depth is measured against the sweep leg rather than a fixed distance, so
     * the rule means the same thing on a two-pip scalp and a fifty-pip swing.
     */
    private fun retest(
        candles: List<Candle>,
        sweep: LiquiditySweep,
        from: Int,
        to: Int,
        config: LiquiditySweepConfig,
    ): Int? {
        val bullish = sweep.direction == Direction.BULLISH
        val level = sweep.level.price
        val leg = abs(sweep.reclaimClose - level)
        if (leg <= 0.0) return null
        val depth = leg * config.retestDepthFraction

        // Bullish: liquidity below was swept, so the retest dips back toward the
        // level from above and must close back above it.
        val trigger = if (bullish) level + depth else level - depth

        for (i in (from + 1)..to) {
            val candle = candles[i]
            val reached = if (bullish) candle.low <= trigger else candle.high >= trigger
            if (!reached) continue
            val held = if (bullish) candle.close > level else candle.close < level
            if (held) return i
        }
        return null
    }

    /**
     * A change of character after the reclaim: price breaking the most recent
     * opposing swing formed during the sweep leg.
     */
    private fun chochAfter(
        candles: List<Candle>,
        sweep: LiquiditySweep,
        from: Int,
        to: Int,
        config: LiquiditySweepConfig,
    ): Int? {
        val bullish = sweep.direction == Direction.BULLISH
        val left = config.ltfSwingLeft
        val right = config.ltfSwingRight

        for (i in (from + 1)..to) {
            // Only swings confirmed before this bar may be broken by it.
            val searchFrom = (sweep.sweepIndex - left).coerceAtLeast(left)
            var level: Double? = null
            for (j in searchFrom..(i - right - 1)) {
                if (j - left < 0 || j + right > candles.lastIndex) continue
                if (isSwing(candles, j, left, right, high = bullish)) {
                    val value = if (bullish) candles[j].high else candles[j].low
                    level = value
                }
            }
            val target = level ?: continue
            val broke = if (bullish) candles[i].close > target else candles[i].close < target
            if (broke) return i
        }
        return null
    }

    private fun isSwing(
        candles: List<Candle>,
        index: Int,
        left: Int,
        right: Int,
        high: Boolean,
    ): Boolean {
        val value = if (high) candles[index].high else candles[index].low
        if (!value.isFinite()) return false
        for (i in index - left until index) {
            val other = if (high) candles[i].high else candles[i].low
            if (high && other > value) return false
            if (!high && other < value) return false
        }
        for (i in index + 1..index + right) {
            val other = if (high) candles[i].high else candles[i].low
            if (high && other >= value) return false
            if (!high && other <= value) return false
        }
        return true
    }

    /** The nearest untouched level on the far side of the trade. */
    private fun nearestOpposing(
        direction: Direction,
        entry: Double,
        levels: List<LiquidityLevel>,
    ): Double? = levels
        .asSequence()
        .filter { if (direction == Direction.BULLISH) it.aboveMarket else !it.aboveMarket }
        .map { it.price }
        .filter { if (direction == Direction.BULLISH) it > entry else it < entry }
        .minByOrNull { abs(it - entry) }
}
