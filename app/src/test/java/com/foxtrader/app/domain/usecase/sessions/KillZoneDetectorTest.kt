package com.foxtrader.app.domain.usecase.sessions

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.KillZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for [KillZoneDetector].
 * Mirrors the SessionDetector test pattern: hourly UTC candles whose index equals
 * the hour, so kill-zone hour windows map directly to bar index ranges.
 */
class KillZoneDetectorTest {

    private lateinit var detector: KillZoneDetector

    @Before
    fun setup() {
        detector = KillZoneDetector()
    }

    private fun candle(
        open: Double, high: Double, low: Double, close: Double,
        volume: Double = 100.0, timestamp: Long = 0L,
    ) = Candle(timestamp, open, high, low, close, volume)

    /** Build hourly candles for a full UTC day (24 bars, index == hour 0..23). */
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
    fun `detect returns empty for empty input`() {
        assertTrue("Empty candles should return empty ranges", detector.detect(emptyList()).isEmpty())
    }

    @Test
    fun `detect identifies London Open window hours 7 to 10 UTC`() {
        val candles = buildFullDayHourlyCandles()
        val ranges = detector.detect(candles, listOf(KillZone.LONDON_OPEN))
        assertTrue("Should detect London Open", ranges.isNotEmpty())
        val london = ranges.first()
        assertEquals(KillZone.LONDON_OPEN, london.zone)
        // LONDON_OPEN: [7, 10) so bars at hours 7,8,9 are in the window.
        assertEquals(7, london.startIndex)
        assertEquals(9, london.endIndex)
    }

    @Test
    fun `detect identifies New York Open window hours 12 to 15 UTC`() {
        val candles = buildFullDayHourlyCandles()
        val ranges = detector.detect(candles, listOf(KillZone.NEW_YORK_OPEN))
        assertTrue("Should detect New York Open", ranges.isNotEmpty())
        val ny = ranges.first()
        assertEquals(KillZone.NEW_YORK_OPEN, ny.zone)
        // NEW_YORK_OPEN: [12, 15) so bars at hours 12,13,14 are in the window.
        assertEquals(12, ny.startIndex)
        assertEquals(14, ny.endIndex)
    }

    @Test
    fun `detect computes correct high and low for the Asian Range`() {
        val candles = buildFullDayHourlyCandles()
        val ranges = detector.detect(candles, listOf(KillZone.ASIAN_RANGE))
        assertTrue("Should detect Asian Range", ranges.isNotEmpty())
        val asian = ranges.first()
        // ASIAN_RANGE: [0, 5) so bars at hours 0..4 are in the window.
        assertEquals(0, asian.startIndex)
        assertEquals(4, asian.endIndex)
        val windowCandles = candles.subList(asian.startIndex, asian.endIndex + 1)
        assertEquals("High should match window max", windowCandles.maxOf { it.high }, asian.high, 0.001)
        assertEquals("Low should match window min", windowCandles.minOf { it.low }, asian.low, 0.001)
    }

    @Test
    fun `isInKillZone returns the active zone or null`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2024, Calendar.JANUARY, 15, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Hour 8 UTC -> London Open [7,10)
        cal.set(Calendar.HOUR_OF_DAY, 8)
        assertEquals(KillZone.LONDON_OPEN, detector.isInKillZone(cal.timeInMillis))

        // Hour 16 UTC -> London Close [15,17)
        cal.set(Calendar.HOUR_OF_DAY, 16)
        assertEquals(KillZone.LONDON_CLOSE, detector.isInKillZone(cal.timeInMillis))

        // Hour 20 UTC -> no kill zone active
        cal.set(Calendar.HOUR_OF_DAY, 20)
        assertNull(detector.isInKillZone(cal.timeInMillis))
    }
}
