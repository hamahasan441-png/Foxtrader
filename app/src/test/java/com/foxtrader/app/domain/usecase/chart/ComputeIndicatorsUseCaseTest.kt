package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.ParabolicSar
import com.foxtrader.app.domain.usecase.indicators.SuperTrend
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ComputeIndicatorsUseCase].
 *
 * Validates that indicator computation is correctly delegated from the use case
 * to the underlying indicator engines, that toggle flags gate computation, and
 * that edge cases (empty candles, insufficient data) are handled safely.
 */
class ComputeIndicatorsUseCaseTest {

    private lateinit var useCase: ComputeIndicatorsUseCase

    @Before
    fun setUp() {
        useCase = ComputeIndicatorsUseCase(
            bollingerBands = BollingerBands(),
            ichimokuCloud = IchimokuCloud(),
            superTrend = SuperTrend(),
            parabolicSar = ParabolicSar(),
            smcDetector = SmcDetector(),
            sessionDetector = SessionDetector(),
        )
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private fun buildCandles(count: Int, basePrice: Double = 100.0): List<Candle> =
        (0 until count).map { i ->
            val price = basePrice + i * 0.1
            Candle(
                timestamp = 1_700_000_000_000L + i * 60_000L,
                open = price,
                high = price + 0.5,
                low = price - 0.5,
                close = price,
                volume = 1000.0,
            )
        }

    // ========================================================================
    // EMPTY / INSUFFICIENT DATA
    // ========================================================================

    @Test
    fun `returns null indicators for empty candle list`() {
        val result = useCase(emptyList(), IndicatorToggles())
        assertNull("EMA should be null for empty candles", result.emaShort)
        assertNull("EMA long should be null for empty candles", result.emaLong)
        assertNull("Bollinger should be null for empty candles", result.bollingerUpper)
        assertNull("VWAP should be null for empty candles", result.vwap)
        assertTrue("Order blocks should be empty", result.orderBlocks.isEmpty())
        assertTrue("FVGs should be empty", result.fairValueGaps.isEmpty())
    }

    @Test
    fun `returns null EMA when fewer than 20 candles and ema toggle on`() {
        val candles = buildCandles(15)
        val result = useCase(candles, IndicatorToggles(ema = true))
        assertNull("EMA short needs 20 candles", result.emaShort)
        assertNull("EMA long needs 50 candles", result.emaLong)
    }

    @Test
    fun `returns emaShort but not emaLong when between 20 and 49 candles`() {
        val candles = buildCandles(30)
        val result = useCase(candles, IndicatorToggles(ema = true))
        assertNotNull("EMA short should be computed with 30 candles", result.emaShort)
        assertNull("EMA long needs 50 candles, should be null with 30", result.emaLong)
        assertEquals(30, result.emaShort!!.size)
    }

    // ========================================================================
    // TOGGLE GATING
    // ========================================================================

    @Test
    fun `EMA is null when ema toggle is off`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(ema = false))
        assertNull("EMA should not be computed when toggle is off", result.emaShort)
        assertNull("EMA long should not be computed when toggle is off", result.emaLong)
    }

    @Test
    fun `Bollinger is null when bollinger toggle is off`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(bollinger = false))
        assertNull(result.bollingerUpper)
        assertNull(result.bollingerMiddle)
        assertNull(result.bollingerLower)
    }

    @Test
    fun `VWAP is null when vwap toggle is off`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(vwap = false))
        assertNull(result.vwap)
    }

    @Test
    fun `order blocks list is empty when orderBlocks toggle is off`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(orderBlocks = false))
        assertTrue(result.orderBlocks.isEmpty())
    }

    @Test
    fun `fair value gaps list is empty when fairValueGaps toggle is off`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(fairValueGaps = false))
        assertTrue(result.fairValueGaps.isEmpty())
    }

    @Test
    fun `liquidity list is empty when liquidity toggle is off`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(liquidity = false))
        assertTrue(result.liquidityPools.isEmpty())
    }

    @Test
    fun `sessions list is empty when sessions toggle is off`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(sessions = false))
        assertTrue(result.sessions.isEmpty())
    }

    // ========================================================================
    // CORRECT COMPUTATION WHEN ENABLED
    // ========================================================================

    @Test
    fun `EMA arrays are correct length when toggle on and sufficient data`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(ema = true))
        assertNotNull(result.emaShort)
        assertNotNull(result.emaLong)
        assertEquals(60, result.emaShort!!.size)
        assertEquals(60, result.emaLong!!.size)
    }

    @Test
    fun `Bollinger arrays are correct length when toggle on`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(bollinger = true))
        assertNotNull(result.bollingerUpper)
        assertNotNull(result.bollingerMiddle)
        assertNotNull(result.bollingerLower)
        assertEquals(60, result.bollingerUpper!!.size)
    }

    @Test
    fun `VWAP array is correct length when toggle on`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(vwap = true))
        assertNotNull(result.vwap)
        assertEquals(60, result.vwap!!.size)
    }

    @Test
    fun `SuperTrend is null when fewer than 15 candles`() {
        val candles = buildCandles(10)
        val result = useCase(candles, IndicatorToggles(superTrend = true))
        assertNull(result.superTrendValues)
    }

    @Test
    fun `SuperTrend is computed when toggle on and sufficient data`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(superTrend = true))
        assertNotNull(result.superTrendValues)
        assertNotNull(result.superTrendDir)
        assertEquals(60, result.superTrendValues!!.size)
    }

    @Test
    fun `ParabolicSar is null when fewer than 2 candles`() {
        val candles = buildCandles(1)
        val result = useCase(candles, IndicatorToggles(parabolicSar = true))
        assertNull(result.parabolicSar)
    }

    @Test
    fun `ParabolicSar is computed when toggle on and sufficient data`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(parabolicSar = true))
        assertNotNull(result.parabolicSar)
        assertEquals(60, result.parabolicSar!!.size)
    }

    @Test
    fun `Ichimoku is null when fewer than 52 candles`() {
        val candles = buildCandles(40)
        val result = useCase(candles, IndicatorToggles(ichimoku = true))
        assertNull(result.ichimokuTenkan)
    }

    @Test
    fun `Ichimoku is computed when toggle on and sufficient data`() {
        val candles = buildCandles(60)
        val result = useCase(candles, IndicatorToggles(ichimoku = true))
        assertNotNull(result.ichimokuTenkan)
        assertNotNull(result.ichimokuKijun)
        assertNotNull(result.ichimokuSenkouA)
        assertNotNull(result.ichimokuSenkouB)
        assertNotNull(result.ichimokuChikou)
    }

    @Test
    fun `volume profile is null when fewer than 20 candles`() {
        val candles = buildCandles(10)
        val result = useCase(candles, IndicatorToggles(volumeProfile = true))
        assertNull(result.volumeProfile)
    }

    // ========================================================================
    // ALL TOGGLES OFF
    // ========================================================================

    @Test
    fun `all indicators null when all toggles off`() {
        val candles = buildCandles(100)
        val toggles = IndicatorToggles(
            ema = false, bollinger = false, superTrend = false, parabolicSar = false,
            vwap = false, ichimoku = false, volumeProfile = false,
            orderBlocks = false, fairValueGaps = false, liquidity = false, sessions = false,
            structure = false,
        )
        val result = useCase(candles, toggles)
        assertNull(result.emaShort)
        assertNull(result.emaLong)
        assertNull(result.bollingerUpper)
        assertNull(result.superTrendValues)
        assertNull(result.parabolicSar)
        assertNull(result.vwap)
        assertNull(result.ichimokuTenkan)
        assertNull(result.volumeProfile)
        assertTrue(result.orderBlocks.isEmpty())
        assertTrue(result.fairValueGaps.isEmpty())
        assertTrue(result.liquidityPools.isEmpty())
        assertTrue(result.sessions.isEmpty())
    }

    // ========================================================================
    // DETERMINISM
    // ========================================================================

    @Test
    fun `same inputs produce identical results (determinism)`() {
        val candles = buildCandles(80)
        val toggles = IndicatorToggles(ema = true, bollinger = true, vwap = true)
        val result1 = useCase(candles, toggles)
        val result2 = useCase(candles, toggles)

        // EMA arrays should be element-wise identical
        assertNotNull(result1.emaShort)
        assertNotNull(result2.emaShort)
        result1.emaShort!!.forEachIndexed { i, v ->
            assertEquals("EMA[$i] should be deterministic", v, result2.emaShort!![i], 0.0)
        }
    }
}
