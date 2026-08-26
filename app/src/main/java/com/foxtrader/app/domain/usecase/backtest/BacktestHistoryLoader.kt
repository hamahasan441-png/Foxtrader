package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.SourcedCandles
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import javax.inject.Inject

/**
 * Pages provider history backwards until a requested start date is covered.
 *
 * The Room cache only ever holds the most recent window of bars, so testing a
 * named period ("March 2024", "all of 2023") requires walking the provider's
 * `loadOlderCandles` pages and merging them in memory. Nothing here is
 * persisted: the caller gets one contiguous, de-duplicated, ascending series
 * and the cache is left as the live chart expects to find it.
 *
 * The walk stops on the first of: the target being reached, the provider
 * running out of history, or the bar-count safety cap. [Result.reachedTarget]
 * reports which, so a caller can tell the user its range was truncated instead
 * of quietly measuring a shorter period than was asked for.
 */
class BacktestHistoryLoader @Inject constructor(
    private val repository: MarketRepository,
) {

    data class Result(
        val candles: List<Candle>,
        val source: CandleSource,
        /** True when history reaches at or before the requested start. */
        val reachedTarget: Boolean,
        /** True when the provider returned no further history. */
        val providerExhausted: Boolean,
        /** True when the bar-count safety cap stopped the walk. */
        val hitBarCap: Boolean,
    )

    /**
     * Load history back to [targetStartTimestamp].
     *
     * @param seed the newest page, already fetched by the caller (so refresh
     *   policy and provenance checks stay in one place).
     * @param maxBars hard ceiling on the merged series, bounding both the
     *   number of network pages and the peak heap this holds.
     */
    suspend fun loadBackTo(
        symbol: String,
        timeframe: Timeframe,
        seed: SourcedCandles,
        targetStartTimestamp: Long,
        maxBars: Int = MAX_BARS,
    ): Result {
        val ordered = seed.candles.sortedBy { it.timestamp }
        if (ordered.isEmpty()) {
            return Result(emptyList(), seed.source, reachedTarget = false, providerExhausted = true, hitBarCap = false)
        }

        // ArrayDeque so each older page is prepended in O(page) rather than
        // shifting a growing ArrayList on every iteration.
        val merged = ArrayDeque(ordered)
        val seen = HashSet<Long>(ordered.size * 2).apply { ordered.forEach { add(it.timestamp) } }
        var source = seed.source
        var reachedTarget = merged.first().timestamp <= targetStartTimestamp
        var providerExhausted = false
        var hitBarCap = false

        while (!reachedTarget) {
            if (merged.size >= maxBars) {
                hitBarCap = true
                break
            }
            val oldest = merged.first().timestamp
            val pageLimit = minOf(PAGE_SIZE, (maxBars - merged.size).coerceAtLeast(1))
            // A failed page is not a failed backtest: report what was actually
            // covered and let the caller decide whether the truncated window is
            // still worth measuring.
            val page = repository.loadOlderCandles(
                symbol = symbol,
                timeframe = timeframe,
                beforeTimestamp = oldest,
                limit = pageLimit,
            ).getOrNull()

            // Synthetic history would silently turn a "3 year backtest" into a
            // random-walk simulation. Stop rather than mix it in; the caller
            // enforces the provenance policy on what it already has.
            if (page == null || page.source == CandleSource.SYNTHETIC) {
                providerExhausted = true
                break
            }

            val fresh = page.candles
                .asSequence()
                .filter { it.timestamp < oldest }
                .filter { seen.add(it.timestamp) }
                .sortedBy { it.timestamp }
                .toList()

            if (fresh.isEmpty()) {
                providerExhausted = true
                break
            }

            for (index in fresh.indices.reversed()) merged.addFirst(fresh[index])
            source = CandleSource.worstOf(listOf(source, page.source))
            reachedTarget = merged.first().timestamp <= targetStartTimestamp
        }

        return Result(
            candles = merged.toList(),
            source = source,
            reachedTarget = reachedTarget,
            providerExhausted = providerExhausted,
            hitBarCap = hitBarCap,
        )
    }

    companion object {
        private const val PAGE_SIZE = 500

        /** Ceiling on the merged in-memory series. Public so the UI can name it. */
        const val MAX_BARS = 20_000
    }
}
