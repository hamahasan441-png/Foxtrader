package com.foxtrader.app.domain.usecase.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Unit tests for adaptive quality control. */
class AdaptiveQualityControllerTest {

    private lateinit var profiler: PerformanceProfiler
    private lateinit var controller: AdaptiveQualityController

    @Before
    fun setUp() {
        profiler = PerformanceProfiler()
        profiler.start(PerformanceProfiler.TARGET_FPS_60)
        controller = AdaptiveQualityController(profiler)
    }

    private fun runFrames(frameMs: Float, times: Int) {
        repeat(times) {
            profiler.reset()
            repeat(10) { profiler.endFrame(frameMs) }
            controller.evaluate()
        }
    }

    private val excellentMs = 4f
    private val goodMs = 10f
    private val degradedMs = 20f
    private val criticalMs = 40f

    @Test
    fun `starts at full quality`() {
        assertEquals(QualityLevel.ULTRA, controller.getCurrentLevel())
        assertTrue(controller.getSettings(QualityLevel.ULTRA).volumeProfile)
    }

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
        runFrames(goodMs, times = 1)
        runFrames(degradedMs, times = AdaptiveQualityController.DOWNGRADE_THRESHOLD - 1)
        assertEquals(QualityLevel.ULTRA, controller.getCurrentLevel())
    }

    @Test
    fun `minimal is the floor`() {
        controller.forceLevel(QualityLevel.MINIMAL)
        runFrames(criticalMs, times = 20)
        assertEquals(QualityLevel.MINIMAL, controller.getCurrentLevel())
    }

    @Test
    fun `upgrade requires sustained excellent performance`() {
        controller.forceLevel(QualityLevel.LOW)
        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD - 1)
        assertEquals(QualityLevel.LOW, controller.getCurrentLevel())
        runFrames(excellentMs, times = 1)
        assertEquals(QualityLevel.MEDIUM, controller.getCurrentLevel())
    }

    @Test
    fun `upgrades happen one level at a time`() {
        controller.forceLevel(QualityLevel.MINIMAL)
        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD)
        assertEquals(QualityLevel.LOW, controller.getCurrentLevel())
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

    @Test
    fun `merely acceptable performance holds the current level`() {
        controller.forceLevel(QualityLevel.MEDIUM)
        runFrames(15f, times = 200)
        assertEquals(QualityLevel.MEDIUM, controller.getCurrentLevel())
    }

    @Test
    fun `alternating good and bad frames does not oscillate`() {
        repeat(50) {
            runFrames(excellentMs, times = 1)
            runFrames(degradedMs, times = 1)
        }
        assertEquals(QualityLevel.ULTRA, controller.getCurrentLevel())
    }

    @Test
    fun `degradation reduces decoration and density but never hides selected studies`() {
        for (level in QualityLevel.entries) {
            val settings = controller.getSettings(level)
            assertTrue("indicators must stay visible at $level", settings.indicators)
            assertTrue("profiles must stay visible at $level", settings.volumeProfile)
            assertTrue("sessions must stay visible at $level", settings.sessions)
            assertTrue("structure must stay visible at $level", settings.structureAnnotations)
        }

        val minimal = controller.getSettings(QualityLevel.MINIMAL)
        assertTrue(!minimal.gridLines)
        assertTrue(!minimal.antiAlias)
        assertEquals(AdaptiveQualityController.MINIMAL_INDICATOR_POINT_BUDGET, minimal.maxVisibleIndicatorPoints)
    }

    @Test
    fun `indicator point budget shrinks monotonically`() {
        val budgets = QualityLevel.entries.map { controller.getSettings(it).maxVisibleIndicatorPoints }
        for (i in 1 until budgets.size) {
            assertTrue(budgets[i] <= budgets[i - 1])
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

    @Test
    fun `setting a ceiling clamps the current level up to it`() {
        assertEquals(QualityLevel.ULTRA, controller.getCurrentLevel())
        controller.setQualityCeiling(QualityLevel.HIGH)
        assertEquals(QualityLevel.HIGH, controller.getCurrentLevel())
    }

    @Test
    fun `forceLevel cannot exceed the ceiling`() {
        controller.setQualityCeiling(QualityLevel.MEDIUM)
        controller.forceLevel(QualityLevel.ULTRA)
        assertEquals(QualityLevel.MEDIUM, controller.getCurrentLevel())
    }

    @Test
    fun `auto-restore stops at the ceiling`() {
        controller.setQualityCeiling(QualityLevel.HIGH)
        controller.forceLevel(QualityLevel.MINIMAL)
        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD * 6)
        assertEquals(QualityLevel.HIGH, controller.getCurrentLevel())
    }

    @Test
    fun `degradation below the ceiling is still allowed under load`() {
        controller.setQualityCeiling(QualityLevel.HIGH)
        runFrames(criticalMs, times = 10)
        assertEquals(QualityLevel.MINIMAL, controller.getCurrentLevel())
    }

    @Test
    fun `default ceiling imposes no cap`() {
        controller.forceLevel(QualityLevel.MINIMAL)
        runFrames(excellentMs, times = AdaptiveQualityController.UPGRADE_THRESHOLD * 6)
        assertEquals(QualityLevel.ULTRA, controller.getCurrentLevel())
    }

    @Test
    fun `performance modes map to the expected ceilings`() {
        assertEquals(QualityLevel.ULTRA, PerformanceMode.SMOOTH.ceiling)
        assertEquals(QualityLevel.HIGH, PerformanceMode.BALANCED.ceiling)
        assertEquals(QualityLevel.LOW, PerformanceMode.BATTERY_SAVER.ceiling)
    }

    private fun runFramesReturning(frameMs: Float): QualitySettings {
        profiler.reset()
        repeat(10) { profiler.endFrame(frameMs) }
        return controller.evaluate()
    }
}
