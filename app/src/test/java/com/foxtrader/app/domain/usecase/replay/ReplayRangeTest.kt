package com.foxtrader.app.domain.usecase.replay

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.tick.TickAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayRangeTest {
    private val candles = List(40) { i ->
        Candle(
            timestamp = 1_700_000_000_000L + i * 60_000L,
            open = 100.0 + i,
            high = 101.0 + i,
            low = 99.0 + i,
            close = 100.5 + i,
            volume = 100.0,
        )
    }

    @Test
    fun `bounded replay preserves warmup prefix and cannot escape selected range`() {
        val engine = ReplayEngine(TickAggregator())
        engine.startRange(candles, startBarIndex = 10, endBarIndex = 15)

        val start = engine.state.value
        assertTrue(start.isBounded)
        assertEquals(11, start.currentIndex)
        assertEquals(16, start.endIndex)
        assertEquals(candles.take(11), start.visibleCandles)
        assertEquals(0f, start.progress, 0.0001f)

        engine.stepBackward()
        assertEquals(11, engine.state.value.currentIndex)

        engine.jumpTo(1)
        assertEquals(11, engine.state.value.currentIndex)

        engine.jumpTo(500)
        assertEquals(16, engine.state.value.currentIndex)
        assertEquals(candles.take(16), engine.state.value.visibleCandles)
        assertTrue(engine.state.value.isAtRangeEnd)
        assertEquals(1f, engine.state.value.progress, 0.0001f)

        engine.stepForward()
        assertEquals(16, engine.state.value.currentIndex)
    }

    @Test
    fun `future candles after selected replay end are never visible`() {
        val engine = ReplayEngine(TickAggregator())
        engine.startRange(candles, startBarIndex = 5, endBarIndex = 9)

        repeat(20) { engine.stepForward() }

        val state = engine.state.value
        assertEquals(10, state.currentIndex)
        assertEquals(candles.take(10), state.visibleCandles)
        assertEquals(candles[9], state.visibleCandles.last())
    }

    @Test
    fun `legacy replay remains unbounded and keeps original progress semantics`() {
        val engine = ReplayEngine(TickAggregator())
        engine.start(candles, startAt = 20)

        assertTrue(!engine.state.value.isBounded)
        assertEquals(0.5f, engine.state.value.progress, 0.0001f)
        engine.stepBackward()
        assertEquals(19, engine.state.value.currentIndex)
    }
}
