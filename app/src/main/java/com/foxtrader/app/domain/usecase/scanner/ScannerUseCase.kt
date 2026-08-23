package com.foxtrader.app.domain.usecase.scanner

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXSignal
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.ScreenerOutput
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.ScreenerSymbol
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.WatchlistCategory
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.analysis.WyckoffDetector
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.patterns.CandlePatternDetector
import com.foxtrader.app.domain.usecase.signalintel.ConfirmedBarPolicy
import com.foxtrader.app.domain.usecase.signalintel.LitEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.strategies.StrategyPackageEngine
import com.foxtrader.app.domain.usecase.strategies.StrategyRuntimeSettingsRegistry
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min

/**
 * Multi-asset scanner backed by the same complete strategy packages used by the
 * live chart and backtests.
 *
 * Scanner ranking and executable trade signals intentionally remain different
 * concepts, but they no longer have different strategy implementations:
 * - [StrategyPackageEngine.Analysis.preferredDirection]/packageScore rank assets.
 * - [StrategyPackageEngine.Analysis.signal] marks a currently executable setup.
 *
 * Both are derived from one causal package containing technical state, confirmed
 * structure, full SMC detections, candlestick patterns, Wyckoff state, sessions
 * and strategy-specific execution logic. Direction/confidence controls from the
 * chart strategy gear are applied here as well; R:R controls apply only when an
 * executable entry actually exists.
 */
