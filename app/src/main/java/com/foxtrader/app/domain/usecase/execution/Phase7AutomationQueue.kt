package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.AutomationCandidate
import com.foxtrader.app.domain.model.AutomationDecision
import com.foxtrader.app.domain.model.AutomationEnvironment
import com.foxtrader.app.domain.model.AutomationPolicy

/** Immutable queue snapshot exposed to presentation/reconciliation surfaces. */
data class AutomationQueueSnapshot(
    val pending: List<AutomationCandidate>,
    val rejectedCount: Long,
    val duplicateCount: Long,
)

/** Result of attempting to route a candidate through the bounded Phase 7 queue. */
sealed class AutomationRouteResult {
    data class Rejected(val reasons: List<String>) : AutomationRouteResult()
    data class Queued(val candidate: AutomationCandidate) : AutomationRouteResult()
    data class SimulatedAutoEligible(val candidate: AutomationCandidate) : AutomationRouteResult()
    data class Duplicate(val candidateId: String) : AutomationRouteResult()
}

/**
 * Thread-safe, bounded, in-memory routing queue.
 *
 * Queue state is intentionally not an order ledger. Broker truth stays in the
 * Phase 6 execution audit/reconciliation path. This queue only prevents noisy
 * duplicate research candidates from reaching the operator repeatedly.
 */
class Phase7AutomationQueue(
    private val engine: Phase7AutomationEngine = Phase7AutomationEngine(),
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, AutomationCandidate>()
    private val lastAcceptedBySymbolDirection = mutableMapOf<String, Long>()
    private var rejectedCount = 0L
    private var duplicateCount = 0L

    fun route(
        candidate: AutomationCandidate,
        policy: AutomationPolicy,
        environment: AutomationEnvironment,
    ): AutomationRouteResult = synchronized(lock) {
        val safe = policy.sanitized()
        if (pending.containsKey(candidate.id)) {
            duplicateCount++
            return@synchronized AutomationRouteResult.Duplicate(candidate.id)
        }

        val dedupeKey = "${candidate.symbol.trim().uppercase()}|${candidate.direction.name}"
        val lastAccepted = lastAcceptedBySymbolDirection[dedupeKey]
        val cooldownMs = safe.cooldownMinutes * 60_000L
        if (lastAccepted != null && candidate.generatedAt - lastAccepted in 0 until cooldownMs) {
            duplicateCount++
            return@synchronized AutomationRouteResult.Duplicate(candidate.id)
        }

        when (val decision = engine.evaluate(candidate, safe, environment)) {
            is AutomationDecision.Rejected -> {
                rejectedCount++
                AutomationRouteResult.Rejected(decision.reasons)
            }
            is AutomationDecision.QueuedForReview -> {
                while (pending.size >= safe.maxQueuedSignals) {
                    val oldest = pending.entries.firstOrNull()?.key ?: break
                    pending.remove(oldest)
                }
                pending[candidate.id] = candidate
                lastAcceptedBySymbolDirection[dedupeKey] = candidate.generatedAt
                AutomationRouteResult.Queued(candidate)
            }
            is AutomationDecision.EligibleForSimulatedAutoExecution -> {
                lastAcceptedBySymbolDirection[dedupeKey] = candidate.generatedAt
                AutomationRouteResult.SimulatedAutoEligible(candidate)
            }
        }
    }

    fun remove(candidateId: String): Boolean = synchronized(lock) { pending.remove(candidateId) != null }

    fun clear() = synchronized(lock) { pending.clear() }

    fun snapshot(): AutomationQueueSnapshot = synchronized(lock) {
        AutomationQueueSnapshot(pending.values.toList(), rejectedCount, duplicateCount)
    }
}
