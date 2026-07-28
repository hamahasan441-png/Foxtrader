package com.foxtrader.app.data.market.connection

/**
 * Ordered failover across a list of endpoints (or providers).
 *
 * When the current endpoint keeps failing, [advance] moves to the next one. Once
 * a connection succeeds the caller [reset]s back to the preferred endpoint. This
 * keeps a single business-logic path working across, e.g., a primary and a
 * backup WebSocket URL, or Binance → Bybit provider failover, without the
 * consuming code knowing which endpoint is live.
 *
 * Generic over the endpoint type so it can route URLs, provider enums, or full
 * transport configs.
 */
class FailoverRouter<T>(endpoints: List<T>) {

    private val items: List<T> = endpoints.toList()

    init {
        require(items.isNotEmpty()) { "FailoverRouter needs at least one endpoint" }
    }

    private var index = 0

    /** The currently selected endpoint. */
    val current: T get() = items[index]

    /** Zero-based position in the endpoint list. */
    val currentIndex: Int get() = index

    /** True when the router is on the last endpoint (no further failover). */
    val isExhausted: Boolean get() = index == items.size - 1

    val size: Int get() = items.size

    /**
     * Moves to the next endpoint. Returns `true` if it advanced, `false` if
     * already on the last endpoint (caller should surface a terminal failure or
     * [reset] and retry).
     */
    fun advance(): Boolean {
        if (isExhausted) return false
        index++
        return true
    }

    /** Returns to the preferred (first) endpoint, e.g. after a success. */
    fun reset() {
        index = 0
    }
}
