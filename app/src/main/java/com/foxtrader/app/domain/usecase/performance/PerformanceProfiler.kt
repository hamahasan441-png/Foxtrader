package com.foxtrader.app.domain.usecase.performance

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

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

    private val lock = Any()

    private val frameTimesMs = FloatArray(HISTORY_SIZE)

    /** Scratch buffer for percentile sorting — avoids per-query allocation. */
    private val sortScratch = FloatArray(HISTORY_SIZE)

    /** Write cursor into the ring buffer, always in `[0, HISTORY_SIZE)`. */
    private var cursor = 0

    /** Number of populated slots, saturating at [HISTORY_SIZE]. */
    private var windowCount = 0

    private var frameCount = 0L
    private var lastFrameTimeNs = 0L

    /** Running sum of the retained window — makes the average O(1). */
    private var windowSumMs = 0f

    private var totalDroppedFrames = 0L
    private var totalSpikes = 0L

    @Volatile
    private var isActive = false

    @Volatile
    private var targetFps = TARGET_FPS_60

    // ========================================================================
    // FRAME TRACKING
    // ========================================================================

    /**
     * Call at the start of each frame render.
     * Returns the time since last frame in milliseconds.
     */
    fun beginFrame(): Float = synchronized(lock) {
        val now = System.nanoTime()
        val deltaMs = if (lastFrameTimeNs > 0) (now - lastFrameTimeNs) / 1_000_000f else 0f
        lastFrameTimeNs = now
        deltaMs
    }

    /**
     * Call at the end of frame render with the render duration.
     * Records timing and detects issues.
     *
     * `PERF` O(1) and allocation-free — this runs on the render path for every
     * single frame, so it must never sort, copy, or box.
     */
    fun endFrame(renderDurationMs: Float) {
        if (!isActive) return
        if (renderDurationMs.isNaN() || renderDurationMs < 0f) return

        synchronized(lock) {
            // Maintain the running sum by subtracting the sample being evicted.
            // An explicit cursor (rather than `frameCount % HISTORY_SIZE`) keeps
            // the index bounded forever — a long-lived session must never be
            // able to overflow it into a negative array index.
            windowSumMs += renderDurationMs - frameTimesMs[cursor]
            frameTimesMs[cursor] = renderDurationMs
            cursor = (cursor + 1) % HISTORY_SIZE
            if (windowCount < HISTORY_SIZE) windowCount++
            frameCount++

            val budget = frameBudgetMs()
            if (renderDurationMs > budget) totalDroppedFrames++
            if (renderDurationMs > SPIKE_THRESHOLD_MS) totalSpikes++
        }
    }

    /** The frame budget in ms for the currently configured target refresh rate. */
    fun frameBudgetMs(): Float =
        if (targetFps >= TARGET_FPS_120) FRAME_BUDGET_120_MS else FRAME_BUDGET_60_MS

    // ========================================================================
    // METRICS
    // ========================================================================

    /** Current FPS based on rolling average of frame times. */
    fun getCurrentFps(): Float {
        val avgMs = getAverageFrameTimeMs()
        return if (avgMs > 0f) 1000f / avgMs else 0f
    }

    /**
     * Average frame render time in milliseconds over the rolling window.
     *
     * `PERF` O(1) via the maintained running sum — no copying or boxing.
     */
    fun getAverageFrameTimeMs(): Float = synchronized(lock) {
        if (windowCount == 0) 0f else windowSumMs / windowCount
    }

    /** Worst frame time in the history window. */
    fun getWorstFrameTimeMs(): Float = synchronized(lock) {
        var worst = 0f
        for (i in 0 until windowCount) {
            if (frameTimesMs[i] > worst) worst = frameTimesMs[i]
        }
        worst
    }

    /**
     * Frame time at the given percentile (0..100) of the rolling window.
     *
     * The p95/p99 tail is what users actually perceive as jank — a healthy
     * average with a bad p99 still feels broken, so [DEVELOPMENT.md §9.1]
     * budgets are validated against percentiles, not just the mean.
     */
    fun getPercentileFrameTimeMs(percentile: Float): Float = synchronized(lock) {
        if (windowCount == 0) return@synchronized 0f
        System.arraycopy(frameTimesMs, 0, sortScratch, 0, windowCount)
        java.util.Arrays.sort(sortScratch, 0, windowCount)
        val rank = ((percentile.coerceIn(0f, 100f) / 100f) * (windowCount - 1)).roundToInt()
        sortScratch[rank.coerceIn(0, windowCount - 1)]
    }

    /** Frame budget usage as percentage (0-100+). */
    fun getBudgetUsagePercent(): Float = (getAverageFrameTimeMs() / frameBudgetMs()) * 100f

    /** Percentage of frames in the window that exceeded the budget (0-100). */
    fun getDroppedFrameRatePercent(): Float = synchronized(lock) {
        if (windowCount == 0) return@synchronized 0f
        val budget = frameBudgetMs()
        var over = 0
        for (i in 0 until windowCount) {
            if (frameTimesMs[i] > budget) over++
        }
        (over.toFloat() / windowCount) * 100f
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
        p95FrameTimeMs = getPercentileFrameTimeMs(95f),
        budgetUsagePercent = getBudgetUsagePercent(),
        droppedFrameRatePercent = getDroppedFrameRatePercent(),
        droppedFrames = synchronized(lock) { totalDroppedFrames },
        spikes = synchronized(lock) { totalSpikes },
        totalFrames = synchronized(lock) { frameCount },
        tier = getPerformanceTier(),
        targetFps = targetFps,
    )

    // ========================================================================
    // CONTROL
    // ========================================================================

    fun start(targetFps: Int = TARGET_FPS_60) {
        this.targetFps = targetFps
        reset()
        isActive = true
    }

    fun stop() {
        isActive = false
    }

    fun isActive(): Boolean = isActive

    fun reset() = synchronized(lock) {
        frameTimesMs.fill(0f)
        windowSumMs = 0f
        cursor = 0
        windowCount = 0
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
    val p95FrameTimeMs: Float,
    val budgetUsagePercent: Float,
    val droppedFrameRatePercent: Float,
    val droppedFrames: Long,
    val spikes: Long,
    val totalFrames: Long,
    val tier: PerformanceTier,
    val targetFps: Int,
)
