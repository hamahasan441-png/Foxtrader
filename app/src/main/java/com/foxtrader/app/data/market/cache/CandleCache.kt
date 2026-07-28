package com.foxtrader.app.data.market.cache

import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.domain.model.Candle
import java.util.TreeMap

/**
 * An in-memory, versioned candle store for one (symbol-less) timeframe series —
 * the offline-first working set the chart and engines read from.
 *
 * It exists to make the offline guarantees concrete and testable:
 *  - **Local cache.** Bars are keyed by bucket-open time in a [TreeMap], so
 *    reads are always sorted oldest→newest and range queries are cheap.
 *  - **Gap filling / missing-candle recovery.** [missingBucketStarts] walks the
 *    contiguous bucket sequence between the first and last stored bar and reports
 *    exactly which buckets are absent, so the sync layer knows what to fetch.
 *  - **Versioning.** [version] advances on every structural change, giving
 *    observers a cheap "did the series change?" signal without diffing bars.
 *  - **No repaint.** Re-storing an *identical* bar is a no-op ([InsertResult.UNCHANGED],
 *    no version bump); only a genuinely different bar revises history, and that
 *    is counted separately in [revisions] so it is never silent.
 *
 * Timestamps are normalised to their bucket-open on insert, so a bar built from a
 * mid-bucket tick compares equal to one built from the bucket boundary.
 *
 * Not thread-safe; the sync layer serialises writes on a single dispatcher.
 */
class CandleCache(val timeframe: MarketTimeframe) {

    private val bars = TreeMap<Long, Candle>()

    /** Monotonic; advances on every insert or revision. */
    var version: Long = 0L
        private set

    /** How many times an existing bar was replaced by different data. */
    var revisions: Long = 0L
        private set

    val size: Int get() = bars.size
    val isEmpty: Boolean get() = bars.isEmpty()

    /**
     * Stores [candle] under its bucket-open time and reports what happened.
     * Idempotent for identical data; tracks genuine revisions separately.
     */
    fun upsert(candle: Candle): InsertResult {
        val start = timeframe.bucketStart(candle.timestamp)
        val normalized = if (candle.timestamp == start) candle else candle.copy(timestamp = start)
        val existing = bars[start]
        return when {
            existing == null -> {
                val result =
                    if (bars.isNotEmpty() && start < bars.lastKey()) InsertResult.FILLED else InsertResult.APPENDED
                bars[start] = normalized
                version++
                result
            }

            existing == normalized -> InsertResult.UNCHANGED

            else -> {
                bars[start] = normalized
                version++
                revisions++
                InsertResult.UPDATED
            }
        }
    }

    /** Bulk upsert; returns the count of bars that actually changed the series. */
    fun upsertAll(candles: Iterable<Candle>): Int =
        candles.count { upsert(it) != InsertResult.UNCHANGED }

    /** All bars oldest→newest. */
    fun candles(): List<Candle> = bars.values.toList()

    fun first(): Candle? = bars.firstEntry()?.value
    fun last(): Candle? = bars.lastEntry()?.value

    /** True when at least one bucket between the first and last bar is missing. */
    fun hasGaps(): Boolean = missingBucketStarts().isNotEmpty()

    /**
     * Bucket-open timestamps that are absent between the first and last stored
     * bar, in ascending order. These are precisely the bars the sync layer must
     * fetch to make the series contiguous. Empty for fewer than two bars.
     */
    fun missingBucketStarts(): List<Long> {
        if (bars.size < 2) return emptyList()
        val last = bars.lastKey()
        val missing = mutableListOf<Long>()
        var cursor = timeframe.bucketEnd(bars.firstKey())
        while (cursor < last) {
            if (!bars.containsKey(cursor)) missing.add(cursor)
            cursor = timeframe.bucketEnd(cursor)
        }
        return missing
    }

    fun clear() {
        bars.clear()
        version++
    }
}

/** The outcome of a [CandleCache.upsert]. */
enum class InsertResult {
    /** A brand-new bar at or beyond the current end. */
    APPENDED,

    /** A new bar inserted into a known gap. */
    FILLED,

    /** An existing bar replaced by different data (a tracked revision). */
    UPDATED,

    /** The bar was already present and identical; nothing changed. */
    UNCHANGED,
}
