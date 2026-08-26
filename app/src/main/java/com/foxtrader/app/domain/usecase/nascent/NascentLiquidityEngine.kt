package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.model.LiquidityCycle
import com.foxtrader.app.domain.usecase.nascent.model.LiquidityPoint
import com.foxtrader.app.domain.usecase.nascent.model.LiquiditySide
import com.foxtrader.app.domain.usecase.nascent.model.LiquidityType
import com.foxtrader.app.domain.usecase.nascent.model.StructurePoint
import com.foxtrader.app.domain.usecase.nascent.model.StructurePointType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Nascent Liquidity Cycle mapper.
 *
 * A cycle is one BUY -> SELL (or SELL -> BUY) delivery. Following the source:
 * for a BUY -> SELL range the highest point is the ILQ, the lowest point is the
 * TLQ, and the structural pivots in between are SLQ. SELL -> BUY mirrors it.
 *
 * ### Why a cycle needs four pivots, not three
 *
 * The obvious implementation walks an alternating LOW -> HIGH -> LOW sequence
 * and calls that a cycle. It repaints. While the closing LOW is still the most
 * recent pivot of its type, a deeper low can arrive and legitimately *replace*
 * it, which silently moves the TLQ of a cycle that was already published.
 *
 * The cycle is therefore only emitted once the *next* opposite pivot has been
 * confirmed. At that point the closing extreme can no longer be extended — a
 * later pivot starts a new leg instead of absorbing the old one — so the cycle
 * is frozen. [LiquidityCycle.confirmationIndex] is that fourth pivot's
 * confirmation bar, which is the first bar on which the cycle was knowable.
 *
 * Deliberately, not every swing becomes liquidity: a point only qualifies when
 * it belongs to a completed cycle.
 */
@Singleton
class NascentLiquidityEngine @Inject constructor() {

    /**
     * All cycles knowable from [candles], each stamped with the bar it became
     * knowable on.
     *
     * The alternating sequence is rebuilt **incrementally**, replaying the same
     * append/merge decisions the live walk makes, and a cycle is emitted at the
     * moment its fourth pivot is *appended* (never when an existing element is
     * merged into a more extreme one). That is what makes this batch call agree
     * bar-for-bar with incremental evaluation: filtering this result to
     * `confirmationIndex <= t` yields exactly what running against the first
     * `t + 1` candles would have produced.
     *
     * Computing the alternating list first and slicing it afterwards looks
     * equivalent and is not — a late merge would retroactively re-stamp a cycle
     * that had already been published.
     */
    fun cycles(
        candles: List<Candle>,
        swings: List<StructurePoint>,
        enableDecisionalSlq: Boolean = false,
    ): List<LiquidityCycle> {
        if (candles.isEmpty() || swings.size < 4) return emptyList()
        val ordered = swings.sortedWith(
            compareBy<StructurePoint> { it.confirmationBarIndex }.thenBy { it.pivotBarIndex },
        )

        val out = ArrayList<LiquidityCycle>(ordered.size)
        val alternating = ArrayList<StructurePoint>(ordered.size)
        for (swing in ordered) {
            if (!appendOrMerge(alternating, swing)) continue
            if (alternating.size < 4) continue
            val start = alternating[alternating.size - 4]
            val mid = alternating[alternating.size - 3]
            val end = alternating[alternating.size - 2]
            val freeze = alternating[alternating.size - 1]
            if (start.type == mid.type || mid.type == end.type) continue

            val from = start.pivotBarIndex
            val to = end.pivotBarIndex
            if (from >= to || to > candles.lastIndex) continue

            // Terminal delivery direction: a cycle that closes on a LOW sold off.
            val direction =
                if (end.type == StructurePointType.LOW) Direction.BEARISH else Direction.BULLISH
            val window = candles.subList(from, to + 1)
            val rangeHigh = window.maxOf { it.high }
            val rangeLow = window.minOf { it.low }
            if (!rangeHigh.isFinite() || !rangeLow.isFinite() || rangeHigh <= rangeLow) continue

            val highIndex = (from..to).maxByOrNull { candles[it].high } ?: continue
            val lowIndex = (from..to).minByOrNull { candles[it].low } ?: continue
            val confirmation = freeze.confirmationBarIndex

            val highPoint = LiquidityPoint(
                type = if (direction == Direction.BEARISH) LiquidityType.ILQ else LiquidityType.TLQ,
                side = LiquiditySide.HIGH,
                price = rangeHigh,
                originIndex = highIndex,
                confirmationIndex = confirmation,
                timestamp = candles[highIndex].timestamp,
            )
            val lowPoint = LiquidityPoint(
                type = if (direction == Direction.BEARISH) LiquidityType.TLQ else LiquidityType.ILQ,
                side = LiquiditySide.LOW,
                price = rangeLow,
                originIndex = lowIndex,
                confirmationIndex = confirmation,
                timestamp = candles[lowIndex].timestamp,
            )

            val slq = structuralLiquidity(
                swings = swings,
                candles = candles,
                from = from,
                to = to,
                excludeBars = setOf(highIndex, lowIndex),
                confirmation = confirmation,
                decisionalAnchor = mid.price,
                enableDecisional = enableDecisionalSlq,
            )

            out += LiquidityCycle(
                direction = direction,
                startIndex = from,
                endIndex = to,
                confirmationIndex = confirmation,
                rangeHigh = rangeHigh,
                rangeLow = rangeLow,
                ilq = if (direction == Direction.BEARISH) highPoint else lowPoint,
                tlq = if (direction == Direction.BEARISH) lowPoint else highPoint,
                slq = slq,
                confirmed = true,
            )
        }
        return out
    }

