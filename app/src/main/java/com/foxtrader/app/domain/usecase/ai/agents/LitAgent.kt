package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import com.foxtrader.app.domain.usecase.signalintel.ConfirmedBarPolicy
import com.foxtrader.app.domain.usecase.signalintel.LitEngine
import javax.inject.Inject

/**
 * Canonical LiT (Liquidity Inducement Theory) agent.
 *
 * This agent is deliberately fail-closed. It may emit execution-grade LiT
 * evidence only when the repository's canonical [LitEngine] validates the full
 * confirmed-bar sequence. Generic upstream SMC/ICT sweeps, BOS/CHOCH events,
 * direct equal-high/equal-low heuristics, or SMT divergence are not allowed to
 * resurrect a setup rejected by [LitEngine]. Those remain independent evidence
 * in their own engines/agents and must not be double-counted as LiT.
 */
class LitAgent @Inject constructor(
    private val litEngine: LitEngine = LitEngine(
        smcDetector = com.foxtrader.app.domain.usecase.smc.SmcDetector(),
        analyzeStructure = com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase(),
        displacementDetector = com.foxtrader.app.domain.usecase.litx.DisplacementDetector(),
        premiumDiscount = com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator(),
    ),
) : TradingAgent {

    override val name = AgentName.LIT
    override val description = "Canonical Liquidity Inducement Theory — validated confirmed-bar LiT sequence only."
    override val version = "2.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val confirmedIndex = ConfirmedBarPolicy.latestConfirmedIndex(
            context.candles,
            context.timeframe,
            System.currentTimeMillis(),
        )
        val candles = if (confirmedIndex >= 0) {
            context.candles.subList(0, confirmedIndex + 1)
        } else {
            emptyList()
        }

        if (candles.size < MIN_CANDLES) {
            return neutralOutput(name, "Insufficient confirmed data for canonical LiT analysis.", start)
        }

        val analysis = litEngine.analyze(
            symbol = context.symbol,
            timeframe = context.timeframe,
            candles = candles,
        )
        val signal = analysis.signal
            ?: return neutralOutput(
                name,
                "Canonical LiT rejected the current setup at ${analysis.stage}; no fallback evidence may override it.",
                start,
            )

        // Defense in depth: a canonical signal must be stamped on an actually
        // confirmed bar inside the exact prefix supplied to the engine.
        if (
            signal.confirmationIndex !in candles.indices ||
            signal.confirmationIndex != candles.lastIndex ||
            signal.timestamp != candles[signal.confirmationIndex].timestamp
        ) {
            return neutralOutput(
                name,
                "Canonical LiT signal failed confirmation-boundary validation.",
                start,
            )
        }

        val insight = AgentInsight(
            id = "${name}-CANONICAL-${signal.confirmationIndex}-${signal.direction}",
            agentName = name,
            type = "LIT_CANONICAL_VALIDATED",
            direction = signal.direction,
            confidence = signal.confidence.toDouble(),
            price = signal.entry,
            timestamp = signal.timestamp,
            barIndex = signal.confirmationIndex,
            detail = signal.rationale,
            weight = CANONICAL_WEIGHT,
            tags = signal.confirmations + listOf(
                "LIT",
                "CANONICAL",
                "SEQUENCE_VALIDATED",
                "NON_REPAINT",
                "NO_FALLBACK",
            ),
        )

        val bias = when (signal.direction) {
            Direction.BULLISH -> Bias.BULLISH
            Direction.BEARISH -> Bias.BEARISH
        }

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = bias,
            confidence = signal.confidence.toDouble(),
            insights = listOf(insight),
            narrative = "LiT: canonical validated sequence at confirmed bar ${signal.confirmationIndex}. Bias $bias.",
            processingTimeMs = elapsedMs(start),
            // Output transport time may be wall clock; the executable insight
            // itself is replay-stamped to the confirmed market candle above.
            timestamp = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val MIN_CANDLES = 60
        const val CANONICAL_WEIGHT = 2.8
    }
}
