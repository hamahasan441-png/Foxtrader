package com.foxtrader.app.domain.usecase.calculator

import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [PositionCalculator] against the degenerate inputs a
 * user can type into the calculator sheet.
 *
 * Every field here is user-editable, so "impossible" values are routine: an
 * empty leverage box, a stop dragged onto the entry, a fresh/blown account with
 * a zero balance. None of them may produce Infinity or NaN, because those
 * render straight into the UI as a trade that looks free or risk-free.
 */
class PositionCalculatorDegenerateInputTest {

    private val calculator = PositionCalculator()

    private fun input(
        balance: Double = 10_000.0,
        riskPercent: Double = 1.0,
        entry: Double = 100.0,
        stop: Double = 99.0,
        takeProfit: Double? = 103.0,
        leverage: Double = 100.0,
        type: PositionCalculator.InstrumentType = PositionCalculator.InstrumentType.FOREX_STANDARD,
    ) = PositionCalculator.CalculationInput(
        accountBalance = balance,
        riskPercent = riskPercent,
        entryPrice = entry,
        stopLossPrice = stop,
        takeProfitPrice = takeProfit,
        direction = Direction.BULLISH,
        instrumentType = type,
        leverage = leverage,
    )

    private fun assertFinite(result: PositionCalculator.CalculationResult, label: String) {
        assertTrue("$label positionSize", result.positionSize.isFinite())
        assertTrue("$label riskAmount", result.riskAmount.isFinite())
        assertTrue("$label marginRequired", result.marginRequired.isFinite())
        assertTrue("$label breakEvenPrice", result.breakEvenPrice.isFinite())
        assertTrue("$label maxLossPrice", result.maxLossPrice.isFinite())
        result.rewardAmount?.let { assertTrue("$label rewardAmount", it.isFinite()) }
        result.riskRewardRatio?.let { assertTrue("$label riskRewardRatio", it.isFinite()) }
    }

    @Test
    fun `zero leverage yields a finite unleveraged margin`() {
        // Leverage 0 made `notional / 0.0` = Infinity, which rendered as
        // "Infinity" in the margin field and made the position look free.
        for (type in PositionCalculator.InstrumentType.entries) {
            val result = calculator.calculate(input(leverage = 0.0, type = type))
            assertFinite(result, "leverage=0/$type")

            val unleveraged = calculator.calculate(input(leverage = 1.0, type = type))
            assertEquals(
                "zero leverage should fall back to 1:1 for $type",
                unleveraged.marginRequired,
                result.marginRequired,
                1e-9,
            )
        }
    }

    @Test
    fun `negative leverage does not produce a negative margin`() {
        val result = calculator.calculate(input(leverage = -50.0))
        assertFinite(result, "leverage=-50")
        assertTrue("margin must not be negative", result.marginRequired >= 0.0)
    }

    @Test
    fun `a stop on the entry price stays finite for every instrument`() {
        for (type in PositionCalculator.InstrumentType.entries) {
            val result = calculator.calculate(input(entry = 100.0, stop = 100.0, type = type))
            assertFinite(result, "zero-stop/$type")
            assertTrue("$type size must respect the 0.01 lot floor", result.positionSize >= 0.01)
        }
    }

    @Test
    fun `an all-zero input does not throw or produce NaN`() {
        for (type in PositionCalculator.InstrumentType.entries) {
            val result = calculator.calculate(
                input(balance = 0.0, riskPercent = 0.0, entry = 0.0, stop = 0.0, takeProfit = null, type = type),
            )
            assertFinite(result, "all-zero/$type")
        }
    }

    @Test
    fun `partial levels tolerate mismatched and empty inputs`() {
        // zip() truncates to the shorter list, so these must not throw.
        val mismatched = calculator.calculatePartials(
            entryPrice = 100.0,
            stopLoss = 99.0,
            direction = Direction.BULLISH,
            partials = listOf(0.5),
            rTargets = listOf(1.0, 2.0, 3.0),
        )
        assertEquals(1, mismatched.size)
        assertTrue(mismatched.all { it.price.isFinite() })

        val empty = calculator.calculatePartials(
            entryPrice = 100.0,
            stopLoss = 99.0,
            direction = Direction.BULLISH,
            partials = emptyList(),
            rTargets = emptyList(),
        )
        assertTrue(empty.isEmpty())
    }
}
