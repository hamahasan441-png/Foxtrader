package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.PriceZone
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import javax.inject.Inject

/**
 * SMART_MONEY agent — finds the nearest unmitigated order block to current
 * price and biases toward a reaction from that institutional zone.
 * Emits ORDER_BLOCK insights. Runs in phase 2.
 */
class SmartMoneyAgent @Inject constructor(
    private val smcDetector: SmcDetector,
) : TradingAgent {

    override val name = AgentName.SMART_MONEY
    override val description = "Nearest unmitigated order block (supply/demand) analysis."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val candles = context.candles
        if (candles.size < 20) {
            return neutralOutput(name, "Insufficient data for order block analysis.", start)
        }

        val price = context.currentPrice
        val unmitigated = smcDetector.detectOrderBlocks(candles).filter { !it.mitigated }
        if (unmitigated.isEmpty()) {
            return neutralOutput(name, "No unmitigated order blocks.", start)
        }

        // Nearest block by distance of price to the zone (0 when price is inside).
        val nearest = unmitigated.minByOrNull { ob ->
            when {
                price in ob.lowPrice..ob.highPrice -> 0.0
                price < ob.lowPrice -> ob.lowPrice - price
                else -> price - ob.highPrice
            }
        } ?: return neutralOutput(name, "No order block found.", start)

        val direction =
            if (nearest.type == OrderBlockType.BULLISH) Direction.BULLISH else Direction.BEARISH
        val bias = if (direction == Direction.BULLISH) Bias.BULLISH else Bias.BEARISH
        val confidence = (nearest.strength * 100.0).coerceIn(0.0, 100.0)

        val insight = AgentInsight(
            id = "${name}-OB-${nearest.startIndex}",
            agentName = name,
            type = if (nearest.type == OrderBlockType.BULLISH) "BULLISH_OB" else "BEARISH_OB",
            direction = direction,
            confidence = confidence,
            price = if (direction == Direction.BULLISH) nearest.lowPrice else nearest.highPrice,
            timestamp = candles.getOrNull(nearest.startIndex)?.timestamp ?: candles.last().timestamp,
            barIndex = nearest.startIndex,
            zone = PriceZone(nearest.highPrice, nearest.lowPrice),
            detail = "${nearest.type} OB [${nearest.lowPrice} – ${nearest.highPrice}], " +
                "strength ${"%.2f".format(nearest.strength)}",
            weight = 1.4,
            tags = listOf("ORDER_BLOCK"),
        )

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = bias,
            confidence = confidence,
            insights = listOf(insight),
            narrative = "Nearest unmitigated ${nearest.type} order block favors $bias.",
            processingTimeMs = elapsedMs(start),
            timestamp = System.currentTimeMillis(),
        )
    }
}
