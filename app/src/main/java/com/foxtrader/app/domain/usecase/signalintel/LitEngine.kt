package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitLevel
import com.foxtrader.app.domain.model.LitPoiZone
import com.foxtrader.app.domain.model.LitProContext
import com.foxtrader.app.domain.model.LitScob
import com.foxtrader.app.domain.model.LitSignal
import com.foxtrader.app.domain.model.LitStage
import com.foxtrader.app.domain.model.PriceZoneKind
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
 * LiT Pro — confirmed-bar Liquidity Inducement Theory execution engine.
 *
 * The previous Phase-13 implementation treated LIT mostly as a liquidity sweep
 * followed by CHOCH/MSS. LiT Pro makes the structural lifecycle explicit:
 *
 *   Pullback -> IDM sweep/reclaim -> BOS -> CHOCH -> POI -> SCOB -> retest entry
 *
 * Every event carries an origin index and the first bar where it is objectively
 * knowable. The engine only emits on the first confirmed POI retest, so replay,
 * live chart and scanner use the same non-repainting decision boundary.
 *
 * Chronology is gated by [LitSequenceValidator]. This keeps the repository's
 * implemented LiT sequence deterministic and prevents a stale/out-of-order IDM,
 * BOS or CHOCH from being rescued by later confluence or confidence scoring.
 */
