package com.foxtrader.app.domain.usecase.risk

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.calculator.PositionCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stop distance that is tiny but non-zero must never produce a plausible
 * looking, enormous position.
 *
 * Both sizing paths rounded with `(volume * 100).roundToInt()`, which saturates
 * at Int.MAX_VALUE instead of throwing — turning ~1e9 lots into 21 474 836.47.
 * RiskEngine already guarded the Infinity/NaN case, but a *finite* overflow
 * reached the same rounding step untouched.
 */
class PositionSizeOverflowTest {

    // ------------------------------------------------------------------
    // RiskEngine
    // ------------------------------------------------------------------

    private fun engine(balance: Double = 100_000.0) = RiskEngine(InstrumentTypeResolver()).apply {
        updateConfig(
            RiskConfig(
                accountBalance = balance,
                riskPercentPerTrade = 1.0,
                sizingMethod = PositionSizingMethod.PERCENTAGE_RISK,
            ),
        )
        updateBalance(balance)
    }

    @Test
    fun `a sub-tick stop does not size a giant position`() {
        val result = engine().calculatePositionSize(
            symbol = "EURUSD",
            entryPrice = 1.10000,
            stopLossPrice = 1.10000 - 1e-11,
        )

        assertTrue(
            "volume ${result.volume} must stay within a tradable range",
            result.volume <= RiskEngine.MAX_TRADE_VOLUME,
        )
        assertEquals(RiskEngine.MIN_TRADE_VOLUME, result.volume, 1e-9)
        assertTrue(
            "the trader must be told why, not handed a silently shrunken size",
            result.warnings.any { it.contains("stop", ignoreCase = true) },
        )
    }

    @Test
    fun `a realistic stop still sizes normally`() {
        val result = engine().calculatePositionSize(
            symbol = "EURUSD",
            entryPrice = 1.10000,
            stopLossPrice = 1.09000,
        )

        // 1 % of 100 000 = 1 000 risk over a 0.01 stop on a 100 000 contract.
        assertEquals(1.0, result.volume, 1e-6)
        assertTrue(result.volume in RiskEngine.MIN_TRADE_VOLUME..RiskEngine.MAX_TRADE_VOLUME)
    }

    // ------------------------------------------------------------------
    // PositionCalculator (the on-screen calculator)
    // ------------------------------------------------------------------

    @Test
    fun `calculator clamps a sub-tick stop instead of showing millions of lots`() {
        val result = PositionCalculator().calculate(
            PositionCalculator.CalculationInput(
                accountBalance = 100_000.0,
                riskPercent = 1.0,
                entryPrice = 1.10000,
                stopLossPrice = 1.10000 - 1e-11,
                direction = Direction.BULLISH,
                instrumentType = PositionCalculator.InstrumentType.FOREX_STANDARD,
            ),
        )

        assertTrue(
            "position size ${result.positionSize} must stay tradable",
            result.positionSize <= PositionCalculator.MAX_POSITION_LOTS,
        )
        // Every downstream figure is derived from the size, so they must all
        // remain real numbers rather than inheriting an overflowed one.
        assertTrue(result.marginRequired.isFinite())
        assertTrue(result.breakEvenPrice.isFinite())
        assertTrue(result.maxLossPrice.isFinite())
    }

    @Test
    fun `calculator sizes a realistic stop unchanged`() {
        val result = PositionCalculator().calculate(
            PositionCalculator.CalculationInput(
                accountBalance = 10_000.0,
                riskPercent = 1.0,
                entryPrice = 1.10000,
                stopLossPrice = 1.09500,
                direction = Direction.BULLISH,
                instrumentType = PositionCalculator.InstrumentType.FOREX_STANDARD,
            ),
        )

        // 100 risk over 50 pips at $10/pip per lot = 0.2 lots.
        assertEquals(0.2, result.positionSize, 1e-6)
    }
}
