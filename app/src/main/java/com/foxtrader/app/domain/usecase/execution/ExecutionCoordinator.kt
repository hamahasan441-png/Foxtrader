package com.foxtrader.app.domain.usecase.execution

import kotlinx.coroutines.CancellationException
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
 *  4. **Durable pre-submit reservation** — records [ExecutionReceipt.Unknown]
 *     before the broker is touched. If durable persistence is unavailable the
 *     order is not submitted. This closes the crash/DB-failure window where a
 *     broker could accept an order but the app would have no idempotency record.
 *  5. **Transport** — invokes [submit] only after that reservation is durable.
 *     The final broker receipt replaces/supersedes the UNKNOWN reservation. If
 *     final persistence fails, UNKNOWN remains the safe user-visible outcome.
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
        // Duplicate-order blocking applies only when the broker may already
        // have seen this intent. A prior local safety REJECTION never reached
        // the broker, so it must be eligible for re-evaluation after the user
        // fixes the blocking condition or performs a fresh confirmation.
        auditLog.findByIdempotencyKey(intent.idempotencyKey)?.let { existing ->
            if (existing is ExecutionReceipt.Accepted || existing is ExecutionReceipt.Unknown) {
                return existing
            }
        }
        // Upgrade compatibility: pre-v10 audit rows did not include account
        // scope in the key. Check the legacy digest as a conservative guard so
        // an UNKNOWN/ACCEPTED order created before upgrade is never blindly
        // re-submitted after account-scoped keys are enabled.
        if (intent.executionScope.isNotBlank()) {
            val legacyKey = TradeIntent.computeLegacyIdempotencyKey(
                intent.symbol,
                intent.direction,
                intent.volume,
                intent.entryPrice,
                intent.stopLoss,
                intent.takeProfit,
            )
            if (legacyKey != intent.idempotencyKey) {
                auditLog.findByIdempotencyKey(legacyKey)?.let { existing ->
                    if (existing is ExecutionReceipt.Accepted || existing is ExecutionReceipt.Unknown) {
                        return existing
                    }
                }
            }
        }

        // Idempotency reservation: prevent concurrent double submission.
        if (!reservedKeys.add(intent.idempotencyKey)) {
            // Do not persist this transient in-flight rejection under the same
            // primary key: it could overwrite the eventual ACCEPTED/UNKNOWN
            // receipt from the request that currently owns the reservation.
            return ExecutionReceipt.Rejected(
                intent = intent,
                reasons = listOf("Duplicate submission blocked for idempotency key ${intent.idempotencyKey.take(8)}…"),
            )
        }

        return try {
            when (val decision = safetyLayer.evaluate(intent, policy, context)) {
                is ExecutionSafetyDecision.Rejected -> {
                    val receipt = ExecutionReceipt.Rejected(intent, decision.reasons)
                    auditLog.record(receipt)
                    receipt
                }
                ExecutionSafetyDecision.Allowed -> {
                    // Durable write-ahead reservation. If this write fails, do
                    // not contact the broker. Once UNKNOWN exists, a crash,
                    // cancellation or post-submit persistence failure cannot
                    // turn a possibly-filled order into a blind retry.
                    val reservation = ExecutionReceipt.Unknown(intent)
                    auditLog.record(reservation)

                    val receipt = submit(intent)
                    try {
                        auditLog.record(receipt)
                        receipt
                    } catch (ce: CancellationException) {
                        // Never swallow structured-concurrency cancellation.
                        // The durable UNKNOWN reservation remains for recovery.
                        throw ce
                    } catch (_: Exception) {
                        // The broker outcome could not be durably finalized.
                        // Keep/surface UNKNOWN so reconciliation is mandatory.
                        reservation
                    }
                }
            }
        } finally {
            reservedKeys.remove(intent.idempotencyKey)
        }
    }
}