@Singleton
@Suppress("UNUSED_PARAMETER")
class LitEngine @Inject constructor(
    // Kept in the constructor for binary/source compatibility with Phase-13
    // manual construction in LitAgent and existing DI wiring.
    smcDetector: SmcDetector,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val displacementDetector: DisplacementDetector,
    private val premiumDiscount: PremiumDiscountCalculator,
    private val structureDetector: LitProStructureDetector = LitProStructureDetector(),
    private val sequenceValidator: LitSequenceValidator = LitSequenceValidator(),
) {

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        config: LitConfig = LitConfig(),
    ): LitAnalysis {
        val integrity = SignalSeriesIntegrity.validate(candles, MIN_BARS)
        if (!integrity.valid) {
            return LitAnalysis.empty(symbol, timeframe, integrity.reason ?: "Invalid market data.")
        }

        val cfg = config.sanitized()
        val context = structureDetector.detect(candles, cfg)
        val baseStage = stageFor(context)

        val choch = context.choch
            ?: return result(symbol, timeframe, baseStage, context, narrativeFor(baseStage, context))

        // Enforce the repository-defined causal sequence before any POI,
        // displacement, R:R or confidence logic is allowed to validate a trade.
        // This is deliberately a hard gate: confluence cannot compensate for an
        // impossible chronology.
        val sequence = sequenceValidator.validate(context, cfg)
        if (!sequence.valid) {
            return result(
                symbol,
                timeframe,
                sequence.stage,
                context,
                "LiT Pro: sequence rejected — ${sequence.reason}.",
            )
        }

        val bos = context.bos
            ?: return result(
                symbol,
                timeframe,
                LitStage.IDM_CONFIRMED,
                context,
                "LiT Pro: sequence validator passed without BOS; waiting for a fresh sequence.",
            )
        val idm = context.inducement
            ?: return result(
                symbol,
                timeframe,
                LitStage.SCANNING,
                context,
                "LiT Pro: sequence validator passed without IDM; waiting for a fresh sequence.",
            )

        val poi = context.poi
            ?.takeIf { it.confirmationIndex == choch.confirmationIndex && it.direction == choch.direction }
            ?: return result(
                symbol,
                timeframe,
                LitStage.CHOCH_CONFIRMED,
                context,
                "LiT Pro: CHOCH confirmed; waiting for a valid post-shift POI.",
            )

        val volatility = averageRange(candles, choch.confirmationIndex)
            ?: return result(symbol, timeframe, baseStage, context, "LiT Pro: volatility is not measurable.")

        // Freeze displacement evidence at the CHOCH confirmation boundary. A
        // future impulse is never allowed to retroactively validate an old shift.
        val throughChoch = candles.subList(0, choch.confirmationIndex + 1)
        val displacement = displacementDetector.detectLatest(
            candles = throughChoch,
            atrMultiple = cfg.displacementAtrMultiple,
            lookback = DISPLACEMENT_LOOKBACK,
        )
        val displacementAligned = displacement != null &&
            displacement.direction == choch.direction &&
            displacement.startIndex in (choch.confirmationIndex - MAX_DISPLACEMENT_LEAD_BARS)
                .coerceAtLeast(0)..choch.confirmationIndex
        if (!displacementAligned) {
            return result(
                symbol,
                timeframe,
                LitStage.CHOCH_CONFIRMED,
                context,
                "LiT Pro: structure shifted; waiting for aligned displacement confirmation.",
            )
        }

        val retestStart = choch.confirmationIndex + 1
        val retestEnd = minOf(candles.lastIndex, choch.confirmationIndex + cfg.maxPoiAgeBars)
        if (retestStart > retestEnd) {
            return result(symbol, timeframe, LitStage.POI_READY, context, "LiT Pro: POI ready; waiting for first mitigation.")
        }
        val retestIndex = (retestStart..retestEnd).firstOrNull { index -> overlaps(candles[index], poi) }
            ?: return result(symbol, timeframe, LitStage.POI_READY, context, "LiT Pro: POI ready; waiting for first mitigation.")

        // One-shot/non-repaint contract. If the first retest is already behind
        // the newest confirmed candle, the setup has been consumed and cannot
        // print a duplicate arrow on later bars.
        if (retestIndex != candles.lastIndex) {
            return result(
                symbol,
                timeframe,
                LitStage.RETEST_READY,
                context,
                "LiT Pro: first POI retest already occurred; waiting for a fresh structural sequence.",
            )
        }

        val scob = context.scob?.takeIf {
            it.direction == choch.direction && it.confirmationIndex == retestIndex
        }
        if (cfg.requireScob && scob == null) {
            return result(
                symbol,
                timeframe,
                LitStage.POI_READY,
                context,
                "LiT Pro: POI retested; waiting for SCOB rejection confirmation.",
            )
        }

        val entry = candles[retestIndex].close
        val stop = stopPrice(choch.direction, poi, scob, volatility, cfg.stopAtrBuffer)
        if (!entry.isFinite() || entry <= 0.0 || !stop.isFinite() || stop <= 0.0) {
            return result(symbol, timeframe, LitStage.RETEST_READY, context, "LiT Pro: invalid entry/risk geometry.")
        }
        val risk = abs(entry - stop)
        if (!risk.isFinite() || risk <= MIN_PRICE_EPSILON) {
            return result(symbol, timeframe, LitStage.RETEST_READY, context, "LiT Pro: risk distance is too small.")
        }

        val structure = analyzeStructure(
            candles = candles.subList(0, retestIndex + 1),
            leftBars = cfg.swingLeftBars,
            rightBars = cfg.swingRightBars,
        )
        val target = structuralTarget(choch.direction, entry, structure, candles, choch.confirmationIndex)
            ?: return result(symbol, timeframe, LitStage.RETEST_READY, context, "LiT Pro: no valid opposing liquidity target.")
        val reward = when (choch.direction) {
            Direction.BULLISH -> target - entry
            Direction.BEARISH -> entry - target
        }
        if (!reward.isFinite() || reward <= 0.0) {
            return result(symbol, timeframe, LitStage.RETEST_READY, context, "LiT Pro: structural target is on the wrong side.")
        }
        val rr = reward / risk
        if (rr < cfg.minRiskReward) {
            return result(
                symbol,
                timeframe,
                LitStage.RETEST_READY,
                context,
                "LiT Pro: setup rejected by minimum R:R (${format2(rr)} < ${format2(cfg.minRiskReward)}).",
            )
        }

        // Premium/discount is evaluated with bars available at the entry. It is
        // never recalculated from bars that occur after the signal timestamp.
        val zone = premiumDiscount.calculate(candles.subList(0, retestIndex + 1))
        val directionalZone = when (choch.direction) {
            Direction.BULLISH -> zone?.currentZone == PriceZoneKind.DISCOUNT
            Direction.BEARISH -> zone?.currentZone == PriceZoneKind.PREMIUM
        }
        if (cfg.requireDirectionalZone && zone != null && !directionalZone) {
            return result(
                symbol,
                timeframe,
                LitStage.RETEST_READY,
                context,
                "LiT Pro: POI retest is outside the required premium/discount side.",
            )
        }

        val score = score(
            idm = idm,
            bos = bos,
            choch = choch,
            poi = poi,
            scob = scob,
            displacementAtr = displacement!!.atrMultiple,
            zoneAligned = directionalZone,
            rr = rr,
        )
        if (score < cfg.minConfidence) {
            return result(
                symbol,
                timeframe,
                LitStage.RETEST_READY,
                context,
                "LiT Pro: setup quality $score is below minimum ${cfg.minConfidence}.",
            )
        }

        val confirmations = buildList {
            if (context.pullback != null) add("PULLBACK")
            add("IDM")
            add("BOS")
            add("CHOCH")
            add("SEQUENCE_VALIDATED")
            add("DISPLACEMENT")
            add("POI_${poi.kind.name}")
            if (scob != null) add("SCOB")
            if (directionalZone) add("PREMIUM_DISCOUNT")
            add("RR_${format2(rr)}")
            add("NON_REPAINT")
        }
        val rationale = "LiT Pro ${choch.direction.name.lowercase()}: validated IDM -> opposite BOS -> " +
            "CHOCH + displacement -> ${poi.kind.name.lowercase()} POI" +
            (if (scob != null) " -> SCOB" else "") + " -> first retest."
        val signal = LitSignal(
            symbol = symbol,
            timeframe = timeframe,
            direction = choch.direction,
            entry = entry,
            stopLoss = stop,
            takeProfit = target,
            confidence = score,
            sweepIndex = idm.confirmationIndex,
            shiftIndex = choch.confirmationIndex,
            confirmationIndex = retestIndex,
            timestamp = candles[retestIndex].timestamp,
            confirmations = confirmations,
            rationale = rationale,
        )
        return LitAnalysis(
            symbol = symbol,
            timeframe = timeframe,
            stage = LitStage.VALIDATED,
            signal = signal,
            narrative = rationale,
            context = context,
        )
    }

    private fun stageFor(context: LitProContext): LitStage = when {
        context.scob != null -> LitStage.SCOB_READY
        context.poi != null -> LitStage.POI_READY
        context.choch != null -> LitStage.CHOCH_CONFIRMED
        context.bos != null -> LitStage.BOS_CONFIRMED
        context.inducement != null -> LitStage.IDM_CONFIRMED
        context.pullback != null -> LitStage.PULLBACK_READY
        else -> LitStage.SCANNING
    }

    private fun narrativeFor(stage: LitStage, context: LitProContext): String = when (stage) {
        LitStage.PULLBACK_READY -> "LiT Pro: pullback mapped; waiting for inducement and structural confirmation."
        LitStage.IDM_CONFIRMED -> "LiT Pro: IDM sweep/reclaim confirmed; waiting for BOS/CHOCH sequence."
        LitStage.BOS_CONFIRMED -> "LiT Pro: BOS confirmed; waiting for opposite CHOCH."
        LitStage.CHOCH_CONFIRMED -> "LiT Pro: CHOCH confirmed; mapping execution POI."
        LitStage.POI_READY -> "LiT Pro: ${context.poi?.kind?.name ?: "POI"} ready; waiting for first retest."
        LitStage.SCOB_READY -> "LiT Pro: SCOB context confirmed; waiting for execution-quality retest."
        LitStage.RETEST_READY -> "LiT Pro: retest context mapped."
        LitStage.VALIDATED -> "LiT Pro: setup validated."
        LitStage.SCANNING,
        LitStage.LIQUIDITY_READY,
        LitStage.SWEEP_CONFIRMED,
        LitStage.SHIFT_CONFIRMED -> context.notes.lastOrNull() ?: "LiT Pro: scanning confirmed structure."
    }

    private fun result(
        symbol: String,
        timeframe: Timeframe,
        stage: LitStage,
        context: LitProContext,
        narrative: String,
    ) = LitAnalysis(symbol, timeframe, stage, null, narrative, context)

    private fun stopPrice(
        direction: Direction,
        poi: LitPoiZone,
        scob: LitScob?,
        volatility: Double,
        atrBuffer: Double,
    ): Double {
        val buffer = volatility * atrBuffer
        return when (direction) {
            Direction.BULLISH -> minOf(poi.low, scob?.low ?: poi.low) - buffer
            Direction.BEARISH -> maxOf(poi.high, scob?.high ?: poi.high) + buffer
        }
    }

    private fun structuralTarget(
        direction: Direction,
        entry: Double,
        structure: com.foxtrader.app.domain.model.MarketStructure,
        candles: List<Candle>,
        shiftIndex: Int,
    ): Double? {
        val swingTarget = when (direction) {
            Direction.BULLISH -> structure.swingHighs
                .asReversed()
                .firstOrNull { it.price > entry && it.index <= shiftIndex }
                ?.price
            Direction.BEARISH -> structure.swingLows
                .asReversed()
                .firstOrNull { it.price < entry && it.index <= shiftIndex }
                ?.price
        }
        if (swingTarget != null && swingTarget.isFinite() && swingTarget > 0.0) return swingTarget

        val start = (shiftIndex - TARGET_LOOKBACK).coerceAtLeast(0)
        val end = shiftIndex.coerceIn(start, candles.lastIndex)
        if (start > end) return null
        return when (direction) {
            Direction.BULLISH -> (start..end).maxOfOrNull { candles[it].high }?.takeIf { it > entry }
            Direction.BEARISH -> (start..end).minOfOrNull { candles[it].low }?.takeIf { it < entry }
        }
    }

    private fun score(
        idm: LitLevel,
        bos: LitLevel,
        choch: LitLevel,
        poi: LitPoiZone,
        scob: LitScob?,
        displacementAtr: Double,
        zoneAligned: Boolean,
        rr: Double,
    ): Int {
        val chronologyBars = choch.confirmationIndex - idm.confirmationIndex
        val chronologyScore = (100 - chronologyBars * 3).coerceIn(50, 100)
        val bosChochBars = choch.confirmationIndex - bos.confirmationIndex
        val transitionScore = (100 - bosChochBars * 4).coerceIn(45, 100)
        val displacementScore = ((displacementAtr / 2.0) * 100.0).roundToInt().coerceIn(45, 100)
        val scobScore = scob?.quality ?: 60
        val zoneScore = if (zoneAligned) 92 else 58
        val rrScore = ((rr / 3.0) * 100.0).roundToInt().coerceIn(40, 100)
        val weighted = chronologyScore * 0.13 + transitionScore * 0.13 + displacementScore * 0.18 +
            poi.quality * 0.18 + scobScore * 0.12 + zoneScore * 0.10 + rrScore * 0.16
        return weighted.roundToInt().coerceIn(0, 100)
    }

    private fun averageRange(candles: List<Candle>, endIndex: Int): Double? {
        val start = (endIndex - ATR_WINDOW + 1).coerceAtLeast(0)
        val end = endIndex.coerceAtMost(candles.lastIndex)
        if (start > end) return null
        val values = (start..end).map { candles[it].range }.filter { it.isFinite() && it > 0.0 }
        if (values.isEmpty()) return null
        return values.average().takeIf { it.isFinite() && it > 0.0 }
    }

    private fun overlaps(candle: Candle, poi: LitPoiZone): Boolean =
        poi.low.isFinite() && poi.high.isFinite() && poi.high > poi.low &&
            candle.low <= poi.high && candle.high >= poi.low

    private fun format2(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    private companion object {
        const val MIN_BARS = 60
        const val ATR_WINDOW = 14
        const val TARGET_LOOKBACK = 80
        const val DISPLACEMENT_LOOKBACK = 8
        const val MAX_DISPLACEMENT_LEAD_BARS = 3
        const val MIN_PRICE_EPSILON = 1e-9
    }
}
