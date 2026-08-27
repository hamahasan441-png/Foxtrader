package com.foxtrader.app.domain.usecase.apex

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.apex.model.ApexCandidate
import com.foxtrader.app.domain.usecase.apex.model.ApexOutcome
import com.foxtrader.app.domain.usecase.apex.model.ApexPrecision

/**
 * Resolves what actually happened to each candidate, and answers "how good has
 * this been lately" without ever looking forward.
 *
 * This is the part that makes a hit-rate threshold mean something. A threshold
 * checked against the whole series would be measuring a number that includes
 * the trade being decided and every trade after it — which is not a filter, it
 * is hindsight, and it would look spectacular and be worthless live.
 *
 * So the rule is strict: the record consulted at bar `t` contains only trades
 * that had already **resolved** before `t`.
 */
object ApexOutcomeLedger {

    /**
     * Walk a candidate forward to its stop or its target.
     *
     * When a single bar contains both levels, the outcome is recorded as a
     * loss. Bar data cannot say which came first, and assuming the good one is
     * how a backtest quietly inflates itself; the pessimistic reading is the
     * only one that cannot flatter the result.
     */
    fun resolve(
        candles: List<Candle>,
        index: Int,
        direction: Direction,
        entry: Double,
        stop: Double,
        target: Double,
        maxHoldBars: Int,
    ): Pair<ApexOutcome, Int?> {
        if (index !in candles.indices) return ApexOutcome.OPEN to null
        val risk = kotlin.math.abs(entry - stop)
        if (risk <= 0.0) return ApexOutcome.EXPIRED to index

        val bullish = direction == Direction.BULLISH
        val last = minOf(candles.lastIndex, index + maxHoldBars)

        for (i in (index + 1)..last) {
            val bar = candles[i]
            val hitStop = if (bullish) bar.low <= stop else bar.high >= stop
            val hitTarget = if (bullish) bar.high >= target else bar.low <= target

            if (hitStop) return ApexOutcome.LOSS to i
            if (hitTarget) return ApexOutcome.WIN to i
        }

        // Ran out of series versus ran out of patience are different states: one
        // may still resolve, the other never will.
        return if (last >= index + maxHoldBars) {
            ApexOutcome.EXPIRED to last
        } else {
            ApexOutcome.OPEN to null
        }
    }

    /**
     * The record available at [asOfIndex], over the most recent
     * [window] trades that had already resolved by then.
     */
    fun precisionAt(
        candidates: List<ApexCandidate>,
        asOfIndex: Int,
        window: Int,
    ): ApexPrecision {
        val resolved = candidates
            .asSequence()
            .filter { it.outcome == ApexOutcome.WIN || it.outcome == ApexOutcome.LOSS }
            // Strictly before: a trade resolving on this very bar was not known
            // when this bar's decision had to be made.
            .filter { (it.resolvedIndex ?: Int.MAX_VALUE) < asOfIndex }
            .sortedBy { it.resolvedIndex }
            .toList()
            .takeLast(window)

        return ApexPrecision.of(resolved.map { it.outcome to (it.realisedR ?: 0.0) })
    }

    /** The record over a finished set of candidates, for reporting. */
    fun summarise(candidates: List<ApexCandidate>): ApexPrecision =
        ApexPrecision.of(candidates.map { it.outcome to (it.realisedR ?: 0.0) })
}
