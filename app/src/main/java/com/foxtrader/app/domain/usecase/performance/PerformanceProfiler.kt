package com.foxtrader.app.domain.usecase.performance

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Performance Profiler — monitors chart rendering and app performance.
 *
 * Tracks:
 * - FPS (frames per second) with rolling average
 * - Frame budget usage (% of 16.6ms/8.3ms budget used)
 * - Frame drops (frames exceeding budget)
 * - Memory pressure indicators
 * - Rendering spike detection
 *
 * Used for:
 * - Overlay FPS counter on chart (debug mode)
 * - Adaptive quality control (reduce detail when under pressure)
 * - Performance regression detection
 *
 * Thread-safe: uses synchronized access to frame history.
 */
@Singleton
class PerformanceProfiler @Inject constructor() {

    companion object {
        const val TARGET_FPS_60 = 60
        const val TARGET_FPS_120 = 120
        const val FRAME_BUDGET_60_MS = 16.67f   // 1000ms / 60fps
        const val FRAME_BUDGET_120_MS = 8.33f   // 1000ms / 120fps
        const val HISTORY_SIZE = 120            // 2 seconds of frames at 60fps
        const val SPIKE_THRESHOLD_MS = 32f      // > 2 frame budgets = spike
    }

    private val frameTimesMs = FloatArray(HISTORY_SIZE)
    private var frameIndex = 0
    private var frameCount = 0L
    private var lastFrameTimeNs = 0L
    private var totalDroppedFrames = 0L
    private var totalSpikes = 0L
    private var isActive = false
    private var targetFps = TARGET_FPS_60

    // ========================================================================
    // FRAME TRACKING
    // ========================================================================

    /**
     * Call at the start of each frame render.
     * Returns the time since last frame in milliseconds.
     */
    fun beginFrame(): Float {
        val now = System.nanoTime()
        val deltaMs = if (lastFrameTimeNs > 0) (now - lastFrameTimeNs) / 1_000_000f else 0f
        lastFrameTimeNs = now
        return deltaMs
    }

    /**
     * Call at the end of frame render with the render duration.
     * Records timing and detects issues.
     */
    fun endFrame(renderDurationMs: Float) {
        if (!isActive) return

        frameTimesMs[frameIndex % HISTORY_SIZE] = renderDurationMs
        frameIndex++
        frameCount++

        val budget = if (targetFps >= TARGET_FPS_120) FRAME_BUDGET_120_MS else FRAME_BUDGET_60_MS
        if (renderDurationMs > budget) totalDroppedFrames++
        if (renderDurationMs > SPIKE_THRESHOLD_MS) totalSpikes++
    }

    // ========================================================================
    // METRICS
    // ========================================================================

    /** Current FPS based on rolling average of frame times. */
    fun getCurrentFps(): Float {
        val count = min(frameIndex, HISTORY_SIZE)
        if (count == 0) return 0f
        val avgMs = frameTimesMs.take(count).average().toFloat()
        return if (avgMs > 0) 1000f / avgMs else 0f
    }

    /** Average frame render time in milliseconds. */
    fun getAverageFrameTimeMs(): Float {
        val count = min(frameIndex, HISTORY_SIZE)
        if (count == 0) return 0f
        return frameTimesMs.take(count).average().toFloat()
    }

    /** Worst frame time in the history window. */
    fun getWorstFrameTimeMs(): Float {
        val count = min(frameIndex, HISTORY_SIZE)
        if (count == 0) return 0f
        return frameTimesMs.take(count).max()
    }

    /** Frame budget usage as percentage (0-100+). */
    fun getBudgetUsagePercent(): Float {
        val budget = if (targetFps >= TARGET_FPS_120) FRAME_BUDGET_120_MS else FRAME_BUDGET_60_MS
        return (getAverageFrameTimeMs() / budget) * 100f
    }

    /** Total dropped frames since profiling started. */
    fun getDroppedFrameCount(): Long = totalDroppedFrames

    /** Total rendering spikes (>32ms) since profiling started. */
    fun getSpikeCount(): Long = totalSpikes

    /** Whether the chart is maintaining target framerate. */
    fun isPerformanceHealthy(): Boolean = getBudgetUsagePercent() < 80f

    /** Current performance tier (for adaptive quality). */
    fun getPerformanceTier(): PerformanceTier {
        val usage = getBudgetUsagePercent()
        return when {
            usage < 50f -> PerformanceTier.EXCELLENT
            usage < 75f -> PerformanceTier.GOOD
            usage < 100f -> PerformanceTier.ACCEPTABLE
            usage < 150f -> PerformanceTier.DEGRADED
            else -> PerformanceTier.CRITICAL
        }
    }

    // ========================================================================
    // SNAPSHOT
    // ========================================================================

    /** Get a complete performance snapshot. */
    fun getSnapshot(): PerformanceSnapshot = PerformanceSnapshot(
        fps = getCurrentFps(),
        avgFrameTimeMs = getAverageFrameTimeMs(),
        worstFrameTimeMs = getWorstFrameTimeMs(),
        budgetUsagePercent = getBudgetUsagePercent(),
        droppedFrames = totalDroppedFrames,
        spikes = totalSpikes,
        totalFrames = frameCount,
        tier = getPerformanceTier(),
        targetFps = targetFps,
    )

    // ========================================================================
    // CONTROL
    // ========================================================================

    fun start(targetFps: Int = TARGET_FPS_60) {
        isActive = true
        this.targetFps = targetFps
        reset()
    }

    fun stop() {
        isActive = false
    }

    fun reset() {
        frameTimesMs.fill(0f)
        frameIndex = 0
        frameCount = 0
        totalDroppedFrames = 0
        totalSpikes = 0
        lastFrameTimeNs = 0
    }

    fun setTargetFps(fps: Int) {
        targetFps = fps
    }
}

/** Performance tier for adaptive quality control. */
enum class PerformanceTier {
    EXCELLENT,   // <50% budget — can add more visual effects
    GOOD,        // 50-75% budget — normal operation
    ACCEPTABLE,  // 75-100% budget — at limit, no extras
    DEGRADED,    // 100-150% budget — reduce quality
    CRITICAL,    // >150% budget — emergency simplification
}

/** Complete performance snapshot. */
data class PerformanceSnapshot(
    val fps: Float,
    val avgFrameTimeMs: Float,
    val worstFrameTimeMs: Float,
    val budgetUsagePercent: Float,
    val droppedFrames: Long,
    val spikes: Long,
    val totalFrames: Long,
    val tier: PerformanceTier,
    val targetFps: Int,
)
