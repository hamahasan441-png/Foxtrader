package com.foxtrader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provenance semantics.
 *
 * The critical property is that a mixed series degrades to its *least*
 * trustworthy member — a cache holding 400 real bars and 100 synthetic seed
 * bars is not "mostly real", it is untrustworthy.
 */
class CandleSourceTest {

    @Test
    fun `only synthetic is untrustworthy`() {
        assertTrue(CandleSource.LIVE.isTrustworthy)
        assertTrue(CandleSource.CACHED.isTrustworthy)
        assertFalse(CandleSource.SYNTHETIC.isTrustworthy)
    }

    @Test
    fun `worstOf collapses a mixed series to synthetic`() {
        val mixed = List(400) { CandleSource.LIVE } + List(1) { CandleSource.SYNTHETIC }
        assertEquals(CandleSource.SYNTHETIC, CandleSource.worstOf(mixed))
    }

    @Test
    fun `worstOf prefers cached over live when both present`() {
        assertEquals(
            CandleSource.CACHED,
            CandleSource.worstOf(listOf(CandleSource.LIVE, CandleSource.CACHED)),
        )
    }

    @Test
    fun `worstOf of all live is live`() {
        assertEquals(
            CandleSource.LIVE,
            CandleSource.worstOf(List(10) { CandleSource.LIVE }),
        )
    }

    @Test
    fun `empty series reports cached rather than live`() {
        // "No evidence" must never be presented as "verified live".
        assertEquals(CandleSource.CACHED, CandleSource.worstOf(emptyList()))
    }

    @Test
    fun `fromStorage round-trips every value`() {
        CandleSource.entries.forEach { source ->
            assertEquals(source, CandleSource.fromStorage(source.name))
        }
    }

    @Test
    fun `fromStorage defaults unknown and null to live`() {
        assertEquals(CandleSource.LIVE, CandleSource.fromStorage(null))
        assertEquals(CandleSource.LIVE, CandleSource.fromStorage("GARBAGE"))
    }

    @Test
    fun `sourced candles reports synthetic and emptiness`() {
        val synthetic = SourcedCandles(
            candles = listOf(Candle(1L, 1.0, 2.0, 0.5, 1.5, 10.0)),
            source = CandleSource.SYNTHETIC,
        )
        assertTrue(synthetic.isSynthetic)
        assertFalse(synthetic.isEmpty)
        assertTrue(SourcedCandles.EMPTY.isEmpty)
        assertFalse(SourcedCandles.EMPTY.isSynthetic)
    }
}
