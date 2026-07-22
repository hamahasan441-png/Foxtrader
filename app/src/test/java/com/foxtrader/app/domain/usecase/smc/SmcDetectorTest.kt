package com.foxtrader.app.domain.usecase.smc

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.OrderBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SmcDetector.
 * Validates detection of Order Blocks, Fair Value Gaps, Liquidity Pools, and Volume Profile.
 */
class SmcDetectorTest {

    private lateinit var detector: SmcDetector

    @Before
    fun setup() {
        detector = SmcDetector()
    }

    // ========================================================================
    // TEST DATA BUILDERS
    // ========================================================================

    private fun candle(
        open: Double, high: Double, low: Double, close: Double,
        volume: Double = 100.0, timestamp: Long = 0L,
    ) = Candle(timestamp, open, high, low, close, volume)

    /**
     * Creates a sequence with a bearish candle followed by a strong bullish impulse.
     * This should produce a bullish order block.
     */
    private fun buildBullishOBSequence(): List<Candle> {
        val candles = mutableListOf<Candle>()
        // 20 bars of base (for ATR calculation)
        for (i in 0 until 20) {
            candles.add(candle(100.0, 101.0, 99.0, 100.0 + (i % 2) * 0.1, timestamp = i * 60000L))
        }
        // Bearish candle (the order block)
        candles.add(candle(100.5, 101.0, 99.0, 99.5, timestamp = 20 * 60000L))
        // Strong bullish impulse (body > 1.5x ATR)
        candles.add(candle(99.5, 104.0, 99.5, 103.5, timestamp = 21 * 60000L))
        // Follow through
        candles.add(candle(103.5, 105.0, 103.0, 104.5, timestamp = 22 * 60000L))
        return candles
    }

    /**
     * Creates a sequence with a gap between candle 1 high and candle 3 low.
     * This should produce a bullish FVG.
     */
    private fun buildBullishFVGSequence(): List<Candle> {
        return listOf(
            candle(100.0, 101.0, 99.0, 100.5, timestamp = 0L),   // c1: high = 101.0
            candle(101.5, 103.0, 101.0, 102.5, timestamp = 60000L), // c2: impulse candle
            candle(102.5, 104.0, 101.5, 103.5, timestamp = 120000L), // c3: low = 101.5 > c1 high = 101.0 ✓ gap!
        )
    }

    // ========================================================================
    // ORDER BLOCK TESTS
    // ========================================================================

    @Test
    fun `detectOrderBlocks finds bullish OB before impulse`() {
        val candles = buildBullishOBSequence()
        val blocks = detector.detectOrderBlocks(candles, impulseMultiplier = 1.0)
        assertTrue("Should detect at least one OB", blocks.isNotEmpty())
        val bullish = blocks.filter { it.type == OrderBlockType.BULLISH }
        assertTrue("Should have at least one bullish OB", bullish.isNotEmpty())
    }

    @Test
    fun `detectOrderBlocks returns empty for insufficient data`() {
        val candles = listOf(
            candle(100.0, 101.0, 99.0, 100.5),
            candle(100.5, 101.5, 99.5, 101.0),
        )
        val blocks = detector.detectOrderBlocks(candles)
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `order block has valid price range`() {
        val candles = buildBullishOBSequence()
        val blocks = detector.detectOrderBlocks(candles, impulseMultiplier = 1.0)
        for (ob in blocks) {
            assertTrue("OB high should be > OB low", ob.highPrice > ob.lowPrice)
            assertTrue("OB strength should be in [0,1]", ob.strength in 0.0..1.0)
        }
    }

    // ========================================================================
    // FAIR VALUE GAP TESTS
    // ========================================================================

    @Test
    fun `detectFairValueGaps finds bullish FVG`() {
        val candles = buildBullishFVGSequence()
        val gaps = detector.detectFairValueGaps(candles)
        assertTrue("Should detect at least one FVG", gaps.isNotEmpty())
        val bullish = gaps.filter { it.type == FvgType.BULLISH }
        assertTrue("Should have bullish FVG", bullish.isNotEmpty())
    }

    @Test
    fun `FVG has valid price range`() {
        val candles = buildBullishFVGSequence()
        val gaps = detector.detectFairValueGaps(candles)
        for (gap in gaps) {
            assertTrue("FVG high > FVG low", gap.highPrice > gap.lowPrice)
            assertTrue("Fill percent in [0,1]", gap.fillPercent in 0.0..1.0)
        }
    }

    @Test
    fun `detectFairValueGaps returns empty for insufficient data`() {
        val candles = listOf(candle(100.0, 101.0, 99.0, 100.5))
        assertTrue(detector.detectFairValueGaps(candles).isEmpty())
    }

    @Test
    fun `no FVG when candles overlap`() {
        // Candles that fully overlap — no gap possible
        val candles = listOf(
            candle(100.0, 102.0, 98.0, 101.0),
            candle(101.0, 102.5, 99.0, 101.5),
            candle(101.5, 102.0, 100.0, 101.0), // low=100 < c1 high=102, no gap
        )
        val gaps = detector.detectFairValueGaps(candles)
        assertTrue("Overlapping candles should produce no FVG", gaps.isEmpty())
    }

    // ========================================================================
    // LIQUIDITY POOL TESTS
    // ========================================================================

    @Test
    fun `detectLiquidity finds equal highs`() {
        // Create candles with equal highs at 105.0
        val candles = mutableListOf<Candle>()
        for (i in 0 until 30) {
            val high = if (i == 5 || i == 10 || i == 15) 105.0 else 103.0
            candles.add(candle(100.0, high, 99.0, 101.0, timestamp = i * 60000L))
        }
        val pools = detector.detectLiquidity(candles, tolerance = 0.5, minTouches = 2)
        assertTrue("Should detect liquidity from equal highs", pools.isNotEmpty())
    }

    @Test
    fun `detectLiquidity returns empty for insufficient data`() {
        val candles = listOf(candle(100.0, 101.0, 99.0, 100.5))
        assertTrue(detector.detectLiquidity(candles).isEmpty())
    }

    // ========================================================================
    // VOLUME PROFILE TESTS
    // ========================================================================

    @Test
    fun `computeVolumeProfile returns correct bucket count`() {
        val candles = buildBullishOBSequence()
        val profile = detector.computeVolumeProfile(candles, buckets = 20)
        assertEquals(20, profile.levels.size)
    }

    @Test
    fun `volume profile POC is within price range`() {
        val candles = buildBullishOBSequence()
        val profile = detector.computeVolumeProfile(candles, buckets = 20)
        val high = candles.maxOf { it.high }
        val low = candles.minOf { it.low }
        assertTrue("POC should be within data range",
            profile.pocPrice in low..high)
    }

    @Test
    fun `volume profile total volume equals sum of levels`() {
        val candles = buildBullishOBSequence()
        val profile = detector.computeVolumeProfile(candles, buckets = 10)
        val summed = profile.levels.sumOf { it.volume }
        assertEquals(profile.totalVolume, summed, 0.01)
    }

    @Test
    fun `volume profile empty input`() {
        val profile = detector.computeVolumeProfile(emptyList())
        assertTrue(profile.levels.isEmpty())
        assertEquals(0.0, profile.totalVolume, 0.001)
    }
}
