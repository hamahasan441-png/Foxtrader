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
    fun `successful cycle subtracts fetch duration from five second cadence`() {
        assertEquals(3_750L, socket.nextDelayMs(Timeframe.M1, failedThisCycle = false, failures = 0, elapsedMs = 1_250L))
        assertEquals(0L, socket.nextDelayMs(Timeframe.H4, failedThisCycle = false, failures = 0, elapsedMs = 5_200L))
    }

    @Test
    fun `failed cycle keeps exponential backoff while accounting for request time`() {
        assertEquals(9_000L, socket.nextDelayMs(Timeframe.M15, failedThisCycle = true, failures = 2, elapsedMs = 1_000L))
        assertEquals(119_000L, socket.nextDelayMs(Timeframe.D1, failedThisCycle = true, failures = 99, elapsedMs = 1_000L))
    }

    @Test
    fun `failure backoff grows exponentially and stays bounded`() {
        assertEquals(5_000L, socket.failureBackoffMs(1))
        assertEquals(10_000L, socket.failureBackoffMs(2))
        assertEquals(120_000L, socket.failureBackoffMs(99))
    }
}
