package com.foxtrader.app.domain.usecase.calculator

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size the calculator hands a trader must never quietly risk more than it
 * says: rounding goes down, and when the 0.01 lot minimum forces more risk the
 * real figure is reported and is what the risk gate judges.
 */
class PositionCalculatorRiskTest {

    private val calculator = PositionCalculator()

    private fun eurusd(balance: Double, stop: Double) = calculator.calculate(
        PositionCalculator.CalculationInput(
            accountBalance = balance,
            riskPercent = 1.0,
            entryPrice = 1.1000,
            stopLossPrice = stop,
            direction = Direction.BULLISH,
        ),
    )

    @Test
    fun `size rounds down rather than to nearest`() {
        // $15 budget on a 100-pip stop is 0.015 lots; nearest-rounding gave
        // 0.02 lots = $20 at risk.
        val r = eurusd(balance = 1_500.0, stop = 1.0900)
        assertEquals(0.01, r.positionSize, 1e-12)
        assertEquals(10.0, r.actualRiskAmount, 1e-6)
        assertFalse(r.exceedsRequestedRisk)
    }

    @Test
    fun `an exact size is kept`() {
        val r = eurusd(balance = 100_000.0, stop = 1.0950)
        assertEquals(2.0, r.positionSize, 1e-12)
        assertFalse(r.exceedsRequestedRisk)
    }

    @Test
    fun `minimum lot overshoot is reported`() {
        // $1 budget, 100-pip stop -> 0.001 lots; the 0.01 floor risks $10.
        val r = eurusd(balance = 100.0, stop = 1.0900)
        assertEquals(0.01, r.positionSize, 1e-12)
        assertEquals(1.0, r.riskAmount, 1e-9)
        assertEquals(10.0, r.actualRiskAmount, 1e-6)
        assertTrue(r.exceedsRequestedRisk)
    }

    @Test
    fun `risk gate judges the risk the minimum lot really takes`() {
        val riskEngine = RiskEngine(InstrumentTypeResolver()).apply {
            updateConfig(RiskConfig(accountBalance = 100.0, riskPercentPerTrade = 1.0))
            updateBalance(100.0)
        }
        val sized = RiskAwarePositionCalculator(calculator, InstrumentTypeResolver(), riskEngine).calculate(
            RiskAwarePositionCalculator.Request(
                symbol = "EURUSD",
                direction = Direction.BULLISH,
                entryPrice = 1.1000,
                stopLossPrice = 1.0900,
                riskPercent = 1.0,
                accountBalance = 100.0,
            ),
        ) as RiskAwarePositionCalculator.Outcome.Sized

        assertFalse("$10 at risk must not pass a $1 per-trade cap", sized.allowed)
    }
}
