package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.PriceZone
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import javax.inject.Inject

/**
 * ICT agent — Fair Value Gaps, liquidity sweeps, and kill-zone (session)
 * timing. Emits FVG, LIQUIDITY_SWEEP, and KILL_ZONE (SESSION) insights.
 * Runs in phase 2.
 */
class IctAgent @Inject constructor(
    private val smcDetector: SmcDetector,
) : TradingAgent {

    override val name = AgentName.ICT
    override val description = "Fair Value Gaps, liquidity sweeps, and kill-zone timing."
    override val version = "1.0.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val candles = context.candles
        if (candles.size < 20) {
            return neutralOutput(name, "Insufficient data for ICT analysis.", start)
        }

        val insights = mutableListOf<AgentInsight>()
        val lastIndex = candles.lastIndex

        // --- Fair Value Gap (most recent unfilled) ---
        val fvg = smcDetector.detectFairValueGaps(candles).lastOrNull { !it.filled }
        var fvgDirection: Direction? = null
        if (fvg != null) {
            fvgDirection = if (fvg.type == FvgType.BULLISH) Direction.BULLISH else Direction.BEARISH
            insights += AgentInsight(
                id = "${name}-FVG-${fvg.index}",
                agentName = name,
                type = "FVG",
                direction = fvgDirection,
                confidence = ((1.0 - fvg.fillPercent) * 80.0).coerceIn(0.0, 100.0),
                price = (fvg.highPrice + fvg.lowPrice) / 2.0,
                timestamp = candles.getOrNull(fvg.index)?.timestamp ?: candles.last().timestamp,
                barIndex = fvg.index,
                zone = PriceZone(fvg.highPrice, fvg.lowPrice),
                detail = "${fvg.type} FVG [${fvg.lowPrice} – ${fvg.highPrice}], " +
                    "${"%.0f".format(fvg.fillPercent * 100)}% filled",
                weight = 1.3,
                tags = listOf("FVG"),
            )
        }

        // --- Liquidity sweep (recent) -> expect reversal in the opposite direction ---
        val recentSweep = smcDetector.detectLiquidity(candles)
            .filter { it.swept && it.sweepIndex != null }
            .maxByOrNull { it.sweepIndex ?: 0 }
        var sweepDirection: Direction? = null
        if (recentSweep?.sweepIndex != null && recentSweep.sweepIndex!! >= lastIndex - SWEEP_RECENCY) {
            // Sell-side liquidity swept (lows taken) -> bullish reversal, and vice versa.
            sweepDirection =
                if (recentSweep.type == LiquidityType.SELL_SIDE) Direction.BULLISH else Direction.BEARISH
            insights += AgentInsight(
                id = "${name}-SWEEP-${recentSweep.sweepIndex}",
                agentName = name,
                type = "LIQUIDITY_SWEEP",
                direction = sweepDirection,
                confidence = 70.0,
                price = recentSweep.price,
                timestamp = candles.getOrNull(recentSweep.sweepIndex!!)?.timestamp
                    ?: candles.last().timestamp,
                barIndex = recentSweep.sweepIndex,
                detail = "${recentSweep.type} liquidity swept @ ${recentSweep.price} " +
                    "-> $sweepDirection reversal expected",
                weight = 1.4,
                tags = listOf("SWEEP", "LIQUIDITY_SWEEP"),
            )
        }

        // --- Kill zone (session timing) ---
        val killZone = killZoneFor(candles.last().timestamp)
        if (killZone != null) {
            insights += AgentInsight(
                id = "${name}-KZ-$lastIndex",
                agentName = name,
                type = "KILL_ZONE",
                direction = null,
                confidence = 60.0,
                timestamp = candles.last().timestamp,
                barIndex = lastIndex,
                detail = "$killZone kill zone active",
                weight = 0.8,
                tags = listOf("SESSION", "KILL_ZONE"),
            )
        }

        // Bias: prefer sweep-reversal, else FVG direction.
        val direction = sweepDirection ?: fvgDirection
        val bias = when (direction) {
            Direction.BULLISH -> Bias.BULLISH
            Direction.BEARISH -> Bias.BEARISH
            null -> Bias.NEUTRAL
        }
        val confidence = insights
            .filter { it.direction == direction && direction != null }
            .maxOfOrNull { it.confidence } ?: 0.0

        if (insights.isEmpty()) {
            return neutralOutput(name, "No ICT signatures (FVG/sweep/kill-zone).", start)
        }

        return AgentOutput(
            agentName = name,
            status = AgentStatus.COMPLETE,
            bias = bias,
            confidence = confidence,
            insights = insights,
            narrative = buildString {
                append("ICT bias $bias.")
                if (sweepDirection != null) append(" Liquidity swept.")
                if (fvg != null) append(" Unfilled ${fvg.type} FVG present.")
                if (killZone != null) append(" $killZone kill zone.")
            },
            processingTimeMs = elapsedMs(start),
            timestamp = System.currentTimeMillis(),
        )
    }

    /** Map a UTC timestamp to an active ICT kill zone, or null. */
    private fun killZoneFor(timestampMs: Long): String? {
        val hourUtc = ((timestampMs / 3_600_000L) % 24L).toInt()
        return when (hourUtc) {
            in 0..2 -> "Asian range"
            in 7..9 -> "London open"
            in 12..14 -> "New York open"
            in 15..16 -> "London close"
            else -> null
        }
    }

    private companion object {
        /** How many bars back a sweep may be to still count as "recent". */
        const val SWEEP_RECENCY = 10
    }
}
