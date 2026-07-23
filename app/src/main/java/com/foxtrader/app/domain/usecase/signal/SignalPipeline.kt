package com.foxtrader.app.domain.usecase.signal

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.DecisionResult

/**
 * Extension point for custom signal-processing steps injected between the AI
 * decision pipeline and the actual alert/order dispatch.
 *
 * Implementations may enrich, filter, throttle, or veto a [DecisionResult]
 * before it reaches the alerting or order management layer. Processors are
 * stateless by convention — any state that must survive calls (e.g. cooldowns)
 * should be held in a dedicated singleton and injected.
 *
 * USAGE: bind custom implementations via Hilt multibindings and inject the
 * resulting `Set<SignalProcessor>` into [SignalPipeline].
 */
fun interface SignalProcessor {
    /**
     * Processes a decision result in the context of the current market.
     *
     * @param decision the output of [com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine].
     * @param context  the full agent context that produced the decision.
     * @return a potentially modified [DecisionResult]; return the same object
     *         unchanged if no transformation is needed.
     */
    fun process(decision: DecisionResult, context: AgentContext): DecisionResult
}

/**
 * Ordered pipeline of [SignalProcessor]s applied left-to-right to a
 * [DecisionResult] before dispatch.
 *
 * Processors run in insertion order. If any processor sets
 * [DecisionResult.approved] to `false`, subsequent processors still run (they
 * may add rejection reasons), but a disapproved result will not trigger alerts.
 *
 * Pure domain class — no platform dependencies.
 */
class SignalPipeline(
    private val processors: List<SignalProcessor> = emptyList(),
) {

    /**
     * Run every registered processor in order.
     *
     * @param decision initial decision from the Master Decision Engine.
     * @param context  agent context for the current analysis cycle.
     * @return the final [DecisionResult] after all processors have run.
     */
    fun process(decision: DecisionResult, context: AgentContext): DecisionResult =
        processors.fold(decision) { acc, processor ->
            runCatching { processor.process(acc, context) }.getOrElse { acc }
        }

    /** Returns `true` when no processors are registered (no-op pipeline). */
    val isEmpty: Boolean get() = processors.isEmpty()

    companion object {
        /** A pipeline with no processors (passes decisions through unchanged). */
        val PASSTHROUGH = SignalPipeline()
    }
}
