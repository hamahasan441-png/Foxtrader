package com.foxtrader.app.feature.chart.presentation.components

import com.foxtrader.app.domain.usecase.performance.AdaptiveQualityController
import com.foxtrader.app.domain.usecase.performance.PerformanceProfiler
import com.foxtrader.app.domain.usecase.performance.PerformanceSnapshot
import com.foxtrader.app.domain.usecase.performance.QualityLevel
import com.foxtrader.app.domain.usecase.performance.QualitySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the render-loop bridge (DEVELOPMENT.md §4.14).
 *
 * Verifies the two invariants the chart relies on:
 * 1. A frame always draws with settings decided *before* it started.
 * 2. Snapshots are throttled so the debug HUD cannot recompose per frame.
 */
class ChartPerformanceMonitorTest {

    private lateinit var profiler: PerformanceProfiler
    private lateinit var qualityController: AdaptiveQualityController
    private lateinit var monitor: ChartPerformanceMonitor

    @Before
    fun setUp() {
        profiler = PerformanceProfiler()
        qualityController = AdaptiveQualityController(profiler)
        monitor = ChartPerformanceMonitor(profiler, qualityController)
    }

    /** Simulate a draw pass of roughly [durationMs]. */
    private fun drawFrame(durationMs: Long) {
        monitor.beginFrame()
        busyWait(durationMs)
        monitor.endFrame()
    }

    private fun busyWait(millis: Long) {
        if (millis <= 0) return
        val end = System.nanoTime() + millis * 1_000_000
        @Suppress("ControlFlowWithEmptyBody")
        while (System.nanoTime() < end) { /* spin: Thread.sleep is too coarse */ }
    }

    // ========================================================================
    // DEFAULTS
    // ========================================================================

    @Test
    fun `renders at full quality before any frame is measured`() {
        assertEquals(QualitySettings.FULL, monitor.quality)
        assertEquals(QualityLevel.ULTRA, monitor.qualityLevel)
    }

    @Test
    fun `start activates the profiler at the requested refresh rate`() {
        monitor.start(PerformanceProfiler.TARGET_FPS_120)

        assertTrue(profiler.isActive())
        assertEquals(
            PerformanceProfiler.FRAME_BUDGET_120_MS,
            profiler.frameBudgetMs(),
            0.001f,
        )
    }

    @Test
    fun `start restores full quality after a previous degraded session`() {
        qualityController.forceLevel(QualityLevel.MINIMAL)

        monitor.start(60)

        assertEquals(QualityLevel.ULTRA, monitor.qualityLevel)
        assertEquals(QualitySettings.FULL, monitor.quality)
    }

    @Test
    fun `stop deactivates the profiler`() {
        monitor.start(60)

        monitor.stop()

        assertFalse(profiler.isActive())
    }

    // ========================================================================
    // FRAME BRACKETING
    // ========================================================================

    @Test
    fun `a bracketed frame is recorded`() {
        monitor.start(60)

        drawFrame(1)

        assertEquals(1L, profiler.getSnapshot().totalFrames)
    }

    @Test
    fun `endFrame without beginFrame is ignored`() {
        monitor.start(60)

        monitor.endFrame()

        assertEquals(
            "an unmatched endFrame must not fabricate a frame time",
            0L,
            profiler.getSnapshot().totalFrames,
        )
    }

    @Test
    fun `a double endFrame only records once`() {
        monitor.start(60)

        monitor.beginFrame()
        monitor.endFrame()
        monitor.endFrame()

        assertEquals(1L, profiler.getSnapshot().totalFrames)
    }

    @Test
    fun `slow frames drive quality down`() {
        monitor.start(PerformanceProfiler.TARGET_FPS_60)

        // Well past 150% of a 16.67ms budget → CRITICAL → immediate downgrade.
        repeat(3) { drawFrame(40) }

        assertTrue(
            "expected degradation, still at ${monitor.qualityLevel}",
            monitor.qualityLevel != QualityLevel.ULTRA,
        )
        assertEquals(qualityController.getSettings(monitor.qualityLevel), monitor.quality)
    }

    @Test
    fun `fast frames keep full quality`() {
        monitor.start(PerformanceProfiler.TARGET_FPS_60)

        repeat(20) { drawFrame(0) }

        assertEquals(QualityLevel.ULTRA, monitor.qualityLevel)
        assertEquals(QualitySettings.FULL, monitor.quality)
    }

    // ========================================================================
    // SNAPSHOT THROTTLING
    // ========================================================================

    @Test
    fun `snapshots are throttled to protect the frame budget`() {
        var emissions = 0
        monitor.onSnapshot = { emissions++ }
        monitor.start(60)

        repeat(200) { drawFrame(0) }

        assertTrue("expected at least one snapshot", emissions >= 1)
        assertTrue(
            "200 fast frames must not produce 200 recompositions (got $emissions)",
            emissions < 10,
        )
    }

    @Test
    fun `the first frame publishes a snapshot immediately`() {
        var received: PerformanceSnapshot? = null
        monitor.onSnapshot = { received = it }
        monitor.start(60)

        drawFrame(1)

        assertNotNull("the HUD must not stay blank for a full throttle window", received)
        assertEquals(1L, received?.totalFrames)
    }

    @Test
    fun `no listener means no snapshot work`() {
        monitor.onSnapshot = null
        monitor.start(60)

        drawFrame(1)

        assertNull(monitor.onSnapshot)
        assertEquals(1L, profiler.getSnapshot().totalFrames)
    }

    @Test
    fun `restarting resets the throttle window`() {
        var emissions = 0
        monitor.onSnapshot = { emissions++ }

        monitor.start(60)
        drawFrame(0)
        val afterFirst = emissions

        monitor.start(60)
        drawFrame(0)

        assertEquals(
            "a fresh session must publish immediately, not wait out the old window",
            afterFirst + 1,
            emissions,
        )
    }
}
