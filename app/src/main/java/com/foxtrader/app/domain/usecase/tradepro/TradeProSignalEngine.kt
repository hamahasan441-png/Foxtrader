package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.AbsorptionEvent
import com.foxtrader.app.domain.model.tradepro.FlipZone
import com.foxtrader.app.domain.model.tradepro.HoldZone
import com.foxtrader.app.domain.model.tradepro.HoldZoneType
import com.foxtrader.app.domain.model.tradepro.Imbalance
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.TradeProSetup
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TRADEPRO trade-setup engine. Runs the framework's daily blueprint end-to-end:
 *
 * Identify levels -> refine with structure/Flip Zone -> qualify with order flow (imbalance / absorption
 * / Buy-Sell Hold zones) -> stage the setup through **Level -> Zone -> Confirmation -> Execute**.
 *
 * Golden rule enforced here: if price has not pulled back into the zone, there is no executable trade —
 * no chasing. Only [SetupStage.EXECUTE] setups are tradable; earlier stages are informational previews.
 *
 * All dependencies are pure-domain and have `@Inject` constructors, so Hilt provides this engine with no
 * DI module needed. Order flow is read through [CandleDerivedOrderFlowProvider]; swap in a tape-backed
 * provider to lift signal fidelity without touching this logic.
 */
