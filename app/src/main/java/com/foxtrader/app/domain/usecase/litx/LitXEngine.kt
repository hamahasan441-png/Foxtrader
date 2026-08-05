package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FairValueGap
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityPool
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitXSignal
import com.foxtrader.app.domain.model.LitXStage
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.PriceZoneKind
import com.foxtrader.app.domain.model.SessionRange
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.TradingSession
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.analysis.RiskRewardOptimizer
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * LIT X Institutional Framework engine.
 *
 * Runs the requested pipeline — Market Context → HTF Bias → Structure →
 * Liquidity Mapping → Inducement/Sweep → Market Shift → POI → Entry Validation
 * → Confidence Scoring → Signal — by ORCHESTRATING the app's existing detectors
 * (`SmcDetector`, `AnalyzeMarketStructureUseCase`, `SessionDetector`,
 * `RiskRewardOptimizer`) and the new LIT X primitives. It never recomputes SMC
 * work the chart already did: pass [Precomputed] to reuse those results.
 *
 * Pure and synchronous (no coroutines, no repository) so it is fully unit
 * testable on the JVM. Callers supply HTF context (via `MtfContextProvider` /
 * `ConfluenceEngine`) as [htfBias] + [htfAlignmentScore].
 */
@Singleton
class LitXEngine @Inject constructor(
    private val smcDetector: SmcDetector,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val sessionDetector: SessionDetector,
    private val riskRewardOptimizer: RiskRewardOptimizer,
    private val displacementDetector: DisplacementDetector,
    private val mitigationDetector: MitigationBlockDetector,
    private val premiumDiscount: PremiumDiscountCalculator,
    private val mssClassifier: MssClassifier,
    private val scorer: LitXConfidenceScorer,
) {

    /** Optional already-computed SMC results, to avoid duplicated calculations. */
    data class Precomputed(
        val orderBlocks: List<OrderBlock>? = null,
        val fairValueGaps: List<FairValueGap>? = null,
        val liquidityPools: List<LiquidityPool>? = null,
        val sessions: List<SessionRange>? = null,
    )

    private data class Poi(val high: Double, val low: Double, val quality: Int, val kind: String)

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: LitXConfig = LitXConfig(),
        htfBias: Bias = Bias.NEUTRAL,
        htfAlignmentScore: Int = 50,
        precomputed: Precomputed? = null,
    ): LitXAnalysis {
        if (candles.size < MIN_BARS) return LitXAnalysis.empty(symbol, timeframe)

        val now = candles.last().timestamp
        val vol = candles.takeLast(VOL_WINDOW).map { it.range }.average().coerceAtLeast(1e-9)
        val price = candles.last().close

        // --- Reuse existing detectors (or precomputed results) ---
        val structure = analyzeStructure(candles)
        val liquidity = precomputed?.liquidityPools ?: smcDetector.detectLiquidity(candles)
        val orderBlocks = precomputed?.orderBlocks ?: smcDetector.detectOrderBlocks(candles)
        val fvgs = precomputed?.fairValueGaps ?: smcDetector.detectFairValueGaps(candles)
        val sessions = precomputed?.sessions ?: sessionDetector.detectSessions(candles)

        // --- New LIT X primitives ---
        val displacement = displacementDetector.detectLatest(candles, config.displacementAtrMultiple)
        val mitigationBlocks = mitigationDetector.detect(candles, orderBlocks)
        val zone = premiumDiscount.calculate(candles)
        val shift = mssClassifier.classify(structure.breaks, displacement, config.displacementAtrMultiple)

        // --- Directional intent ---
        val intended: Direction? = shift.direction ?: when (structure.bias) {
            Bias.BULLISH -> Direction.BULLISH
            Bias.BEARISH -> Direction.BEARISH
            Bias.NEUTRAL -> null
        }

        if (intended == null) {
            return LitXAnalysis(
                symbol = symbol, timeframe = timeframe,
                stage = if (liquidity.isNotEmpty()) LitXStage.LIQUIDITY_MAPPED else LitXStage.SCANNING,
                bias = structure.bias, htfBias = htfBias,
                displacement = displacement, mitigationBlocks = mitigationBlocks,
                premiumDiscount = zone, signal = null,
                narrative = "Awaiting a directional shift — no valid institutional bias yet.",
                timestamp = now,
            )
        }
        val bullish = intended == Direction.BULLISH

        // --- Liquidity sweep aligned with intent (bullish wants sell-side taken) ---
        val wantSide = if (bullish) LiquidityType.SELL_SIDE else LiquidityType.BUY_SIDE
        val sweep = liquidity.filter { it.swept && it.type == wantSide }
            .maxByOrNull { it.sweepIndex ?: -1 }

        // --- Point of interest (mitigation block > fresh OB > unfilled FVG) ---
        val poi = selectPoi(bullish, orderBlocks, fvgs, mitigationBlocks)

        // --- Entry validation: has price returned into the POI? ---
        val retestScore = if (poi == null) {
            30
        } else {
            val inBand = price in poi.low..poi.high
            val inWide = price in (poi.low - vol)..(poi.high + vol)
            when {
                inBand -> 90
                inWide -> 70
                else -> 40
            }
        }

        // --- Risk/reward via the existing optimizer ---
        val setup = riskRewardOptimizer.optimize(candles, intended, config.minRiskReward)

        // --- Score the 11 factors ---
        val inputs = LitXConfidenceScorer.Inputs(
            trendAlignment = trendAlignment(bullish, htfBias, htfAlignmentScore, config),
            liquidityQuality = if (sweep != null) 85 else if (liquidity.any { it.type == wantSide }) 55 else 35,
            structureQuality = structureQuality(intended, shift, structure.bias),
            sweepStrength = sweepStrength(sweep, candles, vol),
            displacementStrength = displacementStrength(displacement, intended),
            poiQuality = poiQuality(poi, zone?.currentZone, bullish),
            retestQuality = retestScore,
            volumeConfirmation = volumeConfirmation(candles),
            volatilityCondition = volatilityCondition(candles, vol),
            sessionQuality = if (isKillZone(sessions, candles.lastIndex)) 90 else 55,
            riskReward = (setup.riskRewardRatio / config.minRiskReward.coerceAtLeast(1e-9) * 70.0)
                .roundToInt().coerceIn(0, 100),
        )
        val confidence = scorer.score(inputs)

        // --- Stage progression ---
        val poiTapped = poi != null && price in (poi.low - vol)..(poi.high + vol)
        val validated = setup.valid && retestScore >= 70 &&
            LitXConfidenceScorer.meets(confidence.grade, config.minGrade)
        val stage = when {
            validated -> LitXStage.VALIDATED
            poiTapped -> LitXStage.POI_TAPPED
            shift.present && shift.direction == intended -> LitXStage.SHIFT_CONFIRMED
            sweep != null -> LitXStage.SWEEP_DETECTED
            liquidity.isNotEmpty() -> LitXStage.LIQUIDITY_MAPPED
            else -> LitXStage.SCANNING
        }

        // --- Signal only when validated and grade passes the filter ---
        val signal = if (validated) {
            LitXSignal(
                symbol = symbol, timeframe = timeframe, direction = intended, stage = stage,
                entry = setup.entry, stopLoss = setup.stopLoss,
                takeProfit1 = setup.takeProfit1, takeProfit2 = setup.takeProfit2,
                riskReward = setup.riskRewardRatio, confidence = confidence, zone = zone,
                rationale = buildRationale(bullish, shift.isStrong, sweep != null, poi, zone?.currentZone),
                timestamp = now,
            )
        } else {
            null
        }

        return LitXAnalysis(
            symbol = symbol, timeframe = timeframe, stage = stage,
            bias = structure.bias, htfBias = htfBias,
            displacement = displacement, mitigationBlocks = mitigationBlocks,
            premiumDiscount = zone, signal = signal,
            narrative = signal?.rationale
                ?: "Institutional pipeline at ${stage.name.lowercase().replace('_', ' ')}; " +
                "conditions not yet sufficient for an ${config.minGrade.name} setup.",
            timestamp = now,
        )
    }

    // ------------------------------------------------------------------------
    // Factor helpers (each returns 0..100)
    // ------------------------------------------------------------------------

    private fun trendAlignment(bullish: Boolean, htfBias: Bias, htfScore: Int, config: LitXConfig): Int {
        val matches = (bullish && htfBias == Bias.BULLISH) || (!bullish && htfBias == Bias.BEARISH)
        val opposes = (bullish && htfBias == Bias.BEARISH) || (!bullish && htfBias == Bias.BULLISH)
        return when {
            matches -> maxOf(htfScore, 70)
            opposes -> if (config.requireHtfAlignment) minOf(htfScore, 25) else minOf(htfScore, 45)
            else -> htfScore.coerceIn(0, 100)
        }
    }

    private fun structureQuality(intended: Direction, shift: MssClassifier.Result, bias: Bias): Int = when {
        shift.present && shift.direction == intended -> if (shift.isStrong) 95 else 78
        (intended == Direction.BULLISH && bias == Bias.BULLISH) ||
            (intended == Direction.BEARISH && bias == Bias.BEARISH) -> 55
        else -> 40
    }

    private fun sweepStrength(sweep: LiquidityPool?, candles: List<Candle>, vol: Double): Int {
        if (sweep == null) return 30
        val c = sweep.sweepIndex?.let { candles.getOrNull(it) } ?: return 45
        val penetration = if (sweep.type == LiquidityType.SELL_SIDE) sweep.price - c.low else c.high - sweep.price
        return (penetration / vol * 50.0).roundToInt().coerceIn(40, 100)
    }

    private fun displacementStrength(displacement: com.foxtrader.app.domain.model.Displacement?, intended: Direction): Int {
        if (displacement == null || displacement.direction != intended) return 30
        val base = (displacement.atrMultiple / 2.0 * 60.0) + (displacement.bodyToRangeRatio * 40.0)
        return (base + if (displacement.hasFairValueGap) 10.0 else 0.0).roundToInt().coerceIn(0, 100)
    }

    private fun poiQuality(poi: Poi?, zone: PriceZoneKind?, bullish: Boolean): Int {
        if (poi == null) return 35
        val locationBonus = when {
            bullish && zone == PriceZoneKind.DISCOUNT -> 10
            !bullish && zone == PriceZoneKind.PREMIUM -> 10
            else -> 0
        }
        return (poi.quality + locationBonus).coerceIn(0, 100)
    }

    private fun volumeConfirmation(candles: List<Candle>): Int {
        val recent = candles.takeLast(VOL_WINDOW + 1)
        val avg = recent.dropLast(1).map { it.volume }.average()
        val last = candles.last().volume
        if (avg <= 0.0 || last <= 0.0) return 50 // provider without real volume
        val ratio = last / avg
        return when {
            ratio >= 1.3 -> 85
            ratio >= 1.0 -> 65
            else -> 45
        }
    }

    private fun volatilityCondition(candles: List<Candle>, shortVol: Double): Int {
        val longVol = candles.takeLast(LONG_VOL_WINDOW).map { it.range }.average().coerceAtLeast(1e-9)
        val ratio = shortVol / longVol
        return when {
            ratio in 0.7..1.5 -> 90
            ratio in 0.5..2.0 -> 65
            else -> 40
        }
    }

    private fun isKillZone(sessions: List<SessionRange>, lastIndex: Int): Boolean =
        sessions.any {
            (it.session == TradingSession.LONDON || it.session == TradingSession.NEW_YORK) &&
                lastIndex in it.startIndex..it.endIndex
        }

    private fun selectPoi(
        bullish: Boolean,
        orderBlocks: List<OrderBlock>,
        fvgs: List<FairValueGap>,
        mitigationBlocks: List<com.foxtrader.app.domain.model.MitigationBlock>,
    ): Poi? {
        val dir = if (bullish) Direction.BULLISH else Direction.BEARISH
        mitigationBlocks.filter { it.direction == dir }.maxByOrNull { it.mitigationIndex }?.let {
            return Poi(it.highPrice, it.lowPrice, 88, "Mitigation block")
        }
        val obType = if (bullish) OrderBlockType.BULLISH else OrderBlockType.BEARISH
        orderBlocks.filter { it.type == obType && !it.mitigated }.maxByOrNull { it.startIndex }?.let {
            return Poi(it.highPrice, it.lowPrice, 78, "Order block")
        }
        val fvgType = if (bullish) FvgType.BULLISH else FvgType.BEARISH
        fvgs.filter { it.type == fvgType && !it.filled }.maxByOrNull { it.index }?.let {
            return Poi(it.highPrice, it.lowPrice, 65, "Fair value gap")
        }
        return null
    }

    private fun buildRationale(
        bullish: Boolean,
        strongShift: Boolean,
        swept: Boolean,
        poi: Poi?,
        zone: PriceZoneKind?,
    ): String {
        val dir = if (bullish) "Long" else "Short"
        val shiftTxt = if (strongShift) "MSS (displacement-confirmed)" else "CHOCH"
        val sweepTxt = if (swept) "liquidity swept, " else ""
        val poiTxt = poi?.kind?.let { "$it POI" } ?: "structural POI"
        val zoneTxt = zone?.name?.lowercase()?.let { " in $it" } ?: ""
        return "$dir: $sweepTxt$shiftTxt, entry from $poiTxt$zoneTxt."
    }

    private companion object {
        const val MIN_BARS = 50
        const val VOL_WINDOW = 14
        const val LONG_VOL_WINDOW = 50
    }
}
