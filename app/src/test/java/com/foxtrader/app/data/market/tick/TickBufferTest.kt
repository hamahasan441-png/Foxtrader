package com.foxtrader.app.data.market.tick

import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.data.market.model.TickSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring buffer must bound memory (drop oldest when full), preserve insertion
 * order for replay, and never allocate on the hot ingest path.
 */
class TickBufferTest {

    private fun tick(i: Int) = Tick("EURUSD", i.toDouble(), 1.0, i * 1000L)

    @Test
    fun `stores ticks up to capacity`() {
        val buffer = TickBuffer(capacity = 3)
        buffer.add(tick(1))
        buffer.add(tick(2))
        assertEquals(2, buffer.size)
        assertTrue(!buffer.isFull)
    }

    @Test
    fun `overwrites the oldest tick when full and counts drops`() {
        val buffer = TickBuffer(capacity = 3)
        (1..5).forEach { buffer.add(tick(it)) }
        assertEquals(3, buffer.size)
        assertTrue(buffer.isFull)
        assertEquals(2L, buffer.droppedCount)
        // The three newest survive, oldest→newest.
        assertEquals(listOf(3.0, 4.0, 5.0), buffer.snapshot().map { it.price })
    }

    @Test
    fun `snapshot returns oldest to newest`() {
        val buffer = TickBuffer(capacity = 10)
        (1..4).forEach { buffer.add(tick(it)) }
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), buffer.snapshot().map { it.price })
    }

    @Test
    fun `latest and oldest reflect the ring ends`() {
        val buffer = TickBuffer(capacity = 3)
        assertNull(buffer.latest())
        assertNull(buffer.oldest())
        (1..4).forEach { buffer.add(tick(it)) }
        assertEquals(4.0, buffer.latest()!!.price, 0.0)
        assertEquals(2.0, buffer.oldest()!!.price, 0.0)
    }

    @Test
    fun `drainTo appends into the caller list`() {
        val buffer = TickBuffer(capacity = 5)
        (1..3).forEach { buffer.add(tick(it)) }
        val out = mutableListOf(tick(99))
        buffer.drainTo(out)
        assertEquals(listOf(99.0, 1.0, 2.0, 3.0), out.map { it.price })
    }

    @Test
    fun `accepts the pooled mutable tick overload`() {
        val buffer = TickBuffer(capacity = 2)
        val mt = MutableTick().set("GBPUSD", 1.25, 10.0, 5_000L, TickSide.BUY)
        buffer.add(mt)
        val stored = buffer.latest()!!
        assertEquals("GBPUSD", stored.symbol)
        assertEquals(1.25, stored.price, 0.0)
        assertEquals(TickSide.BUY, stored.side)
        // Mutating the source afterwards must not corrupt the stored copy.
        mt.set("XXX", 9.9, 9.9, 9L)
        assertEquals("GBPUSD", buffer.latest()!!.symbol)
    }

    @Test
    fun `clear empties the buffer but retains the drop metric`() {
        val buffer = TickBuffer(capacity = 2)
        (1..5).forEach { buffer.add(tick(it)) }
        assertEquals(3L, buffer.droppedCount)
        buffer.clear()
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.size)
        assertEquals(3L, buffer.droppedCount)
    }

    @Test
    fun `replay reproduces the same candle sequence`() {
        // Buffer a stream, then re-drive a CandleBuilder from the snapshot and
        // confirm it matches a direct run — the offline/replay guarantee.
        val buffer = TickBuffer(capacity = 1000)
        val ticks = (0 until 200).map { tick(it) }
        ticks.forEach(buffer::add)

        val direct = com.foxtrader.app.data.market.candle.CandleBuilder(
            com.foxtrader.app.data.market.model.MarketTimeframe.M1,
        )
        val directOut = mutableListOf<com.foxtrader.app.domain.model.Candle>()
        ticks.forEach { direct.onTick(it)?.let(directOut::add) }
        direct.flush()?.let(directOut::add)

        val replay = com.foxtrader.app.data.market.candle.CandleBuilder(
            com.foxtrader.app.data.market.model.MarketTimeframe.M1,
        )
        val replayOut = mutableListOf<com.foxtrader.app.domain.model.Candle>()
        buffer.snapshot().forEach { replay.onTick(it)?.let(replayOut::add) }
        replay.flush()?.let(replayOut::add)

        assertEquals(directOut, replayOut)
    }

    @Test
    fun `capacity must be positive`() {
        try {
            TickBuffer(0)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
