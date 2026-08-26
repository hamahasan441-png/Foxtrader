package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BacktestDateRangeTest {

    private val day = BacktestDateRange.MILLIS_PER_DAY

    @Test
    fun `loaded preset measures everything`() {
        assertNull(BacktestDateRange.resolve(BacktestRangePreset.LOADED, null, null))
    }

    @Test
    fun `day presets resolve relative to now`() {
        val now = BacktestDateRange.startOfDay(LocalDate.of(2024, 6, 15))
        val range = BacktestDateRange.resolve(BacktestRangePreset.THREE_MONTHS, null, null, now)
        assertNotNull(range)
        assertEquals(now, range!!.endMillis)
        assertEquals(now - 90 * day, range.startMillis)
    }

    @Test
    fun `year to date starts at january first`() {
        val now = BacktestDateRange.startOfDay(LocalDate.of(2024, 6, 15))
        val range = BacktestDateRange.resolve(BacktestRangePreset.YEAR_TO_DATE, null, null, now)!!
        assertEquals(BacktestDateRange.startOfDay(LocalDate.of(2024, 1, 1)), range.startMillis)
        assertEquals(now, range.endMillis)
    }

    /**
     * A published result must be reproducible: the same custom range asked for
     * next month has to select exactly the same bars it selects today.
     */
    @Test
    fun `custom range is absolute and independent of now`() {
        val start = BacktestDateRange.startOfDay(LocalDate.of(2023, 1, 1))
        val end = BacktestDateRange.endOfDay(LocalDate.of(2023, 3, 31))
        val early = BacktestDateRange.resolve(BacktestRangePreset.CUSTOM, start, end, now = 1_700_000_000_000L)
        val late = BacktestDateRange.resolve(BacktestRangePreset.CUSTOM, start, end, now = 1_900_000_000_000L)
        assertEquals(early, late)
        assertEquals(start, early!!.startMillis)
        assertEquals(end, early.endMillis)
    }

    @Test
    fun `custom range with a missing bound degrades to all loaded`() {
        val start = BacktestDateRange.startOfDay(LocalDate.of(2023, 1, 1))
        assertNull(BacktestDateRange.resolve(BacktestRangePreset.CUSTOM, start, null))
        assertNull(BacktestDateRange.resolve(BacktestRangePreset.CUSTOM, null, start))
    }

    /** A trader who picks the dates in the wrong order still gets a valid run. */
    @Test
    fun `reversed custom bounds are normalised rather than rejected`() {
        val a = BacktestDateRange.startOfDay(LocalDate.of(2023, 5, 1))
        val b = BacktestDateRange.startOfDay(LocalDate.of(2023, 1, 1))
        val range = BacktestDateRange.resolve(BacktestRangePreset.CUSTOM, a, b)!!
        assertEquals(b, range.startMillis)
        assertEquals(a, range.endMillis)
    }

    /** An end date must include the bars traded on that day. */
    @Test
    fun `end of day covers the final bar of the selected date`() {
        val date = LocalDate.of(2024, 3, 31)
        val lastBar = BacktestDateRange.startOfDay(date) + 23 * 3_600_000L
        assertTrue(lastBar <= BacktestDateRange.endOfDay(date))
        assertTrue(BacktestDateRange.endOfDay(date) < BacktestDateRange.startOfDay(date.plusDays(1)))
    }

    @Test
    fun `window maps a range onto bar indices`() {
        val base = BacktestDateRange.startOfDay(LocalDate.of(2024, 1, 1))
        val candles = List(100) { candle(base + it * 3_600_000L) }
        val range = BacktestDateRange(base + 10 * 3_600_000L, base + 19 * 3_600_000L)

        val window = range.toWindow(candles)!!
        assertEquals(10, window.startIndex)
        assertEquals(19, window.endIndex)
        assertEquals(10, window.barCount)
    }

    /** A window that cannot hold an entry and an exit is not a backtest. */
    @Test
    fun `single bar and empty selections produce no window`() {
        val base = BacktestDateRange.startOfDay(LocalDate.of(2024, 1, 1))
        val candles = List(10) { candle(base + it * 3_600_000L) }

        assertNull(BacktestDateRange(base, base).toWindow(candles))
        assertNull(BacktestDateRange(base + 100 * 3_600_000L, base + 200 * 3_600_000L).toWindow(candles))
        assertNull(BacktestDateRange(base, base + 3_600_000L * 2).toWindow(emptyList()))
    }

    private fun candle(timestamp: Long) =
        Candle(timestamp, 100.0, 101.0, 99.0, 100.5, 1_000.0)
}
