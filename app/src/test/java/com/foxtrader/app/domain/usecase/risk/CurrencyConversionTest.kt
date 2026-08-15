package com.foxtrader.app.domain.usecase.risk

import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the FX-aware live position sizing path:
 *  - [FxConversionRate] direct/inverse construction
 *  - [RiskEngine.calculateLivePositionSize] failing closed when the
 *    quote-currency -> account-currency conversion is missing
 */
class CurrencyConversionTest {

    private fun engine() = RiskEngine(InstrumentTypeResolver())

    private val eurUsdSpec = InstrumentSpec(
        symbol = "EURUSD",
        contractSize = 100_000.0,
        tickSize = 0.00001,
        point = 0.00001,
        minVolume = 0.01,
        maxVolume = 500.0,
        volumeStep = 0.01,
        quoteCurrency = "USD",
        baseCurrency = "EUR",
    )

    @Test
    fun `direct pair builds the expected rate`() {
        val rate = FxConversionRate.fromDirectPair("USD", "EUR", 0.93)!!
        assertEquals(0.93, rate.rate, 1e-9)
        assertEquals(93.0, rate.toAccountCurrency(100.0), 1e-9)
    }

    @Test
    fun `inverse pair inverts the price`() {
        val rate = FxConversionRate.fromInversePair("USD", "EUR", 1.07)!!
        assertEquals(1.0 / 1.07, rate.rate, 1e-9)
    }

    @Test
    fun `same currency converts at 1 to 1`() {
        val rate = FxConversionRate.fromDirectPair("USD", "USD", 1234.0)!!
        assertEquals(1.0, rate.rate, 1e-9)
        assertEquals(50.0, rate.toAccountCurrency(50.0), 1e-9)
    }

    @Test
    fun `blank or non-positive price yields null conversion`() {
        assertNull(FxConversionRate.fromDirectPair("USD", "EUR", 0.0))
        assertNull(FxConversionRate.fromDirectPair("USD", "EUR", Double.NaN))
        assertNull(FxConversionRate.fromDirectPair("USD", "EUR", Double.POSITIVE_INFINITY))
        assertNull(FxConversionRate.fromDirectPair("", "EUR", 1.0))
    }

    @Test
    fun `missing conversion returns null instead of assuming 1`() {
        val size = engine().calculateLivePositionSize(
            spec = eurUsdSpec,
            entryPrice = 1.1000,
            stopLossPrice = 1.0950,
            riskAmountInAccountCurrency = 1000.0,
            quoteToAccountRate = null,
        )
        assertNull("Missing FX conversion must fail closed, never assume 1.0", size)
    }

    @Test
    fun `non-positive conversion returns null`() {
        val size = engine().calculateLivePositionSize(
            spec = eurUsdSpec,
            entryPrice = 1.1000,
            stopLossPrice = 1.0950,
            riskAmountInAccountCurrency = 1000.0,
            quoteToAccountRate = 0.0,
        )
        assertNull(size)
    }

    @Test
    fun `valid conversion sizes the position correctly`() {
        val size = engine().calculateLivePositionSize(
            spec = eurUsdSpec,
            entryPrice = 1.1000,
            stopLossPrice = 1.0950,
            riskAmountInAccountCurrency = 1000.0,
            quoteToAccountRate = 1.0, // account already USD
        )
        assertNotNull(size)
        // 0.0050 * 100_000 * 1.0 = 500 risk per lot => 1000/500 = 2.0 lots
        assertEquals(2.0, size!!, 1e-6)
    }

    @Test
    fun `sized volume is clamped to broker min and step`() {
        val size = engine().calculateLivePositionSize(
            spec = eurUsdSpec,
            entryPrice = 1.1000,
            stopLossPrice = 1.0999,   // tiny stop => huge raw volume
            riskAmountInAccountCurrency = 1000.0,
            quoteToAccountRate = 1.0,
        )
        assertNotNull(size)
        assertTrue("Volume must not exceed broker max", size!! <= eurUsdSpec.maxVolume)
        assertTrue("Volume must respect the broker volume step", eurUsdSpec.isValidVolume(size))
    }

    @Test
    fun `zero stop distance returns null`() {
        val size = engine().calculateLivePositionSize(
            spec = eurUsdSpec,
            entryPrice = 1.1000,
            stopLossPrice = 1.1000,
            riskAmountInAccountCurrency = 1000.0,
            quoteToAccountRate = 1.0,
        )
        assertNull(size)
    }
}
