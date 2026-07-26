package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.MarketRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ) : MarketRepository {
        override fun observeCandles(symbol: String, timeframe: Timeframe): Flow<List<Candle>> = emptyFlow()

        override suspend fun refreshCandles(symbol: String, timeframe: Timeframe, limit: Int): Result<Unit> =
            Result.success(Unit)

        override suspend fun upsertCandle(symbol: String, timeframe: Timeframe, candle: Candle) = Unit

        override suspend fun getCandles(symbol: String, timeframe: Timeframe): List<Candle> = data[symbol].orEmpty()
    }
}