class ScannerUseCase @Inject constructor(
    private val smcDetector: SmcDetector,
    private val candlePatternDetector: CandlePatternDetector,
    private val ichimokuCloud: IchimokuCloud,
    // Retained for public constructor/Hilt compatibility. Bollinger analysis is
    // represented in canonical technical/package logic rather than a second
    // scanner-only strategy implementation.
    @Suppress("unused") private val bollingerBands: BollingerBands,
    private val wyckoffDetector: WyckoffDetector,
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val litXEngine: com.foxtrader.app.domain.usecase.litx.LitXEngine,
    private val litEngine: LitEngine = LitEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        displacementDetector = com.foxtrader.app.domain.usecase.litx.DisplacementDetector(),
        premiumDiscount = com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator(),
    ),
) {
    private var watchlist: MutableList<ScreenerSymbol> = DEFAULT_WATCHLIST.toMutableList()

    private val strategyPackages = StrategyPackageEngine(
        smcDetector = smcDetector,
        analyzeStructure = analyzeStructure,
        ichimokuCloud = ichimokuCloud,
        litXEngine = litXEngine,
        litEngine = litEngine,
        candlePatternDetector = candlePatternDetector,
        wyckoffDetector = wyckoffDetector,
    )

    /** Scan every enabled watchlist symbol using the selected canonical package. */
    operator fun invoke(
        dataMap: Map<String, List<Candle>>,
        strategy: StrategyType = StrategyType.CONFLUENCE,
        timeframe: Timeframe = Timeframe.H1,
    ): ScreenerOutput {
        val results = mutableListOf<ScreenerResult>()
        val validatedLitXSignals = mutableListOf<LitXSignal>()

        for (ws in watchlist) {
            if (!ws.enabled) continue
            val rawCandles = dataMap[ws.symbol] ?: continue
            val candles = ConfirmedBarPolicy.confirmedPrefix(rawCandles, timeframe, System.currentTimeMillis())
            if (candles.size < MIN_SCANNER_BARS) continue
            analyzeSymbol(ws, candles, strategy, timeframe)?.let { candidate ->
                results += candidate.result
                candidate.validatedLitXSignal?.let(validatedLitXSignals::add)
            }
        }

        results.sortByDescending { it.score }

        val buys = results.filter { it.direction == Direction.BULLISH }.sortedByDescending { it.score }
        val sells = results.filter { it.direction == Direction.BEARISH }.sortedByDescending { it.score }
        val swings = results.sortedByDescending { it.trendStrength }
        val scalps = results.sortedByDescending { it.volatility }
        val longterm = results.sortedByDescending { it.trendStrength * it.score }

        val categorized = results.map { it.copy(categories = mutableListOf()) }.toMutableList()
        fun tag(list: List<ScreenerResult>, category: WatchlistCategory) {
            val top = list.firstOrNull() ?: return
            val index = categorized.indexOfFirst { it.symbol == top.symbol }
            if (index >= 0) {
                categorized[index] = categorized[index].copy(
                    categories = categorized[index].categories + category,
                )
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
            validatedLitXSignals = validatedLitXSignals,
        )
    }

    private fun analyzeSymbol(
        ws: ScreenerSymbol,
        candles: List<Candle>,
        strategy: StrategyType,
        timeframe: Timeframe,
    ): AnalyzedCandidate? {
        val last = candles.lastIndex
        val price = candles[last].close
        if (!price.isFinite() || price <= 0.0) return null

        val packageAnalysis = strategyPackages.analyze(
            type = strategy,
            symbol = ws.symbol,
            timeframe = timeframe,
            candles = candles,
            index = last,
        )
        val runtimeSettings = StrategyRuntimeSettingsRegistry.get(strategy)

        // Executable setups obey every shared strategy control, including R:R
        // and target override. Ranking-only rows have no entry/stop yet, so only
        // direction and confidence gates are meaningful for those rows.
        val executableSignal = packageAnalysis.signal?.let { raw ->
            StrategyRuntimeSettingsRegistry.apply(runtimeSettings, raw)
        }
        val rankingDirection = packageAnalysis.preferredDirection?.takeIf { direction ->
            when (direction) {
                Direction.BULLISH -> runtimeSettings.allowBullish
                Direction.BEARISH -> runtimeSettings.allowBearish
            }
        }
        val direction = executableSignal?.direction ?: rankingDirection ?: return null
        val score = (executableSignal?.confidence ?: packageAnalysis.packageScore).coerceIn(0, 100)
        if (score < runtimeSettings.minimumConfidence) return null

        val start = (last - CHANGE_LOOKBACK_BARS).coerceAtLeast(0)
        val startPrice = candles[start].close
        val changePercent = if (startPrice.isFinite() && abs(startPrice) > MIN_DIVISOR_THRESHOLD) {
            ((price - startPrice) / startPrice) * 100.0
        } else {
            0.0
        }

        val technical = packageAnalysis.technical
        val trendStrength = min(100.0, technical.adx14.coerceAtLeast(0.0) * 2.0)
        val normalizedMacd = abs(technical.macdHistogram) / abs(price).coerceAtLeast(MIN_DIVISOR_THRESHOLD)
        val momentum = (50.0 + normalizedMacd * MOMENTUM_SCALE).coerceIn(0.0, 100.0)
        val volatility = ((technical.atr14 / abs(price).coerceAtLeast(MIN_DIVISOR_THRESHOLD)) * 100.0 * VOLATILITY_SCALE)
            .coerceIn(0.0, 100.0)

        val tags = buildList {
            add("PACKAGE")
            if (executableSignal != null) add("EXECUTABLE")
            packageAnalysis.confirmations
                .asSequence()
                .map { it.substringBefore(':') }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_PACKAGE_TAGS)
                .forEach(::add)
            if (packageAnalysis.conflicts.isNotEmpty()) add("CONFLICT_${packageAnalysis.conflicts.size}")
        }
        val riskLevel = riskLevelFor(
            score = score,
            trendStrength = trendStrength,
            volatility = volatility,
        )

        return AnalyzedCandidate(
            result = ScreenerResult(
                symbol = ws.symbol,
                assetClass = ws.assetClass,
                strategy = strategy,
                direction = direction,
                score = score,
                bias = if (direction == Direction.BULLISH) Bias.BULLISH else Bias.BEARISH,
                trendStrength = trendStrength,
                momentum = momentum,
                volatility = volatility,
                setupQuality = score.toDouble(),
                categories = emptyList(),
                tags = tags,
                lastPrice = price,
                changePercent = changePercent,
                riskLevel = riskLevel,
                rationale = buildRationale(
                    symbol = ws.symbol,
                    strategy = strategy,
                    direction = direction,
                    score = score,
                    trendStrength = trendStrength,
                    momentum = momentum,
                    volatility = volatility,
                    changePercent = changePercent,
                    tags = tags,
                    packageNarrative = packageAnalysis.narrative,
                    riskLevel = riskLevel,
                ),
            ),
            // A validated institutional event is persisted only when the shared
            // runtime controls still accept its executable setup.
            validatedLitXSignal = packageAnalysis.validatedLitXSignal.takeIf { executableSignal != null },
        )
    }

    private data class AnalyzedCandidate(
        val result: ScreenerResult,
        val validatedLitXSignal: LitXSignal? = null,
    )

    private fun riskLevelFor(
        score: Int,
        trendStrength: Double,
        volatility: Double,
    ): ScannerRiskLevel = when {
        score >= 70 && volatility < 55.0 && trendStrength >= 35.0 -> ScannerRiskLevel.LOW
        score < 45 || volatility >= 75.0 -> ScannerRiskLevel.HIGH
        else -> ScannerRiskLevel.MODERATE
    }

    private fun buildRationale(
        symbol: String,
        strategy: StrategyType,
        direction: Direction,
        score: Int,
        trendStrength: Double,
        momentum: Double,
        volatility: Double,
        changePercent: Double,
        tags: List<String>,
        packageNarrative: String,
        riskLevel: ScannerRiskLevel,
    ): String {
        val directionText = if (direction == Direction.BULLISH) "bullish" else "bearish"
        val trendText = when {
            trendStrength >= 70.0 -> "strong trend"
            trendStrength >= 40.0 -> "developing trend"
            else -> "weak trend"
        }
        val momentumText = when {
            momentum >= 70.0 -> "strong momentum"
            momentum >= 45.0 -> "balanced momentum"
            else -> "weak momentum"
        }
        val volatilityText = when {
            volatility >= 75.0 -> "high volatility"
            volatility >= 45.0 -> "normal volatility"
            else -> "controlled volatility"
        }
        val driverText = tags.take(5).joinToString(", ").ifBlank { "PACKAGE" }
        return "$symbol ${strategy.label}: $directionText package scan with $score/100 score, " +
            "$trendText, $momentumText, $volatilityText, ${"%+.2f".format(changePercent)}% change. " +
            "Drivers: $driverText. $packageNarrative. " +
            "Risk: ${riskLevel.name.lowercase().replaceFirstChar { it.uppercase() }}."
    }

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
        val index = watchlist.indexOfFirst { it.symbol == symbol }
        if (index >= 0) watchlist[index] = watchlist[index].copy(enabled = enabled)
    }

    fun getWatchlist(): List<ScreenerSymbol> = watchlist.toList()

    fun getByAssetClass(assetClass: AssetClass): List<ScreenerSymbol> =
        watchlist.filter { it.assetClass == assetClass }

    fun setWatchlist(symbols: List<ScreenerSymbol>) {
        watchlist = symbols.toMutableList()
    }

    companion object {
        private const val MIN_SCANNER_BARS = 50
        private const val CHANGE_LOOKBACK_BARS = 20
        private const val MIN_DIVISOR_THRESHOLD = 1e-9
        private const val MOMENTUM_SCALE = 10_000.0
        private const val VOLATILITY_SCALE = 50.0
        private const val MAX_PACKAGE_TAGS = 5

        val DEFAULT_WATCHLIST: List<ScreenerSymbol> = listOf(
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
            ScreenerSymbol("BTCUSDT", AssetClass.CRYPTO),
            ScreenerSymbol("ETHUSDT", AssetClass.CRYPTO),
            ScreenerSymbol("SOLUSDT", AssetClass.CRYPTO),
            ScreenerSymbol("BNBUSDT", AssetClass.CRYPTO),
            ScreenerSymbol("XRPUSDT", AssetClass.CRYPTO),
            ScreenerSymbol("AAPL", AssetClass.STOCKS),
            ScreenerSymbol("MSFT", AssetClass.STOCKS),
            ScreenerSymbol("NVDA", AssetClass.STOCKS),
            ScreenerSymbol("TSLA", AssetClass.STOCKS),
            ScreenerSymbol("AMZN", AssetClass.STOCKS),
            ScreenerSymbol("US30", AssetClass.INDICES),
            ScreenerSymbol("NAS100", AssetClass.INDICES),
            ScreenerSymbol("US500", AssetClass.INDICES),
            ScreenerSymbol("DE30", AssetClass.INDICES),
            ScreenerSymbol("XAUUSD", AssetClass.METALS),
            ScreenerSymbol("XAGUSD", AssetClass.METALS),
            ScreenerSymbol("WTIUSD", AssetClass.ENERGY),
            ScreenerSymbol("BRENTUSD", AssetClass.ENERGY),
            ScreenerSymbol("NATGAS", AssetClass.COMMODITIES),
            ScreenerSymbol("COPPER", AssetClass.COMMODITIES),
        )
    }
}
