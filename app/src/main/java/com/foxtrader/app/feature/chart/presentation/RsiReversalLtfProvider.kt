package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.rsireversal.RsiReversalConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies entry-timeframe candles for the RSI Orderflow Reversal engine.
 *
 * The chart is a single-timeframe pipeline, but §16–§18 confirmation happens
 * one timeframe below the context chart, so this is the one place a second
 * series is fetched. Three properties matter:
 *
 * - **Bounded.** One cached series per symbol/timeframe, refreshed no more
 *   often than [REFRESH_INTERVAL_MILLIS]. Analysis runs on every processed bar
 *   and on live ticks; without this the study would issue a repository fetch
 *   per tick.
 * - **Provenance-gated.** FoxTrader seeds synthetic bars when a provider is
 *   unreachable. A synthetic series is discarded rather than returned, so an
 *   entry can never be confirmed against generated prices — the study simply
 *   reports no signal, which is the honest answer.
 * - **Non-blocking on failure.** A failed fetch yields no data and therefore no
 *   signal, never a stale-but-plausible confirmation.
 */
@Singleton
class RsiReversalLtfProvider @Inject constructor(
    private val repository: MarketRepository,
) {

    private data class Key(val symbol: String, val timeframe: Timeframe)

    private data class Entry(val candles: List<Candle>, val fetchedAt: Long)

    private val cache = HashMap<Key, Entry>()
    private val lock = Any()

    /**
     * Entry-timeframe candles for [symbol], or an empty list when none can be
     * trusted. [now] is injected so tests do not depend on the wall clock.
     */
    suspend fun candlesFor(
        symbol: String,
        contextTimeframe: Timeframe,
        config: RsiReversalConfig,
        now: Long = System.currentTimeMillis(),
    ): Pair<Timeframe, List<Candle>>? {
        val entryTimeframe = config.entryTimeframe(contextTimeframe) ?: return null
        val key = Key(symbol, entryTimeframe)

        synchronized(lock) {
            cache[key]?.let { cached ->
                if (now - cached.fetchedAt < REFRESH_INTERVAL_MILLIS) {
                    return entryTimeframe to cached.candles
                }
            }
        }

        val sourced = runCatching { repository.getSourcedCandles(symbol, entryTimeframe) }.getOrNull()
        val candles = when {
            sourced == null -> emptyList()
            sourced.isSynthetic -> emptyList()
            else -> sourced.candles
        }

        synchronized(lock) {
            // A failed or synthetic fetch is cached as empty too, so a broken
            // provider costs one request per interval rather than one per tick.
            cache[key] = Entry(candles, now)
            if (cache.size > MAX_CACHED_SERIES) {
                cache.entries
                    .sortedBy { it.value.fetchedAt }
                    .take(cache.size - MAX_CACHED_SERIES)
                    .forEach { cache.remove(it.key) }
            }
        }
        return entryTimeframe to candles
    }

    /** Drop cached series, e.g. when the data provider or account changes. */
    fun clear() {
        synchronized(lock) { cache.clear() }
    }

    private companion object {
        const val REFRESH_INTERVAL_MILLIS = 60_000L
        const val MAX_CACHED_SERIES = 8
    }
}
