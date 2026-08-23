package com.foxtrader.app.domain.usecase.marketdata

import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Symbol-aware provider routing used by both historical and live market data.
 *
 * Provider identity is deliberately strict. The provider selected in Settings
 * is the provider used for history and (when that same provider supports it)
 * live updates. FoxTrader never silently continues one venue's history with a
 * different venue's ticks.
 */
@Singleton
class MarketProviderRouter @Inject constructor(
    private val appPreferences: AppPreferences,
) {

    fun canonicalSymbol(symbol: String): String = MarketSymbolClassifier.canonicalSymbol(symbol)

    fun classify(symbol: String): MarketAssetClass = MarketSymbolClassifier.classify(symbol)

    /**
     * True when [provider] is a legitimate venue for [symbol]'s asset class.
     *
     * This is intentionally public so Markets/Scanner and symbol pickers can
     * filter their rows before trying a network request. It prevents a global
     * mixed-asset watchlist from asking Binance for EURUSD or Dukascopy for a
     * USDT spot pair and then presenting the resulting synthetic fallback as if
     * it were a provider problem.
     */
    fun supportsSymbol(provider: DataProvider, symbol: String): Boolean {
        if (symbol.isBlank() || !provider.implemented) return false
        return providerSupportsAsset(provider, classify(symbol))
    }

    /**
     * Safe starter symbols for each implemented provider.
     *
     * These are not claimed to be an exhaustive instrument directory. They are
     * provider-native examples used to recover from an incompatible symbol when
     * a user switches venues, and to seed provider-aware UI surfaces. Broker
     */
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
        DataProvider.OANDA,
        DataProvider.ALPACA,
        DataProvider.INTERACTIVE_BROKERS -> emptyList()
    }

    fun defaultSymbolFor(provider: DataProvider): String? = defaultSymbolsFor(provider).firstOrNull()

    /**
     * Resolve the historical provider selected by the user.
     *
     * Provider identity is strict: FoxTrader must never fetch history from one
     * venue while the UI badge names another. Unsupported symbols/credentials
     * are surfaced by that provider's adapter instead of silently falling back
     * to a different exchange/broker.
     */
    fun historicalProviderFor(
        symbol: String,
        preferred: DataProvider = appPreferences.dataProvider.value,
    ): DataProvider {
        require(symbol.isNotBlank()) { "Market symbol is required" }
        return preferred
    }

    /**
     * Resolve live transport for the explicitly selected provider only.
     *
     * A provider without a native live path returns null. This prevents a
     * historical OKX/KuCoin/REST series, for example, from being continued by
     * Binance ticks and then analysed as if it were one venue.
     */
    fun liveProviderFor(
        symbol: String,
        preferred: DataProvider = appPreferences.dataProvider.value,
    ): DataProvider? {
        if (symbol.isBlank() || preferred == DataProvider.SAMPLE) return null
        if (!preferred.implemented || !preferred.supportsLive || !credentialsReady(preferred)) return null
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
        // Deriv has provider-native symbols (synthetic indices such as R_100,
        // forex aliases, etc.) that do not fit the generic classifier. When the
        // user explicitly chooses Deriv, let the Deriv API validate the symbol.
        DataProvider.DERIV -> true
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
}
