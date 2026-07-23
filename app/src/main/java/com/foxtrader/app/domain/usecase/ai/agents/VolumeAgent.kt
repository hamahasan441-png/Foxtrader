package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import kotlin.math.abs

/**
 * VOLUME agent — estimates buy/sell pressure (delta) over a recent window and
 * flags relative-volume expansion. Emits a DELTA insight the decision engine
 * uses for the VOLUME confluence. Foundational agent (runs in phase 1).
 */
class VolumeAgent @Inject constructor() : TradingAgent {

    override val name = AgentName.VOLUME
    override val description = "Volume delta (buy vs sell pressure) + relative volume expansion."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val candles = context.candles
        if (candles.size < 25) {
            return neutralOutput(name, "Insufficient data for volume analysis.", start)
        }

        val window = candles.takeLast(WINDOW)
        val buyVol = window.filter { it.isBullish }.sumOf { it.volume }
        val sellVol = window.filter { !it.isBullish }.sumOf { it.volume }
        val totalVol = buyVol + sellVol
        if (totalVol <= 0.0) {
            return neutralOutput(name, "No volume in window.", start)
        }

        val delta = buyVol - sellVol
        val direction = when {
            delta > 0 -> Direction.BULLISH
            delta < 0 -> Direction.BEARISH
            else -> null
        }
        val bias = when (direction) {
            Direction.BULLISH -> Bias.BULLISH
            Direction.BEARISH -> Bias.BEARISH
            null -> Bias.NEUTRAL
        }

        // Delta dominance as a share of total volume -> 0..100 confidence.
        val dominance = (abs(delta) / totalVol) * 100.0
        val relVol = TechnicalIndicators.calculateRelativeVolume(candles, 20).last()
        // Volume expansion boosts confidence (capped).
        val confidence = (dominance * (0.5 + 0.5 * relVol.coerceIn(0.0, 2.0))).coerceIn(0.0, 100.0)

        val insights = if (direction != null) listOf(
            AgentInsight(
                id = "${name}-DELTA-${candles.lastIndex}",
                agentName = name,
                type = "DELTA",
                direction = direction,
                confidence = confidence,
                price = candles.last().close,
                timestamp = candles.last().timestamp,
                barIndex = candles.lastIndex,
                detail = "Delta ${if (delta > 0) "+" else ""}${"%.0f".format(delta)} " +
                    "(${"%.0f".format(dominance)}% dominance), relVol ${"%.2f".format(relVol)}x",
                weight = 1.0,
                tags = listOf("DELTA", "VOLUME"),
            )
        ) else emptyList()

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = bias,
            confidence = confidence,
            insights = insights,
            narrative = "Volume $bias — ${"%.0f".format(dominance)}% delta dominance, " +
                "relative volume ${"%.2f".format(relVol)}x.",
            processingTimeMs = elapsedMs(start),
            timestamp = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val WINDOW = 20
    }
}
