package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ConnectionState
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.TickUpdate
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFeedGuardTest {

    @Test
    fun `forming updates stay valid while duplicates and late candles are rejected`() {
        val gate = LiveTickGate()

        assertTrue(gate.accept(tick(timestamp = 1_000L, close = 101.0)))
        assertFalse(gate.accept(tick(timestamp = 1_000L, close = 101.0)))
        assertTrue(gate.accept(tick(timestamp = 1_000L, close = 102.0)))
        assertTrue(gate.accept(tick(timestamp = 2_000L, close = 103.0)))
        assertFalse(gate.accept(tick(timestamp = 1_000L, close = 104.0)))
    }

    @Test
    fun `reset permits an older timestamp for a new chart context`() {
        val gate = LiveTickGate()
        assertTrue(gate.accept(tick(timestamp = 2_000L)))

        gate.reset()

        assertTrue(gate.accept(tick(timestamp = 1_000L)))
    }

    @Test
    fun `provider streams keep independent ordering state`() {
        val gate = LiveTickGate()
        assertTrue(gate.accept(tick(timestamp = 2_000L, provider = DataProvider.BINANCE)))
        assertTrue(gate.accept(tick(timestamp = 1_000L, provider = DataProvider.BYBIT)))
    }

    @Test
    fun `recovery fires once after each connected interruption cycle`() {
        val gate = LiveRecoveryGate()

        assertFalse(gate.onState(ConnectionState.CONNECTING))
        assertFalse(gate.onState(ConnectionState.CONNECTED))
        assertFalse(gate.onState(ConnectionState.RECONNECTING))
        assertTrue(gate.onState(ConnectionState.CONNECTED))
        assertFalse(gate.onState(ConnectionState.CONNECTED))
        assertFalse(gate.onState(ConnectionState.STALE))
        assertFalse(gate.onState(ConnectionState.RECONNECTING))
        assertTrue(gate.onState(ConnectionState.CONNECTED))
    }

    @Test
    fun `reset makes the next connection an initial connection`() {
        val gate = LiveRecoveryGate()
        gate.onState(ConnectionState.CONNECTED)
        gate.onState(ConnectionState.DISCONNECTED)
        gate.reset()

        assertFalse(gate.onState(ConnectionState.CONNECTED))
    }

    private fun tick(
        timestamp: Long,
        close: Double = 100.0,
        provider: DataProvider = DataProvider.BINANCE,
    ) = TickUpdate(
        symbol = "BTCUSDT",
        timeframe = Timeframe.M1,
        candle = Candle(
            timestamp = timestamp,
            open = 100.0,
            high = maxOf(100.0, close),
            low = minOf(100.0, close),
            close = close,
            volume = 1.0,
        ),
        isBarClose = false,
        provider = provider,
    )
}
