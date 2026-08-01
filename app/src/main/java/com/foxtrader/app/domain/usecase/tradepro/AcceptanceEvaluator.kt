package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.AcceptanceResult
import com.foxtrader.app.domain.model.tradepro.AcceptanceState
import javax.inject.Inject

/**
 * Decides whether a level break was truly *accepted* or merely a wick/rejection.
 *
 * Acceptance (per the framework) needs three things after the break: (a) price holds time beyond the
 * level, (b) new structure forms beyond it (a continuation to a higher-high for longs / lower-low for
 * shorts), and (c) pullbacks toward the level are defended (close back beyond it). A first break is just
 * information; this test is the "second decision point".
 */
class AcceptanceEvaluator @Inject constructor() {

    fun evaluate(
        candles: List<Candle>,
        breakIndex: Int,
        level: Double,
        direction: Direction,
        minBars: Int = 2,
    ): AcceptanceResult {
        val size = candles.size
        val from = (breakIndex + 1).coerceIn(0, size)
        val post = if (from < size) candles.subList(from, size) else emptyList()
        if (post.isEmpty()) {
            return AcceptanceResult(
                AcceptanceState.PENDING, level, direction, 0,
                formedNewStructure = false, defendedPullback = false,
                detail = "No bars after the break yet.",
            )
        }

        val bull = direction == Direction.BULLISH
        fun beyond(c: Candle) = if (bull) c.close > level else c.close < level

        val barsHeld = post.takeWhile { beyond(it) }.size
        if (barsHeld == 0) {
            return AcceptanceResult(
                AcceptanceState.REJECTED, level, direction, 0,
                formedNewStructure = false, defendedPullback = false,
                detail = "Immediate snap-back through the level — false break.",
            )
        }

        // After the initial hold, a close back through the level invalidates acceptance.
        val violatedAfterHold = post.drop(barsHeld).any { if (bull) it.close < level else it.close > level }

        // New structure = continuation to a fresh extreme AFTER the break bar (not on the break bar itself).
        val extremeIndex: Int
        val extendedBeyondFirst: Boolean
        if (bull) {
            extremeIndex = post.indices.maxByOrNull { post[it].high } ?: 0
            extendedBeyondFirst = post[extremeIndex].high > post.first().high
        } else {
            extremeIndex = post.indices.minByOrNull { post[it].low } ?: 0
            extendedBeyondFirst = post[extremeIndex].low < post.first().low
        }
        val formedNewStructure = extremeIndex >= 1 && extendedBeyondFirst

        // Defended pullback = a genuine pullback bar that still closed beyond the level.
        val defendedPullback = (1 until post.size).any { k ->
            if (bull) post[k].low < post[k - 1].low && post[k].close > level
            else post[k].high > post[k - 1].high && post[k].close < level
        }

        val state = when {
            violatedAfterHold -> AcceptanceState.REJECTED
            barsHeld >= minBars && formedNewStructure -> AcceptanceState.ACCEPTED
            else -> AcceptanceState.PENDING
        }
        val detail = when (state) {
            AcceptanceState.ACCEPTED -> "Held $barsHeld bars, formed new structure" +
                if (defendedPullback) " and defended a pullback." else "."
            AcceptanceState.REJECTED -> "Closed back through the level after holding — rejection."
            AcceptanceState.PENDING -> "Held $barsHeld bars; awaiting new structure confirmation."
        }
        return AcceptanceResult(state, level, direction, barsHeld, formedNewStructure, defendedPullback, detail)
    }
}
