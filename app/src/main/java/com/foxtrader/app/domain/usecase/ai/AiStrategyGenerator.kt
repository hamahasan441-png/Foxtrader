package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsEngine
import com.foxtrader.app.domain.usecase.backtest.BacktestAnalyticsReport
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.strategies.StrategyTester
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rule-based AI Strategy Generator.
 *
 * Analyzes market data to detect the prevailing regime (trending, ranging, volatile, mixed),
 * runs all strategies through the backtest engine, scores each strategy for regime alignment,
 * and produces a ranked report with recommendations backed by real performance data.
 *
 * Pure domain logic: no Android dependencies, no external network calls.
 */
@Singleton
class AiStrategyGenerator @Inject constructor(
    private val strategyTester: StrategyTester,
    private val analyticsEngine: BacktestAnalyticsEngine,
) {

    /**
     * Generate an AI strategy report for the given candle data.
     */
    fun generate(
        candles: List<Candle>,
        symbol: String,
        timeframe: Timeframe = Timeframe.H1,
    ): AiStrategyGeneratorReport {
        if (candles.size < MINIMUM_BARS) {
            return emptyReport(symbol, timeframe)
        }

        val regimeAnalysis = detectRegime(candles)

        val testReport = strategyTester.testAll(
            candles = candles,
            symbol = symbol,
            timeframe = timeframe,
        )

        val generatedStrategies = testReport.results.map { testResult ->
            val analyticsReport = analyticsEngine.analyze(testResult.backtest)
            val alignmentScore = calculateAlignmentScore(
                strategyType = testResult.definition.type,
                regime = regimeAnalysis.regime,
            )
            val compositeScore = testResult.score * (1.0 + alignmentScore / 100.0)
            val recommendation = buildRecommendation(
                strategyType = testResult.definition.type,
                regime = regimeAnalysis.regime,
                alignmentScore = alignmentScore,
                analyticsReport = analyticsReport,
            )

            GeneratedStrategy(
                strategyType = testResult.definition.type,
                backtestResult = testResult.backtest,
                analyticsReport = analyticsReport,
                regimeAlignmentScore = alignmentScore,
                recommendation = recommendation,
                compositeScore = compositeScore,
            )
        }.sortedByDescending { it.compositeScore }

        val topRecommendation = generatedStrategies.firstOrNull()
        val narrative = buildNarrative(regimeAnalysis, generatedStrategies, topRecommendation)

        return AiStrategyGeneratorReport(
            symbol = symbol,
            timeframe = timeframe,
            regimeAnalysis = regimeAnalysis,
            generatedStrategies = generatedStrategies,
            topRecommendation = topRecommendation,
            narrative = narrative,
        )
    }

    /**
     * Detect market regime from candle data using ADX, ATR percentile,
     * and mean-reversion tendency.
     */
    internal fun detectRegime(candles: List<Candle>): RegimeAnalysis {
        val adxResult = TechnicalIndicators.calculateADX(candles)
        val atrValues = TechnicalIndicators.calculateATR(candles)

        // Use the last valid ADX value as the trend strength indicator
        val lastAdx = adxResult.adx.lastOrNull { it > 0.0 } ?: 0.0

        // Calculate ATR percentile: compare current ATR to 50-bar average
        val volatilityPercentile = calculateAtrPercentile(atrValues)

        // Mean-reversion score: how often price reverts to mean within a lookback
        val meanReversionScore = calculateMeanReversionScore(candles)

        val regime = classifyRegime(lastAdx, volatilityPercentile, meanReversionScore)
        val suitableStrategies = mapRegimeToStrategies(regime)

        return RegimeAnalysis(
            regime = regime,
            trendStrength = lastAdx,
            volatilityPercentile = volatilityPercentile,
            meanReversionScore = meanReversionScore,
            suitableStrategies = suitableStrategies,
        )
    }

    private fun classifyRegime(
        adx: Double,
        volatilityPercentile: Double,
        meanReversionScore: Double,
    ): MarketRegime = when {
        adx > 25.0 -> MarketRegime.TRENDING
        adx < 20.0 && volatilityPercentile <= 75.0 -> MarketRegime.RANGING
        volatilityPercentile > 75.0 -> MarketRegime.VOLATILE
        else -> MarketRegime.MIXED
    }

    private fun mapRegimeToStrategies(regime: MarketRegime): List<StrategyType> = when (regime) {
        MarketRegime.TRENDING -> listOf(
            StrategyType.TREND_FOLLOWING,
            StrategyType.ICHIMOKU,
            StrategyType.SMART_MONEY,
            StrategyType.LIT,
            StrategyType.LITX,
            StrategyType.CONFLUENCE,
        )
        MarketRegime.RANGING -> listOf(
            StrategyType.MEAN_REVERSION,
            StrategyType.PATTERN,
            StrategyType.SMART_MONEY,
            StrategyType.LIT,
            StrategyType.LITX,
            StrategyType.CONFLUENCE,
        )
        MarketRegime.VOLATILE -> listOf(
            StrategyType.BREAKOUT,
            StrategyType.SMART_MONEY,
            StrategyType.LIT,
            StrategyType.LITX,
            StrategyType.CONFLUENCE,
        )
        MarketRegime.MIXED -> StrategyType.entries.toList()
    }

    private fun calculateAlignmentScore(
        strategyType: StrategyType,
        regime: MarketRegime,
    ): Int {
        var score = 0
        when (strategyType) {
            StrategyType.TREND_FOLLOWING -> {
                if (regime == MarketRegime.TRENDING) score += 30
            }
            StrategyType.MEAN_REVERSION -> {
                if (regime == MarketRegime.RANGING) score += 30
            }
            StrategyType.BREAKOUT -> {
                if (regime == MarketRegime.VOLATILE) score += 20
            }
            StrategyType.ICHIMOKU -> {
                if (regime == MarketRegime.TRENDING) score += 20
            }
            StrategyType.PATTERN -> {
                if (regime == MarketRegime.RANGING) score += 15
            }
            StrategyType.SMART_MONEY,
            StrategyType.LIT,
            StrategyType.LITX,
            StrategyType.CONFLUENCE -> {
                score += 10
            }
        }
        return score
    }

    private fun calculateAtrPercentile(atrValues: DoubleArray): Double {
        if (atrValues.isEmpty()) return 50.0
        val lookback = ATR_PERCENTILE_LOOKBACK.coerceAtMost(atrValues.size)
        val recentAtr = atrValues.takeLast(lookback).filter { it > 0.0 }
        if (recentAtr.isEmpty()) return 50.0

        val currentAtr = recentAtr.last()
        val sorted = recentAtr.sorted()
        val rank = sorted.indexOfFirst { it >= currentAtr }
        return if (rank < 0) 100.0 else (rank.toDouble() / sorted.size) * 100.0
    }

    private fun calculateMeanReversionScore(candles: List<Candle>): Double {
        if (candles.size < MEAN_REVERSION_LOOKBACK) return 50.0
        val recent = candles.takeLast(MEAN_REVERSION_LOOKBACK)
        val closes = recent.map { it.close }
        val mean = closes.average()
        var crossings = 0
        for (i in 1 until closes.size) {
            val prev = closes[i - 1] - mean
            val curr = closes[i] - mean
            if (prev * curr < 0) crossings++
        }
        // Normalize: max possible crossings = size - 1
        return (crossings.toDouble() / (closes.size - 1)) * 100.0
    }

    private fun buildRecommendation(
        strategyType: StrategyType,
        regime: MarketRegime,
        alignmentScore: Int,
        analyticsReport: BacktestAnalyticsReport,
    ): String {
        val walkForwardStatus = analyticsReport.walkForward?.let {
            "Walk-forward stability: ${it.stabilityScore}/100 (${it.verdict})"
        } ?: "Insufficient trades for walk-forward validation."

        val regimeMatch = if (alignmentScore >= 20) "Strong" else if (alignmentScore >= 10) "Moderate" else "Weak"

        return "$regimeMatch regime alignment for ${strategyType.label} in $regime market. $walkForwardStatus"
    }

    private fun buildNarrative(
        regimeAnalysis: RegimeAnalysis,
        strategies: List<GeneratedStrategy>,
        top: GeneratedStrategy?,
    ): String = buildString {
        append("Market regime detected: ${regimeAnalysis.regime} ")
        append("(ADX=${String.format("%.1f", regimeAnalysis.trendStrength)}, ")
        append("Volatility=${String.format("%.0f", regimeAnalysis.volatilityPercentile)}th percentile, ")
        append("Mean-reversion=${String.format("%.0f", regimeAnalysis.meanReversionScore)}%). ")

        if (top != null) {
            append("Top recommendation: ${top.strategyType.label} ")
            append("with regime alignment ${top.regimeAlignmentScore}/100. ")
            val metrics = top.backtestResult.metrics
            append("Backtest: ${metrics.totalTrades} trades, ")
            append("${String.format("%.1f", metrics.winRate * 100)}% win rate, ")
            append("PF=${String.format("%.2f", metrics.profitFactor)}.")
        } else {
            append("No strategies generated sufficient trade data for recommendations.")
        }

        if (strategies.size > 1) {
            append(" ${strategies.size} strategies evaluated and ranked by composite score.")
        }
    }

    private fun emptyReport(symbol: String, timeframe: Timeframe) = AiStrategyGeneratorReport(
        symbol = symbol,
        timeframe = timeframe,
        regimeAnalysis = RegimeAnalysis(
            regime = MarketRegime.MIXED,
            trendStrength = 0.0,
            volatilityPercentile = 0.0,
            meanReversionScore = 0.0,
            suitableStrategies = emptyList(),
        ),
        generatedStrategies = emptyList(),
        topRecommendation = null,
        narrative = "Insufficient data: at least $MINIMUM_BARS bars required for analysis.",
    )

    private companion object {
        const val MINIMUM_BARS = 50
        const val ATR_PERCENTILE_LOOKBACK = 50
        const val MEAN_REVERSION_LOOKBACK = 30
    }
}

