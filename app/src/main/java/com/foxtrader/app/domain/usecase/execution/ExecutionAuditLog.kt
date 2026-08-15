package com.foxtrader.app.domain.usecase.execution

/**
 * Append-only seam for persisting execution receipts.
 *
 * This is an interface/seam only for now: the production implementation
 * (encrypted, append-only Room entity + DAO + retention policy) is a pending
 * blocker. Everything downstream depends on this contract so the rest of the
 * safety stack can be built and tested against an in-memory fake.
 *
 * Contract:
 *  - [record] is append-only; a recorded receipt is never mutated or deleted.
 *  - [findByIdempotencyKey] supports duplicate-order blocking and reconciliation.
 */
interface ExecutionAuditLog {
    suspend fun record(receipt: ExecutionReceipt)

    /** Returns the most recent receipt for an idempotency key, if any. */
    suspend fun findByIdempotencyKey(idempotencyKey: String): ExecutionReceipt?

    /** All receipts in append order. */
    suspend fun all(): List<ExecutionReceipt>
}

/**
 * In-memory fake for tests and for the un-persisted interim. Not thread-safe by
 * itself; production must use the Room-backed implementation.
 */
class InMemoryExecutionAuditLog : ExecutionAuditLog {
    private val receipts = mutableListOf<ExecutionReceipt>()

    override suspend fun record(receipt: ExecutionReceipt) {
        receipts += receipt
    }

    override suspend fun findByIdempotencyKey(idempotencyKey: String): ExecutionReceipt? =
        receipts.lastOrNull { it.intent.idempotencyKey == idempotencyKey }

    override suspend fun all(): List<ExecutionReceipt> = receipts.toList()
}
