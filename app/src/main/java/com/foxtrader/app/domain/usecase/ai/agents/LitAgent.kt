package com.foxtrader.app.domain.usecase.ai.agents

import com.foxtrader.app.domain.model.AgentContext
import com.foxtrader.app.domain.model.AgentInsight
import com.foxtrader.app.domain.model.AgentName
import com.foxtrader.app.domain.model.AgentOutput
import com.foxtrader.app.domain.model.AgentStatus
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.PriceZone
import com.foxtrader.app.domain.usecase.ai.TradingAgent
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * LIT (Liquidity Inducement Theory) agent.
 *
 * Detects the institutional trap sequence:
 *  1. Price advertises obvious liquidity (equal highs/lows or a tight local pool).
 *  2. A sweep grabs that liquidity and closes back inside the range.
 *  3. A displacement / market-structure shift confirms the real direction.
 *
 * The agent still consumes upstream MARKET_STRUCTURE / ICT / SMART_MONEY outputs,
 * but now also performs a direct, non-repainting candle pass so LIT remains useful
 * when those upstream agents did not emit explicit IDM insights.
 */
class LitAgent @Inject constructor(
    private val smtDivergenceDetector: SmtDivergenceDetector,
) : TradingAgent {

    override val name = AgentName.LIT
    override val description = "Liquidity Inducement Theory — inducement, sweep and displacement confirmation."
    override val version = "1.1.0"

    override fun analyze(context: AgentContext): AgentOutput {
        val start = System.nanoTime()
        val candles = context.candles
        if (candles.size < MIN_CANDLES) {
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
                confidence = institutionalEntryConfidence(lastSweep.confidence, lastBreak.confidence),
                timestamp = System.currentTimeMillis(),
                barIndex = candles.lastIndex,
                detail = "Sweep + ${lastBreak.type} confirmation → institutional entry ${lastSweep.direction}",
                weight = 2.5,
                tags = listOf("INSTITUTIONAL", "ENTRY_SIGNAL", "LIT", "SWEEP", lastBreak.type),
            )
        }

        // Upstream IDM detection: an IDM insight means a trap was set.
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

        // Direct LIT pass: equal-high/equal-low pool → sweep → displacement.
        detectDirectInducementSetups(candles)
            .takeLast(MAX_DIRECT_SETUPS)
            .forEach { setup ->
                insights += setup.toInsight(name, candles)
            }

        // Cross-symbol SMT pass: correlated symbols disagree at liquidity extremes.
        smtDivergenceDetector.detect(
            primarySymbol = context.symbol,
            primaryCandles = candles,
            correlatedCandles = context.correlatedCandles,
        )
            .takeLast(MAX_SMT_SETUPS)
            .forEach { smt ->
                insights += AgentInsight(
                    id = "$name-SMT-${smt.peerSymbol}-${smt.primaryIndex}-${smt.direction}",
                    agentName = name,
                    type = "SMT",
                    direction = smt.direction,
                    confidence = smt.confidence,
                    price = smt.primaryPrice,
                    timestamp = candles.getOrNull(smt.primaryIndex)?.timestamp ?: System.currentTimeMillis(),
                    barIndex = smt.primaryIndex,
                    detail = smt.detail + " (corr ${String.format(Locale.US, "%.2f", smt.correlation)})",
                    weight = 1.8,
                    tags = listOf("SMT", "DIVERGENCE", "CORRELATED_PAIR", "LIT"),
                )
            }

        if (insights.isEmpty()) {
            return neutralOutput(name, "No LIT setups (inducement + sweep + shift).", start)
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
            narrative = "LIT: ${insights.size} signal(s). " +
                "Inducement/sweep/displacement map complete where confirmed. Bias $bias.",
            processingTimeMs = elapsedMs(start),
            timestamp = System.currentTimeMillis(),
        )
    }

    /**
     * Direct non-repainting LIT detector.
     *
     * At sweep index i it only looks backward to identify the liquidity pool.
     * It emits a setup at the later confirmation index j once displacement has
     * printed, so historical signals do not repaint.
     */
    private fun detectDirectInducementSetups(candles: List<Candle>): List<LitSetup> {
        if (candles.size < MIN_CANDLES) return emptyList()

        val setups = mutableListOf<LitSetup>()
        for (sweepIndex in LOOKBACK_BARS until candles.lastIndex) {
            val sweep = candles[sweepIndex]
            val lookbackStart = max(0, sweepIndex - LOOKBACK_BARS)
            val lookback = candles.subList(lookbackStart, sweepIndex)
            if (lookback.size < MIN_POOL_BARS) continue

            // Thresholds are calculated from candles available before the sweep,
            // never from future bars, preserving the non-repainting contract.
            val rangeStart = max(0, sweepIndex - RANGE_SAMPLE_SIZE)
            val priorRanges = candles.subList(rangeStart, sweepIndex).map { it.range }.filter { it > 0.0 }
            val avgRange = priorRanges.average().takeIf { it.isFinite() && it > 0.0 } ?: continue
            val poolTolerance = avgRange * POOL_TOLERANCE_MULTIPLIER
            val sweepThreshold = avgRange * SWEEP_THRESHOLD_MULTIPLIER
            val displacementThreshold = avgRange * DISPLACEMENT_BODY_MULTIPLIER

            // Sell-side inducement: obvious equal lows get swept, then price reclaims above the pool.
            val sellSideLevel = lookback.minOf { it.low }
            val sellSideTouches = lookback.count { abs(it.low - sellSideLevel) <= poolTolerance }
            val sweptSellSide = sellSideTouches >= MIN_POOL_TOUCHES &&
                sweep.low < sellSideLevel - sweepThreshold &&
                sweep.close > sellSideLevel &&
                sweep.isBullish

            if (sweptSellSide) {
                findBullishConfirmation(candles, sweepIndex, displacementThreshold)?.let { confirmIndex ->
                    setups += LitSetup(
                        direction = Direction.BULLISH,
                        liquidityLevel = sellSideLevel,
                        sweepIndex = sweepIndex,
                        confirmIndex = confirmIndex,
                        sweepPrice = sweep.low,
                        confidence = confidenceFor(candles, sweepIndex, confirmIndex, avgRange),
                        detail = "Sell-side inducement swept below ${sellSideLevel.fmt()} and reclaimed; bullish displacement confirmed.",
                    )
                }
            }

            // Buy-side inducement: obvious equal highs get swept, then price rejects below the pool.
            val buySideLevel = lookback.maxOf { it.high }
            val buySideTouches = lookback.count { abs(it.high - buySideLevel) <= poolTolerance }
            val sweptBuySide = buySideTouches >= MIN_POOL_TOUCHES &&
                sweep.high > buySideLevel + sweepThreshold &&
                sweep.close < buySideLevel &&
                !sweep.isBullish

            if (sweptBuySide) {
                findBearishConfirmation(candles, sweepIndex, displacementThreshold)?.let { confirmIndex ->
                    setups += LitSetup(
                        direction = Direction.BEARISH,
                        liquidityLevel = buySideLevel,
                        sweepIndex = sweepIndex,
                        confirmIndex = confirmIndex,
                        sweepPrice = sweep.high,
                        confidence = confidenceFor(candles, sweepIndex, confirmIndex, avgRange),
                        detail = "Buy-side inducement swept above ${buySideLevel.fmt()} and rejected; bearish displacement confirmed.",
                    )
                }
            }
        }

        // If overlapping windows detect the same confirmation, keep the strongest version.
        return setups
            .groupBy { it.direction to it.confirmIndex }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.confidence } }
            .sortedBy { it.confirmIndex }
    }

    private fun findBullishConfirmation(
        candles: List<Candle>,
        sweepIndex: Int,
        displacementThreshold: Double,
    ): Int? {
        val sweep = candles[sweepIndex]
        val end = min(candles.lastIndex, sweepIndex + CONFIRMATION_WINDOW_BARS)
        return (sweepIndex + 1..end).firstOrNull { index ->
            val candle = candles[index]
            candle.close > sweep.high || (candle.isBullish && candle.bodySize >= displacementThreshold)
        }
    }

    private fun findBearishConfirmation(
        candles: List<Candle>,
        sweepIndex: Int,
        displacementThreshold: Double,
    ): Int? {
        val sweep = candles[sweepIndex]
        val end = min(candles.lastIndex, sweepIndex + CONFIRMATION_WINDOW_BARS)
        return (sweepIndex + 1..end).firstOrNull { index ->
            val candle = candles[index]
            candle.close < sweep.low || (!candle.isBullish && candle.bodySize >= displacementThreshold)
        }
    }

    private fun confidenceFor(
        candles: List<Candle>,
        sweepIndex: Int,
        confirmIndex: Int,
        avgRange: Double,
    ): Double {
        val sweep = candles[sweepIndex]
        val confirm = candles[confirmIndex]
        val sweepStrength = (sweep.range / avgRange).coerceIn(0.0, 2.0) * 10.0
        val displacementStrength = (confirm.bodySize / avgRange).coerceIn(0.0, 2.0) * 12.0
        val speedBonus = (CONFIRMATION_WINDOW_BARS - (confirmIndex - sweepIndex) + 1).coerceAtLeast(0) * 2.0
        return (58.0 + sweepStrength + displacementStrength + speedBonus).coerceIn(60.0, 88.0)
    }

    private data class LitSetup(
        val direction: Direction,
        val liquidityLevel: Double,
        val sweepIndex: Int,
        val confirmIndex: Int,
        val sweepPrice: Double,
        val confidence: Double,
        val detail: String,
    ) {
        fun toInsight(agentName: AgentName, candles: List<Candle>): AgentInsight {
            val high = max(liquidityLevel, sweepPrice)
            val low = min(liquidityLevel, sweepPrice)
            return AgentInsight(
                id = "$agentName-LIT-$confirmIndex-$direction",
                agentName = agentName,
                type = "LIT_INDUCEMENT_REVERSAL",
                direction = direction,
                confidence = confidence,
                price = candles[confirmIndex].close,
                timestamp = candles[confirmIndex].timestamp,
                barIndex = confirmIndex,
                zone = PriceZone(high = high, low = low),
                detail = detail,
                weight = 2.2,
                tags = listOf("LIT", "INDUCEMENT", "SWEEP", "MSS", "ENTRY_SIGNAL"),
            )
        }
    }

    /**
     * Confidence for a sweep + structure-break confluence, weighted toward the
     * stronger of the two pieces of evidence. Replaces a flat 80 so the signal
     * reflects the actual quality of the underlying sweep and break rather than
     * a constant, and stays within a sane institutional-entry band.
     */
    private fun institutionalEntryConfidence(sweepConfidence: Double, breakConfidence: Double): Double {
        val stronger = maxOf(sweepConfidence, breakConfidence)
        val weaker = minOf(sweepConfidence, breakConfidence)
        return (ENTRY_STRONGER_WEIGHT * stronger + ENTRY_WEAKER_WEIGHT * weaker)
            .coerceIn(ENTRY_MIN_CONFIDENCE, ENTRY_MAX_CONFIDENCE)
    }

    private fun Double.fmt(): String = String.format(Locale.US, "%.5f", this)

    private companion object {
        const val MIN_CANDLES = 30
        const val LOOKBACK_BARS = 12
        const val MIN_POOL_BARS = 8
        const val MIN_POOL_TOUCHES = 2
        const val CONFIRMATION_WINDOW_BARS = 3
        const val RANGE_SAMPLE_SIZE = 50
        const val MAX_DIRECT_SETUPS = 3
        const val MAX_SMT_SETUPS = 2
        const val POOL_TOLERANCE_MULTIPLIER = 0.25
        const val SWEEP_THRESHOLD_MULTIPLIER = 0.05
        const val DISPLACEMENT_BODY_MULTIPLIER = 0.60

        // Institutional-entry confidence blend (sweep + break confluence).
        const val ENTRY_STRONGER_WEIGHT = 0.6
        const val ENTRY_WEAKER_WEIGHT = 0.4
        const val ENTRY_MIN_CONFIDENCE = 60.0
        const val ENTRY_MAX_CONFIDENCE = 92.0

        val STRUCT_TYPES = setOf("BOS", "CHOCH", "MSS", "IDM")
    }
}
