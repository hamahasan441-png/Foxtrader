package com.foxtrader.app.domain.usecase.nascent.model

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe

/**
 * How strongly a Nascent rule is supported by the source material.
 *
 * This is deliberately part of the domain model rather than a comment: the
 * engine ships rules at very different confidence levels, and a reader (or a
 * later maintainer) must never be able to mistake a reconstructed geometry for
 * an officially documented one.
 *
 * - [NASCENT_VERIFIED] — stated outright by the Nascent Primary Analysis
 *   material (e.g. "MSU Type 2 = reversal").
 * - [CORROBORATED] — not spelled out by Nascent, but consistently supported by
 *   closely related AlgoHub/LIT material (e.g. TOM = Transfer Of Money).
 * - [INFERRED_V1] — a first-pass reconstruction of a geometry the source
 *   describes only in prose or diagrams. Testable and isolated, but not an
 *   official written definition.
 * - [UNRESOLVED] — the *name* is real but its exact geometry or arithmetic is
 *   not known. Rules at this level must never run as if they were verified.
 * - [RESEARCH_ONLY] — experimental. Never enabled outside research mode.
 *
 * Nothing in this engine may silently promote CORROBORATED, INFERRED_V1 or
 * UNRESOLVED into NASCENT_VERIFIED.
 */
enum class EvidenceLevel {
    NASCENT_VERIFIED,
    CORROBORATED,
    INFERRED_V1,
    UNRESOLVED,
    RESEARCH_ONLY,
}

/**
 * Execution posture.
 *
 * [SOURCE_STRICT] refuses to act on anything weaker than [EvidenceLevel.CORROBORATED]
 * and never fabricates a TOM completion. [RESEARCH] enables inferred/experimental
 * geometry so it can be measured. [BALANCED] is the shipping default.
 */
enum class NascentMode { SOURCE_STRICT, BALANCED, RESEARCH }

/**
 * Nascent liquidity taxonomy for a delivery cycle.
 *
 * - [ILQ] — Inducement Liquidity. Buy -> Sell range high (mirrored for
 *   Sell -> Buy). Its inducement/expansion *role* is corroborated rather than
 *   spelled out by Nascent.
 * - [TLQ] — Transactional Liquidity. Buy -> Sell range low (mirrored). This is
 *   a structural/liquidity concept and specifically **not** a take-profit level.
 * - [SLQ] — Structural Liquidity, resting between ILQ and TLQ. Only confirmed
 *   structure qualifies; a microscopic pivot does not.
 * - [DECISIONAL_SLQ] — the name appears in Nascent's own checklist, but the
 *   supplied material never defines its geometry. It is therefore
 *   [EvidenceLevel.UNRESOLVED] and stays off unless explicitly enabled; see
 *   `NascentConfig.enableDecisionalSlq`.
 */
enum class LiquidityType { ILQ, SLQ, DECISIONAL_SLQ, TLQ }

/** Which side of the book a pool rests on. */
enum class LiquiditySide { HIGH, LOW }

/**
 * One resting-liquidity reference.
 *
 * [confirmationIndex] is the bar on which this point became *knowable*, which
 * is generally later than [originIndex]. Every consumer must gate on the
 * confirmation index, never the origin, or the engine silently reads the future.
 */
data class LiquidityPoint(
    val type: LiquidityType,
    val side: LiquiditySide,
    val price: Double,
    val originIndex: Int,
    val confirmationIndex: Int,
    val timestamp: Long,
    val swept: Boolean = false,
    val sweepIndex: Int? = null,
)

/**
 * A Nascent liquidity cycle: one directional (B2S or S2B) transaction range.
 *
 * [direction] is the direction of the closing delivery leg. A BUY -> SELL cycle
 * is [Direction.BEARISH] and puts Inducement Liquidity at the range high and
 * Transactional Liquidity at the range low; SELL -> BUY mirrors it exactly.
 *
 * The range is built first and only then labelled, which is the whole point:
 * classifying every local high as ILQ (or every low as TLQ) without a valid
 * directional range is precisely the mistake this model exists to prevent.
 */
data class LiquidityCycle(
    val direction: Direction,
    val startIndex: Int,
    val endIndex: Int,
    val confirmationIndex: Int,
    val rangeHigh: Double,
    val rangeLow: Double,
    val ilq: LiquidityPoint?,
    val tlq: LiquidityPoint?,
    val slq: List<LiquidityPoint>,
    val confirmed: Boolean,
)

