package com.foxtrader.app.domain.usecase.execution

/**
 * Durable execution-state seam keyed by [TradeIntent.idempotencyKey].
 *
 * The latest receipt for a key is stored atomically/upserted. This supports a
 * write-ahead UNKNOWN reservation before broker submission and a later
 * transition to ACCEPTED/REJECTED after a definitive broker result.
 *
 * Contract:
 *  - [record] must be durable before it returns in production.
 *  - [findByIdempotencyKey] must expose the latest state for duplicate-order
 *    blocking and reconciliation.
 *  - UNKNOWN/ACCEPTED states must survive process death.
 */
interface ExecutionAuditLog {
    suspend fun record(receipt: ExecutionReceipt)

    /** Returns the latest receipt for an idempotency key, if any. */
    suspend fun findByIdempotencyKey(idempotencyKey: String): ExecutionReceipt?

    /** Latest receipt for each idempotency key. */
    suspend fun all(): List<ExecutionReceipt>
}

/**
 * In-memory test implementation with the same latest-state-per-key semantics
 * as the Room implementation. Synchronization makes concurrency tests honest.
 */
class InMemoryExecutionAuditLog : ExecutionAuditLog {
    private val lock = Any()
    private val receipts = LinkedHashMap<String, ExecutionReceipt>()

    override suspend fun record(receipt: ExecutionReceipt) {
        synchronized(lock) {
            receipts[receipt.intent.idempotencyKey] = receipt
        }
    }

    override suspend fun findByIdempotencyKey(idempotencyKey: String): ExecutionReceipt? =
        synchronized(lock) { receipts[idempotencyKey] }

    override suspend fun all(): List<ExecutionReceipt> =
        synchronized(lock) { receipts.values.toList() }
}
