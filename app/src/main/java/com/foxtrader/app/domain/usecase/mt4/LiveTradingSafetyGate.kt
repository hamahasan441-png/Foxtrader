package com.foxtrader.app.domain.usecase.mt4

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Safety gate that keeps MT4 live execution disabled.
 *
 * Current product truth: broker-side `placeTrade` / `closeTrade` must remain
 * safety-disabled until the remaining production blockers are complete:
 *   - encrypted, append-only [com.foxtrader.app.domain.usecase.execution.ExecutionAuditLog]
 *   - a MetaApi broker transport adapter producing an
 *     [com.foxtrader.app.domain.usecase.execution.ExecutionReceipt] without logging
 *     raw broker payloads/credentials
 *   - authoritative broker instrument/account metadata + live context
 *   - reconciliation of every ACCEPTED and UNKNOWN receipt
 *   - a user-facing two-step confirmation flow + emergency kill switch
 *
 * Do NOT remove or relax this gate before those are done. The rest of the
 * execution safety stack (coordinator, safety layer, reconciliation) can be
 * built and tested independently, but the MT4 repository itself must keep
 * refusing to place or close live orders.
 */
@Singleton
class LiveTradingSafetyGate @Inject constructor() {

    /** The message surfaced to callers when a live order is refused. */
    fun liveExecutionBlockReason(): String =
        "Live MT4 execution is intentionally disabled while the execution " +
            "adapter, audit log, and reconciliation are completed."

    /** The message surfaced to callers when closing a live position is refused. */
    fun liveCloseBlockReason(): String =
        "Closing live MT4 positions is intentionally disabled while the " +
            "execution adapter and reconciliation are completed."

    /** Always fails: live order placement is disabled. */
    fun assertCanPlaceTrade(): Result<Unit> =
        Result.failure(IllegalStateException(liveExecutionBlockReason()))

    /** Always fails: live position closing is disabled. */
    fun assertCanCloseTrade(): Result<Unit> =
        Result.failure(IllegalStateException(liveCloseBlockReason()))
}
