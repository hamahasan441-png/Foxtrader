package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.TickUpdate
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * WebSocket market data feed interface.
 *
 * Implementations connect to a specific broker/exchange (Binance, Bybit, etc.)
 * and emit real-time candle updates. The repository layer consumes these and
 * merges them into the offline-first data flow.
 *
 * Contract:
 * - [connectionState] is always observable (never null)
 * - [subscribe] starts receiving ticks; [unsubscribe] stops
 * - Auto-reconnect is handled internally by implementations
 * - Thread-safe: can be called from any coroutine dispatcher
 */
interface MarketWebSocket {

    /** Observable connection state (for UI indicators). */
    val connectionState: StateFlow<ConnectionState>

    /** Stream of real-time tick updates for all active subscriptions. */
    val ticks: Flow<TickUpdate>

    /** Subscribe to a symbol + timeframe pair. Connects if not already connected. */
    suspend fun subscribe(symbol: String, timeframe: Timeframe)

    /** Unsubscribe from a symbol + timeframe pair. Disconnects if no subscriptions remain. */
    suspend fun unsubscribe(symbol: String, timeframe: Timeframe)

    /** Unsubscribe from all and disconnect. */
    suspend fun disconnectAll()

    /** Whether this WebSocket is currently connected and receiving data. */
    val isConnected: Boolean get() = connectionState.value == ConnectionState.CONNECTED
}
