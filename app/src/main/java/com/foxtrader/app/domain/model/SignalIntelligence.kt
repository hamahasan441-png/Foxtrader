package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Phase 13 institutional signal models. These models are deliberately small and
 * framework-free so the same logic can be used by live chart, replay and tests.
 */
@Serializable
enum class SignalProfile { SCALPING, INTRADAY, SWING }

/**
 * How LiT Pro confirms a structural level break.
 *
 * SHADOW accepts wick penetration, BODY requires the candle body to close through
 * the level, and BODY_PLUS_SWEEP requires a wick through the level plus a body
 * reclaim/close on the confirming side. BODY_PLUS_SWEEP is the production default
 * because it rejects most single-tick liquidity noise without introducing lookahead.
 */
@Serializable
enum class LitBreakMode { SHADOW, BODY, BODY_PLUS_SWEEP }

/** Institutional point-of-interest classification exposed to chart/replay. */
@Serializable
enum class LitPoiKind { DECISIONAL, EXTREME, BREAKER, FLIP }

/** Structural events emitted by the LiT Pro state machine. */
enum class LitEventType { PULLBACK, IDM, BOS, CHOCH, POI, SCOB }

/** User-editable Phase 13 LIT settings. Values are clamped again inside the engine. */
@Serializable
data class LitConfig(
    val profile: SignalProfile = SignalProfile.INTRADAY,
    val minConfidence: Int = 72,
    val requireDirectionalZone: Boolean = true,
    val setupLookback: Int = 40,
    val maxSweepToShiftBars: Int = 10,
    val maxShiftToRetestBars: Int = 12,
    val minRiskReward: Double = 2.0,
    val displacementAtrMultiple: Double = 1.25,
    // LiT Pro structural controls. Appended to preserve compatibility with the
    // existing positional presets/callers from Phase 13.
    val swingLeftBars: Int = 3,
    val swingRightBars: Int = 3,
    val breakMode: LitBreakMode = LitBreakMode.BODY_PLUS_SWEEP,
    val maxIdmToBosBars: Int = 12,
    val maxBosToChochBars: Int = 14,
    val maxPoiAgeBars: Int = 24,
    val allowInsideBarMother: Boolean = true,
    val followDeeperPoiCandle: Boolean = true,
    val requireScob: Boolean = false,
    val hiddenShadowMaxAtrFraction: Double = 0.35,
    val stopAtrBuffer: Double = 0.15,

    /**
     * Require a confirmed RSI divergence before a POI retest may be entered.
     *
     * The structural sequence says the market *reached* a decision point; a
     * divergence says momentum is actually failing there. Requiring both is
     * what separates a POI that reverses from one price simply passes through.
     * Trader-facing and switchable from the LiT study settings.
     */
    val requirePoiDivergence: Boolean = true,

    /** How far back a POI divergence may reach for its earlier pivot. */
    val poiDivergenceLookbackBars: Int = 40,

    /** RSI period used for the POI divergence check. */
    val poiDivergenceRsiPeriod: Int = 14,

    /** Minimum RSI separation, in RSI points, for a divergence to count. */
    val poiDivergenceMinRsiGap: Double = 1.5,

    /** Keep previously confirmed arrows on the chart instead of only live ones. */
    val historicalSignals: Boolean = true,

    /** Trailing bars re-analysed as live on every update. */
    val liveWindowBars: Int = 100,
) {
    fun sanitized(): LitConfig = copy(
        minConfidence = minConfidence.coerceIn(50, 95),
        setupLookback = setupLookback.coerceIn(20, 180),
        maxSweepToShiftBars = maxSweepToShiftBars.coerceIn(3, 30),
        maxShiftToRetestBars = maxShiftToRetestBars.coerceIn(3, 40),
        minRiskReward = minRiskReward.coerceIn(1.0, 5.0),
        displacementAtrMultiple = displacementAtrMultiple.coerceIn(0.8, 3.0),
        swingLeftBars = swingLeftBars.coerceIn(2, 8),
        swingRightBars = swingRightBars.coerceIn(2, 8),
        maxIdmToBosBars = maxIdmToBosBars.coerceIn(3, 30),
        maxBosToChochBars = maxBosToChochBars.coerceIn(3, 36),
        maxPoiAgeBars = maxPoiAgeBars.coerceIn(4, 80),
        hiddenShadowMaxAtrFraction = hiddenShadowMaxAtrFraction.coerceIn(0.05, 1.0),
        stopAtrBuffer = stopAtrBuffer.coerceIn(0.02, 0.75),
        poiDivergenceLookbackBars = poiDivergenceLookbackBars.coerceIn(10, 200),
        poiDivergenceRsiPeriod = poiDivergenceRsiPeriod.coerceIn(2, 100),
        poiDivergenceMinRsiGap = poiDivergenceMinRsiGap.coerceIn(0.0, 30.0),
        liveWindowBars = liveWindowBars.coerceIn(20, 2_000),
    )

    companion object {
        fun preset(profile: SignalProfile): LitConfig = when (profile) {
            SignalProfile.SCALPING -> LitConfig(
                profile = profile,
                minConfidence = 68,
                requireDirectionalZone = false,
                setupLookback = 36,
                maxSweepToShiftBars = 7,
                maxShiftToRetestBars = 7,
                minRiskReward = 1.8,
                displacementAtrMultiple = 1.15,
                swingLeftBars = 2,
                swingRightBars = 2,
                maxIdmToBosBars = 8,
                maxBosToChochBars = 10,
                maxPoiAgeBars = 16,
                hiddenShadowMaxAtrFraction = 0.30,
                stopAtrBuffer = 0.12,
            )
            SignalProfile.INTRADAY -> LitConfig(
                profile = profile,
                minConfidence = 72,
                requireDirectionalZone = true,
                setupLookback = 60,
                maxSweepToShiftBars = 10,
                maxShiftToRetestBars = 12,
                minRiskReward = 2.0,
                displacementAtrMultiple = 1.25,
                swingLeftBars = 3,
                swingRightBars = 3,
                maxIdmToBosBars = 12,
                maxBosToChochBars = 14,
                maxPoiAgeBars = 24,
                hiddenShadowMaxAtrFraction = 0.35,
                stopAtrBuffer = 0.15,
            )
            SignalProfile.SWING -> LitConfig(
                profile = profile,
                minConfidence = 76,
                requireDirectionalZone = true,
                setupLookback = 100,
                maxSweepToShiftBars = 16,
                maxShiftToRetestBars = 20,
                minRiskReward = 2.3,
                displacementAtrMultiple = 1.35,
                swingLeftBars = 4,
                swingRightBars = 4,
                maxIdmToBosBars = 18,
                maxBosToChochBars = 22,
                maxPoiAgeBars = 40,
                hiddenShadowMaxAtrFraction = 0.45,
                stopAtrBuffer = 0.18,
            )
        }
    }
}

