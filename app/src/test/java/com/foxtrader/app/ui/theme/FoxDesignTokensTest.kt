package com.foxtrader.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxDesignTokensTest {

    @Test
    fun `dark tokens cover the semantic roles`() {
        val tokens = FoxDarkTokens
        assertEquals(FoxNeutral0, tokens.background)
        assertEquals(FoxAmber50, tokens.accent)
        assertEquals(FoxBullishText, tokens.bullishText)
        assertEquals(FoxBearishText, tokens.bearishText)
        assertNotEquals(tokens.background, tokens.surface)
        assertNotEquals(tokens.textPrimary, tokens.textMuted)
    }

    @Test
    fun `pnl color is never used without a numeric sign elsewhere`() {
        assertEquals(FoxDarkTokens.bullishText, FoxDarkTokens.pnl(12.0))
        assertEquals(FoxDarkTokens.bearishText, FoxDarkTokens.pnl(-3.0))
        assertEquals(FoxDarkTokens.textSecondary, FoxDarkTokens.pnl(0.0))
    }

    @Test
    fun `spacing scale is monotonic`() {
        val s = FoxSpacing()
        assertTrue(s.xxxs < s.xs)
        assertTrue(s.sm < s.md)
        assertTrue(s.lg < s.xxl)
        assertTrue(s.touch.value >= 44f)
    }

    @Test
    fun `smc visual modes stay distinct`() {
        assertTrue(com.foxtrader.app.domain.model.SmcVisualMode.MINIMAL.intensity < 1f)
        assertEquals(1f, com.foxtrader.app.domain.model.SmcVisualMode.PROFESSIONAL.intensity, 0f)
        assertTrue(com.foxtrader.app.domain.model.SmcVisualMode.FULL.intensity > 1f)
    }
}
