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
 * LIT (Liquidity Inducement Theory) agent — detects when:
 *  1. A sweep (liquidity grab) has occurred (reads from ICT agent's SWEEP insight), AND
 *  2. A structural break follows in the sweep's direction (reads from MARKET_STRUCTURE).
 *
 * The combo is an institutional-entry confirmation. The agent also tags the
 * insight with "SMT" when correlated-pair divergence logic is implemented,
 * contributing to the SMT confluence.
 *
 * Inter-agent: reads MARKET_STRUCTURE + ICT/SMART_MONEY outputs from
 * [AgentContext.previousOutputs]. Runs in phase 2.
 */
class LitAgent @Inject constructor() : TradingAgent {

    override val name = AgentName.LIT
    override val description = "Liquidity Inducement Theory — sweep + structure confirmation."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val candles = context.candles
        if (candles.size < 30) {
            return neutralOutput(name, "Insufficient data for LIT analysis.", start)
        }

        val prev = context.previousOutputs
        val structInsights = prev[AgentName.MARKET_STRUCTURE]?.insights.orEmpty()
        val ictInsights = prev[AgentName.ICT]?.insights.orEmpty()
        val smInsights = prev[AgentName.SMART_MONEY]?.insights.orEmpty()

        // Sweeps from ICT or Smart Money agents.
        val sweeps = (ictInsights + smInsights).filter { insight ->
            insight.type == "LIQUIDITY_SWEEP" || insight.tags.contains("SWEEP")
        }

        // Structure breaks from the structure agent.
        val breaks = structInsights.filter { it.type in STRUCT_TYPES }

        val insights = mutableListOf<AgentInsight>()

        // Combo: sweep direction matches the most recent break direction -> institutional entry.
        val lastSweep = sweeps.lastOrNull { it.direction != null }
        val lastBreak = breaks.lastOrNull { it.direction != null }

        if (lastSweep != null && lastBreak != null && lastSweep.direction == lastBreak.direction) {
            insights += AgentInsight(
                id = "${name}-ENTRY-${candles.lastIndex}",
                agentName = name,
                type = "INSTITUTIONAL_ENTRY_SIGNAL",
                direction = lastSweep.direction,
                confidence = 80.0,
                timestamp = System.currentTimeMillis(),
                barIndex = candles.lastIndex,
                detail = "Sweep + ${lastBreak.type} confirmation → institutional entry ${lastSweep.direction}",
                weight = 2.5,
                tags = listOf("INSTITUTIONAL", "ENTRY_SIGNAL", "LIT"),
            )
        }

        // Inducement detection: an IDM insight present means a trap was set.
        val idm = structInsights.lastOrNull { it.type == "IDM" }
        if (idm != null) {
            insights += AgentInsight(
                id = "${name}-IDM-${idm.barIndex ?: candles.lastIndex}",
                agentName = name,
                type = "INDUCEMENT",
                direction = idm.direction,
                confidence = 60.0,
                timestamp = idm.timestamp,
                barIndex = idm.barIndex,
                detail = "Inducement detected — trap before the real move",
                weight = 1.5,
                tags = listOf("INDUCEMENT", "LIT"),
            )
        }

        if (insights.isEmpty()) {
            return neutralOutput(name, "No LIT setups (sweep + shift combo).", start)
        }

        val bullW = insights.filter { it.direction == Direction.BULLISH }.sumOf { it.weight }
        val bearW = insights.filter { it.direction == Direction.BEARISH }.sumOf { it.weight }
        val bias = when {
            bullW > bearW * 1.2 -> Bias.BULLISH
            bearW > bullW * 1.2 -> Bias.BEARISH
            else -> Bias.NEUTRAL
        }
        val confidence = insights.maxOf { it.confidence }

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = bias,
            confidence = confidence,
            insights = insights,
            narrative = "LIT: ${insights.size} signals. ${if (lastSweep != null && lastBreak != null) "Sweep+break confirmed." else ""} Bias $bias.",
            processingTimeMs = elapsedMs(start),
            timestamp = System.currentTimeMillis(),
        )
    }

    private companion object {
        val STRUCT_TYPES = setOf("BOS", "CHOCH", "MSS", "IDM")
    }
}
