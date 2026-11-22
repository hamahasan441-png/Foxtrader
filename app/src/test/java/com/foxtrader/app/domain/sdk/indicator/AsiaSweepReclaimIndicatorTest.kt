package com.foxtrader.app.domain.sdk.indicator

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.builtin.AsiaSweepReclaimIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AsiaSweepReclaimIndicatorTest {
    private val indicator = AsiaSweepReclaimIndicator()
    private val day = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
    private val step = 15 * 60_000L

    @Test
    fun `short reclaim is confirmed once and does not move`() {
        val candles = session()
        val full = indicator.compute(candles, mapOf("pip" to 0.0001))
        val shorts = full.signals.filter { it.type.name == "SELL" }
        assertEquals(1, shorts.size)
        val mark = shorts.single()
        assertEquals(1.0990, mark.price, 0.0000001)
        assertEquals("Asia reclaim", mark.label)

        val prefix = indicator.compute(candles.subList(0, mark.index + 1), mapOf("pip" to 0.0001))
        val earlier = prefix.signals.single()
        assertEquals(mark.index, earlier.index)
        assertEquals(mark.price, earlier.price, 0.0)
        assertEquals(mark.type, earlier.type)
    }

    @Test
    fun `open Asia session does not print a signal`() {
        val asiaOnly = session().take(20)
        val result = indicator.compute(asiaOnly, mapOf("pip" to 0.0001))
        assertTrue(result.signals.isEmpty())
    }

    private fun session(): List<Candle> {
        val out = ArrayList<Candle>()
        for (i in 0 until 40) {
            val t = day + i * step
            out += Candle(t, 1.1000, 1.1020, 1.0980, 1.1002, 10.0)
        }
        val sweepAt = 40
        val t = day + sweepAt * step
        out += Candle(t, 1.1010, 1.1035, 1.0988, 1.0990, 10.0)
        out += Candle(t + step, 1.0990, 1.0994, 1.0986, 1.0992, 10.0)
        return out
    }
}