/** External contexts Nascent permits a setup to be taken from. */
enum class KeyLevelType { ILQ, SLQ, DECISIONAL_SLQ, TLQ, EPA_DP, EPA_DP_TOM }

/**
 * An external-timeframe location that a setup is allowed to form at.
 *
 * Levels are produced on the external series but consumed on the internal one,
 * so they carry a wall-clock [timestamp] and the [externalCloseTimestamp] of
 * the external bar that confirmed them. The internal engine may only use a
 * level once that external bar has actually closed — this is what keeps the
 * multi-timeframe path free of look-ahead.
 */
data class ExternalKeyLevel(
    val type: KeyLevelType,
    /** Trade direction this location supports (high-side pools support sells). */
    val direction: Direction,
    val price: Double,
    val timestamp: Long,
    val externalCloseTimestamp: Long,
    val evidence: EvidenceLevel,
    val fresh: Boolean = true,
)

/** Confirmed structural extreme. */
enum class StructurePointType { HIGH, LOW }

/**
 * A structural extreme with both of its timestamps.
 *
 * A low may print at bar 100 yet only become a confirmed structural low at bar
 * 103. A decision taken at bar 101 must not be able to see it, which is why
 * both indices are persisted rather than just the pivot.
 */
data class StructurePoint(
    val type: StructurePointType,
    val price: Double,
    val pivotBarIndex: Int,
    val confirmationBarIndex: Int,
)

/** Structural break classification. */
enum class StructureBreakType { BOS, CHOCH }

data class StructureBreak(
    val type: StructureBreakType,
    val direction: Direction,
    val level: Double,
    val originIndex: Int,
    val confirmationIndex: Int,
)

/**
 * A bounded price band used by EPA / DP / transaction reasoning.
 *
 * A Nascent range is **not** necessarily sideways consolidation — a directional
 * leg bounded by meaningful structural extremes (a "recent Buy -> Sell range")
 * is equally a range. [direction] therefore records which kind this is, and the
 * two timestamps record when it was created versus when it became knowable.
 */
data class PriceRange(
    val low: Double,
    val high: Double,
    val startIndex: Int,
    val endIndex: Int,
    val direction: Direction? = null,
    val creationTimestamp: Long = 0L,
    val confirmationTimestamp: Long = 0L,
) {
    val size: Double get() = high - low
    val equilibrium: Double get() = low + (high - low) * 0.5
}

/**
 * Nascent transaction taxonomy.
 *
 * The three names are strongly corroborated by related AlgoHub material, but
 * their precise geometry is [EvidenceLevel.UNRESOLVED]. In particular
 * "touched both range boundaries" is a working reconstruction, **not** an
 * official definition of a Range Transaction. Callers therefore treat every
 * [TransactionState] as supporting context; nothing in the pipeline is allowed
 * to rest a signal on a transaction classification alone.
 *
 * A transaction here is structured movement of price through market structure —
 * it has nothing to do with an individual executed retail order.
 */
enum class TransactionType { RANGE, SIMPLE, STRUCTURE_POINT, UNKNOWN }

data class TransactionState(
    val type: TransactionType,
    val direction: Direction?,
    val sourceIndex: Int?,
    val destinationIndex: Int?,
    val confirmed: Boolean,
    val evidence: EvidenceLevel,
)

/**
 * Efficient Price Action.
 *
 * Deliberately *not* a fair-value-gap detector: Nascent frames EPA in terms of
 * the preceding range, the current range, mitigation and continued delivery.
 * An FVG existing is neither necessary nor sufficient here.
 */
data class EpaState(
    val previousRange: PriceRange?,
    val currentRange: PriceRange?,
    val mitigationObserved: Boolean,
    val structureReturnObserved: Boolean,
    val direction: Direction?,
    val confirmed: Boolean,
    val confirmationIndex: Int?,
    val efficiency: Double,
)

/** Direct Pullback into the 50% of a delivery leg. */
data class DirectPullbackState(
    val direction: Direction,
    val sourceLegStart: Int,
    val sourceLegEnd: Int,
    val rangeHigh: Double,
    val rangeLow: Double,
    val equilibrium50: Double,
    val pullbackStart: Int?,
    val pullbackExtreme: Double?,
    val touchedEqZone: Boolean,
    val invalidated: Boolean,
    val confirmed: Boolean,
    val confirmationIndex: Int?,
)

