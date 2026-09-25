package com.foxtrader.app.domain.usecase.risk

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The risk a sized position reports must be the risk it actually takes.
 *
 * The order gate ([RiskGatedBrokerExecutor][com.foxtrader.app.domain.usecase.orders.RiskGatedBrokerExecutor])
 * checks `riskAmount` against the per-trade cap and then submits `volume`.
 * When those two disagree, an over-cap order is approved.
 */
class RiskEngineRealizedRiskTest {

    private fun engine(balance: Double, method: PositionSizingMethod = PositionSizingMethod.PERCENTAGE_RISK) =
        RiskEngine(InstrumentTypeResolver()).apply {
            updateConfig(RiskConfig(accountBalance = balance, riskPercentPerTrade = 1.0, sizingMethod = method))
            updateBalance(balance)
        }

    @Test
    fun `minimum lot that overshoots the budget reports its real risk and is refused`() {
        // $1,000 at 1% = $10. Gold, $20 stop, 100 oz contract: raw 0.005 lots.
        // The 0.01 minimum really risks $20 — twice the per-trade cap.
        val engine = engine(1_000.0)
        val sized = engine.calculatePositionSize("XAUUSD", 2_000.0, 1_980.0)

        assertEquals(0.01, sized.volume, 1e-12)
        assertEquals(20.0, sized.riskAmount, 1e-9)
        assertTrue(sized.warnings.any { it.contains("minimum", ignoreCase = true) })
        assertFalse(
            "the gate must see the $20 actually at risk, not the $10 budget",
            engine.canOpenTrade(sized.riskAmount).allowed,
        )
    }

    @Test
    fun `sizing rounds down so the budget is never exceeded`() {
        // $1,500 at 1% = $15; 100-pip EURUSD stop -> raw 0.015 lots. Rounding to
        // nearest gave 0.02 lots = $20 at risk.
        val sized = engine(1_500.0).calculatePositionSize("EURUSD", 1.1000, 1.0900)

        assertEquals(0.01, sized.volume, 1e-12)
        assertTrue("risk ${sized.riskAmount} must not exceed the $15 budget", sized.riskAmount <= 15.0 + 1e-9)
    }

    @Test
    fun `an exact size is not floored below itself by float noise`() {
        val sized = engine(10_000.0).calculatePositionSize("EURUSD", 1.1000, 1.0950)
        assertEquals(0.2, sized.volume, 1e-12)
        assertEquals(100.0, sized.riskAmount, 1e-6)
    }

    @Test
    fun `every budget-based method reports the risk at the order's own stop`() {
        // ATR and volatility sizing measure their own stop distance; the order
        // is still placed at the caller's stop, so that is where risk lies.
        val candles = (0 until 60).map { i ->
            val base = 1.1000 + (i % 5) * 0.0002
            Candle(i * 60_000L, base, base + 0.0006, base - 0.0006, base + 0.0001, 1_000.0)
        }
        for (method in PositionSizingMethod.entries) {
            val sized = engine(10_000.0, method).calculatePositionSize("EURUSD", 1.1000, 1.0900, candles)
            if (sized.riskAmount <= 0.0) continue // Kelly with no edge: refused, see below
            val atStop = abs(1.1000 - 1.0900) * sized.volume * sized.contractSize
            assertEquals("$method", atStop, sized.riskAmount, 1e-6)
        }
    }

    @Test
    fun `kelly with no edge stays non-positive so the gate still refuses it`() {
        val engine = RiskEngine(InstrumentTypeResolver()).apply {
            updateConfig(
                RiskConfig(
                    accountBalance = 100_000.0,
                    sizingMethod = PositionSizingMethod.KELLY,
                    maxConsecutiveLosses = 100,
                    maxDrawdownPercent = 100.0,
                    maxDailyLossPercent = 100.0,
                ),
            )
            updateBalance(100_000.0)
        }
        repeat(5) { engine.recordTrade(10.0, "EURUSD") }
        repeat(10) { engine.recordTrade(-100.0, "EURUSD") }

        val sized = engine.calculatePositionSize("EURUSD", 1.1000, 1.0950)
        assertTrue(sized.riskAmount <= 0.0)
        assertFalse(engine.canOpenTrade(sized.riskAmount).allowed)
    }

    @Test
    fun `break-even trades do not extend a losing streak`() {
        // `win` is pnl > 0, so a scratch used to count as a loss and could halt
        // trading after a run of stops moved to entry.
        val engine = RiskEngine(InstrumentTypeResolver()).apply {
            updateConfig(RiskConfig(accountBalance = 10_000.0, maxConsecutiveLosses = 3))
            updateBalance(10_000.0)
        }
        engine.recordTrade(-10.0, "EURUSD")
        engine.recordTrade(-10.0, "EURUSD")
        engine.recordTrade(0.0, "EURUSD")

        assertEquals(0, engine.getConsecutiveLosses())
        assertFalse(engine.isTradingHalted())
    }
}
