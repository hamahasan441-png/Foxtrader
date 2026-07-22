package com.foxtrader.app.domain.usecase.scanner

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ScreenerOutput
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.ScreenerSymbol
import com.foxtrader.app.domain.model.WatchlistCategory
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import kotlin.math.abs
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
class ScannerUseCase @Inject constructor() {

    private var watchlist: MutableList<ScreenerSymbol> = DEFAULT_WATCHLIST.toMutableList()

    /**
     * Scan all watchlist symbols.
     * @param dataMap symbol → candle data (at least 50 bars each)
     */
    operator fun invoke(dataMap: Map<String, List<Candle>>): ScreenerOutput {
        val results = mutableListOf<ScreenerResult>()

        for (ws in watchlist) {
            if (!ws.enabled) continue
            val candles = dataMap[ws.symbol] ?: continue
            if (candles.size < 50) continue
            results += analyzeSymbol(ws, candles)
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

    private fun analyzeSymbol(ws: ScreenerSymbol, candles: List<Candle>): ScreenerResult {
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

        val bullEMA = ema20[last] > ema50[last]
        val diUp = adxResult.plusDI[last] > adxResult.minusDI[last]
        val direction: Direction = if ((bullEMA && diUp) || changePercent > 0.5) Direction.BULLISH else Direction.BEARISH
        val bias: Bias = if (direction == Direction.BULLISH) Bias.BULLISH else Bias.BEARISH

        val trendStrength = min(100.0, adxResult.adx[last] * 2.0)
        val momentum = min(100.0, 50.0 + abs(mom[last]) * 5.0)
        val volatility = min(100.0, (atr[last] / price) * 100.0 * 50.0)
        val rsiScore = abs(rsi[last] - 50.0)
        val setupQuality = min(100.0, trendStrength * 0.4 + momentum * 0.3 + rsiScore * 0.3)
        val score = min(100, (setupQuality * 0.6 + trendStrength * 0.3 + if (bullEMA == diUp) 10.0 else 0.0).roundToInt())

        val tags = mutableListOf<String>()
        if (adxResult.adx[last] > 25) tags += "TRENDING"
        if (rsi[last] > 70) tags += "OVERBOUGHT"
        if (rsi[last] < 30) tags += "OVERSOLD"
        if (volatility > 60) tags += "HIGH_VOL"
        if (abs(changePercent) > 2) tags += "MOVER"

        return ScreenerResult(
            symbol = ws.symbol,
            assetClass = ws.assetClass,
            direction = direction,
            score = score,
            bias = bias,
            trendStrength = trendStrength,
            momentum = momentum,
            volatility = volatility,
            setupQuality = setupQuality,
            categories = emptyList(),
            tags = tags,
            lastPrice = price,
            changePercent = changePercent,
        )
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
