package com.foxtrader.app.domain.usecase.nascent.msu

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.NascentConfig
import com.foxtrader.app.domain.usecase.nascent.NascentInternalContext
import com.foxtrader.app.domain.usecase.nascent.NascentTransactionEngine
import com.foxtrader.app.domain.usecase.nascent.model.EpaState
import com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel
import com.foxtrader.app.domain.usecase.nascent.model.NascentMode
import com.foxtrader.app.domain.usecase.nascent.model.NascentSetup
import com.foxtrader.app.domain.usecase.nascent.model.PriceRange
import com.foxtrader.app.domain.usecase.nascent.model.SetupType
import com.foxtrader.app.domain.usecase.nascent.model.TomState
import com.foxtrader.app.domain.usecase.nascent.model.TransactionType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MSU Type 3 — **continuation** through a two-sided transaction.
 *
 * That MSU Type 3 is a continuation model is [EvidenceLevel.NASCENT_VERIFIED],
 * as is Nascent's statement that EPA support generally *improves* its success
 * probability. That wording matters: EPA is a quality enhancer here, not an
 * absolute precondition, so it is required only in
 * [NascentMode.SOURCE_STRICT] and otherwise contributes to confidence.
 *
 * The geometry is [EvidenceLevel.INFERRED_V1]:
 * ```
 *   reference range  [====================]
 *                      |            |
 *                      |            +--- 2nd transaction, in the trade
 *                      |                 direction  -> continuation
 *                      +--- 1st transaction, through the opposite side
 * ```
 * Reducing this to `sweepHigh && sweepLow` would lose the part that matters:
 * both the **order** of the two transactions and the delivery that follows the
 * second one. A range poked from both sides in the wrong sequence is not a
 * Type 3.
 */
@Singleton
class Msu3Detector @Inject constructor(
    private val transactionEngine: NascentTransactionEngine,
) {

    /**
     * Recent anchors are swept so a two-sided transaction stays detectable
     * while its continuation delivery is still developing.
     */
    fun detect(context: NascentInternalContext, epa: EpaState?): NascentSetup? {
        for (offset in 0 until ANCHOR_SWEEP) {
            detectAt(context, epa, context.alternatingPivots.size - 1 - offset)?.let { return it }
        }
        return null
    }

    private fun detectAt(
        context: NascentInternalContext,
        epa: EpaState?,
        anchor: Int,
    ): NascentSetup? {
        val direction = context.externalDirection
        val pivots = context.alternatingPivots
        if (anchor < REQUIRED_PIVOTS - 1) return null

        if (context.config.mode == NascentMode.SOURCE_STRICT && epa?.confirmed != true) return null

        // The reference range is a completed earlier range, never the leg still
        // in progress, so the transactions through it are unambiguous.
        val rangeStart = pivots[anchor - 2]
        val rangeEnd = pivots[anchor - 1]
        val from = minOf(rangeStart.pivotBarIndex, rangeEnd.pivotBarIndex)
        val to = maxOf(rangeStart.pivotBarIndex, rangeEnd.pivotBarIndex)
        if (from >= to || to > context.candles.lastIndex) return null

        val window = context.candles.subList(from, to + 1)
        val high = window.maxOf { it.high }
        val low = window.minOf { it.low }
        if (!high.isFinite() || !low.isFinite() || high <= low) return null
        val atr = context.atr
        if (atr.isFinite() && atr > 0.0 && (high - low) < atr * context.config.minLegAtrMultiple) {
            return null
        }
        val reference = PriceRange(low = low, high = high, startIndex = from, endIndex = to)

        // Transactions are only searched after the range completed.
        val searchFrom = maxOf(rangeEnd.confirmationBarIndex, to + 1)
        if (searchFrom > context.atIndex) return null
        val transactions = transactionEngine.transactions(
            candles = context.candles,
            reference = reference,
            fromIndex = searchFrom,
            toIndex = context.atIndex,
        )
        val rangeTransaction = transactions.firstOrNull { it.type == TransactionType.RANGE }
            ?: return null

        // The *second* side taken has to be the trade direction, otherwise this
        // is a range being transacted against the external premise.
        if (rangeTransaction.direction != direction) return null
        val secondIndex = rangeTransaction.destinationIndex ?: return null
        val firstIndex = rangeTransaction.sourceIndex ?: return null

        // Continuation: bodied delivery in the trade direction at or after the
        // second transaction.
        val continuation = (secondIndex..context.atIndex).firstOrNull { index ->
            context.isDelivery(index, direction)
        } ?: return null

        // The two-sided transaction must have taken place at a real external
        // location, not at an arbitrary range somewhere on the chart.
        val keyLevel = context.levelReachedBetween(from, secondIndex) ?: return null

        val protectedExtreme = if (direction == Direction.BULLISH) low else high
        return NascentSetup(
            type = SetupType.MSU3,
            direction = direction,
            originIndex = firstIndex,
            confirmationIndex = continuation,
            keyLevel = keyLevel,
            protectedExtreme = protectedExtreme,
            referenceRange = reference,
            epa = epa,
            directPullback = null,
            tom = TomState.UNKNOWN,
            transactions = transactions,
            evidence = EvidenceLevel.INFERRED_V1,
            notes = buildList {
                add("MSU3 continuation over range [$low, $high]")
                add("First transaction at bar $firstIndex, second at bar $secondIndex")
                add("Continuation delivery confirmed at bar $continuation")
                add(if (epa?.confirmed == true) "EPA supported" else "EPA absent — lower confidence")
            },
        )
    }

    private companion object {
        const val REQUIRED_PIVOTS = 3

        /** Recent anchor positions examined; see [detect]. */
        const val ANCHOR_SWEEP = 3
    }
}
