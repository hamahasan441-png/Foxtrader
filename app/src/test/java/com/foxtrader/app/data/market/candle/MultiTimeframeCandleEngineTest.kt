package com.foxtrader.app.data.market.candle

import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fanning one tick stream out to every timeframe, plus a large-dataset stress
 * pass that proves the no-repaint invariant and bucket counts hold at scale.
 */
class MultiTimeframeCandleEngineTest {

    private fun tick(ts: Long, price: Double = 100.0, qty: Double = 1.0) =
        Tick("ETHUSDT", price, qty, ts)

    private fun collect(
        ticks: Sequence<Tick>,
        tfs: Collection<MarketTimeframe> = MarketTimeframe.ALL,
    ): Map<MarketTimeframe, List<Candle>> {
        val engine = MultiTimeframeCandleEngine(tfs)
        val sink = tfs.associateWith { mutableListOf<Candle>() }
        ticks.forEach { t -> engine.onTick(t) { tf, c -> sink[tf]!!.add(c) } }
        engine.flush().forEach { (tf, c) -> sink[tf]!!.add(c) }
        return sink
    }

    @Test
    fun `builds all twelve timeframes`() {
        val engine = MultiTimeframeCandleEngine(MarketTimeframe.ALL)
        assertEquals(12, engine.timeframes.size)
    }

    @Test
    fun `a single tick opens forming buckets and emits nothing`() {
        val engine = MultiTimeframeCandleEngine(MarketTimeframe.ALL)
        val closed = engine.onTickCollect(tick(0L))
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `crossing a one-minute boundary closes only M1`() {
        val engine = MultiTimeframeCandleEngine(MarketTimeframe.ALL)
        val start = mondayEpoch()
        // Two ticks straddling the first minute; nothing else should close.
        engine.onTickCollect(tick(start))
        val closed = engine.onTickCollect(tick(start + 60_000L))
        assertEquals(setOf(MarketTimeframe.M1), closed.keys)
    }

    @Test
    fun `crossing a shared boundary closes every timeframe that ends there`() {
        val engine = MultiTimeframeCandleEngine(MarketTimeframe.ALL)
        val start = mondayEpoch()
        engine.onTickCollect(tick(start))
        // Jump to exactly 4h later: M1,M2,M3,M5,M10,M15,M30,H1 all end at 4h;
        // H4 also ends at 4h. Larger frames (D1/W1/MN) do not.
        val closed = engine.onTickCollect(tick(start + 4 * 3_600_000L))
        val expected = setOf(
            MarketTimeframe.M1, MarketTimeframe.M2, MarketTimeframe.M3,
            MarketTimeframe.M5, MarketTimeframe.M10, MarketTimeframe.M15,
            MarketTimeframe.M30, MarketTimeframe.H1, MarketTimeframe.H4,
        )
        assertEquals(expected, closed.keys)
    }

    @Test
    fun `two hours of one-second ticks produce the exact bucket counts per timeframe`() {
        val start = mondayEpoch()
        val seconds = 7200L
        val ticks = (0L until seconds).asSequence().map { s -> tick(start + s * 1000L, price = 100.0 + (s % 50)) }
        val result = collect(ticks)

        // Buckets touched = floor((seconds-1)*1000 / durationMs) + 1.
        fun expected(tf: MarketTimeframe): Int {
            val d = tf.fixedDurationMs ?: return 1
            return (( (seconds - 1) * 1000L) / d).toInt() + 1
        }

        MarketTimeframe.ALL.forEach { tf ->
            val candles = result[tf]!!
            assertEquals("$tf candle count", expected(tf), candles.size)
            // No-repaint: strictly increasing bar open times.
            val times = candles.map { it.timestamp }
            assertEquals("$tf bars must be monotonic", times.sorted(), times)
            assertEquals("$tf bars must be unique", times.distinct(), times)
        }
    }

    @Test
    fun `volume is conserved across the M1 aggregation of a one-second stream`() {
        val start = mondayEpoch()
        val ticksPerBucket = 60 // one per second
        val buckets = 3
        val totalTicks = ticksPerBucket * buckets
        val ticks = (0L until totalTicks).asSequence().map { s -> tick(start + s * 1000L, qty = 2.0) }
        val engine = MultiTimeframeCandleEngine(listOf(MarketTimeframe.M1))
        val m1 = mutableListOf<Candle>()
        ticks.forEach { t -> engine.onTick(t) { _, c -> m1.add(c) } }
        engine.flush().forEach { (_, c) -> m1.add(c) }

        assertEquals(buckets, m1.size)
        m1.forEach { candle -> assertEquals(2.0 * ticksPerBucket, candle.volume, 0.0) }
    }

    @Test
    fun `stress - one million ticks aggregate correctly and stay monotonic`() {
        val start = mondayEpoch()
        val n = 1_000_000L
        val engine = MultiTimeframeCandleEngine(listOf(MarketTimeframe.M1, MarketTimeframe.H1))
        val m1 = mutableListOf<Candle>()
        val h1 = mutableListOf<Candle>()
        var i = 0L
        while (i < n) {
            engine.onTick(tick(start + i * 1000L, price = 100.0 + (i % 7))) { tf, c ->
                when (tf) {
                    MarketTimeframe.M1 -> m1.add(c)
                    MarketTimeframe.H1 -> h1.add(c)
                    else -> Unit
                }
            }
            i++
        }
        engine.flush().forEach { (tf, c) -> if (tf == MarketTimeframe.M1) m1.add(c) else h1.add(c) }

        val expectedM1 = ((n - 1) / 60L).toInt() + 1
        val expectedH1 = ((n - 1) / 3600L).toInt() + 1
        assertEquals(expectedM1, m1.size)
        assertEquals(expectedH1, h1.size)

        val m1Times = m1.map { it.timestamp }
        assertEquals("M1 monotonic", m1Times.sorted(), m1Times)
        assertEquals("M1 unique", m1Times.distinct(), m1Times)
        assertEquals(0L, engine.totalRejectedLateTicks())
    }

    private fun mondayEpoch(): Long =
        LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