/** User-editable SMT synchronization/divergence settings. */
@Serializable
data class SmtConfig(
    val profile: SignalProfile = SignalProfile.INTRADAY,
    val period: Int = 160,
    val swingLookback: Int = 3,
    val minCorrelation: Double = 0.45,
    /** Maximum peer timestamp skew as a fraction of the inferred candle interval. */
    val maxTimestampSkewFraction: Double = 0.25,
    val maxSwingSyncBars: Int = 4,
    val maxSignalAgeBars: Int = 24,
    val minDivergenceStrength: Double = 0.05,
    val minConfidence: Int = 62,
) {
    fun sanitized(): SmtConfig = copy(
        period = period.coerceIn(40, 600),
        swingLookback = swingLookback.coerceIn(1, 10),
        minCorrelation = minCorrelation.coerceIn(0.10, 0.95),
        maxTimestampSkewFraction = maxTimestampSkewFraction.coerceIn(0.0, 0.5),
        maxSwingSyncBars = maxSwingSyncBars.coerceIn(1, 12),
        maxSignalAgeBars = maxSignalAgeBars.coerceIn(1, 80),
        minDivergenceStrength = minDivergenceStrength.coerceIn(0.01, 1.0),
        minConfidence = minConfidence.coerceIn(50, 95),
    )

    companion object {
        fun preset(profile: SignalProfile): SmtConfig = when (profile) {
            SignalProfile.SCALPING -> SmtConfig(profile, 100, 2, 0.40, 0.20, 3, 10, 0.04, 60)
            SignalProfile.INTRADAY -> SmtConfig(profile, 160, 3, 0.45, 0.25, 4, 24, 0.05, 62)
            SignalProfile.SWING -> SmtConfig(profile, 260, 5, 0.50, 0.30, 6, 40, 0.07, 66)
        }
    }
}

