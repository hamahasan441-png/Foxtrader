package com.foxtrader.app.domain.usecase.scanner

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.ScreenerOutput
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.ScreenerSymbol
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.WatchlistCategory
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.analysis.WyckoffDetector
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.patterns.CandlePatternDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-asset Screener / Scanner.
 *
 * Scans all enabled watchlist symbols using EMA, ADX, RSI, Momentum, ATR.
 * Ranks results by composite score and assigns "Best" category badges.
 *
 * Pure domain logic — no platform dependencies.
 */
class ScannerUseCase @Inject constructor(
    private val smcDetector: SmcDetector,
    private val candlePatternDetector: CandlePatternDetector,
    private val ichimokuCloud: IchimokuCloud,
    private val bollingerBands: BollingerBands,
    private val wyckoffDetector: WyckoffDetector,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
) {
    private var watchlist: MutableList<ScreenerSymbol> = DEFAULT_WATCHLIST.toMutableList()

    /**
     * Scan all watchlist symbols.
     * @param dataMap symbol → candle data (at least 50 bars each)
     */
    operator fun invoke(
        dataMap: Map<String, List<Candle>>,
        strategy: StrategyType = StrategyType.CONFLUENCE,
    ): ScreenerOutput {
        val results = mutableListOf<ScreenerResult>()

        for (ws in watchlist) {
            if (!ws.enabled) continue
            val candles = dataMap[ws.symbol] ?: continue
            if (candles.size < 50) continue
            results += analyzeSymbol(ws, candles, strategy)
        }

        // Sort by score descending
        results.sortByDescending { it.score }

        // Assign category badges
        val buys = results.filter { it.direction == Direction.BULLISH }.sortedByDescending { it.score }
        val sells = results.filter { it.direction == Direction.BEARISH }.sortedByDescending { it.score }
        val swings = results.sortedByDescending { it.trendStrength }
        val scalps = results.sortedByDescending { it.volatility }
        val longterm = results.sortedByDescending { it.trendStrength * it.score }

        val categorized = results.map { it.copy(categories = mutableListOf()) }.toMutableList()
        fun tag(list: List<ScreenerResult>, cat: WatchlistCategory) {
            val top = list.firstOrNull() ?: return
            val idx = categorized.indexOfFirst { it.symbol == top.symbol }
            if (idx >= 0) {
                val current = categorized[idx]
                categorized[idx] = current.copy(categories = current.categories + cat)
            }
        }
        tag(buys, WatchlistCategory.BEST_BUY)
        tag(sells, WatchlistCategory.BEST_SELL)
        tag(swings, WatchlistCategory.BEST_SWING)
        tag(scalps, WatchlistCategory.BEST_SCALP)
        tag(longterm, WatchlistCategory.BEST_LONG_TERM)

        return ScreenerOutput(
            results = categorized,
            bestBuy = buys.firstOrNull(),
            bestSell = sells.firstOrNull(),
            bestSwing = swings.firstOrNull(),
            bestScalp = scalps.firstOrNull(),
            bestLongTerm = longterm.firstOrNull(),
            scannedAt = System.currentTimeMillis(),
            totalSymbols = categorized.size,
        )
    }

    // ========================================================================
    // ANALYSIS
    // ========================================================================

    private fun analyzeSymbol(
        ws: ScreenerSymbol,
        candles: List<Candle>,
        strategy: StrategyType,
    ): ScreenerResult {
        val last = candles.lastIndex
        val price = candles[last].close
        val start = (last - 20).coerceAtLeast(0)
        val changePercent = ((price - candles[start].close) / candles[start].close) * 100.0

        val ema20 = TechnicalIndicators.calculateEMA(candles, 20)
        val ema50 = TechnicalIndicators.calculateEMA(candles, 50)
        val adxResult = TechnicalIndicators.calculateADX(candles)
        val rsi = TechnicalIndicators.calculateRSI(candles)
        val mom = TechnicalIndicators.calculateMomentum(candles)
        val atr = TechnicalIndicators.calculateATR(candles, 14)

        val trendStrength = min(100.0, adxResult.adx[last] * 2.0)
        val momentum = min(100.0, 50.0 + abs(mom[last]) * 5.0)
        val volatility = min(100.0, (atr[last] / price) * 100.0 * 50.0)
        val signal = when (strategy) {
            StrategyType.CONFLUENCE -> confluenceSignal(
                changePercent = changePercent,
                ema20 = ema20[last],
                ema50 = ema50[last],
                plusDi = adxResult.plusDI[last],
                minusDi = adxResult.minusDI[last],
                adx = adxResult.adx[last],
                rsi = rsi[last],
                trendStrength = trendStrength,
                momentum = momentum,
                volatility = volatility,
            )
            StrategyType.TREND_FOLLOWING -> trendSignal(
                ema20 = ema20[last],
                ema50 = ema50[last],
                adx = adxResult.adx[last],
                plusDi = adxResult.plusDI[last],
                minusDi = adxResult.minusDI[last],
                momentum = mom[last],
                changePercent = changePercent,
            )
            StrategyType.MEAN_REVERSION -> meanReversionSignal(
                price = price,
                rsi = rsi[last],
                boll = bollingerBands.calculate(candles),
                atr = atr[last],
                lastIndex = last,
            )
            StrategyType.BREAKOUT -> breakoutSignal(
                candles = candles,
                lastIndex = last,
                adx = adxResult.adx[last],
                atr = atr[last],
                momentum = mom[last],
            )
            StrategyType.SMART_MONEY -> smartMoneySignal(
                candles = candles,
                price = price,
                atr = atr[last],
            )
            StrategyType.LIT -> litSignal(
                candles = candles,
                price = price,
                atr = atr[last],
            )
            StrategyType.ICHIMOKU -> ichimokuSignal(
                candles = candles,
                changePercent = changePercent,
                momentum = momentum,
            )
            StrategyType.PATTERN -> patternSignal(
                candles = candles,
                atr = atr[last],
            )
        }

        return ScreenerResult(
            symbol = ws.symbol,
            assetClass = ws.assetClass,
            strategy = strategy,
            direction = signal.direction,
            score = signal.score,
            bias = if (signal.direction == Direction.BULLISH) Bias.BULLISH else Bias.BEARISH,
            trendStrength = trendStrength,
            momentum = momentum,
            volatility = volatility,
            setupQuality = signal.setupQuality,
            categories = emptyList(),
            tags = signal.tags,
            lastPrice = price,
            changePercent = changePercent,
        )
    }

    private data class StrategySignalSnapshot(
        val direction: Direction,
        val score: Int,
        val setupQuality: Double,
        val tags: List<String>,
    )

    private fun confluenceSignal(
        changePercent: Double,
        ema20: Double,
        ema50: Double,
        plusDi: Double,
        minusDi: Double,
        adx: Double,
        rsi: Double,
        trendStrength: Double,
        momentum: Double,
        volatility: Double,
    ): StrategySignalSnapshot {
        val bullEma = ema20 > ema50
        val diUp = plusDi > minusDi
        val direction = if ((bullEma && diUp) || changePercent > 0.5) Direction.BULLISH else Direction.BEARISH
        val rsiScore = abs(rsi - 50.0)
        val setupQuality = min(100.0, trendStrength * 0.4 + momentum * 0.3 + rsiScore * 0.3)
        val score = min(100, (setupQuality * 0.6 + trendStrength * 0.3 + if (bullEma == diUp) 10.0 else 0.0).roundToInt())
        val tags = buildList {
            if (adx > 25) add("TRENDING")
            if (rsi > 70) add("OVERBOUGHT")
            if (rsi < 30) add("OVERSOLD")
            if (volatility > 60) add("HIGH_VOL")
            if (abs(changePercent) > 2) add("MOVER")
        }
        return StrategySignalSnapshot(direction, score, setupQuality, tags)
    }

    private fun trendSignal(
        ema20: Double,
        ema50: Double,
        adx: Double,
        plusDi: Double,
        minusDi: Double,
        momentum: Double,
        changePercent: Double,
    ): StrategySignalSnapshot {
        val bullish = ema20 >= ema50 && plusDi >= minusDi
        val alignment = if ((ema20 >= ema50) == (plusDi >= minusDi)) 12.0 else 0.0
        val score = (adx * 2.1 + abs(momentum) * 6.0 + abs(changePercent) * 4.0 + alignment)
            .roundToInt()
            .coerceIn(0, 100)
        return StrategySignalSnapshot(
            direction = if (bullish) Direction.BULLISH else Direction.BEARISH,
            score = score,
            setupQuality = score.toDouble(),
            tags = listOfNotNull(
                "EMA_STACK",
                if (adx >= 25) "STRONG_TREND" else null,
                if (abs(changePercent) >= 1.0) "FOLLOW_THROUGH" else null,
            ),
        )
    }

    private fun meanReversionSignal(
        price: Double,
        rsi: Double,
        boll: BollingerBands.BollingerResult,
        atr: Double,
        lastIndex: Int,
    ): StrategySignalSnapshot {
        val upper = boll.upper.getOrNull(lastIndex) ?: price
        val lower = boll.lower.getOrNull(lastIndex) ?: price
        val distance = max(abs(price - upper), abs(price - lower))
        val bullish = rsi <= 40.0 || price <= lower
        val score = (
            abs(rsi - 50.0) * 1.6 +
                (if (price <= lower || price >= upper) 20 else 0) +
                distance / atr * 8
            )
            .roundToInt()
            .coerceIn(0, 100)
        return StrategySignalSnapshot(
            direction = if (bullish) Direction.BULLISH else Direction.BEARISH,
            score = score,
            setupQuality = score.toDouble(),
            tags = listOfNotNull(
                if (price <= lower) "LOWER_BAND" else null,
                if (price >= upper) "UPPER_BAND" else null,
                if (rsi < 30) "RSI_OVERSOLD" else null,
                if (rsi > 70) "RSI_OVERBOUGHT" else null,
            ),
        )
    }

    private fun breakoutSignal(
        candles: List<Candle>,
        lastIndex: Int,
        adx: Double,
        atr: Double,
        momentum: Double,
    ): StrategySignalSnapshot {
        val lookback = candles.subList(max(0, lastIndex - 20), lastIndex)
        val breakoutHigh = lookback.maxOfOrNull { it.high } ?: candles[lastIndex].high
        val breakoutLow = lookback.minOfOrNull { it.low } ?: candles[lastIndex].low
        val candle = candles[lastIndex]
        val bullish = candle.close >= breakoutHigh
        val bearish = candle.close <= breakoutLow
        val score = (
            adx * BREAKOUT_ADX_WEIGHT +
                abs(momentum) * BREAKOUT_MOMENTUM_WEIGHT +
                candle.range / atr * BREAKOUT_RANGE_WEIGHT +
                if (bullish || bearish) BREAKOUT_BONUS else 0
            )
            .roundToInt()
            .coerceIn(0, 100)
        return StrategySignalSnapshot(
            direction = if (bullish || !bearish && momentum >= 0) Direction.BULLISH else Direction.BEARISH,
            score = score,
            setupQuality = score.toDouble(),
            tags = listOfNotNull(
                if (bullish) "RANGE_BREAK_HIGH" else null,
                if (bearish) "RANGE_BREAK_LOW" else null,
                if (adx >= 25) "EXPANSION" else null,
            ),
        )
    }

    private fun smartMoneySignal(
        candles: List<Candle>,
        price: Double,
        atr: Double,
    ): StrategySignalSnapshot {
        val activeOrderBlock = smcDetector.detectOrderBlocks(candles).filter { !it.mitigated }
            .minByOrNull { abs(((it.highPrice + it.lowPrice) / 2.0) - price) }
        val activeFvg = smcDetector.detectFairValueGaps(candles).filter { !it.filled }
            .minByOrNull { abs(((it.highPrice + it.lowPrice) / 2.0) - price) }
        val liquidity = smcDetector.detectLiquidity(candles).lastOrNull { !it.swept }
        val bullish = when {
            activeOrderBlock != null -> activeOrderBlock.type == OrderBlockType.BULLISH
            activeFvg != null -> activeFvg.type == FvgType.BULLISH
            liquidity != null -> liquidity.type == LiquidityType.SELL_SIDE
            else -> true
        }
        val distanceScore = listOfNotNull(
            activeOrderBlock?.let { atrSafeDistance(price, (it.highPrice + it.lowPrice) / 2.0, atr) },
            activeFvg?.let { atrSafeDistance(price, (it.highPrice + it.lowPrice) / 2.0, atr) },
        ).maxOrNull() ?: 0.0
        val score = (
            SMC_BASE_SCORE +
                distanceScore * SMC_DISTANCE_WEIGHT +
                (activeOrderBlock?.strength ?: 0.0) * SMC_ORDER_BLOCK_WEIGHT +
                if (liquidity != null) SMC_LIQUIDITY_BONUS else 0
            )
            .roundToInt()
            .coerceIn(0, 100)
        return StrategySignalSnapshot(
            direction = if (bullish) Direction.BULLISH else Direction.BEARISH,
            score = score,
            setupQuality = score.toDouble(),
            tags = listOfNotNull(
                if (activeOrderBlock != null) "ORDER_BLOCK" else null,
                if (activeFvg != null) "FVG" else null,
                if (liquidity != null) "LIQUIDITY" else null,
            ),
        )
    }

    private fun ichimokuSignal(
        candles: List<Candle>,
        changePercent: Double,
        momentum: Double,
    ): StrategySignalSnapshot {
        val result = ichimokuCloud.calculate(candles)
        val position = ichimokuCloud.cloudPosition(candles, result)
        val last = candles.lastIndex
        val bullish = position != IchimokuCloud.CloudPosition.BELOW &&
            result.tenkan[last] >= result.kijun[last]
        val score = (55 + abs(changePercent) * 6 + abs(momentum) * 4 +
            if (position != IchimokuCloud.CloudPosition.INSIDE) 15 else 0)
            .roundToInt()
            .coerceIn(0, 100)
        return StrategySignalSnapshot(
            direction = if (bullish) Direction.BULLISH else Direction.BEARISH,
            score = score,
            setupQuality = score.toDouble(),
            tags = listOf(
                "CLOUD_${position.name}",
                if (result.tenkan[last] >= result.kijun[last]) "TK_BULL" else "TK_BEAR",
            ),
        )
    }

    private fun litSignal(
        candles: List<Candle>,
        price: Double,
        atr: Double,
    ): StrategySignalSnapshot {
        val liquiditySweep = smcDetector.detectLiquidity(candles).lastOrNull { it.swept && it.sweepIndex != null }
        val structureBreak = analyzeStructure(candles).breaks.lastOrNull { it.confirmed }
        val activeOrderBlock = smcDetector.detectOrderBlocks(candles)
            .lastOrNull { !it.mitigated }
        val activeFvg = smcDetector.detectFairValueGaps(candles)
            .lastOrNull { !it.filled }
        val direction = when {
            liquiditySweep?.type == LiquidityType.SELL_SIDE -> Direction.BULLISH
            liquiditySweep?.type == LiquidityType.BUY_SIDE -> Direction.BEARISH
            structureBreak != null -> structureBreak.direction
            activeOrderBlock?.type == OrderBlockType.BULLISH -> Direction.BULLISH
            else -> Direction.BEARISH
        }
        val sweepMatchesBreak = structureBreak != null && liquiditySweep != null &&
            ((liquiditySweep.type == LiquidityType.SELL_SIDE && structureBreak.direction == Direction.BULLISH) ||
                (liquiditySweep.type == LiquidityType.BUY_SIDE && structureBreak.direction == Direction.BEARISH))
        val mitigationScore = listOfNotNull(
            activeOrderBlock?.let { atrSafeDistance(price, (it.highPrice + it.lowPrice) / 2.0, atr) },
            activeFvg?.let { atrSafeDistance(price, (it.highPrice + it.lowPrice) / 2.0, atr) },
        ).maxOrNull() ?: 0.0
        val score = (45 + (if (liquiditySweep != null) 18 else 0) +
            (if (structureBreak != null) 18 else 0) +
            (if (sweepMatchesBreak) 14 else 0) +
            mitigationScore * 10).roundToInt().coerceIn(0, 100)
        return StrategySignalSnapshot(
            direction = direction,
            score = score,
            setupQuality = score.toDouble(),
            tags = listOfNotNull(
                if (liquiditySweep != null) "SWEEP" else null,
                structureBreak?.type?.name,
                if (activeOrderBlock != null) "MITIGATION_OB" else null,
                if (activeFvg != null) "MITIGATION_FVG" else null,
                if (sweepMatchesBreak) "ENTRY_SIGNAL" else null,
            ),
        )
    }

    private fun patternSignal(
        candles: List<Candle>,
        atr: Double,
    ): StrategySignalSnapshot {
        val pattern = candlePatternDetector(candles, lookback = 20)
            .maxByOrNull { it.confidence }
        val wyckoff = wyckoffDetector.detect(candles)
        val direction = when {
            pattern != null -> pattern.direction
            wyckoff.phase == WyckoffDetector.WyckoffPhase.MARKDOWN ||
                wyckoff.phase == WyckoffDetector.WyckoffPhase.DISTRIBUTION -> Direction.BEARISH
            else -> Direction.BULLISH
        }
        val score = (
            (pattern?.confidence ?: 40) +
                (if (wyckoff.confidence >= 60) 10 else 0) +
                (atr / candles.last().close * 1000).coerceAtMost(10.0)
            ).roundToInt().coerceIn(0, 100)
        return StrategySignalSnapshot(
            direction = direction,
            score = score,
            setupQuality = score.toDouble(),
            tags = listOfNotNull(
                pattern?.type?.name?.let(::truncateTag),
                if (wyckoff.phase != WyckoffDetector.WyckoffPhase.UNDEFINED) wyckoff.phase.name else null,
            ),
        )
    }

    private fun atrSafeDistance(price: Double, target: Double, atr: Double): Double {
        val safeAtr = atr.coerceAtLeast(MIN_DIVISOR_THRESHOLD)
        return (1.0 - (abs(price - target) / safeAtr).coerceAtMost(1.0)).coerceIn(0.0, 1.0)
    }

    private fun truncateTag(tag: String): String =
        if (tag.length <= MAX_TAG_LENGTH) tag else tag.take(MAX_TAG_LENGTH - 1) + "…"

    // ========================================================================
    // WATCHLIST MANAGEMENT
    // ========================================================================

    fun addSymbol(symbol: String, assetClass: AssetClass) {
        if (watchlist.none { it.symbol == symbol }) {
            watchlist += ScreenerSymbol(symbol, assetClass)
        }
    }

    fun removeSymbol(symbol: String) {
        watchlist.removeAll { it.symbol == symbol }
    }

    fun toggleSymbol(symbol: String, enabled: Boolean) {
        val idx = watchlist.indexOfFirst { it.symbol == symbol }
        if (idx >= 0) watchlist[idx] = watchlist[idx].copy(enabled = enabled)
    }

    fun getWatchlist(): List<ScreenerSymbol> = watchlist.toList()

    fun getByAssetClass(assetClass: AssetClass): List<ScreenerSymbol> =
        watchlist.filter { it.assetClass == assetClass }

    fun setWatchlist(symbols: List<ScreenerSymbol>) {
        watchlist = symbols.toMutableList()
    }

    // ========================================================================
    // DEFAULT WATCHLIST
    // ========================================================================

    companion object {
        private const val MAX_TAG_LENGTH = 18
        private const val MIN_DIVISOR_THRESHOLD = 1e-9
        private const val BREAKOUT_ADX_WEIGHT = 1.8
        private const val BREAKOUT_MOMENTUM_WEIGHT = 8.0
        private const val BREAKOUT_RANGE_WEIGHT = 12.0
        private const val BREAKOUT_BONUS = 15
        private const val SMC_BASE_SCORE = 55
        private const val SMC_DISTANCE_WEIGHT = 20
        private const val SMC_ORDER_BLOCK_WEIGHT = 25
        private const val SMC_LIQUIDITY_BONUS = 8
        val DEFAULT_WATCHLIST: List<ScreenerSymbol> = listOf(
            // Forex
            ScreenerSymbol("EURUSD", AssetClass.FOREX),
            ScreenerSymbol("GBPUSD", AssetClass.FOREX),
            ScreenerSymbol("USDJPY", AssetClass.FOREX),
            ScreenerSymbol("USDCHF", AssetClass.FOREX),
            ScreenerSymbol("AUDUSD", AssetClass.FOREX),
            ScreenerSymbol("NZDUSD", AssetClass.FOREX),
            ScreenerSymbol("USDCAD", AssetClass.FOREX),
            ScreenerSymbol("EURGBP", AssetClass.FOREX),
            ScreenerSymbol("EURJPY", AssetClass.FOREX),
            ScreenerSymbol("GBPJPY", AssetClass.FOREX),
            // Crypto
            ScreenerSymbol("BTCUSDT", AssetClass.CRYPTO),
            ScreenerSymbol("ETHUSDT", AssetClass.CRYPTO),
            ScreenerSymbol("SOLUSDT", AssetClass.CRYPTO),
            ScreenerSymbol("BNBUSDT", AssetClass.CRYPTO),
            ScreenerSymbol("XRPUSDT", AssetClass.CRYPTO),
            // Stocks
            ScreenerSymbol("AAPL", AssetClass.STOCKS),
            ScreenerSymbol("MSFT", AssetClass.STOCKS),
            ScreenerSymbol("NVDA", AssetClass.STOCKS),
            ScreenerSymbol("TSLA", AssetClass.STOCKS),
            ScreenerSymbol("AMZN", AssetClass.STOCKS),
            // Indices
            ScreenerSymbol("US30", AssetClass.INDICES),
            ScreenerSymbol("NAS100", AssetClass.INDICES),
            ScreenerSymbol("US500", AssetClass.INDICES),
            ScreenerSymbol("DE30", AssetClass.INDICES),
            // Metals
            ScreenerSymbol("XAUUSD", AssetClass.METALS),
            ScreenerSymbol("XAGUSD", AssetClass.METALS),
            // Energy
            ScreenerSymbol("WTIUSD", AssetClass.ENERGY),
            ScreenerSymbol("BRENTUSD", AssetClass.ENERGY),
            // Commodities
            ScreenerSymbol("NATGAS", AssetClass.COMMODITIES),
            ScreenerSymbol("COPPER", AssetClass.COMMODITIES),
        )
    }
}
