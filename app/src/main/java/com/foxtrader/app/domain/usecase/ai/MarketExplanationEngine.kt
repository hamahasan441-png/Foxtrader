package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import javax.inject.Inject
import kotlin.math.abs

/**
 * Deterministic Market Explanation Engine.
 *
 * Explains the current state of a symbol independent of a trade signal. This is
 * the Section 7.8 mentor/scanner narrative surface: HTF bias, value zone,
 * liquidity, inefficiencies, trend/volatility regime, and what price is likely
 * seeking next. It never approves trades; it prepares context for humans and
 * downstream deterministic gates.
 */
class MarketExplanationEngine @Inject constructor(
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val smcDetector: SmcDetector,
) {

    fun explain(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        htfCandles: Map<Timeframe, List<Candle>> = emptyMap(),
    ): MarketExplanation {
        if (candles.size < MIN_BARS) {
            return MarketExplanation.insufficient(symbol, timeframe, candles.size)
        }

        val last = candles.lastIndex
        val price = candles[last].close
        val structure = analyzeStructure(candles)
        val htfSummary = summarizeHtf(htfCandles)
        val valueZone = valueZone(candles, price)
        val trendRegime = trendRegime(candles)
        val volatilityRegime = volatilityRegime(candles)
        val liquidityNarrative = liquidityNarrative(candles, price)
        val inefficiencyNarrative = inefficiencyNarrative(candles, price)
        val keyLevels = keyLevels(candles, price)
        val directionalContext = directionalContext(structure.bias, htfSummary.overallBias, trendRegime)
        val nextObjective = nextObjective(
            price = price,
            valueZone = valueZone,
            structureBias = structure.bias,
            liquidity = liquidityNarrative,
            inefficiency = inefficiencyNarrative,
        )
        val warnings = warnings(valueZone, volatilityRegime, htfSummary, structure.bias)
        val summary = "$symbol ${timeframe.label}: ${directionalContext.label}; " +
            "price is in ${valueZone.label.lowercase()} with ${trendRegime.label.lowercase()} and " +
            "${volatilityRegime.label.lowercase()}. $nextObjective"

        return MarketExplanation(
            symbol = symbol,
            timeframe = timeframe,
            bias = structure.bias,
            htfBias = htfSummary.overallBias,
            directionalContext = directionalContext,
            valueZone = valueZone,
            trendRegime = trendRegime,
            volatilityRegime = volatilityRegime,
            structureNarrative = structureNarrative(structure.bias, structure.breaks.lastOrNull()?.type?.name),
            liquidityNarrative = liquidityNarrative.text,
            inefficiencyNarrative = inefficiencyNarrative.text,
            nextObjective = nextObjective,
            keyLevels = keyLevels,
            warnings = warnings,
            htfAlignmentScore = htfSummary.alignmentScore,
            summary = summary,
            mentorNotes = mentorNotes(directionalContext, valueZone, warnings),
            tags = tags(directionalContext, valueZone, trendRegime, volatilityRegime, warnings),
        )
    }

    private fun summarizeHtf(htfCandles: Map<Timeframe, List<Candle>>): HtfSummary {
        val analyses = htfCandles
            .filterValues { it.size >= MIN_BARS }
            .mapValues { (_, candles) -> analyzeStructure(candles).bias }
        if (analyses.isEmpty()) return HtfSummary(Bias.NEUTRAL, 0)
        val bull = analyses.values.count { it == Bias.BULLISH }
        val bear = analyses.values.count { it == Bias.BEARISH }
        val bias = when {
            bull > bear -> Bias.BULLISH
            bear > bull -> Bias.BEARISH
            else -> Bias.NEUTRAL
        }
        val aligned = analyses.values.count { it == bias && bias != Bias.NEUTRAL }
        val score = if (bias == Bias.NEUTRAL) 0 else ((aligned.toDouble() / analyses.size) * 100.0).toInt()
        return HtfSummary(bias, score)
    }

    private fun valueZone(candles: List<Candle>, price: Double): MarketValueZone {
        val range = candles.takeLast(VALUE_LOOKBACK)
        val high = range.maxOf { it.high }
        val low = range.minOf { it.low }
        val span = (high - low).coerceAtLeast(1e-9)
        val position = ((price - low) / span).coerceIn(0.0, 1.0)
        return when {
            position >= 0.66 -> MarketValueZone.PREMIUM
            position <= 0.34 -> MarketValueZone.DISCOUNT
            else -> MarketValueZone.EQUILIBRIUM
        }
    }

    private fun trendRegime(candles: List<Candle>): MarketTrendRegime {
        val last = candles.lastIndex
        val ema20 = TechnicalIndicators.calculateEMA(candles, 20)
        val ema50 = TechnicalIndicators.calculateEMA(candles, 50)
        val adx = TechnicalIndicators.calculateADX(candles).adx[last]
        return when {
            adx >= 30.0 && ema20[last] > ema50[last] -> MarketTrendRegime.IMPULSIVE_BULLISH
            adx >= 30.0 && ema20[last] < ema50[last] -> MarketTrendRegime.IMPULSIVE_BEARISH
            adx >= 18.0 -> MarketTrendRegime.DEVELOPING
            else -> MarketTrendRegime.RANGING
        }
    }

    private fun volatilityRegime(candles: List<Candle>): MarketVolatilityRegime {
        val last = candles.lastIndex
        val atr = TechnicalIndicators.calculateATR(candles, 14)[last]
        val pct = (atr / candles[last].close) * 100.0
        return when {
            pct >= 2.0 -> MarketVolatilityRegime.HIGH
            pct <= 0.35 -> MarketVolatilityRegime.COMPRESSED
            else -> MarketVolatilityRegime.NORMAL
        }
    }

    private fun liquidityNarrative(candles: List<Candle>, price: Double): LiquidityContext {
        val openPools = smcDetector.detectLiquidity(candles).filter { !it.swept }
        val nearest = openPools.minByOrNull { abs(it.price - price) }
        return when (nearest?.type) {
            LiquidityType.BUY_SIDE -> LiquidityContext(
                text = "Nearest resting liquidity is buy-side above ${nearest.price.fmt()}.",
                nextLiquidity = "buy-side liquidity above ${nearest.price.fmt()}",
            )
            LiquidityType.SELL_SIDE -> LiquidityContext(
                text = "Nearest resting liquidity is sell-side below ${nearest.price.fmt()}.",
                nextLiquidity = "sell-side liquidity below ${nearest.price.fmt()}",
            )
            null -> LiquidityContext(
                text = "No clear un-swept equal-high/equal-low liquidity pool nearby.",
                nextLiquidity = "fresh liquidity formation",
            )
        }
    }

    private fun inefficiencyNarrative(candles: List<Candle>, price: Double): InefficiencyContext {
        val activeFvg = smcDetector.detectFairValueGaps(candles)
            .filter { !it.filled }
            .minByOrNull { abs(((it.highPrice + it.lowPrice) / 2.0) - price) }
        val activeOb = smcDetector.detectOrderBlocks(candles)
            .filter { !it.mitigated }
            .minByOrNull { abs(((it.highPrice + it.lowPrice) / 2.0) - price) }
        val fvgText = activeFvg?.let { "nearest ${it.type.name.lowercase()} FVG ${it.lowPrice.fmt()}–${it.highPrice.fmt()}" }
        val obText = activeOb?.let { "nearest ${it.type.name.lowercase()} order block ${it.lowPrice.fmt()}–${it.highPrice.fmt()}" }
        val text = listOfNotNull(fvgText, obText).joinToString("; ").ifBlank {
            "No nearby unfilled FVG or unmitigated order block dominates current price."
        }
        val directionalHint = when {
            activeFvg?.type == FvgType.BULLISH || activeOb?.type == OrderBlockType.BULLISH -> Bias.BULLISH
            activeFvg?.type == FvgType.BEARISH || activeOb?.type == OrderBlockType.BEARISH -> Bias.BEARISH
            else -> Bias.NEUTRAL
        }
        return InefficiencyContext(text = text, directionalHint = directionalHint)
    }

    private fun keyLevels(candles: List<Candle>, price: Double): List<MarketKeyLevel> {
        val window = candles.takeLast(KEY_LEVEL_LOOKBACK)
        val high = window.maxOf { it.high }
        val low = window.minOf { it.low }
        val eq = (high + low) / 2.0
        val openPools = smcDetector.detectLiquidity(candles).filter { !it.swept }
        return buildList {
            add(MarketKeyLevel("Range High", high, high - price))
            add(MarketKeyLevel("Equilibrium", eq, eq - price))
            add(MarketKeyLevel("Range Low", low, low - price))
            openPools
                .sortedBy { abs(it.price - price) }
                .take(2)
                .forEach { pool ->
                    add(MarketKeyLevel(pool.type.name.replace('_', ' '), pool.price, pool.price - price))
                }
        }.sortedBy { abs(it.distanceFromPrice) }.take(MAX_KEY_LEVELS)
    }

    private fun directionalContext(
        structureBias: Bias,
        htfBias: Bias,
        trend: MarketTrendRegime,
    ): MarketDirectionalContext = when {
        structureBias == Bias.BULLISH && htfBias == Bias.BULLISH -> MarketDirectionalContext.BULLISH_ALIGNED
        structureBias == Bias.BEARISH && htfBias == Bias.BEARISH -> MarketDirectionalContext.BEARISH_ALIGNED
        structureBias != Bias.NEUTRAL && htfBias != Bias.NEUTRAL && structureBias != htfBias -> MarketDirectionalContext.CONFLICTED
        trend == MarketTrendRegime.RANGING -> MarketDirectionalContext.RANGE_BOUND
        structureBias == Bias.BULLISH -> MarketDirectionalContext.LOCAL_BULLISH
        structureBias == Bias.BEARISH -> MarketDirectionalContext.LOCAL_BEARISH
        else -> MarketDirectionalContext.NEUTRAL
    }

    private fun nextObjective(
        price: Double,
        valueZone: MarketValueZone,
        structureBias: Bias,
        liquidity: LiquidityContext,
        inefficiency: InefficiencyContext,
    ): String = when {
        structureBias == Bias.BULLISH && valueZone != MarketValueZone.PREMIUM ->
            "Likely objective is ${liquidity.nextLiquidity} or premium repricing."
        structureBias == Bias.BEARISH && valueZone != MarketValueZone.DISCOUNT ->
            "Likely objective is ${liquidity.nextLiquidity} or discount repricing."
        inefficiency.directionalHint == Bias.BULLISH && price > 0.0 ->
            "Bullish inefficiency remains relevant; wait for mitigation/reaction confirmation."
        inefficiency.directionalHint == Bias.BEARISH && price > 0.0 ->
            "Bearish inefficiency remains relevant; wait for mitigation/reaction confirmation."
        else -> "Likely objective is liquidity discovery; wait for a sweep or structure break."
    }

    private fun warnings(
        valueZone: MarketValueZone,
        volatility: MarketVolatilityRegime,
        htf: HtfSummary,
        structureBias: Bias,
    ): List<String> = buildList {
        if (valueZone == MarketValueZone.PREMIUM && structureBias == Bias.BULLISH) {
            add("Bullish continuation is late in premium; avoid chasing without pullback.")
        }
        if (valueZone == MarketValueZone.DISCOUNT && structureBias == Bias.BEARISH) {
            add("Bearish continuation is late in discount; avoid chasing without retracement.")
        }
        if (volatility == MarketVolatilityRegime.HIGH) {
            add("High volatility — widen execution tolerance or reduce size.")
        }
        if (htf.overallBias != Bias.NEUTRAL && structureBias != Bias.NEUTRAL && htf.overallBias != structureBias) {
            add("Local structure conflicts with higher-timeframe bias.")
        }
    }

    private fun structureNarrative(bias: Bias, lastBreak: String?): String = when (bias) {
        Bias.BULLISH -> "Structure is bullish${lastBreak?.let { " after $it" }.orEmpty()}."
        Bias.BEARISH -> "Structure is bearish${lastBreak?.let { " after $it" }.orEmpty()}."
        Bias.NEUTRAL -> "Structure is neutral; wait for a confirmed break."
    }

    private fun mentorNotes(
        context: MarketDirectionalContext,
        valueZone: MarketValueZone,
        warnings: List<String>,
    ): List<String> = buildList {
        add("Context: ${context.label}.")
        add("Value: ${valueZone.label}; plan entries from displacement + retracement, not prediction.")
        if (warnings.isEmpty()) add("No major contextual warning from deterministic engine.") else addAll(warnings)
    }

    private fun tags(
        context: MarketDirectionalContext,
        valueZone: MarketValueZone,
        trend: MarketTrendRegime,
        volatility: MarketVolatilityRegime,
        warnings: List<String>,
    ): List<String> = buildList {
        add(context.name)
        add(valueZone.name)
        add(trend.name)
        add(volatility.name)
        if (warnings.isNotEmpty()) add("CAUTION")
    }

    private fun Double.fmt(): String = "%.5f".format(this)

    private data class HtfSummary(val overallBias: Bias, val alignmentScore: Int)
    private data class LiquidityContext(val text: String, val nextLiquidity: String)
    private data class InefficiencyContext(val text: String, val directionalHint: Bias)

    private companion object {
        const val MIN_BARS = 50
        const val VALUE_LOOKBACK = 100
        const val KEY_LEVEL_LOOKBACK = 120
        const val MAX_KEY_LEVELS = 5
    }
}

