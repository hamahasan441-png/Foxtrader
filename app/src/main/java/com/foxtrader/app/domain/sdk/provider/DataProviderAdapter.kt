package com.foxtrader.app.domain.sdk.provider

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe

/**
 * SDK interface for market data providers.
 *
 * Implementations fetch historical candles (and optionally stream live ticks)
 * from a specific source (Binance, Dukascopy, Polygon, etc.).
 *
 * CONTRACT:
 * - [fetchHistory] returns candles ascending by timestamp.
 * - Must handle errors gracefully (return empty list, never crash).
 * - Thread-safe, suspend for network calls.
 */
interface DataProviderAdapter {
    val id: String
    val displayName: String
    val supportsLive: Boolean
    val supportedTimeframes: List<Timeframe>
    val supportedSymbols: List<String>  // empty = all

    /** Fetch historical candles. */
    suspend fun fetchHistory(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 500,
        startTime: Long? = null,
        endTime: Long? = null,
    ): List<Candle>

    /** Whether this provider can serve the given symbol. */
    fun supports(symbol: String): Boolean =
        supportedSymbols.isEmpty() || supportedSymbols.any { symbol.contains(it, ignoreCase = true) }
}

/**
 * Registry for data providers. The repository layer queries this to route
 * symbol requests to the appropriate provider.
 */
class DataProviderRegistry {
    private val providers = LinkedHashMap<String, DataProviderAdapter>()

    fun register(provider: DataProviderAdapter) { providers[provider.id] = provider }
    fun getAll(): List<DataProviderAdapter> = providers.values.toList()
    fun get(id: String): DataProviderAdapter? = providers[id]

    /** Find the best provider for a symbol (first that supports it). */
    fun findFor(symbol: String): DataProviderAdapter? =
        providers.values.firstOrNull { it.supports(symbol) }
}
