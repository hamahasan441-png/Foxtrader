package com.foxtrader.app.domain.model

/**
 * WebSocket connection lifecycle states.
 * Observed by the UI to show connection indicators.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

/**
 * A real-time tick update from the market data feed.
 * Represents either a forming candle update or a confirmed bar close.
 */
data class TickUpdate(
    val symbol: String,
    val timeframe: Timeframe,
    val candle: Candle,
    val isBarClose: Boolean,  // true = bar is confirmed/closed, false = still forming
    val timestamp: Long = System.currentTimeMillis(),
)
