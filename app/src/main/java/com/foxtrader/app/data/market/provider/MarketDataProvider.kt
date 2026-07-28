package com.foxtrader.app.data.market.provider

import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import kotlinx.coroutines.flow.Flow

/**
 * The single abstraction every market-data source implements.
 *
 * Business logic (the candle engine, the cache, the UI) depends only on this
 * interface, never on a concrete feed. Swapping Binance for Dukascopy for a
 * future provider is then a binding change, not a logic change — the mission's
 * "switch providers without changing business logic" requirement.
 *
 * Two responsibilities, matching the two ways the engine obtains data:
 *  - [connectTicks] — the live path: a hot [Flow] of ticks that the candle
 *    engine turns into bars across all timeframes.
 *  - [fetchCandles] — the recovery path: historical bars used to backfill cache
 *    gaps and to seed a chart before the live feed catches up.
 *
 * [capabilities] lets the engine adapt (e.g. a REST-only source has no live
 * ticks; a crypto source may not serve monthly bars) without `is`-checks on the
 * concrete type.
 */
interface MarketDataProvider {

    /** Which [DataProvider] this implementation backs. */
    val provider: DataProvider

    /** What this source can and cannot do. */
    val capabilities: ProviderCapability

    /**
     * A hot stream of live ticks for [symbol]. The flow is cold-started per
     * collector and should reconnect internally or terminate so the engine's
     * reconnect/failover logic can take over.
     */
    fun connectTicks(symbol: String): Flow<Tick>

    /**
     * Historical candles for [symbol]/[timeframe] with bucket-open timestamps in
     * `[from, to)`. Used for gap-filling and initial seeding. Returns bars
     * ordered oldest→newest; an empty list means "no data in range".
     */
    suspend fun fetchCandles(
        symbol: String,
        timeframe: MarketTimeframe,
        from: Long,
        to: Long,
    ): List<Candle>

    /** Releases any underlying connection. Idempotent. */
    fun disconnect()
}

/**
 * Declares what a provider supports, so the engine can branch on capability
 * rather than concrete type.
 *
 * @param supportsLiveTicks         true if [MarketDataProvider.connectTicks] yields data.
 * @param supportsHistoricalCandles true if [MarketDataProvider.fetchCandles] is implemented.
 * @param supportedTimeframes       the timeframes this source can serve.
 */
data class ProviderCapability(
    val supportsLiveTicks: Boolean,
    val supportsHistoricalCandles: Boolean,
    val supportedTimeframes: Set<MarketTimeframe>,
) {
    fun supports(timeframe: MarketTimeframe): Boolean = timeframe in supportedTimeframes
}
