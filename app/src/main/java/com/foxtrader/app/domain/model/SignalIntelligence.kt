package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Phase 13 institutional signal models. These models are deliberately small and
 * framework-free so the same logic can be used by live chart, replay and tests.
 */
@Serializable
enum class SignalProfile { SCALPING, INTRADAY, SWING }

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
) {
    fun sanitized(): LitConfig = copy(
        minConfidence = minConfidence.coerceIn(50, 95),
        setupLookback = setupLookback.coerceIn(20, 120),
        maxSweepToShiftBars = maxSweepToShiftBars.coerceIn(3, 30),
        maxShiftToRetestBars = maxShiftToRetestBars.coerceIn(3, 40),
        minRiskReward = minRiskReward.coerceIn(1.0, 5.0),
        displacementAtrMultiple = displacementAtrMultiple.coerceIn(0.8, 3.0),
    )

    companion object {
        fun preset(profile: SignalProfile): LitConfig = when (profile) {
            SignalProfile.SCALPING -> LitConfig(profile, 68, false, 28, 7, 7, 1.8, 1.15)
            SignalProfile.INTRADAY -> LitConfig(profile, 72, true, 40, 10, 12, 2.0, 1.25)
            SignalProfile.SWING -> LitConfig(profile, 76, true, 70, 16, 20, 2.3, 1.35)
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


enum class LitStage { SCANNING, LIQUIDITY_READY, SWEEP_CONFIRMED, SHIFT_CONFIRMED, RETEST_READY, VALIDATED }

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
