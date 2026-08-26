package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.confirmation.EngulfConfirmation
import com.foxtrader.app.domain.usecase.nascent.confirmation.SweepConfirmation
import com.foxtrader.app.domain.usecase.nascent.model.EvidenceLevel
import com.foxtrader.app.domain.usecase.nascent.model.ExternalKeyLevel
import com.foxtrader.app.domain.usecase.nascent.model.KeyLevelType
import com.foxtrader.app.domain.usecase.nascent.model.LiquidityType
import com.foxtrader.app.domain.usecase.nascent.model.NascentMode
import com.foxtrader.app.domain.usecase.nascent.model.StructurePoint
import com.foxtrader.app.domain.usecase.nascent.model.TomState
import com.foxtrader.app.domain.usecase.nascent.msu.Msu1Detector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Component-level tests.
 *
 * The engine test proves the pipeline works end to end; these prove the parts
 * refuse the things they are supposed to refuse, which is where a signal engine
 * usually goes wrong.
 */
class NascentComponentTest {

    private val structure = NascentStructureEngine()
    private val liquidity = NascentLiquidityEngine()
    private val directPullback = NascentDirectPullbackEngine()
    private val tom = NascentTomEngine()
    private val msu1 = Msu1Detector()

    // ------------------------------------------------------------------
    // Liquidity cycle
    // ------------------------------------------------------------------

    @Test
    fun `a buy to sell range puts inducement liquidity at the high and transactional at the low`() {
        val candles = NascentFixtures.richSeries()
        val swings = structure.swings(candles, 2, 2)

        val cycles = liquidity.cycles(candles, swings)

        assertTrue("fixture must produce cycles", cycles.isNotEmpty())
        cycles.filter { it.direction == Direction.BEARISH }.forEach { cycle ->
            assertEquals(LiquidityType.ILQ, cycle.ilq!!.type)
            assertEquals(cycle.rangeHigh, cycle.ilq!!.price, 1e-9)
            assertEquals(LiquidityType.TLQ, cycle.tlq!!.type)
            assertEquals(cycle.rangeLow, cycle.tlq!!.price, 1e-9)
        }
    }

    @Test
    fun `a sell to buy range mirrors the liquidity assignment`() {
        val candles = NascentFixtures.richSeries()
        val swings = structure.swings(candles, 2, 2)

        val cycles = liquidity.cycles(candles, swings)

        cycles.filter { it.direction == Direction.BULLISH }.forEach { cycle ->
            assertEquals(LiquidityType.ILQ, cycle.ilq!!.type)
            assertEquals(cycle.rangeLow, cycle.ilq!!.price, 1e-9)
            assertEquals(LiquidityType.TLQ, cycle.tlq!!.type)
            assertEquals(cycle.rangeHigh, cycle.tlq!!.price, 1e-9)
        }
    }

    /**
     * Decisional Structural Liquidity is named by Nascent but never defined, so
     * the engine must not manufacture one by default.
     */
    @Test
    fun `decisional structural liquidity stays off unless explicitly enabled`() {
        val candles = NascentFixtures.richSeries()
        val swings = structure.swings(candles, 2, 2)

        val defaultCycles = liquidity.cycles(candles, swings, enableDecisionalSlq = false)

        assertTrue(
            "an unverified geometry must not be promoted by default",
            defaultCycles.flatMap { it.slq }.none { it.type == LiquidityType.DECISIONAL_SLQ },
        )
    }

    @Test
    fun `cycles are stable when the series is extended`() {
        val candles = NascentFixtures.richSeries()
        val swings = structure.swings(candles, 2, 2)
        val before = liquidity.cycles(candles, swings)

        val extended = NascentFixtures.withFuture(candles, bars = 30)
        val after = liquidity.cycles(extended, structure.swings(extended, 2, 2))

        assertEquals(
            "an already published cycle must never be re-stamped",
            before,
            after.filter { it.confirmationIndex <= candles.lastIndex },
        )
    }

    // ------------------------------------------------------------------
    // MSU Type 1 rejections
    // ------------------------------------------------------------------

