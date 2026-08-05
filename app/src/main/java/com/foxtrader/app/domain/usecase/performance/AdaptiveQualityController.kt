package com.foxtrader.app.domain.usecase.performance

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive Quality Controller — dynamically adjusts chart detail level
 * based on real-time performance metrics from the PerformanceProfiler.
 *
 * When frame budget is exceeded:
 * 1. First: reduce grid line density
 * 2. Second: disable volume profile rendering
 * 3. Third: reduce indicator line quality (fewer points)
 * 4. Fourth: disable session backgrounds
 * 5. Last resort: reduce candle body rendering (skip wicks on tiny bars)
 *
 * When performance recovers, quality is gradually restored.
 * Hysteresis prevents oscillation (need sustained recovery before upgrading).
 */
@Singleton
class AdaptiveQualityController @Inject constructor(
    private val profiler: PerformanceProfiler,
) {
    private val lock = Any()

    private var currentLevel: QualityLevel = QualityLevel.ULTRA

    /**
     * Best quality the controller may auto-restore to (a user "performance
     * mode" cap). Defaults to [QualityLevel.ULTRA] = no cap, so behaviour is
     * unchanged unless a mode is chosen. Degradation *below* the ceiling under
     * load is always permitted; only the upper bound is constrained.
     */
    private var qualityCeiling: QualityLevel = QualityLevel.ULTRA

    /** Consecutive DEGRADED frames — reset by any non-degraded frame. */
    private var consecutiveBadFrames = 0

    /** Consecutive EXCELLENT frames — reset by any non-excellent frame. */
    private var consecutiveGoodFrames = 0

    /**
     * Evaluate performance and return current quality settings.
     *
     * Asymmetric hysteresis (DEVELOPMENT.md §4.14):
     * - **Downgrade fast** — CRITICAL steps down immediately; DEGRADED steps
     *   down after [DOWNGRADE_THRESHOLD] *consecutive* bad frames.
     * - **Upgrade slow** — requires [UPGRADE_THRESHOLD] *consecutive* excellent
     *   frames, so a brief lull can never start an oscillation.
     *
     * `PERF` Called once per frame: O(1), allocation-free, no enum array copies.
     */
    fun evaluate(): QualitySettings = synchronized(lock) {
        when (profiler.getPerformanceTier()) {
            PerformanceTier.CRITICAL -> {
                consecutiveGoodFrames = 0
                consecutiveBadFrames = 0
                stepDown()
            }
            PerformanceTier.DEGRADED -> {
                consecutiveGoodFrames = 0
                consecutiveBadFrames++
                if (consecutiveBadFrames >= DOWNGRADE_THRESHOLD) {
                    consecutiveBadFrames = 0
                    stepDown()
                }
            }
            PerformanceTier.EXCELLENT -> {
                consecutiveBadFrames = 0
                consecutiveGoodFrames++
                if (consecutiveGoodFrames >= UPGRADE_THRESHOLD) {
                    consecutiveGoodFrames = 0
                    stepUp()
                }
            }
            // GOOD / ACCEPTABLE: inside budget but with no headroom to spare —
            // hold the current level and let both counters decay.
            PerformanceTier.GOOD, PerformanceTier.ACCEPTABLE -> {
                consecutiveBadFrames = 0
                consecutiveGoodFrames = 0
            }
        }

        getSettings(currentLevel)
    }

    /** Drop one quality level (no-op at MINIMAL). */
    private fun stepDown() {
        if (currentLevel.ordinal < QualityLevel.entries.lastIndex) {
            currentLevel = QualityLevel.entries[currentLevel.ordinal + 1]
        }
    }

    /** Restore one quality level, never above the active ceiling. */
    private fun stepUp() {
        if (currentLevel.ordinal > qualityCeiling.ordinal) {
            currentLevel = QualityLevel.entries[currentLevel.ordinal - 1]
        }
    }

    /** Get quality settings for a specific level. */
    fun getSettings(level: QualityLevel = currentLevel): QualitySettings = when (level) {
        QualityLevel.ULTRA -> QualitySettings(
            gridLines = true, volumeProfile = true, indicators = true,
            sessions = true, structureAnnotations = true, antiAlias = true,
            maxVisibleIndicatorPoints = Int.MAX_VALUE,
        )
        QualityLevel.HIGH -> QualitySettings(
            gridLines = true, volumeProfile = true, indicators = true,
            sessions = true, structureAnnotations = true, antiAlias = true,
            maxVisibleIndicatorPoints = 500,
        )
        QualityLevel.MEDIUM -> QualitySettings(
            gridLines = true, volumeProfile = false, indicators = true,
            sessions = true, structureAnnotations = true, antiAlias = true,
            maxVisibleIndicatorPoints = 300,
        )
        QualityLevel.LOW -> QualitySettings(
            gridLines = true, volumeProfile = false, indicators = true,
            sessions = false, structureAnnotations = false, antiAlias = false,
            maxVisibleIndicatorPoints = 150,
        )
        QualityLevel.MINIMAL -> QualitySettings(
            gridLines = false, volumeProfile = false, indicators = false,
            sessions = false, structureAnnotations = false, antiAlias = false,
            maxVisibleIndicatorPoints = 0,
        )
    }

    fun getCurrentLevel(): QualityLevel = synchronized(lock) { currentLevel }

    fun forceLevel(level: QualityLevel) = synchronized(lock) {
        // Never allow a level better (lower ordinal) than the active ceiling.
        currentLevel = if (level.ordinal < qualityCeiling.ordinal) qualityCeiling else level
        consecutiveBadFrames = 0
        consecutiveGoodFrames = 0
    }

    /**
     * Cap the best quality the controller may auto-restore to (user performance
     * mode). Clamps the current level up to the ceiling immediately; does not
     * prevent further degradation under load.
     */
    fun setQualityCeiling(ceiling: QualityLevel) = synchronized(lock) {
        qualityCeiling = ceiling
        if (currentLevel.ordinal < ceiling.ordinal) currentLevel = ceiling
    }

    /** Return to the best allowed quality — called when a chart session starts. */
    fun reset() = forceLevel(QualityLevel.ULTRA)

    companion object {
        /** Consecutive excellent frames required before restoring quality (~1s @60fps). */
        const val UPGRADE_THRESHOLD = 60

        /** Consecutive degraded frames tolerated before stepping down. */
        const val DOWNGRADE_THRESHOLD = 5
    }
}

