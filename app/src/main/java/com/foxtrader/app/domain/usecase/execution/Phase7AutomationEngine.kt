package com.foxtrader.app.domain.usecase.execution

import com.foxtrader.app.domain.model.AutomationCandidate
import com.foxtrader.app.domain.model.AutomationDecision
import com.foxtrader.app.domain.model.AutomationEnvironment
import com.foxtrader.app.domain.model.AutomationMode
import com.foxtrader.app.domain.model.AutomationPolicy

/**
 * Phase 7 signal-to-execution policy gate.
 *
 * It never places an order. It only decides whether an already-computed signal
 * is rejected, queued for explicit review, or eligible for simulated/demo
 * automation. Live-money candidates are always forced through manual review so
 * the Phase 6 confirmation + execution-safety layer remains authoritative.
 */
class Phase7AutomationEngine {
    fun evaluate(
        candidate: AutomationCandidate,
        policy: AutomationPolicy,
        environment: AutomationEnvironment,
    ): AutomationDecision {
        val safe = policy.sanitized()
        val reasons = mutableListOf<String>()

        if (safe.mode == AutomationMode.OFF) reasons += "Automation is disabled"
        if (!candidate.sourceIsTrustworthy) reasons += "Signal data source is not trustworthy"
        if (candidate.confidence < safe.minimumConfidence) {
            reasons += "Confidence ${candidate.confidence} is below ${safe.minimumConfidence}"
        }
        if (safe.requireConfirmedBar && !candidate.confirmedBar) reasons += "Signal bar is not confirmed"
        if (safe.requirePhase4Actionable && !candidate.phase4Actionable) reasons += "Phase 4 confluence gate failed"

        if (reasons.isNotEmpty()) return AutomationDecision.Rejected(reasons)

        if (environment == AutomationEnvironment.LIVE) {
            return AutomationDecision.QueuedForReview(candidate)
        }

        return when (safe.mode) {
            AutomationMode.OFF -> AutomationDecision.Rejected(listOf("Automation is disabled"))
            AutomationMode.REVIEW_QUEUE -> AutomationDecision.QueuedForReview(candidate)
            AutomationMode.AUTO_PAPER_DEMO -> AutomationDecision.EligibleForSimulatedAutoExecution(candidate)
        }
    }
}
