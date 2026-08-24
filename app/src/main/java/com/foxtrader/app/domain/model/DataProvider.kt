package com.foxtrader.app.domain.model

/**
 * Market data providers the app can source candles / live ticks from.
 *
 * Provider API credentials are entered in Settings only when required and are
 * persisted by AppPreferences in encrypted storage. They must never be
 * hardcoded into BuildConfig, source files, logs, or URLs.
 */
enum class DataProvider(
    val displayName: String,
    val supportsLive: Boolean,
    val requiresApiKey: Boolean,
    val apiKeyLabel: String? = null,
    val implemented: Boolean = false,
) {
    SAMPLE("Sample Data", supportsLive = false, requiresApiKey = false, implemented = true),
    BINANCE("Binance", supportsLive = true, requiresApiKey = false, implemented = true),
    BYBIT("Bybit", supportsLive = true, requiresApiKey = false, implemented = true),
    OKX("OKX", supportsLive = false, requiresApiKey = false, implemented = true),
    KUCOIN("KuCoin", supportsLive = false, requiresApiKey = false, implemented = true),
    DUKASCOPY("Dukascopy", supportsLive = true, requiresApiKey = false, implemented = true),
    DERIV("Deriv", supportsLive = true, requiresApiKey = false, implemented = true),
    ALPHA_VANTAGE(
        "Alpha Vantage",
        supportsLive = false,
        requiresApiKey = true,
        apiKeyLabel = "Alpha Vantage API Key",
        implemented = true,
    ),
    POLYGON(
        "Polygon.io",
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "Polygon.io API Key",
        implemented = true,
    ),
    OANDA(
        "OANDA",
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "OANDA API Token",
    ),
    ALPACA(
        "Alpaca",
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "Alpaca API Key",
    ),
    TWELVE_DATA(
        "Twelve Data",
        supportsLive = false,
        requiresApiKey = true,
        apiKeyLabel = "Twelve Data API Key",
        implemented = true,
    ),
    ALL_RATES_TODAY(
        "AllRatesToday",
        // The vendor exposes authenticated REST live/time-series data. FoxTrader
        // stores the user-entered key encrypted and forwards it to the backend
        // proxy over HTTPS; the backend then uses it as the vendor Bearer token.
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "AllRatesToday API Key",
        implemented = true,
    ),
    INTERACTIVE_BROKERS(
        "Interactive Brokers",
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "Interactive Brokers Gateway Key",
    ),
    ;

    companion object {
        fun implemented(): List<DataProvider> = entries.filter { it.implemented }
    }
}
