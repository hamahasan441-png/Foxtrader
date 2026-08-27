package com.foxtrader.app.feature.chart.presentation

import androidx.compose.runtime.Immutable

/**
 * Typed, finite-safe settings for chart studies.
 *
 * These values are part of [IndicatorToggles] so a settings change participates
 * in the same immutable state/equality contract as enable/disable changes. That
 * is important for the incremental chart cache: a frame computed with RSI(14)
 * must never be reused after the trader changes the study to RSI(7).
 */
@Immutable
data class ChartStudySettings(
    val ema: EmaStudySettings = EmaStudySettings(),
    val rsi: RsiStudySettings = RsiStudySettings(),
    val rsiOrderFlow: RsiOrderFlowStudySettings = RsiOrderFlowStudySettings(),
    val rsiReversal: RsiReversalStudySettings = RsiReversalStudySettings(),
    val liquiditySweep: LiquiditySweepStudySettings = LiquiditySweepStudySettings(),
    val virginWick: VirginWickStudySettings = VirginWickStudySettings(),
    val pivotSweepDivergence: PivotSweepDivergenceStudySettings = PivotSweepDivergenceStudySettings(),
    val valueAreaLiquidityRejection: ValueAreaLiquidityRejectionStudySettings = ValueAreaLiquidityRejectionStudySettings(),
    val amd: AmdStudySettings = AmdStudySettings(),
    val nascent: NascentStudySettings = NascentStudySettings(),
    val macd: MacdStudySettings = MacdStudySettings(),
    val bollinger: BollingerStudySettings = BollingerStudySettings(),
    val superTrend: SuperTrendStudySettings = SuperTrendStudySettings(),
    val stochastic: StochasticStudySettings = StochasticStudySettings(),
    val keltner: KeltnerStudySettings = KeltnerStudySettings(),
    val donchian: DonchianStudySettings = DonchianStudySettings(),
    val ichimoku: IchimokuStudySettings = IchimokuStudySettings(),
    val parabolicSar: ParabolicSarStudySettings = ParabolicSarStudySettings(),
    val mfi: MfiStudySettings = MfiStudySettings(),
) {
    fun sanitized(): ChartStudySettings = copy(
        ema = ema.sanitized(),
        rsi = rsi.sanitized(),
        rsiOrderFlow = rsiOrderFlow.sanitized(),
        rsiReversal = rsiReversal.sanitized(),
        liquiditySweep = liquiditySweep.sanitized(),
        virginWick = virginWick.sanitized(),
        pivotSweepDivergence = pivotSweepDivergence.sanitized(),
        valueAreaLiquidityRejection = valueAreaLiquidityRejection.sanitized(),
        amd = amd.sanitized(),
        nascent = nascent.sanitized(),
        macd = macd.sanitized(),
        bollinger = bollinger.sanitized(),
        superTrend = superTrend.sanitized(),
        stochastic = stochastic.sanitized(),
        keltner = keltner.sanitized(),
        donchian = donchian.sanitized(),
        ichimoku = ichimoku.sanitized(),
        parabolicSar = parabolicSar.sanitized(),
        mfi = mfi.sanitized(),
    )
}

/**
 * Trader-facing settings for the RSI Orderflow Reversal engine.
 *
 * Deliberately narrower than [com.foxtrader.app.domain.usecase.rsireversal.RsiReversalConfig]:
 * the engine keeps its full threshold surface for research and backtesting,
 * while the chart exposes only the choices a trader should be making, so the
 * shipped defaults stay a faithful implementation of the methodology rather
 * than a quietly tuned variant of it.
 */
