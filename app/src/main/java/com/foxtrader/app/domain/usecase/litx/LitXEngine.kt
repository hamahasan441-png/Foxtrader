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
import com.foxtrader.app.domain.model.gates
import com.foxtrader.app.domain.model.LitXMode
import com.foxtrader.app.domain.model.LitXSignal
import com.foxtrader.app.domain.model.LitXStage
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.PriceZoneKind
import com.foxtrader.app.domain.model.SessionRange
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.TradingSession
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.signalintel.SignalSeriesIntegrity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * LIT X Institutional Framework engine.
 *
 * Runs the requested pipeline — Market Context → HTF Bias → Structure →
 * Liquidity Mapping → Inducement/Sweep → Market Shift → POI → Entry Validation
 * → Confidence Scoring → Signal — by ORCHESTRATING the app's existing detectors
 * (`SmcDetector`, `AnalyzeMarketStructureUseCase`, `SessionDetector`) and the
 * new LIT X primitives. It never recomputes SMC
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

    private data class Poi(
        val high: Double,
        val low: Double,
        val quality: Int,
        val kind: String,
        val originIndex: Int,
        /** First bar on which the POI is fully observable (not a retest). */
        val availableIndex: Int,
    )

    private data class ProfileRules(
        val displacementAtr: Double,
        val minRiskReward: Double,
        val minConfidence: Int,
        val maxSweepToShiftBars: Int,
        val maxShiftToRetestBars: Int,
    )

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: LitXConfig = LitXConfig(),
        htfBias: Bias = Bias.NEUTRAL,
        htfAlignmentScore: Int = 50,
        precomputed: Precomputed? = null,
    ): LitXAnalysis {
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) {
            return LitXAnalysis.empty(symbol, timeframe).copy(
                narrative = integrity.reason ?: "Invalid market data for LIT X.",
            )
        }

        val cfg = config.sanitized()
        val rules = cfg.effectiveGates()
        val effectiveDisplacementAtr = cfg.displacementAtrMultiple
        val effectiveMinRr = cfg.minRiskReward
        val effectiveMinConfidence = cfg.minConfidenceScore
        val effectiveSweepToShift = cfg.maxSweepToShiftBars
        val effectiveShiftToRetest = cfg.maxShiftToRetestBars

        val now = candles.last().timestamp
        // The setup window has to be able to contain the sequence the other
        // windows describe, or those settings are promises the engine cannot
        // keep. A sweep, the shift that follows it, the bars structure needs to
        // confirm that shift, and the retest after it span
        // maxSweepToShift + STRUCTURE_RIGHT_BARS + maxShiftToRetest bars — 31
        // at the defaults, against a fixed window of 30. So the last bar of a
        // valid sequence always fell outside the window that was searched for
        // its first bar, and widening either configured window made no
        // difference because the fixed one still cut the sweep off.
        //
        // Measured on five thousand bars each of EURUSD, GBPUSD, AUDUSD,
        // USDJPY and XAUUSD on M15 and H1 — ten real series — the study
        // published nothing at all.
        val setupLookbackBars = maxOf(
            SETUP_LOOKBACK_BARS,
            effectiveSweepToShift + STRUCTURE_RIGHT_BARS + effectiveShiftToRetest + SETUP_WINDOW_MARGIN,
        )
        val setupStartIndex = (candles.lastIndex - setupLookbackBars + 1).coerceAtLeast(0)
        val vol = candles.takeLast(VOL_WINDOW).map { it.range }.average().coerceAtLeast(1e-9)
        val price = candles.last().close

        // --- Reuse existing detectors (or precomputed results) ---
        val structure = analyzeStructure(candles)
        val liquidity = precomputed?.liquidityPools ?: smcDetector.detectLiquidity(candles)
        val orderBlocks = precomputed?.orderBlocks ?: smcDetector.detectOrderBlocks(candles)
        val fvgs = precomputed?.fairValueGaps ?: smcDetector.detectFairValueGaps(candles)
        val sessions = precomputed?.sessions ?: sessionDetector.detectSessions(candles)

        // --- New LIT X primitives ---
        val displacement = displacementDetector.detectLatest(candles, effectiveDisplacementAtr)
        val mitigationBlocks = mitigationDetector.detect(candles, orderBlocks)
        val zone = premiumDiscount.calculate(candles)
        val shift = mssClassifier.classify(
            breaks = structure.breaks,
            displacementAtrMultiple = effectiveDisplacementAtr,
            minBreakIndex = setupStartIndex,
        ) { direction, from, to ->
            displacementDetector.detectInWindow(
                candles = candles,
                direction = direction,
                from = from,
                to = to,
                atrMultiple = effectiveDisplacementAtr,
            )
        }

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

        // --- Higher-timeframe context ---
        // When the caller supplies no HTF read (the chart path calls analyze()
        // without htfBias, so it defaults to NEUTRAL), derive a coarse HTF trend
        // proxy from a longer window of the same series. This keeps the highest
        // weighted factor (Trend Alignment) meaningful instead of pinned neutral.
        val (effHtfBias, effHtfScore) = if (htfBias == Bias.NEUTRAL) {
            deriveHtfProxy(candles)
        } else {
            htfBias to htfAlignmentScore
        }

        // --- Liquidity sweep aligned with intent (bullish wants sell-side taken) ---
        // Keep a recent sweep for stage reporting, but only an ordered sweep
        // (sweep before the recent shift) may validate the institutional setup.
        val wantSide = if (bullish) LiquidityType.SELL_SIDE else LiquidityType.BUY_SIDE
        val recentSweep = liquidity.asSequence()
            .filter { it.swept && it.type == wantSide }
            .filter { (it.sweepIndex ?: -1) in setupStartIndex..candles.lastIndex }
            .maxByOrNull { it.sweepIndex ?: -1 }
        val sweep = if (shift.present) {
            liquidity.asSequence()
                .filter { it.swept && it.type == wantSide }
                .filter { (it.sweepIndex ?: -1) in setupStartIndex..shift.breakIndex }
                .maxByOrNull { it.sweepIndex ?: -1 }
        } else {
            null
        }
        val sweepIndex = sweep?.sweepIndex ?: -1
        val shiftKnowledgeIndex = if (shift.present) shift.breakIndex + STRUCTURE_RIGHT_BARS else -1
        // A sweep, when present, must still precede the shift within the
        // profile window. Modes that are not liquidity-led (MOMENTUM) may
        // confirm a shift without one; modes that are led by liquidity may not.
        val sweepOrdered = sweep != null && sweepIndex >= 0 &&
            shift.breakIndex >= sweepIndex &&
            shift.breakIndex - sweepIndex <= effectiveSweepToShift
        val orderedShift = shift.present &&
            shiftKnowledgeIndex <= candles.lastIndex &&
            (!rules.requireSweep || sweepOrdered)
        val shiftConfirmed = orderedShift && shift.direction == intended &&
            (!cfg.requireStrongMss || shift.isStrong)

        // --- Point of interest (mitigation block > fresh OB > unfilled FVG) ---
        // A POI belongs to this setup only when it formed after the sweep. The
        // final-bar retest must occur after the shift before progression can
        // reach POI_TAPPED.
        val poi = if (shiftConfirmed) {
            selectPoi(
                bullish = bullish,
                orderBlocks = orderBlocks,
                fvgs = fvgs,
                mitigationBlocks = mitigationBlocks,
                minOriginIndex = sweep?.sweepIndex ?: setupStartIndex,
                shiftIndex = shift.breakIndex,
                allowFvg = rules.allowFvgPoi,
            )
        } else {
            null
        }
        val retestSearchStart = if (shiftConfirmed && poi != null) {
            maxOf(shiftKnowledgeIndex + 1, poi.availableIndex + 1)
        } else {
            Int.MAX_VALUE
        }
        val firstRetestIndex = if (poi != null && retestSearchStart <= candles.lastIndex) {
            (retestSearchStart..candles.lastIndex).firstOrNull { idx ->
                candles[idx].low <= poi.high && candles[idx].high >= poi.low
            }
        } else null
        val retestAfterShift = firstRetestIndex != null &&
            firstRetestIndex - shiftKnowledgeIndex <= effectiveShiftToRetest
        val isFreshRetest = retestAfterShift && firstRetestIndex == candles.lastIndex

        // --- Entry validation: has price returned into the ordered POI? ---
        // MOMENTUM does not use a retest at all, so scoring it as a failed
        // retest (30) would penalise the mode for a rule it does not run.
        // It gets a neutral 60 instead: no evidence for, no evidence against.
        // The factor is not free -- MOMENTUM pays for it by requiring aligned
        // displacement, which PRECISION does not.
        val retestScore = if (poi == null || !isFreshRetest) {
            if (!rules.requireRetest && poi != null) NEUTRAL_RETEST_SCORE else 30
        } else {
            // Continuous score based on how far price sits from the POI band,
            // normalised by volatility, instead of three hard buckets. Inside
            // the band scores highest and decays smoothly with distance. The
            // decay is tuned so ~1 volatility unit away still yields 70 (the
            // validation gate), preserving the prior in-band / near-band cutoff.
            val distance = when {
                price in poi.low..poi.high -> 0.0
                price < poi.low -> poi.low - price
                else -> price - poi.high
            }
            val norm = (distance / vol).coerceAtLeast(0.0)
            (RETEST_MAX_SCORE - norm * RETEST_DECAY_PER_VOL).roundToInt().coerceIn(40, RETEST_MAX_SCORE)
        }

        // --- Risk/reward derived from the actual POI + dealing range ---
        // NOTE: intentionally NOT RiskRewardOptimizer — it builds the target as
        // exactly minRR*stop, so its ratio is a constant and could never gate a
        // setup. LIT X measures real reward to the opposite side of the range.
        val rr = buildRiskReward(bullish, price, poi, zone, vol)

        // --- Score the 11 factors ---
        val inputs = LitXConfidenceScorer.Inputs(
            trendAlignment = trendAlignment(bullish, effHtfBias, effHtfScore, config),
            liquidityQuality = if (sweep != null) 85 else if (liquidity.any { it.type == wantSide }) 55 else 35,
            structureQuality = structureQuality(intended, shift, structure.bias),
            sweepStrength = sweepStrength(sweep, candles, vol),
            displacementStrength = displacementStrength(displacement, intended),
            poiQuality = poiQuality(poi, zone?.currentZone, bullish),
            retestQuality = retestScore,
            volumeConfirmation = volumeConfirmation(candles),
            volatilityCondition = volatilityCondition(candles, vol),
            sessionQuality = if (isKillZone(sessions, candles.lastIndex)) 90 else 55,
            riskReward = (rr.riskReward / effectiveMinRr.coerceAtLeast(1e-9) * 70.0)
                .roundToInt().coerceIn(0, 100),
        )
        val confidence = scorer.score(inputs)

        // --- Stage progression ---
        val poiTapped = retestAfterShift && poi != null &&
            firstRetestIndex != null && candles[firstRetestIndex].low <= poi.high + vol &&
            candles[firstRetestIndex].high >= poi.low - vol
        // "Longs from discount" means do not buy what is already expensive, not
        // "buy only at the very bottom". Demanding DISCOUNT exactly also refused
        // every setup at EQUILIBRIUM — which is where the 50% retracement of a
        // displacement lands, so it rejected the entries the model is built
        // around. Premium is what disqualifies a long; equilibrium does not.
        val directionalZoneAligned = when {
            zone == null -> true
            bullish -> zone.currentZone != PriceZoneKind.PREMIUM
            else -> zone.currentZone != PriceZoneKind.DISCOUNT
        }
        val htfDirectionAligned = (bullish && effHtfBias == Bias.BULLISH) ||
            (!bullish && effHtfBias == Bias.BEARISH)
        val displacementAligned = displacement != null && displacement.direction == intended
        val inBandTap = poi != null && price in poi.low..poi.high
        val killZone = isKillZone(sessions, candles.lastIndex)

        // The trigger bar. Retest modes fire on the first POI tap; MOMENTUM
        // fires on the bar the shift itself becomes knowable. Both are pinned
        // to candles.lastIndex, so every mode keeps the one-shot, right-edge,
        // non-repainting emission contract -- a mode can change how selective
        // the engine is, never when it is allowed to know something.
        val entryTrigger = if (rules.requireRetest) {
            isFreshRetest && poiTapped
        } else {
            shiftConfirmed && shiftKnowledgeIndex == candles.lastIndex
        }

        // A validated LiT Adventure setup requires the gates its active mode
        // declares (see ModeRules), plus the shared geometry and grade floors.
        val validated = shiftConfirmed && poi != null && rr.valid && entryTrigger &&
            rr.riskReward >= effectiveMinRr &&
            (!rules.requireRetest || retestScore >= 70) &&
            (!rules.requireInBandTap || inBandTap) &&
            (!rules.requireAlignedDisplacement || displacementAligned) &&
            (!rules.requireKillZone || killZone) &&
            (!cfg.requireHtfAlignment || htfDirectionAligned) &&
            (!cfg.requireDirectionalZone || directionalZoneAligned) &&
            confidence.score >= effectiveMinConfidence &&
            LitXConfidenceScorer.meets(confidence.grade, cfg.minGrade)
        val stage = when {
            validated -> LitXStage.VALIDATED
            poiTapped -> LitXStage.POI_TAPPED
            shiftConfirmed -> LitXStage.SHIFT_CONFIRMED
            recentSweep != null -> LitXStage.SWEEP_DETECTED
            liquidity.isNotEmpty() -> LitXStage.LIQUIDITY_MAPPED
            else -> LitXStage.SCANNING
        }

        // --- Signal only when validated and grade passes the filter ---
        val signal = if (validated) {
            LitXSignal(
                symbol = symbol, timeframe = timeframe, direction = intended, stage = stage,
                entry = rr.entry, stopLoss = rr.stopLoss,
                takeProfit1 = rr.takeProfit1, takeProfit2 = rr.takeProfit2,
                riskReward = rr.riskReward, confidence = confidence, zone = zone,
                rationale = buildRationale(
                    cfg.mode, bullish, shift.isStrong, sweep != null,
                    rules.requireRetest, poi, zone?.currentZone,
                ),
                timestamp = now,
                confirmationIndex = candles.lastIndex,
                confirmations = buildList {
                    // Configuration traceability: a stored signal must be
                    // attributable to the rule set that produced it, otherwise
                    // per-mode accuracy cannot be measured after the fact.
                    add("MODE_${cfg.mode.name}")
                    if (sweep != null) add("LIQUIDITY_SWEEP")
                    add(if (shift.isStrong) "MSS" else "CHOCH")
                    if (displacement?.direction == intended) add("DISPLACEMENT")
                    poi?.kind?.let { add(it.uppercase().replace(' ', '_')) }
                    if (rules.requireRetest) add("POI_RETEST") else add("CONTINUATION_ENTRY")
                    if (rules.requireKillZone && killZone) add("KILL_ZONE")
                    if (directionalZoneAligned) add("PREMIUM_DISCOUNT")
                    add("RR_${"%.2f".format(rr.riskReward)}")
                },
            )
        } else {
            null
        }

        return LitXAnalysis(
            symbol = symbol, timeframe = timeframe, stage = stage,
            bias = structure.bias, htfBias = effHtfBias,
            displacement = displacement, mitigationBlocks = mitigationBlocks,
            premiumDiscount = zone, signal = signal,
            narrative = signal?.rationale
                ?: "${cfg.mode.label}: pipeline at ${stage.name.lowercase().replace('_', ' ')}; " +
                "conditions not yet sufficient for an ${cfg.minGrade.name} setup.",
            timestamp = now,
        )
    }

    // ------------------------------------------------------------------------
    // Factor helpers (each returns 0..100)
    // ------------------------------------------------------------------------

    /**
     * Coarse higher-timeframe trend proxy derived from a longer window of the
     * same series, used only when the caller provides no external HTF read.
     * Net directional move over the window is expressed in average-range units;
     * the magnitude maps to a 50..80 alignment score (kept below a true HTF
     * read's ceiling because a same-series proxy is weaker evidence). Uses only
     * past/current closes, so it never repaints.
     */
    private fun deriveHtfProxy(candles: List<Candle>): Pair<Bias, Int> {
        val window = candles.takeLast(HTF_PROXY_WINDOW)
        if (window.size < HTF_PROXY_MIN_BARS) return Bias.NEUTRAL to 50
        val netMove = window.last().close - window.first().close
        val avgRange = window.map { it.range }.average().coerceAtLeast(1e-9)
        val strength = netMove / avgRange
        val magnitude = kotlin.math.abs(strength)
        val score = (50.0 + (magnitude / HTF_PROXY_FULL_STRENGTH).coerceAtMost(1.0) * 30.0)
            .roundToInt().coerceIn(50, 80)
        return when {
            strength >= HTF_PROXY_TREND_MIN -> Bias.BULLISH to score
            strength <= -HTF_PROXY_TREND_MIN -> Bias.BEARISH to score
            else -> Bias.NEUTRAL to 50
        }
    }

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
        val avgVol = recent.dropLast(1).map { it.volume }.average()
        val lastVol = candles.last().volume
        if (avgVol > 0.0 && lastVol > 0.0) {
            val ratio = lastVol / avgVol
            return when {
                ratio >= 1.3 -> 85
                ratio >= 1.0 -> 65
                else -> 45
            }
        }
        // Provider without real volume (common on spot FX / some crypto feeds).
        // Fall back to a range-based participation proxy: an expansion bar
        // (range well above the recent average) reflects the same "increased
        // participation" that a volume spike would, so a whole weighted factor
        // is no longer silently neutralised at a flat 50.
        val avgRange = recent.dropLast(1).map { it.range }.average()
        val lastRange = candles.last().range
        if (avgRange <= 0.0 || lastRange <= 0.0) return 50
        val ratio = lastRange / avgRange
        return when {
            ratio >= 1.5 -> 82
            ratio >= 1.1 -> 66
            ratio >= 0.8 -> 55
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
        minOriginIndex: Int,
        shiftIndex: Int,
        allowFvg: Boolean,
    ): Poi? {
        val dir = if (bullish) Direction.BULLISH else Direction.BEARISH
        mitigationBlocks
            .filter {
                it.direction == dir &&
                    it.originIndex >= minOriginIndex &&
                    it.mitigationIndex > shiftIndex
            }
            .maxByOrNull { it.mitigationIndex }
            ?.let {
                return Poi(
                    it.highPrice, it.lowPrice, 88, "Mitigation block",
                    originIndex = it.originIndex,
                    availableIndex = it.confirmationIndex,
                )
            }
        val obType = if (bullish) OrderBlockType.BULLISH else OrderBlockType.BEARISH
        orderBlocks
            .filter { it.type == obType && !it.mitigated && it.startIndex >= minOriginIndex }
            .maxByOrNull { it.startIndex }
            ?.let {
                return Poi(
                    it.highPrice, it.lowPrice, 78, "Order block",
                    originIndex = it.startIndex,
                    availableIndex = it.startIndex + 1,
                )
            }
        // SNIPER refuses an unmitigated gap as a standalone institutional
        // origin: it is the weakest of the three POI kinds (quality 65 vs
        // 78/88) and carries no order-flow evidence of participation.
        if (!allowFvg) return null
        val fvgType = if (bullish) FvgType.BULLISH else FvgType.BEARISH
        fvgs
            .filter { it.type == fvgType && !it.filled && it.index >= minOriginIndex }
            .maxByOrNull { it.index }
            ?.let {
                return Poi(
                    it.highPrice, it.lowPrice, 65, "Fair value gap",
                    originIndex = it.index,
                    availableIndex = it.index + 1,
                )
            }
        return null
    }

    private data class Rr(
        val entry: Double,
        val stopLoss: Double,
        val takeProfit1: Double,
        val takeProfit2: Double,
        val riskReward: Double,
        val valid: Boolean,
    )

    /**
     * Build entry/stop/targets from the selected POI and the dealing range.
     * Long: stop below the POI, reward toward the range high; short mirrors it.
     * Invalid (valid=false) when there is no POI or the geometry is degenerate
     * (non-positive risk or reward), which prevents a signal being emitted.
     */
    private fun buildRiskReward(
        bullish: Boolean,
        price: Double,
        poi: Poi?,
        zone: com.foxtrader.app.domain.model.PremiumDiscountZone?,
        vol: Double,
    ): Rr {
        fun invalid() = Rr(price, price, price, price, 0.0, false)
        if (poi == null) return invalid()
        val buffer = vol * 0.25
        return if (bullish) {
            val stop = poi.low - buffer
            val risk = price - stop
            val target = zone?.rangeHigh?.takeIf { it > price } ?: return invalid()
            val reward = target - price
            val ratio = if (risk > 0.0) reward / risk else 0.0
            Rr(price, stop, price + reward * 0.6, target, ratio, risk > 0.0 && reward > 0.0)
        } else {
            val stop = poi.high + buffer
            val risk = stop - price
            val target = zone?.rangeLow?.takeIf { it < price } ?: return invalid()
            val reward = price - target
            val ratio = if (risk > 0.0) reward / risk else 0.0
            Rr(price, stop, price - reward * 0.6, target, ratio, risk > 0.0 && reward > 0.0)
        }
    }

    private fun buildRationale(
        mode: LitXMode,
        bullish: Boolean,
        strongShift: Boolean,
        swept: Boolean,
        retestEntry: Boolean,
        poi: Poi?,
        zone: PriceZoneKind?,
    ): String {
        val dir = if (bullish) "Long" else "Short"
        val shiftTxt = if (strongShift) "MSS (displacement-confirmed)" else "CHOCH"
        val sweepTxt = if (swept) "liquidity swept, " else ""
        val poiTxt = poi?.kind?.let { "$it POI" } ?: "structural POI"
        val zoneTxt = zone?.name?.lowercase()?.let { " in $it" } ?: ""
        val entryTxt = if (retestEntry) "entry from" else "continuation entry off"
        return "${mode.label} $dir: $sweepTxt$shiftTxt, $entryTxt $poiTxt$zoneTxt."
    }

    private fun profileRules(profile: com.foxtrader.app.domain.model.SignalProfile): ProfileRules = when (profile) {
        com.foxtrader.app.domain.model.SignalProfile.SCALPING -> ProfileRules(1.20, 1.8, 76, 7, 7)
        com.foxtrader.app.domain.model.SignalProfile.INTRADAY -> ProfileRules(1.25, 2.0, 78, 10, 12)
        com.foxtrader.app.domain.model.SignalProfile.SWING -> ProfileRules(1.35, 2.3, 80, 16, 20)
    }

    private companion object {
        const val MIN_BARS = 50
        /** Floor for the setup window; the configured windows can widen it. */
        const val SETUP_LOOKBACK_BARS = 30

        /**
         * Slack beyond the exact span the configured windows need.
         *
         * Without it the sequence only fits when every stage lands on its
         * earliest possible bar, which is a boundary the market has no reason
         * to respect.
         */
        const val SETUP_WINDOW_MARGIN = 5
        const val VOL_WINDOW = 14
        const val LONG_VOL_WINDOW = 50
        const val STRUCTURE_RIGHT_BARS = 5

        // Continuous retest scoring: in-band max, decaying per volatility unit.
        // 92 - 22*1.0 = 70 keeps the ~1-vol near-band cutoff at the validation gate.
        const val RETEST_MAX_SCORE = 92

        // Applied when the active mode does not evaluate retests at all.
        const val NEUTRAL_RETEST_SCORE = 60
        const val RETEST_DECAY_PER_VOL = 22.0

        // Higher-timeframe trend proxy (same-series fallback when no HTF supplied).
        const val HTF_PROXY_WINDOW = 60
        const val HTF_PROXY_MIN_BARS = 30
        // Net move (in avg-range units) at/above which the window is "trending".
        const val HTF_PROXY_TREND_MIN = 1.5
        // Net move (in avg-range units) mapped to the full proxy score ceiling.
        const val HTF_PROXY_FULL_STRENGTH = 12.0
    }
}
