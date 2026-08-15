package com.foxtrader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only persisted record of a live MT4 order attempt.
 *
 * One row per idempotency key (each distinct order intent). Recording the latest
 * receipt for a key is what lets us block duplicate submissions and reconcile an
 * ambiguous (UNKNOWN) order after an app restart, so the same order is never
 * double-submitted to the broker.
 *
 * The raw broker payload is deliberately NOT stored here — only enough to
 * identify and reconcile the order. No credentials are ever persisted.
 */
@Entity(
    tableName = "execution_audit_log",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["status"]),
    ],
)
data class ExecutionAuditLogEntity(
    @PrimaryKey val idempotencyKey: String,
    /** ACCEPTED / REJECTED / UNKNOWN (see [com.foxtrader.app.domain.usecase.execution.ExecutionReceipt]). */
    val status: String,
    val symbol: String,
    val direction: String,
    val volume: Double,
    val entryPrice: Double,
    val stopLoss: Double?,
    val takeProfit: Double?,
    /** Broker order id for ACCEPTED receipts, null otherwise. */
    val orderId: String?,
    /** Semicolon-joined rejection reasons, empty for accepted/unknown. */
    val reasons: String,
    val timestamp: Long,
) {
    companion object {
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_UNKNOWN = "UNKNOWN"
    }
}
