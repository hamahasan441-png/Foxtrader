package com.foxtrader.app.domain.usecase.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the frame profiler (DEVELOPMENT.md §4.14, §9.1).
 *
 * The profiler is what every performance claim in the Engineering Bible is
 * measured against, so its arithmetic has to be exactly right — a profiler that
 * under-reports jank is worse than no profiler at all.
 */
class PerformanceProfilerTest {

    private lateinit var profiler: PerformanceProfiler

    @Before
    fun setUp() {
        profiler = PerformanceProfiler()
    }

    private fun record(count: Int, frameMs: Float) {
        repeat(count) { profiler.endFrame(frameMs) }
    }

    // ========================================================================
    // ACTIVATION
    // ========================================================================

    @Test
    fun `frames are ignored until profiling starts`() {
        record(10, 8f)

        assertEquals(0L, profiler.getSnapshot().totalFrames)
        assertFalse(profiler.isActive())
    }

    @Test
    fun `stop freezes frame accounting`() {
        profiler.start(60)
        record(5, 10f)
        profiler.stop()

        record(50, 500f)

        assertEquals(5L, profiler.getSnapshot().totalFrames)
    }

    // ========================================================================
    // AVERAGES / FPS
    // ========================================================================

    @Test
    fun `average frame time reflects recorded frames`() {
        profiler.start(60)

        profiler.endFrame(10f)
        profiler.endFrame(20f)

        assertEquals(15f, profiler.getAverageFrameTimeMs(), 0.001f)
    }

    @Test
    fun `fps is derived from the average frame time`() {
        profiler.start(60)
        record(10, 10f)

        assertEquals(100f, profiler.getCurrentFps(), 0.01f)
    }

    @Test
    fun `metrics are zero before any frame is recorded`() {
        profiler.start(60)

        assertEquals(0f, profiler.getAverageFrameTimeMs(), 0f)
        assertEquals(0f, profiler.getCurrentFps(), 0f)
        assertEquals(0f, profiler.getWorstFrameTimeMs(), 0f)
        assertEquals(0f, profiler.getDroppedFrameRatePercent(), 0f)
    }

    @Test
    fun `the rolling window evicts the oldest samples`() {
        profiler.start(60)

        // Fill the window with slow frames, then completely overwrite it.
        record(PerformanceProfiler.HISTORY_SIZE, 40f)
        record(PerformanceProfiler.HISTORY_SIZE, 5f)

        assertEquals(
            "old samples must fall out of the rolling average",
            5f,
            profiler.getAverageFrameTimeMs(),
            0.001f,
        )
    }

    @Test
    fun `running sum stays accurate across many wraps`() {
        profiler.start(60)

        repeat(PerformanceProfiler.HISTORY_SIZE * 7) { profiler.endFrame(12f) }

        assertEquals(12f, profiler.getAverageFrameTimeMs(), 0.001f)
    }

    @Test
    fun `negative and NaN durations are rejected`() {
        profiler.start(60)
        profiler.endFrame(10f)

        profiler.endFrame(-5f)
        profiler.endFrame(Float.NaN)

        assertEquals(1L, profiler.getSnapshot().totalFrames)
        assertEquals(10f, profiler.getAverageFrameTimeMs(), 0.001f)
    }

    // ========================================================================
    // WORST CASE / PERCENTILES
    // ========================================================================

    @Test
    fun `worst frame time captures the spike`() {
        profiler.start(60)
        record(20, 5f)
        profiler.endFrame(48f)

        assertEquals(48f, profiler.getWorstFrameTimeMs(), 0.001f)
    }

    @Test
    fun `p95 ignores a single outlier but p100 does not`() {
        profiler.start(60)
        record(99, 8f)
        profiler.endFrame(100f)

        assertEquals(8f, profiler.getPercentileFrameTimeMs(95f), 0.001f)
        assertEquals(100f, profiler.getPercentileFrameTimeMs(100f), 0.001f)
    }

    @Test
    fun `p50 is the median of the window`() {
        profiler.start(60)
        record(50, 4f)
        record(50, 12f)

        val median = profiler.getPercentileFrameTimeMs(50f)

        assertTrue("median $median should sit between the two clusters", median in 4f..12f)
    }

    @Test
    fun `percentile input is clamped to a valid range`() {
        profiler.start(60)
        record(10, 7f)

        assertEquals(7f, profiler.getPercentileFrameTimeMs(-50f), 0.001f)
        assertEquals(7f, profiler.getPercentileFrameTimeMs(500f), 0.001f)
    }