data class MarketExplanation(
    val symbol: String,
    val timeframe: Timeframe,
    val bias: Bias,
    val htfBias: Bias,
    val directionalContext: MarketDirectionalContext,
    val valueZone: MarketValueZone,
    val trendRegime: MarketTrendRegime,
    val volatilityRegime: MarketVolatilityRegime,
    val structureNarrative: String,
    val liquidityNarrative: String,
    val inefficiencyNarrative: String,
    val nextObjective: String,
    val keyLevels: List<MarketKeyLevel>,
    val warnings: List<String>,
    val htfAlignmentScore: Int,
    val summary: String,
    val mentorNotes: List<String>,
    val tags: List<String>,
) {
    companion object {
        fun insufficient(symbol: String, timeframe: Timeframe, barCount: Int): MarketExplanation = MarketExplanation(
            symbol = symbol,
            timeframe = timeframe,
            bias = Bias.NEUTRAL,
            htfBias = Bias.NEUTRAL,
            directionalContext = MarketDirectionalContext.NEUTRAL,
            valueZone = MarketValueZone.EQUILIBRIUM,
            trendRegime = MarketTrendRegime.RANGING,
            volatilityRegime = MarketVolatilityRegime.NORMAL,
            structureNarrative = "Insufficient data for market explanation.",
            liquidityNarrative = "Need at least 50 candles; got $barCount.",
            inefficiencyNarrative = "Unavailable.",
            nextObjective = "Collect more confirmed candles.",
            keyLevels = emptyList(),
            warnings = listOf("Insufficient data"),
            htfAlignmentScore = 0,
            summary = "$symbol ${timeframe.label}: insufficient data ($barCount bars).",
            mentorNotes = listOf("Wait for more confirmed candles before forming context."),
            tags = listOf("INSUFFICIENT_DATA"),
        )
    }
}

data class MarketKeyLevel(
    val label: String,
    val price: Double,
    val distanceFromPrice: Double,
)

enum class MarketValueZone(val label: String) { PREMIUM("Premium"), EQUILIBRIUM("Equilibrium"), DISCOUNT("Discount") }

enum class MarketTrendRegime(val label: String) {
    IMPULSIVE_BULLISH("Impulsive bullish trend"),
    IMPULSIVE_BEARISH("Impulsive bearish trend"),
    DEVELOPING("Developing trend"),
    RANGING("Ranging market"),
}

enum class MarketVolatilityRegime(val label: String) { COMPRESSED("Compressed volatility"), NORMAL("Normal volatility"), HIGH("High volatility") }

enum class MarketDirectionalContext(val label: String) {
    BULLISH_ALIGNED("Bullish HTF/local alignment"),
    BEARISH_ALIGNED("Bearish HTF/local alignment"),
    LOCAL_BULLISH("Local bullish structure"),
    LOCAL_BEARISH("Local bearish structure"),
    CONFLICTED("Conflicting timeframe structure"),
    RANGE_BOUND("Range-bound context"),
    NEUTRAL("Neutral context"),
}