    @Test
    fun `msu1 rejects a pullback that breaks the protected extreme`() {
        // H0=110, L0=104, H1=107, L1=101, H2=111 -> H2 exceeds the protected
        // high, so the bearish premise is gone and this is not a continuation.
        val context = contextWith(
            pivots = listOf(
                high(110.0, 10), low(104.0, 20), high(107.0, 30), low(101.0, 40), high(111.0, 50),
            ),
            direction = Direction.BEARISH,
        )

        assertNull(msu1.detect(context))
    }

    @Test
    fun `msu1 rejects a pullback that never took internal liquidity`() {
        // H2=106 fails to trade through H1=107: no manipulation, no Type 1.
        val context = contextWith(
            pivots = listOf(
                high(110.0, 10), low(104.0, 20), high(107.0, 30), low(101.0, 40), high(106.0, 50),
            ),
            direction = Direction.BEARISH,
        )

        assertNull(msu1.detect(context))
    }

    @Test
    fun `msu1 rejects a trend that made no new extreme`() {
        // L1=105 is higher than L0=104, so the bearish trend never advanced.
        val context = contextWith(
            pivots = listOf(
                high(110.0, 10), low(104.0, 20), high(107.0, 30), low(105.0, 40), high(108.0, 50),
            ),
            direction = Direction.BEARISH,
        )

        assertNull(msu1.detect(context))
    }

    @Test
    fun `msu1 rejects a valid geometry sitting at no external location`() {
        val context = contextWith(
            pivots = listOf(
                high(110.0, 10), low(104.0, 20), high(107.0, 30), low(101.0, 40), high(108.0, 50),
            ),
            direction = Direction.BEARISH,
            levels = emptyList(),
        )

        assertNull(
            "an MSU shape in the middle of nowhere is not a Nascent setup",
            msu1.detect(context),
        )
    }

    // ------------------------------------------------------------------
    // Confirmations
    // ------------------------------------------------------------------

    @Test
    fun `a sweep needs the close to reclaim the level`() {
        val sweep = SweepConfirmation()
        val reclaimed = listOf(candle(open = 100.0, high = 100.4, low = 98.5, close = 100.2))
        val notReclaimed = listOf(candle(open = 100.0, high = 100.1, low = 98.5, close = 98.8))

        assertNotNull(sweep.detect(reclaimed, reference = 99.0, direction = Direction.BULLISH, index = 0))
        assertNull(sweep.detect(notReclaimed, reference = 99.0, direction = Direction.BULLISH, index = 0))
    }

    @Test
    fun `an engulfing candle must engulf an opposing candle`() {
        val engulf = EngulfConfirmation()
        val config = NascentConfig()
        // Two bullish candles in a row: continuation, not an engulf.
        val sameDirection = listOf(
            candle(open = 100.0, high = 101.0, low = 99.9, close = 100.9),
            candle(open = 100.9, high = 102.0, low = 99.5, close = 101.8),
        )

        assertNull(engulf.detect(sameDirection, Direction.BULLISH, index = 1, config = config))
    }

    @Test
    fun `the stricter engulfing variant also requires the range to engulf`() {
        val engulf = EngulfConfirmation()
        val config = NascentConfig()
        // Body engulfs, but the previous candle's high is not covered.
        val candles = listOf(
            candle(open = 101.0, high = 103.0, low = 100.9, close = 100.95),
            candle(open = 100.9, high = 101.6, low = 100.8, close = 101.5),
        )

        assertNotNull(engulf.detect(candles, Direction.BULLISH, 1, config, EngulfConfirmation.Variant.BODY))
        assertNull(
            engulf.detect(candles, Direction.BULLISH, 1, config, EngulfConfirmation.Variant.BODY_AND_RANGE),
        )
    }

    // ------------------------------------------------------------------
    // Direct pullback
    // ------------------------------------------------------------------

    @Test
    fun `a direct pullback confirms only after reaching the equilibrium zone`() {
        // A bullish source leg needs a confirmed LOW then a confirmed HIGH, so
        // the series has to descend first before the leg the pullback belongs
        // to can exist at all.
        val candles = NascentFixtures.SeriesBuilder(110.0)
            .leg(to = 100.0, bars = 20)
            .leg(to = 110.0, bars = 20)
            .leg(to = 104.5, bars = 10)
            .build()
        val pivots = structure.swings(candles, 2, 2)

        val state = directPullback.evaluate(
            candles = candles,
            alternatingPivots = pivots,
            direction = Direction.BULLISH,
            atIndex = candles.lastIndex,
            atr = 0.6,
            config = NascentConfig(),
        )

        assertNotNull(state)
        assertEquals(105.0, state!!.equilibrium50, 0.6)
        assertTrue("price retraced past the 50% zone", state.touchedEqZone)
    }