@Immutable
data class RsiReversalStudySettings(
    val rsiLength: Int = 14,
    val pricePivotStrength: Int = 2,
    val rsiPivotStrength: Int = 2,
    /** Require the RSI structure break to close beyond the protected swing. */
    val requireRsiCloseBreak: Boolean = true,
    val entryMode: RsiReversalEntryPreset = RsiReversalEntryPreset.BALANCED,
    val riskReward: Double = 4.0,
    val ltfConfirmationWindowBars: Int = 12,
    /** Draw the P1..P4 pattern points and RSI reference swings (§23). */
    val showDebugLabels: Boolean = false,
) {
    fun sanitized(): RsiReversalStudySettings = copy(
        rsiLength = rsiLength.coerceIn(2, 100),
        pricePivotStrength = pricePivotStrength.coerceIn(1, 20),
        rsiPivotStrength = rsiPivotStrength.coerceIn(1, 20),
        riskReward = riskReward.coerceIn(0.5, 20.0),
        ltfConfirmationWindowBars = ltfConfirmationWindowBars.coerceIn(1, 200),
    )
}

/** Entry strictness presets (§18). */
enum class RsiReversalEntryPreset(val label: String) {
    AGGRESSIVE("Aggressive"),
    BALANCED("Balanced"),
    STRICT("Strict"),
}

/**
 * Trader-facing settings for the Liquidity Sweep multi-timeframe engine.
 *
 * Narrower than the engine's own configuration on purpose: the engine keeps its
 * full surface for research, while the chart exposes the choices a trader
 * should actually be making. What is left out — penetration thresholds, level
 * clustering, ageing — is the part the methodology fixes rather than the part a
 * trader tunes.
 */
@Immutable
data class LiquiditySweepStudySettings(
    val biasMode: LiquiditySweepBiasPreset = LiquiditySweepBiasPreset.HIGHER_TIMEFRAME,
    val entryMode: LiquiditySweepEntryPreset = LiquiditySweepEntryPreset.RETEST,
    val useEqualLevels: Boolean = true,
    val usePreviousHtfRange: Boolean = false,
    val targetOpposingLiquidity: Boolean = true,
    val riskReward: Double = 2.0,
    val minRiskReward: Double = 1.5,
    val entryWindowBars: Int = 12,
    val maxReclaimBars: Int = 3,
    /** Keep previously confirmed arrows rather than only the live window. */
    val historicalSignals: Boolean = true,
    val liveWindowBars: Int = 200,
    /** Draw the marked liquidity levels on the price chart. */
    val showLevels: Boolean = false,
) {
    fun sanitized(): LiquiditySweepStudySettings = copy(
        riskReward = riskReward.coerceIn(0.5, 20.0),
        minRiskReward = minRiskReward.coerceIn(0.1, 20.0),
        entryWindowBars = entryWindowBars.coerceIn(1, 200),
        maxReclaimBars = maxReclaimBars.coerceIn(1, 20),
        liveWindowBars = liveWindowBars.coerceIn(20, 5_000),
    )
}

/**
 * Trader-facing settings for the Virgin Wick engine.
 *
 * The noise floors, ageing and cluster tolerances stay on the engine: those are
 * the part of the model that is fixed, not the part a trader tunes. What is
 * exposed is what the methodology actually asks a trader to decide.
 */
@Immutable
data class VirginWickStudySettings(
    val testMode: VirginWickTestPreset = VirginWickTestPreset.ANY_TOUCH,
    val entryMode: VirginWickEntryPreset = VirginWickEntryPreset.IFVG,
    /** Restrict entries to the New York and London opens. */
    val killZonesOnly: Boolean = false,
    val closesBeyondToActivate: Int = 1,
    val confirmationWindowBars: Int = 60,
    val defaultRewardMultiple: Double = 2.0,
    val minRewardMultiple: Double = 1.5,
    val maxDolRewardMultiple: Double = 4.0,
    val historicalSignals: Boolean = true,
    val liveWindowBars: Int = 500,
    /** Draw the untested wick zones on the price chart. */
    val showZones: Boolean = false,
) {
    fun sanitized(): VirginWickStudySettings = copy(
        closesBeyondToActivate = closesBeyondToActivate.coerceIn(1, 10),
        confirmationWindowBars = confirmationWindowBars.coerceIn(1, 600),
        defaultRewardMultiple = defaultRewardMultiple.coerceIn(0.5, 20.0),
        minRewardMultiple = minRewardMultiple.coerceIn(0.1, 20.0),
        maxDolRewardMultiple = maxDolRewardMultiple.coerceIn(0.5, 50.0),
        liveWindowBars = liveWindowBars.coerceIn(20, 10_000),
    )
}