/**
 * Transfer Of Money.
 *
 * The abbreviation is corroborated and its association with equilibrium pricing
 * is well supported, but the completion geometry Nascent intends is not. The
 * engine therefore refuses to report [COMPLETED] outside research mode instead
 * of inventing a "closed above the 50%" rule.
 */
enum class TomState { UNKNOWN, ACTIVE, COMPLETED, INVALIDATED }

/** The Nascent execution families supported by step 2 of the pipeline. */
enum class SetupType { MSU1, MSU2, MSU3, EPA_DP, EPA_DP_TOM, MSU1_DP, MSU2_DP }

/** Source-confirmed Nascent entry confirmations. */
enum class ConfirmationType { SWEEP_OF_HIGH_LOW, ENGULFING, DIRECT_PULLBACK_50 }

/** Validity is decided first; confidence only ever grades an already-valid setup. */
enum class SignalConfidence { A_PLUS, A, B, WATCH, INVALID }

/** Lifecycle of a signal. Only [LOCKED] may be drawn as a historical arrow. */
enum class SignalState { PROVISIONAL, CONFIRMED, LOCKED }

/**
 * A detected Nascent setup, before entry confirmation.
 *
 * The seven families are distinguished by [type] and detected by seven separate
 * detectors; the shared carrier keeps value equality (which the golden-fixture
 * tests rely on) without collapsing the geometries into one generic routine.
 */
data class NascentSetup(
    val type: SetupType,
    val direction: Direction,
    val originIndex: Int,
    val confirmationIndex: Int,
    /**
     * The external location this setup was actually built on.
     *
     * Recorded by the detector rather than assigned by the caller, because the
     * gate has to be answered where the setup *formed* — a reversal out of
     * resistance confirms far below that resistance, and validating the level
     * at the confirmation bar would reject it.
     */
    val keyLevel: ExternalKeyLevel?,
    val protectedExtreme: Double?,
    val referenceRange: PriceRange?,
    val epa: EpaState?,
    val directPullback: DirectPullbackState?,
    val tom: TomState,
    val transactions: List<TransactionState>,
    val evidence: EvidenceLevel,
    val notes: List<String>,
)

/**
 * An immutable, locked Nascent signal.
 *
 * Once emitted this must survive timeframe changes, chart refreshes, reconnects
 * and additional candles. [id] is deterministic in the inputs that identify the
 * event, so a recompute produces the identical id rather than a duplicate.
 */
data class NascentSignal(
    val id: String,
    val symbol: String,
    val timestamp: Long,
    val barIndex: Int,
    val direction: Direction,
    val externalTimeframe: Timeframe,
    val internalTimeframe: Timeframe,
    val keyLevelType: KeyLevelType,
    val setupType: SetupType,
    val confirmationType: ConfirmationType,
    val entryPrice: Double,
    val invalidationPrice: Double?,
    val targetPrice: Double?,
    val state: SignalState,
    val confidence: SignalConfidence,
    val score: Int,
    val evidence: EvidenceLevel,
    val reasons: List<String>,
)

/** Outcome of one pipeline gate, for explainability. */
enum class GateResult { PASS, FAIL, NOT_APPLICABLE }

data class NascentGate(val name: String, val result: GateResult, val detail: String)

/**
 * Why a bar did or did not produce a signal.
 *
 * This exists so "no arrow" can always be attributed to either "no valid Nascent
 * context" or "a detector failed", which are very different problems.
 */
data class NascentDiagnostic(
    val barIndex: Int,
    val timestamp: Long,
    val gates: List<NascentGate>,
    val rejectedReason: String?,
)

/** Full engine output for one series. */
data class NascentAnalysis(
    val signals: List<NascentSignal>,
    val externalTimeframe: Timeframe?,
    val internalTimeframe: Timeframe,
    val liquidityCycles: List<LiquidityCycle>,
    val keyLevels: List<ExternalKeyLevel>,
    val diagnostics: List<NascentDiagnostic>,
    val processedBars: Int,
    val notes: List<String>,
) {
    companion object {
        fun empty(internalTimeframe: Timeframe, note: String): NascentAnalysis = NascentAnalysis(
            signals = emptyList(),
            externalTimeframe = null,
            internalTimeframe = internalTimeframe,
            liquidityCycles = emptyList(),
            keyLevels = emptyList(),
            diagnostics = emptyList(),
            processedBars = 0,
            notes = listOf(note),
        )
    }
}
