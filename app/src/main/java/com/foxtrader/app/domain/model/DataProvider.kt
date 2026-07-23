package com.foxtrader.app.domain.model

/**
 * Market data providers the app can source candles / live ticks from.
 *
 * SAMPLE       — built-in offline sample data (no network)
 * BINANCE      — Binance spot WebSocket + REST (crypto)
 * BYBIT        — Bybit (crypto derivatives)
 * DUKASCOPY    — forex / CFD tick history
 * ALPHA_VANTAGE — stocks / forex REST
 * POLYGON      — stocks / forex / crypto
 * OANDA        — forex / CFDs
 * ALPACA       — US stocks
 * TWELVE_DATA  — multi-asset REST
 * INTERACTIVE_BROKERS — multi-asset via gateway
 */
enum class DataProvider(
    val displayName: String,
    val supportsLive: Boolean,
    val requiresApiKey: Boolean,
    val apiKeyLabel: String? = null,
) {
    SAMPLE("Sample Data", supportsLive = false, requiresApiKey = false),
    BINANCE("Binance", supportsLive = true, requiresApiKey = false),
    BYBIT("Bybit", supportsLive = true, requiresApiKey = false),
    DUKASCOPY("Dukascopy", supportsLive = false, requiresApiKey = false),
    ALPHA_VANTAGE(
        "Alpha Vantage",
        supportsLive = false,
        requiresApiKey = true,
        apiKeyLabel = "Alpha Vantage API Key",
    ),
    POLYGON(
        "Polygon.io",
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "Polygon.io API Key",
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
    ),
    INTERACTIVE_BROKERS(
        "Interactive Brokers",
        supportsLive = true,
        requiresApiKey = true,
        apiKeyLabel = "Interactive Brokers Gateway Key",
    ),
}
