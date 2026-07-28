package com.foxtrader.app.data.market.provider

import com.foxtrader.app.data.market.candle.buildCandles
import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The provider abstraction must let identical business logic run against any
 * source. These tests drive a fake provider through the same candle pipeline
 * the real Binance/Dukascopy adapters will use, and show capability-based
 * branching instead of concrete-type checks.
 */
class MarketDataProviderTest {

    private fun monday() =
        LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** A stand-in for a real feed; swaps in without touching business logic. */
    private class FakeProvider(
        private val ticks: List<Tick>,
        private val historical: List<Candle>,
        private val live: Boolean = true,
    ) : MarketDataProvider {
        override val provider = DataProvider.SAMPLE
        override val capabilities = ProviderCapability(
            supportsLiveTicks = live,
            supportsHistoricalCandles = true,
            supportedTimeframes = MarketTimeframe.ALL.toSet(),
        )

        override fun connectTicks(symbol: String): Flow<Tick> = flow {
            ticks.forEach { emit(it) }
        }

        override suspend fun fetchCandles(
            symbol: String,
            timeframe: MarketTimeframe,
            from: Long,
            to: Long,
        ): List<Candle> = historical.filter { it.timestamp in from until to }

        override fun disconnect() = Unit
    }

    /** Provider-agnostic business logic: seed from history, then build live bars. */
    private suspend fun runPipeline(
        provider: MarketDataProvider,
        symbol: String,
        timeframe: MarketTimeframe,
    ): Pair<List<Candle>, List<Candle>> {
        val seed = provider.fetchCandles(symbol, timeframe, from = 0L, to = Long.MAX_VALUE)
        val live = if (provider.capabilities.supportsLiveTicks) {
            provider.connectTicks(symbol)
                .buildCandles(listOf(timeframe))
                .toList()
                .map { it.candle }
        } else {
            emptyList()
        }
        return seed to live
    }

    @Test
    fun `the same pipeline works against an arbitrary provider`() = runTest {
        val start = monday()
        // Two minutes of one-second ticks => two M1 bars (second flushed).
        val ticks = (0L until 120L).map { Tick("BTCUSDT", 100.0 + it, 1.0, start + it * 1000L) }
        val historical = listOf(Candle(start, 1.0, 1.0, 1.0, 1.0, 1.0))
        val provider = FakeProvider(ticks, historical)

        val (seed, live) = runPipeline(provider, "BTCUSDT", MarketTimeframe.M1)

        assertEquals(1, seed.size)
        assertEquals(2, live.size)
        assertEquals(start, live[0].timestamp)
        assertEquals(start + 60_000L, live[1].timestamp)
    }

    @Test
    fun `a REST-only source skips the live path via capability`() = runTest {
        val start = monday()
        val historical = listOf(Candle(start, 1.0, 2.0, 0.5, 1.5, 5.0))
        val provider = FakeProvider(ticks = emptyList(), historical = historical, live = false)

        assertFalse(provider.capabilities.supportsLiveTicks)
        val (seed, live) = runPipeline(provider, "EURUSD", MarketTimeframe.M1)

        assertEquals(1, seed.size)
        assertTrue("no live bars from a tick-less source", live.isEmpty())
    }

    @Test
    fun `historical fetch honours the requested range`() = runTest {
        val start = monday()
        val m = 60_000L
        val historical = listOf(
            Candle(start, 1.0, 1.0, 1.0, 1.0, 1.0),
            Candle(start + m, 1.0, 1.0, 1.0, 1.0, 1.0),
            Candle(start + 2 * m, 1.0, 1.0, 1.0, 1.0, 1.0),
        )
        val provider = FakeProvider(emptyList(), historical)
        val slice = provider.fetchCandles("X", MarketTimeframe.M1, start + m, start + 3 * m)
        assertEquals(listOf(start + m, start + 2 * m), slice.map { it.timestamp })
    }

    @Test
    fun `capability reports per-timeframe support`() {
        val cap = ProviderCapability(
            supportsLiveTicks = true,
            supportsHistoricalCandles = true,
            supportedTimeframes = setOf(MarketTimeframe.M1, MarketTimeframe.H1),
        )
        assertTrue(cap.supports(MarketTimeframe.M1))
        assertFalse(cap.supports(MarketTimeframe.MN))
    }
}