// =============================================================================
// DATA CLASSES
// =============================================================================

/**
 * Market regime classification based on statistical properties of price data.
 */
enum class MarketRegime {
    TRENDING,
    RANGING,
    VOLATILE,
    MIXED,
}

/**
 * Analysis of the detected market regime with supporting statistics.
 */
data class RegimeAnalysis(
    val regime: MarketRegime,
    val trendStrength: Double,
    val volatilityPercentile: Double,
    val meanReversionScore: Double,
    val suitableStrategies: List<StrategyType>,
)

/**
 * A strategy recommendation produced by the AI generator, including
 * backtest performance data and regime alignment scoring.
 */
data class GeneratedStrategy(
    val strategyType: StrategyType,
    val backtestResult: BacktestResult,
    val analyticsReport: BacktestAnalyticsReport,
    val regimeAlignmentScore: Int,
    val recommendation: String,
    val compositeScore: Double = 0.0,
)

/**
 * Complete report from the AI Strategy Generator including regime analysis,
 * ranked strategy recommendations, and a human-readable narrative.
 */
data class AiStrategyGeneratorReport(
    val symbol: String,
    val timeframe: Timeframe,
    val regimeAnalysis: RegimeAnalysis,
    val generatedStrategies: List<GeneratedStrategy>,
    val topRecommendation: GeneratedStrategy?,
    val narrative: String,
)
