package com.foxtrader.app.domain.usecase.execution

/**
 * Outcome of attempting to place an order.
 *
 * - [Accepted]: the transport confirmed the order (with an id). Reconciliation
 *   may still verify later.
 * - [Rejected]: the order was refused before/during submission. Safe to retry
 *   only with a *new* intent.
 * - [Unknown]: the transport could not confirm the outcome (timeout, dropped
 *   connection, ambiguous error). Reconciliation MUST treat this as unresolved;
 *   it must NEVER be automatically retried, because doing so risks a duplicate
 *   fill. An operator must resolve it against the broker's order history.
 */
sealed class ExecutionReceipt {
    abstract val intent: TradeIntent
    abstract val timestamp: Long

    data class Accepted(
        override val intent: TradeIntent,
        val orderId: String,
        val fillPrice: Double? = null,
        /** Realized profit for this receipt when it represents a close (null for opens). */
        val realizedProfit: Double? = null,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ExecutionReceipt()

    data class Rejected(
        override val intent: TradeIntent,
        val reasons: List<String>,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ExecutionReceipt()

    data class Unknown(
        override val intent: TradeIntent,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ExecutionReceipt()
}
