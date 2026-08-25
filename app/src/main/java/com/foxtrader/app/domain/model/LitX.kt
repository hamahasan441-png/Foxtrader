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
@Serializable
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
    /** First bar on which the complete setup is objectively confirmed. */
    val confirmationIndex: Int = -1,
    /** Explainable institutional conditions that passed. */
    val confirmations: List<String> = emptyList(),
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
 * LiT Adventure execution modes.
 *
 * A mode is NOT a preset. [SignalProfile] already varies thresholds (how strict)
 * against one fixed rule set. A mode varies **which rules apply at all** — which
 * confluences are mandatory, which POI kinds are admissible, and whether entry
 * comes from a retest or from continuation. Two modes on the same candles can
 * therefore disagree about whether a setup exists, not merely about its grade.
 *
 * All modes share the one core engine and the same causal boundary: a signal is
 * still emitted only on the newest confirmed bar, and no mode may read data
 * after its own confirmation index. Modes change selectivity, never causality.
 */
@Serializable
enum class LitXMode(val label: String, val description: String) {
    /**
     * Maximum-conviction sniper. Every confluence is mandatory and the entry
     * must be a true in-band tap: sweep, displacement-confirmed MSS, an
     * institutional POI (mitigation block or order block — a fair value gap is
     * not accepted as a standalone origin), directional premium/discount, HTF
     * agreement, and a kill-zone session. Expect very few signals.
     */
    SNIPER(
        "Sniper",
        "All confluences mandatory, in-band entry only, no FVG-only origins.",
    ),

    /**
     * The repository's established structure-led behaviour: sweep -> shift ->
     * POI retest, with fair value gaps admissible as an origin and a near-band
     * retest tolerated. This is the historical LiT Adventure rule set.
     */
    PRECISION(
        "Precision",
        "Structure-led sweep to shift to POI retest; FVG origins allowed.",
    ),

    /**
     * Displacement-led continuation. Requires a strong, aligned impulse and a
     * confirmed shift, but does NOT require price to return into the POI band —
     * entry is taken on continuation, so the premium/discount gate is dropped
     * (a momentum entry is by definition not at a discount).
     */
    MOMENTUM(
        "Momentum",
        "Impulse-led continuation; no retest requirement, no zone gate.",
    ),

    /**
     * Liquidity-led reversal. Anchored on the sweep and the premium/discount
     * side rather than on trend agreement, so a counter-trend turn is
     * admissible: HTF alignment is not required and a plain CHOCH is accepted
     * without displacement corroboration. The POI retest stays mandatory.
     */
    SWEEP_REVERSAL(
        "Sweep Reversal",
        "Liquidity-led counter-trend turn; CHOCH accepted, retest mandatory.",
    ),
}

/**
 * User-configurable LIT X behaviour. Persisted as a JSON string in DataStore.
 *
 * Enabled is true by default so the on-chart LiTX switch is authoritative on a
 * fresh install. The engine is still dormant until a chart/TradePro path asks
 * for it, so this does not add background work or produce unsolicited signals.
 */
@Serializable
data class LitXConfig(
    val enabled: Boolean = true,
    /** Hide any setup graded below this. */
    val minGrade: LitXGrade = LitXGrade.A,
    val minRiskReward: Double = 2.0,
    val requireHtfAlignment: Boolean = true,
    /** Impulse must be at least this many average-ranges to count as displacement. */
    val displacementAtrMultiple: Double = 1.2,
    /** Phase 13 execution profile. */
    val profile: SignalProfile = SignalProfile.INTRADAY,
    /** Accuracy-first mode: CHOCH must be corroborated by aligned displacement (MSS). */
    val requireStrongMss: Boolean = true,
    /** Longs should originate in discount and shorts in premium when range context exists. */
    val requireDirectionalZone: Boolean = true,
    /** Absolute score floor in addition to the grade filter. */
    val minConfidenceScore: Int = 75,
    /** Maximum bars allowed between liquidity sweep and structure shift. */
    val maxSweepToShiftBars: Int = 12,
    /** Maximum bars allowed between confirmed shift and first POI retest. */
    val maxShiftToRetestBars: Int = 14,
    /**
     * Which LiT Adventure rule set to run. Appended last so every existing
     * positional construction and every previously persisted JSON payload
     * continues to deserialize into [LitXMode.PRECISION], the historical
     * behaviour.
     */
    val mode: LitXMode = LitXMode.PRECISION,
) {
    fun sanitized(): LitXConfig = copy(
        minRiskReward = minRiskReward.coerceIn(1.0, 5.0),
        displacementAtrMultiple = displacementAtrMultiple.coerceIn(0.8, 3.0),
        minConfidenceScore = minConfidenceScore.coerceIn(50, 95),
        maxSweepToShiftBars = maxSweepToShiftBars.coerceIn(3, 30),
        maxShiftToRetestBars = maxShiftToRetestBars.coerceIn(3, 40),
    )

    companion object {
        fun preset(profile: SignalProfile, enabled: Boolean = true): LitXConfig = when (profile) {
            SignalProfile.SCALPING -> LitXConfig(enabled, LitXGrade.A, 1.8, false, 1.10, profile, true, false, 70, 7, 8)
            SignalProfile.INTRADAY -> LitXConfig(enabled, LitXGrade.A, 2.0, true, 1.20, profile, true, true, 75, 12, 14)
            SignalProfile.SWING -> LitXConfig(enabled, LitXGrade.A_PLUS, 2.3, true, 1.35, profile, true, true, 80, 18, 22)
        }

        /**
         * Mode preset layered on top of a profile preset.
         *
         * The mode owns the structural gates (applied inside the engine); the
         * fields set here are only the score/grade floors that make each mode's
         * selectivity coherent with its rule set. Sniper raises the bar because
         * it demands every confluence; Sweep Reversal lowers the trend-related
         * requirement because counter-trend entries cannot satisfy it.
         */
        fun preset(
            mode: LitXMode,
            profile: SignalProfile = SignalProfile.INTRADAY,
            enabled: Boolean = true,
        ): LitXConfig {
            val base = preset(profile, enabled).copy(mode = mode)
            return when (mode) {
                LitXMode.SNIPER -> base.copy(
                    minGrade = LitXGrade.A_PLUS,
                    minConfidenceScore = (base.minConfidenceScore + 8).coerceAtMost(95),
                    minRiskReward = maxOf(base.minRiskReward, 2.5),
                    requireStrongMss = true,
                    requireHtfAlignment = true,
                    requireDirectionalZone = true,
                )
                LitXMode.PRECISION -> base
                LitXMode.MOMENTUM -> base.copy(
                    requireStrongMss = true,
                    requireDirectionalZone = false,
                    displacementAtrMultiple = maxOf(base.displacementAtrMultiple, 1.5),
                )
                LitXMode.SWEEP_REVERSAL -> base.copy(
                    requireStrongMss = false,
                    requireHtfAlignment = false,
                    requireDirectionalZone = true,
                )
            }.sanitized()
        }
    }
}
