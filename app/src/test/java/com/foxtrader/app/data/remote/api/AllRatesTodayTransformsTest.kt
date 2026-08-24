package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.AssetClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllRatesTodayTransformsTest {

    @Test
    fun `single rate snapshots are candleized with previous close continuity`() {
        val candles = buildAllRatesTodaySnapshotCandles(
            points = listOf(
                0L to 1.1000,
                60_000L to 1.1015,
                120_000L to 1.1005,
            ),
            timeframeMinutes = 1,
        )

        assertEquals(3, candles.size)
        assertEquals(1.1000, candles[0].open, 1e-9)
        assertEquals(1.1000, candles[0].close, 1e-9)

        assertEquals(1.1000, candles[1].open, 1e-9)
        assertEquals(1.1015, candles[1].high, 1e-9)
        assertEquals(1.1000, candles[1].low, 1e-9)
        assertEquals(1.1015, candles[1].close, 1e-9)

        assertEquals(1.1015, candles[2].open, 1e-9)
        assertEquals(1.1015, candles[2].high, 1e-9)
        assertEquals(1.1005, candles[2].low, 1e-9)
        assertEquals(1.1005, candles[2].close, 1e-9)
    }

    @Test
    fun `multiple snapshots preserve observed intrabar high and low`() {
        val candles = buildAllRatesTodaySnapshotCandles(
            points = listOf(
                0L to 1.1000,
                60_000L to 1.1030,
                120_000L to 1.0980,
                240_000L to 1.1010,
                300_000L to 1.1020,
            ),
            timeframeMinutes = 5,
        )

        assertEquals(2, candles.size)
        assertEquals(1.1000, candles[0].open, 1e-9)
        assertEquals(1.1030, candles[0].high, 1e-9)
        assertEquals(1.0980, candles[0].low, 1e-9)
        assertEquals(1.1010, candles[0].close, 1e-9)
        assertEquals(1.1010, candles[1].open, 1e-9)
        assertEquals(1.1020, candles[1].close, 1e-9)
    }

    @Test
    fun `curated directory contains majors and available metals only`() {
        val currencies = mapOf(
            "USD" to "US Dollar",
            "EUR" to "Euro",
            "GBP" to "British Pound",
            "JPY" to "Japanese Yen",
            "CHF" to "Swiss Franc",
            "CAD" to "Canadian Dollar",
            "AUD" to "Australian Dollar",
            "NZD" to "New Zealand Dollar",
            "XAU" to "Gold",
            "XAG" to "Silver",
            "TRY" to "Turkish Lira",
        )

        val symbols = buildAllRatesTodayCuratedSymbols(currencies)
        val names = symbols.map { it.providerSymbol }

        assertEquals(
            listOf(
                "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "USDCAD", "AUDUSD", "NZDUSD",
                "XAUUSD", "XAGUSD",
            ),
            names,
        )
        assertFalse("EURGBP" in names)
        assertFalse("USDTRY" in names)
        assertEquals(AssetClass.METALS, symbols.first { it.providerSymbol == "XAUUSD" }.assetClass)
        assertTrue(symbols.filter { it.providerSymbol.endsWith("USD") }.isNotEmpty())
    }

    @Test
    fun `metal pairs disappear when provider does not advertise metal codes`() {
        val currencies = mapOf(
            "USD" to "US Dollar",
            "EUR" to "Euro",
            "GBP" to "British Pound",
            "JPY" to "Japanese Yen",
            "CHF" to "Swiss Franc",
            "CAD" to "Canadian Dollar",
            "AUD" to "Australian Dollar",
            "NZD" to "New Zealand Dollar",
        )

        val symbols = buildAllRatesTodayCuratedSymbols(currencies)

        assertEquals(7, symbols.size)
        assertFalse(symbols.any { it.assetClass == AssetClass.METALS })
    }
}
