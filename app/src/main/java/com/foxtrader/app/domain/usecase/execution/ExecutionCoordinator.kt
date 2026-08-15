package com.foxtrader.app.domain.usecase.execution

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates a single order attempt through the safety stack and produces an
 * [ExecutionReceipt].
 *
 * Responsibilities, in order:
 *  1. **Duplicate-order blocking** — if a receipt already exists for the
 *     intent's idempotency key, it is returned without touching the broker.
 *  2. **Idempotency reservation** — an in-memory reservation prevents two
 *     concurrent submissions for the same key (e.g. a double-tap on "Place").
 *  3. **Safety evaluation** — [ExecutionSafetyLayer] must [ExecutionSafetyDecision.Allowed]
 *     the intent; otherwise a [ExecutionReceipt.Rejected] is recorded.
 *  4. **Transport** — invokes [submit] only when allowed, then records the
 *     resulting receipt to the [ExecutionAuditLog].
 *
 * [submit] is the broker transport seam (a fake in tests, a MetaApi adapter in
 * production). It must never log raw broker payloads or credentials.
 */
class ExecutionCoordinator(
    private val safetyLayer: ExecutionSafetyLayer,
    private val auditLog: ExecutionAuditLog,
) {

    private val reservedKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Attempts to execute [intent].
     *
     * @param submit the broker transport call, invoked only if every safety
     *   gate passes. Returns the transport's outcome receipt.
     * @return the final [ExecutionReceipt]. A [ExecutionReceipt.Unknown] from
     *   [submit] is recorded and surfaced as-is; it must be reconciled, never
     *   automatically retried.
     */
    suspend fun execute(
        intent: TradeIntent,
        policy: ExecutionPolicy,
        context: ExecutionContext,
        submit: suspend (TradeIntent) -> ExecutionReceipt,
    ): ExecutionReceipt {
        // Duplicate-order blocking: a previously recorded receipt for this
        // exact intent means the order already went out (or was already judged).
        auditLog.findByIdempotencyKey(intent.idempotencyKey)?.let { return it }

        // Idempotency reservation: prevent concurrent double submission.
        if (!reservedKeys.add(intent.idempotencyKey)) {
            val duplicate = ExecutionReceipt.Rejected(
                intent = intent,
                reasons = listOf("Duplicate submission blocked for idempotency key ${intent.idempotencyKey.take(8)}…"),
            )
            auditLog.record(duplicate)
            return duplicate
        }

        return try {
            when (val decision = safetyLayer.evaluate(intent, policy, context)) {
                is ExecutionSafetyDecision.Rejected -> {
                    val receipt = ExecutionReceipt.Rejected(intent, decision.reasons)
                    auditLog.record(receipt)
                    receipt
                }
                ExecutionSafetyDecision.Allowed -> {
                    val receipt = submit(intent)
                    auditLog.record(receipt)
                    receipt
                }
            }
        } finally {
            reservedKeys.remove(intent.idempotencyKey)
        }
    }
}