/** How much of a wick price must take before it stops being untested. */
enum class VirginWickTestPreset(val label: String) {
    ANY_TOUCH("Touch"),
    MIDPOINT("Half"),
    EXTREME("Full"),
}

/** What must confirm the return to the zone. */
enum class VirginWickEntryPreset(val label: String) {
    POI_TOUCH("Touch"),
    IFVG("iFVG"),
    IFVG_IN_POI("In-zone"),
}

/** How the directional bias is established. */
enum class LiquiditySweepBiasPreset(val label: String) {
    OFF("Off"),
    HIGHER_TIMEFRAME("HTF"),
    BOTH_AGREE("HTF+MTF"),
}

/** What the engine waits for after the reclaim. */
enum class LiquiditySweepEntryPreset(val label: String) {
    RECLAIM("Reclaim"),
    RETEST("Retest"),
    CHOCH_RETEST("CHOCH"),
}

enum class PivotSweepDivergenceMode { FAST, PRECISION, POWER }

enum class ValueAreaLiquidityRejectionMode { FAST, PRECISION, POWER }

enum class AmdMode { FAST, PRECISION, POWER }

/** Signal-quality floor offered to the trader, mapped to Nascent grades. */
enum class NascentQuality { A_PLUS_ONLY, A_AND_ABOVE, ALL_VALID }

/** How much Nascent diagnostic detail to retain. Off costs nothing. */
enum class NascentDebugLevel { OFF, SUMMARY, FULL }

/**
 * Trader-facing Nascent settings.
 *
 * Deliberately a short list. The engine has many isolated thresholds, but
 * exposing all of them would invite tuning a documented methodology into
 * something else; the experimental ones stay inside
 * [com.foxtrader.app.domain.usecase.nascent.NascentConfig].
 */
@Immutable
data class NascentStudySettings(
    val mode: com.foxtrader.app.domain.usecase.nascent.model.NascentMode =
        com.foxtrader.app.domain.usecase.nascent.model.NascentMode.BALANCED,
    val quality: NascentQuality = NascentQuality.ALL_VALID,
    /** Reconstruct historical signals, not only those forming from now on. */
    val historicalCalculation: Boolean = true,
    val historyDepthBars: Int = 4_000,
    /** Rolling window described to the trader as live analysis. */
    val liveWindowBars: Int = 100,
    val showKeyLevels: Boolean = true,
    val showSetupLabels: Boolean = false,
    val debug: NascentDebugLevel = NascentDebugLevel.OFF,
) {
    fun sanitized(): NascentStudySettings = copy(
        historyDepthBars = historyDepthBars.coerceIn(200, 20_000),
        liveWindowBars = liveWindowBars.coerceIn(20, 2_000),
    )
}