    /**
     * Structural pivots inside the cycle.
     *
     * ### Decisional Structural Liquidity
     *
     * The *name* appears in Nascent's own checklist, but the supplied Primary
     * Analysis never defines its geometry, so it is
     * [com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel.UNRESOLVED].
     * Promoting "the pivot nearest the turning price" — or the BOS origin, or
     * the last order block — would be inventing a Nascent rule, so the
     * promotion is **off by default** and gated behind
     * [NascentConfig.enableDecisionalSlq] for research use only. With the flag
     * off every internal pivot is reported as plain [LiquidityType.SLQ].
     */
    private fun structuralLiquidity(
        swings: List<StructurePoint>,
        candles: List<Candle>,
        from: Int,
        to: Int,
        excludeBars: Set<Int>,
        confirmation: Int,
        decisionalAnchor: Double,
        enableDecisional: Boolean,
    ): List<LiquidityPoint> {
        val inside = swings.filter { swing ->
            swing.pivotBarIndex > from &&
                swing.pivotBarIndex < to &&
                swing.pivotBarIndex !in excludeBars &&
                swing.confirmationBarIndex <= confirmation
        }
        if (inside.isEmpty()) return emptyList()
        val decisional = if (enableDecisional) {
            inside.minByOrNull { abs(it.price - decisionalAnchor) }
        } else {
            null
        }
        return inside.map { swing ->
            LiquidityPoint(
                type = if (swing === decisional) {
                    LiquidityType.DECISIONAL_SLQ
                } else {
                    LiquidityType.SLQ
                },
                side = if (swing.type == StructurePointType.HIGH) {
                    LiquiditySide.HIGH
                } else {
                    LiquiditySide.LOW
                },
                price = swing.price,
                originIndex = swing.pivotBarIndex,
                confirmationIndex = maxOf(swing.confirmationBarIndex, confirmation),
                timestamp = candles[swing.pivotBarIndex].timestamp,
            )
        }
    }

    companion object {
        /**
         * Fold one confirmed pivot into a strictly alternating HIGH/LOW sequence.
         *
         * Returns `true` when the pivot was **appended** as a new element and
         * `false` when it merged into (or was discarded by) the current last
         * element. Callers use that distinction to decide when a structure has
         * genuinely advanced versus merely deepened.
         *
         * Pivots confirm in increasing pivot order, so a merge can only ever
         * affect the newest element — which is exactly why a cycle is not
         * published until a further pivot has frozen its closing extreme.
         *
         * Shared as a single primitive so the batch and incremental paths
         * cannot drift apart.
         */
        fun appendOrMerge(
            alternating: MutableList<StructurePoint>,
            swing: StructurePoint,
        ): Boolean {
            val last = alternating.lastOrNull()
            if (last == null || last.type != swing.type) {
                alternating += swing
                return true
            }
            val moreExtreme = when (swing.type) {
                StructurePointType.HIGH -> swing.price > last.price
                StructurePointType.LOW -> swing.price < last.price
            }
            if (moreExtreme) alternating[alternating.lastIndex] = swing
            return false
        }
    }
}
