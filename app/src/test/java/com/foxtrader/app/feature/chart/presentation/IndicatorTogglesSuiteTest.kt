package com.foxtrader.app.feature.chart.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorTogglesSuiteTest {

    @Test
    fun `SMC suite enables the complete visual context`() {
        val state = IndicatorToggles().withSmcSuite(true)

        assertTrue(state.smcSuiteActive)
        assertTrue(state.structure)
        assertTrue(state.orderBlocks)
        assertTrue(state.fairValueGaps)
        assertTrue(state.liquidity)
        assertTrue(state.sessions)
    }

    @Test
    fun `LiTX suite enables institutional context without fabricating other engines`() {
        val state = IndicatorToggles().withLitXSuite(true)

        assertTrue(state.litX)
        assertTrue(state.structure)
        assertTrue(state.orderBlocks)
        assertTrue(state.fairValueGaps)
        assertTrue(state.liquidity)
        assertTrue(state.sessions)
        assertFalse(state.lit)
        assertFalse(state.smt)
        assertFalse(state.tradePro)
    }

    @Test
    fun `SMT suite enables structure and liquidity context`() {
        val state = IndicatorToggles().withSmtSuite(true)

        assertTrue(state.smt)
        assertTrue(state.structure)
        assertTrue(state.liquidity)
        assertFalse(state.litX)
    }

    @Test
    fun `institutional suite enables all institutional engines and confluence`() {
        val state = IndicatorToggles().withInstitutionalSuite(true)

        assertTrue(state.institutionalSuiteActive)
        assertTrue(state.confluence)
        assertTrue(state.litX)
        assertTrue(state.lit)
        assertTrue(state.sms)
        assertTrue(state.smt)
        assertTrue(state.tradePro)
    }

    @Test
    fun `strategy history projects on chart by default`() {
        assertTrue(ChartUiState().showSignalHistory)
    }
}
