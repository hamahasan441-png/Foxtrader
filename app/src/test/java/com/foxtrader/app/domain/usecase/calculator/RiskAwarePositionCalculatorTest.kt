package com.foxtrader.app.domain.usecase.calculator

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Risk-gated position sizing.
 *
 * Two properties matter most:
 *  1. A "stop" on the wrong side of entry is rejected, not silently absorbed by
 *     `abs(entry - stop)` into a plausible-looking size.
 *  2. A computed size is always accompanied by the risk engine's verdict, so
 *     the app never suggests a trade the order path would refuse.
 */
class RiskAwarePositionCalculatorTest {

    private lateinit var riskEngine: RiskEngine
    private lateinit var calculator: RiskAwarePositionCalculator

    @Before
    fun setUp() {
        riskEngine = RiskEngine(InstrumentTypeResolver())
        riskEngine.updateConfig(
            RiskConfig(accountBalance = 100_000.0, riskPercentPerTrade = 1.0)
        )
        riskEngine.updateBalance(100_000.0)
        calculator = RiskAwarePositionCalculator(
            calculator = PositionCalculator(),
            instrumentTypeResolver = InstrumentTypeResolver(),
            riskEngine = riskEngine,
        )
    }

    private fun request(
        symbol: String = "EURUSD",
        direction: Direction = Direction.BULLISH,
        entry: Double = 1.1000,
        stop: Double = 1.0950,
        target: Double? = 1.1100,
        riskPercent: Double = 1.0,
        balance: Double = 100_000.0,
    ) = RiskAwarePositionCalculator.Request(
        symbol = symbol,
        direction = direction,
        entryPrice = entry,
        stopLossPrice = stop,
        takeProfitPrice = target,
        riskPercent = riskPercent,
        accountBalance = balance,
    )

    // ------------------------------------------------------------- validation

    @Test
    fun `long with stop above entry is rejected`() {
        val outcome = calculator.calculate(
            request(direction = Direction.BULLISH, entry = 1.1000, stop = 1.1050, target = 1.1100)
        )
        val invalid = outcome as RiskAwarePositionCalculator.Outcome.Invalid
        assertTrue(invalid.reasons.any { it.contains("stop must sit below", ignoreCase = true) })
    }

    @Test
    fun `short with stop below entry is rejected`() {
        val outcome = calculator.calculate(
            request(direction = Direction.BEARISH, entry = 1.1000, stop = 1.0950, target = 1.0900)
        )
        val invalid = outcome as RiskAwarePositionCalculator.Outcome.Invalid
        assertTrue(invalid.reasons.any { it.contains("stop must sit above", ignoreCase = true) })
    }

    @Test
    fun `target on the wrong side is rejected`() {
        val outcome = calculator.calculate(
            request(direction = Direction.BULLISH, entry = 1.1000, stop = 1.0950, target = 1.0900)
        )
        val invalid = outcome as RiskAwarePositionCalculator.Outcome.Invalid
        assertTrue(invalid.reasons.any { it.contains("target must sit above", ignoreCase = true) })
    }

    @Test
    fun `stop equal to entry is rejected`() {
        val outcome = calculator.calculate(request(entry = 1.1000, stop = 1.1000, target = null))
        val invalid = outcome as RiskAwarePositionCalculator.Outcome.Invalid
        assertTrue(invalid.reasons.any { it.contains("cannot equal", ignoreCase = true) })
    }

    @Test
    fun `non-positive balance and risk are rejected`() {
        val zeroBalance = calculator.calculate(request(balance = 0.0))
            as RiskAwarePositionCalculator.Outcome.Invalid
        assertTrue(zeroBalance.reasons.any { it.contains("balance", ignoreCase = true) })

        val zeroRisk = calculator.calculate(request(riskPercent = 0.0))
            as RiskAwarePositionCalculator.Outcome.Invalid
        assertTrue(zeroRisk.reasons.any { it.contains("Risk percent", ignoreCase = true) })
    }

    @Test
    fun `absurd risk percent is rejected`() {
        val outcome = calculator.calculate(request(riskPercent = 500.0))
            as RiskAwarePositionCalculator.Outcome.Invalid
        assertTrue(outcome.reasons.any { it.contains("not supported", ignoreCase = true) })
    }

    // ------------------------------------------------------------ calculation

    @Test
    fun `valid long produces a size and an allowed risk check`() {
        val sized = calculator.calculate(request())
            as RiskAwarePositionCalculator.Outcome.Sized

        assertTrue("position size must be positive", sized.result.positionSize > 0.0)
        // 1% of 100k
        assertEquals(1_000.0, sized.result.riskAmount, 1e-6)
        assertTrue("1% risk must pass the 1% per-trade cap", sized.allowed)
    }

    @Test
    fun `risk beyond the per-trade cap is computed but blocked`() {
        // 5% risk against a 1% per-trade limit.
        val sized = calculator.calculate(request(riskPercent = 5.0))
            as RiskAwarePositionCalculator.Outcome.Sized

        assertEquals(5_000.0, sized.result.riskAmount, 1e-6)
        assertFalse("risk engine must refuse 5% when the cap is 1%", sized.allowed)
        assertTrue(sized.riskCheck.reasons.any { it.contains("per-trade limit", ignoreCase = true) })
    }

    @Test
    fun `a trading halt blocks an otherwise valid size`() {
        riskEngine.haltTrading("daily loss limit")

        val sized = calculator.calculate(request())
            as RiskAwarePositionCalculator.Outcome.Sized

        assertFalse("no size may be presented as tradable while halted", sized.allowed)
        assertTrue(sized.riskCheck.reasons.any { it.contains("halted", ignoreCase = true) })
    }

    @Test
    fun `instrument type is resolved from the symbol`() {
        val jpy = calculator.calculate(
            request(symbol = "USDJPY", entry = 155.00, stop = 154.50, target = 156.00)
        ) as RiskAwarePositionCalculator.Outcome.Sized

        assertEquals(PositionCalculator.InstrumentType.FOREX_JPY, jpy.instrumentType)
    }

    @Test
    fun `risk reward ratio reflects the configured target`() {
        // Stop 50 pips, target 100 pips -> 1:2.
        val sized = calculator.calculate(
            request(entry = 1.1000, stop = 1.0950, target = 1.1100)
        ) as RiskAwarePositionCalculator.Outcome.Sized

        assertEquals(2.0, sized.result.riskRewardRatio ?: 0.0, 1e-6)
    }

    @Test
    fun `omitting the target still sizes the position`() {
        val sized = calculator.calculate(request(target = null))
            as RiskAwarePositionCalculator.Outcome.Sized

        assertTrue(sized.result.positionSize > 0.0)
        assertEquals(null, sized.result.riskRewardRatio)
        assertEquals(null, sized.result.rewardAmount)
    }

    @Test
    fun `scale-out plan is produced on the correct side of entry`() {
        val long = calculator.calculate(request())
            as RiskAwarePositionCalculator.Outcome.Sized
        assertTrue("long partials must be above entry", long.partials.all { it.price > 1.1000 })

        val short = calculator.calculate(
            request(direction = Direction.BEARISH, entry = 1.1000, stop = 1.1050, target = 1.0900)
        ) as RiskAwarePositionCalculator.Outcome.Sized
        assertTrue("short partials must be below entry", short.partials.all { it.price < 1.1000 })
    }
}
