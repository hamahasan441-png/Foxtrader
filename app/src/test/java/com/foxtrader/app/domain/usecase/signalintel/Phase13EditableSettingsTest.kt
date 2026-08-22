package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.SmsConfig
import com.foxtrader.app.domain.model.SmtConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase13EditableSettingsTest {
    @Test
    fun configsClampUnsafeExtremes() {
        val lit = LitConfig(minConfidence = 999, setupLookback = 1, minRiskReward = 10.0).sanitized()
        assertEquals(95, lit.minConfidence)
        assertEquals(20, lit.setupLookback)
        assertEquals(5.0, lit.minRiskReward, 0.0)

        val smt = SmtConfig(minCorrelation = 5.0, swingLookback = 0, maxTimestampSkewFraction = 9.0).sanitized()
        assertEquals(0.95, smt.minCorrelation, 0.0)
        assertEquals(1, smt.swingLookback)
        assertEquals(0.5, smt.maxTimestampSkewFraction, 0.0)

        val sms = SmsConfig(swingBars = 100, displacementAtrMultiple = 0.1, maxSignalAgeBars = 0).sanitized()
        assertEquals(12, sms.swingBars)
        assertEquals(0.8, sms.displacementAtrMultiple, 0.0)
        assertEquals(1, sms.maxSignalAgeBars)
    }

    @Test
    fun presetsRemainDistinctAndOrderedByTradingHorizon() {
        val litScalp = LitConfig.preset(SignalProfile.SCALPING)
        val litSwing = LitConfig.preset(SignalProfile.SWING)
        assertTrue(litSwing.setupLookback > litScalp.setupLookback)
        assertTrue(litSwing.minRiskReward > litScalp.minRiskReward)

        val smtScalp = SmtConfig.preset(SignalProfile.SCALPING)
        val smtSwing = SmtConfig.preset(SignalProfile.SWING)
        assertTrue(smtSwing.period > smtScalp.period)
        assertTrue(smtSwing.swingLookback > smtScalp.swingLookback)

        val smsScalp = SmsConfig.preset(SignalProfile.SCALPING)
        val smsSwing = SmsConfig.preset(SignalProfile.SWING)
        assertTrue(smsSwing.swingBars > smsScalp.swingBars)
        assertTrue(smsSwing.minConfidence > smsScalp.minConfidence)
    }
}
