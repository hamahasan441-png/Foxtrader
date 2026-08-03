package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.HoldZone
import com.foxtrader.app.domain.model.tradepro.OpportunityBoard
import com.foxtrader.app.domain.model.tradepro.OpportunityGrade
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeOpportunity
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

/**
 * Grades a [TradeProAnalysis] into a comparable [TradeOpportunity] readiness score, and assembles a
 * ranked [OpportunityBoard] across a watchlist. Pure and deterministic so it is fully unit-testable.
 *
 * Readiness is a weighted blend of five factors, all of which the framework cares about:
 *  - **Stage progression** (LEVEL -> ZONE -> CONFIRMATION -> EXECUTE): the single biggest driver.
 *  - **Setup confidence** as reported by the signal engine.
 *  - **Proximity to the entry zone** (closer = more actionable; inside the zone = full marks).
 *  - **HTF alignment** (an `HTF_ALIGNED_*` confluence): trading with the higher-timeframe bias.
 *  - **Confluence depth**: how many corroborating factors stacked up.
 *
 * The result is a 0-100 score and an A+/A/B/C/Watch grade for fast triage across many symbols.
 */
class OpportunityScorer @Inject constructor() {

    /**
     * Score a single symbol's analysis. [pointSize] converts price distance into framework points so
     * proximity is comparable across instruments.
     */
    fun score(
        analysis: TradeProAnalysis,
        currentPrice: Double,
        config: TradeProConfig = TradeProConfig(),
    ): TradeOpportunity {
        val setup = analysis.setup
        val bias = analysis.flipZone?.bias ?: Bias.NEUTRAL
        val direction = setup?.direction ?: biasDirection(bias)
        val confidence = setup?.confidence ?: 0
        val htfAligned = setup?.confluences?.any { it.startsWith(HTF_ALIGNED_PREFIX) } ?: false
        val confluenceCount = setup?.confluences?.size ?: 0
        val riskReward = setup?.riskReward ?: 0.0

        val distancePoints = distanceToZonePoints(analysis, currentPrice, config.pointSize)

        val stageScore = stageScore(analysis.stage) * STAGE_WEIGHT
        val confidenceScore = (confidence / 100.0) * CONFIDENCE_WEIGHT
        val proximityScore = proximityScore(analysis.stage, distancePoints, config) * PROXIMITY_WEIGHT
        val htfScore = (if (htfAligned) 1.0 else 0.0) * HTF_WEIGHT
        val confluenceScore = (confluenceCount.coerceAtMost(MAX_CONFLUENCE).toDouble() / MAX_CONFLUENCE) * CONFLUENCE_WEIGHT

        val readiness = ((stageScore + confidenceScore + proximityScore + htfScore + confluenceScore) * 100)
            .toInt()
            .coerceIn(0, 100)
        val grade = OpportunityGrade.fromScore(readiness)

        return TradeOpportunity(
            symbol = analysis.symbol,
            stage = analysis.stage,
            bias = bias,
            direction = direction,
            readinessScore = readiness,
            grade = grade,
            confidence = confidence,
            distanceToZonePoints = distancePoints,
            riskReward = riskReward,
            htfAligned = htfAligned,
            confluenceCount = confluenceCount,
            headline = headline(analysis, grade, distancePoints),
            hasData = true,
            dataWarning = null,
        )
    }

    /**
     * Assemble a ranked board from per-symbol scored opportunities. Actionable (EXECUTE) setups always
     * rank above lower stages; within a stage, higher readiness wins. Ties break on symbol for a stable,
     * deterministic ordering.
     */
    fun buildBoard(
        opportunities: List<TradeOpportunity>,
        scannedSymbols: Int,
        hadSyntheticData: Boolean,
        nowEpochMs: Long,
    ): OpportunityBoard {
        val ranked = opportunities.sortedWith(
            compareByDescending<TradeOpportunity> { it.hasData }
                .thenByDescending { it.stage.ordinal }
                .thenByDescending { it.readinessScore }
                .thenBy { it.symbol },
        )
        val actionable = ranked.count { it.isActionable }
        val watch = ranked.count { it.isWatch }
        val bullish = ranked.count { it.hasData && it.bias == Bias.BULLISH }
        val bearish = ranked.count { it.hasData && it.bias == Bias.BEARISH }

        val narrative = buildString {
            append("Scanned $scannedSymbols symbols: ")
            append("$actionable actionable, $watch on watch. ")
            append("Bias split $bullish bullish / $bearish bearish.")
            ranked.firstOrNull { it.isActionable }?.let {
                append(" Top: ${it.symbol} (${it.grade.label}, ${it.readinessScore}).")
            }
            if (hadSyntheticData) append(" \u26A0 Some symbols used simulated data.")
        }

        return OpportunityBoard(
            opportunities = ranked,
            scannedSymbols = scannedSymbols,
            actionableCount = actionable,
            watchCount = watch,
            bullishCount = bullish,
            bearishCount = bearish,
            lastScanEpochMs = nowEpochMs,
            hadSyntheticData = hadSyntheticData,
            narrative = narrative,
        )
    }

