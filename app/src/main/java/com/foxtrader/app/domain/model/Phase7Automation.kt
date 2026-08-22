package com.foxtrader.app.domain.model

/** Execution environment used by the Phase 7 automation policy. */
enum class AutomationEnvironment { PAPER, BROKER_DEMO, LIVE }

/** User-selected automation behavior. Live never supports unattended submission. */
enum class AutomationMode { OFF, REVIEW_QUEUE, AUTO_PAPER_DEMO }

data class AutomationPolicy(
    val mode: AutomationMode = AutomationMode.REVIEW_QUEUE,
    val minimumConfidence: Int = 75,
    val requirePhase4Actionable: Boolean = true,
    val requireConfirmedBar: Boolean = true,
    val maxQueuedSignals: Int = 12,
    val cooldownMinutes: Int = 30,
) {
    fun sanitized(): AutomationPolicy = copy(
        minimumConfidence = minimumConfidence.coerceIn(50, 100),
        maxQueuedSignals = maxQueuedSignals.coerceIn(1, 50),
        cooldownMinutes = cooldownMinutes.coerceIn(1, 240),
    )
}

data class AutomationCandidate(
    val id: String,
    val symbol: String,
    val direction: Direction,
    val confidence: Int,
    val confirmedBar: Boolean,
    val phase4Actionable: Boolean,
    val sourceIsTrustworthy: Boolean,
    val generatedAt: Long,
)

sealed class AutomationDecision {
    data class Rejected(val reasons: List<String>) : AutomationDecision()
    data class QueuedForReview(val candidate: AutomationCandidate) : AutomationDecision()
    data class EligibleForSimulatedAutoExecution(val candidate: AutomationCandidate) : AutomationDecision()
}
