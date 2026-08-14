package com.foxtrader.app.domain.model

/**
 * Market data providers the app can source candles / live ticks from.
 *
 * SAMPLE       — built-in offline sample data (no network)
 * BINANCE      — Binance spot WebSocket + REST (crypto)
 * BYBIT        — Bybit spot REST + WebSocket (crypto)
 * DUKASCOPY    — forex / CFD tick history
 * ALPHA_VANTAGE — stocks / forex REST
 * POLYGON      — stocks / forex / crypto
 * OANDA        — forex / CFDs
 * ALPACA       — US stocks
 * TWELVE_DATA  — multi-asset REST
 * INTERACTIVE_BROKERS — multi-asset via gateway
 *
 * [implemented] marks whether the provider actually has a working fetch path.
 * Several entries below are declared for the roadmap but have no data-source
 * implementation; Settings must not accept an API key for those, and the
 * repository must fail loudly rather than silently falling back to synthetic
 * data while the user believes they are seeing their broker's prices.
 */
enum class DataProvider(
    val displayName: String,
    val supportsLive: Boolean,
    val requiresApiKey: Boolean,
    val apiKeyLabel: String? = null,
    /** True only when a real candle-fetch path exists for this provider. */
    val implemented: Boolean = false,
) {
    SAMPLE("Sample Data", supportsLive = false, requiresApiKey = false, implemented = true),
    BINANCE("Binance", supportsLive = true, requiresApiKey = false, implemented = true),
    BYBIT("Bybit", supportsLive = true, requiresApiKey = false, implemented = true),
    OKX("OKX", supportsLive = false, requiresApiKey = false, implemented = true),
    KUCOIN("KuCoin", supportsLive = false, requiresApiKey = false, implemented = true),
    // Dukascopy forex/CFD tick history pipeline.
    DUKASCOPY("Dukascopy", supportsLive = false, requiresApiKey = false, implemented = true),
    ALPHA_VANTAGE(
        "Alpha Vantage",
        supportsLive = false,
        requiresApiKey = true,
        apiKeyLabel = "Alpha Vantage API Key",
        implemented = true,
    ),
    POLYGON(
        "Polygon.io",
        // REST aggregates and authenticated minute-aggregate WebSocket are wired.
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
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "Twelve Data API Key",
        implemented = true,
    ),
    INTERACTIVE_BROKERS(
        "Interactive Brokers",
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "Interactive Brokers Gateway Key",
    ),
    MT4(
        "MT4 (MetaApi)",
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "MetaApi Token",
        implemented = true,
    ),
    ;

    companion object {
        /** Providers a user can actually select and get real data from. */
        fun implemented(): List<DataProvider> = entries.filter { it.implemented }
    }
}