@Immutable
data class AmdStudySettings(
    val mode: AmdMode = AmdMode.PRECISION,
    val atrPeriod: Int = 14,
    val minAccumulationBars: Int = 6,
    val maxAccumulationBars: Int = 40,
    val accumulationRangeAtrMultiple: Double = 1.6,
    val minSweepAtr: Double = 0.15,
    val minRejectionWickFraction: Double = 0.20,
    val minCloseLocation: Double = 0.55,
    val maxReclaimBars: Int = 2,
    val maxConfirmBars: Int = 4,
    val displacementAtrMultiple: Double = 0.45,
    val stopBufferAtr: Double = 0.25,
    val rewardRisk: Double = 2.0,
    val minScore: Int = 66,
    val cooldownBars: Int = 6,
    val sessionOffsetMinutes: Int = 0,
    val maxSignals: Int = 160,
) {
    fun sanitized(): AmdStudySettings {
        val minBars = minAccumulationBars.coerceIn(3, 250)
        return copy(
            atrPeriod = atrPeriod.coerceIn(2, MAX_PERIOD),
            minAccumulationBars = minBars,
            maxAccumulationBars = maxAccumulationBars.coerceIn(minBars, 500),
            accumulationRangeAtrMultiple = finiteOr(accumulationRangeAtrMultiple, 1.6).coerceIn(0.1, 10.0),
            minSweepAtr = finiteOr(minSweepAtr, 0.15).coerceIn(0.0, 3.0),
            minRejectionWickFraction = finiteOr(minRejectionWickFraction, 0.20).coerceIn(0.0, 1.0),
            minCloseLocation = finiteOr(minCloseLocation, 0.55).coerceIn(0.50, 1.0),
            maxReclaimBars = maxReclaimBars.coerceIn(0, 25),
            maxConfirmBars = maxConfirmBars.coerceIn(0, 30),
            displacementAtrMultiple = finiteOr(displacementAtrMultiple, 0.45).coerceIn(0.0, 5.0),
            stopBufferAtr = finiteOr(stopBufferAtr, 0.25).coerceIn(0.0, 5.0),
            rewardRisk = finiteOr(rewardRisk, 2.0).coerceIn(0.25, 10.0),
            minScore = minScore.coerceIn(0, 100),
            cooldownBars = cooldownBars.coerceIn(0, 250),
            sessionOffsetMinutes = sessionOffsetMinutes.coerceIn(-720, 840),
            maxSignals = maxSignals.coerceIn(20, 500),
        )
    }
}

@Immutable
data class ValueAreaLiquidityRejectionStudySettings(
    val mode: ValueAreaLiquidityRejectionMode = ValueAreaLiquidityRejectionMode.PRECISION,
    val profileBins: Int = 48,
    val valueAreaPercent: Double = 0.70,
    val minPreviousSessionBars: Int = 24,
    val atrPeriod: Int = 14,
    val swingLeft: Int = 2,
    val swingRight: Int = 2,
    val liquidityLookback: Int = 30,
    val poolToleranceAtr: Double = 0.50,
    val minSweepAtr: Double = 0.04,
    val minWickFraction: Double = 0.25,
    val minCloseLocation: Double = 0.55,
    val volumeLookback: Int = 20,
    val volumeSpikeMultiple: Double = 1.15,
    val structureLookback: Int = 5,
    val maxConfirmBars: Int = 4,
    val displacementAtrMultiple: Double = 0.45,
    val stopBufferAtr: Double = 0.20,
    val minPocRewardRisk: Double = 1.00,
    val minScore: Int = 66,
    val cooldownBars: Int = 6,
    val sessionOffsetMinutes: Int = 0,
    val maxSignals: Int = 160,
) {
    fun sanitized(): ValueAreaLiquidityRejectionStudySettings = copy(
        profileBins = profileBins.coerceIn(12, 200),
        valueAreaPercent = finiteOr(valueAreaPercent, 0.70).coerceIn(0.50, 0.90),
        minPreviousSessionBars = minPreviousSessionBars.coerceIn(4, 1_500),
        atrPeriod = atrPeriod.coerceIn(2, MAX_PERIOD),
        swingLeft = swingLeft.coerceIn(1, 25),
        swingRight = swingRight.coerceIn(1, 25),
        liquidityLookback = liquidityLookback.coerceIn(3, 500),
        poolToleranceAtr = finiteOr(poolToleranceAtr, 0.50).coerceIn(0.0, 5.0),
        minSweepAtr = finiteOr(minSweepAtr, 0.04).coerceIn(0.0, 3.0),
        minWickFraction = finiteOr(minWickFraction, 0.25).coerceIn(0.0, 1.0),
        minCloseLocation = finiteOr(minCloseLocation, 0.55).coerceIn(0.50, 1.0),
        volumeLookback = volumeLookback.coerceIn(2, 500),
        volumeSpikeMultiple = finiteOr(volumeSpikeMultiple, 1.15).coerceIn(0.0, 10.0),
        structureLookback = structureLookback.coerceIn(1, 100),
        maxConfirmBars = maxConfirmBars.coerceIn(0, 30),
        displacementAtrMultiple = finiteOr(displacementAtrMultiple, 0.45).coerceIn(0.0, 5.0),
        stopBufferAtr = finiteOr(stopBufferAtr, 0.20).coerceIn(0.0, 5.0),
        minPocRewardRisk = finiteOr(minPocRewardRisk, 1.00).coerceIn(0.25, 10.0),
        minScore = minScore.coerceIn(0, 100),
        cooldownBars = cooldownBars.coerceIn(0, 250),
        sessionOffsetMinutes = sessionOffsetMinutes.coerceIn(-720, 840),
        maxSignals = maxSignals.coerceIn(20, 500),
    )
}