    // ========================================================================
    // BUDGETS (§9.1)
    // ========================================================================

    @Test
    fun `budget is 60hz by default and 120hz when targeted`() {
        profiler.start(PerformanceProfiler.TARGET_FPS_60)
        assertEquals(PerformanceProfiler.FRAME_BUDGET_60_MS, profiler.frameBudgetMs(), 0.001f)

        profiler.setTargetFps(PerformanceProfiler.TARGET_FPS_120)
        assertEquals(PerformanceProfiler.FRAME_BUDGET_120_MS, profiler.frameBudgetMs(), 0.001f)
    }

    @Test
    fun `the same frame time costs twice the budget at 120hz`() {
        profiler.start(PerformanceProfiler.TARGET_FPS_60)
        record(10, 8.33f)
        val usageAt60 = profiler.getBudgetUsagePercent()

        profiler.setTargetFps(PerformanceProfiler.TARGET_FPS_120)
        val usageAt120 = profiler.getBudgetUsagePercent()

        assertEquals(50f, usageAt60, 1f)
        assertEquals(100f, usageAt120, 1f)
    }

    @Test
    fun `dropped frames are counted against the active budget`() {
        profiler.start(PerformanceProfiler.TARGET_FPS_60)

        record(8, 5f)   // inside budget
        record(2, 25f)  // over budget

        assertEquals(2L, profiler.getDroppedFrameCount())
        assertEquals(20f, profiler.getDroppedFrameRatePercent(), 0.001f)
    }

    @Test
    fun `spikes are counted separately from dropped frames`() {
        profiler.start(PerformanceProfiler.TARGET_FPS_60)

        record(1, 20f) // over budget, not a spike
        record(1, 40f) // over budget AND a spike

        assertEquals(2L, profiler.getDroppedFrameCount())
        assertEquals(1L, profiler.getSpikeCount())
    }

    // ========================================================================
    // TIERS
    // ========================================================================

    @Test
    fun `tier maps budget usage onto the quality ladder`() {
        val cases = listOf(
            4f to PerformanceTier.EXCELLENT,     // ~24%
            10f to PerformanceTier.GOOD,         // ~60%
            15f to PerformanceTier.ACCEPTABLE,   // ~90%
            20f to PerformanceTier.DEGRADED,     // ~120%
            40f to PerformanceTier.CRITICAL,     // ~240%
        )

        for ((frameMs, expected) in cases) {
            val p = PerformanceProfiler()
            p.start(PerformanceProfiler.TARGET_FPS_60)
            repeat(10) { p.endFrame(frameMs) }
            assertEquals("frame time ${frameMs}ms", expected, p.getPerformanceTier())
        }
    }

    @Test
    fun `health check trips before the budget is fully consumed`() {
        profiler.start(PerformanceProfiler.TARGET_FPS_60)
        record(10, 5f)
        assertTrue(profiler.isPerformanceHealthy())

        profiler.reset()
        record(10, 15f)
        assertFalse(profiler.isPerformanceHealthy())
    }

    // ========================================================================
    // SNAPSHOT / RESET
    // ========================================================================

    @Test
    fun `snapshot exposes a consistent view of the window`() {
        profiler.start(PerformanceProfiler.TARGET_FPS_120)
        record(9, 4f)
        profiler.endFrame(40f)

        val snapshot = profiler.getSnapshot()

        assertEquals(10L, snapshot.totalFrames)
        assertEquals(40f, snapshot.worstFrameTimeMs, 0.001f)
        assertEquals(PerformanceProfiler.TARGET_FPS_120, snapshot.targetFps)
        assertEquals(1L, snapshot.spikes)
        assertEquals(10f, snapshot.droppedFrameRatePercent, 0.001f)
    }

    @Test
    fun `reset clears history without deactivating the profiler`() {
        profiler.start(60)
        record(30, 25f)

        profiler.reset()

        assertEquals(0L, profiler.getSnapshot().totalFrames)
        assertEquals(0L, profiler.getDroppedFrameCount())
        assertEquals(0f, profiler.getAverageFrameTimeMs(), 0f)
        assertTrue("reset must not stop profiling", profiler.isActive())
    }

    @Test
    fun `start resets accumulated history from a previous session`() {
        profiler.start(60)
        record(30, 25f)

        profiler.start(120)

        assertEquals(0L, profiler.getSnapshot().totalFrames)
        assertEquals(PerformanceProfiler.TARGET_FPS_120, profiler.getSnapshot().targetFps)
    }
}
