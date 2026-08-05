package com.foxtrader.app.domain.usecase.patterns

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandlePatternType
import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CandlePatternDetector.
 * Validates detection of single, double, and triple candlestick patterns.
 */
class CandlePatternDetectorTest {

    private lateinit var detector: CandlePatternDetector

    @Before
    fun setup() {
        detector = CandlePatternDetector()
    }

    private fun candle(
        open: Double, high: Double, low: Double, close: Double,
        volume: Double = 100.0, timestamp: Long = 0L,
    ) = Candle(timestamp, open, high, low, close, volume)

    /**
     * Builds a downtrend followed by a hammer candle (long lower wick, small body at top).
     */
    private fun buildHammerSequence(): List<Candle> {
        val candles = mutableListOf<Candle>()
        // 6 bars of downtrend (close < close[i-5])
        candles.add(candle(110.0, 111.0, 109.0, 109.5, timestamp = 0L))
        candles.add(candle(109.5, 110.0, 108.0, 108.5, timestamp = 60000L))
        candles.add(candle(108.5, 109.0, 107.0, 107.5, timestamp = 120000L))
        candles.add(candle(107.5, 108.0, 106.0, 106.5, timestamp = 180000L))
        candles.add(candle(106.5, 107.0, 105.0, 105.5, timestamp = 240000L))
        candles.add(candle(105.5, 106.0, 104.0, 104.5, timestamp = 300000L))
        // Hammer: open=104.5, high=105.0, low=100.0, close=104.8
        // range=5.0, body=0.3, lowerWick=4.5, upperWick=0.2
        // bodyRatio=0.06, lowerRatio=0.9, upperRatio=0.04
        candles.add(candle(104.5, 105.0, 100.0, 104.8, timestamp = 360000L))
        return candles
    }

    /**
     * Builds a bullish engulfing pattern: bearish candle followed by a larger bullish candle.
     */
    private fun buildBullishEngulfingSequence(): List<Candle> {
        val candles = mutableListOf<Candle>()
        // Prefix for context
        for (i in 0 until 5) {
            candles.add(candle(100.0, 101.0, 99.0, 100.0, timestamp = i * 60000L))
        }
        // Bearish candle: open=102, close=100, body=2
        candles.add(candle(102.0, 102.5, 99.5, 100.0, timestamp = 5 * 60000L))
        // Bullish engulfing: open < prev close, close > prev open, body > prev body * 1.2
        // prev body = 2.0, need body > 2.4
        candles.add(candle(99.5, 103.5, 99.0, 103.0, timestamp = 6 * 60000L))
        return candles
    }

    /**
     * Builds three white soldiers: three consecutive large bullish candles with higher closes.
     */
    private fun buildThreeWhiteSoldiersSequence(): List<Candle> {
        val candles = mutableListOf<Candle>()
        // Prefix
        for (i in 0 until 5) {
            candles.add(candle(100.0, 101.0, 99.0, 100.0, timestamp = i * 60000L))
        }
        // Soldier 1: strong bullish (bodyRatio > 0.5) open=100, high=103, low=99.5, close=102.5
        // range=3.5, body=2.5, bodyRatio=0.71
        candles.add(candle(100.0, 103.0, 99.5, 102.5, timestamp = 5 * 60000L))
        // Soldier 2: close > prev close, strong bullish
        candles.add(candle(102.5, 106.0, 102.0, 105.5, timestamp = 6 * 60000L))
        // Soldier 3: close > prev close, strong bullish
        candles.add(candle(105.5, 109.0, 105.0, 108.5, timestamp = 7 * 60000L))
        return candles
    }

    @Test
    fun `detects hammer in downtrend`() {
        val candles = buildHammerSequence()
        val patterns = detector(candles, lookback = 10)
        val hammers = patterns.filter { it.type == CandlePatternType.HAMMER }
        assertTrue("Should detect at least one hammer pattern", hammers.isNotEmpty())
        assertTrue("Hammer should be bullish", hammers.all { it.direction == Direction.BULLISH })
    }

    @Test
    fun `detects bullish engulfing`() {
        val candles = buildBullishEngulfingSequence()
        val patterns = detector(candles, lookback = 10)
        val engulfing = patterns.filter { it.type == CandlePatternType.ENGULFING_BULLISH }
        assertTrue("Should detect bullish engulfing", engulfing.isNotEmpty())
        assertTrue("Engulfing should be bullish direction", engulfing.all { it.direction == Direction.BULLISH })
    }

    @Test
    fun `detects three white soldiers`() {
        val candles = buildThreeWhiteSoldiersSequence()
        val patterns = detector(candles, lookback = 10)
        val soldiers = patterns.filter { it.type == CandlePatternType.THREE_WHITE_SOLDIERS }
        assertTrue("Should detect three white soldiers", soldiers.isNotEmpty())
        assertTrue("Three white soldiers is bullish continuation",
            soldiers.all { it.direction == Direction.BULLISH })
    }

    @Test
    fun `returns empty for insufficient candles`() {
        val candles = listOf(
            candle(100.0, 101.0, 99.0, 100.5, timestamp = 0L),
        )
        val patterns = detector(candles, lookback = 50)
        assertTrue("Single candle cannot form patterns (start >= size)", patterns.isEmpty())
    }
}
