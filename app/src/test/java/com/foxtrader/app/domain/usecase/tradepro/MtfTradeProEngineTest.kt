package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.MtfBias
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.TradeProManagementPlan
import com.foxtrader.app.domain.model.tradepro.TradeProSetup
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtfTradeProEngineTest {

    private val engine = MtfTradeProEngine(
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        flipZoneEngine = FlipZoneEngine(),
    )

    /** Rising candles that produce a bullish structure.
     *
     * Uses an 8-up / 6-down cycle (14 bars) so that swing highs and lows are
     * detectable with the default swingLookback=5 (leftBars=5, rightBars=5).
     * The 6-bar correction keeps the 5 confirmation bars after each peak below
     * the swing high, satisfying the right-bars check in AnalyzeMarketStructureUseCase.
     */
    private fun bullishCandles(n: Int): List<Candle> {
        val list = ArrayList<Candle>()
        var price = 100.0
        for (i in 0 until n) {
            if (i % 14 < 8) {
                val open = price; val close = price + 1.0
                list += Candle(i * 60_000L, open, close + 0.2, open - 0.2, close, 100.0)
                price = close
            } else {
                val open = price; val close = price - 0.5
                list += Candle(i * 60_000L, open, open + 0.2, close - 0.2, close, 80.0)
                price = close
            }
        }
        return list
    }

    /** Falling candles that produce a bearish structure.
     *
     * Uses an 8-down / 6-up cycle (14 bars) so that swing lows are detectable
     * with the default swingLookback=5, mirroring the bullish helper.
     */
    private fun bearishCandles(n: Int): List<Candle> {
        val list = ArrayList<Candle>()
        var price = 200.0
        for (i in 0 until n) {
            if (i % 14 < 8) {
                val open = price; val close = price - 1.0
                list += Candle(i * 60_000L, open, open + 0.2, close - 0.2, close, 100.0)
                price = close
            } else {
                val open = price; val close = price + 0.5
                list += Candle(i * 60_000L, open, close + 0.2, open - 0.2, close, 80.0)
                price = close
            }
        }
        return list
    }

    private fun flatCandles(n: Int): List<Candle> =
        (0 until n).map { Candle(it * 60_000L, 100.0, 100.5, 99.5, 100.0, 100.0) }

    @Test
    fun `computeHtfBias returns bullish when HTFs trend up`() {
        val htf = mapOf(
            Timeframe.H4 to bullishCandles(80),
            Timeframe.D1 to bullishCandles(80),
        )
        val bias = engine.computeHtfBias(htf)
        assertEquals(Bias.BULLISH, bias.bias)
        assertTrue(bias.isDefined)
        assertTrue(bias.alignedCount >= 1)
        assertEquals(2, bias.totalChecked)
    }

    @Test
    fun `computeHtfBias returns bearish when HTFs trend down`() {
        val htf = mapOf(Timeframe.H4 to bearishCandles(80))
        val bias = engine.computeHtfBias(htf)
        assertEquals(Bias.BEARISH, bias.bias)
    }

    @Test
    fun `computeHtfBias returns neutral for flat markets`() {
        val htf = mapOf(Timeframe.H4 to flatCandles(80))
        val bias = engine.computeHtfBias(htf)
        assertEquals(Bias.NEUTRAL, bias.bias)
    }

    @Test
    fun `empty HTF map returns neutral`() {
        val bias = engine.computeHtfBias(emptyMap())
        assertEquals(Bias.NEUTRAL, bias.bias)
        assertNull(bias.flipZonePrice)
    }

    @Test
    fun `validateAlignment passes aligned setup through with boosted confidence`() {
        val setup = makeSetup(Direction.BULLISH, confidence = 70)
        val analysis = makeAnalysis(setup)
        val htfBias = MtfBias(Timeframe.H4, Bias.BULLISH, 99.0, 2, 2)

        val result = engine.validateAlignment(analysis, htfBias)
        val resultSetup = result.setup!!
        assertTrue(resultSetup.confidence > 70)
        assertTrue(resultSetup.confluences.any { it.contains("HTF_ALIGNED") })
        assertEquals(SetupStage.EXECUTE, resultSetup.stage)
    }

    @Test
    fun `validateAlignment blocks misaligned setup`() {
        val setup = makeSetup(Direction.BULLISH, confidence = 80)
        val analysis = makeAnalysis(setup)
        val htfBias = MtfBias(Timeframe.H4, Bias.BEARISH, 110.0, 2, 2)

        val result = engine.validateAlignment(analysis, htfBias)
        val resultSetup = result.setup!!
        assertEquals(SetupStage.LEVEL, resultSetup.stage) // demoted from EXECUTE
        assertTrue(resultSetup.note.contains("BLOCKED"))
        assertTrue(result.narrative.contains("Standing aside"))
    }

    @Test
    fun `validateAlignment with neutral HTF does not filter`() {
        val setup = makeSetup(Direction.BEARISH, confidence = 65)
        val analysis = makeAnalysis(setup)
        val htfBias = MtfBias.neutral(Timeframe.D1)

        val result = engine.validateAlignment(analysis, htfBias)
        assertEquals(SetupStage.EXECUTE, result.setup!!.stage) // unchanged
    }

    private fun makeSetup(direction: Direction, confidence: Int) = TradeProSetup(
        symbol = "ES", direction = direction, stage = SetupStage.EXECUTE,
        entry = 100.0, stopLoss = 97.0, target1 = 104.0, target2 = 108.0, runnerTarget = 116.0,
        riskPoints = 3.0, riskReward = 4.0, confidence = confidence,
        flipZone = null, holdZone = null,
        managementPlan = TradeProManagementPlan(3, 3.0, 4.0, 8.0, 1, 1, 1, 9.0, 0.4286),
        confluences = listOf("FLIP_ZONE"), note = "Test",
    )

    private fun makeAnalysis(setup: TradeProSetup) = TradeProAnalysis(
        symbol = setup.symbol, flipZone = null, holdZones = emptyList(),
        imbalances = emptyList(), absorptions = emptyList(),
        setup = setup, stage = SetupStage.EXECUTE, narrative = "Test analysis.",
    )
}
