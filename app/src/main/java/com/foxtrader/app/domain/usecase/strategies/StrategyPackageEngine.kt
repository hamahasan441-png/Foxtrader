package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.MarketStructure
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.SessionRange
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.signalintel.LitEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One causal analysis package for every built-in strategy.
 *
 * Instead of letting each strategy independently recompute a few indicators,
 * this engine builds one market snapshot containing technical state, confirmed
 * market structure, the complete SMC detector bundle and session context. The
 * strategy-specific rule then consumes that package and returns the final setup.
 *
 * LiT and LiTX keep their dedicated institutional pipelines, but they are routed
 * through this same package boundary so live chart and historical research call
 * one canonical entry point for every [StrategyType].
 *
 * Non-repainting invariant: [analyze] truncates input to [0..index] before any
 * detector is invoked. Nothing in this class can inspect a future candle.
 */
class StrategyPackageEngine(
    private val smcDetector: SmcDetector,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val ichimokuCloud: IchimokuCloud,
    private val litXEngine: LitXEngine,
    private val litEngine: LitEngine,
    private val sessionDetector: SessionDetector = SessionDetector(),
) {

    data class TechnicalPackage(
        val ema9: Double,
        val ema20: Double,
        val ema21: Double,
        val ema50: Double,
        val rsi14: Double,
        val atr14: Double,
        val adx14: Double,
        val plusDi14: Double,
        val minusDi14: Double,
        val relativeVolume20: Double,
        val macd: Double,
        val macdSignal: Double,
        val macdHistogram: Double,
        val ichimokuPosition: IchimokuCloud.CloudPosition,
        val ichimoku: IchimokuCloud.IchimokuResult,
    )

    data class Evidence(
        val source: String,
        val direction: Direction?,
        val score: Int,
        val detail: String,
    )

    data class Analysis(
        val type: StrategyType,
        val symbol: String,
        val timeframe: Timeframe,
        val index: Int,
        val timestamp: Long,
        val technical: TechnicalPackage,
        val structure: MarketStructure,
        val smc: SmcDetector.SmcAnalysisResult,
        val sessions: List<SessionRange>,
        val evidence: List<Evidence>,
        val confirmations: List<String>,
        val conflicts: List<String>,
        val packageConfidence: Int,
        val signal: StrategySignal?,
        val narrative: String,
    )

    /** Convert a package into the StrategyFunction API used by live/backtest. */
    fun function(
        type: StrategyType,
        symbol: String,
        timeframe: Timeframe,
        minimumBars: Int,
    ): StrategyFunction = fn@{ candles, index ->
        if (index < minimumBars || index !in candles.indices) return@fn null
        analyze(type, symbol, timeframe, candles, index).signal
    }

    fun analyze(
        type: StrategyType,
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        index: Int,
    ): Analysis {
        require(index in candles.indices) { "Strategy package index $index is outside ${candles.indices}." }
        val visible = if (index == candles.lastIndex) candles else candles.subList(0, index + 1)
        val core = core(visible)
        val baseSignal = when (type) {
            StrategyType.SMART_MONEY -> smcOrderBlockSignal(core)
            StrategyType.LIT -> litSignal(symbol, timeframe, visible, index)
            StrategyType.LITX -> litXSignal(symbol, timeframe, visible, index)
            StrategyType.TREND_FOLLOWING -> emaCrossoverSignal(core)
            StrategyType.MEAN_REVERSION -> rsiMeanReversionSignal(core)
            StrategyType.BREAKOUT -> structureBreakoutSignal(core)
            StrategyType.ICHIMOKU -> ichimokuTrendSignal(core)
            StrategyType.CONFLUENCE -> confluenceSignal(core)
            StrategyType.PATTERN -> fvgRetestSignal(core)
        }

        val evidence = buildEvidence(core)
        val direction = baseSignal?.direction
        val supportive = if (direction == null) emptyList() else evidence.filter { it.direction == direction }
        val opposing = if (direction == null) emptyList() else evidence.filter { it.direction != null && it.direction != direction }
        val packageConfidence = combineConfidence(baseSignal, supportive, opposing)
        val signal = baseSignal?.copy(confidence = packageConfidence)
        val confirmations = supportive.sortedByDescending { it.score }.map { "${it.source}:${it.score}:${it.detail}" }
        val conflicts = opposing.sortedByDescending { it.score }.map { "${it.source}:${it.score}:${it.detail}" }

        val smc = core.smc
        val narrative = buildString {
            append(type.label)
            append(" package · ")
            append(core.structure.bias.name)
            append(" structure · SMC[")
            append("OB=").append(smc.orderBlocks.size)
            append(",FVG=").append(smc.fairValueGaps.size)
            append(",LQ=").append(smc.liquidityPools.size)
            append(",BRK=").append(smc.breakerBlocks.size)
            append(",IFVG=").append(smc.inversionFVGs.size)
            append(",BPR=").append(smc.balancedPriceRanges.size)
            append("] · evidence=").append(evidence.size)
            if (signal != null) {
                append(" · ").append(signal.direction.name)
                append(" ").append(packageConfidence).append("/100")
            } else {
                append(" · no executable setup")
            }
        }

        return Analysis(
            type = type,
            symbol = symbol,
            timeframe = timeframe,
            index = index,
            timestamp = visible.last().timestamp,
            technical = core.technical,
            structure = core.structure,
            smc = core.smc,
            sessions = core.sessions,
            evidence = evidence,
            confirmations = confirmations,
            conflicts = conflicts,
            packageConfidence = packageConfidence,
            signal = signal,
            narrative = narrative,
        )
    }

    // =====================================================================
    // Shared core package
    // =====================================================================

    private data class Core(
        val candles: List<Candle>,
        val index: Int,
        val technical: TechnicalPackage,
        val structure: MarketStructure,
        val smc: SmcDetector.SmcAnalysisResult,
        val sessions: List<SessionRange>,
    )

    private data class CoreKey(
        val size: Int,
        val firstTimestamp: Long,
        val lastTimestamp: Long,
        val tailHash: Long,
    )

    @Volatile private var cachedCoreKey: CoreKey? = null
    @Volatile private var cachedCore: Core? = null

    /**
     * One-entry cache is enough for LiveStrategyEngine.evaluateAll(): all strategy
     * types inspect the same confirmed prefix consecutively. The tail hash also
     * invalidates an in-progress candle update even when timestamp/size match.
     */
    @Synchronized
    private fun core(candles: List<Candle>): Core {
        val key = coreKey(candles)
        val previous = cachedCore
        if (cachedCoreKey == key && previous != null) return previous

        val i = candles.lastIndex
        val ema9 = TechnicalIndicators.calculateEMA(candles, 9)
        val ema20 = TechnicalIndicators.calculateEMA(candles, 20)
        val ema21 = TechnicalIndicators.calculateEMA(candles, 21)
        val ema50 = TechnicalIndicators.calculateEMA(candles, 50)
        val rsi = TechnicalIndicators.calculateRSI(candles, 14)
        val atr = TechnicalIndicators.calculateATR(candles, 14)
        val adx = TechnicalIndicators.calculateADX(candles, 14)
        val relVol = TechnicalIndicators.calculateRelativeVolume(candles, 20)
        val macd = TechnicalIndicators.calculateMACD(candles)
        val ichi = ichimokuCloud.calculate(candles)

        val built = Core(
            candles = candles,
            index = i,
            technical = TechnicalPackage(
                ema9 = ema9[i],
                ema20 = ema20[i],
                ema21 = ema21[i],
                ema50 = ema50[i],
                rsi14 = rsi[i],
                atr14 = atr[i],
                adx14 = adx.adx[i],
                plusDi14 = adx.plusDI[i],
                minusDi14 = adx.minusDI[i],
                relativeVolume20 = relVol[i],
                macd = macd.macd[i],
                macdSignal = macd.signal[i],
                macdHistogram = macd.histogram[i],
                ichimokuPosition = ichimokuCloud.cloudPosition(candles, ichi),
                ichimoku = ichi,
            ),
            structure = analyzeStructure(candles),
            smc = smcDetector.analyzeAll(candles),
            sessions = sessionDetector.detectSessions(candles),
        )
        cachedCoreKey = key
        cachedCore = built
        return built
    }

    private fun coreKey(candles: List<Candle>): CoreKey {
        var hash = 1125899906842597L
        val start = (candles.size - CACHE_HASH_BARS).coerceAtLeast(0)
        for (i in start until candles.size) {
            val c = candles[i]
            hash = hash * 31 + c.timestamp
            hash = hash * 31 + c.open.toBits()
            hash = hash * 31 + c.high.toBits()
            hash = hash * 31 + c.low.toBits()
            hash = hash * 31 + c.close.toBits()
            hash = hash * 31 + c.volume.toBits()
        }
        return CoreKey(
            size = candles.size,
            firstTimestamp = candles.firstOrNull()?.timestamp ?: 0L,
            lastTimestamp = candles.lastOrNull()?.timestamp ?: 0L,
            tailHash = hash,
        )
    }

    // =====================================================================
    // Package evidence — diagnostics/confidence, not a blind voting system
    // =====================================================================

    private fun buildEvidence(core: Core): List<Evidence> = buildList {
        when (core.structure.bias) {
            Bias.BULLISH -> add(Evidence("STRUCTURE", Direction.BULLISH, 78, "confirmed BOS/CHOCH bias"))
            Bias.BEARISH -> add(Evidence("STRUCTURE", Direction.BEARISH, 78, "confirmed BOS/CHOCH bias"))
            Bias.NEUTRAL -> Unit
        }
        core.structure.breaks.lastOrNull()?.takeIf { it.confirmed }?.let {
            add(Evidence("STRUCTURE_BREAK", it.direction, 74, it.type.name))
        }

        val t = core.technical
        if (t.ema20 > t.ema50) add(Evidence("EMA_STACK", Direction.BULLISH, 62, "EMA20 > EMA50"))
        else if (t.ema20 < t.ema50) add(Evidence("EMA_STACK", Direction.BEARISH, 62, "EMA20 < EMA50"))

        if (t.adx14 >= 20.0) {
            val direction = if (t.plusDi14 >= t.minusDi14) Direction.BULLISH else Direction.BEARISH
            add(Evidence("ADX_DI", direction, t.adx14.roundToInt().coerceIn(55, 90), "ADX ${t.adx14.roundToInt()}"))
        }
        when {
            t.rsi14 <= 35.0 -> add(Evidence("RSI_REVERSION", Direction.BULLISH, 60, "RSI ${t.rsi14.roundToInt()}"))
            t.rsi14 >= 65.0 -> add(Evidence("RSI_REVERSION", Direction.BEARISH, 60, "RSI ${t.rsi14.roundToInt()}"))
        }
        when {
            t.macdHistogram > 0.0 -> add(Evidence("MACD", Direction.BULLISH, 58, "positive histogram"))
            t.macdHistogram < 0.0 -> add(Evidence("MACD", Direction.BEARISH, 58, "negative histogram"))
        }
        when (t.ichimokuPosition) {
            IchimokuCloud.CloudPosition.ABOVE -> add(Evidence("ICHIMOKU", Direction.BULLISH, 68, "price above Kumo"))
            IchimokuCloud.CloudPosition.BELOW -> add(Evidence("ICHIMOKU", Direction.BEARISH, 68, "price below Kumo"))
            IchimokuCloud.CloudPosition.INSIDE -> Unit
        }
        if (t.relativeVolume20 >= 1.3) {
            add(Evidence("VOLUME", null, (55 + (t.relativeVolume20 * 8)).roundToInt().coerceIn(55, 85), "relative volume ${"%.2f".format(java.util.Locale.US, t.relativeVolume20)}x"))
        }

        core.smc.orderBlocks.lastOrNull { !it.mitigated }?.let {
            val direction = if (it.type == OrderBlockType.BULLISH) Direction.BULLISH else Direction.BEARISH
            add(Evidence("ORDER_BLOCK", direction, (it.strength * 100).roundToInt().coerceIn(55, 90), "fresh ${it.type.name}"))
        }
        core.smc.fairValueGaps.lastOrNull { !it.filled }?.let {
            val direction = if (it.type == FvgType.BULLISH) Direction.BULLISH else Direction.BEARISH
            add(Evidence("FVG", direction, (60 + (1.0 - it.fillPercent) * 25).roundToInt().coerceIn(55, 88), "unfilled ${it.type.name}"))
        }
        core.smc.liquidityPools
            .filter { it.swept && it.sweepIndex != null }
            .maxByOrNull { it.sweepIndex ?: -1 }
            ?.let {
                val direction = if (it.type == LiquidityType.SELL_SIDE) Direction.BULLISH else Direction.BEARISH
                add(Evidence("LIQUIDITY_SWEEP", direction, 72, "${it.type.name} swept"))
            }

        val activeSession = core.sessions.lastOrNull { core.index in it.startIndex..it.endIndex }
        activeSession?.let { add(Evidence("SESSION", null, 55, it.session.name)) }
    }

    private fun combineConfidence(
        signal: StrategySignal?,
        supportive: List<Evidence>,
        opposing: List<Evidence>,
    ): Int {
        if (signal == null) return 0
        val base = signal.confidence ?: DEFAULT_CONFIDENCE
        val support = supportive.count { it.score >= 60 }
        val conflict = opposing.count { it.score >= 65 }
        // Package context can refine confidence but cannot dominate the strategy's
        // own entry model. This keeps migration stable while making the complete
        // detector package materially visible in final setup quality.
        val adjustment = (support * 2 - conflict * 2).coerceIn(-8, 8)
        return (base + adjustment).coerceIn(0, 95)
    }

    // =====================================================================
    // Strategy-specific decision rules consuming the shared package
    // =====================================================================

    private fun smcOrderBlockSignal(core: Core): StrategySignal? {
        if (core.index < 80 || core.technical.atr14 <= 0.0) return null
        val bias = core.structure.bias
        if (bias == Bias.NEUTRAL) return null
        val bar = core.candles[core.index]
        for (ob in core.smc.orderBlocks.asReversed()) {
            if (ob.mitigated) continue
            val mid = (ob.highPrice + ob.lowPrice) / 2.0
            if (ob.type == OrderBlockType.BULLISH && bias == Bias.BULLISH && bar.low <= mid && bar.close > mid && bar.close < ob.highPrice) {
                val sl = ob.lowPrice - core.technical.atr14 * 0.15
                val risk = mid - sl
                if (risk <= 0.0) continue
                return StrategySignal(core.index, bar.timestamp, Direction.BULLISH, mid, sl, mid + risk * 3.0, confidence = (ob.strength * 100).roundToInt().coerceIn(50, 95), setupType = "OB_RETEST_BULL")
            }
            if (ob.type == OrderBlockType.BEARISH && bias == Bias.BEARISH && bar.high >= mid && bar.close < mid && bar.close > ob.lowPrice) {
                val sl = ob.highPrice + core.technical.atr14 * 0.15
                val risk = sl - mid
                if (risk <= 0.0) continue
                return StrategySignal(core.index, bar.timestamp, Direction.BEARISH, mid, sl, mid - risk * 3.0, confidence = (ob.strength * 100).roundToInt().coerceIn(50, 95), setupType = "OB_RETEST_BEAR")
            }
        }
        return null
    }

    private fun litSignal(symbol: String, timeframe: Timeframe, candles: List<Candle>, index: Int): StrategySignal? {
        if (index < 60) return null
        val signal = litEngine.analyze(symbol, timeframe, candles).signal ?: return null
        if (signal.confirmationIndex != index || signal.timestamp != candles[index].timestamp) return null
        return StrategySignal(
            index = index,
            timestamp = signal.timestamp,
            direction = signal.direction,
            entry = signal.entry,
            stopLoss = signal.stopLoss,
            takeProfit = signal.takeProfit,
            confidence = signal.confidence.coerceIn(50, 95),
            setupType = "LIT_PHASE13",
        )
    }

    private fun litXSignal(symbol: String, timeframe: Timeframe, candles: List<Candle>, index: Int): StrategySignal? {
        if (index < 60) return null
        val signal = litXEngine.analyze(symbol = symbol, timeframe = timeframe, candles = candles).signal ?: return null
        return StrategySignal(
            index = index,
            timestamp = candles[index].timestamp,
            direction = signal.direction,
            entry = signal.entry,
            stopLoss = signal.stopLoss,
            takeProfit = signal.takeProfit1,
            confidence = signal.confidence.score.coerceIn(50, 95),
            setupType = "LITX_${signal.confidence.grade.name}",
        )
    }

    private fun emaCrossoverSignal(core: Core): StrategySignal? {
        val i = core.index
        if (i < 60 || core.technical.atr14 <= 0.0 || core.technical.adx14 < 25.0) return null
        // Previous values are needed only for the cross event; compute from the
        // already-causal package prefix, never from a future bar.
        val ema9 = TechnicalIndicators.calculateEMA(core.candles, 9)
        val ema21 = TechnicalIndicators.calculateEMA(core.candles, 21)
        val bar = core.candles[i]
        val crossUp = ema9[i] > ema21[i] && ema9[i - 1] <= ema21[i - 1]
        val crossDown = ema9[i] < ema21[i] && ema9[i - 1] >= ema21[i - 1]
        if (crossUp && bar.close > core.technical.ema50) {
            return StrategySignal(i, bar.timestamp, Direction.BULLISH, bar.close, bar.close - core.technical.atr14 * 1.5, bar.close + core.technical.atr14 * 4.5, confidence = core.technical.adx14.roundToInt().coerceIn(50, 90), setupType = "EMA_CROSS_BULL")
        }
        if (crossDown && bar.close < core.technical.ema50) {
            return StrategySignal(i, bar.timestamp, Direction.BEARISH, bar.close, bar.close + core.technical.atr14 * 1.5, bar.close - core.technical.atr14 * 4.5, confidence = core.technical.adx14.roundToInt().coerceIn(50, 90), setupType = "EMA_CROSS_BEAR")
        }
        return null
    }

    private fun rsiMeanReversionSignal(core: Core): StrategySignal? {
        val i = core.index
        if (i < 50 || core.technical.atr14 <= 0.0) return null
        val rsi = TechnicalIndicators.calculateRSI(core.candles, 14)
        val bar = core.candles[i]
        if (rsi[i - 1] <= 30.0 && rsi[i] > 30.0) {
            val sl = bar.close - core.technical.atr14 * 2.0
            val tp = core.technical.ema20
            if (tp <= bar.close) return null
            val rr = (tp - bar.close) / (bar.close - sl)
            if (rr < 2.0) return null
            return StrategySignal(i, bar.timestamp, Direction.BULLISH, bar.close, sl, tp, confidence = (100.0 - rsi[i - 1] * 2).roundToInt().coerceIn(55, 85), setupType = "RSI_LEAVE_OVERSOLD")
        }
        if (rsi[i - 1] >= 70.0 && rsi[i] < 70.0) {
            val sl = bar.close + core.technical.atr14 * 2.0
            val tp = core.technical.ema20
            if (tp >= bar.close) return null
            val rr = (bar.close - tp) / (sl - bar.close)
            if (rr < 2.0) return null
            return StrategySignal(i, bar.timestamp, Direction.BEARISH, bar.close, sl, tp, confidence = (rsi[i - 1] * 2 - 100.0).roundToInt().coerceIn(55, 85), setupType = "RSI_LEAVE_OVERBOUGHT")
        }
        return null
    }

    private fun structureBreakoutSignal(core: Core): StrategySignal? {
        val i = core.index
        val atr = core.technical.atr14
        if (i < 60 || atr <= 0.0) return null
        val recentBreak = core.structure.breaks.lastOrNull {
            it.confirmed && it.type == StructureBreakType.BOS && it.breakIndex == i - STRUCTURE_SWING_CONFIRMATION_BARS
        } ?: return null
        if (recentBreak.direction != core.structure.bias.toDirection()) return null
        val bar = core.candles[i]
        if (recentBreak.direction == Direction.BULLISH && bar.close <= recentBreak.breakPrice) return null
        if (recentBreak.direction == Direction.BEARISH && bar.close >= recentBreak.breakPrice) return null
        val entry = bar.close
        val sl = if (recentBreak.direction == Direction.BULLISH) {
            (core.structure.swingLows.lastOrNull()?.price ?: (entry - atr * 2.0)) - atr * 0.2
        } else {
            (core.structure.swingHighs.lastOrNull()?.price ?: (entry + atr * 2.0)) + atr * 0.2
        }
        val risk = abs(entry - sl)
        if (risk <= 0.0 || risk > atr * 5.0) return null
        val tp = if (recentBreak.direction == Direction.BULLISH) entry + risk * 3.0 else entry - risk * 3.0
        return StrategySignal(i, bar.timestamp, recentBreak.direction, entry, sl, tp, confidence = 70, setupType = "BOS_CONTINUATION")
    }

    private fun ichimokuTrendSignal(core: Core): StrategySignal? {
        val i = core.index
        if (i < 60 || core.technical.atr14 <= 0.0 || core.technical.ichimokuPosition == IchimokuCloud.CloudPosition.INSIDE) return null
        val ichi = core.technical.ichimoku
        val bar = core.candles[i]
        val tkCrossUp = ichi.tenkan[i] > ichi.kijun[i] && ichi.tenkan[i - 1] <= ichi.kijun[i - 1]
        val tkCrossDown = ichi.tenkan[i] < ichi.kijun[i] && ichi.tenkan[i - 1] >= ichi.kijun[i - 1]
        if (core.technical.ichimokuPosition == IchimokuCloud.CloudPosition.ABOVE && tkCrossUp) {
            val sl = minOf(ichi.senkouA[i], ichi.senkouB[i]) - core.technical.atr14 * 0.3
            val risk = bar.close - sl
            if (risk <= 0.0) return null
            return StrategySignal(i, bar.timestamp, Direction.BULLISH, bar.close, sl, bar.close + risk * 3.0, confidence = 72, setupType = "ICHIMOKU_KUMO_BULL")
        }
        if (core.technical.ichimokuPosition == IchimokuCloud.CloudPosition.BELOW && tkCrossDown) {
            val sl = maxOf(ichi.senkouA[i], ichi.senkouB[i]) + core.technical.atr14 * 0.3
            val risk = sl - bar.close
            if (risk <= 0.0) return null
            return StrategySignal(i, bar.timestamp, Direction.BEARISH, bar.close, sl, bar.close - risk * 3.0, confidence = 72, setupType = "ICHIMOKU_KUMO_BEAR")
        }
        return null
    }

    private fun confluenceSignal(core: Core): StrategySignal? {
        val i = core.index
        val atr = core.technical.atr14
        if (i < 60 || atr <= 0.0) return null
        val bar = core.candles[i]
        var bullScore = 0
        var bearScore = 0
        if (bar.close > core.technical.ema20 && core.technical.ema20 > core.technical.ema50) bullScore++
        if (bar.close < core.technical.ema20 && core.technical.ema20 < core.technical.ema50) bearScore++
        if (core.technical.rsi14 < 40.0) bullScore++
        if (core.technical.rsi14 > 60.0) bearScore++
        if (core.structure.bias == Bias.BULLISH) bullScore++
        if (core.structure.bias == Bias.BEARISH) bearScore++
        if (core.technical.relativeVolume20 > 1.3) { bullScore++; bearScore++ }
        core.smc.orderBlocks.lastOrNull { !it.mitigated && it.type == OrderBlockType.BULLISH }?.let {
            if (abs(bar.close - (it.highPrice + it.lowPrice) / 2.0) < atr) bullScore++
        }
        core.smc.orderBlocks.lastOrNull { !it.mitigated && it.type == OrderBlockType.BEARISH }?.let {
            if (abs(bar.close - (it.highPrice + it.lowPrice) / 2.0) < atr) bearScore++
        }
        if (bullScore >= 3 && bullScore > bearScore) {
            return StrategySignal(i, bar.timestamp, Direction.BULLISH, bar.close, bar.close - atr * 2.0, bar.close + atr * 6.0, confidence = (50 + bullScore * 10).coerceIn(50, 95), setupType = "CONFLUENCE_BULL")
        }
        if (bearScore >= 3 && bearScore > bullScore) {
            return StrategySignal(i, bar.timestamp, Direction.BEARISH, bar.close, bar.close + atr * 2.0, bar.close - atr * 6.0, confidence = (50 + bearScore * 10).coerceIn(50, 95), setupType = "CONFLUENCE_BEAR")
        }
        return null
    }

    private fun fvgRetestSignal(core: Core): StrategySignal? {
        val i = core.index
        val atr = core.technical.atr14
        if (i < 60 || atr <= 0.0 || core.structure.bias == Bias.NEUTRAL) return null
        val bar = core.candles[i]
        for (fvg in core.smc.fairValueGaps.asReversed()) {
            if (fvg.filled) continue
            val mid = (fvg.highPrice + fvg.lowPrice) / 2.0
            if (fvg.type == FvgType.BULLISH && core.structure.bias == Bias.BULLISH && bar.low <= mid && bar.close > mid) {
                val sl = fvg.lowPrice - atr * 0.15
                val risk = mid - sl
                if (risk <= 0.0) continue
                return StrategySignal(i, bar.timestamp, Direction.BULLISH, mid, sl, mid + risk * 2.5, confidence = (60 + (1.0 - fvg.fillPercent) * 30).roundToInt().coerceIn(55, 90), setupType = "FVG_RETEST_BULL")
            }
            if (fvg.type == FvgType.BEARISH && core.structure.bias == Bias.BEARISH && bar.high >= mid && bar.close < mid) {
                val sl = fvg.highPrice + atr * 0.15
                val risk = sl - mid
                if (risk <= 0.0) continue
                return StrategySignal(i, bar.timestamp, Direction.BEARISH, mid, sl, mid - risk * 2.5, confidence = (60 + (1.0 - fvg.fillPercent) * 30).roundToInt().coerceIn(55, 90), setupType = "FVG_RETEST_BEAR")
            }
        }
        return null
    }

    private fun Bias.toDirection(): Direction? = when (this) {
        Bias.BULLISH -> Direction.BULLISH
        Bias.BEARISH -> Direction.BEARISH
        Bias.NEUTRAL -> null
    }

    private companion object {
        const val STRUCTURE_SWING_CONFIRMATION_BARS = 5
        const val CACHE_HASH_BARS = 32
        const val DEFAULT_CONFIDENCE = 60
    }
}
