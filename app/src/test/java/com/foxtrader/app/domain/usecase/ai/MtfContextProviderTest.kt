package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.SourcedCandles
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtfContextProviderTest {

    @Test
    fun `correlated context returns configured peers with sufficient data only`() = runBlocking {
        val repo = FakeMarketRepository(
            mapOf(
                "GBPUSD" to candles(60),
                "AUDUSD" to candles(20), // insufficient: should be filtered out
            )
        )
        val provider = MtfContextProvider(repo)

        val result = provider.getCorrelatedContext("EURUSD", Timeframe.H1)

        assertEquals(setOf("GBPUSD"), result.keys)
        assertEquals(60, result["GBPUSD"]?.size)
        assertFalse(result.containsKey("EURUSD"))
    }

    @Test
    fun `correlated context rejects synthetic peer candles`() = runBlocking {
        val repo = FakeMarketRepository(
            data = mapOf("GBPUSD" to candles(60)),
            source = CandleSource.SYNTHETIC,
        )
        val provider = MtfContextProvider(repo)

        val result = provider.getCorrelatedContext("EURUSD", Timeframe.H1)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `explicit correlated refresh replaces synthetic cache with real peer data`() = runBlocking {
        val repo = FakeMarketRepository(
            data = mapOf(
                "GBPUSD" to candles(60),
                "AUDUSD" to candles(60),
            ),
            source = CandleSource.SYNTHETIC,
            sourceAfterRefresh = CandleSource.LIVE,
        )
        val provider = MtfContextProvider(repo)

        val result = provider.getCorrelatedContext(
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            refreshMissing = true,
        )

        assertEquals(setOf("GBPUSD", "AUDUSD"), result.keys)
        assertEquals(2, repo.refreshCalls)
    }

    private fun candles(count: Int): List<Candle> = (0 until count).map { i ->
        Candle(
            timestamp = i * 60_000L,
            open = 100.0 + i,
            high = 101.0 + i,
            low = 99.0 + i,
            close = 100.5 + i,
            volume = 100.0,
        )
    }

    private class FakeMarketRepository(
        private val data: Map<String, List<Candle>>,
        private val source: CandleSource = CandleSource.LIVE,
        private val sourceAfterRefresh: CandleSource? = null,
    ) : MarketRepository {
        private val currentSources = mutableMapOf<String, CandleSource>()
        var refreshCalls: Int = 0
            private set

        override fun observeCandles(symbol: String, timeframe: Timeframe): Flow<List<Candle>> = emptyFlow()

        override fun observeSourcedCandles(
            symbol: String,
            timeframe: Timeframe,
        ): Flow<SourcedCandles> = emptyFlow()

        override suspend fun refreshCandles(symbol: String, timeframe: Timeframe, limit: Int): Result<Unit> {
            refreshCalls++
            sourceAfterRefresh?.let { currentSources[symbol] = it }
            return Result.success(Unit)
        }

        override suspend fun upsertCandle(symbol: String, timeframe: Timeframe, candle: Candle) = Unit

        override suspend fun getSourcedCandles(
            symbol: String,
            timeframe: Timeframe,
        ): SourcedCandles = SourcedCandles(
            data[symbol].orEmpty(),
            currentSources[symbol] ?: source,
        )

        override suspend fun loadOlderCandles(
            symbol: String,
            timeframe: Timeframe,
            beforeTimestamp: Long,
            limit: Int,
        ): Result<SourcedCandles> = Result.success(
            SourcedCandles(emptyList(), CandleSource.CACHED)
        )

        override suspend fun testProviderConnection(): Result<Int> = Result.success(0)

        override suspend fun testBackendConnection(): Result<Int> = Result.success(0)

        override suspend fun clearMarketDataCache() = Unit
    }
}
