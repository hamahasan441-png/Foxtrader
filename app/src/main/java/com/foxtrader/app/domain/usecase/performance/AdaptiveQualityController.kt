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
    private var currentLevel: QualityLevel = QualityLevel.ULTRA
    private var framesAtCurrentLevel = 0
    private val upgradeThreshold = 60  // 1 second of sustained good performance
    private val downgradeThreshold = 5 // 5 bad frames → downgrade immediately

    /** Evaluate performance and return current quality settings. */
    fun evaluate(): QualitySettings {
        framesAtCurrentLevel++

        val tier = profiler.getPerformanceTier()

        when {
            // Downgrade quickly
            tier == PerformanceTier.CRITICAL && currentLevel != QualityLevel.MINIMAL -> {
                currentLevel = QualityLevel.entries[
                    (currentLevel.ordinal + 1).coerceAtMost(QualityLevel.entries.lastIndex)
                ]
                framesAtCurrentLevel = 0
            }
            tier == PerformanceTier.DEGRADED && framesAtCurrentLevel >= downgradeThreshold -> {
                if (currentLevel != QualityLevel.MINIMAL) {
                    currentLevel = QualityLevel.entries[currentLevel.ordinal + 1]
                    framesAtCurrentLevel = 0
                }
            }
            // Upgrade slowly (hysteresis)
            tier == PerformanceTier.EXCELLENT && framesAtCurrentLevel >= upgradeThreshold -> {
                if (currentLevel != QualityLevel.ULTRA) {
                    currentLevel = QualityLevel.entries[currentLevel.ordinal - 1]
                    framesAtCurrentLevel = 0
                }
            }
        }

        return getSettings(currentLevel)
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

    fun getCurrentLevel(): QualityLevel = currentLevel
    fun forceLevel(level: QualityLevel) { currentLevel = level; framesAtCurrentLevel = 0 }
}

/** Quality level tiers. */
enum class QualityLevel {
    ULTRA,    // Everything on, max detail
    HIGH,     // Full features, limited indicator density
    MEDIUM,   // No volume profile
    LOW,      // No sessions, no annotations
    MINIMAL,  // Candles only (emergency)
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
)
