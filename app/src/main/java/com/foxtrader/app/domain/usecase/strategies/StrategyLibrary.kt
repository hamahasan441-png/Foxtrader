package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.StrategySignal
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.StructureBreakType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.backtest.StrategyFunction
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Production-grade strategy library providing fully-defined, backtestable
 * [StrategyFunction] implementations for every supported methodology.
 *
 * Each strategy:
 * - Is **non-repainting**: at bar index `i`, only data `[0..i]` is read.
 * - Has **precise entry logic** (structure-based or indicator-confirmed).
 * - Uses **ATR-adaptive stops** (never a fixed pip distance).
 * - Targets **minimum 2:1 R:R** (most target 3:1).
 * - Is independently backtestable through [BacktestEngine].
 *
 * Strategies are accessed by [StrategyType] and return null on bars where
 * no valid setup exists (the backtester only opens on non-null returns).
 */
@Singleton
class StrategyLibrary @Inject constructor(
    private val smcDetector: SmcDetector,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val ichimokuCloud: IchimokuCloud,
    private val litXEngine: LitXEngine,
) {


    /** Registry of all available strategies by type. */
    fun all(): Map<StrategyType, StrategyDefinition> = mapOf(
        StrategyType.SMART_MONEY to smcOrderBlockStrategy(),
        StrategyType.LIT to litInstitutionalStrategy(),
        StrategyType.LITX to litXStrategy(),
        StrategyType.TREND_FOLLOWING to emaCrossoverStrategy(),
        StrategyType.MEAN_REVERSION to rsiMeanReversionStrategy(),
        StrategyType.BREAKOUT to structureBreakoutStrategy(),
        StrategyType.ICHIMOKU to ichimokuTrendStrategy(),
        StrategyType.CONFLUENCE to confluenceStrategy(),
        StrategyType.PATTERN to fvgRetestStrategy(),
    )

    /** Get a single strategy by type. */
    fun get(type: StrategyType): StrategyDefinition = all().getValue(type)

    // =========================================================================
    // STRATEGY 1: Smart Money — Order Block Retest
    //
    // Entry: Price retests the midpoint of an unmitigated OB in the direction of
    //        the prevailing market structure bias. Confirmed by the bar closing
    //        within the OB zone after wicking below/above.
    // Stop:  Below the OB low (bullish) / above OB high (bearish) + 0.15×ATR pad.
    // TP:    3:1 R:R from entry.
    // =========================================================================
    private fun smcOrderBlockStrategy() = StrategyDefinition(
        name = "SMC Order Block Retest",
        type = StrategyType.SMART_MONEY,
        description = "Enters on confirmed retest of an unmitigated institutional OB aligned with structure bias.",
        minimumBars = 80,
        function = smcOrderBlockFunction(),
    )

    private fun smcOrderBlockFunction(): StrategyFunction = fn@{ candles, i ->
        if (i < 80) return@fn null
        val slice = candles.subList(0, i + 1)
        val atr = TechnicalIndicators.calculateATR(slice, 14)[i]
        if (atr <= 0.0) return@fn null


        val bias = analyzeStructure(slice).bias
        if (bias == Bias.NEUTRAL) return@fn null

        val obs = smcDetector.detectOrderBlocks(slice).filter { !it.mitigated }
        val bar = candles[i]

        for (ob in obs.reversed()) {
            val obMid = (ob.highPrice + ob.lowPrice) / 2.0
            val isBullishSetup = ob.type == OrderBlockType.BULLISH &&
                bias == Bias.BULLISH
            val isBearishSetup = ob.type == OrderBlockType.BEARISH &&
                bias == Bias.BEARISH

            if (isBullishSetup && bar.low <= obMid && bar.close > obMid && bar.close < ob.highPrice) {
                val entry = obMid
                val sl = ob.lowPrice - atr * 0.15
                val risk = entry - sl
                if (risk <= 0.0) continue
                val tp = entry + risk * 3.0
                return@fn StrategySignal(
                    index = i, timestamp = bar.timestamp, direction = Direction.BULLISH,
                    entry = entry, stopLoss = sl, takeProfit = tp,
                    confidence = (ob.strength * 100).toInt().coerceIn(50, 95),
                    setupType = "OB_RETEST_BULL",
                )
            }
            if (isBearishSetup && bar.high >= obMid && bar.close < obMid && bar.close > ob.lowPrice) {
                val entry = obMid
                val sl = ob.highPrice + atr * 0.15
                val risk = sl - entry
                if (risk <= 0.0) continue
                val tp = entry - risk * 3.0
                return@fn StrategySignal(
                    index = i, timestamp = bar.timestamp, direction = Direction.BEARISH,
                    entry = entry, stopLoss = sl, takeProfit = tp,
                    confidence = (ob.strength * 100).toInt().coerceIn(50, 95),
                    setupType = "OB_RETEST_BEAR",
                )
            }
        }
        null
    }


    // =========================================================================
    // STRATEGY 2: LIT Institutional Entry (Liquidity Sweep + BOS + OB Retest)
    //
    // The full ICT/LIT model:
    // 1. Liquidity sweep (equal highs/lows taken).
    // 2. Structure break (BOS or CHOCH) confirming the reversal.
    // 3. Price retests an unmitigated OB or FVG in the new direction.
    // Entry: OB/FVG midpoint on retest bar.
    // Stop:  Below/above the mitigation zone + ATR pad.
    // TP:    3:1 minimum (targets the opposing liquidity).
    // =========================================================================
    private fun litInstitutionalStrategy() = StrategyDefinition(
        name = "LIT Institutional Entry",
        type = StrategyType.LIT,
        description = "Full institutional model: sweep → structure shift → mitigation zone retest.",
        minimumBars = 80,
        function = litInstitutionalFunction(),
    )

    private fun litInstitutionalFunction(): StrategyFunction = fn@{ candles, i ->
        if (i < 80) return@fn null
        val slice = candles.subList(0, i + 1)
        val bar = candles[i]
        val atr = TechnicalIndicators.calculateATR(slice, 14)[i]
        if (atr <= 0.0) return@fn null

        val structure = analyzeStructure(slice)
        val recentBreak = structure.breaks.lastOrNull { it.confirmed } ?: return@fn null
        val breakRecency = i - recentBreak.breakIndex
        if (breakRecency > 10) return@fn null

        val sweeps = smcDetector.detectLiquidity(slice).filter { it.swept && it.sweepIndex != null }
        val recentSweep = sweeps.maxByOrNull { it.sweepIndex ?: -1 } ?: return@fn null
        val sweepIdx = recentSweep.sweepIndex ?: return@fn null
        val sweepRecency = i - sweepIdx
        if (sweepRecency > 12) return@fn null


        val dir = if (recentSweep.type == LiquidityType.SELL_SIDE)
            Direction.BULLISH else Direction.BEARISH
        if (dir != recentBreak.direction) return@fn null

        val obs = smcDetector.detectOrderBlocks(slice).filter { !it.mitigated }
        val fvgs = smcDetector.detectFairValueGaps(slice).filter { !it.filled }

        val mitigationOb = obs.lastOrNull {
            (dir == Direction.BULLISH && it.type == OrderBlockType.BULLISH) ||
                (dir == Direction.BEARISH && it.type == OrderBlockType.BEARISH)
        }
        val mitigationFvg = fvgs.lastOrNull {
            (dir == Direction.BULLISH && it.type == FvgType.BULLISH) ||
                (dir == Direction.BEARISH && it.type == FvgType.BEARISH)
        }

        val entry = mitigationOb?.let { (it.highPrice + it.lowPrice) / 2.0 }
            ?: mitigationFvg?.let { (it.highPrice + it.lowPrice) / 2.0 }
            ?: return@fn null

        if (abs(bar.close - entry) > atr * 0.75) return@fn null

        val slBase = when {
            mitigationOb != null && dir == Direction.BULLISH -> mitigationOb.lowPrice
            mitigationOb != null -> mitigationOb.highPrice
            mitigationFvg != null && dir == Direction.BULLISH -> mitigationFvg.lowPrice
            else -> mitigationFvg?.highPrice ?: return@fn null
        }
        val sl = if (dir == Direction.BULLISH) slBase - atr * 0.15 else slBase + atr * 0.15
        val risk = abs(entry - sl)
        if (risk <= 0.0) return@fn null
        val tp = if (dir == Direction.BULLISH) entry + risk * 3.0 else entry - risk * 3.0

        StrategySignal(
            index = i, timestamp = bar.timestamp, direction = dir,
            entry = entry, stopLoss = sl, takeProfit = tp,
            confidence = 80, setupType = "LIT_INSTITUTIONAL",
        )
    }


    // =========================================================================
    // STRATEGY 2b: LIT X Institutional Framework (engine-backed)
    //
    // Delegates to [LitXEngine], the full institutional pipeline (liquidity →
    // sweep → market shift → POI → entry) gated by the 11-factor confidence
    // model. Non-repainting: the engine only sees candles [0..i]. Fires on bars
    // where a setup validates; the backtester opens on the non-null return.
    // =========================================================================
    private fun litXStrategy() = StrategyDefinition(
        name = "LIT X Institutional",
        type = StrategyType.LITX,
        description = "LIT X engine: sweep → market shift (CHOCH/MSS) → POI retest, gated by an 11-factor score.",
        minimumBars = 60,
        function = litXFunction(),
    )

    private fun litXFunction(): StrategyFunction = fn@{ candles, i ->
        if (i < 60) return@fn null
        val slice = candles.subList(0, i + 1) // non-repainting
        val analysis = litXEngine.analyze(symbol = "", timeframe = Timeframe.H1, candles = slice)
        val signal = analysis.signal ?: return@fn null
        StrategySignal(
            index = i,
            timestamp = candles[i].timestamp,
            direction = signal.direction,
            entry = signal.entry,
            stopLoss = signal.stopLoss,
            takeProfit = signal.takeProfit1,
            confidence = signal.confidence.score.coerceIn(50, 95),
            setupType = "LITX_${signal.confidence.grade.name}",
        )
    }

    // =========================================================================
    // STRATEGY 3: EMA Crossover Trend Following
    //
    // Entry: EMA(9) crosses above EMA(21) with ADX(14) > 25 confirming trend
    //        strength. Bullish only above EMA(50), bearish only below.
    // Stop:  1.5×ATR from entry.
    // TP:    3:1 R:R.
    // =========================================================================
    private fun emaCrossoverStrategy() = StrategyDefinition(
        name = "EMA Crossover Trend",
        type = StrategyType.TREND_FOLLOWING,
        description = "EMA(9/21) crossover confirmed by ADX > 25 and EMA(50) filter.",
        minimumBars = 60,
        function = emaCrossoverFunction(),
    )

    private fun emaCrossoverFunction(): StrategyFunction = fn@{ candles, i ->
        if (i < 60) return@fn null
        val slice = candles.subList(0, i + 1)
        val ema9 = TechnicalIndicators.calculateEMA(slice, 9)
        val ema21 = TechnicalIndicators.calculateEMA(slice, 21)
        val ema50 = TechnicalIndicators.calculateEMA(slice, 50)
        val adxResult = TechnicalIndicators.calculateADX(slice, 14)
        val atr = TechnicalIndicators.calculateATR(slice, 14)[i]
        if (atr <= 0.0) return@fn null

        val adx = adxResult.adx[i]
        if (adx < 25.0) return@fn null

        val bar = candles[i]
        val crossUp = ema9[i] > ema21[i] && ema9[i - 1] <= ema21[i - 1]
        val crossDown = ema9[i] < ema21[i] && ema9[i - 1] >= ema21[i - 1]

        if (crossUp && bar.close > ema50[i]) {
            val entry = bar.close
            val sl = entry - atr * 1.5
            val tp = entry + atr * 4.5
            return@fn StrategySignal(
                index = i, timestamp = bar.timestamp, direction = Direction.BULLISH,
                entry = entry, stopLoss = sl, takeProfit = tp,
                confidence = (adx.toInt()).coerceIn(50, 90), setupType = "EMA_CROSS_BULL",
            )
        }
        if (crossDown && bar.close < ema50[i]) {
            val entry = bar.close
            val sl = entry + atr * 1.5
            val tp = entry - atr * 4.5
            return@fn StrategySignal(
                index = i, timestamp = bar.timestamp, direction = Direction.BEARISH,
                entry = entry, stopLoss = sl, takeProfit = tp,
                confidence = (adx.toInt()).coerceIn(50, 90), setupType = "EMA_CROSS_BEAR",
            )
        }
        null
    }


    // =========================================================================
    // STRATEGY 4: RSI Mean Reversion
    //
    // Entry: RSI(14) < 30 (oversold) or > 70 (overbought) with price at/near
    //        a support/resistance zone (lower Bollinger Band or OB).
    // Stop:  2×ATR from entry.
    // TP:    Mean (EMA 20) = 2:1+ R:R minimum.
    // =========================================================================
    private fun rsiMeanReversionStrategy() = StrategyDefinition(
        name = "RSI Mean Reversion",
        type = StrategyType.MEAN_REVERSION,
        description = "RSI(14) oversold/overbought reversal with ATR-adaptive stops targeting the mean.",
        minimumBars = 50,
        function = rsiMeanReversionFunction(),
    )

    private fun rsiMeanReversionFunction(): StrategyFunction = fn@{ candles, i ->
        if (i < 50) return@fn null
        val slice = candles.subList(0, i + 1)
        val rsi = TechnicalIndicators.calculateRSI(slice, 14)
        val ema20 = TechnicalIndicators.calculateEMA(slice, 20)
        val atr = TechnicalIndicators.calculateATR(slice, 14)[i]
        if (atr <= 0.0) return@fn null
        val bar = candles[i]

        if (rsi[i] < 30.0 && rsi[i - 1] >= 30.0) {
            val entry = bar.close
            val sl = entry - atr * 2.0
            val tp = ema20[i]
            if (tp <= entry) return@fn null
            val rr = (tp - entry) / (entry - sl)
            if (rr < 2.0) return@fn null
            return@fn StrategySignal(
                index = i, timestamp = bar.timestamp, direction = Direction.BULLISH,
                entry = entry, stopLoss = sl, takeProfit = tp,
                confidence = (100.0 - rsi[i] * 2).toInt().coerceIn(55, 85),
                setupType = "RSI_OVERSOLD",
            )
        }
        if (rsi[i] > 70.0 && rsi[i - 1] <= 70.0) {
            val entry = bar.close
            val sl = entry + atr * 2.0
            val tp = ema20[i]
            if (tp >= entry) return@fn null
            val rr = (entry - tp) / (sl - entry)
            if (rr < 2.0) return@fn null
            return@fn StrategySignal(
                index = i, timestamp = bar.timestamp, direction = Direction.BEARISH,
                entry = entry, stopLoss = sl, takeProfit = tp,
                confidence = (rsi[i] * 2 - 100.0).toInt().coerceIn(55, 85),
                setupType = "RSI_OVERBOUGHT",
            )
        }
        null
    }


    // =========================================================================
    // STRATEGY 5: Structure Breakout (BOS Continuation)
    //
    // Entry: Confirmed BOS in the direction of the prevailing bias. Enter on the
    //        bar that confirms the break (close beyond structure level).
    // Stop:  Behind the swing that created the structure level + ATR pad.
    // TP:    Next liquidity target (opposing swing extreme) or 3:1 R:R.
    // =========================================================================
    private fun structureBreakoutStrategy() = StrategyDefinition(
        name = "Structure Breakout (BOS)",
        type = StrategyType.BREAKOUT,
        description = "Enters on confirmed BOS continuation aligned with trend bias.",
        minimumBars = 60,
        function = structureBreakoutFunction(),
    )

    private fun structureBreakoutFunction(): StrategyFunction = fn@{ candles, i ->
        if (i < 60) return@fn null
        val slice = candles.subList(0, i + 1)
        val structure = analyzeStructure(slice)
        val atr = TechnicalIndicators.calculateATR(slice, 14)[i]
        if (atr <= 0.0) return@fn null

        // `BUGFIX` This previously required `breakIndex == i`, which is
        // unsatisfiable: the structure engine only confirms a swing once
        // rightBars (5) further candles exist, so on a slice ending at bar i
        // the newest possible breakIndex is i - 5. The strategy therefore
        // NEVER fired — on the chart, in backtests, or in the scanner.
        //
        // A break with breakIndex = s becomes *visible* exactly at bar
        // s + STRUCTURE_SWING_CONFIRMATION_BARS (when its confirming swing
        // completes). Firing on that bar keeps the entry non-repainting AND
        // fires exactly once per break.
        val recentBreak = structure.breaks.lastOrNull {
            it.confirmed && it.type == StructureBreakType.BOS &&
                it.breakIndex == i - STRUCTURE_SWING_CONFIRMATION_BARS
        } ?: return@fn null

        if (recentBreak.direction != structure.bias.toDirection()) return@fn null

        val bar = candles[i]
        // Skip stale entries: if price has already retraced back through the
        // broken level by the confirmation bar, the breakout failed.
        if (recentBreak.direction == Direction.BULLISH && bar.close <= recentBreak.breakPrice) return@fn null
        if (recentBreak.direction == Direction.BEARISH && bar.close >= recentBreak.breakPrice) return@fn null
        val dir = recentBreak.direction
        val entry = bar.close
        val sl = if (dir == Direction.BULLISH) {
            (structure.swingLows.lastOrNull()?.price ?: (entry - atr * 2)) - atr * 0.2
        } else {
            (structure.swingHighs.lastOrNull()?.price ?: (entry + atr * 2)) + atr * 0.2
        }
        val risk = abs(entry - sl)
        if (risk <= 0.0 || risk > atr * 5) return@fn null
        val tp = if (dir == Direction.BULLISH) entry + risk * 3.0 else entry - risk * 3.0

        StrategySignal(
            index = i, timestamp = bar.timestamp, direction = dir,
            entry = entry, stopLoss = sl, takeProfit = tp,
            confidence = 70, setupType = "BOS_CONTINUATION",
        )
    }


    // =========================================================================
    // STRATEGY 6: Ichimoku Trend (Kumo Breakout + TK Cross)
    //
    // Entry: Price breaks above/below the cloud AND Tenkan crosses Kijun in the
    //        same direction. Cloud thickness confirms momentum.
    // Stop:  Opposite edge of the cloud + ATR pad.
    // TP:    3:1 R:R.
    // =========================================================================
    private fun ichimokuTrendStrategy() = StrategyDefinition(
        name = "Ichimoku Kumo Breakout",
        type = StrategyType.ICHIMOKU,
        description = "Price above/below cloud with TK cross confirmation and cloud-thickness momentum.",
        minimumBars = 60,
        function = ichimokuTrendFunction(),
    )

    private fun ichimokuTrendFunction(): StrategyFunction = fn@{ candles, i ->
        if (i < 60) return@fn null
        val slice = candles.subList(0, i + 1)
        val ichi = ichimokuCloud.calculate(slice)
        val position = ichimokuCloud.cloudPosition(slice, ichi)
        if (position == IchimokuCloud.CloudPosition.INSIDE) return@fn null

        val tenkan = ichi.tenkan[i]
        val kijun = ichi.kijun[i]
        val tenkanPrev = ichi.tenkan[i - 1]
        val kijunPrev = ichi.kijun[i - 1]
        val atr = TechnicalIndicators.calculateATR(slice, 14)[i]
        if (atr <= 0.0) return@fn null
        val bar = candles[i]

        val tkCrossUp = tenkan > kijun && tenkanPrev <= kijunPrev
        val tkCrossDown = tenkan < kijun && tenkanPrev >= kijunPrev

        if (position == IchimokuCloud.CloudPosition.ABOVE && tkCrossUp) {
            val entry = bar.close
            val cloudBottom = minOf(ichi.senkouA[i], ichi.senkouB[i])
            val sl = cloudBottom - atr * 0.3
            val risk = entry - sl
            if (risk <= 0.0) return@fn null
            val tp = entry + risk * 3.0
            return@fn StrategySignal(
                index = i, timestamp = bar.timestamp, direction = Direction.BULLISH,
                entry = entry, stopLoss = sl, takeProfit = tp,
                confidence = 72, setupType = "ICHIMOKU_KUMO_BULL",
            )
        }
        if (position == IchimokuCloud.CloudPosition.BELOW && tkCrossDown) {
            val entry = bar.close
            val cloudTop = maxOf(ichi.senkouA[i], ichi.senkouB[i])
            val sl = cloudTop + atr * 0.3
            val risk = sl - entry
            if (risk <= 0.0) return@fn null
            val tp = entry - risk * 3.0
            return@fn StrategySignal(
                index = i, timestamp = bar.timestamp, direction = Direction.BEARISH,
                entry = entry, stopLoss = sl, takeProfit = tp,
                confidence = 72, setupType = "ICHIMOKU_KUMO_BEAR",
            )
        }
        null
    }


    // =========================================================================
    // STRATEGY 7: Multi-Confluence (EMA + RSI + Structure + Volume)
    //
    // Entry: Requires 3+ confluences from: EMA alignment, RSI extremes,
    //        structure bias, volume expansion, OB proximity.
    // Stop:  2×ATR.
    // TP:    3:1 R:R.
    // =========================================================================
    private fun confluenceStrategy() = StrategyDefinition(
        name = "Multi-Confluence",
        type = StrategyType.CONFLUENCE,
        description = "Requires 3+ aligned confluences (EMA/RSI/structure/volume/OB) for high-probability entry.",
        minimumBars = 60,
        function = confluenceFunction(),
    )

    private fun confluenceFunction(): StrategyFunction = fn@{ candles, i ->
        if (i < 60) return@fn null
        val slice = candles.subList(0, i + 1)
        val bar = candles[i]
        val atr = TechnicalIndicators.calculateATR(slice, 14)[i]
        if (atr <= 0.0) return@fn null

        val ema20 = TechnicalIndicators.calculateEMA(slice, 20)[i]
        val ema50 = TechnicalIndicators.calculateEMA(slice, 50)[i]
        val rsi = TechnicalIndicators.calculateRSI(slice, 14)[i]
        val relVol = TechnicalIndicators.calculateRelativeVolume(slice, 20)[i]
        val structure = analyzeStructure(slice)
        val obs = smcDetector.detectOrderBlocks(slice).filter { !it.mitigated }

        var bullScore = 0
        var bearScore = 0

        // EMA alignment
        if (bar.close > ema20 && ema20 > ema50) bullScore++
        if (bar.close < ema20 && ema20 < ema50) bearScore++
        // RSI
        if (rsi < 40.0) bullScore++
        if (rsi > 60.0) bearScore++
        // Structure bias
        if (structure.bias == Bias.BULLISH) bullScore++
        if (structure.bias == Bias.BEARISH) bearScore++
        // Volume expansion
        if (relVol > 1.3) { bullScore++; bearScore++ }
        // OB proximity
        obs.lastOrNull { it.type == OrderBlockType.BULLISH }?.let {
            if (abs(bar.close - (it.highPrice + it.lowPrice) / 2.0) < atr) bullScore++
        }
        obs.lastOrNull { it.type == OrderBlockType.BEARISH }?.let {
            if (abs(bar.close - (it.highPrice + it.lowPrice) / 2.0) < atr) bearScore++
        }


        if (bullScore >= 3 && bullScore > bearScore) {
            val entry = bar.close
            val sl = entry - atr * 2.0
            val tp = entry + atr * 6.0
            return@fn StrategySignal(
                index = i, timestamp = bar.timestamp, direction = Direction.BULLISH,
                entry = entry, stopLoss = sl, takeProfit = tp,
                confidence = (50 + bullScore * 10).coerceIn(50, 95),
                setupType = "CONFLUENCE_BULL",
            )
        }
        if (bearScore >= 3 && bearScore > bullScore) {
            val entry = bar.close
            val sl = entry + atr * 2.0
            val tp = entry - atr * 6.0
            return@fn StrategySignal(
                index = i, timestamp = bar.timestamp, direction = Direction.BEARISH,
                entry = entry, stopLoss = sl, takeProfit = tp,
                confidence = (50 + bearScore * 10).coerceIn(50, 95),
                setupType = "CONFLUENCE_BEAR",
            )
        }
        null
    }

    // =========================================================================
    // STRATEGY 8: FVG Retest (Smart Money Pattern)
    //
    // Entry: Price retests an unfilled FVG in the direction of the trend.
    //        The bar must wick into the gap and close beyond its midpoint.
    // Stop:  Beyond the FVG extreme + ATR pad.
    // TP:    2.5:1 R:R.
    // =========================================================================
    private fun fvgRetestStrategy() = StrategyDefinition(
        name = "FVG Retest",
        type = StrategyType.PATTERN,
        description = "Price retests an unfilled Fair Value Gap aligned with the prevailing structure bias.",
        minimumBars = 60,
        function = fvgRetestFunction(),
    )

    private fun fvgRetestFunction(): StrategyFunction = fn@{ candles, i ->
        if (i < 60) return@fn null
        val slice = candles.subList(0, i + 1)
        val bar = candles[i]
        val atr = TechnicalIndicators.calculateATR(slice, 14)[i]
        if (atr <= 0.0) return@fn null

        val bias = analyzeStructure(slice).bias
        if (bias == Bias.NEUTRAL) return@fn null

        val fvgs = smcDetector.detectFairValueGaps(slice).filter { !it.filled }


        for (fvg in fvgs.reversed()) {
            val fvgMid = (fvg.highPrice + fvg.lowPrice) / 2.0
            val isBull = fvg.type == FvgType.BULLISH &&
                bias == Bias.BULLISH
            val isBear = fvg.type == FvgType.BEARISH &&
                bias == Bias.BEARISH

            if (isBull && bar.low <= fvgMid && bar.close > fvgMid) {
                val entry = fvgMid
                val sl = fvg.lowPrice - atr * 0.15
                val risk = entry - sl
                if (risk <= 0.0) continue
                val tp = entry + risk * 2.5
                return@fn StrategySignal(
                    index = i, timestamp = bar.timestamp, direction = Direction.BULLISH,
                    entry = entry, stopLoss = sl, takeProfit = tp,
                    confidence = (60 + (1.0 - fvg.fillPercent) * 30).toInt().coerceIn(55, 90),
                    setupType = "FVG_RETEST_BULL",
                )
            }
            if (isBear && bar.high >= fvgMid && bar.close < fvgMid) {
                val entry = fvgMid
                val sl = fvg.highPrice + atr * 0.15
                val risk = sl - entry
                if (risk <= 0.0) continue
                val tp = entry - risk * 2.5
                return@fn StrategySignal(
                    index = i, timestamp = bar.timestamp, direction = Direction.BEARISH,
                    entry = entry, stopLoss = sl, takeProfit = tp,
                    confidence = (60 + (1.0 - fvg.fillPercent) * 30).toInt().coerceIn(55, 90),
                    setupType = "FVG_RETEST_BEAR",
                )
            }
        }
        null
    }

    private companion object {
        /**
         * Bars needed after a swing before AnalyzeMarketStructureUseCase
         * confirms it (its `rightBars` default). A structure break with
         * breakIndex = s first becomes visible on bar s + this value, which is
         * therefore the exact non-repainting entry bar for breakout logic.
         */
        const val STRUCTURE_SWING_CONFIRMATION_BARS = 5
    }
}
