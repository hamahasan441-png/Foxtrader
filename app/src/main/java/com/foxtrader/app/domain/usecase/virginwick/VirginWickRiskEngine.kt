package com.foxtrader.app.domain.usecase.virginwick

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.virginwick.model.IfvgConfirmation
import com.foxtrader.app.domain.usecase.virginwick.model.TargetSource
import com.foxtrader.app.domain.usecase.virginwick.model.VirginWick
import com.foxtrader.app.domain.usecase.virginwick.model.WickPoi
import kotlin.math.abs

/**
 * Step 5 — the stop and the target.
 *
 * The stop goes on the far side of whichever is safer: the inversion that
 * confirmed the entry, or the entry bar itself. Taking the further of the two
 * is deliberate — the tighter one is inside the structure that just rejected
 * price, and a stop placed there is paying for precision the setup has not
 * earned.
 *
 * The target is the nearest draw on liquidity: the next untested wick on the
 * far side, which is exactly the kind of unfinished business this model says
 * price travels toward. When that draw is unreasonably far, aiming at it turns
 * a scalp into a swing, so a fixed multiple is used instead.
 */
object VirginWickRiskEngine {

    /** Resolved trade geometry. */
    data class Geometry(
        val entry: Double,
        val stop: Double,
        val target: Double,
        val source: TargetSource,
    )

    fun build(
        direction: Direction,
        entry: Double,
        entryCandle: Candle,
        ifvg: IfvgConfirmation?,
        poi: WickPoi,
        opposingWicks: List<VirginWick>,
        config: VirginWickConfig,
    ): Geometry? {
        if (!entry.isFinite() || entry <= 0.0) return null

        val bullish = direction == Direction.BULLISH

        // Candidate stops: the far side of the inversion, the far side of the
        // entry bar, and the wick's own extreme — the zone is invalid if price
        // trades clean through it.
        val candidates = buildList {
            ifvg?.let { add(if (bullish) it.low else it.high) }
            add(if (bullish) entryCandle.low else entryCandle.high)
            add(poi.distal)
        }.filter { it.isFinite() }
        if (candidates.isEmpty()) return null

        val raw = if (bullish) candidates.min() else candidates.max()
        val buffer = abs(raw) * config.stopBufferFraction
        val stop = if (bullish) raw - buffer else raw + buffer

        val risk = if (bullish) entry - stop else stop - entry
        if (risk <= 0.0 || !risk.isFinite()) return null

        val fixed = if (bullish) {
            entry + config.defaultRewardMultiple * risk
        } else {
            entry - config.defaultRewardMultiple * risk
        }

        val draw = nearestDraw(bullish, entry, opposingWicks)
        val drawMultiple = draw?.let { (if (bullish) it - entry else entry - it) / risk }

        val useDraw = draw != null &&
            drawMultiple != null &&
            drawMultiple >= config.minRewardMultiple &&
            drawMultiple <= config.maxDolRewardMultiple

        val target = if (useDraw) draw else fixed
        val source = if (useDraw) TargetSource.DRAW_ON_LIQUIDITY else TargetSource.FIXED_MULTIPLE
        if (!target.isFinite()) return null

        val reward = if (bullish) target - entry else entry - target
        if (reward <= 0.0 || reward / risk < config.minRewardMultiple) return null

        return Geometry(entry = entry, stop = stop, target = target, source = source)
    }

    /** The nearest untested wick on the far side of the trade. */
    private fun nearestDraw(
        bullish: Boolean,
        entry: Double,
        wicks: List<VirginWick>,
    ): Double? = wicks
        .asSequence()
        .map { if (bullish) it.low else it.high }
        .filter { it.isFinite() }
        .filter { if (bullish) it > entry else it < entry }
        .minByOrNull { abs(it - entry) }
}
