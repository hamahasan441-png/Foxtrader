package com.foxtrader.app.data.remote.dukascopy

import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.tick.TickAggregator
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [DukascopyDataSource].
 */
class DukascopyDataSourceTest {

    private lateinit var dataSource: DukascopyDataSource

    @Before
    fun setup() {
        dataSource = DukascopyDataSource(
            httpClient = OkHttpClient(),
            tickDecoder = DukascopyTickDecoder(),
            lzmaDecompressor = LzmaDecompressor(),
            tickAggregator = TickAggregator(),
            candleDecoder = DukascopyCandleDecoder(),
            bi5CandleDecoder = DukascopyBi5CandleDecoder(),
            instrumentCatalog = DukascopyInstrumentCatalog(),
        )
    }

    @Test
    fun `point value for standard FX pairs is 100000`() {
        assertEquals(100_000.0, dataSource.getPointValue("EURUSD"), 1e-6)
        assertEquals(100_000.0, dataSource.getPointValue("GBPUSD"), 1e-6)
        assertEquals(100_000.0, dataSource.getPointValue("AUDUSD"), 1e-6)
        assertEquals(100_000.0, dataSource.getPointValue("USDCAD"), 1e-6)
    }

    @Test
    fun `point value for JPY pairs is 1000`() {
        assertEquals(1_000.0, dataSource.getPointValue("USDJPY"), 1e-6)
        assertEquals(1_000.0, dataSource.getPointValue("EURJPY"), 1e-6)
        assertEquals(1_000.0, dataSource.getPointValue("GBPJPY"), 1e-6)
    }

    @Test
    fun `point value for precious metals is 1000`() {
        assertEquals(1_000.0, dataSource.getPointValue("XAUUSD"), 1e-6)
        assertEquals(1_000.0, dataSource.getPointValue("XAGUSD"), 1e-6)
    }

    @Test
    fun `normalizeSymbol strips slashes and dashes`() {
        assertEquals("EURUSD", dataSource.normalizeSymbol("EUR/USD"))
        assertEquals("BTCUSD", dataSource.normalizeSymbol("BTC-USD"))
        assertEquals("GBPUSD", dataSource.normalizeSymbol("gbp_usd"))
    }

    @Test
    fun `index aliases resolve to exact Dukascopy archive symbols`() {
        assertEquals("USA30IDXUSD", dataSource.normalizeSymbol("US30"))
        assertEquals("USATECHIDXUSD", dataSource.normalizeSymbol("NAS100"))
        assertEquals("USA500IDXUSD", dataSource.normalizeSymbol("SPX500"))
        assertEquals("DEUIDXEUR", dataSource.normalizeSymbol("GER40"))
    }

    @Test
    fun `weekly and monthly bars open on the calendar, not the unix epoch`() {
        val friday = LocalDate.of(2024, 11, 15).atTime(15, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val monday = LocalDate.of(2024, 11, 11).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val november = LocalDate.of(2024, 11, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(monday, Timeframe.W1.barOpenMillis(friday))
        assertEquals(november, Timeframe.MN.barOpenMillis(friday))
        // Epoch division would start this week on Thursday 14 Nov 2024.
        val thursday = LocalDate.of(2024, 11, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertTrue(Timeframe.W1.barOpenMillis(friday) != thursday)
    }
}
