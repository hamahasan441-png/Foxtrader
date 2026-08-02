package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.FlipZone
import com.foxtrader.app.domain.model.tradepro.MtfBias
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-timeframe TRADEPRO engine.
 *
 * Implements the framework's core MTF principle: **HTF defines the bias, LTF provides the entry.**
 * It computes the Flip Zone and structural bias on each available higher timeframe, then validates
 * that the LTF setup aligns before letting it through.
 *
 * Usage: call [computeHtfBias] with the HTF candle map from [MtfContextProvider], then pass the
 * result to [validateAlignment] to check an LTF setup before promoting it to EXECUTE.
 */
@Singleton
class MtfTradeProEngine @Inject constructor(
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val flipZoneEngine: FlipZoneEngine,
) {

    /**
     * Compute the aggregate HTF bias from a map of higher-timeframe candles.
     *
     * @param htfCandles map of timeframe → candle list (from [MtfContextProvider.getHtfContext]).
     * @return The combined HTF bias. If multiple HTFs agree, [MtfBias.alignedCount] > 1.
     */
    fun computeHtfBias(
        htfCandles: Map<Timeframe, List<Candle>>,
        config: TradeProConfig = TradeProConfig(),
    ): MtfBias {
        if (htfCandles.isEmpty()) return MtfBias.neutral(Timeframe.D1)

        var bullish = 0
        var bearish = 0
        var primaryFlipZone: FlipZone? = null
        var primaryTf: Timeframe = Timeframe.D1

        // Process HTFs from closest to furthest (the map is ordered by MtfContextProvider).
        for ((tf, candles) in htfCandles) {
            if (candles.size < MIN_BARS) continue
            val structure = analyzeStructure(candles, config.swingLookback, config.swingLookback)
            val fz = flipZoneEngine.compute(structure)

            when (structure.bias) {
                Bias.BULLISH -> bullish++
                Bias.BEARISH -> bearish++
                Bias.NEUTRAL -> Unit
            }

            // The closest HTF's Flip Zone is the primary one (most relevant to LTF).
            if (primaryFlipZone == null && fz != null) {
                primaryFlipZone = fz
                primaryTf = tf
            }
        }

        val total = bullish + bearish
        val bias = when {
            bullish > bearish -> Bias.BULLISH
            bearish > bullish -> Bias.BEARISH
            else -> Bias.NEUTRAL
        }
        val aligned = when (bias) {
            Bias.BULLISH -> bullish
            Bias.BEARISH -> bearish
            Bias.NEUTRAL -> 0
        }

        return MtfBias(
            timeframe = primaryTf,
            bias = bias,
            flipZonePrice = primaryFlipZone?.price,
            alignedCount = aligned,
            totalChecked = htfCandles.size,
        )
    }

    /**
     * Validates that an LTF [TradeProAnalysis] aligns with the HTF bias.
     *
     * Returns the analysis unchanged if aligned (or if HTF is NEUTRAL — no override).
     * If misaligned, demotes the setup to informational (clears the executable flag via narrative).
     */
    fun validateAlignment(
        ltfAnalysis: TradeProAnalysis,
        htfBias: MtfBias,
    ): TradeProAnalysis {
        if (!htfBias.isDefined) return ltfAnalysis // no HTF bias = no filter
        val setup = ltfAnalysis.setup ?: return ltfAnalysis

        val aligned = when (htfBias.bias) {
            Bias.BULLISH -> setup.direction == Direction.BULLISH
            Bias.BEARISH -> setup.direction == Direction.BEARISH
            Bias.NEUTRAL -> true
        }

        return if (aligned) {
            // Boost confidence by alignment strength and annotate.
            val boosted = setup.copy(
                confidence = (setup.confidence + (htfBias.alignmentStrength * 10).toInt()).coerceAtMost(100),
                confluences = setup.confluences + "HTF_ALIGNED_${htfBias.timeframe.label}",
                note = setup.note + " HTF ${htfBias.timeframe.label} confirms ${htfBias.bias} bias" +
                    (htfBias.flipZonePrice?.let { " (Flip Zone ${"%.2f".format(it)})" } ?: "") + ".",
            )
            ltfAnalysis.copy(setup = boosted)
        } else {
            // Misaligned: keep the analysis informational but don't execute.
            ltfAnalysis.copy(
                setup = setup.copy(
                    stage = com.foxtrader.app.domain.model.tradepro.SetupStage.LEVEL,
                    note = setup.note + " BLOCKED: LTF ${setup.direction} conflicts with HTF ${htfBias.bias} bias.",
                ),
                narrative = ltfAnalysis.narrative +
                    " HTF ${htfBias.timeframe.label} is ${htfBias.bias} — opposing LTF direction. Standing aside.",
            )
        }
    }

    private companion object {
        const val MIN_BARS = 30
    }
}
