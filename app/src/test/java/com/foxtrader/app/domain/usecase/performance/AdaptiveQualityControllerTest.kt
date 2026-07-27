package com.foxtrader.app.domain.usecase.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for adaptive quality control (DEVELOPMENT.md §4.14).
 *
 * The contract under test is the asymmetric hysteresis: **downgrade fast,
 * upgrade slow**. Getting this wrong produces visible oscillation (layers
 * flickering on and off), which is worse than simply rendering at low quality.
 */
class AdaptiveQualityControllerTest {

    private lateinit var profiler: PerformanceProfiler
    private lateinit var controller: AdaptiveQualityController

    @Before
    fun setUp() {
        profiler = PerformanceProfiler()
        profiler.start(PerformanceProfiler.TARGET_FPS_60)
        controller = AdaptiveQualityController(profiler)
    }

    /** Drive the profiler into a specific tier, then evaluate [times] frames. */
    private fun runFrames(frameMs: Float, times: Int) {
        repeat(times) {
            profiler.reset()
            repeat(10) { profiler.endFrame(frameMs) }
            controller.evaluate()
        }
    }

    private val excellentMs = 4f    // ~24% of budget
    private val goodMs = 10f        // ~60%
    private val degradedMs = 20f    // ~120%
    private val criticalMs = 40f    // ~240%

    // ========================================================================
    // DEFAULTS
    // ========================================================================

    @Test
    fun `starts at full quality`() {
        assertEquals(QualityLevel.ULTRA, controller.getCurrentLevel())
        assertTrue(controller.getSettings(QualityLevel.ULTRA).volumeProfile)
    }

    // ========================================================================
    // DOWNGRADE PATH
    // ========================================================================

    @Test
    fun `a single critical frame downgrades immediately`() {
        runFrames(criticalMs, times = 1)

        assertEquals(QualityLevel.HIGH, controller.getCurrentLevel())
    }

    @Test
    fun `sustained critical frames walk all the way down to minimal`() {
        runFrames(criticalMs, times = 10)

        assertEquals(QualityLevel.MINIMAL, controller.getCurrentLevel())
    }

    @Test
    fun `degraded frames need the full threshold before downgrading`() {
        runFrames(degradedMs, times = AdaptiveQualityController.DOWNGRADE_THRESHOLD - 1)
        assertEquals("must tolerate a short bad patch", QualityLevel.ULTRA, controller.getCurrentLevel())

        runFrames(degradedMs, times = 1)
        assertEquals(QualityLevel.HIGH, controller.getCurrentLevel())
    }

    @Test
    fun `a recovered frame resets the degraded streak`() {
        runFrames(degradedMs, times = AdaptiveQualityController.DOWNGRADE_THRESHOLD - 1)
        runFrames(goodMs, times = 1) // one healthy frame breaks the streak
        runFrames(degradedMs, times = AdaptiveQualityController.DOWNGRADE_THRESHOLD - 1)

        assertEquals(
            "non-consecutive bad frames must not accumulate into a downgrade",
            QualityLevel.ULTRA,
            controller.getCurrentLevel(),
        )
    }

    @Test
    fun `minimal is the floor`() {
        controller.forceLevel(QualityLevel.MINIMAL)

        runFrames(criticalMs, times = 20)

        assertEquals(QualityLevel.MINIMAL, controller.getCurrentLevel())
    }

    // ========================================================================
    // UPGRADE PATH
    // ========================================================================

    @Test
    fun `upgrade requires sustained excellent performance`() {
        controller.forceLevel(QualityLevel.LOW)

        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD - 1)
        assertEquals("must not upgrade early", QualityLevel.LOW, controller.getCurrentLevel())

        runFrames(excellentMs, times = 1)
        assertEquals(QualityLevel.MEDIUM, controller.getCurrentLevel())
    }

    @Test
    fun `upgrades happen one level at a time`() {
        controller.forceLevel(QualityLevel.MINIMAL)

        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD)
        assertEquals("one threshold buys exactly one level", QualityLevel.LOW, controller.getCurrentLevel())

        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD)
        assertEquals(QualityLevel.MEDIUM, controller.getCurrentLevel())
    }

    @Test
    fun `a single bad frame resets the upgrade streak`() {
        controller.forceLevel(QualityLevel.LOW)

        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD - 1)
        runFrames(degradedMs, times = 1)
        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD - 1)

        assertEquals(QualityLevel.LOW, controller.getCurrentLevel())
    }

    @Test
    fun `ultra is the ceiling`() {
        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD * 3)

        assertEquals(QualityLevel.ULTRA, controller.getCurrentLevel())
    }

    // ========================================================================
    // HYSTERESIS / STABILITY
    // ========================================================================

    @Test
    fun `merely acceptable performance holds the current level`() {
        controller.forceLevel(QualityLevel.MEDIUM)

        // ~90% of budget: inside the frame budget but with no headroom.
        runFrames(15f, times = 200)

        assertEquals(
            "no headroom means no upgrade, but no jank means no downgrade",
            QualityLevel.MEDIUM,
            controller.getCurrentLevel(),
        )
    }

    @Test
    fun `alternating good and bad frames does not oscillate`() {
        repeat(50) {
            runFrames(excellentMs, times = 1)
            runFrames(degradedMs, times = 1)
        }

        assertEquals(
            "neither streak ever completes, so quality must be untouched",
            QualityLevel.ULTRA,
            controller.getCurrentLevel(),
        )
    }

    // ========================================================================
    // SETTINGS LADDER
    // ========================================================================

    @Test
    fun `each level drops progressively more work`() {
        val ultra = controller.getSettings(QualityLevel.ULTRA)
        val medium = controller.getSettings(QualityLevel.MEDIUM)
        val low = controller.getSettings(QualityLevel.LOW)
        val minimal = controller.getSettings(QualityLevel.MINIMAL)

        // MEDIUM drops the volume profile (the most expensive overlay).
        assertTrue(ultra.volumeProfile)
        assertFalse(medium.volumeProfile)

        // LOW additionally drops sessions and structure annotations.
        assertTrue(medium.sessions)
        assertFalse(low.sessions)
        assertFalse(low.structureAnnotations)

        // MINIMAL is candles only.
        assertFalse(minimal.gridLines)
        assertFalse(minimal.indicators)
        assertEquals(0, minimal.maxVisibleIndicatorPoints)
    }

    @Test
    fun `indicator point budget shrinks monotonically`() {
        val budgets = QualityLevel.entries.map { controller.getSettings(it).maxVisibleIndicatorPoints }

        for (i in 1 until budgets.size) {
            assertTrue(
                "level ${QualityLevel.entries[i]} must not allow more points than ${QualityLevel.entries[i - 1]}",
                budgets[i] <= budgets[i - 1],
            )
        }
    }

    @Test
    fun `evaluate returns the settings for the active level`() {
        controller.forceLevel(QualityLevel.LOW)

        val settings = runFramesReturning(goodMs)

        assertEquals(controller.getSettings(QualityLevel.LOW), settings)
    }

    @Test
    fun `reset restores full quality`() {
        controller.forceLevel(QualityLevel.MINIMAL)

        controller.reset()

        assertEquals(QualityLevel.ULTRA, controller.getCurrentLevel())
    }

    @Test
    fun `the FULL preset matches the ULTRA level`() {
        assertEquals(controller.getSettings(QualityLevel.ULTRA), QualitySettings.FULL)
    }

    private fun runFramesReturning(frameMs: Float): QualitySettings {
        profiler.reset()
        repeat(10) { profiler.endFrame(frameMs) }
        return controller.evaluate()
    }
}
