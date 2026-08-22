package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitSignal
import com.foxtrader.app.domain.model.LitStage
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.PriceZoneKind
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * First-class LIT (Liquidity Inducement Theory) engine for Phase 13.
 *
 * Institutional sequence:
 *   liquidity pool -> reclaiming sweep -> CHOCH/MSS -> aligned displacement ->
 *   post-confirmation POI retest -> bounded risk/reward.
 *
 * The engine is intentionally stricter than the legacy StrategyLibrary LIT rule:
 * it emits only on the first confirmed retest bar, so a zone cannot print the
 * same arrow on every candle. The caller must provide closed candles.
 */
@Singleton
class LitEngine @Inject constructor(
    private val smcDetector: SmcDetector,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val displacementDetector: DisplacementDetector,
    private val premiumDiscount: PremiumDiscountCalculator,
) {

    private data class ProfileRules(
        val setupLookback: Int,
        val maxSweepToShiftBars: Int,
        val maxShiftToRetestBars: Int,
        val minRr: Double,
        val displacementAtr: Double,
    )

    private data class Poi(
        val low: Double,
        val high: Double,
        val originIndex: Int,
        val availableIndex: Int,
        val quality: Int,
        val label: String,
    )

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: LitConfig = LitConfig(),
    ): LitAnalysis {
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) return LitAnalysis.empty(symbol, timeframe, integrity.reason ?: "Invalid market data.")

        val cfg = config.sanitized()
        val rules = ProfileRules(
            setupLookback = cfg.setupLookback,
            maxSweepToShiftBars = cfg.maxSweepToShiftBars,
            maxShiftToRetestBars = cfg.maxShiftToRetestBars,
            minRr = cfg.minRiskReward,
            displacementAtr = cfg.displacementAtrMultiple,
        )
        val last = candles.lastIndex
        val rangeStart = (last - rules.setupLookback + 1).coerceAtLeast(0)
        val avgRange = candles.takeLast(ATR_WINDOW).map { it.range }.filter { it > 0.0 }.average()
            .takeIf { it.isFinite() && it > 0.0 }
            ?: return LitAnalysis.empty(symbol, timeframe, "Volatility is not measurable.")

        val liquidity = smcDetector.detectLiquidity(candles)
        val recentSweeps = liquidity.asSequence()
            .filter { it.swept && (it.sweepIndex ?: -1) in rangeStart..last }
            .sortedByDescending { it.sweepIndex }
            .toList()
        if (recentSweeps.isEmpty()) {
            return LitAnalysis(symbol, timeframe, LitStage.LIQUIDITY_READY, null, "LIT: liquidity mapped; waiting for a reclaiming sweep.")
        }

        val structure = analyzeStructure(candles)
        val displacement = displacementDetector.detectLatest(candles, rules.displacementAtr)
        val zone = premiumDiscount.calculate(candles)
        val orderBlocks = smcDetector.detectOrderBlocks(candles)
        val fvgs = smcDetector.detectFairValueGaps(candles)

        // Work newest-to-oldest but accept only a fully ordered institutional sequence.
        for (sweep in recentSweeps) {
            val sweepIndex = sweep.sweepIndex ?: continue
            val direction = if (sweep.type == LiquidityType.SELL_SIDE) Direction.BULLISH else Direction.BEARISH
            if (!isReclaimingSweep(candles, sweepIndex, sweep.price, direction, avgRange)) continue

            val shift = structure.breaks.lastOrNull { br ->
                br.confirmed &&
                    br.direction == direction &&
                    br.type in setOf(StructureBreakType.CHOCH, StructureBreakType.MSS) &&
                    br.breakIndex >= sweepIndex &&
                    br.breakIndex - sweepIndex <= rules.maxSweepToShiftBars
            } ?: continue

            // AnalyzeMarketStructure confirms a swing only after its right-hand bars.
            // Its default lookback is 5, so this is the first bar the shift can be used.
            val shiftConfirmationIndex = shift.breakIndex + STRUCTURE_RIGHT_BARS
            if (shiftConfirmationIndex > last) continue

            val alignedDisplacement = displacement?.takeIf {
                it.direction == direction &&
                    it.atrMultiple >= rules.displacementAtr &&
                    it.startIndex in shift.breakIndex..minOf(
                        shiftConfirmationIndex,
                        shift.breakIndex + MAX_DISPLACEMENT_GAP_BARS,
                    )
            } ?: continue

            val poi = selectPoi(direction, sweepIndex, shiftConfirmationIndex, orderBlocks, fvgs) ?: continue
            val retestStart = maxOf(shiftConfirmationIndex + 1, poi.availableIndex + 1)
            if (retestStart > last) {
                return LitAnalysis(symbol, timeframe, LitStage.RETEST_READY, null, "LIT: shift confirmed; waiting for the first POI retest.")
            }
            val retestIndex = (retestStart..last).firstOrNull { idx -> overlaps(candles[idx], poi) } ?: continue
            if (retestIndex - shiftConfirmationIndex > rules.maxShiftToRetestBars) continue

            // One-shot signal: only the first confirmed retest may fire. Re-running
            // on later candles therefore cannot duplicate/repaint this setup.
            if (retestIndex != last) {
                return LitAnalysis(symbol, timeframe, LitStage.RETEST_READY, null, "LIT setup already retested; waiting for a fresh sequence.")
            }

            val entry = candles[retestIndex].close
            val stop = when (direction) {
                Direction.BULLISH -> poi.low - avgRange * STOP_BUFFER_RANGE
                Direction.BEARISH -> poi.high + avgRange * STOP_BUFFER_RANGE
            }
            val risk = abs(entry - stop)
            if (risk <= 0.0 || !risk.isFinite()) continue
            val structuralTarget = when (direction) {
                Direction.BULLISH -> candles.takeLast(TARGET_LOOKBACK).maxOf { it.high }
                Direction.BEARISH -> candles.takeLast(TARGET_LOOKBACK).minOf { it.low }
            }
            val reward = when (direction) {
                Direction.BULLISH -> structuralTarget - entry
                Direction.BEARISH -> entry - structuralTarget
            }
            if (reward <= 0.0 || !reward.isFinite()) continue
            val rr = reward / risk
            if (rr < rules.minRr) continue

            val directionalZone = when (direction) {
                Direction.BULLISH -> zone?.currentZone == PriceZoneKind.DISCOUNT
                Direction.BEARISH -> zone?.currentZone == PriceZoneKind.PREMIUM
            }
            if (cfg.requireDirectionalZone && zone != null && !directionalZone) continue

            val score = score(
                reclaimQuality = reclaimQuality(candles[sweepIndex], sweep.price, direction, avgRange),
                displacementQuality = ((alignedDisplacement.atrMultiple / 2.0) * 100.0).roundToInt().coerceIn(45, 100),
                poiQuality = poi.quality,
                zoneAligned = directionalZone,
                rr = rr,
                shiftType = shift.type,
                speedBars = retestIndex - sweepIndex,
            )
            if (score < cfg.minConfidence) continue

            val confirmations = buildList {
                add("LIQUIDITY_SWEEP")
                add(if (shift.type == StructureBreakType.MSS) "MSS" else "CHOCH")
                add("DISPLACEMENT")
                add(poi.label.uppercase().replace(' ', '_'))
                add("POI_RETEST")
                if (directionalZone) add("PREMIUM_DISCOUNT")
                add("RR_${"%.2f".format(rr)}")
            }
            val signal = LitSignal(
                symbol = symbol,
                timeframe = timeframe,
                direction = direction,
                entry = entry,
                stopLoss = stop,
                takeProfit = structuralTarget,
                confidence = score,
                sweepIndex = sweepIndex,
                shiftIndex = shift.breakIndex,
                confirmationIndex = retestIndex,
                timestamp = candles[retestIndex].timestamp,
                confirmations = confirmations,
                rationale = "LIT ${direction.name.lowercase()}: reclaiming liquidity sweep → ${shift.type.name} → displacement → ${poi.label} retest.",
            )
            return LitAnalysis(symbol, timeframe, LitStage.VALIDATED, signal, signal.rationale)
        }

        val latestSweep = recentSweeps.firstOrNull()
        return LitAnalysis(
            symbol = symbol,
            timeframe = timeframe,
            stage = if (latestSweep != null) LitStage.SWEEP_CONFIRMED else LitStage.LIQUIDITY_READY,
            signal = null,
            narrative = "LIT: a sweep exists, but the ordered shift/displacement/POI sequence is not fully confirmed.",
        )
    }

    private fun isReclaimingSweep(
        candles: List<Candle>, index: Int, level: Double, direction: Direction, avgRange: Double,
    ): Boolean {
        val c = candles.getOrNull(index) ?: return false
        val minimumPenetration = avgRange * MIN_SWEEP_PENETRATION_RANGE
        return when (direction) {
            Direction.BULLISH -> c.low < level - minimumPenetration && c.close > level
            Direction.BEARISH -> c.high > level + minimumPenetration && c.close < level
        }
    }

    private fun reclaimQuality(c: Candle, level: Double, direction: Direction, avgRange: Double): Int {
        val penetration = when (direction) {
            Direction.BULLISH -> level - c.low
            Direction.BEARISH -> c.high - level
        }.coerceAtLeast(0.0)
        val wick = when (direction) {
            Direction.BULLISH -> minOf(c.open, c.close) - c.low
            Direction.BEARISH -> c.high - maxOf(c.open, c.close)
        }.coerceAtLeast(0.0)
        val penetrationScore = (penetration / avgRange * 55.0).roundToInt().coerceIn(0, 55)
        val wickScore = (wick / c.range.coerceAtLeast(1e-9) * 45.0).roundToInt().coerceIn(0, 45)
        return (penetrationScore + wickScore).coerceIn(35, 100)
    }

    private fun selectPoi(
        direction: Direction,
        sweepIndex: Int,
        shiftConfirmationIndex: Int,
        orderBlocks: List<com.foxtrader.app.domain.model.OrderBlock>,
        fvgs: List<com.foxtrader.app.domain.model.FairValueGap>,
    ): Poi? {
        val obType = if (direction == Direction.BULLISH) OrderBlockType.BULLISH else OrderBlockType.BEARISH
        orderBlocks.asSequence()
            .filter { !it.mitigated && it.type == obType }
            .filter { it.startIndex in sweepIndex..shiftConfirmationIndex }
            .maxByOrNull { it.startIndex }
            ?.let { return Poi(it.lowPrice, it.highPrice, it.startIndex, it.endIndex, 88, "Order block") }

        val fvgType = if (direction == Direction.BULLISH) FvgType.BULLISH else FvgType.BEARISH
        fvgs.asSequence()
            .filter { !it.filled && it.type == fvgType }
            .filter { it.index in sweepIndex..shiftConfirmationIndex }
            .maxByOrNull { it.index }
            ?.let { return Poi(it.lowPrice, it.highPrice, it.index, it.index + 1, 78, "Fair value gap") }
        return null
    }

    private fun overlaps(candle: Candle, poi: Poi): Boolean = candle.low <= poi.high && candle.high >= poi.low

    private fun score(
        reclaimQuality: Int,
        displacementQuality: Int,
        poiQuality: Int,
        zoneAligned: Boolean,
        rr: Double,
        shiftType: StructureBreakType,
        speedBars: Int,
    ): Int {
        val structure = if (shiftType == StructureBreakType.MSS) 96 else 82
        val zone = if (zoneAligned) 92 else 55
        val rrScore = (rr / 3.0 * 100.0).roundToInt().coerceIn(35, 100)
        val speed = (100 - speedBars * 4).coerceIn(45, 100)
        val weighted = reclaimQuality * 0.18 + structure * 0.18 + displacementQuality * 0.18 +
            poiQuality * 0.15 + zone * 0.10 + rrScore * 0.13 + speed * 0.08
        return weighted.roundToInt().coerceIn(0, 100)
    }

    private fun rules(profile: SignalProfile): ProfileRules = when (profile) {
        SignalProfile.SCALPING -> ProfileRules(28, 7, 7, 1.8, 1.15)
        SignalProfile.INTRADAY -> ProfileRules(40, 10, 12, 2.0, 1.25)
        SignalProfile.SWING -> ProfileRules(70, 16, 20, 2.3, 1.35)
    }

    private companion object {
        const val MIN_BARS = 60
        const val ATR_WINDOW = 14
        const val TARGET_LOOKBACK = 60
        const val STRUCTURE_RIGHT_BARS = 5
        const val MAX_DISPLACEMENT_GAP_BARS = 6
        const val STOP_BUFFER_RANGE = 0.20
        const val MIN_SWEEP_PENETRATION_RANGE = 0.03
    }
}
