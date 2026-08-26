package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel
import com.foxtrader.app.domain.usecase.nascent.model.PriceRange
import com.foxtrader.app.domain.usecase.nascent.model.TransactionState
import com.foxtrader.app.domain.usecase.nascent.model.TransactionType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nascent transaction classifier.
 *
 * The vocabulary — Range Transaction, Simple Transaction, Structure Point
 * Transaction — is strongly corroborated, but the exact geometry is
 * [EvidenceLevel.UNRESOLVED]. This engine therefore stays deliberately thin and
 * honest: it reports what price demonstrably did to a reference and tags every
 * result UNRESOLVED, rather than pretending the mapping is settled.
 *
 * Specifically, "closed through both boundaries" is a working reconstruction of
 * a Range Transaction, **not** an official Nascent rule, and the same applies to
 * the single-boundary and structure-point variants.
 *
 * Consumers use these as supporting context. Nothing in the pipeline is allowed
 * to hinge a signal purely on a transaction classification.
 */
@Singleton
class NascentTransactionEngine @Inject constructor() {

    /**
     * Transactions performed against [reference] between [fromIndex] and
     * [toIndex] inclusive. Reads no bar outside that window, so the result is a
     * pure function of already-closed data.
     */
    fun transactions(
        candles: List<Candle>,
        reference: PriceRange,
        fromIndex: Int,
        toIndex: Int,
    ): List<TransactionState> {
        val from = fromIndex.coerceAtLeast(0)
        val to = toIndex.coerceAtMost(candles.lastIndex)
        if (from > to || reference.size <= EPSILON) return emptyList()

        var throughHigh: Int? = null
        var throughLow: Int? = null
        for (index in from..to) {
            val close = candles[index].close
            if (!close.isFinite()) continue
            if (throughHigh == null && close > reference.high) throughHigh = index
            if (throughLow == null && close < reference.low) throughLow = index
        }

        val out = ArrayList<TransactionState>(2)
        if (throughHigh != null) {
            out += TransactionState(
                type = TransactionType.SIMPLE,
                direction = Direction.BULLISH,
                sourceIndex = from,
                destinationIndex = throughHigh,
                confirmed = true,
                evidence = EvidenceLevel.UNRESOLVED,
            )
        }
        if (throughLow != null) {
            out += TransactionState(
                type = TransactionType.SIMPLE,
                direction = Direction.BEARISH,
                sourceIndex = from,
                destinationIndex = throughLow,
                confirmed = true,
                evidence = EvidenceLevel.UNRESOLVED,
            )
        }
        // Both sides taken, in a definite order: the reference range as a whole
        // has been transacted rather than merely poked at from one side.
        if (throughHigh != null && throughLow != null) {
            val first = minOf(throughHigh, throughLow)
            val second = maxOf(throughHigh, throughLow)
            out += TransactionState(
                type = TransactionType.RANGE,
                direction = if (second == throughHigh) Direction.BULLISH else Direction.BEARISH,
                sourceIndex = first,
                destinationIndex = second,
                confirmed = true,
                evidence = EvidenceLevel.UNRESOLVED,
            )
        }
        return out
    }

    /** A close through one specific confirmed structure point. */
    fun structurePointTransaction(
        candles: List<Candle>,
        level: Double,
        direction: Direction,
        fromIndex: Int,
        toIndex: Int,
    ): TransactionState? {
        val from = fromIndex.coerceAtLeast(0)
        val to = toIndex.coerceAtMost(candles.lastIndex)
        if (from > to || !level.isFinite()) return null
        val hit = (from..to).firstOrNull { index ->
            val close = candles[index].close
            close.isFinite() && when (direction) {
                Direction.BULLISH -> close > level
                Direction.BEARISH -> close < level
            }
        } ?: return null
        return TransactionState(
            type = TransactionType.STRUCTURE_POINT,
            direction = direction,
            sourceIndex = from,
            destinationIndex = hit,
            confirmed = true,
            evidence = EvidenceLevel.UNRESOLVED,
        )
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}
