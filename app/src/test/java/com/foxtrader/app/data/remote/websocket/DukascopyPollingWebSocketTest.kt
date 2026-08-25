package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.data.remote.dukascopy.DukascopyDataSource
import com.foxtrader.app.domain.model.Timeframe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class DukascopyPollingWebSocketTest {
    private val socket = DukascopyPollingWebSocket(mockk<DukascopyDataSource>(), Dispatchers.Unconfined)

    @Test
    fun `every Dukascopy chart timeframe refreshes on a five second healthy cadence`() {
        Timeframe.entries.forEach { timeframe ->
            assertEquals("Unexpected Dukascopy poll interval for $timeframe", 5_000L, socket.pollIntervalMs(timeframe))
        }
    }

    @Test
    fun `failure backoff grows exponentially and stays bounded`() {
        assertEquals(5_000L, socket.failureBackoffMs(1))
        assertEquals(10_000L, socket.failureBackoffMs(2))
        assertEquals(120_000L, socket.failureBackoffMs(99))
    }
}
