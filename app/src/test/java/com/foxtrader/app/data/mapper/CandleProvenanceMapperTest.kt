package com.foxtrader.app.data.mapper

import com.foxtrader.app.data.local.entity.CandleEntity
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapper-level provenance rules.
 *
 * These guard the boundary where a synthetic bar could be laundered into the
 * trustworthy set: if [toEntity] silently defaulted a seed bar to LIVE, or
 * [provenance] averaged instead of taking the worst case, the whole Sprint 6
 * data-integrity guarantee would be void while still looking correct.
 */
class CandleProvenanceMapperTest {

    private fun candle(ts: Long) = Candle(
        timestamp = ts,
        open = 1.10,
        high = 1.12,
        low = 1.09,
        close = 1.11,
        volume = 100.0,
    )

    private fun entity(ts: Long, source: String) = CandleEntity(
        symbol = "EURUSD",
        timeframe = "15m",
        timestamp = ts,
        open = 1.10,
        high = 1.12,
        low = 1.09,
        close = 1.11,
        volume = 100.0,
        source = source,
    )

    @Test
    fun `toEntity records the requested provenance`() {
        val e = candle(1L).toEntity("EURUSD", Timeframe.M15, CandleSource.SYNTHETIC)
        assertEquals(CandleSource.SYNTHETIC.name, e.source)
    }

    @Test
    fun `toEntity defaults to live`() {
        val e = candle(1L).toEntity("EURUSD", Timeframe.M15)
        assertEquals(CandleSource.LIVE.name, e.source)
    }

    @Test
    fun `toEntity preserves ohlcv and key fields`() {
        val e = candle(42L).toEntity("GBPUSD", Timeframe.H1, CandleSource.CACHED)
        assertEquals("GBPUSD", e.symbol)
        assertEquals("1H", e.timeframe)
        assertEquals(42L, e.timestamp)
        assertEquals(1.11, e.close, 1e-9)
        assertEquals(100.0, e.volume, 1e-9)
    }

    @Test
    fun `provenance of an all-live series is live`() {
        val rows = (1L..5L).map { entity(it, "LIVE") }
        assertEquals(CandleSource.LIVE, rows.provenance())
    }

    @Test
    fun `a single synthetic bar poisons the whole series`() {
        val rows = (1L..499L).map { entity(it, "LIVE") } + entity(500L, "SYNTHETIC")
        assertEquals(
            "one fabricated bar must degrade the series",
            CandleSource.SYNTHETIC,
            rows.provenance(),
        )
    }

    @Test
    fun `legacy rows with unknown source degrade to live`() {
        // Matches CandleSource.fromStorage's documented default.
        val rows = listOf(entity(1L, "NOT_A_SOURCE"))
        assertEquals(CandleSource.LIVE, rows.provenance())
    }

    @Test
    fun `empty cache reports cached not live`() {
        assertEquals(CandleSource.CACHED, emptyList<CandleEntity>().provenance())
    }
}
