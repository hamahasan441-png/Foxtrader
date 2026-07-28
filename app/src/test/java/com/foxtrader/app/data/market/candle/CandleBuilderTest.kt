package com.foxtrader.app.data.market.candle

import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-repaint guarantee is the engine's core correctness property: a sealed
 * candle is emitted exactly once and never changes, even when late or
 * out-of-order ticks arrive afterward. These tests pin that behaviour down.
 */
class CandleBuilderTest {

    private val m = 60_000L // one M1 bucket

    private fun tick(price: Double, ts: Long, qty: Double = 1.0) =
        Tick("BTCUSDT", price, qty, ts)

    private fun run(ticks: List<Tick>, tf: MarketTimeframe = MarketTimeframe.M1): List<Candle> {
        val builder = CandleBuilder(tf)
        val out = mutableListOf<Candle>()
        ticks.forEach { t -> builder.onTick(t)?.let(out::add) }
        builder.flush()?.let(out::add)
        return out
    }

    @Test
    fun `first tick opens a forming bucket and emits nothing`() {
        val builder = CandleBuilder(MarketTimeframe.M1)
        assertNull(builder.onTick(tick(100.0, 0L)))
    }

    @Test
    fun `ticks in the same bucket fold into correct OHLCV`() {
        val builder = CandleBuilder(MarketTimeframe.M1)
        builder.onTick(tick(100.0, 0L, qty = 2.0))
        builder.onTick(tick(110.0, 10_000L, qty = 3.0))
        builder.onTick(tick(90.0, 20_000L, qty = 5.0))
        val sealed = builder.onTick(tick(120.0, m)) // crosses into the next bucket

        assertEquals(Candle(0L, open = 100.0, high = 110.0, low = 90.0, close = 90.0, volume = 10.0), sealed)
    }

    @Test
    fun `a single-tick bucket has open == high == low == close`() {
        val builder = CandleBuilder(MarketTimeframe.M1)
        builder.onTick(tick(42.0, 5_000L, qty = 7.0))
        val sealed = builder.flush()
        assertEquals(Candle(0L, 42.0, 42.0, 42.0, 42.0, 7.0), sealed)
    }

    @Test
    fun `candle timestamp is the bucket open time, not the tick time`() {
        val builder = CandleBuilder(MarketTimeframe.M1)
        builder.onTick(tick(1.0, 30_000L))
        builder.onTick(tick(2.0, 45_000L))
        val sealed = builder.flush()
        assertEquals(0L, sealed!!.timestamp)
    }

    @Test
    fun `late ticks for a sealed bucket are rejected and change nothing`() {
        val builder = CandleBuilder(MarketTimeframe.M1)
        builder.onTick(tick(100.0, 0L))
        builder.onTick(tick(110.0, 10_000L))
        val sealed = builder.onTick(tick(105.0, m)) // seals [0, m)
        assertEquals(100.0, sealed!!.open, 0.0)
        assertEquals(110.0, sealed.high, 0.0)

        // A late tick that *would* raise the high of the sealed bucket.
        val late = builder.onTick(tick(999.0, 20_000L))
        assertNull("late tick must not emit", late)
        assertEquals(1L, builder.rejectedLateTicks)

        // The sealed candle captured earlier is immutable and unchanged.
        assertEquals(110.0, sealed.high, 0.0)

        // The forming bucket [m, 2m) is still intact and flushes cleanly.
        val second = builder.flush()
        assertEquals(m, second!!.timestamp)
        assertEquals(105.0, second.open, 0.0)
    }

    @Test
    fun `a forward gap seals only the finished bucket and fabricates no empties`() {
        val builder = CandleBuilder(MarketTimeframe.M1)
        builder.onTick(tick(1.0, 0L))
        // Jump from bucket [0,m) straight to [10m, 11m): nine empty buckets between.
        val sealed = builder.onTick(tick(2.0, 10 * m))
        assertEquals(0L, sealed!!.timestamp)
        // Nothing else is emitted until the next crossing.
        assertNull(builder.onTick(tick(3.0, 10 * m + 1_000L)))
        val end = builder.flush()
        assertEquals(10 * m, end!!.timestamp)
    }

