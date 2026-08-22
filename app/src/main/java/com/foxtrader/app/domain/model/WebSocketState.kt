package com.foxtrader.app.domain.model

/**
 * WebSocket connection lifecycle states.
 * Observed by the UI to show connection indicators.
 *
 * [AUTH_FAILED] indicates the remote endpoint rejected our credentials
 * (HTTP 401/403 during the upgrade or an explicit auth failure). It is a
 * terminal state: the caller must obtain fresh credentials before reconnecting.
 *
 * [STALE] indicates the stream stopped delivering heartbeats within the stale
 * timeout and is being torn down so a fresh connection can be established.
 *
 * [FATAL] is a terminal state reached after exhausting the reconnect budget or
 * encountering an unrecoverable protocol error. A manual reconnect is required.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
    AUTH_FAILED,
    STALE,
    FATAL,
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
    /** Filled by ProviderMarketWebSocket at the routing boundary. */
    val provider: DataProvider? = null,
)
