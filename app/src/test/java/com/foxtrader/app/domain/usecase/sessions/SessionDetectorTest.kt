package com.foxtrader.app.domain.usecase.sessions

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.TradingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for SessionDetector.
 * Validates correct identification of trading session time ranges.
 */
class SessionDetectorTest {

    private lateinit var detector: SessionDetector

    @Before
    fun setup() {
        detector = SessionDetector()
    }

    private fun candle(
        open: Double, high: Double, low: Double, close: Double,
        volume: Double = 100.0, timestamp: Long = 0L,
    ) = Candle(timestamp, open, high, low, close, volume)

    /**
     * Build hourly candles for a full UTC day (24 bars, each at hour 0..23).
     */
    private fun buildFullDayHourlyCandles(): List<Candle> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2024, Calendar.JANUARY, 15, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)

        return (0 until 24).map { hour ->
            cal.set(Calendar.HOUR_OF_DAY, hour)
            val price = 100.0 + hour * 0.5
            candle(price, price + 1.0, price - 0.5, price + 0.3, timestamp = cal.timeInMillis)
        }
    }

    @Test
    fun `detectSessions returns empty for empty input`() {
        val result = detector.detectSessions(emptyList())
        assertTrue("Empty candles should return empty sessions", result.isEmpty())
    }

    @Test
    fun `detectSessions identifies London session hours 7 to 16 UTC`() {
        val candles = buildFullDayHourlyCandles()
        val sessions = detector.detectSessions(candles, listOf(TradingSession.LONDON))
        assertTrue("Should detect London session", sessions.isNotEmpty())
        val london = sessions.first()
        assertEquals(TradingSession.LONDON, london.session)
        // London: openHourUtc=7, closeHourUtc=16, so bars at hours 7..15 are in session
        assertEquals("London start at hour 7 bar (index 7)", 7, london.startIndex)
        assertEquals("London end at hour 15 bar (index 15)", 15, london.endIndex)
    }

    @Test
    fun `detectSessions computes correct session high and low`() {
        val candles = buildFullDayHourlyCandles()
        val sessions = detector.detectSessions(candles, listOf(TradingSession.TOKYO))
        assertTrue("Should detect Tokyo session", sessions.isNotEmpty())
        val tokyo = sessions.first()
        // Tokyo: openHourUtc=0, closeHourUtc=9, so bars at hours 0..8 are in session
        val sessionCandles = candles.subList(tokyo.startIndex, tokyo.endIndex + 1)
        val expectedHigh = sessionCandles.maxOf { it.high }
        val expectedLow = sessionCandles.minOf { it.low }
        assertEquals("Session high should match", expectedHigh, tokyo.highPrice, 0.001)
        assertEquals("Session low should match", expectedLow, tokyo.lowPrice, 0.001)
    }

    @Test
    fun `detectSessions handles overnight session (Sydney wraps midnight)`() {
        // Sydney: openHourUtc=22, closeHourUtc=7 -- wraps midnight
        val candles = buildFullDayHourlyCandles()
        val sessions = detector.detectSessions(candles, listOf(TradingSession.SYDNEY))
        assertTrue("Should detect Sydney overnight session", sessions.isNotEmpty())
        val sydney = sessions.first()
        assertEquals(TradingSession.SYDNEY, sydney.session)
        // Bars at hours 22,23,0,1,2,3,4,5,6 are in session.
        // Since candles go 0..23, the first in-session bar is index 0 (hour 0)
        assertEquals("Sydney starts at hour 0 (first matching bar)", 0, sydney.startIndex)
    }
}