    @Test
    fun `flush returns null when nothing is forming`() {
        val builder = CandleBuilder(MarketTimeframe.M1)
        assertNull(builder.flush())
    }

    @Test
    fun `flushed bucket stays sealed against later late ticks`() {
        val builder = CandleBuilder(MarketTimeframe.M1)
        builder.onTick(tick(5.0, 0L))
        builder.flush() // seals [0, m)
        assertNull(builder.onTick(tick(6.0, 30_000L))) // late for flushed bucket
        assertEquals(1L, builder.rejectedLateTicks)
    }

    @Test
    fun `emitted candles are strictly monotonic even with out-of-order input`() {
        // Deliberately messy: a late tick is injected after the stream advanced.
        val ticks = listOf(
            tick(1.0, 0L),
            tick(2.0, 30_000L),
            tick(3.0, m),          // seals bucket 0
            tick(4.0, m + 30_000L),
            tick(9.0, 15_000L),    // LATE for bucket 0 — must be dropped
            tick(5.0, 2 * m),      // seals bucket 1
            tick(6.0, 3 * m),      // seals bucket 2
        )
        val candles = run(ticks)
        val timestamps = candles.map { it.timestamp }
        assertEquals("no duplicate/repainted bars", timestamps.distinct(), timestamps)
        assertEquals("strictly increasing bar times", timestamps.sorted(), timestamps)
        // Buckets 0,1,2 are sealed by crossings; bucket 3 (the last tick) is flushed.
        assertEquals(listOf(0L, m, 2 * m, 3 * m), timestamps)
        assertEquals(1, CandleBuilder(MarketTimeframe.M1).also { b -> ticks.forEach { b.onTick(it) } }.rejectedLateTicks)
    }

    @Test
    fun `weekly bucketing groups a full week of ticks into one candle`() {
        // Monday 2024-01-01 UTC.
        val monday = java.time.LocalDate.of(2024, 1, 1)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val day = 86_400_000L
        val ticks = (0L..6L).map { d -> tick(100.0 + d, monday + d * day) } +
            tick(500.0, monday + 7 * day) // next Monday seals the week
        val candles = run(ticks, MarketTimeframe.W1)
        // The Mon..Sun ticks form one weekly bar; the next-Monday tick that seals
        // it opens the following week, which flush emits as a single-tick bar.
        assertEquals(2, candles.size)
        assertEquals(monday, candles[0].timestamp)
        assertEquals(100.0, candles[0].open, 0.0)
        assertEquals(106.0, candles[0].high, 0.0)
        assertEquals(106.0, candles[0].close, 0.0)
        assertEquals(7.0, candles[0].volume, 0.0)
        assertEquals(monday + 7 * day, candles[1].timestamp)
        assertEquals(500.0, candles[1].open, 0.0)
    }

    @Test
    fun `replaying the identical tick stream yields identical candles`() {
        val ticks = listOf(
            tick(10.0, 0L, 1.0),
            tick(12.0, 20_000L, 2.0),
            tick(9.0, 40_000L, 1.0),
            tick(11.0, m, 3.0),
            tick(13.0, m + 30_000L, 1.0),
            tick(14.0, 2 * m, 2.0),
        )
        assertEquals(run(ticks), run(ticks))
    }

    @Test
    fun `rejects nothing when ticks are perfectly ordered`() {
        val builder = CandleBuilder(MarketTimeframe.M1)
        repeat(5) { i -> builder.onTick(tick(i.toDouble(), i * m)) }
        builder.flush()
        assertEquals(0L, builder.rejectedLateTicks)
        assertTrue(builder.rejectedLateTicks == 0L)
    }
}
