package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel
import com.foxtrader.app.domain.usecase.nascent.model.NascentMode
import com.foxtrader.app.domain.usecase.nascent.model.SignalConfidence

/**
 * Tuning surface for the Nascent engine.
 *
 * Every threshold here is isolated and named. None of them are baked into the
 * detectors, because the Nascent source does not publish numeric values and a
 * hidden magic constant would be indistinguishable from a documented rule.
 */
data class NascentConfig(
    val mode: NascentMode = NascentMode.BALANCED,
    /** null = derive the external timeframe from the chart timeframe. */
    val externalTimeframe: Timeframe? = null,
    val internalSwingLeftBars: Int = 2,
    val internalSwingRightBars: Int = 2,
    val externalSwingLeftBars: Int = 2,
    val externalSwingRightBars: Int = 2,
    val atrPeriod: Int = 14,
    /** How far back the historical reconstruction runs. */
    val historyDepthBars: Int = 4_000,
    /**
     * Size of the rolling window reported as "live analysis".
     *
     * History is always reconstructed in full; this only bounds the window the
     * UI describes as actively updating, and the diagnostics retained for it.
     */
    val liveWindowBars: Int = 100,
    /** Distance from an external level that still counts as an interaction. */
    val keyLevelToleranceAtrMultiple: Double = 0.75,
    /** Internal bars an external level stays actionable for. */
    val keyLevelMaxAgeBars: Int = 240,
    /**
     * Bars a key level stays "in play" after price last interacted with it.
     *
     * The external level marks where a *setup* is allowed to form, not where
     * its confirmation must land — a reversal into resistance confirms on a
     * close well below that resistance. Requiring the interaction to happen on
     * the confirmation bar itself would reject essentially every real setup.
     */
    val keyLevelInteractionWindowBars: Int = 40,
    /** Half-width of the 50% zone, so DP is a region rather than a float compare. */
    val equilibriumToleranceAtrMultiple: Double = 0.35,
    /** Bars allowed between a setup confirming and its entry confirmation. */
    val maxSetupToConfirmBars: Int = 6,
    /** Bars a pullback may take before a Direct Pullback stops being "direct". */
    val maxDirectPullbackBars: Int = 24,
    /** Lookback used when reconstructing internal reference ranges. */
    val internalRangeLookbackBars: Int = 60,
    /** Minimum body/range ratio for a candle to count as directional delivery. */
    val minDeliveryBodyFraction: Double = 0.45,
    /**
     * Net progress / distance travelled before a delivery leg counts as
     * "efficient" for EPA. Isolated here because Nascent publishes no number.
     */
    val minEpaEfficiency: Double = 0.30,
    /** Minimum leg size, in ATR, before a swing counts as a delivery leg. */
    val minLegAtrMultiple: Double = 0.75,
    /**
     * Promote one internal pivot per cycle to Decisional Structural Liquidity.
     *
     * Nascent names the concept but never defines its geometry, so the
     * promotion rule here is unverified. Off by default; research use only.
     */
    val enableDecisionalSlq: Boolean = false,
    val minConfidence: SignalConfidence = SignalConfidence.B,
    /** Stop buffer beyond the protected extreme, in ATR. */
    val stopBufferAtr: Double = 0.25,
    /**
     * Reward multiple used when no structural objective is available.
     *
     * Note this is an execution convenience, not a Nascent rule: TLQ is a
     * liquidity concept and is deliberately never treated as a take-profit.
     */
    val rewardRisk: Double = 2.0,
    val cooldownBars: Int = 3,
    val maxSignals: Int = 200,
    /** Emit per-bar diagnostics (developer/debug layers only). */
    val collectDiagnostics: Boolean = false,
) {
    init {
        require(internalSwingLeftBars >= 1 && internalSwingRightBars >= 1)
        require(externalSwingLeftBars >= 1 && externalSwingRightBars >= 1)
        require(atrPeriod >= 2)
        require(historyDepthBars >= 50)
        require(liveWindowBars >= 1)
        require(keyLevelToleranceAtrMultiple.isFinite() && keyLevelToleranceAtrMultiple >= 0.0)
        require(keyLevelMaxAgeBars >= 1)
        require(keyLevelInteractionWindowBars >= 1)
        require(equilibriumToleranceAtrMultiple.isFinite() && equilibriumToleranceAtrMultiple >= 0.0)
        require(maxSetupToConfirmBars >= 0)
        require(maxDirectPullbackBars >= 1)
        require(internalRangeLookbackBars >= 5)
        require(minDeliveryBodyFraction in 0.0..1.0)
        require(minEpaEfficiency in 0.0..1.0)
        require(minLegAtrMultiple.isFinite() && minLegAtrMultiple >= 0.0)
        require(stopBufferAtr.isFinite() && stopBufferAtr >= 0.0)
        require(rewardRisk.isFinite() && rewardRisk > 0.0)
        require(cooldownBars >= 0)
        require(maxSignals >= 1)
    }

    /**
     * Minimum evidence a rule needs before this mode will act on it.
     *
     * UNRESOLVED sits below INFERRED_V1 deliberately: an inferred geometry is a
     * reconstruction of something the source describes, whereas an unresolved
     * one is a name with no published definition at all.
     */
    fun permits(evidence: EvidenceLevel): Boolean = when (mode) {
        NascentMode.SOURCE_STRICT ->
            evidence == EvidenceLevel.NASCENT_VERIFIED || evidence == EvidenceLevel.CORROBORATED
        NascentMode.BALANCED ->
            evidence != EvidenceLevel.RESEARCH_ONLY && evidence != EvidenceLevel.UNRESOLVED
        NascentMode.RESEARCH -> true
    }

    companion object {
        /**
         * External timeframe for a given internal (chart) timeframe.
         *
         * Every pair below is the inverse of a mapping the Nascent material
         * lists explicitly, except M30, which the source never mentions and
         * which is therefore an inferred convenience.
         */
        fun externalFor(internal: Timeframe): Timeframe? = when (internal) {
            Timeframe.M1 -> Timeframe.M15
            Timeframe.M5 -> Timeframe.H1
            Timeframe.M15 -> Timeframe.H4
            Timeframe.M30 -> Timeframe.H4
            Timeframe.H1 -> Timeframe.H4
            Timeframe.H4 -> Timeframe.D1
            Timeframe.D1 -> Timeframe.W1
            Timeframe.W1 -> Timeframe.MN
            // Nothing sits above the monthly chart, so there is no external
            // structure to anchor to and the engine must stay silent.
            Timeframe.MN -> null
        }
    }
}