/** User-editable Smart Money Structure (SMS) settings. */
@Serializable
data class SmsConfig(
    val profile: SignalProfile = SignalProfile.INTRADAY,
    val swingBars: Int = 5,
    val displacementAtrMultiple: Double = 1.2,
    val maxDisplacementGapBars: Int = 6,
    val maxSweepToShiftBars: Int = 12,
    val maxSignalAgeBars: Int = 4,
    val minConfidence: Int = 66,
    val requireLiquiditySweep: Boolean = false,
    val requireDisplacementForChoch: Boolean = false,
) {
    fun sanitized(): SmsConfig = copy(
        swingBars = swingBars.coerceIn(2, 12),
        displacementAtrMultiple = displacementAtrMultiple.coerceIn(0.8, 3.0),
        maxDisplacementGapBars = maxDisplacementGapBars.coerceIn(1, 20),
        maxSweepToShiftBars = maxSweepToShiftBars.coerceIn(2, 30),
        maxSignalAgeBars = maxSignalAgeBars.coerceIn(1, 20),
        minConfidence = minConfidence.coerceIn(50, 95),
    )

    companion object {
        fun preset(profile: SignalProfile): SmsConfig = when (profile) {
            SignalProfile.SCALPING -> SmsConfig(profile, 3, 1.10, 4, 8, 3, 62, false, false)
            SignalProfile.INTRADAY -> SmsConfig(profile, 5, 1.20, 6, 12, 4, 66, false, false)
            SignalProfile.SWING -> SmsConfig(profile, 7, 1.35, 8, 18, 6, 70, true, true)
        }
    }
}

/**
 * Coarse state exposed to UI/scanners. The detailed structural context is kept in
 * [LitProContext] so adding a new visual event does not require changing every
 * state consumer.
 */
enum class LitStage {
    SCANNING,
    PULLBACK_READY,
    IDM_CONFIRMED,
    BOS_CONFIRMED,
    CHOCH_CONFIRMED,
    POI_READY,
    SCOB_READY,
    RETEST_READY,
    VALIDATED,
    // Kept for compatibility with earlier Phase 13 UI text and saved state.
    LIQUIDITY_READY,
    SWEEP_CONFIRMED,
    SHIFT_CONFIRMED,
}

data class LitLevel(
    val type: LitEventType,
    val direction: Direction?,
    val price: Double,
    val originIndex: Int,
    /** First bar where this event is objectively knowable. */
    val confirmationIndex: Int,
    val swept: Boolean = false,
)

data class LitPoiZone(
    val kind: LitPoiKind,
    val direction: Direction,
    val low: Double,
    val high: Double,
    val originIndex: Int,
    val confirmationIndex: Int,
    val mitigated: Boolean = false,
    val quality: Int = 0,
)

data class LitScob(
    val direction: Direction,
    val low: Double,
    val high: Double,
    val originIndex: Int,
    val confirmationIndex: Int,
    val quality: Int,
)

data class LitProContext(
    val trend: Direction? = null,
    val pullback: LitLevel? = null,
    val inducement: LitLevel? = null,
    val bos: LitLevel? = null,
    val choch: LitLevel? = null,
    val poi: LitPoiZone? = null,
    val scob: LitScob? = null,
    val protectedHigh: Double? = null,
    val protectedLow: Double? = null,
    val notes: List<String> = emptyList(),
)

data class LitSignal(
    val symbol: String,
    val timeframe: Timeframe,
    val direction: Direction,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val confidence: Int,
    val sweepIndex: Int,
    val shiftIndex: Int,
    val confirmationIndex: Int,
    val timestamp: Long,
    val confirmations: List<String>,
    val rationale: String,
)

data class LitAnalysis(
    val symbol: String,
    val timeframe: Timeframe,
    val stage: LitStage,
    val signal: LitSignal?,
    val narrative: String,
    val context: LitProContext = LitProContext(),
) {
    companion object {
        fun empty(symbol: String, timeframe: Timeframe, reason: String = "No confirmed LIT setup.") =
            LitAnalysis(symbol, timeframe, LitStage.SCANNING, null, reason)
    }
}

enum class SmsEventType { BOS, CHOCH, MSS }

data class SmsSignal(
    val symbol: String,
    val timeframe: Timeframe,
    val direction: Direction,
    val type: SmsEventType,
    val price: Double,
    /** Swing/event index on the primary chart. */
    val eventIndex: Int,
    /** First bar on which the structure event is objectively knowable. */
    val confirmationIndex: Int,
    val confidence: Int,
    val protectedHigh: Double?,
    val protectedLow: Double?,
    val timestamp: Long,
    val confirmations: List<String>,
    val rationale: String,
)

data class SmsAnalysis(
    val symbol: String,
    val timeframe: Timeframe,
    val bias: Bias,
    val signal: SmsSignal?,
    val protectedHigh: Double?,
    val protectedLow: Double?,
    val narrative: String,
) {
    companion object {
        fun empty(symbol: String, timeframe: Timeframe, reason: String = "No confirmed smart-money structure event.") =
            SmsAnalysis(symbol, timeframe, Bias.NEUTRAL, null, null, null, reason)
    }
}

data class SignalFusionComponent(
    val name: String,
    val direction: Direction?,
    val score: Int,
    val active: Boolean,
    val detail: String,
)

data class SignalFusionResult(
    val direction: Direction?,
    val score: Int,
    val strong: Boolean,
    val conflict: Boolean,
    val components: List<SignalFusionComponent>,
    val confirmations: List<String>,
    val narrative: String,
)
