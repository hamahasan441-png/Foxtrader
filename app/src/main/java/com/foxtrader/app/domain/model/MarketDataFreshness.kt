package com.foxtrader.app.domain.model

/** User-facing freshness classification independent of candle provenance. */
enum class MarketDataFreshness {
    LIVE,
    DELAYED,
    CACHED,
    SIMULATED,
}

/** Pure resolver so freshness logic is deterministic and unit-testable. */
object MarketDataFreshnessResolver {
    fun resolve(
        source: CandleSource,
        connectionState: ConnectionState,
        timeframe: Timeframe,
        latestBarTimestamp: Long?,
        nowMillis: Long = System.currentTimeMillis(),
    ): MarketDataFreshness {
        if (source == CandleSource.SYNTHETIC) return MarketDataFreshness.SIMULATED
        val timestamp = latestBarTimestamp ?: return MarketDataFreshness.CACHED
        val age = (nowMillis - timestamp).coerceAtLeast(0L)
        val period = timeframe.minutes.toLong().coerceAtLeast(1L) * 60_000L
        val liveWindow = (period + 120_000L).coerceAtLeast(180_000L)
        val delayedWindow = (period * 3L).coerceAtLeast(15L * 60_000L)

        if (connectionState == ConnectionState.CONNECTED && age <= liveWindow) {
            return MarketDataFreshness.LIVE
        }
        if (age <= delayedWindow) return MarketDataFreshness.DELAYED
        return MarketDataFreshness.CACHED
    }
}
