package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.LitXSignal
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
 * Instead of letting each strategy independently recompute a handful of
 * indicators, this engine builds one shared market package containing:
 * - technical state and previous-bar event state,
 * - confirmed market structure (swings/BOS/CHOCH/bias),
 * - the complete SMC detector bundle (OB/FVG/liquidity/breaker/IFVG/BPR),
 * - session context,
 * - and the strategy-specific institutional/classical execution rule.
 *
 * The package has two intentional outputs:
 * 1. [Analysis.preferredDirection]/[Analysis.packageScore] for scanners/ranking.
 * 2. [Analysis.signal] for an objectively executable setup used by live/backtest.
 *
 * Both outputs come from the exact same causal snapshot. Scanner therefore no
 * longer needs a second strategy implementation merely because it ranks symbols
 * even when no entry is currently executable.
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
        val ema9Previous: Double,
        val ema20: Double,
        val ema21: Double,
        val ema21Previous: Double,
        val ema50: Double,
        val rsi14: Double,
        val rsi14Previous: Double,
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
        val preferredDirection: Direction?,
        val confirmations: List<String>,
        val conflicts: List<String>,
        val packageScore: Int,
        /** Final confidence after package context refines the base setup. */
        val packageConfidence: Int,
        /** Non-null only when the selected strategy has an executable setup. */
        val signal: StrategySignal?,
        /** Raw validated LiTX signal, retained so scanners never rerun LiTX. */
        val validatedLitXSignal: LitXSignal? = null,
        val narrative: String,
    )

    private data class SpecificResult(
        val signal: StrategySignal? = null,
        val validatedLitXSignal: LitXSignal? = null,
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
        val specific = when (type) {
            StrategyType.SMART_MONEY -> SpecificResult(smcOrderBlockSignal(core))
            StrategyType.LIT -> SpecificResult(litSignal(symbol, timeframe, visible, index))
            StrategyType.LITX -> litXResult(symbol, timeframe, visible, index)
            StrategyType.TREND_FOLLOWING -> SpecificResult(emaCrossoverSignal(core))
            StrategyType.MEAN_REVERSION -> SpecificResult(rsiMeanReversionSignal(core))
            StrategyType.BREAKOUT -> SpecificResult(structureBreakoutSignal(core))
            StrategyType.ICHIMOKU -> SpecificResult(ichimokuTrendSignal(core))
            StrategyType.CONFLUENCE -> SpecificResult(confluenceSignal(core))
            StrategyType.PATTERN -> SpecificResult(fvgRetestSignal(core))
        }

        val evidence = buildEvidence(core)
        val preferredDirection = dominantDirection(evidence)
        val decisionDirection = specific.signal?.direction ?: preferredDirection
        val supportive = if (decisionDirection == null) emptyList() else evidence.filter { it.direction == decisionDirection }
        val opposing = if (decisionDirection == null) emptyList() else evidence.filter { it.direction != null && it.direction != decisionDirection }
        val packageScore = scorePackage(decisionDirection, supportive, opposing)
        val packageConfidence = combineConfidence(specific.signal, supportive, opposing, packageScore)
        val signal = specific.signal?.copy(confidence = packageConfidence)
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
            if (preferredDirection != null) {
                append(" · bias=").append(preferredDirection.name)
                append(" ").append(packageScore).append("/100")
            }
            if (signal != null) {
                append(" · EXECUTE ").append(signal.direction.name)
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
            preferredDirection = preferredDirection,
            confirmations = confirmations,
            conflicts = conflicts,
            packageScore = packageScore,
            packageConfidence = packageConfidence,
            signal = signal,
            validatedLitXSignal = specific.validatedLitXSignal,
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
        val previousIndex = (i - 1).coerceAtLeast(0)
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
                ema9Previous = ema9[previousIndex],
                ema20 = ema20[i],
                ema21 = ema21[i],
                ema21Previous = ema21[previousIndex],
                ema50 = ema50[i],
                rsi14 = rsi[i],
                rsi14Previous = rsi[previousIndex],
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
            add(
                Evidence(
                    "VOLUME",
                    null,
                    (55 + (t.relativeVolume20 * 8)).roundToInt().coerceIn(55, 85),
                    "relative volume ${"%.2f".format(java.util.Locale.US, t.relativeVolume20)}x",
                ),
            )
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
        activeSession?.let { add(Evidence("SESSION", null, 55, it.session.label)) }
    }

    private fun dominantDirection(evidence: List<Evidence>): Direction? {
        val bull = evidence.filter { it.direction == Direction.BULLISH }.sumOf { it.score.toDouble() }
        val bear = evidence.filter { it.direction == Direction.BEARISH }.sumOf { it.score.toDouble() }
        if (bull <= 0.0 && bear <= 0.0) return null
        val high = maxOf(bull, bear)
        val low = minOf(bull, bear)
        if (high > 0.0 && low / high >= DIRECTION_BALANCE_RATIO) return null
        return if (bull > bear) Direction.BULLISH else Direction.BEARISH
    }

    private fun scorePackage(
        direction: Direction?,
        supportive: List<Evidence>,
        opposing: List<Evidence>,
    ): Int {
        if (direction == null || supportive.isEmpty()) return 0
        val supportQuality = supportive.map { it.score }.average()
        val oppositionQuality = if (opposing.isEmpty()) 0.0 else opposing.map { it.score }.average()
        val diversityBoost = (supportive.map { it.source }.distinct().size * 2.0).coerceAtMost(10.0)
        val oppositionPenalty = oppositionQuality * 0.22
        return (supportQuality + diversityBoost - oppositionPenalty).roundToInt().coerceIn(0, 95)
    }

    private fun combineConfidence(
        signal: StrategySignal?,
        supportive: List<Evidence>,
        opposing: List<Evidence>,
        packageScore: Int,
    ): Int {
        if (signal == null) return packageScore
        val base = signal.confidence ?: DEFAULT_CONFIDENCE
        val support = supportive.count { it.score >= 60 }
        val conflict = opposing.count { it.score >= 65 }
        // Full package context refines, but never overwhelms, the strategy's own
        // execution rule. This keeps migration stable while materially using the
        // shared detector package in final setup quality.
        val countAdjustment = (support * 2 - conflict * 2).coerceIn(-6, 6)
        val packageAdjustment = ((packageScore - 60) * 0.12).roundToInt().coerceIn(-3, 4)
        return (base + countAdjustment + packageAdjustment).coerceIn(50, 95)
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
                return StrategySignal(
                    index = core.index,
                    timestamp = bar.timestamp,
                    direction = Direction.BULLISH,
                    entry = mid,
                    stopLoss = sl,
                    takeProfit = mid + risk * 3.0,
                    confidence = (ob.strength * 100).roundToInt().coerceIn(50, 95),
                    setupType = "OB_RETEST_BULL",
                )
            }
            if (ob.type == OrderBlockType.BEARISH && bias == Bias.BEARISH && bar.high >= mid && bar.close < mid && bar.close > ob.lowPrice) {
                val sl = ob.highPrice + core.technical.atr14 * 0.15
                val risk = sl - mid
                if (risk <= 0.0) continue
                return StrategySignal(
                    index = core.index,
                    timestamp = bar.timestamp,
                    direction = Direction.BEARISH,
                    entry = mid,
                    stopLoss = sl,
                    takeProfit = mid - risk * 3.0,
                    confidence = (ob.strength * 100).roundToInt().coerceIn(50, 95),
                    setupType = "OB_RETEST_BEAR",
                )
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

    private fun litXResult(symbol: String, timeframe: Timeframe, candles: List<Candle>, index: Int): SpecificResult {
        if (index < 60) return SpecificResult()
        val raw = litXEngine.analyze(symbol = symbol, timeframe = timeframe, candles = candles).signal ?: return SpecificResult()
        return SpecificResult(
            signal = StrategySignal(
                index = index,
                timestamp = candles[index].timestamp,
                direction = raw.direction,
                entry = raw.entry,
                stopLoss = raw.stopLoss,
                takeProfit = raw.takeProfit1,
                confidence = raw.confidence.score.coerceIn(50, 95),
                setupType = "LITX_${raw.confidence.grade.name}",
            ),
            validatedLitXSignal = raw,
        )
    }

    private fun emaCrossoverSignal(core: Core): StrategySignal? {
        val i = core.index
        val t = core.technical
        if (i < 60 || t.atr14 <= 0.0 || t.adx14 < 25.0) return null
        val bar = core.candles[i]
        val crossUp = t.ema9 > t.ema21 && t.ema9Previous <= t.ema21Previous
        val crossDown = t.ema9 < t.ema21 && t.ema9Previous >= t.ema21Previous
        if (crossUp && bar.close > t.ema50) {
            return StrategySignal(
                index = i,
                timestamp = bar.timestamp,
                direction = Direction.BULLISH,
                entry = bar.close,
                stopLoss = bar.close - t.atr14 * 1.5,
                takeProfit = bar.close + t.atr14 * 4.5,
                confidence = t.adx14.roundToInt().coerceIn(50, 90),
                setupType = "EMA_CROSS_BULL",
            )
        }
        if (crossDown && bar.close < t.ema50) {
            return StrategySignal(
                index = i,
                timestamp = bar.timestamp,
                direction = Direction.BEARISH,
                entry = bar.close,
                stopLoss = bar.close + t.atr14 * 1.5,
                takeProfit = bar.close - t.atr14 * 4.5,
                confidence = t.adx14.roundToInt().coerceIn(50, 90),
                setupType = "EMA_CROSS_BEAR",
            )
        }
        return null
    }

    private fun rsiMeanReversionSignal(core: Core): StrategySignal? {
        val i = core.index
        val t = core.technical
        if (i < 50 || t.atr14 <= 0.0) return null
        val bar = core.candles[i]
        if (t.rsi14Previous <= 30.0 && t.rsi14 > 30.0) {
            val sl = bar.close - t.atr14 * 2.0
            val tp = t.ema20
            if (tp <= bar.close) return null
            val rr = (tp - bar.close) / (bar.close - sl)
            if (rr < 2.0) return null
            return StrategySignal(
                index = i,
                timestamp = bar.timestamp,
                direction = Direction.BULLISH,
                entry = bar.close,
                stopLoss = sl,
                takeProfit = tp,
                confidence = (100.0 - t.rsi14Previous * 2).roundToInt().coerceIn(55, 85),
                setupType = "RSI_LEAVE_OVERSOLD",
            )
        }
        if (t.rsi14Previous >= 70.0 && t.rsi14 < 70.0) {
            val sl = bar.close + t.atr14 * 2.0
            val tp = t.ema20
            if (tp >= bar.close) return null
            val rr = (bar.close - tp) / (sl - bar.close)
            if (rr < 2.0) return null
            return StrategySignal(
                index = i,
                timestamp = bar.timestamp,
                direction = Direction.BEARISH,
                entry = bar.close,
                stopLoss = sl,
                takeProfit = tp,
                confidence = (t.rsi14Previous * 2 - 100.0).roundToInt().coerceIn(55, 85),
                setupType = "RSI_LEAVE_OVERBOUGHT",
            )
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
        return StrategySignal(
            index = i,
            timestamp = bar.timestamp,
            direction = recentBreak.direction,
            entry = entry,
            stopLoss = sl,
            takeProfit = tp,
            confidence = 70,
            setupType = "BOS_CONTINUATION",
        )
    }

    private fun ichimokuTrendSignal(core: Core): StrategySignal? {
        val i = core.index
        val t = core.technical
        if (i < 60 || t.atr14 <= 0.0 || t.ichimokuPosition == IchimokuCloud.CloudPosition.INSIDE) return null
        val ichi = t.ichimoku
        val bar = core.candles[i]
        val tkCrossUp = ichi.tenkan[i] > ichi.kijun[i] && ichi.tenkan[i - 1] <= ichi.kijun[i - 1]
        val tkCrossDown = ichi.tenkan[i] < ichi.kijun[i] && ichi.tenkan[i - 1] >= ichi.kijun[i - 1]
        if (t.ichimokuPosition == IchimokuCloud.CloudPosition.ABOVE && tkCrossUp) {
            val sl = minOf(ichi.senkouA[i], ichi.senkouB[i]) - t.atr14 * 0.3
            val risk = bar.close - sl
            if (risk <= 0.0) return null
            return StrategySignal(
                index = i,
                timestamp = bar.timestamp,
                direction = Direction.BULLISH,
                entry = bar.close,
                stopLoss = sl,
                takeProfit = bar.close + risk * 3.0,
                confidence = 72,
                setupType = "ICHIMOKU_KUMO_BULL",
            )
        }
        if (t.ichimokuPosition == IchimokuCloud.CloudPosition.BELOW && tkCrossDown) {
            val sl = maxOf(ichi.senkouA[i], ichi.senkouB[i]) + t.atr14 * 0.3
            val risk = sl - bar.close
            if (risk <= 0.0) return null
            return StrategySignal(
                index = i,
                timestamp = bar.timestamp,
                direction = Direction.BEARISH,
                entry = bar.close,
                stopLoss = sl,
                takeProfit = bar.close - risk * 3.0,
                confidence = 72,
                setupType = "ICHIMOKU_KUMO_BEAR",
            )
        }
        return null
    }

    private fun confluenceSignal(core: Core): StrategySignal? {
        val i = core.index
        val t = core.technical
        val atr = t.atr14
        if (i < 60 || atr <= 0.0) return null
        val bar = core.candles[i]
        var bullScore = 0
        var bearScore = 0
        if (bar.close > t.ema20 && t.ema20 > t.ema50) bullScore++
        if (bar.close < t.ema20 && t.ema20 < t.ema50) bearScore++
        if (t.rsi14 < 40.0) bullScore++
        if (t.rsi14 > 60.0) bearScore++
        if (core.structure.bias == Bias.BULLISH) bullScore++
        if (core.structure.bias == Bias.BEARISH) bearScore++
        if (t.relativeVolume20 > 1.3) { bullScore++; bearScore++ }
        core.smc.orderBlocks.lastOrNull { !it.mitigated && it.type == OrderBlockType.BULLISH }?.let {
            if (abs(bar.close - (it.highPrice + it.lowPrice) / 2.0) < atr) bullScore++
        }
        core.smc.orderBlocks.lastOrNull { !it.mitigated && it.type == OrderBlockType.BEARISH }?.let {
            if (abs(bar.close - (it.highPrice + it.lowPrice) / 2.0) < atr) bearScore++
        }
        if (bullScore >= 3 && bullScore > bearScore) {
            return StrategySignal(
                index = i,
                timestamp = bar.timestamp,
                direction = Direction.BULLISH,
                entry = bar.close,
                stopLoss = bar.close - atr * 2.0,
                takeProfit = bar.close + atr * 6.0,
                confidence = (50 + bullScore * 10).coerceIn(50, 95),
                setupType = "CONFLUENCE_BULL",
            )
        }
        if (bearScore >= 3 && bearScore > bullScore) {
            return StrategySignal(
                index = i,
                timestamp = bar.timestamp,
                direction = Direction.BEARISH,
                entry = bar.close,
                stopLoss = bar.close + atr * 2.0,
                takeProfit = bar.close - atr * 6.0,
                confidence = (50 + bearScore * 10).coerceIn(50, 95),
                setupType = "CONFLUENCE_BEAR",
            )
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
                return StrategySignal(
                    index = i,
                    timestamp = bar.timestamp,
                    direction = Direction.BULLISH,
                    entry = mid,
                    stopLoss = sl,
                    takeProfit = mid + risk * 2.5,
                    confidence = (60 + (1.0 - fvg.fillPercent) * 30).roundToInt().coerceIn(55, 90),
                    setupType = "FVG_RETEST_BULL",
                )
            }
            if (fvg.type == FvgType.BEARISH && core.structure.bias == Bias.BEARISH && bar.high >= mid && bar.close < mid) {
                val sl = fvg.highPrice + atr * 0.15
                val risk = sl - mid
                if (risk <= 0.0) continue
                return StrategySignal(
                    index = i,
                    timestamp = bar.timestamp,
                    direction = Direction.BEARISH,
                    entry = mid,
                    stopLoss = sl,
                    takeProfit = mid - risk * 2.5,
                    confidence = (60 + (1.0 - fvg.fillPercent) * 30).roundToInt().coerceIn(55, 90),
                    setupType = "FVG_RETEST_BEAR",
                )
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
        const val DIRECTION_BALANCE_RATIO = 0.85
    }
}
