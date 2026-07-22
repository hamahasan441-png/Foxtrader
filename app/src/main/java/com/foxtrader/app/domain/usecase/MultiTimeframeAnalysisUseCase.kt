package com.foxtrader.app.domain.usecase

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmaAlignment
import com.foxtrader.app.domain.model.MTFResult
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.TimeframeBias
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-Timeframe Analysis Engine.
 *
 * Simultaneously analyzes all provided timeframes (MN → M1).
 * Determines HTF Bias by weighting higher timeframes more heavily.
 * Uses market structure (BOS/CHOCH) + EMA alignment + ADX trend strength.
 *
 * No look-ahead bias — each TF uses only its own confirmed data.
 */
class MultiTimeframeAnalysisUseCase @Inject constructor(
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
) {

    companion object {
        /** Timeframes considered "higher" for HTF bias derivation */
        val HTF_TIMEFRAMES = listOf(Timeframe.MN, Timeframe.W1, Timeframe.D1, Timeframe.H4)

        /** Weight per timeframe (higher TF = more weight) */
        val WEIGHTS: Map<Timeframe, Double> = mapOf(
            Timeframe.MN to 5.0,
            Timeframe.W1 to 4.0,
            Timeframe.D1 to 3.0,
            Timeframe.H4 to 2.5,
            Timeframe.H1 to 2.0,
            Timeframe.M30 to 1.8,
            Timeframe.M15 to 1.5,
            Timeframe.M5 to 1.0,
            Timeframe.M1 to 0.5,
        )

        private const val MIN_BARS_REQUIRED = 50
    }

    /**
     * Analyze all timeframes from the provided data map.
     * @param symbol Instrument identifier (e.g. "EURUSD")
     * @param dataMap Map of Timeframe → List<Candle> (provide whatever TFs are available)
     */
    operator fun invoke(symbol: String, dataMap: Map<Timeframe, List<Candle>>): MTFResult {
        val biases = mutableMapOf<Timeframe, TimeframeBias>()

        for ((tf, candles) in dataMap) {
            if (candles.size < MIN_BARS_REQUIRED) continue

            val structure = analyzeStructure(candles)
            val last = candles.lastIndex

            // EMA alignment: 20 > 50 > 200 = bullish, reversed = bearish
            val ema20 = TechnicalIndicators.calculateEMA(candles, 20)
            val ema50 = TechnicalIndicators.calculateEMA(candles, 50)
            val ema200 = if (candles.size >= 200) TechnicalIndicators.calculateEMA(candles, 200) else ema50

            val bullEMA = ema20[last] > ema50[last] && ema50[last] > ema200[last]
            val bearEMA = ema20[last] < ema50[last] && ema50[last] < ema200[last]

            // ADX strength
            val adxResult = TechnicalIndicators.calculateADX(candles)
            val adxVal = adxResult.adx[last]

            // Derive per-TF bias
            val structureBias = structure.bias
            val emaBias: Bias = when {
                bullEMA -> Bias.BULLISH
                bearEMA -> Bias.BEARISH
                else -> Bias.NEUTRAL
            }
            val bias = blendBias(structureBias, emaBias, adxVal)

            // Confidence scoring
            val lastBreak = structure.breaks.lastOrNull()
            var confidence = 40
            if (structureBias == emaBias && structureBias != Bias.NEUTRAL) confidence += 25
            if (adxVal > 25) confidence += 15
            if (lastBreak?.confirmed == true) confidence += 10
            confidence = min(95, confidence)

            biases[tf] = TimeframeBias(
                timeframe = tf,
                bias = bias,
                confidence = confidence,
                lastBreakType = lastBreak?.type,
                lastBreakDirection = lastBreak?.direction,
                emaAlignment = when {
                    bullEMA -> EmaAlignment.BULLISH
                    bearEMA -> EmaAlignment.BEARISH
                    else -> EmaAlignment.MIXED
                },
                adxStrength = adxVal,
            )
        }

        // HTF Bias from higher timeframes
        val (htfBias, htfConfidence) = calculateHTFBias(biases)

        // Alignment / conflict
        val aligned = mutableListOf<Timeframe>()
        val conflicting = mutableListOf<Timeframe>()
        if (htfBias != Bias.NEUTRAL) {
            for ((tf, tfBias) in biases) {
                when {
                    tfBias.bias == htfBias -> aligned += tf
                    tfBias.bias != Bias.NEUTRAL -> conflicting += tf
                }
            }
        }

        val narrative = buildNarrative(htfBias, htfConfidence, aligned, conflicting, biases)

        return MTFResult(
            symbol = symbol,
            biases = biases,
            htfBias = htfBias,
            htfConfidence = htfConfidence,
            alignedTimeframes = aligned,
            conflictingTimeframes = conflicting,
            narrative = narrative,
            timestamp = System.currentTimeMillis(),
        )
    }

    // ========================================================================
    // PRIVATE
    // ========================================================================

    private fun calculateHTFBias(biases: Map<Timeframe, TimeframeBias>): Pair<Bias, Int> {
        var bullishScore = 0.0
        var bearishScore = 0.0
        var totalWeight = 0.0

        for (tf in HTF_TIMEFRAMES) {
            val tfBias = biases[tf] ?: continue
            val weight = WEIGHTS[tf] ?: 1.0
            totalWeight += weight

            val contribution = (tfBias.confidence / 100.0) * weight
            when (tfBias.bias) {
                Bias.BULLISH -> bullishScore += contribution
                Bias.BEARISH -> bearishScore += contribution
                Bias.NEUTRAL -> { /* no contribution */ }
            }
        }

        if (totalWeight == 0.0) return Bias.NEUTRAL to 30

        val normalizedBull = (bullishScore / totalWeight) * 100.0
        val normalizedBear = (bearishScore / totalWeight) * 100.0

        val htfBias: Bias
        val htfConfidence: Int

        when {
            normalizedBull > normalizedBear * 1.2 -> {
                htfBias = Bias.BULLISH
                htfConfidence = normalizedBull.roundToInt()
            }
            normalizedBear > normalizedBull * 1.2 -> {
                htfBias = Bias.BEARISH
                htfConfidence = normalizedBear.roundToInt()
            }
            else -> {
                htfBias = Bias.NEUTRAL
                htfConfidence = maxOf(normalizedBull, normalizedBear).roundToInt()
            }
        }

        return htfBias to min(95, htfConfidence)
    }

    private fun blendBias(structureBias: Bias, emaBias: Bias, adxVal: Double): Bias {
        // Both agree → strong signal
        if (structureBias == emaBias && structureBias != Bias.NEUTRAL) return structureBias
        // Structure takes priority (60% weight)
        if (structureBias != Bias.NEUTRAL) return structureBias
        // EMA as fallback only in trending market
        if (emaBias != Bias.NEUTRAL && adxVal > 20) return emaBias
        return Bias.NEUTRAL
    }

    private fun buildNarrative(
        htfBias: Bias,
        confidence: Int,
        aligned: List<Timeframe>,
        conflicting: List<Timeframe>,
        biases: Map<Timeframe, TimeframeBias>,
    ): String = buildString {
        append("HTF Bias: $htfBias ($confidence%).")
        if (aligned.isNotEmpty()) {
            append(" Aligned (${aligned.size}): ${aligned.joinToString { it.label }}.")
        }
        if (conflicting.isNotEmpty()) {
            append(" Conflicting (${conflicting.size}): ${conflicting.joinToString { it.label }}.")
        }
        // Top-down flow
        val topDown = listOf(Timeframe.MN, Timeframe.W1, Timeframe.D1)
            .mapNotNull { tf -> biases[tf]?.let { "${tf.label}=${it.bias}" } }
        if (topDown.isNotEmpty()) {
            append(" Top-down: ${topDown.joinToString(" → ")}.")
        }
    }
}
