package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.model.EpaState
import com.foxtrader.app.domain.usecase.nascent.model.PriceRange
import com.foxtrader.app.domain.usecase.nascent.model.StructurePoint
import com.foxtrader.app.domain.usecase.nascent.model.StructurePointType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Efficient Price Action.
 *
 * EPA is **not** a fair-value-gap detector, and the engine deliberately refuses
 * to treat "an FVG exists" as "EPA confirmed". Nascent frames EPA around the
 * preceding range, the recent range, mitigation of those ranges, and whether
 * delivery continued afterwards — a gap is at best a side effect of that.
 *
 * The reconstruction below is [com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel.INFERRED_V1]:
 * the source describes EPA in prose only, so the geometry is a first pass, kept
 * isolated and independently testable rather than fused into the MSU detectors.
 *
 * "Mitigation" here means price returning to and interacting with a previously
 * created *range* — not "an FVG was filled" and not "an order block was
 * touched". Those are objects from a different methodology and neither is what
 * the source discusses.
 *
 * Note also what this is **not** claiming to be: Nascent's checklist mentions
 * "EPA + DP (momentum validity)", but never defines momentum validity
 * numerically. No RSI level, ATR threshold, body percentage or volume spike is
 * presented here as that formula — it remains
 * [com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel.UNRESOLVED].
 *
 * A candidate needs four things, all decidable from closed bars at or before
 * the evaluation bar:
 *  1. a completed prior range and a completed current delivery range;
 *  2. delivery that was *efficient* — net progress large relative to the churn
 *     spent making it, which is what separates a real expansion from chop;
 *  3. mitigation — price traded back into the delivery range afterwards;
 *  4. a structural return — a closed, bodied candle resuming the delivery.
 */
@Singleton
class NascentEpaEngine @Inject constructor() {

    fun evaluate(
        candles: List<Candle>,
        alternatingPivots: List<StructurePoint>,
        direction: Direction,
        atIndex: Int,
        config: NascentConfig,
    ): EpaState {
        val empty = EpaState(
            previousRange = null,
            currentRange = null,
            mitigationObserved = false,
            structureReturnObserved = false,
            direction = direction,
            confirmed = false,
            confirmationIndex = null,
            efficiency = 0.0,
        )
        if (atIndex !in candles.indices) return empty

        val pivots = alternatingPivots.filter { it.confirmationBarIndex <= atIndex }
        if (pivots.size < 3) return empty
        val p0 = pivots[pivots.size - 3]
        val p1 = pivots[pivots.size - 2]
        val p2 = pivots[pivots.size - 1]

        // The most recent leg must actually be the delivery we are reasoning about.
        val legDirection = when {
            p2.type == StructurePointType.HIGH && p1.type == StructurePointType.LOW -> Direction.BULLISH
            p2.type == StructurePointType.LOW && p1.type == StructurePointType.HIGH -> Direction.BEARISH
            else -> return empty
        }
        if (legDirection != direction) return empty

        val previousRange = rangeOf(candles, p0.pivotBarIndex, p1.pivotBarIndex) ?: return empty
        val currentRange = rangeOf(candles, p1.pivotBarIndex, p2.pivotBarIndex) ?: return empty

        val efficiency = efficiency(candles, p1.pivotBarIndex, p2.pivotBarIndex)
        val partial = empty.copy(
            previousRange = previousRange,
            currentRange = currentRange,
            efficiency = efficiency,
        )
        if (efficiency < config.minEpaEfficiency) return partial

        // Mitigation: price returns into the delivery range it just left.
        val mitigationIndex = ((p2.pivotBarIndex + 1)..atIndex).firstOrNull { index ->
            val candle = candles[index]
            candle.low <= currentRange.high && candle.high >= currentRange.low
        } ?: return partial

        // Structural return: a bodied, directional close after that mitigation,
        // without the delivery's protected extreme being lost first.
        val protectedExtreme = if (direction == Direction.BULLISH) currentRange.low else currentRange.high
        var returnIndex: Int? = null
        for (index in mitigationIndex..atIndex) {
            val candle = candles[index]
            val invalidated = if (direction == Direction.BULLISH) {
                candle.close < protectedExtreme
            } else {
                candle.close > protectedExtreme
            }
            if (invalidated) {
                return partial.copy(mitigationObserved = true)
            }
            if (isDelivery(candle, direction, config)) {
                returnIndex = index
                break
            }
        }
        if (returnIndex == null) return partial.copy(mitigationObserved = true)

        return partial.copy(
            mitigationObserved = true,
            structureReturnObserved = true,
            confirmed = true,
            confirmationIndex = returnIndex,
        )
    }

    private fun rangeOf(candles: List<Candle>, fromPivot: Int, toPivot: Int): PriceRange? {
        val from = minOf(fromPivot, toPivot)
        val to = maxOf(fromPivot, toPivot)
        if (from < 0 || to > candles.lastIndex || from >= to) return null
        val window = candles.subList(from, to + 1)
        val high = window.maxOf { it.high }
        val low = window.minOf { it.low }
        if (!high.isFinite() || !low.isFinite() || high <= low) return null
        return PriceRange(low = low, high = high, startIndex = from, endIndex = to)
    }

    /**
     * Net progress divided by total distance travelled over the leg.
     *
     * 1.0 is a single uninterrupted move; values near zero mean price spent its
     * range going nowhere. This is the "efficient" in Efficient Price Action.
     */
    private fun efficiency(candles: List<Candle>, fromPivot: Int, toPivot: Int): Double {
        val from = minOf(fromPivot, toPivot)
        val to = maxOf(fromPivot, toPivot)
        if (from < 0 || to > candles.lastIndex || from >= to) return 0.0
        var travelled = 0.0
        for (index in from..to) {
            val range = candles[index].range
            if (range.isFinite() && range > 0.0) travelled += range
        }
        if (travelled <= EPSILON) return 0.0
        val net = abs(candles[to].close - candles[from].close)
        if (!net.isFinite()) return 0.0
        return (net / travelled).coerceIn(0.0, 1.0)
    }

    private fun isDelivery(candle: Candle, direction: Direction, config: NascentConfig): Boolean {
        val range = candle.range
        if (!range.isFinite() || range <= EPSILON) return false
        if (candle.bodySize / range < config.minDeliveryBodyFraction) return false
        return when (direction) {
            Direction.BULLISH -> candle.close > candle.open
            Direction.BEARISH -> candle.close < candle.open
        }
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}
