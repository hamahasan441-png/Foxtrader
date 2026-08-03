package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction

/**
 * A single scored trading opportunity for one symbol — the output of running the full TRADEPRO read
 * and grading how "ready" the setup is right now. The board ranks these so the trader instantly sees
 * where the highest-quality action is across the whole watchlist.
 */
data class TradeOpportunity(
    val symbol: String,
    val stage: SetupStage,
    val bias: Bias,
    val direction: Direction?,
    /** 0-100 composite readiness score (stage progression + confidence + proximity + MTF + confluence). */
    val readinessScore: Int,
    val grade: OpportunityGrade,
    val confidence: Int,
    /** Signed distance from current price to the entry zone, in points (0 when inside the zone). */
    val distanceToZonePoints: Double,
    val riskReward: Double,
    val htfAligned: Boolean,
    val confluenceCount: Int,
    val headline: String,
    val hasData: Boolean,
    val dataWarning: String?,
) {
    val isActionable: Boolean get() = stage == SetupStage.EXECUTE
    val isWatch: Boolean get() = stage == SetupStage.CONFIRMATION || stage == SetupStage.ZONE

    companion object {
        fun noData(symbol: String, reason: String): TradeOpportunity = TradeOpportunity(
            symbol = symbol,
            stage = SetupStage.NONE,
            bias = Bias.NEUTRAL,
            direction = null,
            readinessScore = 0,
            grade = OpportunityGrade.NONE,
            confidence = 0,
            distanceToZonePoints = 0.0,
            riskReward = 0.0,
            htfAligned = false,
            confluenceCount = 0,
            headline = reason,
            hasData = false,
            dataWarning = reason,
        )
    }
}

/**
 * A letter grade derived from the readiness score — a fast visual signal for triage.
 */
enum class OpportunityGrade(val label: String, val minScore: Int) {
    A_PLUS("A+", 85),
    A("A", 70),
    B("B", 55),
    C("C", 40),
    WATCH("Watch", 20),
    NONE("--", 0),
    ;

    companion object {
        fun fromScore(score: Int): OpportunityGrade =
            entries.firstOrNull { score >= it.minScore } ?: NONE
    }
}

/**
 * The ranked opportunity board across the scanned watchlist, plus roll-up counts for the header.
 */
data class OpportunityBoard(
    val opportunities: List<TradeOpportunity>,
    val scannedSymbols: Int,
    val actionableCount: Int,
    val watchCount: Int,
    val bullishCount: Int,
    val bearishCount: Int,
    val lastScanEpochMs: Long,
    val hadSyntheticData: Boolean,
    val narrative: String,
) {
    val isEmpty: Boolean get() = opportunities.isEmpty()
    val topOpportunity: TradeOpportunity? get() = opportunities.firstOrNull { it.hasData }

    companion object {
        val EMPTY = OpportunityBoard(
            opportunities = emptyList(),
            scannedSymbols = 0,
            actionableCount = 0,
            watchCount = 0,
            bullishCount = 0,
            bearishCount = 0,
            lastScanEpochMs = 0L,
            hadSyntheticData = false,
            narrative = "No scan run yet.",
        )
    }
}
