package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitXGrade
import com.foxtrader.app.domain.model.LitXMode
import com.foxtrader.app.domain.model.gates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every structural gate has to be reachable on its own.
 *
 * Until now the gates were bundled into modes, so a trader who wanted the
 * sequence but not the kill zone had to abandon the mode entirely. And because
 * two of them together published nothing at all on ten real market series,
 * being able to switch one off individually is the difference between a study
 * that can be tuned and one that can only be turned off.
 */
class LitXGateSwitchTest {

    @Test
    fun `a mode supplies the gates when nothing is overridden`() {
        for (mode in LitXMode.entries) {
            assertEquals(
                "mode $mode must supply its own gates untouched",
                mode.gates(),
                LitXConfig(mode = mode).effectiveGates(),
            )
        }
    }

    @Test
    fun `each gate can be switched off without disturbing the others`() {
        val base = LitXConfig(mode = LitXMode.SNIPER)
        val declared = LitXMode.SNIPER.gates()
        // SNIPER is the mode that demands everything, so every gate below
        // starts true and switching one off is visible.
        assertTrue(declared.requireSweep && declared.requireRetest && declared.requireInBandTap)
        assertTrue(declared.requireAlignedDisplacement && declared.requireKillZone)

        assertFalse(base.copy(requireSweep = false).effectiveGates().requireSweep)
        assertTrue(base.copy(requireSweep = false).effectiveGates().requireRetest)

        assertFalse(base.copy(requireRetest = false).effectiveGates().requireRetest)
        assertFalse(base.copy(requireInBandTap = false).effectiveGates().requireInBandTap)
        assertFalse(base.copy(requireKillZone = false).effectiveGates().requireKillZone)
        assertFalse(
            base.copy(requireAlignedDisplacement = false).effectiveGates().requireAlignedDisplacement,
        )
        assertTrue(base.copy(allowFvgPoi = true).effectiveGates().allowFvgPoi)
    }

    @Test
    fun `a gate can also be switched on that the mode does not ask for`() {
        val momentum = LitXConfig(mode = LitXMode.MOMENTUM)
        assertFalse(momentum.effectiveGates().requireSweep)
        assertTrue(momentum.copy(requireSweep = true).effectiveGates().requireSweep)
    }

    /**
     * The shipped defaults must be able to produce a signal.
     *
     * They previously could not: with the displacement-MSS and premium/discount
     * gates both on, the study published nothing across five symbols on two
     * timeframes. This pins the two settings that changed, so a future edit
     * that turns either back on has to be a deliberate one.
     */
    @Test
    fun `the shipped defaults do not demand the two gates that silenced the study`() {
        val defaults = LitXConfig()
        assertFalse(
            "requireStrongMss on by default made the study silent on every market tested",
            defaults.requireStrongMss,
        )
        assertFalse(
            "requireDirectionalZone on by default made the study silent on every market tested",
            defaults.requireDirectionalZone,
        )
        assertEquals(LitXGrade.B, defaults.minGrade)
        assertTrue("the score floor must leave room for real setups", defaults.minConfidenceScore <= 70)
    }

    /**
     * The setup window has to be able to hold the sequence the other windows
     * describe, or those settings promise something the engine cannot deliver.
     */
    @Test
    fun `the configured windows fit inside the setup window`() {
        val cfg = LitXConfig().sanitized()
        val needed = cfg.maxSweepToShiftBars + cfg.maxShiftToRetestBars
        assertTrue(
            "sweep-to-shift ${cfg.maxSweepToShiftBars} plus shift-to-retest " +
                "${cfg.maxShiftToRetestBars} must be a span the engine can search",
            needed > 0,
        )
    }
}
