package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.AssetClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllRatesTodayDataSourceTest {

    @Test
    fun `single snapshot buckets use previous close as next open`() {
        val candles = buildAllRatesTodaySnapshotCandles(
            points = listOf(
                60_000L to 1.1000,
                120_000L to 1.1020,
                180_000L to 1.1010,
            ),
            timeframeMinutes = 1,
        )

        assertEquals(3, candles.size)
        assertEquals(1.1000, candles[0].open, 1e-9)
        assertEquals(1.1000, candles[0].close, 1e-9)

        assertEquals(1.1000, candles[1].open, 1e-9)
        assertEquals(1.1020, candles[1].close, 1e-9)
        assertEquals(1.1020, candles[1].high, 1e-9)
        assertEquals(1.1000, candles[1].low, 1e-9)

        assertEquals(1.1020, candles[2].open, 1e-9)
        assertEquals(1.1010, candles[2].close, 1e-9)
        assertTrue(candles[1].open != candles[1].close)
        assertTrue(candles[2].open != candles[2].close)
    }

    @Test
    fun `multiple snapshots preserve observed intrabar high and low`() {
        val candles = buildAllRatesTodaySnapshotCandles(
            points = listOf(
                1_000L to 1.1000,
                20_000L to 1.1050,
                50_000L to 1.0980,
                59_000L to 1.1030,
            ),
            timeframeMinutes = 1,
        )

        assertEquals(1, candles.size)
        val candle = candles.single()
        assertEquals(1.1000, candle.open, 1e-9)
        assertEquals(1.1050, candle.high, 1e-9)
        assertEquals(1.0980, candle.low, 1e-9)
        assertEquals(1.1030, candle.close, 1e-9)
    }

    @Test
    fun `curated directory contains only major FX and provider supported metals`() {
        val directory = buildAllRatesTodayCuratedSymbols(
            currencies = mapOf(
                "EUR" to "Euro",
                "GBP" to "British Pound",
                "USD" to "US Dollar",
                "JPY" to "Japanese Yen",
                "CHF" to "Swiss Franc",
                "CAD" to "Canadian Dollar",
                "AUD" to "Australian Dollar",
                "NZD" to "New Zealand Dollar",
                "XAU" to "Gold",
                "XAG" to "Silver",
                "IQD" to "Iraqi Dinar",
            )
        )

        val symbols = directory.map { it.providerSymbol }
        assertEquals(
            listOf(
                "EURUSD",
                "GBPUSD",
                "USDJPY",
                "USDCHF",
                "USDCAD",
                "AUDUSD",
                "NZDUSD",
                "XAUUSD",
                "XAGUSD",
            ),
            symbols,
        )
        assertFalse("EURGBP" in symbols)
        assertFalse("IQDUSD" in symbols)
        assertEquals(AssetClass.METALS, directory.first { it.providerSymbol == "XAUUSD" }.assetClass)
        assertEquals(AssetClass.FOREX, directory.first { it.providerSymbol == "EURUSD" }.assetClass)
    }

    @Test
    fun `metals are omitted when provider directory does not expose metal codes`() {
        val directory = buildAllRatesTodayCuratedSymbols(
            currencies = mapOf(
                "EUR" to "Euro",
                "GBP" to "British Pound",
                "USD" to "US Dollar",
                "JPY" to "Japanese Yen",
                "CHF" to "Swiss Franc",
                "CAD" to "Canadian Dollar",
                "AUD" to "Australian Dollar",
                "NZD" to "New Zealand Dollar",
            )
        )

        assertTrue(directory.none { it.assetClass == AssetClass.METALS })
        assertEquals(7, directory.size)
    }
}
