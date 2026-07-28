package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChartAiCoordinatorTest {

    private fun candles(count: Int, startTimestamp: Long = 1_000L): List<Candle> =
        (0 until count).map { i ->
            Candle(
                timestamp = startTimestamp + i * 60_000L,
                open = 100.0 + i,
                high = 101.0 + i,
                low = 99.0 + i,
                close = 100.5 + i,
                volume = 1000.0,
            )
        }

    @Test
    fun `fingerprint is deterministic for same candles`() {
        val data = candles(100)
        val hash1 = ChartAiCoordinator.computeFingerprint(data)
        val hash2 = ChartAiCoordinator.computeFingerprint(data)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `fingerprint changes when last bar close changes`() {
        val data = candles(100).toMutableList()
        val hash1 = ChartAiCoordinator.computeFingerprint(data)

        val lastBar = data.last()
        data[data.lastIndex] = lastBar.copy(close = lastBar.close + 0.001)
        val hash2 = ChartAiCoordinator.computeFingerprint(data)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `fingerprint changes when last bar volume changes`() {
        val data = candles(100).toMutableList()
        val hash1 = ChartAiCoordinator.computeFingerprint(data)

        val lastBar = data.last()
        data[data.lastIndex] = lastBar.copy(volume = lastBar.volume + 500.0)
        val hash2 = ChartAiCoordinator.computeFingerprint(data)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `fingerprint changes when size changes`() {
        val data100 = candles(100)
        val data101 = candles(101)
        val hash1 = ChartAiCoordinator.computeFingerprint(data100)
        val hash2 = ChartAiCoordinator.computeFingerprint(data101)
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `fingerprint changes when mid-point candle changes`() {
        val data = candles(100).toMutableList()
        val hash1 = ChartAiCoordinator.computeFingerprint(data)

        val midBar = data[50]
        data[50] = midBar.copy(high = midBar.high + 5.0)
        val hash2 = ChartAiCoordinator.computeFingerprint(data)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `fingerprint changes when first candle timestamp changes`() {
        val data = candles(100).toMutableList()
        val hash1 = ChartAiCoordinator.computeFingerprint(data)

        val firstBar = data[0]
        data[0] = firstBar.copy(timestamp = firstBar.timestamp + 1)
        val hash2 = ChartAiCoordinator.computeFingerprint(data)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `fingerprint is stable when high and low remain unchanged`() {
        val data = candles(100)
        val hash1 = ChartAiCoordinator.computeFingerprint(data)
        // Same list, same content
        val hash2 = ChartAiCoordinator.computeFingerprint(data.toList())
        assertEquals(hash1, hash2)
    }
}
