package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import javax.inject.Inject

/**
 * STRATEGY agent — the synthesis agent that runs last (phase 3).
 *
 * Reads ALL other agents' outputs via [AgentContext.previousOutputs] and
 * produces a high-level strategy recommendation:
 *  - Tallies directional weight across agent biases.
 *  - Detects alignment (majority agreement) and flags disagreement.
 *  - Emits a final STRATEGY_RECOMMENDATION insight.
 *
 * This is NOT the final decision (that's the MasterDecisionEngine); it's a
 * synthesis that the orchestrator weights alongside the others.
 */
class StrategyAgent @Inject constructor() : TradingAgent {

    override val name = AgentName.STRATEGY
    override val description = "Final synthesis across all agents — strategy recommendation."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val prev = context.previousOutputs
        if (prev.isEmpty()) {
            return neutralOutput(name, "No prior agent outputs to synthesize.", start)
        }

        // Weighted vote across all agents.
        var bullish = 0.0
        var bearish = 0.0
        val details = mutableListOf<String>()
        var blockedBy: String? = null

        for ((agentName, output) in prev) {
            if (output.status != AgentStatus.COMPLETE) continue
            // Detect a block from Risk/Psychology/News.
            if (output.insights.any { it.tags.contains("BLOCK") }) {
                blockedBy = agentName.name
            }
            val w = WEIGHTS[agentName] ?: 1.0
            val contribution = (output.confidence / 100.0) * w
            when (output.bias) {
                Bias.BULLISH -> { bullish += contribution; details += "$agentName: BULL ${output.confidence.toInt()}%" }
                Bias.BEARISH -> { bearish += contribution; details += "$agentName: BEAR ${output.confidence.toInt()}%" }
                Bias.NEUTRAL -> details += "$agentName: NEUTRAL"
            }
        }

        // If any agent blocked, strategy should reflect it.
        if (blockedBy != null) {
            return AgentOutput(
                agentName = name,
                status = AgentStatus.COMPLETE,
                bias = Bias.NEUTRAL,
                confidence = 0.0,
                insights = listOf(
                    AgentInsight(
                        id = "$name-BLOCKED",
                        agentName = name,
                        type = "STRATEGY_BLOCKED",
                        direction = null,
                        confidence = 0.0,
                        timestamp = System.currentTimeMillis(),
                        detail = "Strategy blocked by $blockedBy",
                        weight = 0.0,
                        tags = listOf("BLOCKED"),
                    )
                ),
                narrative = "Strategy BLOCKED by $blockedBy — no recommendation.",
                processingTimeMs = elapsedMs(start),
                timestamp = System.currentTimeMillis(),
            )
        }

        val total = bullish + bearish
        val direction = when {
            total == 0.0 -> null
            bullish > bearish * 1.15 -> Direction.BULLISH
            bearish > bullish * 1.15 -> Direction.BEARISH
            else -> null
        }
        val bias = when (direction) {
            Direction.BULLISH -> Bias.BULLISH
            Direction.BEARISH -> Bias.BEARISH
            null -> Bias.NEUTRAL
        }
        // Confidence = the dominant side's share of total weight, scaled to 100.
        val dominantScore = maxOf(bullish, bearish)
        val confidence = if (total > 0) ((dominantScore / total) * 100.0).coerceIn(0.0, 100.0) else 0.0

        val insights = if (direction != null) listOf(
            AgentInsight(
                id = "$name-REC-${context.candles.lastIndex}",
                agentName = name,
                type = "STRATEGY_RECOMMENDATION",
                direction = direction,
                confidence = confidence,
                timestamp = System.currentTimeMillis(),
                barIndex = context.candles.lastIndex,
                detail = "Strategy recommends $direction (${"%.0f".format(confidence)}% synthesized).",
                weight = 1.5,
                tags = listOf("STRATEGY"),
            )
        ) else emptyList()

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = bias,
            confidence = confidence,
            insights = insights,
            narrative = "Strategy $bias (${"%.0f".format(confidence)}%). Inputs: ${details.joinToString("; ")}.",
            processingTimeMs = elapsedMs(start),
            timestamp = System.currentTimeMillis(),
        )
    }

    private companion object {
        val WEIGHTS = mapOf(
            AgentName.MARKET_STRUCTURE to 1.5,
            AgentName.SMART_MONEY to 1.4,
            AgentName.ICT to 1.3,
            AgentName.LIT to 1.3,
            AgentName.VOLUME to 1.0,
            AgentName.TREND to 1.2,
            AgentName.RISK to 0.0,      // risk doesn't vote directionally
            AgentName.NEWS to 0.0,      // news doesn't vote directionally
            AgentName.PSYCHOLOGY to 0.0, // psychology doesn't vote directionally
        )
    }
}
