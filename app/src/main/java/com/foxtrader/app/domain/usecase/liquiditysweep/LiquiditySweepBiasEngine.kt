package com.foxtrader.app.domain.usecase.liquiditysweep

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.liquiditysweep.model.SweepBias

/**
 * Step 1 — the higher-timeframe directional read.
 *
 * The model only takes a sweep in the direction the higher timeframe is already
 * going: a sweep of resting liquidity is a trap being sprung, and it is far
 * more often a genuine reversal when it happens against a level the higher
 * timeframe is trading away from. Without this filter the same geometry fires
 * in both directions on every range boundary.
 *
 * Bias is recomputed per execution bar from the higher-timeframe bars that had
 * closed by then, so it changes only when the higher timeframe itself changes.
 */
class LiquiditySweepBiasEngine(
    private val analyzeStructure: AnalyzeMarketStructureUseCase = AnalyzeMarketStructureUseCase(),
) {

    /**
     * Bias usable at [executionIndex], or null when the higher timeframe has
     * not yet closed enough bars to have a structure of its own.
     */
    fun biasAt(
        executionIndex: Int,
        higher: MultiTimeframeSeries,
        mid: MultiTimeframeSeries,
        config: LiquiditySweepConfig,
    ): SweepBias? {
        if (config.biasMode == BiasMode.NONE) {
            return SweepBias(
                bias = Bias.NEUTRAL,
                higherTimeframe = higher.timeframe,
                midTimeframe = mid.timeframe,
                knownFromIndex = executionIndex,
                reason = "Bias filter disabled; both sides eligible.",
            )
        }

        val higherBars = higher.closedPrefix(executionIndex)
        val minimum = config.htfSwingLeft + config.htfSwingRight + MIN_STRUCTURE_BARS
        if (higherBars.size < minimum) return null

        val higherStructure = analyzeStructure(
            candles = higherBars,
            leftBars = config.htfSwingLeft,
            rightBars = config.htfSwingRight,
        )
        val higherBias = higherStructure.bias
        if (higherBias == Bias.NEUTRAL) return null

        if (config.biasMode == BiasMode.HTF_AND_MTF_AGREE) {
            val midBars = mid.closedPrefix(executionIndex)
            if (midBars.size < config.mtfSwingLeft + config.mtfSwingRight + MIN_STRUCTURE_BARS) return null
            val midBias = analyzeStructure(
                candles = midBars,
                leftBars = config.mtfSwingLeft,
                rightBars = config.mtfSwingRight,
            ).bias
            if (midBias != higherBias) return null

            return SweepBias(
                bias = higherBias,
                higherTimeframe = higher.timeframe,
                midTimeframe = mid.timeframe,
                knownFromIndex = executionIndex,
                reason = "${higher.timeframe.label} and ${mid.timeframe.label} both ${higherBias.name.lowercase()}",
            )
        }

        return SweepBias(
            bias = higherBias,
            higherTimeframe = higher.timeframe,
            midTimeframe = mid.timeframe,
            knownFromIndex = executionIndex,
            reason = "${higher.timeframe.label} structure ${higherBias.name.lowercase()}",
        )
    }

    private companion object {
        /** Bars beyond the swing window before a structure call means anything. */
        const val MIN_STRUCTURE_BARS = 6
    }
}
