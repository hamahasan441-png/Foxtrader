package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.rsireversal.model.LtfConfirmationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lower-timeframe confirmation (§16–§18) and the §41 acceptance cases, plus
 * the risk geometry of §19–§20.
 */
class RsiReversalLtfEngineTest {

    private val engine = RsiReversalLtfEngine()
    private val config = RsiReversalFixtures.testConfig()

    /** Index of the sweep bar in [bullishSeries]. */
    private val sweepBar = 33

    /** Index of the change-of-character bar in [bullishSeries]. */
    private val chochBar = 35

    // ------------------------------------------------------------------
    // §41 — acceptance cases
    // ------------------------------------------------------------------

    @Test
    fun `armed setup with a valid sweep and choch produces an entry`() {
        val confirmation = engine.confirm(Direction.BULLISH, bullishSeries(), startIndex = 31, config = config)

        assertNotNull("balanced mode should confirm this sequence", confirmation)
        requireNotNull(confirmation)
        assertEquals(LtfConfirmationType.SWEEP_DISPLACEMENT_BOS, confirmation.type)
        assertEquals(chochBar, confirmation.entryIndex)
        assertEquals("stop sits behind the swept low", 1.0998, confirmation.sweptExtreme, 1e-9)
        assertEquals(bullishSeries()[chochBar].close, confirmation.entry, 1e-9)
    }

    @Test
    fun `a sweep without a change of character produces no entry`() {
        // Truncate before the CHOCH bar: the sweep happened, nothing confirmed it.
        val truncated = bullishSeries().take(chochBar)
        val confirmation = engine.confirm(Direction.BULLISH, truncated, startIndex = 31, config = config)
        assertNull(confirmation)
    }

    @Test
    fun `an expired window produces no entry`() {
        val narrow = config.copy(ltfConfirmationWindowBars = 2)
        val confirmation = engine.confirm(Direction.BULLISH, bullishSeries(), startIndex = 31, config = narrow)
        assertNull("the window closed before the sweep", confirmation)
    }

    @Test
    fun `bars before the armed time are never consulted`() {
        // Starting after the sweep, the same series must yield nothing: the
        // engine may not reach backwards for a sweep the setup could not see.
        val confirmation = engine.confirm(
            Direction.BULLISH,
            bullishSeries(),
            startIndex = sweepBar + 1,
            config = config,
        )
        assertNull(confirmation)
    }

    @Test
    fun `an empty or out of range series is handled without throwing`() {
        assertNull(engine.confirm(Direction.BULLISH, emptyList(), 0, config))
        assertNull(engine.confirm(Direction.BULLISH, bullishSeries(), startIndex = 9_999, config = config))
        assertNull(engine.confirm(Direction.BULLISH, bullishSeries(), startIndex = -1, config = config))
    }

    // ------------------------------------------------------------------
    // §18 — entry modes
    // ------------------------------------------------------------------

    @Test
    fun `aggressive mode confirms on the change of character alone`() {
        val aggressive = config.copy(entryMode = EntryMode.AGGRESSIVE)
        val confirmation = engine.confirm(Direction.BULLISH, bullishSeries(), 31, aggressive)

        requireNotNull(confirmation)
        assertEquals(LtfConfirmationType.SWEEP_CHOCH, confirmation.type)
        assertEquals(chochBar, confirmation.entryIndex)
    }

    @Test
    fun `balanced mode rejects a change of character with no displacement`() {
        // Same structure, but the impulse bars are shrunk to ordinary size.
        val flat = bullishSeries().mapIndexed { index, candle ->
            if (index == 34 || index == chochBar) {
                candle.copy(open = candle.close - 0.0002)
            } else {
                candle
            }
        }
        assertNull(engine.confirm(Direction.BULLISH, flat, 31, config))
        assertNotNull(
            "the same series still confirms when displacement is not required",
            engine.confirm(Direction.BULLISH, flat, 31, config.copy(entryMode = EntryMode.AGGRESSIVE)),
        )
    }

    @Test
    fun `strict mode demands more than the balanced sequence`() {
        val strict = config.copy(entryMode = EntryMode.STRICT)
        // The fixture stops at the change of character, so STRICT — which also
        // needs a BOS and a held retest — must not confirm on it.
        assertNull(engine.confirm(Direction.BULLISH, bullishSeries(), 31, strict))
    }

    // ------------------------------------------------------------------
    // §17 — the mirror
    // ------------------------------------------------------------------

    @Test
    fun `sell confirmation mirrors buy confirmation exactly`() {
        val buy = engine.confirm(Direction.BULLISH, bullishSeries(), 31, config)
        val sell = engine.confirm(Direction.BEARISH, mirrored(bullishSeries()), 31, config)

        requireNotNull(buy)
        requireNotNull(sell)
        assertEquals(buy.type, sell.type)
        assertEquals(buy.entryIndex, sell.entryIndex)
        assertEquals("mirrored sweep sits at the mirrored price", mirror(buy.sweptExtreme), sell.sweptExtreme, 1e-9)
    }

