package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.rsireversal.model.PivotSeries
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiCandle
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Master pattern tests (§38, §39, §40) plus the BUY/SELL mirror requirement
 * (§12, §47.8).
 */
class RsiReversalHtfEngineTest {

    private val engine = RsiReversalHtfEngine()

    // ------------------------------------------------------------------
    // §39 — recursion
    // ------------------------------------------------------------------

    @Test
    fun `recursive re-arm waits until RSI fails to confirm the newest low`() {
        // Price: LL1 -> LL2 -> LL3 -> LL4, RSI: HL2 -> LL3 -> HL4.
        val setups = scan(
            priceExtremes = listOf(1.1000, 1.0900, 1.0950, 1.0880, 1.0930, 1.0860, 1.0910, 1.0840, 1.0890),
            rsiExtremes = listOf(40.0, 30.0, 45.0, 35.0, 55.0, 25.0, 50.0, 32.0, 45.0),
        )

        val bullish = setups.filter { it.direction == Direction.BULLISH }
        assertEquals("exactly one BUY should arm", 1, bullish.size)

        val setup = bullish.single()
        // LL3 made a lower RSI low, so it moved the reference instead of arming.
        assertEquals("armed on LL4, not LL3", 35, setup.finalExtreme.index)
        assertEquals(1, setup.recursiveDepth)
        assertEquals(25, setup.recursiveExtremes.single().index)
        assertEquals(5, setup.p1.index)
        assertEquals(15, setup.p2.index)
        assertTrue("no BUY may arm on LL3", bullish.none { it.finalExtreme.index == 25 })
    }

    // ------------------------------------------------------------------
    // §38 — divergence
    // ------------------------------------------------------------------

    @Test
    fun `direct pattern arms when RSI refuses the final low`() {
        val setups = scan(
            priceExtremes = listOf(1.1000, 1.0900, 1.0950, 1.0880, 1.0930, 1.0860, 1.0910),
            rsiExtremes = listOf(40.0, 30.0, 45.0, 35.0, 55.0, 38.0, 50.0),
        ).filter { it.direction == Direction.BULLISH }

        assertEquals(1, setups.size)
        assertEquals("direct pattern has no recursion", 0, setups.single().recursiveDepth)
        assertEquals(25, setups.single().finalExtreme.index)
    }

    @Test
    fun `price lower low confirmed by an RSI lower low is not a divergence`() {
        val setups = scan(
            priceExtremes = listOf(1.1000, 1.0900, 1.0950, 1.0880, 1.0930, 1.0860),
            rsiExtremes = listOf(40.0, 30.0, 45.0, 25.0, 55.0, 20.0),
        )
        assertTrue("RSI confirmed every low, nothing may arm", setups.none { it.direction == Direction.BULLISH })
    }

    // ------------------------------------------------------------------
    // §40 — P3 break semantics
    // ------------------------------------------------------------------

    @Test
    fun `wick-only RSI break arms in wick mode and is ignored in close mode`() {
        val priceExtremes = listOf(1.1000, 1.0900, 1.0950, 1.0880, 1.0930, 1.0860, 1.0910)
        val rsiExtremes = listOf(40.0, 30.0, 45.0, 35.0, 44.0, 38.0, 50.0)

        // The rally after P2 tops out at an RSI close of 44, below the protected
        // 45, but its wicks reach 50.
        val overrideHighs = mapOf(18 to 50.0, 19 to 50.0, 20 to 50.0)

        val closeMode = scan(priceExtremes, rsiExtremes, overrideHighs, RsiReversalFixtures.testConfig())
        assertTrue(
            "a wick alone must not satisfy a close break",
            closeMode.none { it.direction == Direction.BULLISH },
        )

        val wickMode = scan(
            priceExtremes,
            rsiExtremes,
            overrideHighs,
            RsiReversalFixtures.testConfig(rsiBreakMode = BreakMode.WICK_BREAK),
        )
        assertEquals(
            "the same wick must satisfy a wick break",
            1,
            wickMode.count { it.direction == Direction.BULLISH },
        )
    }

    // ------------------------------------------------------------------
    // §25 / §48.4 — the equality ambiguity, both interpretations
    // ------------------------------------------------------------------

    @Test
    fun `equal RSI at the final extreme is governed by the configured flag`() {
        val priceExtremes = listOf(1.1000, 1.0900, 1.0950, 1.0880, 1.0930, 1.0860, 1.0910)
        val rsiExtremes = listOf(40.0, 30.0, 45.0, 35.0, 55.0, 35.0, 50.0)

        val treatedAsFailure = scan(
            priceExtremes,
            rsiExtremes,
            emptyMap(),
            RsiReversalFixtures.testConfig(equalRsiCountsAsFailure = true),
        )
        assertEquals(1, treatedAsFailure.count { it.direction == Direction.BULLISH })

        val treatedAsConfirmation = scan(
            priceExtremes,
            rsiExtremes,
            emptyMap(),
            RsiReversalFixtures.testConfig(equalRsiCountsAsFailure = false),
        )
        assertTrue(treatedAsConfirmation.none { it.direction == Direction.BULLISH })
    }

