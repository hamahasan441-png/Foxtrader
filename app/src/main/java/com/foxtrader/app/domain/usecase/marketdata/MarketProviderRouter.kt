package com.foxtrader.app.domain.usecase.marketdata

import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/** Strict symbol-aware provider routing for historical and live market data. */
@Singleton
class MarketProviderRouter @Inject constructor(
    private val appPreferences: AppPreferences,
) {

    fun canonicalSymbol(symbol: String): String = MarketSymbolClassifier.canonicalSymbol(symbol)

    fun classify(symbol: String): MarketAssetClass = MarketSymbolClassifier.classify(symbol)

    fun supportsSymbol(provider: DataProvider, symbol: String): Boolean {
        if (symbol.isBlank() || !provider.implemented) return false
        if (provider == DataProvider.ALL_RATES_TODAY) return normalizeIsoPair(symbol) != null
        return providerSupportsAsset(provider, classify(symbol))
    }

    /** Starter symbols only; provider-native discovery supplies the full directory. */
    fun defaultSymbolsFor(provider: DataProvider): List<String> = when (provider) {
        DataProvider.SAMPLE -> listOf("EURUSD", "BTCUSDT", "AAPL")
        DataProvider.BINANCE,
        DataProvider.BYBIT,
        DataProvider.OKX,
        DataProvider.KUCOIN -> listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT")
        DataProvider.DUKASCOPY -> listOf(
            "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "XAUUSD", "XAGUSD", "US30", "NAS100",
        )
        DataProvider.DERIV -> listOf(
            "R_100", "R_75", "R_50", "R_25", "R_10", "frxEURUSD", "frxGBPUSD", "frxUSDJPY",
        )
        DataProvider.ALPHA_VANTAGE -> listOf("EURUSD", "AAPL", "MSFT", "NVDA")
        DataProvider.POLYGON -> listOf("AAPL", "MSFT", "NVDA", "TSLA")
        DataProvider.TWELVE_DATA -> listOf("EURUSD", "AAPL", "MSFT", "BTCUSD")
        DataProvider.ALL_RATES_TODAY -> listOf("EURUSD", "GBPUSD", "USDJPY", "EURGBP", "AUDUSD")
        DataProvider.OANDA,
        DataProvider.ALPACA,
        DataProvider.INTERACTIVE_BROKERS -> emptyList()
    }

    fun defaultSymbolFor(provider: DataProvider): String? = defaultSymbolsFor(provider).firstOrNull()

    fun historicalProviderFor(
        symbol: String,
        preferred: DataProvider = appPreferences.dataProvider.value,
    ): DataProvider {
        require(symbol.isNotBlank()) { "Market symbol is required" }
        return preferred
    }

    fun liveProviderFor(
        symbol: String,
        preferred: DataProvider = appPreferences.dataProvider.value,
    ): DataProvider? {
        if (symbol.isBlank() || preferred == DataProvider.SAMPLE) return null
        if (!preferred.implemented || !preferred.supportsLive || !credentialsReady(preferred)) return null
        if (preferred == DataProvider.ALL_RATES_TODAY) {
            return preferred.takeIf { normalizeIsoPair(symbol) != null }
        }
        val asset = classify(symbol)
        return if (providerSupportsAsset(preferred, asset)) preferred else null
    }

    fun canGoLive(
        symbol: String,
        preferred: DataProvider = appPreferences.dataProvider.value,
    ): Boolean = liveProviderFor(symbol, preferred) != null

    private fun providerSupportsAsset(provider: DataProvider, asset: MarketAssetClass): Boolean = when (provider) {
        DataProvider.SAMPLE -> true
        DataProvider.BINANCE,
        DataProvider.BYBIT,
        DataProvider.OKX,
        DataProvider.KUCOIN -> asset == MarketAssetClass.CRYPTO
        DataProvider.DUKASCOPY -> asset == MarketAssetClass.FOREX ||
            asset == MarketAssetClass.METAL || asset == MarketAssetClass.INDEX
        DataProvider.DERIV,
        DataProvider.ALL_RATES_TODAY -> true
        DataProvider.ALPHA_VANTAGE,
        DataProvider.POLYGON,
        DataProvider.TWELVE_DATA -> asset != MarketAssetClass.UNKNOWN
        DataProvider.OANDA -> asset == MarketAssetClass.FOREX ||
            asset == MarketAssetClass.METAL || asset == MarketAssetClass.INDEX
        DataProvider.ALPACA -> asset == MarketAssetClass.STOCK
        DataProvider.INTERACTIVE_BROKERS -> asset != MarketAssetClass.UNKNOWN
    }

    private fun credentialsReady(provider: DataProvider): Boolean =
        !provider.requiresApiKey || !appPreferences.getApiKey(provider).isNullOrBlank()

    private fun normalizeIsoPair(symbol: String): String? {
        val pair = symbol.trim().uppercase()
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")
        return pair.takeIf { it.length == 6 && it.all(Char::isLetter) && it.take(3) != it.drop(3) }
    }
}
