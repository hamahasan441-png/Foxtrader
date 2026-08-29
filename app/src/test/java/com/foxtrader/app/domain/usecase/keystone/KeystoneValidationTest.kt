package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneBiasRead
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneDisplacement
import com.foxtrader.app.domain.usecase.keystone.KeystoneLiquiditySource
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneOutcome
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePerformance
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePool
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneSignal
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneSweep
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneValidationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoneValidationTest {

    private val validation = KeystoneValidation()

    /**
     * A trade whose hold window reaches past the end of the data is unresolved,
     * not lost.
     *
     * Without this, the same setup is a small loss on today's chart and a
     * winner on tomorrow's, and every statistic computed from the record moves
     * because the series grew rather than because the market did.
     */
    @Test
    fun `a trade that runs out of series stays open`() {
        val candles = flat(size = 80)
        val signal = signalAt(index = 70, entry = 1.1000, stop = 1.0980, target = 1.1060)
        val config = KeystoneConfig(maxHoldBars = 240)

        val trade = validation.resolve(listOf(signal), candles, config).single()

        assertEquals(KeystoneOutcome.OPEN, trade.outcome)
        assertEquals(0.0, trade.rMultiple, 1e-12)
    }

    @Test
    fun `a trade with room to resolve is resolved`() {
        // Rises steadily, so a long entered at 1.1000 reaches 1.1060.
        val candles = rising(size = 400, step = 0.0004)
        val signal = signalAt(index = 10, entry = candles[10].close, stop = candles[10].close - 0.0020, target = candles[10].close + 0.0060)

        val trade = validation.resolve(listOf(signal), candles, KeystoneConfig()).single()

        assertEquals(KeystoneOutcome.WIN, trade.outcome)
        assertTrue("A winner must be worth something.", trade.rMultiple > 0.0)
    }

    /**
     * Costs are charged, and they are charged against the trade.
     *
     * A 3R target does not pay 3R. If it does, the spread, the commission and
     * the slippage are all being quietly ignored, and every expectancy figure
     * downstream is overstated by the amount that was skipped.
     */
    @Test
    fun `a winner nets less than its nominal reward`() {
        val candles = rising(size = 400, step = 0.0004)
        val entry = candles[10].close
        val risk = 0.0020
        val signal = signalAt(index = 10, entry = entry, stop = entry - risk, target = entry + risk * 3.0)

        val gross = validation.resolve(
            listOf(signal),
            candles,
            KeystoneConfig(assumedSpreadFraction = 0.0, commissionFraction = 0.0, slippageFraction = 0.0),
        ).single()
        val net = validation.resolve(listOf(signal), candles, KeystoneConfig()).single()

        assertEquals(3.0, gross.rMultiple, 0.02)
        assertTrue(
            "Costs did not reduce the result: gross ${gross.rMultiple}, net ${net.rMultiple}.",
            net.rMultiple < gross.rMultiple,
        )
    }

    /**
     * The rule this engine exists to enforce.
     *
     * A record that is right most of the time and loses money must be refused,
     * and a record that is wrong most of the time and makes money must not be
     * refused for that reason. Nothing in [KeystoneValidation.accept] may read
     * the win rate, and these two cases are how that is pinned rather than
     * asserted.
     */
    @Test
    fun `acceptance refuses a high win rate that loses money`() {
        val losing = KeystonePerformance(
            trades = 400,
            winRate = 0.80,
            expectancyR = -0.05,
            profitFactor = 0.9,
            maxDrawdownR = 4.0,
            totalR = -20.0,
            standardDeviationR = 0.7,
        )
        val verdict = validation.accept(losing, reportWith(losing), KeystoneConfig())

        assertFalse(verdict.accepted)
        assertFalse(verdict.expectancyPassed)
        assertFalse(verdict.profitFactorPassed)
        assertTrue(
            "The verdict must say the win rate is not a criterion.",
            verdict.summary.contains("not a criterion"),
        )
    }

    @Test
    fun `acceptance allows a low win rate that makes money`() {
        val winning = KeystonePerformance(
            trades = 400,
            winRate = 0.38,
            expectancyR = 0.22,
            profitFactor = 1.6,
            maxDrawdownR = 6.0,
            totalR = 88.0,
            standardDeviationR = 1.2,
        )
        val verdict = validation.accept(winning, reportWith(winning), KeystoneConfig())

        assertTrue(verdict.summary, verdict.accepted)
        assertTrue(verdict.expectancyPassed)
        assertTrue(verdict.profitFactorPassed)
        assertTrue(verdict.drawdownPassed)
    }

    @Test
    fun `acceptance refuses a sample too small to mean anything`() {
        val small = KeystonePerformance(
            trades = 20,
            winRate = 0.5,
            expectancyR = 0.9,
            profitFactor = 4.0,
            maxDrawdownR = 1.0,
            totalR = 18.0,
            standardDeviationR = 1.0,
        )
        val verdict = validation.accept(small, reportWith(small), KeystoneConfig())

        assertFalse(verdict.accepted)
        assertFalse(verdict.samplePassed)
    }

    // --- helpers -------------------------------------------------------------

    private fun reportWith(performance: KeystonePerformance) = KeystoneValidationReport.EMPTY.copy(
        outOfSample = performance,
        walkForwardExpectancyR = List(5) { performance.expectancyR },
        positiveFolds = if (performance.expectancyR > 0.0) 5 else 0,
        monteCarloDrawdownR95 = performance.maxDrawdownR,
        overfittingProbability = 0.2,
    )

    private fun flat(size: Int): List<Candle> = (0 until size).map {
        Candle(
            timestamp = KeystoneFixtures.START_TIME + it * KeystoneFixtures.M15_MILLIS,
            open = 1.1000, high = 1.1002, low = 1.0998, close = 1.1000, volume = 1_000.0,
        )
    }

    private fun rising(size: Int, step: Double): List<Candle> {
        var price = 1.1000
        return (0 until size).map {
            val open = price
            val close = open + step
            price = close
            Candle(
                timestamp = KeystoneFixtures.START_TIME + it * KeystoneFixtures.M15_MILLIS,
                open = open, high = close + 0.0001, low = open - 0.0001, close = close, volume = 1_000.0,
            )
        }
    }

    private fun signalAt(index: Int, entry: Double, stop: Double, target: Double): KeystoneSignal {
        val pool = KeystonePool(KeystoneLiquiditySource.MAJOR_SWING, stop, false, index - 10, "Swing low")
        val sweep = KeystoneSweep(pool, index - 5, 0L, stop, Direction.BULLISH, 0.5)
        return KeystoneSignal(
            symbol = KeystoneFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            direction = Direction.BULLISH,
            index = index,
            timestamp = KeystoneFixtures.START_TIME + index * KeystoneFixtures.M15_MILLIS,
            entry = entry,
            stopLoss = stop,
            takeProfit = target,
            rewardMultiple = (target - entry) / (entry - stop),
            riskPercent = 0.5,
            sweep = sweep,
            biasRead = KeystoneBiasRead(
                bias = com.foxtrader.app.domain.model.Bias.BULLISH,
                higherTimeframe = Timeframe.H4,
                midTimeframe = Timeframe.H1,
                sessionDirection = Direction.BULLISH,
                reason = "test",
            ),
            divergence = null,
            displacement = KeystoneDisplacement(
                index = index - 2,
                direction = Direction.BULLISH,
                startPrice = entry,
                endPrice = entry,
                bodyToRangeRatio = 0.9,
                atrMultiple = 2.0,
                fairValueGap = null,
                brokenStructureLevel = null,
            ),
            session = KeystoneSession.LONDON,
            entryFromGap = false,
            reasons = emptyList(),
        )
    }
}