@Immutable
data class PivotSweepDivergenceStudySettings(
    val mode: PivotSweepDivergenceMode = PivotSweepDivergenceMode.PRECISION,
    val rsiPeriod: Int = 14,
    val flowPeriod: Int = 14,
    val flowSmoothing: Int = 5,
    val pivotLeft: Int = 3,
    val pivotRight: Int = 3,
    val minPivotSeparation: Int = 5,
    val maxPivotSeparation: Int = 80,
    val minRsiDifference: Double = 2.0,
    val minFlowDifference: Double = 3.0,
    val atrPeriod: Int = 14,
    val minSweepAtr: Double = 0.05,
    val minRejectionWickFraction: Double = 0.25,
    val minCloseLocation: Double = 0.55,
    val structureLookback: Int = 5,
    val maxConfirmBars: Int = 4,
    val displacementAtrMultiple: Double = 0.45,
    val stopBufferAtr: Double = 0.25,
    val rewardRisk: Double = 2.0,
    val minScore: Int = 66,
    val cooldownBars: Int = 6,
    val sessionOffsetMinutes: Int = 0,
    val maxSignals: Int = 160,
    /** Bars either side of the divergence pivot that may carry the level sweep. */
    val sweepWindowBars: Int = 3,
    /** Bars allowed between the level pierce and its closing reclaim. */
    val maxReclaimBars: Int = 2,
) {
    fun sanitized(): PivotSweepDivergenceStudySettings {
        val minSep = minPivotSeparation.coerceIn(1, 250)
        return copy(
            rsiPeriod = rsiPeriod.coerceIn(2, MAX_PERIOD),
            flowPeriod = flowPeriod.coerceIn(2, MAX_PERIOD),
            flowSmoothing = flowSmoothing.coerceIn(1, 100),
            pivotLeft = pivotLeft.coerceIn(1, 25),
            pivotRight = pivotRight.coerceIn(1, 25),
            minPivotSeparation = minSep,
            maxPivotSeparation = maxPivotSeparation.coerceIn(minSep, 500),
            minRsiDifference = finiteOr(minRsiDifference, 2.0).coerceIn(0.0, 50.0),
            minFlowDifference = finiteOr(minFlowDifference, 3.0).coerceIn(0.0, 50.0),
            atrPeriod = atrPeriod.coerceIn(2, MAX_PERIOD),
            minSweepAtr = finiteOr(minSweepAtr, 0.05).coerceIn(0.0, 3.0),
            minRejectionWickFraction = finiteOr(minRejectionWickFraction, 0.25).coerceIn(0.0, 1.0),
            minCloseLocation = finiteOr(minCloseLocation, 0.55).coerceIn(0.50, 1.0),
            structureLookback = structureLookback.coerceIn(1, 100),
            maxConfirmBars = maxConfirmBars.coerceIn(0, 30),
            displacementAtrMultiple = finiteOr(displacementAtrMultiple, 0.45).coerceIn(0.0, 5.0),
            stopBufferAtr = finiteOr(stopBufferAtr, 0.25).coerceIn(0.0, 5.0),
            rewardRisk = finiteOr(rewardRisk, 2.0).coerceIn(0.25, 10.0),
            minScore = minScore.coerceIn(0, 100),
            cooldownBars = cooldownBars.coerceIn(0, 250),
            sessionOffsetMinutes = sessionOffsetMinutes.coerceIn(-720, 840),
            maxSignals = maxSignals.coerceIn(20, 500),
            sweepWindowBars = sweepWindowBars.coerceIn(0, 25),
            maxReclaimBars = maxReclaimBars.coerceIn(0, 25),
        )
    }
}

