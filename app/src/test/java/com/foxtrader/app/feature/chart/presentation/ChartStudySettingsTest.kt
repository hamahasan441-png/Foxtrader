package com.foxtrader.app.feature.chart.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartStudySettingsTest {

    @Test
    fun `EMA sanitizer preserves ascending periods`() {
        val value = EmaStudySettings(fastPeriod = 80, slowPeriod = 20).sanitized()
        assertEquals(20, value.fastPeriod)
        assertEquals(80, value.slowPeriod)
    }

    @Test
    fun `MACD sanitizer always keeps fast below slow`() {
        val value = MacdStudySettings(fastPeriod = 50, slowPeriod = 10, signalPeriod = 0).sanitized()
        assertTrue(value.fastPeriod < value.slowPeriod)
        assertTrue(value.signalPeriod >= 1)
    }

    @Test
    fun `oscillator levels cannot cross neutral zone`() {
        val rsi = RsiStudySettings(overbought = 20.0, oversold = 90.0).sanitized()
        val stoch = StochasticStudySettings(overbought = Double.NaN, oversold = Double.POSITIVE_INFINITY).sanitized()
        assertTrue(rsi.overbought > 50.0)
        assertTrue(rsi.oversold < 50.0)
        assertTrue(stoch.overbought.isFinite())
        assertTrue(stoch.oversold.isFinite())
    }

    @Test
    fun `parabolic SAR settings stay finite and ordered`() {
        val value = ParabolicSarStudySettings(
            accelerationStart = Double.NaN,
            accelerationStep = -5.0,
            accelerationMax = 0.001,
        ).sanitized()
        assertTrue(value.accelerationStart.isFinite())
        assertTrue(value.accelerationStep > 0.0)
        assertTrue(value.accelerationMax >= value.accelerationStart)
    }

    @Test
    fun `RSI order flow pivot separation is internally valid`() {
        val value = RsiOrderFlowStudySettings(
            minPivotSeparation = 50,
            maxPivotSeparation = 2,
            minStrength = 500,
            riskLookback = 0,
            stopBufferRangeMultiple = Double.NaN,
            rewardRisk = Double.POSITIVE_INFINITY,
        ).sanitized()
        assertTrue(value.maxPivotSeparation >= value.minPivotSeparation)
        assertTrue(value.minStrength in 0..100)
        assertTrue(value.riskLookback >= 1)
        assertTrue(value.stopBufferRangeMultiple.isFinite())
        assertTrue(value.rewardRisk.isFinite())
    }
}
