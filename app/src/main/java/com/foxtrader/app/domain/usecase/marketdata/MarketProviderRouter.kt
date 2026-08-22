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
        DataProvider.DERIV,
        DataProvider.MT4 -> true
        DataProvider.ALPHA_VANTAGE,
        DataProvider.POLYGON,
        DataProvider.TWELVE_DATA -> asset != MarketAssetClass.UNKNOWN
        DataProvider.OANDA -> asset == MarketAssetClass.FOREX ||
            asset == MarketAssetClass.METAL || asset == MarketAssetClass.INDEX
        DataProvider.ALPACA -> asset == MarketAssetClass.STOCK
        DataProvider.INTERACTIVE_BROKERS -> asset != MarketAssetClass.UNKNOWN
    }

    private fun credentialsReady(provider: DataProvider): Boolean = when (provider) {
        DataProvider.MT4 -> !(appPreferences.getMetaApiToken()
            ?: appPreferences.getApiKey(DataProvider.MT4)).isNullOrBlank() &&
            !appPreferences.getMetaApiAccountId().isNullOrBlank()
        else -> !provider.requiresApiKey || !appPreferences.getApiKey(provider).isNullOrBlank()
    }

}
