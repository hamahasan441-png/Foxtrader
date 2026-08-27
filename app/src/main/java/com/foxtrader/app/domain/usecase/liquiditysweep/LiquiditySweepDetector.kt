package com.foxtrader.app.domain.usecase.liquiditysweep

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquidityLevel
import com.foxtrader.app.domain.usecase.liquiditysweep.model.LiquiditySweep
import kotlin.math.abs

/**
 * Step 3 — the sweep itself.
 *
 * Price must trade beyond a marked level and then close back on the original
 * side within a small number of bars. Both halves matter: the penetration is
 * the liquidity being collected, and the reclaim is the proof that it was
 * collected rather than genuinely broken. A break that holds is the opposite
 * signal, and without the reclaim requirement the two are indistinguishable.
 */
class LiquiditySweepDetector {

    /**
     * The sweep that completes its reclaim exactly at [reclaimIndex], if any.
     *
     * Keyed on the reclaim bar rather than the penetration bar so a caller
     * walking the series forward sees each sweep once, on the bar it became
     * confirmed — never on a bar that had not closed when it happened.
     */
    fun sweepAt(
        candles: List<Candle>,
        reclaimIndex: Int,
        levels: List<LiquidityLevel>,
        config: LiquiditySweepConfig,
    ): LiquiditySweep? {
        if (reclaimIndex !in candles.indices) return null
        val reclaim = candles[reclaimIndex]
        if (!reclaim.close.isFinite()) return null

        var best: LiquiditySweep? = null

        for (level in levels) {
            if (!level.price.isFinite() || level.price <= 0.0) continue
            val tolerance = abs(level.price) * config.minSweepPenetrationFraction

            // The reclaim must have closed back on the level's original side.
            val reclaimed = if (level.aboveMarket) {
                if (config.requireCloseReclaim) reclaim.close < level.price else reclaim.close <= level.price
            } else {
                if (config.requireCloseReclaim) reclaim.close > level.price else reclaim.close >= level.price
            }
            if (!reclaimed) continue

            // Find the penetration: the most recent bar within the reclaim
            // window that traded through the level.
            val earliest = (reclaimIndex - config.maxReclaimBars + 1).coerceAtLeast(0)
            var sweepIndex = -1
            var extreme = if (level.aboveMarket) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY

            for (i in earliest..reclaimIndex) {
                if (i < level.knownFromIndex) continue
                val candle = candles[i]
                val penetrated = if (level.aboveMarket) {
                    candle.high > level.price + tolerance
                } else {
                    candle.low < level.price - tolerance
                }
                if (!penetrated) continue
                sweepIndex = if (sweepIndex < 0) i else sweepIndex
                extreme = if (level.aboveMarket) {
                    maxOf(extreme, candle.high)
                } else {
                    minOf(extreme, candle.low)
                }
            }
            if (sweepIndex < 0) continue

            // A bar that both penetrates and reclaims is a valid one-bar sweep,
            // but every bar between them must not have closed beyond the level:
            // that would be an accepted break with a later pullback, not a trap.
            var brokenAndHeld = false
            for (i in sweepIndex until reclaimIndex) {
                val close = candles[i].close
                val beyond = if (level.aboveMarket) close > level.price else close < level.price
                if (beyond && i > sweepIndex) {
                    brokenAndHeld = true
                    break
                }
            }
            if (brokenAndHeld) continue

            val candidate = LiquiditySweep(
                level = level,
                direction = level.sweepDirection,
                sweepIndex = sweepIndex,
                reclaimIndex = reclaimIndex,
                extreme = extreme,
                reclaimClose = reclaim.close,
                timestamp = reclaim.timestamp,
            )

            // Deepest penetration wins: it collected the most liquidity, and on
            // an equal-level cluster it is the one that actually ran the stops.
            if (best == null || penetration(candidate) > penetration(best)) best = candidate
        }

        return best
    }

    private fun penetration(sweep: LiquiditySweep): Double =
        abs(sweep.extreme - sweep.level.price)
}
