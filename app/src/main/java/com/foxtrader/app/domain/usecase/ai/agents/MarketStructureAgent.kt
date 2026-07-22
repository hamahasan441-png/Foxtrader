package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import javax.inject.Inject

/**
 * MARKET_STRUCTURE agent — reads swing structure to derive HTF directional bias
 * and emits BOS / CHoCH / MSS insights. Foundational agent (runs in phase 1).
 */
class MarketStructureAgent @Inject constructor(
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
) : TradingAgent {

    override val name = AgentName.MARKET_STRUCTURE
    override val description = "Detects BOS/CHoCH/MSS and derives HTF directional bias."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val candles = context.candles
        if (candles.size < 20) {
            return neutralOutput(name, "Insufficient data for structure analysis.", start)
        }

        val structure = analyzeStructure(candles)
        val recent = structure.breaks.filter { it.confirmed }.takeLast(5)
        if (recent.isEmpty()) {
            return neutralOutput(name, "No confirmed structure breaks yet.", start)
        }

        val insights = recent.map { brk ->
            AgentInsight(
                id = "${name}-${brk.type}-${brk.breakIndex}",
                agentName = name,
                type = brk.type.name, // BOS / CHOCH / MSS / IDM
                direction = brk.direction,
                confidence = weightFor(brk.type) * 60.0, // scaled per break type
                price = brk.breakPrice,
                timestamp = brk.breakTimestamp,
                barIndex = brk.breakIndex,
                detail = "${brk.type} ${brk.direction} @ ${brk.breakPrice}",
                weight = weightFor(brk.type),
                tags = listOf(brk.type.name),
            )
        }

        val bias = structure.bias
        val aligned = recent.count { bias.agreesWith(it.direction) }
        val confidence = (aligned.toDouble() / recent.size) * 100.0

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = bias,
            confidence = confidence,
            insights = insights,
            narrative = "Structure bias $bias from ${recent.size} recent breaks " +
                "($aligned aligned). Latest: ${recent.last().type} ${recent.last().direction}.",
            processingTimeMs = elapsedMs(start),
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun weightFor(type: StructureBreakType): Double = when (type) {
        StructureBreakType.MSS -> 1.5
        StructureBreakType.CHOCH -> 1.3
        StructureBreakType.BOS -> 1.0
        StructureBreakType.IDM -> 0.6
    }
}
