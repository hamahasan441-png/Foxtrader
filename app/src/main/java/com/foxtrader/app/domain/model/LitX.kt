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
/**
 * The structural gates a [LitXMode] declares, before any per-gate override.
 *
 * Lives in the model rather than inside the engine so the settings sheet can
 * show a trader what the mode they picked actually requires — and so there is
 * one definition of that rather than two that can drift.
 *
 * | gate | SNIPER | PRECISION | MOMENTUM | SWEEP_REVERSAL |
 * |---|---|---|---|---|
 * | liquidity sweep required      | yes | yes | no  | yes |
 * | POI retest required           | yes | yes | no  | yes |
 * | in-band tap (not near-band)   | yes | no  | n/a | no  |
 * | FVG admissible as POI origin  | no  | yes | yes | yes |
 * | aligned displacement required | yes | no  | yes | no  |
 * | kill-zone session required    | yes | no  | no  | no  |
 */
data class LitXGates(
    val requireSweep: Boolean,
    val requireRetest: Boolean,
    val requireInBandTap: Boolean,
    val allowFvgPoi: Boolean,
    val requireAlignedDisplacement: Boolean,
    val requireKillZone: Boolean,
)

/** The gates [this] mode declares before overrides. */
fun LitXMode.gates(): LitXGates = when (this) {
    LitXMode.SNIPER -> LitXGates(
        requireSweep = true,
        requireRetest = true,
        requireInBandTap = true,
        allowFvgPoi = false,
        requireAlignedDisplacement = true,
        requireKillZone = true,
    )
    LitXMode.PRECISION -> LitXGates(
        requireSweep = true,
        requireRetest = true,
        requireInBandTap = false,
        allowFvgPoi = true,
        requireAlignedDisplacement = false,
        requireKillZone = false,
    )
    LitXMode.MOMENTUM -> LitXGates(
        requireSweep = false,
        requireRetest = false,
        requireInBandTap = false,
        allowFvgPoi = true,
        requireAlignedDisplacement = true,
        requireKillZone = false,
    )
    LitXMode.SWEEP_REVERSAL -> LitXGates(
        requireSweep = true,
        requireRetest = true,
        requireInBandTap = false,
        allowFvgPoi = true,
        requireAlignedDisplacement = false,
        requireKillZone = false,
    )
}

