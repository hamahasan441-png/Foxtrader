package com.foxtrader.app.data.market.model

import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Bucket-alignment correctness. Every tick must map to exactly one stable bucket;
 * if these boundaries are wrong, candles repaint or drift, so this is the
 * foundation the whole engine is tested against.
 */
class MarketTimeframeTest {

    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    @Test
    fun `exposes all twelve required timeframes`() {
        assertEquals(12, MarketTimeframe.ALL.size)
        val labels = MarketTimeframe.ALL.map { it.label }
        assertEquals(
            listOf("1m", "2m", "3m", "5m", "10m", "15m", "30m", "1h", "4h", "1d", "1w", "1M"),
            labels,
        )
    }

    @Test
    fun `intraday buckets floor-align to UTC`() {
        // M1
        assertEquals(0L, MarketTimeframe.M1.bucketStart(0L))
        assertEquals(0L, MarketTimeframe.M1.bucketStart(59_999L))
        assertEquals(minute, MarketTimeframe.M1.bucketStart(minute))

        // M5 straddles the 5-minute mark.
        assertEquals(0L, MarketTimeframe.M5.bucketStart(4 * minute))
        assertEquals(5 * minute, MarketTimeframe.M5.bucketStart(5 * minute))

        // M2 and M3 (engine-only) divide the hour evenly.
        assertEquals(2 * minute, MarketTimeframe.M2.bucketStart(2 * minute + 30_000L))
        assertEquals(3 * minute, MarketTimeframe.M3.bucketStart(3 * minute))
        assertEquals(0L, MarketTimeframe.M3.bucketStart(179_999L))

        // H1 / H4.
        assertEquals(0L, MarketTimeframe.H1.bucketStart(hour - 1))
        assertEquals(hour, MarketTimeframe.H1.bucketStart(hour))
        assertEquals(0L, MarketTimeframe.H4.bucketStart(3 * hour + 59 * minute))
        assertEquals(4 * hour, MarketTimeframe.H4.bucketStart(4 * hour))

        // D1 aligns to UTC midnight.
        assertEquals(0L, MarketTimeframe.D1.bucketStart(day - 1))
        assertEquals(day, MarketTimeframe.D1.bucketStart(day))
    }

    @Test
    fun `weekly buckets are Monday-aligned UTC`() {
        // 2024-01-01 is a Monday.
        val monday = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val weekMs = 7 * day

        assertEquals(monday, MarketTimeframe.W1.bucketStart(monday))
        // Any day within that week (Mon..Sun) resolves to the same Monday start.
        assertEquals(monday, MarketTimeframe.W1.bucketStart(monday + 6 * day))
        // The next Monday advances by exactly one week.
        assertEquals(monday + weekMs, MarketTimeframe.W1.bucketStart(monday + weekMs))
        // The start is genuinely a Monday.
        val startDow = java.time.Instant.ofEpochMilli(MarketTimeframe.W1.bucketStart(monday + 3 * day))
            .atZone(ZoneOffset.UTC).dayOfWeek
        assertEquals(DayOfWeek.MONDAY, startDow)
        assertEquals(monday + weekMs, MarketTimeframe.W1.bucketEnd(monday))
    }

    @Test
    fun `monthly buckets start on the first day of the UTC month`() {
        val jan1 = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val feb1 = LocalDate.of(2024, 2, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val mar1 = LocalDate.of(2024, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val midJan = LocalDate.of(2024, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val midFeb = LocalDate.of(2024, 2, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertEquals(jan1, MarketTimeframe.MN.bucketStart(jan1))
        assertEquals(jan1, MarketTimeframe.MN.bucketStart(midJan))
        assertEquals(feb1, MarketTimeframe.MN.bucketStart(midFeb))
        // Bucket end of January is the start of February (handles month length).
        assertEquals(feb1, MarketTimeframe.MN.bucketEnd(jan1))
        assertEquals(mar1, MarketTimeframe.MN.bucketEnd(feb1))
    }

    @Test
    fun `bucketEnd advances by the fixed duration for intraday buckets`() {
        assertEquals(minute, MarketTimeframe.M1.bucketEnd(0L))
        assertEquals(5 * minute, MarketTimeframe.M5.bucketEnd(0L))
        assertEquals(day, MarketTimeframe.D1.bucketEnd(0L))
    }

    @Test
    fun `bridges to the chart enum losslessly for overlapping buckets`() {
        assertEquals(Timeframe.M5, MarketTimeframe.M5.toChartTimeframe())
        assertEquals(Timeframe.H4, MarketTimeframe.H4.toChartTimeframe())
        assertEquals(Timeframe.MN, MarketTimeframe.MN.toChartTimeframe())
        // Engine-only timeframes have no chart representation.
        assertNull(MarketTimeframe.M2.toChartTimeframe())
        assertNull(MarketTimeframe.M3.toChartTimeframe())
        assertNull(MarketTimeframe.M10.toChartTimeframe())
    }

    @Test
    fun `chart round-trip preserves every chart timeframe`() {
        Timeframe.entries.forEach { chartTf ->
            assertEquals(chartTf, MarketTimeframe.fromChart(chartTf).toChartTimeframe())
        }
    }

    @Test
    fun `resolves labels`() {
        assertEquals(MarketTimeframe.M5, MarketTimeframe.fromLabel("5m"))
        assertEquals(MarketTimeframe.M2, MarketTimeframe.fromLabel("2m"))
        assertEquals(MarketTimeframe.MN, MarketTimeframe.fromLabel("1M"))
        assertNull(MarketTimeframe.fromLabel("nonsense"))
    }

    @Test
    fun `alignment is deterministic for repeated lookups`() {
        val ts = 1_704_123_456_789L
        MarketTimeframe.ALL.forEach { tf ->
            assertEquals(tf.name, tf.bucketStart(ts), tf.bucketStart(ts))
            assertTrue(tf.name, tf.bucketStart(ts) <= ts)
            assertTrue(tf.name, tf.bucketEnd(tf.bucketStart(ts)) > ts)
        }
    }
}