    // --- Scoring components ---

    private fun stageScore(stage: SetupStage): Double = when (stage) {
        SetupStage.NONE -> 0.0
        SetupStage.LEVEL -> 0.25
        SetupStage.ZONE -> 0.55
        SetupStage.CONFIRMATION -> 0.8
        SetupStage.EXECUTE -> 1.0
    }

    /**
     * Full marks when price is inside/at the zone; decays to zero as price moves a full stop-distance
     * (or more) away. Non-actionable stages without a zone get a neutral baseline.
     */
    private fun proximityScore(stage: SetupStage, distancePoints: Double, config: TradeProConfig): Double {
        if (stage == SetupStage.NONE || stage == SetupStage.LEVEL) return 0.3
        val tolerance = (config.stopPoints * PROXIMITY_TOLERANCE_MULT).coerceAtLeast(1.0)
        val d = abs(distancePoints)
        return (1.0 - (d / tolerance)).coerceIn(0.0, 1.0)
    }

    /**
     * Signed distance from [currentPrice] to the nearest relevant hold zone edge, in points. Zero when
     * price is within the zone. Positive means price is above the zone, negative below.
     */
    private fun distanceToZonePoints(
        analysis: TradeProAnalysis,
        currentPrice: Double,
        pointSize: Double,
    ): Double {
        val zone = relevantZone(analysis) ?: return 0.0
        val size = if (pointSize > 0.0) pointSize else 1.0
        return when {
            currentPrice in zone.low..zone.high -> 0.0
            currentPrice > zone.high -> (currentPrice - zone.high) / size
            else -> (currentPrice - zone.low) / size
        }
    }

    private fun relevantZone(analysis: TradeProAnalysis): HoldZone? {
        val zones = analysis.holdZones
        if (zones.isEmpty()) return null
        // Prefer the setup's own zone; otherwise the most recent (largest endIndex).
        return analysis.setup?.holdZone ?: zones.maxByOrNull { it.endIndex }
    }

    private fun biasDirection(bias: Bias): Direction? = when (bias) {
        Bias.BULLISH -> Direction.BULLISH
        Bias.BEARISH -> Direction.BEARISH
        Bias.NEUTRAL -> null
    }

    private fun headline(analysis: TradeProAnalysis, grade: OpportunityGrade, distancePoints: Double): String {
        val stage = analysis.stage
        return when (stage) {
            SetupStage.EXECUTE -> "${grade.label} \u00B7 Executable now"
            SetupStage.CONFIRMATION -> "${grade.label} \u00B7 Awaiting final confirmation"
            SetupStage.ZONE -> {
                val d = abs(distancePoints)
                if (d <= 0.0) "${grade.label} \u00B7 Price in the zone" else "${grade.label} \u00B7 ${fmt(d)} pts from zone"
            }
            SetupStage.LEVEL -> "Bias set \u00B7 waiting for price to reach the zone"
            SetupStage.NONE -> "No qualifying setup"
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

    companion object {
        private const val HTF_ALIGNED_PREFIX = "HTF_ALIGNED"
        private const val STAGE_WEIGHT = 0.40
        private const val CONFIDENCE_WEIGHT = 0.25
        private const val PROXIMITY_WEIGHT = 0.20
        private const val HTF_WEIGHT = 0.10
        private const val CONFLUENCE_WEIGHT = 0.05
        private const val MAX_CONFLUENCE = 5
        private const val PROXIMITY_TOLERANCE_MULT = 2.0
    }
}
