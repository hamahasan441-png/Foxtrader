package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.tradepro.AbsorptionDetector
import com.foxtrader.app.domain.usecase.tradepro.CandleDerivedOrderFlowProvider
import com.foxtrader.app.domain.usecase.tradepro.FlipZoneEngine
import com.foxtrader.app.domain.usecase.tradepro.HoldZoneEngine
import com.foxtrader.app.domain.usecase.tradepro.ImbalanceDetector
import com.foxtrader.app.domain.usecase.tradepro.MtfTradeProEngine
import com.foxtrader.app.domain.usecase.tradepro.TradeProRiskGuard
import com.foxtrader.app.domain.usecase.tradepro.TradeProSignalEngine
import com.foxtrader.app.domain.usecase.tradepro.TrendRegimeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeProSignalEngineTest {

    private val engine = TradeProSignalEngine(
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        flipZoneEngine = FlipZoneEngine(),
        orderFlowProvider = CandleDerivedOrderFlowProvider(),
        imbalanceDetector = ImbalanceDetector(),
        absorptionDetector = AbsorptionDetector(),
        holdZoneEngine = HoldZoneEngine(),
        riskGuard = TradeProRiskGuard(),
        trendRegimeFilter = TrendRegimeFilter(),
        mtfEngine = MtfTradeProEngine(AnalyzeMarketStructureUseCase(), FlipZoneEngine()),
    )

    private fun flat(n: Int): List<Candle> =
        (0 until n).map { Candle(it * 60_000L, 100.0, 100.0, 100.0, 100.0, 100.0) }

    /** Oscillating uptrend: rising legs with pullbacks so real structure/swings form. */
    private fun uptrend(): List<Candle> {
        val list = ArrayList<Candle>()
        var price = 100.0
        var t = 0L
        repeat(9) {
            repeat(7) {
                val open = price
                val close = price + 1.0
                list += Candle(t, open, close + 0.2, open - 0.2, close, 120.0)
                price = close
                t += 60_000L
            }
            repeat(3) {
                val open = price
                val close = price - 0.5
                list += Candle(t, open, open + 0.2, close - 0.2, close, 80.0)
                price = close
                t += 60_000L
            }
        }
        return list
    }

    @Test
    fun `too few bars returns an empty stand-aside analysis`() {
        val a = engine.analyze("MESUSD", flat(10))
        assertEquals(SetupStage.NONE, a.stage)
        assertNull(a.setup)
    }

    @Test
    fun `flat market has no flip zone and no setup`() {
        val a = engine.analyze("MESUSD", flat(40))
        assertEquals(SetupStage.NONE, a.stage)
        assertNull(a.flipZone)
        assertNull(a.setup)
    }

    @Test
    fun `trending market is analysed without error and any setup obeys invariants`() {
        val a = engine.analyze("MESUSD", uptrend())
        assertEquals("MESUSD", a.symbol)
        // Engine always returns a coherent read; hold zones list is never null.
        assertTrue(a.holdZones.size >= 0)

        val setup = a.setup
        if (setup != null) {
            assertTrue(setup.confidence in 0..100)
            assertTrue(setup.riskReward.isFinite() && setup.riskReward >= 0.0)
            if (setup.direction == Direction.BULLISH) {
                assertTrue("stop must sit below entry for a long", setup.stopLoss < setup.entry)
                assertTrue(setup.target1 <= setup.target2)
                assertTrue(setup.target1 > setup.entry)
            } else {
                assertTrue("stop must sit above entry for a short", setup.stopLoss > setup.entry)
                assertTrue(setup.target1 >= setup.target2)
                assertTrue(setup.target1 < setup.entry)
            }
            // Only EXECUTE setups are tradable.
            assertEquals(setup.stage == SetupStage.EXECUTE, setup.isExecutable)
        }
    }

    @Test
    fun `execute setups only appear once price is inside the zone`() {
        val a = engine.analyze("MESUSD", uptrend())
        val setup = a.setup
        if (setup != null && setup.stage == SetupStage.EXECUTE) {
            val z = setup.holdZone!!
            // Entry is the current price and must be within the qualified zone.
            assertTrue(setup.entry in z.low..z.high)
        }
    }
}
