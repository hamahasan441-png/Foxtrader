package com.foxtrader.app.domain.usecase.replay

import com.foxtrader.app.domain.model.Tick
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.tick.TickAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for tick-aware replay ([ReplayEngine.startTickReplay]).
 * Uses real [TickAggregator] and [ReplayEngine] instances with synthetic ticks.
 */
class ReplayEngineTickTest {

    private lateinit var aggregator: TickAggregator
    private lateinit var engine: ReplayEngine
    private lateinit var ticks: List<Tick>

    @Before
    fun setup() {
        aggregator = TickAggregator()
        engine = ReplayEngine(aggregator)
        // 60 ticks, each in a distinct M1 bucket -> 60 candles after aggregation.
        ticks = (0 until 60).map { i ->
            Tick(
                timestampMs = i * 60_000L + 1_000L,
                bid = 100.0 + i * 0.1,
                ask = 100.2 + i * 0.1,
                bidVolume = 1.0,
                askVolume = 1.0,
            )
        }
    }

    @Test
    fun `tick replay total bars equals aggregated candle count`() {
        val expectedCount = aggregator.aggregate(ticks, Timeframe.M1).size
        engine.startTickReplay(ticks, aggregateTo = Timeframe.M1, startAt = 10)

        assertEquals(expectedCount, engine.state.value.totalBars)
    }

    @Test
    fun `startTickReplay activates replay`() {
        engine.startTickReplay(ticks, aggregateTo = Timeframe.M1, startAt = 10)

        assertTrue("Replay should be active after startTickReplay", engine.state.value.isActive)
    }
}