    // ------------------------------------------------------------------
    // §12 — SELL is the exact mirror of BUY
    // ------------------------------------------------------------------

    @Test
    fun `sell pattern is the exact mirror of the buy pattern`() {
        val priceExtremes = listOf(1.1000, 1.0900, 1.0950, 1.0880, 1.0930, 1.0860, 1.0910, 1.0840, 1.0890)
        val rsiExtremes = listOf(40.0, 30.0, 45.0, 35.0, 55.0, 25.0, 50.0, 32.0, 45.0)

        val buy = scan(priceExtremes, rsiExtremes).filter { it.direction == Direction.BULLISH }

        val pricePath = RsiReversalFixtures.mirrorPrice(RsiReversalFixtures.zigzag(priceExtremes))
        val rsiPath = RsiReversalFixtures.mirrorRsi(RsiReversalFixtures.zigzag(rsiExtremes))
        val sell = scanPaths(pricePath, rsiPath, emptyMap(), RsiReversalFixtures.testConfig())
            .filter { it.direction == Direction.BEARISH }

        assertEquals("mirrored data must produce the mirrored count", buy.size, sell.size)
        buy.zip(sell).forEach { (b, s) ->
            assertEquals(b.p1.index, s.p1.index)
            assertEquals(b.p2.index, s.p2.index)
            assertEquals(b.finalExtreme.index, s.finalExtreme.index)
            assertEquals(b.recursiveDepth, s.recursiveDepth)
            assertEquals(b.armedIndex, s.armedIndex)
        }
    }

    // ------------------------------------------------------------------
    // §27 — expiry
    // ------------------------------------------------------------------

    @Test
    fun `a setup that never reaches its final extreme expires instead of arming`() {
        val setups = scan(
            priceExtremes = listOf(1.1000, 1.0900, 1.0950, 1.0880, 1.0930, 1.0860, 1.0910),
            rsiExtremes = listOf(40.0, 30.0, 45.0, 35.0, 55.0, 38.0, 50.0),
            overrideRsiHighs = emptyMap(),
            config = RsiReversalFixtures.testConfig().copy(maxBarsP3ToFinal = 2),
        )
        assertTrue(
            "the final extreme arrived long after the window closed",
            setups.none { it.direction == Direction.BULLISH },
        )
    }

    // ------------------------------------------------------------------

    private fun scan(
        priceExtremes: List<Double>,
        rsiExtremes: List<Double>,
        overrideRsiHighs: Map<Int, Double> = emptyMap(),
        config: RsiReversalConfig = RsiReversalFixtures.testConfig(),
    ): List<RsiReversalSetup> = scanPaths(
        pricePath = RsiReversalFixtures.zigzag(priceExtremes),
        rsiPath = RsiReversalFixtures.zigzag(rsiExtremes),
        overrideRsiHighs = overrideRsiHighs,
        config = config,
    )

    private fun scanPaths(
        pricePath: DoubleArray,
        rsiPath: DoubleArray,
        overrideRsiHighs: Map<Int, Double>,
        config: RsiReversalConfig,
    ): List<RsiReversalSetup> {
        val candles = RsiReversalFixtures.priceCandles(pricePath)
        val rsiCandles = RsiReversalFixtures.rsiCandlesFrom(rsiPath, candles).map { candle ->
            overrideRsiHighs[candle.index]?.let { candle.copy(high = maxOf(it, candle.high)) } ?: candle
        }

        return engine.scan(
            symbol = RsiReversalFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = candles,
            rsiCandles = rsiCandles,
            pricePivots = pricePivots(candles, config),
            rsiPivots = rsiPivots(rsiCandles, config),
            config = config,
        )
    }

    private fun pricePivots(candles: List<com.foxtrader.app.domain.model.Candle>, config: RsiReversalConfig) =
        RsiReversalPivotEngine.detect(
            series = PivotSeries.PRICE,
            size = candles.size,
            left = config.pricePivotLeft,
            right = config.pricePivotRight,
            highAt = { candles[it].high },
            lowAt = { candles[it].low },
            timestampAt = { candles[it].timestamp },
        )

    private fun rsiPivots(rsiCandles: List<RsiCandle>, config: RsiReversalConfig) =
        RsiReversalPivotEngine.detect(
            series = PivotSeries.RSI,
            size = rsiCandles.size,
            left = config.rsiPivotLeft,
            right = config.rsiPivotRight,
            highAt = { rsiCandles[it].high },
            lowAt = { rsiCandles[it].low },
            timestampAt = { rsiCandles[it].timestamp },
        )
}
