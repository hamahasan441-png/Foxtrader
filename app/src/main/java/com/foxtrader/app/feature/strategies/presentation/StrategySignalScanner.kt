package com.foxtrader.app.feature.strategies.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.analysis.DivergenceDetector
import com.foxtrader.app.domain.usecase.analysis.RiskRewardOptimizer
import com.foxtrader.app.domain.usecase.analysis.WyckoffDetector
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.patterns.CandlePatternDetector
import com.foxtrader.app.domain.usecase.patterns.HarmonicPatternDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.tradepro.TradeProSignalEngine
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

/**
 * Generates actionable strategy setups for a symbol from the analysis engines
 * (harmonics, SMC order blocks / FVGs / liquidity, candlestick patterns, RSI
 * divergence, Wyckoff, Ichimoku, and a LIT institutional-entry confluence).
 *
 * This logic used to live inside [StrategiesViewModel]. It was extracted here so
 * it is a single-responsibility, framework-free unit that can be tested directly
 * without a ViewModel or coroutine scope — the ViewModel now only orchestrates
 * scanning and state. Behavior is unchanged from the previous in-ViewModel path.
 */
class StrategySignalScanner @Inject constructor(
    private val harmonicDetector: HarmonicPatternDetector,
    private val candlePatternDetector: CandlePatternDetector,
    private val divergenceDetector: DivergenceDetector,
    private val smcDetector: SmcDetector,
    private val wyckoffDetector: WyckoffDetector,
    private val ichimokuCloud: IchimokuCloud,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val riskReward: RiskRewardOptimizer,
    private val tradeProEngine: TradeProSignalEngine,
) {

    fun detect(symbol: String, candles: List<Candle>): List<StrategySignalItem> {
        val out = mutableListOf<StrategySignalItem>()

        // --- Harmonic patterns ---
        harmonicDetector(candles).take(2).forEach { pattern ->
            out += StrategySignalItem(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                strategyName = "Harmonic ${formatEnumName(pattern.type.name)}",
                direction = pattern.direction,
                confidence = pattern.score.toInt(),
                entry = pattern.dPrice,
                stopLoss = pattern.stopLoss,
                takeProfit = pattern.tp1,
                riskReward = rr(pattern.dPrice, pattern.stopLoss, pattern.tp1),
                signalProvider = "Harmonic Engine",
                note = "PRZ ${"%.5f".format(pattern.prz.first)} - ${"%.5f".format(pattern.prz.second)}",
            )
        }

        // --- Order blocks (unmitigated) ---
        smcDetector.detectOrderBlocks(candles).filter { !it.mitigated }.take(1).forEach { ob ->
            val dir = if (ob.type == OrderBlockType.BULLISH) Direction.BULLISH else Direction.BEARISH
            val entry = (ob.highPrice + ob.lowPrice) / 2.0
            val sl = if (dir == Direction.BULLISH) ob.lowPrice else ob.highPrice
            val tp = if (dir == Direction.BULLISH) entry + (entry - sl) * 2 else entry - (sl - entry) * 2
            out += StrategySignalItem(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                strategyName = "Order Block",
                direction = dir,
                confidence = (ob.strength * 100).toInt().coerceIn(0, 100),
                entry = entry, stopLoss = sl, takeProfit = tp,
                riskReward = rr(entry, sl, tp),
                signalProvider = "SMC Engine",
                note = "Institutional supply/demand zone",
            )
        }

        // --- Fair Value Gaps ---
        smcDetector.detectFairValueGaps(candles).filter { !it.filled }.take(1).forEach { fvg ->
            val dir = if (fvg.type == FvgType.BULLISH) Direction.BULLISH else Direction.BEARISH
            val entry = (fvg.highPrice + fvg.lowPrice) / 2.0
            val sl = if (dir == Direction.BULLISH) fvg.lowPrice else fvg.highPrice
            val tp = if (dir == Direction.BULLISH) entry + (entry - sl) * 2 else entry - (sl - entry) * 2
            out += StrategySignalItem(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                strategyName = "Fair Value Gap",
                direction = dir,
                confidence = (55 + (1.0 - fvg.fillPercent) * 35).toInt().coerceIn(0, 100),
                entry = entry,
                stopLoss = sl,
                takeProfit = tp,
                riskReward = rr(entry, sl, tp),
                signalProvider = "SMC Engine",
                note = "Unfilled ${formatEnumName(fvg.type.name)} imbalance",
            )
        }

        // --- Risk/Reward optimized setup aligned with bias ---
        val rrSetup = riskReward.optimize(candles, Direction.BULLISH)
        if (rrSetup.valid) {
            out += StrategySignalItem(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                strategyName = "R:R Optimized",
                direction = rrSetup.direction,
                confidence = 60,
                entry = rrSetup.entry,
                stopLoss = rrSetup.stopLoss,
                takeProfit = rrSetup.takeProfit1,
                riskReward = rrSetup.riskRewardRatio,
                signalProvider = "Risk Engine",
                note = rrSetup.reason,
            )
        }

        // --- Candlestick patterns ---
        candlePatternDetector(candles, lookback = 20)
            .filter { it.confidence >= 60 }
            .takeLast(1)
            .forEach { pattern ->
                val entry = candles[pattern.endIndex].close
                val atr = TechnicalIndicators.calculateATR(candles, 14)[candles.lastIndex]
                val sl = if (pattern.direction == Direction.BULLISH) entry - atr * 1.5 else entry + atr * 1.5
                val tp = if (pattern.direction == Direction.BULLISH) entry + atr * 3 else entry - atr * 3
                out += StrategySignalItem(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol,
                    strategyName = formatEnumName(pattern.type.name),
                    direction = pattern.direction,
                    confidence = pattern.confidence.coerceIn(0, 100),
                    entry = entry,
                    stopLoss = sl,
                    takeProfit = tp,
                    riskReward = rr(entry, sl, tp),
                    signalProvider = "Pattern Engine",
                    note = pattern.context,
                )
            }

        // --- RSI divergences ---
        divergenceDetector.detectRsiDivergences(candles)
            .takeLast(1)
            .forEach { divergence ->
                val dir = when (divergence.type) {
                    DivergenceDetector.DivergenceType.REGULAR_BULLISH,
                    DivergenceDetector.DivergenceType.HIDDEN_BULLISH -> Direction.BULLISH
                    DivergenceDetector.DivergenceType.REGULAR_BEARISH,
                    DivergenceDetector.DivergenceType.HIDDEN_BEARISH -> Direction.BEARISH
                }
                val entry = candles[divergence.endIndex].close
                val atr = TechnicalIndicators.calculateATR(candles, 14)[candles.lastIndex]
                val sl = if (dir == Direction.BULLISH) entry - atr * 1.5 else entry + atr * 1.5
                val tp = if (dir == Direction.BULLISH) entry + atr * 2.5 else entry - atr * 2.5
                val confidence = when (divergence.type) {
                    DivergenceDetector.DivergenceType.REGULAR_BULLISH,
                    DivergenceDetector.DivergenceType.REGULAR_BEARISH -> DIVERGENCE_REGULAR_CONFIDENCE
                    DivergenceDetector.DivergenceType.HIDDEN_BULLISH,
                    DivergenceDetector.DivergenceType.HIDDEN_BEARISH -> DIVERGENCE_HIDDEN_CONFIDENCE
                }
                out += StrategySignalItem(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol,
                    strategyName = "RSI Divergence",
                    direction = dir,
                    confidence = confidence,
                    entry = entry,
                    stopLoss = sl,
                    takeProfit = tp,
                    riskReward = rr(entry, sl, tp),
                    signalProvider = "Divergence Engine",
                    note = formatEnumName(divergence.type.name),
                )
            }

        // --- Wyckoff phase ---
        val wyckoff = wyckoffDetector.detect(candles)
        if (wyckoff.confidence >= 60 && wyckoff.phase != WyckoffDetector.WyckoffPhase.UNDEFINED) {
            val entry = candles.last().close
            val dir = when (wyckoff.phase) {
                WyckoffDetector.WyckoffPhase.ACCUMULATION,
                WyckoffDetector.WyckoffPhase.MARKUP -> Direction.BULLISH
                else -> Direction.BEARISH
            }
            val sl = if (dir == Direction.BULLISH) wyckoff.rangeLow else wyckoff.rangeHigh
            val tp = if (dir == Direction.BULLISH) entry + (entry - sl) * 2 else entry - (sl - entry) * 2
            out += StrategySignalItem(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                strategyName = "Wyckoff ${formatEnumName(wyckoff.phase.name)}",
                direction = dir,
                confidence = wyckoff.confidence.toInt().coerceIn(0, 100),
                entry = entry,
                stopLoss = sl,
                takeProfit = tp,
                riskReward = rr(entry, sl, tp),
                signalProvider = "Wyckoff Engine",
                note = wyckoff.description,
            )
        }

        // --- Ichimoku trend ---
        if (candles.size >= 52) {
            val ichimoku = ichimokuCloud.calculate(candles)
            val position = ichimokuCloud.cloudPosition(candles, ichimoku)
            if (position != IchimokuCloud.CloudPosition.INSIDE) {
                val entry = candles.last().close
                val dir = if (position == IchimokuCloud.CloudPosition.ABOVE) Direction.BULLISH else Direction.BEARISH
                val atr = TechnicalIndicators.calculateATR(candles, 14)[candles.lastIndex]
                val tkSpread = abs(ichimoku.tenkan[candles.lastIndex] - ichimoku.kijun[candles.lastIndex])
                val confidence = (55 + (tkSpread / atr.coerceAtLeast(1e-9) * 18)).toInt().coerceIn(55, 85)
                val sl = if (dir == Direction.BULLISH) entry - atr * 2 else entry + atr * 2
                val tp = if (dir == Direction.BULLISH) entry + atr * 3 else entry - atr * 3
                out += StrategySignalItem(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol,
                    strategyName = "Ichimoku Trend",
                    direction = dir,
                    confidence = confidence,
                    entry = entry,
                    stopLoss = sl,
                    takeProfit = tp,
                    riskReward = rr(entry, sl, tp),
                    signalProvider = "Ichimoku Engine",
                    note = "Price ${formatEnumName(position.name)} cloud",
                )
            }
        }

        // --- LIT institutional entry ---
        val lastIndex = candles.lastIndex
        val atr = TechnicalIndicators.calculateATR(candles, 14)[lastIndex]
        val liquiditySweep = smcDetector.detectLiquidity(candles)
            .filter { it.swept && it.sweepIndex != null }
            .maxByOrNull { it.sweepIndex ?: -1 }
        val sweepIndex = liquiditySweep?.sweepIndex
        val structureBreak = analyzeStructure(candles).breaks.lastOrNull { it.confirmed }
        if (liquiditySweep != null && sweepIndex != null && structureBreak != null) {
            val dir = if (liquiditySweep.type == LiquidityType.SELL_SIDE) Direction.BULLISH else Direction.BEARISH
            val sweepRecency = lastIndex - sweepIndex
            val breakRecency = lastIndex - structureBreak.breakIndex
            if (dir == structureBreak.direction && sweepRecency in 0..12 && breakRecency in 0..10) {
                val mitigationOb = smcDetector.detectOrderBlocks(candles).lastOrNull {
                    !it.mitigated &&
                        ((dir == Direction.BULLISH && it.type == OrderBlockType.BULLISH) ||
                            (dir == Direction.BEARISH && it.type == OrderBlockType.BEARISH))
                }
                val mitigationFvg = smcDetector.detectFairValueGaps(candles).lastOrNull {
                    !it.filled &&
                        ((dir == Direction.BULLISH && it.type == FvgType.BULLISH) ||
                            (dir == Direction.BEARISH && it.type == FvgType.BEARISH))
                }
                val entry = mitigationOb?.let { (it.highPrice + it.lowPrice) / 2.0 }
                    ?: mitigationFvg?.let { (it.highPrice + it.lowPrice) / 2.0 }
                if (entry != null && abs(candles.last().close - entry) <= atr * 0.75) {
                    val slBase = when {
                        mitigationOb != null && dir == Direction.BULLISH -> mitigationOb.lowPrice
                        mitigationOb != null && dir == Direction.BEARISH -> mitigationOb.highPrice
                        mitigationFvg != null && dir == Direction.BULLISH -> mitigationFvg.lowPrice
                        mitigationFvg != null && dir == Direction.BEARISH -> mitigationFvg.highPrice
                        dir == Direction.BULLISH -> entry - atr * 1.5
                        else -> entry + atr * 1.5
                    }
                    val sl = if (dir == Direction.BULLISH) slBase - atr * 0.15 else slBase + atr * 0.15
                    val tp = if (dir == Direction.BULLISH) entry + (entry - sl) * 3 else entry - (sl - entry) * 3
                    val structureShift = structureBreak.type == StructureBreakType.CHOCH ||
                        structureBreak.type == StructureBreakType.MSS
                    val confidence = (
                        LIT_BASE_CONFIDENCE +
                            (if (mitigationOb != null) LIT_ORDER_BLOCK_BONUS else 0) +
                            (if (mitigationFvg != null) LIT_FVG_BONUS else 0) +
                            (if (structureShift) LIT_STRUCTURE_SHIFT_BONUS else 0) +
                            ((12 - sweepRecency).coerceAtLeast(0) / 2) +
                            ((10 - breakRecency).coerceAtLeast(0) / 2)
                        ).coerceIn(0, LIT_MAX_CONFIDENCE)
                    out += StrategySignalItem(
                        id = UUID.randomUUID().toString(),
                        symbol = symbol,
                        strategyName = "LIT Institutional Entry",
                        direction = dir,
                        confidence = confidence,
                        entry = entry,
                        stopLoss = sl,
                        takeProfit = tp,
                        riskReward = rr(entry, sl, tp),
                        signalProvider = "LIT Signal Provider",
                        note = "Sweep + ${structureBreak.type.name} + mitigation retest",
                    )
                }
            }
        }

        // --- TRADEPRO order-flow / auction setup (Flip Zone + Buy/Sell-Hold + confirmation) ---
        // Only surfaced when the setup reaches EXECUTE (price pulled into a defended zone AND
        // order flow confirmed) — respects the framework's "no chasing" rule.
        val tradePro = tradeProEngine.analyze(symbol, candles).setup
        if (tradePro != null && tradePro.isExecutable) {
            out += StrategySignalItem(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                strategyName = "TRADEPRO " + (tradePro.holdZone?.let { formatEnumName(it.type.name) } ?: "Flip Zone"),
                direction = tradePro.direction,
                confidence = tradePro.confidence.coerceIn(0, 100),
                entry = tradePro.entry,
                stopLoss = tradePro.stopLoss,
                takeProfit = tradePro.target2,
                riskReward = tradePro.riskReward,
                signalProvider = "TRADEPRO",
                note = tradePro.note,
            )
        }

        return out
    }

    private fun rr(entry: Double, sl: Double, tp: Double): Double {
        val risk = abs(entry - sl)
        val reward = abs(tp - entry)
        return if (risk > 0) reward / risk else 0.0
    }

    private fun formatEnumName(name: String): String =
        name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private companion object {
        const val DIVERGENCE_REGULAR_CONFIDENCE = 64
        const val DIVERGENCE_HIDDEN_CONFIDENCE = 60
        const val LIT_BASE_CONFIDENCE = 64
        const val LIT_ORDER_BLOCK_BONUS = 9
        const val LIT_FVG_BONUS = 6
        const val LIT_STRUCTURE_SHIFT_BONUS = 6
        const val LIT_MAX_CONFIDENCE = 96
    }
}