    @Test
    fun `a shallow pullback does not confirm`() {
        val candles = NascentFixtures.SeriesBuilder(110.0)
            .leg(to = 100.0, bars = 20)
            .leg(to = 110.0, bars = 20)
            .leg(to = 109.0, bars = 6)
            .build()
        val pivots = structure.swings(candles, 2, 2)

        val state = directPullback.evaluate(
            candles = candles,
            alternatingPivots = pivots,
            direction = Direction.BULLISH,
            atIndex = candles.lastIndex,
            atr = 0.6,
            config = NascentConfig(),
        )

        assertFalse("a 1-point dip is not a pullback to equilibrium", state?.confirmed ?: false)
    }

    // ------------------------------------------------------------------
    // TOM discipline
    // ------------------------------------------------------------------

    @Test
    fun `source strict mode never claims to know the tom state`() {
        val candles = NascentFixtures.SeriesBuilder(100.0).leg(to = 110.0, bars = 20).build()
        val state = tom.evaluate(
            candles = candles,
            dp = null,
            direction = Direction.BULLISH,
            atIndex = candles.lastIndex,
            config = NascentConfig(mode = NascentMode.SOURCE_STRICT),
        )

        assertEquals(TomState.UNKNOWN, state)
    }

    @Test
    fun `balanced mode never fabricates a tom completion`() {
        val candles = NascentFixtures.SeriesBuilder(100.0)
            .leg(to = 110.0, bars = 20)
            .leg(to = 104.0, bars = 10)
            .leg(to = 112.0, bars = 12)
            .build()
        val pivots = structure.swings(candles, 2, 2)
        val dp = directPullback.evaluate(
            candles, pivots, Direction.BULLISH, candles.lastIndex, 0.6, NascentConfig(),
        )

        val state = tom.evaluate(
            candles = candles,
            dp = dp,
            direction = Direction.BULLISH,
            atIndex = candles.lastIndex,
            config = NascentConfig(mode = NascentMode.BALANCED),
        )

        assertTrue(
            "only research mode may assert a completion the source never defines",
            state != TomState.COMPLETED,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun candle(open: Double, high: Double, low: Double, close: Double) =
        Candle(NascentFixtures.START_TIME, open, high, low, close, 1_000.0)

    private fun high(price: Double, bar: Int) =
        StructurePoint(
            com.foxtrader.app.domain.usecase.nascent.model.StructurePointType.HIGH,
            price,
            bar,
            bar + 2,
        )

    private fun low(price: Double, bar: Int) =
        StructurePoint(
            com.foxtrader.app.domain.usecase.nascent.model.StructurePointType.LOW,
            price,
            bar,
            bar + 2,
        )

    /**
     * Builds a context whose candles span the pivot prices, so the key-level
     * lookup has a realistic price envelope to work against.
     */
    private fun contextWith(
        pivots: List<StructurePoint>,
        direction: Direction,
        levels: List<ExternalKeyLevel> = listOf(
            ExternalKeyLevel(
                type = KeyLevelType.ILQ,
                direction = direction,
                price = 107.5,
                timestamp = NascentFixtures.START_TIME,
                externalCloseTimestamp = NascentFixtures.START_TIME,
                evidence = EvidenceLevel.NASCENT_VERIFIED,
            ),
        ),
    ): NascentInternalContext {
        val candles = NascentFixtures.SeriesBuilder(100.0)
            .leg(to = 112.0, bars = 30)
            .leg(to = 98.0, bars = 30)
            .leg(to = 106.0, bars = 20)
            .build()
        return NascentInternalContext(
            candles = candles,
            atIndex = candles.lastIndex,
            externalDirection = direction,
            candidateLevels = levels,
            alternatingPivots = pivots,
            breaks = emptyList(),
            atr = 0.5,
            config = NascentConfig(),
        )
    }
}