@Immutable
data class EmaStudySettings(
    val fastPeriod: Int = 20,
    val slowPeriod: Int = 50,
) {
    fun sanitized(): EmaStudySettings {
        val fast = fastPeriod.coerceIn(1, MAX_PERIOD)
        val slow = slowPeriod.coerceIn(1, MAX_PERIOD)
        return if (fast <= slow) copy(fastPeriod = fast, slowPeriod = slow)
        else copy(fastPeriod = slow, slowPeriod = fast)
    }
}

@Immutable
data class RsiStudySettings(
    val period: Int = 14,
    val overbought: Double = 70.0,
    val oversold: Double = 30.0,
) {
    fun sanitized(): RsiStudySettings {
        val low = finiteOr(oversold, 30.0).coerceIn(1.0, 49.0)
        val high = finiteOr(overbought, 70.0).coerceIn(51.0, 99.0)
        return copy(period = period.coerceIn(2, MAX_PERIOD), overbought = high, oversold = low)
    }
}

@Immutable
data class RsiOrderFlowStudySettings(
    val rsiPeriod: Int = 14,
    val flowPeriod: Int = 14,
    val flowSmoothing: Int = 5,
    val pivotLeft: Int = 3,
    val pivotRight: Int = 3,
    val minPivotSeparation: Int = 5,
    val maxPivotSeparation: Int = 80,
    val minRsiDifference: Double = 2.0,
    val minFlowDifference: Double = 3.0,
    val includeHidden: Boolean = false,
    val minStrength: Int = 40,
    val riskLookback: Int = 14,
    val stopBufferRangeMultiple: Double = 0.25,
    val rewardRisk: Double = 2.0,
) {
    fun sanitized(): RsiOrderFlowStudySettings {
        val minSep = minPivotSeparation.coerceIn(1, 250)
        return copy(
            rsiPeriod = rsiPeriod.coerceIn(2, MAX_PERIOD),
            flowPeriod = flowPeriod.coerceIn(2, MAX_PERIOD),
            flowSmoothing = flowSmoothing.coerceIn(1, 100),
            pivotLeft = pivotLeft.coerceIn(1, 25),
            pivotRight = pivotRight.coerceIn(1, 25),
            minPivotSeparation = minSep,
            maxPivotSeparation = maxPivotSeparation.coerceIn(minSep, 500),
            minRsiDifference = finiteOr(minRsiDifference, 2.0).coerceIn(0.0, 50.0),
            minFlowDifference = finiteOr(minFlowDifference, 3.0).coerceIn(0.0, 50.0),
            minStrength = minStrength.coerceIn(0, 100),
            riskLookback = riskLookback.coerceIn(1, 250),
            stopBufferRangeMultiple = finiteOr(stopBufferRangeMultiple, 0.25).coerceIn(0.0, 5.0),
            rewardRisk = finiteOr(rewardRisk, 2.0).coerceIn(0.25, 10.0),
        )
    }
}

@Immutable
data class MacdStudySettings(
    val fastPeriod: Int = 12,
    val slowPeriod: Int = 26,
    val signalPeriod: Int = 9,
) {
    fun sanitized(): MacdStudySettings {
        val fast = fastPeriod.coerceIn(1, MAX_PERIOD)
        val slow = slowPeriod.coerceIn(2, MAX_PERIOD)
        val resolvedSlow = if (slow <= fast) (fast + 1).coerceAtMost(MAX_PERIOD) else slow
        val resolvedFast = if (fast >= resolvedSlow) (resolvedSlow - 1).coerceAtLeast(1) else fast
        return copy(
            fastPeriod = resolvedFast,
            slowPeriod = resolvedSlow,
            signalPeriod = signalPeriod.coerceIn(1, MAX_PERIOD),
        )
    }
}

