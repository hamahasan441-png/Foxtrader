package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleRenkoBuilderTest {

    private val builder = CandleRenkoBuilder()

    @Test
    fun `empty candles returns empty`() {
        val result = builder.build(emptyList(), 10.0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `brickSize zero returns empty`() {
        val candles = listOf(
            Candle(
                timestamp = 1700000000000L,
                open = 100.0,
                high = 120.0,
                low = 90.0,
                close = 115.0,
                volume = 500.0,
            ),
        )
        val result = builder.build(candles, 0.0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `negative brickSize returns empty`() {
        val candles = listOf(
            Candle(
                timestamp = 1700000000000L,
                open = 100.0,
                high = 120.0,
                low = 90.0,
                close = 115.0,
                volume = 500.0,
            ),
        )
        val result = builder.build(candles, -5.0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single candle with insufficient move returns empty`() {
        // First candle close = 100, so basePrice = 100. brickSize = 20.
        // The candle close itself (100) doesn't move away from basePrice.
        val candles = listOf(
            Candle(
                timestamp = 1700000000000L,
                open = 98.0,
                high = 105.0,
                low = 95.0,
                close = 100.0,
                volume = 300.0,
            ),
        )
        val result = builder.build(candles, 20.0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `bullish bricks from upward movement`() {
        // basePrice starts at first candle close = 100.0
        // brickSize = 10.0
        // Second candle close = 120.0, which is >= 100 + 10 = 110 (one brick)
        // After first brick: basePrice = 110, close(120) >= 110 + 10 = 120 (second brick)
        // After second brick: basePrice = 120, close(120) < 120 + 10 -> stop
        val candles = listOf(
            Candle(
                timestamp = 1700000000000L,
                open = 99.0,
                high = 101.0,
                low = 98.0,
                close = 100.0,
                volume = 100.0,
            ),
            Candle(
                timestamp = 1700000060000L,
                open = 100.0,
                high = 122.0,
                low = 99.0,
                close = 120.0,
                volume = 400.0,
            ),
        )

        val result = builder.build(candles, 10.0)

        assertEquals(2, result.size)

        // First brick: open=100, close=110 (bullish)
        val brick1 = result[0]
        assertEquals(100.0, brick1.open, 0.0001)
        assertEquals(110.0, brick1.close, 0.0001)
        assertEquals(110.0, brick1.high, 0.0001)
        assertEquals(100.0, brick1.low, 0.0001)
        assertEquals(1700000060000L, brick1.timestamp)
        // Volume: accumulated from both candles (100 + 400 = 500)
        assertEquals(500.0, brick1.volume, 0.0001)

        // Second brick: open=110, close=120 (bullish)
        val brick2 = result[1]
        assertEquals(110.0, brick2.open, 0.0001)
        assertEquals(120.0, brick2.close, 0.0001)
        assertEquals(120.0, brick2.high, 0.0001)
        assertEquals(110.0, brick2.low, 0.0001)
        assertEquals(1700000060000L, brick2.timestamp)
        // Volume resets after first brick, second brick from same candle remainder
        assertEquals(0.0, brick2.volume, 0.0001)
    }

    @Test
    fun `bearish bricks from downward movement`() {
        // basePrice starts at first candle close = 100.0
        // brickSize = 10.0
        // Second candle close = 80.0
        // 80 <= 100 - 10 = 90 -> bearish brick (open=100, close=90)
        // After: basePrice = 90, 80 <= 90 - 10 = 80 -> bearish brick (open=90, close=80)
        // After: basePrice = 80, 80 > 80 - 10 = 70 -> stop
        val candles = listOf(
            Candle(
                timestamp = 1700000000000L,
                open = 101.0,
                high = 102.0,
                low = 99.0,
                close = 100.0,
                volume = 200.0,
            ),
            Candle(
                timestamp = 1700000060000L,
                open = 99.0,
                high = 100.0,
                low = 78.0,
                close = 80.0,
                volume = 800.0,
            ),
        )

        val result = builder.build(candles, 10.0)

        assertEquals(2, result.size)

        // First brick: bearish, open=100, close=90
        val brick1 = result[0]
        assertEquals(100.0, brick1.open, 0.0001)
        assertEquals(90.0, brick1.close, 0.0001)
        assertEquals(100.0, brick1.high, 0.0001)
        assertEquals(90.0, brick1.low, 0.0001)
        assertEquals(1700000060000L, brick1.timestamp)

        // Second brick: bearish, open=90, close=80
        val brick2 = result[1]
        assertEquals(90.0, brick2.open, 0.0001)
        assertEquals(80.0, brick2.close, 0.0001)
        assertEquals(90.0, brick2.high, 0.0001)
        assertEquals(80.0, brick2.low, 0.0001)
        assertEquals(1700000060000L, brick2.timestamp)
    }

    @Test
    fun `volume accumulates across candles between bricks`() {
        // basePrice starts at first candle close = 100.0
        // brickSize = 20.0
        // Candle 1: close=100, move insufficient (basePrice=100, need >=120 or <=80)
        // Candle 2: close=108, still insufficient
        // Candle 3: close=125, triggers brick (100 -> 120)
        val candles = listOf(
            Candle(
                timestamp = 1700000000000L,
                open = 99.0,
                high = 101.0,
                low = 98.0,
                close = 100.0,
                volume = 100.0,
            ),
            Candle(
                timestamp = 1700000060000L,
                open = 101.0,
                high = 110.0,
                low = 100.0,
                close = 108.0,
                volume = 200.0,
            ),
            Candle(
                timestamp = 1700000120000L,
                open = 109.0,
                high = 126.0,
                low = 107.0,
                close = 125.0,
                volume = 300.0,
            ),
        )

        val result = builder.build(candles, 20.0)

        assertEquals(1, result.size)
        val brick = result[0]
        assertEquals(100.0, brick.open, 0.0001)
        assertEquals(120.0, brick.close, 0.0001)
        // Volume accumulated from all three candles: 100 + 200 + 300 = 600
        assertEquals(600.0, brick.volume, 0.0001)
        // Timestamp is the triggering candle's timestamp
        assertEquals(1700000120000L, brick.timestamp)
    }
}
