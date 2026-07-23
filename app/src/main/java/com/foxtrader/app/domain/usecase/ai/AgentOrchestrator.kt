package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.OrchestratorResult
import kotlin.math.max
import kotlin.math.roundToInt

/** Tunable orchestration parameters. */
data class OrchestratorConfig(
    /** Minimum agents that must agree with the aggregate direction for a signal. */
    val minAgentConsensus: Int = 5,
    /** Minimum aggregate confidence (0..100) to approve a signal. */
    val minConfidence: Double = 60.0,
    /** Per-agent weight multipliers used in aggregation. */
    val agentWeights: Map<AgentName, Double> = DEFAULT_WEIGHTS,
) {
    companion object {
        val DEFAULT_WEIGHTS: Map<AgentName, Double> = mapOf(
            AgentName.MARKET_STRUCTURE to 1.5,
            AgentName.SMART_MONEY to 1.4,
            AgentName.ICT to 1.3,
            AgentName.LIT to 1.3,
            AgentName.VOLUME to 1.0,
            AgentName.TREND to 1.2,
            AgentName.RISK to 1.1,
            AgentName.NEWS to 0.8,
            AgentName.PSYCHOLOGY to 0.7,
            AgentName.STRATEGY to 1.5,
        )
    }
}

/**
 * Coordinates the reasoning agents: runs them in dependency phases (foundational
 * agents first so later agents can read their outputs), then aggregates their
 * biases and insights into a single [OrchestratorResult].
 *
 * Pure and deterministic given the same context + registered agents. Not a
 * singleton by itself — callers register the agents they want (usually all of
 * them, wired via Hilt).
 */
class AgentOrchestrator(
    private var config: OrchestratorConfig = OrchestratorConfig(),
) {

    private val agents = LinkedHashMap<AgentName, TradingAgent>()

    fun registerAgent(agent: TradingAgent) { agents[agent.name] = agent }
    fun unregisterAgent(name: AgentName) { agents.remove(name) }
    fun registeredAgents(): List<AgentName> = agents.keys.toList()
    fun updateConfig(newConfig: OrchestratorConfig) { config = newConfig }
    fun resetAll() { agents.values.forEach { it.reset() } }

    /**
     * Run all registered agents on [context] and aggregate. Agents run in three
     * phases so downstream agents can access upstream outputs via
     * [AgentContext.previousOutputs].
     */
    fun analyze(context: AgentContext): OrchestratorResult {
        val start = System.nanoTime()
        val outputs = LinkedHashMap<AgentName, AgentOutput>()

        for (phase in PHASES) {
            for (agentName in phase) {
                val agent = agents[agentName] ?: continue
                val enriched = context.copy(previousOutputs = HashMap(outputs))
                val output = runCatching { agent.analyze(enriched) }
                    .getOrElse { err -> errorOutput(agentName, err) }
                outputs[agentName] = output
            }
        }

        return aggregate(outputs, context, start)
    }

    private fun aggregate(
        outputs: Map<AgentName, AgentOutput>,
        context: AgentContext,
        startNanos: Long,
    ): OrchestratorResult {
        var bullish = 0.0
        var bearish = 0.0
        var totalWeight = 0.0

        for ((name, output) in outputs) {
            if (output.status == AgentStatus.ERROR) continue
            val weight = config.agentWeights[name] ?: 1.0
            totalWeight += weight

            val contribution = (output.confidence / 100.0) * weight
            when (output.bias) {
                Bias.BULLISH -> bullish += contribution
                Bias.BEARISH -> bearish += contribution
                Bias.NEUTRAL -> Unit // neutral agents don't vote directionally
            }

            // Individual insights nudge the score (smaller weight).
            for (insight in output.insights) {
                val nudge = (insight.confidence / 100.0) * insight.weight * 0.1
                when (insight.direction) {
                    Direction.BULLISH -> bullish += nudge
                    Direction.BEARISH -> bearish += nudge
                    null -> Unit
                }
            }
        }

        // Aggregate direction requires a >15% edge to avoid coin-flip signals.
        val aggregateBias = when {
            bullish > bearish * 1.15 -> Bias.BULLISH
            bearish > bullish * 1.15 -> Bias.BEARISH
            else -> Bias.NEUTRAL
        }
        val signalDirection = when (aggregateBias) {
            Bias.BULLISH -> Direction.BULLISH
            Bias.BEARISH -> Direction.BEARISH
            Bias.NEUTRAL -> null
        }

        val maxScore = max(bullish, bearish)
        val aggregateConfidence =
            if (totalWeight > 0) ((maxScore / totalWeight) * 100.0).coerceIn(0.0, 100.0) else 0.0

        var agentConsensus = 0
        var alignedInsights = 0
        for (output in outputs.values) {
            if (output.bias == aggregateBias && aggregateBias != Bias.NEUTRAL && output.confidence >= 50.0) {
                agentConsensus++
            }
            alignedInsights += output.insights.count { it.direction == signalDirection && signalDirection != null }
        }

        val signalApproved = signalDirection != null &&
            agentConsensus >= config.minAgentConsensus &&
            aggregateConfidence >= config.minConfidence

        return OrchestratorResult(
            timestamp = System.currentTimeMillis(),
            symbol = context.symbol,
            timeframe = context.timeframe,
            agentOutputs = outputs,
            aggregateBias = aggregateBias,
            aggregateConfidence = aggregateConfidence.roundToInt().toDouble(),
            alignedInsightCount = alignedInsights,
            agentConsensus = agentConsensus,
            totalProcessingMs = (System.nanoTime() - startNanos) / 1_000_000L,
            signalApproved = signalApproved,
            signalDirection = if (signalApproved) signalDirection else null,
        )
    }

    private fun errorOutput(name: AgentName, err: Throwable): AgentOutput = AgentOutput(
        agentName = name,
        status = AgentStatus.ERROR,
        bias = Bias.NEUTRAL,
        confidence = 0.0,
        insights = emptyList(),
        narrative = "Agent $name failed: ${err.message}",
        processingTimeMs = 0L,
        timestamp = System.currentTimeMillis(),
    )

    private companion object {
        // Foundational agents run first; strategy-like agents run last.
        val PHASES: List<List<AgentName>> = listOf(
            listOf(AgentName.MARKET_STRUCTURE, AgentName.VOLUME, AgentName.TREND, AgentName.NEWS),
            listOf(AgentName.SMART_MONEY, AgentName.ICT, AgentName.LIT, AgentName.RISK, AgentName.PSYCHOLOGY),
            listOf(AgentName.STRATEGY),
        )
    }
}