@Singleton
class TradeProSignalEngine @Inject constructor(
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val flipZoneEngine: FlipZoneEngine,
    private val orderFlowProvider: CandleDerivedOrderFlowProvider,
    private val imbalanceDetector: ImbalanceDetector,
    private val absorptionDetector: AbsorptionDetector,
    private val holdZoneEngine: HoldZoneEngine,
    private val riskGuard: TradeProRiskGuard,
) {

    fun analyze(
        symbol: String,
        candles: List<Candle>,
        config: TradeProConfig = TradeProConfig(),
    ): TradeProAnalysis {
        if (candles.size < MIN_BARS) {
            return TradeProAnalysis.empty(symbol, "Need at least $MIN_BARS bars for a TRADEPRO read.")
        }
        val currentPrice = candles.last().close
        if (!currentPrice.isFinite()) {
            return TradeProAnalysis.empty(symbol, "Latest price is not finite.")
        }

        val structure = analyzeStructure(candles, config.swingLookback, config.swingLookback)
        val flipZone = flipZoneEngine.compute(structure)

        val bars = orderFlowProvider.toOrderFlow(candles)
        val imbalances = imbalanceDetector.detect(bars, config.imbalanceRatio)
        val absorptions = absorptionDetector.detect(bars)
        val holdZones = holdZoneEngine.build(bars, imbalances)

        if (flipZone == null || flipZone.bias == Bias.NEUTRAL) {
            return TradeProAnalysis(
                symbol, flipZone, holdZones, imbalances, absorptions,
                setup = null, stage = SetupStage.NONE,
                narrative = "No Flip Zone yet — structure is neutral. Stand aside until bias is defined.",
            )
        }

        val direction = if (flipZone.bias == Bias.BULLISH) Direction.BULLISH else Direction.BEARISH
        val zone = selectAlignedZone(holdZones, direction, currentPrice)
        if (zone == null) {
            return TradeProAnalysis(
                symbol, flipZone, holdZones, imbalances, absorptions,
                setup = null, stage = SetupStage.LEVEL,
                narrative = "$direction bias set by Flip Zone at ${fmt(flipZone.price)}; " +
                    "no defended ${holdLabel(direction)} zone to trade yet.",
            )
        }

        val priceInZone = zone.contains(currentPrice)
        val recentImbalance = imbalances.any { it.direction == direction && it.index >= bars.size - CONFIRM_WINDOW }
        val absorptionReversal = absorptions.any {
            it.absorbedSide == opposite(direction) && it.index >= bars.size - CONFIRM_WINDOW
        }
        val lastCandleReacts = if (direction == Direction.BULLISH) {
            candles.last().close >= candles.last().open
        } else {
            candles.last().close <= candles.last().open
        }
        val confirmed = priceInZone && (recentImbalance || absorptionReversal || (zone.defended && lastCandleReacts))

        val stage = when {
            !priceInZone -> SetupStage.LEVEL
            confirmed -> SetupStage.EXECUTE
            else -> SetupStage.ZONE
        }

        val setup = buildSetup(
            symbol, direction, stage, currentPrice, priceInZone, zone, flipZone,
            structure.swingHighs, structure.swingLows, recentImbalance, absorptionReversal, config,
        )

        return TradeProAnalysis(
            symbol, flipZone, holdZones, imbalances, absorptions,
            setup = setup, stage = stage, narrative = setup.note,
        )
    }

    private fun selectAlignedZone(
        holdZones: List<HoldZone>,
        direction: Direction,
        currentPrice: Double,
    ): HoldZone? {
        val wanted = if (direction == Direction.BULLISH) HoldZoneType.BUY_HOLD else HoldZoneType.SELL_HOLD
        val aligned = holdZones.filter { it.type == wanted }
        if (aligned.isEmpty()) return null
        aligned.firstOrNull { it.contains(currentPrice) }?.let { return it }
        return if (direction == Direction.BULLISH) {
            // buy-hold below price: the nearest one price can pull back down into.
            aligned.filter { it.high <= currentPrice }.maxByOrNull { it.high } ?: aligned.maxByOrNull { it.high }
        } else {
            // sell-hold above price: the nearest one price can rally up into.
            aligned.filter { it.low >= currentPrice }.minByOrNull { it.low } ?: aligned.minByOrNull { it.low }
        }
    }

    @Suppress("LongParameterList")
    private fun buildSetup(
        symbol: String,
        direction: Direction,
        stage: SetupStage,
        currentPrice: Double,
        priceInZone: Boolean,
        zone: HoldZone,
        flipZone: FlipZone,
        swingHighs: List<com.foxtrader.app.domain.model.SwingPoint>,
        swingLows: List<com.foxtrader.app.domain.model.SwingPoint>,
        recentImbalance: Boolean,
        absorptionReversal: Boolean,
        config: TradeProConfig,
    ): TradeProSetup {
        val entry = when {
            priceInZone -> currentPrice
            direction == Direction.BULLISH -> zone.high
            else -> zone.low
        }
        val stop = riskGuard.structuralStop(entry, direction, zone, config)
        val magnet = if (direction == Direction.BULLISH) {
            swingHighs.map { it.price }.filter { it > entry }.minOrNull()
        } else {
            swingLows.map { it.price }.filter { it < entry }.maxOrNull()
        }
        val targets = riskGuard.targets(entry, direction, config, magnet)

        val riskDistance = abs(entry - stop)
        val riskPoints = riskDistance / config.pointSize
        val rewardDistance = abs(targets.t2 - entry)
        val riskReward = if (riskDistance > 0.0) rewardDistance / riskDistance else 0.0
        val plan = riskGuard.buildManagementPlan(config, stopPoints = riskPoints.coerceAtLeast(config.stopPoints))

        val confluences = buildList {
            add("FLIP_ZONE_${direction}")
            add(zone.type.name)
            if (zone.stackedCount >= 2) add("STACKED_IMBALANCE_x${zone.stackedCount}")
            if (zone.defended) add("ZONE_DEFENDED")
            if (recentImbalance) add("ORDER_FLOW_IMBALANCE")
            if (absorptionReversal) add("ABSORPTION_REVERSAL")
        }

        var confidence = 40.0
        confidence += zone.strength * 0.3
        if (recentImbalance) confidence += 12.0
        if (absorptionReversal) confidence += 12.0
        if (zone.defended) confidence += 8.0
        if (riskPoints <= config.maxRiskPoints) confidence += 6.0 else confidence -= 10.0
        val confidenceInt = confidence.coerceIn(0.0, 100.0).toInt()

        val note = when (stage) {
            SetupStage.EXECUTE -> "$direction TRADEPRO setup confirmed inside ${holdLabel(direction)} " +
                "zone [${fmt(zone.low)}-${fmt(zone.high)}]. Entry ${fmt(entry)}, stop ${fmt(stop)} " +
                "(${"%.1f".format(riskPoints)} pts), T1 ${fmt(targets.t1)}, T2 ${fmt(targets.t2)}, " +
                "runner ${fmt(targets.runner)}."
            SetupStage.ZONE -> "Price in ${holdLabel(direction)} zone; awaiting order-flow confirmation. " +
                "No trade until confirmed."
            else -> "$direction bias; waiting for pullback into ${holdLabel(direction)} zone " +
                "[${fmt(zone.low)}-${fmt(zone.high)}]. No chasing."
        }

        return TradeProSetup(
            symbol = symbol,
            direction = direction,
            stage = stage,
            entry = entry,
            stopLoss = stop,
            target1 = targets.t1,
            target2 = targets.t2,
            runnerTarget = targets.runner,
            riskPoints = riskPoints,
            riskReward = riskReward,
            confidence = confidenceInt,
            flipZone = flipZone,
            holdZone = zone,
            managementPlan = plan,
            confluences = confluences,
            note = note,
        )
    }

    private fun opposite(direction: Direction): Direction =
        if (direction == Direction.BULLISH) Direction.BEARISH else Direction.BULLISH

    private fun holdLabel(direction: Direction): String =
        if (direction == Direction.BULLISH) "Buy-Hold" else "Sell-Hold"

    private fun fmt(v: Double): String = if (v.isFinite()) "%.2f".format(v) else "n/a"

    private companion object {
        const val MIN_BARS = 30
        const val CONFIRM_WINDOW = 3
    }
}
