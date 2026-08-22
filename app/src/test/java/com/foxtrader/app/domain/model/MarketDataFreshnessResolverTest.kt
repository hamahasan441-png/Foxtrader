package com.foxtrader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketDataFreshnessResolverTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `synthetic data is always simulated even when socket says connected`() {
        val result = MarketDataFreshnessResolver.resolve(
            source = CandleSource.SYNTHETIC,
            connectionState = ConnectionState.CONNECTED,
            timeframe = Timeframe.M15,
            latestBarTimestamp = now,
            nowMillis = now,
        )

        assertEquals(MarketDataFreshness.SIMULATED, result)
    }

    @Test
    fun `connected recent real bar is live`() {
        val result = MarketDataFreshnessResolver.resolve(
            source = CandleSource.LIVE,
            connectionState = ConnectionState.CONNECTED,
            timeframe = Timeframe.M15,
            latestBarTimestamp = now - 60_000L,
            nowMillis = now,
        )

        assertEquals(MarketDataFreshness.LIVE, result)
    }

    @Test
    fun `recent real bar without connected stream is delayed not live`() {
        val result = MarketDataFreshnessResolver.resolve(
            source = CandleSource.LIVE,
            connectionState = ConnectionState.CONNECTING,
            timeframe = Timeframe.M15,
            latestBarTimestamp = now - 60_000L,
            nowMillis = now,
        )

        assertEquals(MarketDataFreshness.DELAYED, result)
    }

    @Test
    fun `old real bar is cached`() {
        val result = MarketDataFreshnessResolver.resolve(
            source = CandleSource.CACHED,
            connectionState = ConnectionState.CONNECTED,
            timeframe = Timeframe.M15,
            latestBarTimestamp = now - 60L * 60_000L,
            nowMillis = now,
        )

        assertEquals(MarketDataFreshness.CACHED, result)
    }
}
