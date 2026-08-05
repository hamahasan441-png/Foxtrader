package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeikinAshiTransformerTest {

    private val transformer = HeikinAshiTransformer()

    @Test
    fun `empty input returns empty output`() {
        val result = transformer.transform(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single candle produces correct HA values`() {
        val candle = Candle(
            timestamp = 1700000000000L,
            open = 100.0,
            high = 110.0,
            low = 90.0,
            close = 105.0,
            volume = 500.0,
        )

        val result = transformer.transform(listOf(candle))

        assertEquals(1, result.size)
        val ha = result[0]
        // HA Open for first bar = candle open
        assertEquals(100.0, ha.open, 0.0001)
        // HA Close = (O + H + L + C) / 4 = (100 + 110 + 90 + 105) / 4 = 101.25
        assertEquals(101.25, ha.close, 0.0001)
        // HA High = max(high, haOpen, haClose) = max(110, 100, 101.25) = 110
        assertEquals(110.0, ha.high, 0.0001)
        // HA Low = min(low, haOpen, haClose) = min(90, 100, 101.25) = 90
        assertEquals(90.0, ha.low, 0.0001)
        // Volume preserved
        assertEquals(500.0, ha.volume, 0.0001)
    }

    @Test
    fun `multiple candles chain HA open from previous bar`() {
        val candles = listOf(
            Candle(
                timestamp = 1700000000000L,
                open = 100.0,
                high = 110.0,
                low = 90.0,
                close = 105.0,
                volume = 500.0,
            ),
            Candle(
                timestamp = 1700000060000L,
                open = 106.0,
                high = 115.0,
                low = 102.0,
                close = 112.0,
                volume = 600.0,
            ),
        )

        val result = transformer.transform(candles)

        assertEquals(2, result.size)

        // First bar HA values
        val ha0 = result[0]
        val expectedHaClose0 = (100.0 + 110.0 + 90.0 + 105.0) / 4.0 // 101.25
        assertEquals(100.0, ha0.open, 0.0001)
        assertEquals(expectedHaClose0, ha0.close, 0.0001)

        // Second bar HA values
        val ha1 = result[1]
        // HA Open = (prevHaOpen + prevHaClose) / 2 = (100.0 + 101.25) / 2 = 100.625
        val expectedHaOpen1 = (100.0 + expectedHaClose0) / 2.0
        assertEquals(100.625, expectedHaOpen1, 0.0001)
        assertEquals(expectedHaOpen1, ha1.open, 0.0001)

        // HA Close = (O + H + L + C) / 4 = (106 + 115 + 102 + 112) / 4 = 108.75
        val expectedHaClose1 = (106.0 + 115.0 + 102.0 + 112.0) / 4.0
        assertEquals(108.75, expectedHaClose1, 0.0001)
        assertEquals(expectedHaClose1, ha1.close, 0.0001)

        // HA High = max(high, haOpen, haClose) = max(115, 100.625, 108.75) = 115
        assertEquals(115.0, ha1.high, 0.0001)
        // HA Low = min(low, haOpen, haClose) = min(102, 100.625, 108.75) = 100.625
        assertEquals(expectedHaOpen1, ha1.low, 0.0001)
    }

    @Test
    fun `timestamp is preserved for each bar`() {
        val candles = listOf(
            Candle(
                timestamp = 1700000000000L,
                open = 50.0,
                high = 55.0,
                low = 48.0,
                close = 53.0,
                volume = 200.0,
            ),
            Candle(
                timestamp = 1700000060000L,
                open = 54.0,
                high = 58.0,
                low = 52.0,
                close = 57.0,
                volume = 300.0,
            ),
            Candle(
                timestamp = 1700000120000L,
                open = 57.0,
                high = 60.0,
                low = 55.0,
                close = 59.0,
                volume = 150.0,
            ),
        )

        val result = transformer.transform(candles)

        assertEquals(3, result.size)
        assertEquals(1700000000000L, result[0].timestamp)
        assertEquals(1700000060000L, result[1].timestamp)
        assertEquals(1700000120000L, result[2].timestamp)
    }
}
