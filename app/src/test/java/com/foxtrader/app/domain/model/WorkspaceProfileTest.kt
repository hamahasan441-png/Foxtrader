package com.foxtrader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProfileTest {

    @Test
    fun `risk posture maps to a conservative default`() {
        val profile = WorkspaceProfile(risk = RiskPreference.CONSERVATIVE)
        assertEquals(0.5, profile.suggestedRiskPercent(), 0.0)
    }

    @Test
    fun `style drives greeting language only`() {
        assertEquals("scalp", WorkspaceProfile(style = TradingStyle.SCALP).greetingFocus)
        assertEquals("swing", WorkspaceProfile(style = TradingStyle.SWING).greetingFocus)
    }

    @Test
    fun `subscription trial expires`() {
        val live = SubscriptionState(
            plan = SubscriptionPlan.TRIAL,
            trialEndsAtEpochMs = 2_000L,
        )
        assertTrue(live.isPro(nowMs = 1_000L))
        assertEquals("Trial", live.label(nowMs = 1_000L))
        assertEquals("Free", live.label(nowMs = 3_000L))
    }
}
