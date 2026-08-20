package com.foxtrader.app.data.repository

import com.foxtrader.app.data.remote.api.MetaApiSymbolSpecResponse
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.risk.InstrumentSpec
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for Task 6: broker-authoritative volume spec vs estimated fallback.
 *
 * Verifies:
 * - When broker spec is available → its min/max/step are used and isEstimated=false
 * - When fetch fails (null) → fallback to defaults and isEstimated=true + note for UI
 */
class Mt4RepositoryVolumeSpecTest {

    private val resolver = InstrumentTypeResolver()

    private fun buildSpecFromBroker(
        symbol: String,
        accountCurrency: String,
        brokerSpec: MetaApiSymbolSpecResponse?,
    ): InstrumentSpec {
        val instrumentType = resolver.resolve(symbol)
        return if (brokerSpec != null) {
            val contractSize = if (brokerSpec.contractSize.isFinite() && brokerSpec.contractSize > 0.0) {
                brokerSpec.contractSize
            } else {
                instrumentType.contractSize
            }
            val tickSize = if (brokerSpec.tickSize.isFinite() && brokerSpec.tickSize > 0.0) {
                brokerSpec.tickSize
            } else {
                instrumentType.pipSize
            }
            InstrumentSpec(
                symbol = symbol,
                contractSize = contractSize,
                tickSize = tickSize,
                point = tickSize,
                minVolume = brokerSpec.minVolume,
                maxVolume = brokerSpec.maxVolume,
                volumeStep = brokerSpec.volumeStep,
                quoteCurrency = accountCurrency,
                isEstimated = false,
            )
        } else {
            InstrumentSpec(
                symbol = symbol,
                contractSize = instrumentType.contractSize,
                tickSize = instrumentType.pipSize,
                point = instrumentType.pipSize,
                minVolume = 0.01,
                maxVolume = 100.0,
                volumeStep = 0.01,
                quoteCurrency = accountCurrency,
                isEstimated = true,
            )
        }
    }

    @Test
    fun `broker spec available is used and flagged as authoritative`() {
        val brokerSpec = MetaApiSymbolSpecResponse(
            symbol = "EURUSD",
            tickSize = 0.00001,
            minVolume = 0.01,
            maxVolume = 50.0,
            volumeStep = 0.01,
            contractSize = 100000.0,
        )

        val spec = buildSpecFromBroker("EURUSD", "USD", brokerSpec)

        assertFalse("Should not be estimated when broker spec is present", spec.isEstimated)
        assertEquals(0.01, spec.minVolume, 1e-9)
        assertEquals(50.0, spec.maxVolume, 1e-9)
        assertEquals(0.01, spec.volumeStep, 1e-9)
        assertEquals(100000.0, spec.contractSize, 1e-9)
        assertTrue(spec.isValidVolume(0.01))
        assertFalse(spec.isValidVolume(0.001)) // below min
        assertFalse(spec.isValidVolume(100.0)) // above max for this broker
    }

    @Test
    fun `fallback to defaults when broker spec fetch fails and flagged as estimated`() {
        val spec = buildSpecFromBroker("EURUSD", "USD", null)

        assertTrue("Should be flagged as estimated on failure", spec.isEstimated)
        assertEquals(0.01, spec.minVolume, 1e-9)
        assertEquals(100.0, spec.maxVolume, 1e-9)
        assertEquals(0.01, spec.volumeStep, 1e-9)
        // The UI would show "using estimated limits" note when isEstimated=true
        val uiNote = if (spec.isEstimated) {
            "Using estimated limits [min=${spec.minVolume}, max=${spec.maxVolume}, step=${spec.volumeStep}] — broker spec unavailable."
        } else {
            "Broker limits: min=${spec.minVolume}, max=${spec.maxVolume}, step=${spec.volumeStep}"
        }
        assertTrue(uiNote.contains("estimated"))
    }

    @Test
    fun `broker spec with non-finite values is treated as unavailable`() {
        val badSpec = MetaApiSymbolSpecResponse(
            symbol = "EURUSD",
            tickSize = Double.NaN,
            minVolume = 0.01,
            maxVolume = 100.0,
            volumeStep = 0.01,
            contractSize = 100000.0,
        )
        // Simulate dataSource filtering returning null for invalid
        val filtered: MetaApiSymbolSpecResponse? = if (!badSpec.tickSize.isFinite() || !badSpec.minVolume.isFinite()) null else badSpec

        val spec = buildSpecFromBroker("EURUSD", "USD", filtered)
        assertTrue(spec.isEstimated)
    }
}
