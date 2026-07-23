package com.foxtrader.app.feature.strategies.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.JournalRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.analysis.RiskRewardOptimizer
import com.foxtrader.app.domain.usecase.analysis.DivergenceDetector
import com.foxtrader.app.domain.usecase.analysis.WyckoffDetector
import com.foxtrader.app.domain.usecase.backtest.AiScoredBacktestEngine
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.journal.BacktestJournalMapper
import com.foxtrader.app.domain.usecase.patterns.CandlePatternDetector
import com.foxtrader.app.domain.usecase.patterns.HarmonicPatternDetector
import com.foxtrader.app.domain.usecase.scanner.ScannerUseCase
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

/**
 * Strategies screen ViewModel — scans watchlist symbols and surfaces
 * actionable setups detected by the analysis engines (harmonics, SMC,
 * risk/reward). Kept separate from the chart so users have a dedicated
 * "strategies" section.
 */
@HiltViewModel
class StrategiesViewModel @Inject constructor(
    private val repository: MarketRepository,
    private val scannerUseCase: ScannerUseCase,
    private val harmonicDetector: HarmonicPatternDetector,
    private val candlePatternDetector: CandlePatternDetector,
    private val divergenceDetector: DivergenceDetector,
    private val smcDetector: SmcDetector,
    private val wyckoffDetector: WyckoffDetector,
    private val ichimokuCloud: IchimokuCloud,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val riskReward: RiskRewardOptimizer,
    private val aiBacktestEngine: AiScoredBacktestEngine,
    private val journalRepository: JournalRepository,
) : ViewModel() {
    private companion object {
        const val DIVERGENCE_REGULAR_CONFIDENCE = 64
        const val DIVERGENCE_HIDDEN_CONFIDENCE = 60
        const val LIT_BASE_CONFIDENCE = 64
        const val LIT_ORDER_BLOCK_BONUS = 9
        const val LIT_FVG_BONUS = 6
        const val LIT_STRUCTURE_SHIFT_BONUS = 6
        const val LIT_MAX_CONFIDENCE = 96
    }

    private val _uiState = MutableStateFlow(StrategiesUiState())
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    init {
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, error = null) }
            try {
                val signals = mutableListOf<StrategySignalItem>()
                val watchlist = scannerUseCase.getWatchlist().filter { it.enabled }.take(15)

                for (ws in watchlist) {
                    val candles = repository.getCandles(ws.symbol)
                    if (candles.size < 50) continue
                    signals += detectSignals(ws.symbol, candles)
                }

                signals.sortByDescending { it.confidence }
                _uiState.update {
                    it.copy(
                        signals = signals,
                        isScanning = false,
                        lastScanTime = System.currentTimeMillis(),
                    )
                }
                // Run an AI-scored backtest on the first scanned symbol (representative).
                runAiBacktest()
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, error = e.message) }
            }
        }
    }

    /**
     * Run AI-scored backtest on the first available symbol with sufficient data.
     * Uses a simple RSI mean-reversion strategy as the demonstration strategy.
     */
    private suspend fun runAiBacktest() {
        try {
            val watchlist = scannerUseCase.getWatchlist().filter { it.enabled }.take(5)
            val symbol = watchlist.firstOrNull()?.symbol ?: return
            val candles = repository.getCandles(symbol, Timeframe.H1)
            if (candles.size < 100) return

            val strategy: StrategyFunction = { c, i ->
                if (i < 50) null else {
                    val rsi = TechnicalIndicators.calculateRSI(c, 14)
                    val atr = TechnicalIndicators.calculateATR(c, 14)
                    val atrVal = atr[i]
                    when {
                        rsi[i] < 30.0 -> StrategySignal(
                            index = i, timestamp = c[i].timestamp,
                            direction = Direction.BULLISH,
                            entry = c[i].close,
                            stopLoss = c[i].close - atrVal * 2,
                            takeProfit = c[i].close + atrVal * 3,
                            setupType = "RSI_OVERSOLD",
                        )
                        rsi[i] > 70.0 -> StrategySignal(
                            index = i, timestamp = c[i].timestamp,
                            direction = Direction.BEARISH,
                            entry = c[i].close,
                            stopLoss = c[i].close + atrVal * 2,
                            takeProfit = c[i].close - atrVal * 3,
                            setupType = "RSI_OVERBOUGHT",
                        )
                        else -> null
                    }
                }
            }

            val result = aiBacktestEngine(candles, strategy, symbol, Timeframe.H1)

            // Auto-journal: persist backtest trades into the journal.
            if (result.trades.isNotEmpty()) {
                val journalEntries = BacktestJournalMapper.mapTrades(result.trades, symbol, Timeframe.H1)
                journalRepository.upsertAll(journalEntries)
            }

            _uiState.update { state ->
                state.copy(
                    aiBacktestEnabled = true,
                    aiApprovalRate = result.aiApprovalRate,
                    allTradesWinRate = result.metrics.winRate,
                    aiFilteredWinRate = result.aiFilteredMetrics?.winRate,
                    allTradesProfitFactor = result.metrics.profitFactor,
                    aiFilteredProfitFactor = result.aiFilteredMetrics?.profitFactor,
                    backtestTradeCount = result.metrics.totalTrades,
                    aiApprovedTradeCount = result.aiFilteredMetrics?.totalTrades,
                )
            }
        } catch (_: Exception) {
            // AI backtest is supplementary — don't crash the strategies screen.
        }
    }

    private fun detectSignals(symbol: String, candles: List<Candle>): List<StrategySignalItem> {
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
                val tkSpread = kotlin.math.abs(ichimoku.tenkan[candles.lastIndex] - ichimoku.kijun[candles.lastIndex])
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
        val structureBreak = analyzeStructure(candles).breaks.lastOrNull { it.confirmed }
        if (liquiditySweep?.sweepIndex != null && structureBreak != null) {
            val sweepIndex = liquiditySweep.sweepIndex!!
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

        return out
    }

    private fun rr(entry: Double, sl: Double, tp: Double): Double {
        val risk = abs(entry - sl)
        val reward = abs(tp - entry)
        return if (risk > 0) reward / risk else 0.0
    }

    private fun formatEnumName(name: String): String =
        name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
