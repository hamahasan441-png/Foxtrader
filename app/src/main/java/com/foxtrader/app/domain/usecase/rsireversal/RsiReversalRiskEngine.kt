package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Direction
import kotlin.math.abs

/**
 * Entry / stop / target geometry (§19, §20).
 *
 * The stop sits behind the final lower-timeframe swept extreme — the level the
 * sweep already proved the market rejected — and the target is a fixed multiple
 * of that risk. Nothing here adjusts the multiple based on structure: §20 is
 * explicit that opposing-liquidity awareness must not silently alter the fixed
 * mode, so a different target is a different configured multiple, not a hidden
 * override.
 */
object RsiReversalRiskEngine {

    /** Resolved trade geometry. */
    data class Geometry(val entry: Double, val stop: Double, val target: Double)

    /**
     * Build geometry for a confirmed entry, or null when the stop would sit on
     * the wrong side of the entry (a degenerate sweep that left no risk).
     */
    fun build(
        direction: Direction,
        entry: Double,
        sweptExtreme: Double,
        config: RsiReversalConfig,
    ): Geometry? {
        if (!entry.isFinite() || !sweptExtreme.isFinite()) return null

        val buffer = abs(sweptExtreme) * config.stopBufferFraction
        val stop = if (direction == Direction.BULLISH) sweptExtreme - buffer else sweptExtreme + buffer

        val risk = if (direction == Direction.BULLISH) entry - stop else stop - entry
        if (risk <= 0.0 || !risk.isFinite()) return null

        val target = if (direction == Direction.BULLISH) {
            entry + config.riskReward * risk
        } else {
            entry - config.riskReward * risk
        }
        if (!target.isFinite()) return null

        return Geometry(entry = entry, stop = stop, target = target)
    }
}
