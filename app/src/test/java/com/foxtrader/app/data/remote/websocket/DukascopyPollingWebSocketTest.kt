package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.data.remote.dukascopy.DukascopyDataSource
import com.foxtrader.app.data.remote.dukascopy.DukascopyTickDecoder
import com.foxtrader.app.data.remote.dukascopy.LzmaDecompressor
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.tick.TickAggregator
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class DukascopyPollingWebSocketTest {
    @Test
    fun `active intraday candle is refreshed every second`() {
        val dataSource = DukascopyDataSource(
            OkHttpClient(), DukascopyTickDecoder(), LzmaDecompressor(), TickAggregator(),
        )
        val socket = DukascopyPollingWebSocket(dataSource, Dispatchers.Unconfined)
        listOf(Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1, Timeframe.H4, Timeframe.D1)
            .forEach { timeframe -> assertEquals(1_000L, socket.pollIntervalMs(timeframe)) }
        assertEquals(5_000L, socket.pollIntervalMs(Timeframe.W1))
    }
}