@Immutable
data class BollingerStudySettings(
    val period: Int = 20,
    val multiplier: Double = 2.0,
) {
    fun sanitized(): BollingerStudySettings = copy(
        period = period.coerceIn(2, MAX_PERIOD),
        multiplier = finiteOr(multiplier, 2.0).coerceIn(0.1, 10.0),
    )
}

@Immutable
data class SuperTrendStudySettings(
    val atrPeriod: Int = 10,
    val multiplier: Double = 3.0,
) {
    fun sanitized(): SuperTrendStudySettings = copy(
        atrPeriod = atrPeriod.coerceIn(1, MAX_PERIOD),
        multiplier = finiteOr(multiplier, 3.0).coerceIn(0.1, 20.0),
    )
}

@Immutable
data class StochasticStudySettings(
    val kPeriod: Int = 14,
    val dPeriod: Int = 3,
    val overbought: Double = 80.0,
    val oversold: Double = 20.0,
) {
    fun sanitized(): StochasticStudySettings {
        val low = finiteOr(oversold, 20.0).coerceIn(1.0, 49.0)
        val high = finiteOr(overbought, 80.0).coerceIn(51.0, 99.0)
        return copy(
            kPeriod = kPeriod.coerceIn(1, MAX_PERIOD),
            dPeriod = dPeriod.coerceIn(1, MAX_PERIOD),
            overbought = high,
            oversold = low,
        )
    }
}

@Immutable
data class KeltnerStudySettings(
    val emaPeriod: Int = 20,
    val atrPeriod: Int = 10,
    val multiplier: Double = 2.0,
) {
    fun sanitized(): KeltnerStudySettings = copy(
        emaPeriod = emaPeriod.coerceIn(1, MAX_PERIOD),
        atrPeriod = atrPeriod.coerceIn(1, MAX_PERIOD),
        multiplier = finiteOr(multiplier, 2.0).coerceIn(0.1, 20.0),
    )
}

@Immutable
data class DonchianStudySettings(val period: Int = 20) {
    fun sanitized(): DonchianStudySettings = copy(period = period.coerceIn(1, MAX_PERIOD))
}

@Immutable
data class IchimokuStudySettings(
    val tenkanPeriod: Int = 9,
    val kijunPeriod: Int = 26,
    val senkouBPeriod: Int = 52,
    val displacement: Int = 26,
) {
    fun sanitized(): IchimokuStudySettings = copy(
        tenkanPeriod = tenkanPeriod.coerceIn(1, MAX_PERIOD),
        kijunPeriod = kijunPeriod.coerceIn(1, MAX_PERIOD),
        senkouBPeriod = senkouBPeriod.coerceIn(1, MAX_PERIOD),
        displacement = displacement.coerceIn(0, MAX_PERIOD),
    )
}

@Immutable
data class ParabolicSarStudySettings(
    val accelerationStart: Double = 0.02,
    val accelerationStep: Double = 0.02,
    val accelerationMax: Double = 0.20,
) {
    fun sanitized(): ParabolicSarStudySettings {
        val start = finiteOr(accelerationStart, 0.02).coerceIn(0.001, 1.0)
        val step = finiteOr(accelerationStep, 0.02).coerceIn(0.001, 1.0)
        val max = finiteOr(accelerationMax, 0.20).coerceIn(start, 2.0)
        return copy(accelerationStart = start, accelerationStep = step, accelerationMax = max)
    }
}

@Immutable
data class MfiStudySettings(
    val period: Int = 14,
    val overbought: Double = 80.0,
    val oversold: Double = 20.0,
) {
    fun sanitized(): MfiStudySettings {
        val low = finiteOr(oversold, 20.0).coerceIn(1.0, 49.0)
        val high = finiteOr(overbought, 80.0).coerceIn(51.0, 99.0)
        return copy(period = period.coerceIn(1, MAX_PERIOD), overbought = high, oversold = low)
    }
}

private const val MAX_PERIOD = 500
private fun finiteOr(value: Double, fallback: Double): Double = if (value.isFinite()) value else fallback
