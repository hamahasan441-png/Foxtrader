package com.foxtrader.app.domain.usecase.nascent.msu

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.NascentInternalContext
import com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel
import com.foxtrader.app.domain.usecase.nascent.model.NascentSetup
import com.foxtrader.app.domain.usecase.nascent.model.PriceRange
import com.foxtrader.app.domain.usecase.nascent.model.SetupType
import com.foxtrader.app.domain.usecase.nascent.model.StructurePoint
import com.foxtrader.app.domain.usecase.nascent.model.StructurePointType
import com.foxtrader.app.domain.usecase.nascent.model.TomState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * MSU Type 2 — **reversal**.
 *
 * That MSU Type 2 is a reversal model is [EvidenceLevel.NASCENT_VERIFIED]. The
 * geometry is [EvidenceLevel.INFERRED_V1], reconstructed from Nascent's
 * description of price moving *towards* the key level — which is the semantic
 * opposite of Type 1, where price behaves *around* it.
 *
 * ### What this deliberately is not
 *
 * `sweepHigh -> SELL` would be wrong, and is the single easiest way to turn a
 * Nascent reversal into a generic liquidity-grab indicator. Type 2 is an
 * internal *delivery sequence into* the level: price expands towards it in
 * alternating steps, the final extreme interacts with it, and only then does
 * opposite delivery confirm.
 *
 * Bearish shape (bullish mirrors):
 * ```
 *                                  ~~~ external key level ~~~
 *                            H2   <- final extreme interacts with the level
 *                           /  \
 *                 H1       /    \
 *                /  \     /      v  close below L1 confirms the reversal
 *               /    \   L1
 *              L0     \_/
 * ```
 * `H1 < H2` (progressively higher extremes) plus a real intervening low is the
 * "alternating internal expansion"; the close back through that low is the
 * reversal itself.
 */
@Singleton
class Msu2Detector @Inject constructor() {

    /**
     * Recent anchors are swept for the same reason as in Type 1: the reversal
     * close routinely lands a few bars after the final extreme confirmed, by
     * which time another pivot may have been added.
     */
    fun detect(context: NascentInternalContext): NascentSetup? {
        for (offset in 0 until ANCHOR_SWEEP) {
            detectAt(context, context.alternatingPivots.size - 1 - offset)?.let { return it }
        }
        return null
    }

    private fun detectAt(context: NascentInternalContext, anchor: Int): NascentSetup? {
        val direction = context.externalDirection
        val pivots = context.alternatingPivots
        if (anchor < REQUIRED_PIVOTS - 1) return null

        // Expansion runs *against* the trade direction, into the level.
        val approachType =
            if (direction == Direction.BEARISH) StructurePointType.HIGH else StructurePointType.LOW
        val counterType =
            if (direction == Direction.BEARISH) StructurePointType.LOW else StructurePointType.HIGH

        val finalExtreme = pivots.getOrNull(anchor)?.takeIf { it.type == approachType } ?: return null
        val intervening = pivots.getOrNull(anchor - 1)?.takeIf { it.type == counterType }
            ?: return null
        val priorExtreme = pivots.getOrNull(anchor - 2)?.takeIf { it.type == approachType }
            ?: return null

        // 1. Progressive expansion towards the level, not a flat double top.
        val expanding = when (direction) {
            Direction.BEARISH -> finalExtreme.price > priorExtreme.price
            Direction.BULLISH -> finalExtreme.price < priorExtreme.price
        }
        if (!expanding) return null

        // 2. The intervening leg must be a real leg, so the sequence genuinely
        //    alternates rather than drifting in one straight push.
        val legSize = abs(finalExtreme.price - intervening.price)
        val atr = context.atr
        if (atr.isFinite() && atr > 0.0 && legSize < atr * context.config.minLegAtrMultiple) {
            return null
        }

        // 3. The approach must actually reach an external location. Measured
        //    over the whole approach leg rather than at the pivot price alone:
        //    price "moving towards the key level" is a leg that gets there, and
        //    demanding an exact pivot/level coincidence rejects essentially
        //    every real example.
        val keyLevel = context.levelReachedBetween(
            intervening.pivotBarIndex,
            finalExtreme.pivotBarIndex,
        ) ?: return null

        // 4. Opposite delivery confirms: a close back through the intervening
        //    extreme, searched only from the bar the final extreme confirmed.
        val searchFrom = maxOf(finalExtreme.confirmationBarIndex, finalExtreme.pivotBarIndex + 1)
        val reversal = context.firstCloseBeyond(intervening.price, direction, searchFrom)
            ?: return null

        return NascentSetup(
            type = SetupType.MSU2,
            direction = direction,
            originIndex = finalExtreme.pivotBarIndex,
            confirmationIndex = reversal,
            keyLevel = keyLevel,
            protectedExtreme = finalExtreme.price,
            referenceRange = PriceRange(
                low = minOf(finalExtreme.price, intervening.price),
                high = maxOf(finalExtreme.price, intervening.price),
                startIndex = intervening.pivotBarIndex,
                endIndex = finalExtreme.pivotBarIndex,
            ),
            epa = null,
            directPullback = null,
            tom = TomState.UNKNOWN,
            transactions = emptyList(),
            evidence = EvidenceLevel.INFERRED_V1,
            notes = listOf(
                "MSU2 reversal: expansion into ${keyLevel.type.name} at ${keyLevel.price}",
                "Progressive extremes ${priorExtreme.price} -> ${finalExtreme.price}",
                "Reversal confirmed by close through ${intervening.price} at bar $reversal",
            ),
        )
    }

    private companion object {
        /** H1, L1, H2 for the bearish case. */
        const val REQUIRED_PIVOTS = 3

        /** Recent anchor positions examined; see [detect]. */
        const val ANCHOR_SWEEP = 3
    }
}
