package com.foxtrader.app.data.market.cache

import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline-first cache behaviour: sorted storage, gap detection for recovery,
 * monotonic versioning, and no-repaint upserts (identical bars are a no-op).
 */
class CandleCacheTest {

    private val m = 60_000L

    private fun bar(bucketStart: Long, close: Double = 1.0) =
        Candle(bucketStart, open = 1.0, high = 2.0, low = 0.5, close = close, volume = 10.0)

    @Test
    fun `appends keep the series sorted and bump the version`() {
        val cache = CandleCache(MarketTimeframe.M1)
        assertEquals(InsertResult.APPENDED, cache.upsert(bar(2 * m)))
        assertEquals(InsertResult.APPENDED, cache.upsert(bar(0 * m)))
        assertEquals(InsertResult.APPENDED, cache.upsert(bar(1 * m)))
        assertEquals(listOf(0L, m, 2 * m), cache.candles().map { it.timestamp })
        assertEquals(3L, cache.version)
    }

    @Test
    fun `detects the exact missing buckets between first and last`() {
        val cache = CandleCache(MarketTimeframe.M1)
        cache.upsert(bar(0))
        cache.upsert(bar(2 * m))
        cache.upsert(bar(3 * m))
        assertEquals(listOf(m), cache.missingBucketStarts())
        assertTrue(cache.hasGaps())
    }

    @Test
    fun `filling a gap clears it and is reported as FILLED`() {
        val cache = CandleCache(MarketTimeframe.M1)
        cache.upsert(bar(0))
        cache.upsert(bar(2 * m))
        val before = cache.version
        assertEquals(InsertResult.FILLED, cache.upsert(bar(m)))
        assertFalse(cache.hasGaps())
        assertEquals(emptyList<Long>(), cache.missingBucketStarts())
        assertEquals(before + 1, cache.version)
    }

    @Test
    fun `re-storing an identical bar is a no-op`() {
        val cache = CandleCache(MarketTimeframe.M1)
        cache.upsert(bar(0))
        val version = cache.version
        assertEquals(InsertResult.UNCHANGED, cache.upsert(bar(0)))
        assertEquals(version, cache.version) // no spurious version bump
        assertEquals(0L, cache.revisions)
    }

    @Test
    fun `replacing a bar with different data is a tracked revision`() {
        val cache = CandleCache(MarketTimeframe.M1)
        cache.upsert(bar(0, close = 1.0))
        val version = cache.version
        assertEquals(InsertResult.UPDATED, cache.upsert(bar(0, close = 9.0)))
        assertEquals(version + 1, cache.version)
        assertEquals(1L, cache.revisions)
        assertEquals(9.0, cache.first()!!.close, 0.0)
    }

    @Test
    fun `timestamps are normalised to the bucket open`() {
        val cache = CandleCache(MarketTimeframe.M1)
        // A bar stamped mid-bucket is stored at the bucket open (0).
        cache.upsert(Candle(30_000L, 1.0, 2.0, 0.5, 1.0, 10.0))
        assertEquals(0L, cache.first()!!.timestamp)
        // The same bar stamped at the boundary is therefore identical.
        assertEquals(InsertResult.UNCHANGED, cache.upsert(bar(0)))
    }

    @Test
    fun `upsertAll counts only real changes`() {
        val cache = CandleCache(MarketTimeframe.M1)
        val changed = cache.upsertAll(listOf(bar(0), bar(m), bar(0)))
        assertEquals(2, changed)
    }

    @Test
    fun `fewer than two bars have no gaps`() {
        val cache = CandleCache(MarketTimeframe.M1)
        assertFalse(cache.hasGaps())
        cache.upsert(bar(0))
        assertFalse(cache.hasGaps())
    }

    @Test
    fun `monthly gap detection steps by calendar month`() {
        val cache = CandleCache(MarketTimeframe.MN)
        val jan = java.time.LocalDate.of(2024, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val feb = java.time.LocalDate.of(2024, 2, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val mar = java.time.LocalDate.of(2024, 3, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val apr = java.time.LocalDate.of(2024, 4, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        cache.upsert(bar(jan))
        cache.upsert(bar(apr))
        // February and March are missing.
        assertEquals(listOf(feb, mar), cache.missingBucketStarts())
    }
}
