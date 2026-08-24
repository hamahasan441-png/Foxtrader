package com.foxtrader.app.domain.model

/**
 * Market data providers the app can source candles / live ticks from.
 *
 * API credentials that must remain server-side are deliberately not collected
 * by the Android app. AllRatesToday is proxied through the FoxTrader backend so
 * its ART_API_KEY never ships in the APK.
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
        // The vendor refreshes interbank rates roughly every minute. FoxTrader
        // uses backend REST polling, not a vendor WebSocket.
        supportsLive = true,
        requiresApiKey = false,
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
