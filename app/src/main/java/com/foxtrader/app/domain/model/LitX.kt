package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain models for the LIT X Institutional Framework engine.
 *
 * LIT X is an ADDITIVE analysis module: it orchestrates the app's existing SMC
 * detectors (order blocks, FVGs, liquidity/sweeps, structure) and adds the few
 * institutional primitives that were previously missing as first-class types —
 * [Displacement], [MitigationBlock], [PremiumDiscountZone] — plus a dedicated
 * 11-factor confidence model. It does not replace `MasterDecisionEngine` or
 * TradePro; it coexists with them.
 *
 * All types live in `domain.model.*`, which is already marked stable in
 * `compose-stability.conf`, so they are Compose-stable for the LIT X UI.
 */

// ============================================================================
// NEW FIRST-CLASS SMC PRIMITIVES (added by LIT X)
// ============================================================================

/**
 * A displacement leg — a strong, high-body impulse candle (optionally leaving a
 * Fair Value Gap) that signals institutional intent / a market shift.
 */
data class Displacement(
    val direction: Direction,
    val startIndex: Int,
    val endIndex: Int,
    val startPrice: Double,
    val endPrice: Double,
    /** Body-to-range ratio of the impulse (0..1). Higher = cleaner displacement. */
    val bodyToRangeRatio: Double,
    /** Impulse size expressed as a multiple of recent average range (ATR proxy). */
    val atrMultiple: Double,
    /** Whether the leg left a Fair Value Gap (imbalance). */
    val hasFairValueGap: Boolean,
)

/**
 * A Mitigation Block — an order block that price has returned into (mitigated)
 * and then respected, leaving a refined institutional entry zone. Distinct from
 * a breaker (which flips direction); a mitigation block holds its original bias.
 */
data class MitigationBlock(
    val direction: Direction,
    val highPrice: Double,
    val lowPrice: Double,
    /** Index of the originating order block. */
    val originIndex: Int,
    /** Bar index at which price first returned into the block. */
    val mitigationIndex: Int,
    val strength: Double,
    /** First bar on which the post-mitigation reaction is confirmed. */
    val confirmationIndex: Int = mitigationIndex,
)

/** Which third of the dealing range price currently occupies. */
enum class PriceZoneKind { PREMIUM, EQUILIBRIUM, DISCOUNT }

/**
 * Premium / Discount (equilibrium) zoning over the current dealing range.
 * Longs are favoured from discount, shorts from premium.
 */
data class PremiumDiscountZone(
    val rangeHigh: Double,
    val rangeLow: Double,
    val equilibrium: Double,
    val currentZone: PriceZoneKind,
    /** Where the last price sits in the range: 0.0 = low, 1.0 = high. */
    val currentPositionPct: Double,
)

// ============================================================================
// LIT X CONFIDENCE MODEL (0-100, A+/A/B/Reject)
// ============================================================================

/** LIT X setup grade. Only [A_PLUS]/[A] are surfaced by default. */
enum class LitXGrade { A_PLUS, A, B, REJECT }

/** A single scored factor contributing to the LIT X confidence total. */
data class LitXFactor(
    val name: String,
    /** 0..100 quality of this factor. */
    val score: Int,
    /** Relative weight in the aggregate. */
    val weight: Double,
)

/** Aggregated LIT X confidence: a 0-100 score, its grade, and the breakdown. */
data class LitXConfidence(
    val score: Int,
    val grade: LitXGrade,
    val factors: List<LitXFactor>,
)

// ============================================================================
// LIT X PIPELINE OUTPUT
// ============================================================================

/**
 * How far the institutional pipeline progressed for the current series:
 * Market Context → Liquidity Mapping → Sweep → Market Shift → POI → Entry.
 */
enum class LitXStage {
    SCANNING,
    LIQUIDITY_MAPPED,
    SWEEP_DETECTED,
    SHIFT_CONFIRMED,
    POI_TAPPED,
    VALIDATED,
}

/** A validated, tradeable LIT X setup with entry/risk and confidence. */
data class LitXSignal(
    val symbol: String,
    val timeframe: Timeframe,
    val direction: Direction,
    val stage: LitXStage,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val riskReward: Double,
    val confidence: LitXConfidence,
    /** The premium/discount context the setup was taken from. */
    val zone: PremiumDiscountZone?,
    val rationale: String,
    val timestamp: Long,
)

/**
 * Full LIT X analysis for a symbol/timeframe. [signal] is non-null only when a
 * setup passed entry validation; [stage] always reflects pipeline progress even
 * when no signal is produced.
 */
data class LitXAnalysis(
    val symbol: String,
    val timeframe: Timeframe,
    val stage: LitXStage,
    val bias: Bias,
    val htfBias: Bias,
    val displacement: Displacement?,
    val mitigationBlocks: List<MitigationBlock>,
    val premiumDiscount: PremiumDiscountZone?,
    val signal: LitXSignal?,
    val narrative: String,
    val timestamp: Long,
) {
    val hasSignal: Boolean get() = signal != null

    companion object {
        fun empty(symbol: String, timeframe: Timeframe): LitXAnalysis = LitXAnalysis(
            symbol = symbol,
            timeframe = timeframe,
            stage = LitXStage.SCANNING,
            bias = Bias.NEUTRAL,
            htfBias = Bias.NEUTRAL,
            displacement = null,
            mitigationBlocks = emptyList(),
            premiumDiscount = null,
            signal = null,
            narrative = "No institutional setup detected.",
            timestamp = 0L,
        )
    }
}

/**
 * A persisted, reviewable record of a validated LIT X signal (the durable
 * subset of [LitXSignal] kept in the signal-history table). The full per-factor
 * confidence breakdown is intentionally not persisted — only what a trader
 * reviews later.
 */
data class LitXSignalRecord(
    val id: String,
    val symbol: String,
    val timeframe: Timeframe,
    val direction: Direction,
    val grade: LitXGrade,
    val score: Int,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val riskReward: Double,
    val rationale: String,
    val createdAt: Long,
) {
    companion object {
        fun from(signal: LitXSignal): LitXSignalRecord = LitXSignalRecord(
            id = "${signal.symbol}:${signal.timeframe.label}:${signal.timestamp}",
            symbol = signal.symbol,
            timeframe = signal.timeframe,
            direction = signal.direction,
            grade = signal.confidence.grade,
            score = signal.confidence.score,
            entry = signal.entry,
            stopLoss = signal.stopLoss,
            takeProfit1 = signal.takeProfit1,
            takeProfit2 = signal.takeProfit2,
            riskReward = signal.riskReward,
            rationale = signal.rationale,
            createdAt = signal.timestamp,
        )
    }
}

// ============================================================================
// LIT X CONFIGURATION (persisted via AppPreferences as JSON)
// ============================================================================

/**
 * User-configurable LIT X behaviour. Persisted as a JSON string in DataStore.
 * Defaults to OFF so the module is strictly opt-in and adds zero cost until the
 * user enables it.
 */
@Serializable
data class LitXConfig(
    val enabled: Boolean = false,
    /** Hide any setup graded below this. */
    val minGrade: LitXGrade = LitXGrade.A,
    val minRiskReward: Double = 2.0,
    val requireHtfAlignment: Boolean = true,
    /** Impulse must be at least this many average-ranges to count as displacement. */
    val displacementAtrMultiple: Double = 1.2,
)
