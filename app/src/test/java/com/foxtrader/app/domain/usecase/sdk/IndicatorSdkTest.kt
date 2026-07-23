package com.foxtrader.app.domain.usecase.sdk

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.indicator.Indicator
import com.foxtrader.app.domain.sdk.indicator.IndicatorResult
import com.foxtrader.app.domain.sdk.indicator.IndicatorRegistry
import com.foxtrader.app.domain.sdk.indicator.builtin.AdxIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.BollingerIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.EmaIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.IchimokuIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.MacdIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.ParabolicSarIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.RsiIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.StochasticIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.SuperTrendIndicator
import com.foxtrader.app.domain.sdk.indicator.builtin.VwapIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the Indicator SDK registry and all built-in indicator adapters.
 *
 * Validates:
 * - Registry CRUD operations (register, get, unregister, contains, size).
 * - Each built-in indicator computes non-empty series on sufficient data.
 * - Each built-in indicator returns empty gracefully on insufficient data.
 * - Overlay vs sub-panel categorization.
 */
class IndicatorSdkTest {

    private lateinit var registry: IndicatorRegistry

    @Before
    fun setup() {
        registry = IndicatorRegistry().apply {
            register(EmaIndicator(20))
            register(EmaIndicator(50))
            register(EmaIndicator(200))
            register(BollingerIndicator())
            register(VwapIndicator())
            register(SuperTrendIndicator())
            register(ParabolicSarIndicator())
            register(IchimokuIndicator())
            register(RsiIndicator(14))
            register(MacdIndicator())
            register(StochasticIndicator())
            register(AdxIndicator())
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Registry CRUD
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `registry registers all built-in indicators`() {
        assertEquals(12, registry.size)
    }

    @Test
    fun `registry get returns registered indicator`() {
        assertNotNull(registry.get("ema_20"))
        assertNotNull(registry.get("macd"))
        assertNotNull(registry.get("ichimoku"))
    }

    @Test
    fun `registry get returns null for unknown id`() {
        assertNull(registry.get("unknown_indicator_xyz"))
    }

    @Test
    fun `registry contains returns correct value`() {
        assertTrue(registry.contains("vwap"))
        assertFalse(registry.contains("does_not_exist"))
    }

    @Test
    fun `registry unregister removes indicator`() {
        registry.unregister("adx")
        assertFalse(registry.contains("adx"))
        assertEquals(11, registry.size)
    }

    @Test
    fun `registry re-register overwrites existing`() {
        val before = registry.size
        registry.register(EmaIndicator(20)) // same id "ema_20"
        assertEquals("Size unchanged on overwrite", before, registry.size)
    }

    @Test
    fun `getOverlays returns only overlay indicators`() {
        val overlays = registry.getOverlays()
        assertTrue(overlays.all { it.isOverlay })
        assertTrue(overlays.any { it.id == "ema_20" })
        assertTrue(overlays.any { it.id == "vwap" })
        assertTrue(overlays.any { it.id == "supertrend" })
    }

    @Test
    fun `getSubPanels returns only sub-panel indicators`() {
        val panels = registry.getSubPanels()
        assertTrue(panels.all { !it.isOverlay })
        assertTrue(panels.any { it.id == "rsi_14" })
        assertTrue(panels.any { it.id == "macd" })
        assertTrue(panels.any { it.id == "stochastic" })
        assertTrue(panels.any { it.id == "adx" })
    }

    // ────────────────────────────────────────────────────────────────────────
    // Built-in indicator compute tests (happy path: 200 bars of data)
    // ────────────────────────────────────────────────────────────────────────

    private fun syntheticCandles(n: Int): List<Candle> {
        val candles = mutableListOf<Candle>()
        var price = 100.0
        for (i in 0 until n) {
            val open = price
            val close = price + (if (i % 3 == 0) 0.5 else -0.3)
            val high = maxOf(open, close) + 0.2
            val low = minOf(open, close) - 0.2
            candles.add(Candle(timestamp = i * 60_000L, open = open, high = high, low = low, close = close, volume = 1000.0 + i))
            price = close
        }
        return candles
    }

    @Test
    fun `EmaIndicator compute returns main series of correct length`() {
        val candles = syntheticCandles(50)
        val result = EmaIndicator(20).compute(candles)
        assertNotNull(result.main)
        assertEquals(candles.size, result.main!!.size)
    }

    @Test
    fun `EmaIndicator compute returns empty on insufficient data`() {
        val result = EmaIndicator(20).compute(syntheticCandles(10))
        assertTrue("Should be empty on < 20 bars", result.series.isEmpty())
    }

    @Test
    fun `BollingerIndicator compute returns upper middle and lower series`() {
        val candles = syntheticCandles(100)
        val result = BollingerIndicator().compute(candles)
        assertNotNull(result.series["upper"])
        assertNotNull(result.series["middle"])
        assertNotNull(result.series["lower"])
        assertEquals(candles.size, result.series["upper"]!!.size)
    }

    @Test
    fun `VwapIndicator compute returns main series`() {
        val candles = syntheticCandles(50)
        val result = VwapIndicator().compute(candles)
        assertNotNull(result.main)
        assertEquals(candles.size, result.main!!.size)
    }

    @Test
    fun `SuperTrendIndicator compute returns values and direction`() {
        val candles = syntheticCandles(50)
        val result = SuperTrendIndicator().compute(candles)
        assertNotNull(result.series["main"])
        assertNotNull(result.series["direction"])
        assertEquals(candles.size, result.series["main"]!!.size)
    }

    @Test
    fun `ParabolicSarIndicator compute returns main series`() {
        val candles = syntheticCandles(20)
        val result = ParabolicSarIndicator().compute(candles)
        assertNotNull(result.main)
        assertEquals(candles.size, result.main!!.size)
    }

    @Test
    fun `RsiIndicator compute returns main series in 0-100 range`() {
        val candles = syntheticCandles(50)
        val result = RsiIndicator(14).compute(candles)
        assertNotNull(result.main)
        result.main!!.forEach { v -> assertTrue("RSI in range", v in 0.0..100.0) }
    }

    @Test
    fun `MacdIndicator compute returns macd, signal, histogram`() {
        val candles = syntheticCandles(100)
        val result = MacdIndicator().compute(candles)
        assertNotNull(result.series["macd"])
        assertNotNull(result.series["signal"])
        assertNotNull(result.series["histogram"])
    }

    @Test
    fun `MacdIndicator returns empty on insufficient data`() {
        val result = MacdIndicator().compute(syntheticCandles(30))
        assertTrue(result.series.isEmpty())
    }

    @Test
    fun `StochasticIndicator compute returns k and d series`() {
        val candles = syntheticCandles(50)
        val result = StochasticIndicator().compute(candles)
        assertNotNull(result.series["k"])
        assertNotNull(result.series["d"])
        result.series["k"]!!.forEach { v -> assertTrue("Stoch K in range", v in 0.0..100.0) }
    }

    @Test
    fun `AdxIndicator compute returns adx, plusDI, minusDI`() {
        val candles = syntheticCandles(100)
        val result = AdxIndicator().compute(candles)
        assertNotNull(result.series["adx"])
        assertNotNull(result.series["plusDI"])
        assertNotNull(result.series["minusDI"])
        result.series["adx"]!!.forEach { v -> assertTrue("ADX non-negative", v >= 0.0) }
    }

    @Test
    fun `IchimokuIndicator compute returns all five series`() {
        val candles = syntheticCandles(100)
        val result = IchimokuIndicator().compute(candles)
        assertNotNull(result.series["tenkan"])
        assertNotNull(result.series["kijun"])
        assertNotNull(result.series["senkouA"])
        assertNotNull(result.series["senkouB"])
        assertNotNull(result.series["chikou"])
    }

    @Test
    fun `IchimokuIndicator returns empty on insufficient data`() {
        val result = IchimokuIndicator().compute(syntheticCandles(30))
        assertTrue(result.series.isEmpty())
    }
}