/** Quality level tiers. */
enum class QualityLevel {
    ULTRA,    // Everything on, max detail
    HIGH,     // Full features, limited indicator density
    MEDIUM,   // No volume profile
    LOW,      // No sessions, no annotations
    MINIMAL,  // Candles only (emergency)
}

/**
 * User-facing chart performance preference. Sets the *best* quality the
 * adaptive controller may auto-restore to (a ceiling); the controller can still
 * degrade below it under load. [SMOOTH] imposes no cap — the default, identical
 * to prior always-adaptive behaviour.
 */
enum class PerformanceMode(val displayName: String, val ceiling: QualityLevel) {
    SMOOTH("Smooth (full detail)", QualityLevel.ULTRA),
    BALANCED("Balanced", QualityLevel.HIGH),
    BATTERY_SAVER("Battery saver", QualityLevel.LOW),
}

/** What to render at the current quality level. */
data class QualitySettings(
    val gridLines: Boolean,
    val volumeProfile: Boolean,
    val indicators: Boolean,
    val sessions: Boolean,
    val structureAnnotations: Boolean,
    val antiAlias: Boolean,
    val maxVisibleIndicatorPoints: Int,
) {
    companion object {
        /**
         * Everything on. Used as the renderer default so a chart rendered
         * without an attached controller (previews, tests) is never degraded.
         */
        val FULL = QualitySettings(
            gridLines = true,
            volumeProfile = true,
            indicators = true,
            sessions = true,
            structureAnnotations = true,
            antiAlias = true,
            maxVisibleIndicatorPoints = Int.MAX_VALUE,
        )
    }
}
