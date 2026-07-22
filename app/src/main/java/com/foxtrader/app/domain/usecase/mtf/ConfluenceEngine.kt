package com.foxtrader.app.domain.usecase.mtf

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import kotlin.math.abs

/**
 * Multi-Timeframe Confluence Engine — evaluates alignment across timeframes.
 *
 * Checks multiple timeframes simultaneously and produces a confluence score
 * indicating how strongly aligned the market is in one direction.
 *
 * A high confluence score = stronger trade setup (multiple timeframes agree).
 * Used to filter low-probability setups and highlight institutional-grade entries.
 *
 * Pure domain logic — data fetching handled by caller/repository.
 */
class ConfluenceEngine @Inject constructor(
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
) {

    /**
     * A single timeframe's analysis result.
     */
    data class TimeframeAnalysis(
        val timeframe: Timeframe,
        val bias: Bias,
        val trendStrength: Double,  // 0-100 ADX
        val emaAlignment: Boolean,  // EMA20 > EMA50 for bullish
        val rsiZone: RsiZone,
        val structureIntact: Boolean,
    )

    enum class RsiZone { OVERSOLD, NEUTRAL, OVERBOUGHT }

    /**
     * Multi-timeframe confluence result.
     */
    data class ConfluenceResult(
        val analyses: List<TimeframeAnalysis>,
        val overallBias: Bias,
        val confluenceScore: Int,    // 0-100 (100 = all TFs perfectly aligned)
        val recommendation: String,
        val alignedTimeframes: Int,
        val totalTimeframes: Int,
    )

    /**
     * Analyze confluence across multiple timeframes.
     *
     * @param dataByTimeframe Map of timeframe → candle data
     * @param primaryDirection The direction being evaluated (BULLISH or BEARISH)
     */
    fun analyze(
        dataByTimeframe: Map<Timeframe, List<Candle>>,
        primaryDirection: Direction = Direction.BULLISH,
    ): ConfluenceResult {
        val analyses = mutableListOf<TimeframeAnalysis>()

        for ((tf, candles) in dataByTimeframe) {
            if (candles.size < 50) continue
            analyses.add(analyzeTimeframe(tf, candles))
        }

        if (analyses.isEmpty()) {
            return ConfluenceResult(
                analyses = emptyList(),
                overallBias = Bias.NEUTRAL,
                confluenceScore = 0,
                recommendation = "Insufficient data",
                alignedTimeframes = 0,
                totalTimeframes = 0,
            )
        }

        // Calculate alignment
        val targetBias = if (primaryDirection == Direction.BULLISH) Bias.BULLISH else Bias.BEARISH
        val aligned = analyses.count { it.bias == targetBias }
        val total = analyses.size
        val confluenceScore = ((aligned.toDouble() / total) * 100).toInt()

        // Determine overall bias (majority vote weighted by timeframe)
        val bullishCount = analyses.count { it.bias == Bias.BULLISH }
        val bearishCount = analyses.count { it.bias == Bias.BEARISH }
        val overallBias = when {
            bullishCount > bearishCount -> Bias.BULLISH
            bearishCount > bullishCount -> Bias.BEARISH
            else -> Bias.NEUTRAL
        }

        val recommendation = when {
            confluenceScore >= 80 -> "Strong setup — all timeframes aligned"
            confluenceScore >= 60 -> "Good setup — majority alignment"
            confluenceScore >= 40 -> "Mixed signals — proceed with caution"
            else -> "Weak setup — wait for better alignment"
        }

        return ConfluenceResult(
            analyses = analyses,
            overallBias = overallBias,
            confluenceScore = confluenceScore,
            recommendation = recommendation,
            alignedTimeframes = aligned,
            totalTimeframes = total,
        )
    }

    // ========================================================================
    // PRIVATE
    // ========================================================================

    private fun analyzeTimeframe(tf: Timeframe, candles: List<Candle>): TimeframeAnalysis {
        val structure = analyzeStructure(candles)
        val ema20 = TechnicalIndicators.calculateEMA(candles, 20)
        val ema50 = TechnicalIndicators.calculateEMA(candles, 50)
        val adx = TechnicalIndicators.calculateADX(candles)
        val rsi = TechnicalIndicators.calculateRSI(candles)

        val last = candles.lastIndex
        val emaAligned = ema20[last] > ema50[last] // Bullish alignment
        val trendStrength = adx.adx[last].coerceIn(0.0, 100.0)
        val rsiValue = rsi[last]

        val rsiZone = when {
            rsiValue > 70 -> RsiZone.OVERBOUGHT
            rsiValue < 30 -> RsiZone.OVERSOLD
            else -> RsiZone.NEUTRAL
        }

        return TimeframeAnalysis(
            timeframe = tf,
            bias = structure.bias,
            trendStrength = trendStrength,
            emaAlignment = emaAligned,
            rsiZone = rsiZone,
            structureIntact = structure.breaks.lastOrNull()?.confirmed == true,
        )
    }
}
