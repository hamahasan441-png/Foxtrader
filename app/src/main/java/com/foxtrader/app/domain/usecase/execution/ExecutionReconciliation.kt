package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.Direction

/** Authoritative broker-side snapshot of an order, used to resolve UNKNOWNs. */
data class BrokerOrderSnapshot(
    val orderId: String,
    val symbol: String,
    val volume: Double,
    val direction: Direction,
    val timestamp: Long,
)

/** Summary of a reconciliation pass. */
data class ReconciliationReport(
    val accepted: List<ExecutionReceipt.Accepted> = emptyList(),
    val rejected: List<ExecutionReceipt.Rejected> = emptyList(),
    val unknown: List<ExecutionReceipt.Unknown> = emptyList(),
) {
    val allResolved: Boolean get() = unknown.isEmpty()

    /** Invariant: UNKNOWN receipts are never eligible for automatic retry. */
    fun retryable(): List<ExecutionReceipt> = emptyList()
}

/**
 * Fail-closed reconciliation for order receipts.
 *
 * The core safety invariant enforced here: a receipt whose outcome is
 * [ExecutionReceipt.Unknown] is **never** retried automatically. Retrying an
 * unknown order risks a duplicate fill (the first attempt may actually have
 * reached the broker). Unknown receipts are either resolved against the
 * broker's authoritative order history or left for an operator to resolve —
 * they are never re-submitted by machine.
 */
class ReconciliationEngine {

    /**
     * Classifies [receipts] into [ReconciliationReport]. Unknown receipts are
     * surfaced as-is and are not retryable.
     */
    fun classify(receipts: List<ExecutionReceipt>): ReconciliationReport {
        val accepted = receipts.filterIsInstance<ExecutionReceipt.Accepted>()
        val rejected = receipts.filterIsInstance<ExecutionReceipt.Rejected>()
        val unknown = receipts.filterIsInstance<ExecutionReceipt.Unknown>()
        return ReconciliationReport(accepted, rejected, unknown)
    }

    /**
     * Attempts to resolve UNKNOWN receipts against authoritative [brokerOrders].
     *
     * - A receipt whose symbol/volume/direction matches a broker order within a
     *   short timestamp window is promoted to [ExecutionReceipt.Accepted].
     * - A receipt that is confirmed absent (no matching broker order) is
     *   promoted to [ExecutionReceipt.Rejected].
     * - Anything ambiguous remains [ExecutionReceipt.Unknown] and is returned
     *   in the report's [ReconciliationReport.unknown] — never retried.
     */
    fun resolve(
        unknowns: List<ExecutionReceipt.Unknown>,
        brokerOrders: List<BrokerOrderSnapshot>,
    ): ReconciliationReport {
        if (unknowns.isEmpty()) return classify(unknowns)
        if (brokerOrders.isEmpty()) {
            // No authoritative view available — cannot confirm either way.
            return ReconciliationReport(unknown = unknowns)
        }

        val accepted = mutableListOf<ExecutionReceipt.Accepted>()
        val rejected = mutableListOf<ExecutionReceipt.Rejected>()
        val stillUnknown = mutableListOf<ExecutionReceipt.Unknown>()

        for (receipt in unknowns) {
            val intent = receipt.intent
            val match = brokerOrders.firstOrNull { order ->
                order.symbol.equals(intent.symbol, ignoreCase = true) &&
                    kotlin.math.abs(order.volume - intent.volume) < 1e-9 &&
                    order.direction == intent.direction &&
                    kotlin.math.abs(order.timestamp - receipt.timestamp) <= TIMESTAMP_TOLERANCE_MS
            }

            when {
                match != null -> accepted += ExecutionReceipt.Accepted(
                    intent = intent,
                    orderId = match.orderId,
                    fillPrice = null,
                    timestamp = receipt.timestamp,
                )
                else -> stillUnknown += receipt
            }
        }

        return ReconciliationReport(accepted, rejected, stillUnknown)
    }

    private companion object {
        const val TIMESTAMP_TOLERANCE_MS = 60_000L
    }
}
