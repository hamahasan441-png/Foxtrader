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

/**
 * MSU Type 1 — **continuation**.
 *
 * That MSU Type 1 is a continuation model is
 * [EvidenceLevel.NASCENT_VERIFIED]; the geometry below is [EvidenceLevel.INFERRED_V1],
 * reconstructed from Nascent's description of how price behaves *around* the
 * key level.
 *
 * Bearish shape (bullish is the exact mirror):
 * ```
 *   H0  protected / main high              <- must survive
 *      \
 *       L0
 *        \    H1  previous internal high
 *         \  /  \
 *          L1    \                         <- new lower low confirms the trend
 *                 H2  pullback high        <- trades THROUGH H1, stays under H0
 *                   \
 *                    continuation below L1 <- delivery resumes
 * ```
 *
 * The pullback deliberately *must* exceed the previous internal high: that
 * excursion is what collects internal liquidity and distinguishes a Type 1 from
 * an ordinary lower-high retracement. Equally, it must stay below the protected
 * high — once that is lost the bearish premise is gone and this is no longer a
 * continuation setup.
 */
@Singleton
class Msu1Detector @Inject constructor() {

    /**
     * A setup does not stop existing because a newer minor pivot printed.
     *
     * The pullback high can confirm several bars before delivery actually
     * resumes, and in that gap another pivot may well be added. Anchoring only
     * on the newest pivot would therefore make the setup visible for a few bars
     * and then silently drop it — the detector would look "broken" while in
     * fact it had simply moved its own goalposts. Each recent anchor position is
     * examined instead, and the caller keeps whichever candidate confirms on the
     * bar being evaluated.
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

        // The anchor pivot is the pullback extreme against the trend.
        val pullbackType =
            if (direction == Direction.BEARISH) StructurePointType.HIGH else StructurePointType.LOW
        val counterType =
            if (direction == Direction.BEARISH) StructurePointType.LOW else StructurePointType.HIGH

        val pullback = pivots.getOrNull(anchor)?.takeIf { it.type == pullbackType } ?: return null
        val trendExtreme = pivots.getOrNull(anchor - 1)?.takeIf { it.type == counterType }
            ?: return null
        val previousInternal = pivots.getOrNull(anchor - 2)?.takeIf { it.type == pullbackType }
            ?: return null
        val previousCounter = pivots.getOrNull(anchor - 3)?.takeIf { it.type == counterType }
            ?: return null
        val protectedExtreme = pivots.getOrNull(anchor - 4)?.takeIf { it.type == pullbackType }
            ?: return null

        // 1. The trend made a genuinely new extreme.
        val newExtremeConfirmed = when (direction) {
            Direction.BEARISH -> trendExtreme.price < previousCounter.price
            Direction.BULLISH -> trendExtreme.price > previousCounter.price
        }
        if (!newExtremeConfirmed) return null

        // 2. The pullback traded through the previous internal extreme.
        val tookInternalLiquidity = when (direction) {
            Direction.BEARISH -> pullback.price > previousInternal.price
            Direction.BULLISH -> pullback.price < previousInternal.price
        }
        if (!tookInternalLiquidity) return null

        // 3. ...but left the protected extreme intact.
        val protectedIntact = when (direction) {
            Direction.BEARISH -> pullback.price < protectedExtreme.price
            Direction.BULLISH -> pullback.price > protectedExtreme.price
        }
        if (!protectedIntact) return null

        // 4. Delivery resumed: a close back through the trend extreme, found
        //    only on bars at or after the pullback became confirmable.
        val searchFrom = maxOf(pullback.confirmationBarIndex, pullback.pivotBarIndex + 1)
        val continuation = context.firstCloseBeyond(trendExtreme.price, direction, searchFrom)
            ?: return null

        if (!legIsSignificant(context, trendExtreme, previousInternal)) return null

        // 5. The manipulation has to have happened at a real external location.
        //    Nascent describes MSU1 as price behaviour *around* the key level,
        //    so the window examined spans the trend extreme through the
        //    pullback — the part of the structure that does the manipulating.
        val keyLevel = context.levelReachedBetween(
            trendExtreme.pivotBarIndex,
            pullback.pivotBarIndex,
        ) ?: return null

        return NascentSetup(
            type = SetupType.MSU1,
            direction = direction,
            originIndex = pullback.pivotBarIndex,
            confirmationIndex = continuation,
            keyLevel = keyLevel,
            protectedExtreme = protectedExtreme.price,
            referenceRange = PriceRange(
                low = minOf(trendExtreme.price, pullback.price),
                high = maxOf(trendExtreme.price, pullback.price),
                startIndex = trendExtreme.pivotBarIndex,
                endIndex = pullback.pivotBarIndex,
            ),
            epa = null,
            directPullback = null,
            tom = TomState.UNKNOWN,
            transactions = emptyList(),
            evidence = EvidenceLevel.INFERRED_V1,
            notes = listOf(
                "MSU1 continuation: new ${if (direction == Direction.BEARISH) "lower low" else "higher high"} confirmed",
                "Pullback took internal liquidity at ${previousInternal.price}",
                "Protected extreme ${protectedExtreme.price} intact",
                "Delivery resumed through ${trendExtreme.price} at bar $continuation",
            ),
        )
    }

    /** Rejects micro-noise legs that technically alternate but carry no delivery. */
    private fun legIsSignificant(
        context: NascentInternalContext,
        trendExtreme: StructurePoint,
        previousInternal: StructurePoint,
    ): Boolean {
        val atr = context.atr
        if (!atr.isFinite() || atr <= 0.0) return true
        val leg = kotlin.math.abs(previousInternal.price - trendExtreme.price)
        return leg >= atr * context.config.minLegAtrMultiple
    }

    private companion object {
        /** H0, L0, H1, L1, H2 for the bearish case. */
        const val REQUIRED_PIVOTS = 5

        /** Recent anchor positions examined; see [detect]. */
        const val ANCHOR_SWEEP = 3
    }
}