    // ------------------------------------------------------------------
    // §19 / §20 — risk geometry
    // ------------------------------------------------------------------

    @Test
    fun `buy geometry places the stop behind the sweep and targets four R`() {
        val geometry = RsiReversalRiskEngine.build(
            direction = Direction.BULLISH,
            entry = 1.1085,
            sweptExtreme = 1.0998,
            config = config,
        )
        requireNotNull(geometry)
        assertEquals(1.0998, geometry.stop, 1e-9)
        val risk = geometry.entry - geometry.stop
        assertEquals(geometry.entry + 4.0 * risk, geometry.target, 1e-9)
    }

    @Test
    fun `sell geometry is the exact mirror`() {
        val geometry = RsiReversalRiskEngine.build(
            direction = Direction.BEARISH,
            entry = 1.0998,
            sweptExtreme = 1.1085,
            config = config,
        )
        requireNotNull(geometry)
        assertEquals(1.1085, geometry.stop, 1e-9)
        val risk = geometry.stop - geometry.entry
        assertEquals(geometry.entry - 4.0 * risk, geometry.target, 1e-9)
    }

    @Test
    fun `a degenerate sweep that leaves no risk yields no geometry`() {
        assertNull(
            RsiReversalRiskEngine.build(Direction.BULLISH, entry = 1.1000, sweptExtreme = 1.1000, config = config),
        )
        assertNull(
            RsiReversalRiskEngine.build(Direction.BULLISH, entry = 1.1000, sweptExtreme = 1.1050, config = config),
        )
    }

    @Test
    fun `the stop buffer widens risk without changing the reward multiple`() {
        val buffered = RsiReversalRiskEngine.build(
            direction = Direction.BULLISH,
            entry = 1.1085,
            sweptExtreme = 1.0998,
            config = config.copy(stopBufferFraction = 0.0001),
        )
        requireNotNull(buffered)
        assertTrue("buffer must push the stop further away", buffered.stop < 1.0998)
        val risk = buffered.entry - buffered.stop
        assertEquals(buffered.entry + 4.0 * risk, buffered.target, 1e-9)
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    /**
     * A bullish sweep sequence with explicit structure:
     * bars 0–32 decline into a confirmed swing low at 24 and a confirmed swing
     * high at 28; bar 33 sweeps below the swing low and closes back above it;
     * bar 34 displaces upward; bar 35 closes above the protected high.
     */
    private fun bullishSeries(): List<Candle> {
        val lows = DoubleArray(40)
        val highs = DoubleArray(40)

        // Strict decline: deliberately pivot-free, so the only structure the
        // engine can find is the structure this fixture states explicitly.
        for (i in 0..19) {
            lows[i] = 1.1200 - i * 0.0008
            highs[i] = lows[i] + 0.0006
        }
        val structured = listOf(
            1.1040, 1.1032, 1.1024, 1.1016, 1.1008, // 20..24, swing low at 24
            1.1016, 1.1024, 1.1032, 1.1040, 1.1032, // 25..29, swing high at 28
            1.1024, 1.1016, 1.1010, // 30..32
        )
        structured.forEachIndexed { offset, low ->
            val i = 20 + offset
            lows[i] = low
            highs[i] = low + 0.0006
        }
        highs[28] = 1.1070 // the protected high

        val candles = ArrayList<Candle>(40)
        for (i in 0..32) {
            candles += bar(i, open = lows[i] + 0.0004, high = highs[i], low = lows[i], close = lows[i] + 0.0002)
        }
        // 33: the sweep — trades through 1.1008 and closes back above it.
        candles += bar(33, open = 1.1010, high = 1.1020, low = 1.0998, close = 1.1015)
        // 34: displacement.
        candles += bar(34, open = 1.1015, high = 1.1055, low = 1.1012, close = 1.1050)
        // 35: change of character above the protected 1.1070.
        candles += bar(35, open = 1.1050, high = 1.1090, low = 1.1048, close = 1.1085)
        for (i in 36..39) {
            candles += bar(i, open = 1.1085, high = 1.1092, low = 1.1080, close = 1.1087)
        }
        return candles
    }

    private fun bar(index: Int, open: Double, high: Double, low: Double, close: Double) = Candle(
        timestamp = index * RsiReversalFixtures.BAR_MILLIS,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1_000.0,
    )

    private fun mirror(value: Double) = 2.0 * MIRROR_CENTRE - value

    private fun mirrored(candles: List<Candle>) = candles.map {
        it.copy(open = mirror(it.open), high = mirror(it.low), low = mirror(it.high), close = mirror(it.close))
    }

    private companion object {
        const val MIRROR_CENTRE = 1.1100
    }
}
