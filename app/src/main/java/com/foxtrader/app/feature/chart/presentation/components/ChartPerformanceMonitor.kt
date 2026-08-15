package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.runtime.Stable
import com.foxtrader.app.domain.usecase.performance.AdaptiveQualityController
import com.foxtrader.app.domain.usecase.performance.PerformanceMode
import com.foxtrader.app.domain.usecase.performance.PerformanceProfiler
import com.foxtrader.app.domain.usecase.performance.PerformanceSnapshot
import com.foxtrader.app.domain.usecase.performance.QualityLevel
import com.foxtrader.app.domain.usecase.performance.QualitySettings

/**
 * Bridges the domain [PerformanceProfiler] / [AdaptiveQualityController] into
 * the chart's render pass (DEVELOPMENT.md §4.14).
 *
 * Responsibilities:
 * 1. Bracket every draw pass and feed its duration to the profiler.
 * 2. Ask the quality controller for the settings the *next* frame should use.
 * 3. Publish a throttled [PerformanceSnapshot] for the debug FPS overlay.
 *
 * Why this exists as a plain class rather than Compose state:
 *
 * `PERF` Frame timings arrive at 60–120 Hz. Routing them through
 * `mutableStateOf` or a `StateFlow` would schedule a recomposition per frame
 * and the measurement itself would become the bottleneck. Instead the monitor
 * holds plain fields mutated inside the draw pass, and surfaces a snapshot to
 * the UI only [SNAPSHOT_INTERVAL_NANOS] apart via an explicit callback.
 *
 * `RULE` Quality settings are applied on the frame *after* they are computed.
 * Mutating what a frame draws while it is drawing would tear the layer stack.
 */
@Stable
class ChartPerformanceMonitor(
    private val profiler: PerformanceProfiler,
    private val qualityController: AdaptiveQualityController,
) {
    /** Settings the current frame must honour (computed by the previous frame). */
    var quality: QualitySettings = QualitySettings.FULL
        private set

    /** The active quality tier, surfaced for the debug HUD. */
    val qualityLevel: QualityLevel get() = qualityController.getCurrentLevel()

    private var frameStartNanos = 0L
    private var lastSnapshotNanos = 0L

    /** Called when a fresh snapshot is available (throttled). Set by the UI. */
    var onSnapshot: ((PerformanceSnapshot) -> Unit)? = null

    /** Begin profiling a chart session at the display's refresh rate. */
    fun start(targetFps: Int) {
        profiler.start(targetFps)
        qualityController.reset()
        quality = QualitySettings.FULL
        lastSnapshotNanos = 0L
    }

    /** Stop profiling (chart left composition / went to background). */
    fun stop() {
        profiler.stop()
    }

    /** Apply a user performance mode (quality ceiling) to the controller. */
    fun setPerformanceMode(mode: PerformanceMode) {
        qualityController.setQualityCeiling(mode.ceiling)
    }

    /**
     * The user changed what the chart should draw (indicator toggles, strategy
     * selection). Restore full quality (bounded by the ceiling) and clear the
     * stale frame history so the *new* overlay set is measured from scratch.
     *
     * Why this exists: adaptive quality only re-evaluates inside draw frames,
     * and an idle chart draws no frames. Without this reset, a single heavy
     * recompute could degrade to MINIMAL (indicators skipped entirely) and
     * then stay there indefinitely — the user toggles Bollinger on and simply
     * never sees it. Explicit intent ("show me this overlay") must always get
     * a fresh chance to render; if the device genuinely cannot keep up, the
     * controller will degrade again within a few frames.
     */
    fun onOverlayConfigChanged() {
        profiler.reset()
        qualityController.reset()
        quality = qualityController.getSettings()
    }

    /** Mark the start of a draw pass. */
    fun beginFrame() {
        frameStartNanos = System.nanoTime()
    }

    /**
     * Mark the end of a draw pass: records the duration, re-evaluates adaptive
     * quality for the next frame, and emits a throttled snapshot.
     */
    fun endFrame() {
        if (frameStartNanos == 0L) return
        val now = System.nanoTime()
        profiler.endFrame((now - frameStartNanos) / 1_000_000f)
        frameStartNanos = 0L

        // Re-evaluate quality for the next frame.
        quality = qualityController.evaluate()

        val listener = onSnapshot ?: return
        if (now - lastSnapshotNanos >= SNAPSHOT_INTERVAL_NANOS) {
            lastSnapshotNanos = now
            listener(profiler.getSnapshot())
        }
    }

    companion object {
        /** Publish at most ~4 snapshots/second to the debug overlay. */
        const val SNAPSHOT_INTERVAL_NANOS = 250_000_000L
    }
}