data class LitXConfig(
    val enabled: Boolean = true,
    /** Hide any setup graded below this. */
    val minGrade: LitXGrade = LitXGrade.B,
    val minRiskReward: Double = 1.5,
    /**
     * The setup must point the same way as the higher-timeframe read.
     *
     * Off by default, which is not an oversight. Measured over 5 000 bars each
     * of EURUSD, GBPUSD and XAUUSD on M15, turning it on halved the signal
     * count and moved expectancy from +0.015R to -0.079R — it cost trades and
     * did not improve the ones that remained. It stays available for anyone who
     * wants it.
     */
    val requireHtfAlignment: Boolean = false,
    /** Impulse must be at least this many average-ranges to count as displacement. */
    val displacementAtrMultiple: Double = 1.2,
    /** Phase 13 execution profile. */
    val profile: SignalProfile = SignalProfile.INTRADAY,
    /**
     * The CHOCH must be corroborated by an aligned displacement (an MSS).
     *
     * Off by default. It is a real distinction and it is expensive: on real
     * EURUSD only 22% of confirmed shifts carry a qualifying impulse within
     * five bars, and with this and the zone gate both on the study published
     * nothing at all across ten market series.
     */
    val requireStrongMss: Boolean = false,
    /**
     * Longs should originate in discount and shorts in premium.
     *
     * Off by default for the same reason: with it on, nothing was ever
     * published. The rule now reads as "not from the wrong half" rather than
     * "only from the right third", but even so it is left to the trader.
     */
    val requireDirectionalZone: Boolean = false,
    /** Absolute score floor in addition to the grade filter. */
    val minConfidenceScore: Int = 68,
    /** Maximum bars allowed between liquidity sweep and structure shift. */
    val maxSweepToShiftBars: Int = 20,
    /** Maximum bars allowed between confirmed shift and first POI retest. */
    val maxShiftToRetestBars: Int = 25,
    /**
     * Which LiT Adventure rule set to run. Appended last so every existing
     * positional construction and every previously persisted JSON payload
     * continues to deserialize into [LitXMode.PRECISION], the historical
     * behaviour.
     */
    val mode: LitXMode = LitXMode.PRECISION,

    // --- Per-gate switches -------------------------------------------------
    // Null means "whatever the mode declares". Appended last and nullable so
    // every previously persisted payload keeps its behaviour exactly.
    //
    // These exist because the gates are what decide whether the study can fire
    // at all, and until now they were reachable only by picking a whole mode.
    // A trader who wants the sequence but not the kill-zone, or the sweep but
    // not the retest, had no way to say so.
    /** A liquidity sweep must precede the structure shift. */
    val requireSweep: Boolean? = null,
    /** Price must return to the point of interest before the setup is taken. */
    val requireRetest: Boolean? = null,
    /** The retest must land inside the POI band, not merely near it. */
    val requireInBandTap: Boolean? = null,
    /** A fair value gap may serve as the point of interest. */
    val allowFvgPoi: Boolean? = null,
    /** An impulse pointing the trade's way must accompany the shift. */
    val requireAlignedDisplacement: Boolean? = null,
    /** The entry must fall inside an ICT kill zone. */
    val requireKillZone: Boolean? = null,
) {
    /**
     * User-facing three-mode LiT Adventure identity. The app already persists
     * [SignalProfile], so this keeps the new indicator modes backward-compatible
     * with existing settings while giving each profile a distinct rule package.
     */
    val adventureModeLabel: String
        get() = when (profile) {
            SignalProfile.SCALPING -> "Fast Scalp"
            SignalProfile.INTRADAY -> "Balanced Trade"
            SignalProfile.SWING -> "Power Trade"
        }

    /**
     * The gates actually in force: the mode's, with any switch the trader has
     * set applied on top. A mode is a preset, not a cage.
     */
    fun effectiveGates(): LitXGates {
        val base = mode.gates()
        return LitXGates(
            requireSweep = requireSweep ?: base.requireSweep,
            requireRetest = requireRetest ?: base.requireRetest,
            requireInBandTap = requireInBandTap ?: base.requireInBandTap,
            allowFvgPoi = allowFvgPoi ?: base.allowFvgPoi,
            requireAlignedDisplacement =
                requireAlignedDisplacement ?: base.requireAlignedDisplacement,
            requireKillZone = requireKillZone ?: base.requireKillZone,
        )
    }

    fun sanitized(): LitXConfig = copy(
        minRiskReward = minRiskReward.coerceIn(1.0, 5.0),
        displacementAtrMultiple = displacementAtrMultiple.coerceIn(0.8, 3.0),
        minConfidenceScore = minConfidenceScore.coerceIn(50, 95),
        maxSweepToShiftBars = maxSweepToShiftBars.coerceIn(3, 30),
        maxShiftToRetestBars = maxShiftToRetestBars.coerceIn(3, 40),
    )

    companion object {
        /**
         * Production LiT Adventure indicator presets.
         *
         * FAST SCALP: closed-bar liquidity reversal + displacement/MSS, relaxed
         * HTF/zone gates, 68 score floor for earlier entries.
         * BALANCED TRADE: full sweep -> MSS -> POI retest with HTF/zone alignment.
         * POWER TRADE: sniper structure, in-band institutional POI, kill-zone,
         * HTF alignment and a 90 score floor for only maximum-conviction arrows.
         *
         * All three are evaluated by the same causal engine and therefore keep
         * identical non-repaint/live/replay semantics.
         */
        fun preset(profile: SignalProfile, enabled: Boolean = true): LitXConfig = when (profile) {
            SignalProfile.SCALPING -> LitXConfig(
                enabled = enabled,
                minGrade = LitXGrade.B,
                minRiskReward = 1.6,
                requireHtfAlignment = false,
                displacementAtrMultiple = 1.05,
                profile = profile,
                requireDirectionalZone = false,
                minConfidenceScore = 68,
                requireStrongMss = false,
                maxSweepToShiftBars = 14,
                maxShiftToRetestBars = 18,
                mode = LitXMode.SWEEP_REVERSAL,
            )
            SignalProfile.INTRADAY -> LitXConfig(
                enabled = enabled,
                minGrade = LitXGrade.B,
                minRiskReward = 1.5,
                requireHtfAlignment = false,
                displacementAtrMultiple = 1.20,
                profile = profile,
                requireStrongMss = false,
                requireDirectionalZone = false,
                minConfidenceScore = 68,
                maxSweepToShiftBars = 20,
                maxShiftToRetestBars = 25,
                mode = LitXMode.PRECISION,
            )
            SignalProfile.SWING -> LitXConfig(
                enabled = enabled,
                minGrade = LitXGrade.A_PLUS,
                minRiskReward = 2.5,
                requireHtfAlignment = true,
                displacementAtrMultiple = 1.50,
                profile = profile,
                requireStrongMss = true,
                // Measured off across every preset: with this gate on the study
                // published nothing on any of ten real market series. Power
                // Trade stays the strictest package by grade, score and the
                // SNIPER gates; it is not made dead by one rule that no market
                // satisfied.
                requireDirectionalZone = false,
                minConfidenceScore = 90,
                maxSweepToShiftBars = 14,
                maxShiftToRetestBars = 16,
                mode = LitXMode.SNIPER,
            )
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
